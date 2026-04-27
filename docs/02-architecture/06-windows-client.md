# 02.06 — Cliente Windows con Wintun

## Por qué Windows

El cliente Android resuelve el caso del segundo teléfono o tablet. Para que la promesa de Gravital Share sea completa, debe haber cliente para PC. La PC del usuario suele ser donde el tráfico es más sensible (trabajo remoto, archivos grandes, descargas) y donde el operador móvil no tiene VPN nativa.

## Decisión: Wintun, no TAP

| Criterio | TAP-Windows | **Wintun** |
|---|---|---|
| Capa OSI | 2 (Ethernet) | 3 (IP puro) |
| Overhead | Encabezado Ethernet por paquete + ARP/DHCP simulados | Mínimo |
| Driver | Heredado de OpenVPN | Mantenido por WireGuard |
| Firma | Antiguo, problemas con Windows 10/11 | Firmado por Microsoft, estable |
| Throughput | Cuello de botella conocido | Elimina overhead, alto throughput |
| Bridging | Sí | No (irrelevante para nosotros) |

Conclusión: **Wintun**.

## Arquitectura del cliente Windows

```
┌────────────────────────────────────────────────────────┐
│   Aplicación de bandeja (system tray)                  │
│   — Tauri (web tech) o WinUI 3 (decisión pendiente)    │
└─────────────────┬──────────────────────────────────────┘
                  │ IPC (named pipe)
┌─────────────────▼──────────────────────────────────────┐
│   Servicio NT "Gravital Share Service"                 │
│   ────────────────────────────────────                 │
│   gravital-engine.exe (proceso de servicio)            │
│   ├── WintunReader / WintunWriter                      │
│   ├── UserspaceStack (smoltcp)                         │
│   ├── SocksClient                                      │
│   ├── DnsInterceptor                                   │
│   └── Telemetry                                        │
└─────────────────┬──────────────────────────────────────┘
                  │ wintun.dll API
┌─────────────────▼──────────────────────────────────────┐
│   Adaptador virtual Wintun "gravital0"                 │
│   ────────────────────────────────────                 │
│   IP virtual: 10.42.0.2/32                             │
│   Rutas: 0.0.0.0/0, ::/0 (default)                     │
└────────────────────────────────────────────────────────┘
```

## Por qué Servicio NT y no app de usuario

- Para manipular tabla de enrutamiento se necesitan privilegios elevados.
- Para instalar/abrir adaptador Wintun se necesitan privilegios.
- Si el usuario cierra sesión en Windows, el servicio sigue protegiendo (si así lo configura).
- Modelo de seguridad limpio: la app de bandeja es no privilegiada y solo habla por IPC con el servicio.

## Capa FFI: el mismo `gravital-engine`

El motor Rust **es el mismo** que usa Android. Cambia el adaptador y la pieza FFI:

- En Android: JNI + tunFd entregado por `VpnService`.
- En Windows: cdylib estándar + adaptador Wintun.

```rust
// engine/crates/gravital-tun/src/lib.rs
#[cfg(target_os = "android")]
mod android;
#[cfg(target_os = "windows")]
mod windows;
#[cfg(target_os = "linux")]
mod linux;

#[cfg(target_os = "android")]
pub use android::AndroidTunDevice as TunDevice;
#[cfg(target_os = "windows")]
pub use windows::WintunDevice as TunDevice;
```

El resto del motor es idéntico.

## Wintun: API mínima necesaria

Usaremos el crate `wintun = "0.5"` (binding Rust mantenido). Operaciones:

```rust
use wintun::Adapter;
use std::sync::Arc;

let adapter = Arc::new(
    Adapter::create("Gravital Share", "gravital0", None)?
);

let session = Arc::new(adapter.start_session(wintun::MAX_RING_CAPACITY)?);

// Loop de lectura
let r_session = session.clone();
std::thread::spawn(move || loop {
    match r_session.receive_blocking() {
        Ok(packet) => {
            let bytes = packet.bytes();
            // entregar al stack
        }
        Err(e) => { /* handle */ }
    }
});

// Loop de escritura
let w_session = session.clone();
let mut packet = w_session.allocate_send_packet(payload.len() as u16)?;
packet.bytes_mut().copy_from_slice(&payload);
w_session.send_packet(packet);
```

**Notas**:
- Wintun ya gestiona el ring buffer.
- `receive_blocking` es bloqueante; integramos a tokio con `spawn_blocking` o un `mpsc` puente.
- `wintun.dll` debe distribuirse junto al instalador. Está firmado por Microsoft.

## Manipulación de la tabla de rutas

Una vez creada la interfaz `gravital0` con IP `10.42.0.2/32`, hay que enrutar todo el tráfico hacia ella **excepto** el que va al hotspot Wi-Fi del anfitrión.

```rust
// pseudo, usando crate `windows-sys` o invocando netsh/route por proceso
fn install_routes(host_proxy_ip: Ipv4Addr) -> Result<()> {
    // 1. Ruta default por gravital0
    run_cmd("route", &["add", "0.0.0.0", "mask", "0.0.0.0", "10.42.0.1", "metric", "1"])?;
    
    // 2. Excepción: el proxy del anfitrión NO va por gravital0
    let gw = current_default_gateway()?;
    run_cmd("route", &["add", &host_proxy_ip.to_string(), "mask", "255.255.255.255", &gw.to_string(), "metric", "1"])?;
    
    Ok(())
}
```

**Importante**: guardar las rutas que añadimos para poder revertirlas en `shutdown`. Si el servicio crashea sin limpiar, la PC del usuario queda sin internet hasta reiniciar. Política: el servicio implementa un *watchdog* que, si detecta que el motor murió, limpia las rutas antes de reportar el fallo.

## Configuración de DNS en Windows

```rust
// Powershell o WMI; preferimos WMI por estabilidad
fn set_dns_for_adapter(adapter_name: &str, dns: Ipv4Addr) -> Result<()> {
    // SetDNSServerSearchOrder en Win32_NetworkAdapterConfiguration
    // ...
}
```

Default: `1.1.1.1`, configurable a `8.8.8.8`, `9.9.9.9` o un resolver privado del anfitrión.

## Anti-fuga DNS en Windows

Windows tiene un comportamiento llamado "Smart Multi-Homed Name Resolution" que envía consultas DNS en paralelo a **todos** los DNS de **todas** las interfaces. Esto es la principal causa de DNS leaks en clientes VPN para Windows.

Mitigación:
1. Deshabilitar Smart Multi-Homed Name Resolution vía Group Policy (registro):
   `HKLM\Software\Policies\Microsoft\Windows NT\DNSClient\DisableSmartNameResolution = 1`
2. Configurar la métrica de la interfaz Wintun como la más baja (preferida sobre cualquier otra).
3. Bloquear con `WFP` (Windows Filtering Platform) cualquier UDP/53 saliente que no provenga de nuestro adaptador.

La opción 3 es la más robusta. Es trabajo no trivial pero es la **diferencia clave** vs implementaciones laxas de la competencia.

## Instalador

- **Tecnología**: WiX Toolset (MSI nativo) o NSIS si necesitamos algo más liviano. Decisión preferida: **WiX**.
- **Componentes instalados**:
  - `C:\Program Files\Gravital\Share\gravital-engine.exe`
  - `C:\Program Files\Gravital\Share\wintun.dll`
  - `C:\Program Files\Gravital\Share\gravital-tray.exe`
  - Servicio NT registrado.
  - Acceso directo en el menú de inicio.
- **Firma de código**: certificado EV Code Signing. Costo y proceso documentado en `docs/04-engineering/03-cicd.md`.
- **Auto-update**: omitido en MVP. Se añade post-lanzamiento con Squirrel.Windows o solución propia.

## Aplicación de bandeja

### Opción A: Tauri (preferida tentativa)

- Stack: Rust + WebView2.
- Comparte código con potenciales clientes Linux/Mac futuros.
- Bundle pequeño (~10 MB).
- UI con la misma stack que dashboards web internos.

### Opción B: WinUI 3

- Más nativo, mejor integración con Win11.
- Curva más alta.

**Decisión a tomar antes de escribir una sola línea**: con qué stack arrancar. Recomendación tentativa: Tauri por reuso. La decisión final cabe en el roadmap del cliente Windows.

## Tray UI mínima

- Icono en bandeja: gris (idle), verde (conectado), naranja (reconectando), rojo (error).
- Click derecho:
  - Estado actual (Connected/Idle/...)
  - Throughput (subir/bajar)
  - Cambiar perfil (si hay varios anfitriones guardados)
  - Configuración…
  - Salir
- Doble click: abre la ventana principal con dashboard completo.

## IPC entre tray y servicio

Named pipe (`\\.\pipe\gravital-share`).

Protocolo: JSON-RPC 2.0 sobre la pipe. Métodos:

| Método | Argumentos | Retorno |
|---|---|---|
| `session.start` | `{config}` | `{ok: true}` |
| `session.stop` | `{}` | `{ok: true}` |
| `session.state` | `{}` | `{state, mode, since, peer, throughput}` |
| `session.stats` | `{}` | `{bytes_in, bytes_out, conns_active, ...}` |
| `config.set` | `{config}` | `{ok: true}` |
| `config.get` | `{}` | `{config}` |
| `events.subscribe` | `{}` | streaming de eventos |

## Pruebas

- Windows 10 21H2, 22H2.
- Windows 11 22H2, 23H2, 24H2.
- Adaptadores Wi-Fi típicos (Intel AX2xx, Realtek RTL88xx, Killer/Qualcomm).
- Operadores LATAM con tethering desde Android: misma matriz que en `03-control-plane-kotlin.md`.

## Métricas Windows-específicas

- Tiempo desde "click conectar" hasta "Connected": objetivo < 3s.
- Sobrecarga vs conexión directa: objetivo < 8% en throughput, < 5ms en RTT.
- Memoria del servicio en idle: < 25 MB.
- Memoria del tray: < 60 MB (si Tauri).
