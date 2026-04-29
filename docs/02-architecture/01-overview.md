# 02.01 — Vista General de Arquitectura

## Principio rector

> **Data plane en Rust. Control plane en Kotlin/Compose. Comunicación por una superficie FFI mínima y bien tipada.**

Esta separación no es un capricho estético. Es la única manera de que el motor de red sea auditable de forma independiente, portable a otras plataformas (Linux desktop, eventualmente iOS si Apple lo permite), y que la app Android no se ahogue intentando hacer trabajo de red en la VM de Java.

## Diagrama de capas

```
┌─────────────────────────────────────────────────────────────────┐
│                  CAPA G — OBSERVABILIDAD                         │
│   Logs JSON estructurados · Traces OTel · Endpoint MCP local    │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│                  CAPA F — UI (Jetpack Compose)                   │
│   Pantallas · Componentes · Sistema de diseño Gravital          │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│                  CAPA E — CONTROL PLANE (Kotlin)                 │
│   ViewModels · SessionManager · VpnService Android              │
│   Permisos · Notificaciones · Lifecycle                         │
└─────────────────────────────────────────────────────────────────┘
                       ↕ FFI (JNI / cdylib)
┌─────────────────────────────────────────────────────────────────┐
│                  CAPA D — DATA PLANE (Rust)                      │
│   gravital-engine (orquestador)                                 │
│   gravital-tun     │  gravital-socks  │  gravital-http          │
│   gravital-stack   │  gravital-udpgw  │  gravital-dns           │
│   gravital-proto   (parsers IP/TCP/UDP/ICMP)                    │
└─────────────────────────────────────────────────────────────────┘
                       ↕ system calls (sockets, FD TUN)
┌─────────────────────────────────────────────────────────────────┐
│                  CAPA C — KERNEL ANDROID / WINDOWS               │
│   Interfaces virtuales (tun0 · Wintun) · Sockets nativos        │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│                  CAPA B — TRANSPORTE FÍSICO                      │
│   wlan0 · rmnet0 (celular) · Ethernet                           │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│                  CAPA A — RED EXTERNA / VPN ANFITRIONA           │
│   Túnel cifrado existente (SocksIP, WireGuard, OpenVPN, etc.)   │
└─────────────────────────────────────────────────────────────────┘
```

## Topología de despliegue

Hay dos roles mutuamente excluyentes en cada dispositivo:

### Modo Servidor (anfitrión, dispositivo A)
- Android primario, ya con VPN activa.
- Levanta hotspot Wi-Fi.
- Levanta proxy local SOCKS5 + HTTP CONNECT en `192.168.43.1:1080` y `:8080`.
- Acepta conexiones entrantes desde clientes en la subred del hotspot.
- Cada conexión cliente → conexión saliente desde el anfitrión → captada por el sistema operativo → entra a la VPN existente vía `tun0`.

### Modo Cliente (receptor, dispositivo B)
- Android secundario o computadora Windows.
- Conectado al hotspot del anfitrión.
- Levanta `VpnService` (Android) o adaptador `Wintun` (Windows).
- Captura todo el tráfico saliente vía `0.0.0.0/0` y `::/0`.
- Lo transcodifica de paquetes IP crudos (capa 3) a streams SOCKS5 (capa 7).
- Los envía al proxy del anfitrión, marcando el socket con `protect()` para evitar bucles.

## Arquitectura de procesos en Android (modo servidor)

```
[App Gravital Share — proceso principal]
├── Activity / Service Compose (UI)
├── ForegroundService "GravitalShareServer"
│   ├── ProxyListener (TCP, escucha en 0.0.0.0:1080 y :8080)
│   ├── ConnectionPool (gestiona conexiones por cliente)
│   ├── UdpRelay (UDP Associate)
│   └── TelemetryCollector
└── JNI bridge → libgravital_engine.so (Rust)
    └── tokio runtime con N hilos según núcleos del SoC
```

## Arquitectura de procesos en Android (modo cliente)

```
[App Gravital Share — proceso principal]
├── Activity / Service Compose (UI)
├── VpnService "GravitalShareClient"
│   └── ParcelFileDescriptor (tunFd) ←── kernel
└── JNI bridge → libgravital_engine.so (Rust)
    ├── TunReader (lee tunFd, entrega paquetes IP a la pila)
    ├── UserspaceStack (smoltcp) — reensambla TCP, gestiona UDP
    ├── SocksClient (habla SOCKS5 con el anfitrión)
    ├── DnsInterceptor (captura UDP/53 y resuelve via socket protegido)
    └── TunWriter (inyecta paquetes IP de vuelta al kernel)
```

## Arquitectura de procesos en Windows (modo cliente)

```
[Servicio NT "Gravital Share"]
├── Adaptador Wintun (gravital0)
├── gravital-engine.exe (mismo crate Rust, target windows)
│   ├── WintunReader / WintunWriter
│   ├── UserspaceStack
│   ├── SocksClient
│   └── DnsInterceptor
└── [App de bandeja — UI Tauri o WinUI 3, decisión pendiente]
```

## Decisiones arquitectónicas clave

### AD-001 · Rust para el data plane
**Decisión**: Implementar todo el procesamiento de paquetes en Rust.
**Razón**: Memory safety sin GC, control de bajo nivel para parsers, ecosistema maduro (`tokio`, `smoltcp`), portable a Android (vía NDK), Windows, Linux y eventual eBPF.
**Consecuencia**: Curva de aprendizaje más alta, build chain más compleja. Se compensa con `cargo` + `gradle` plugin para Android.
**Alternativa rechazada**: Go (aceptable, pero overhead del GC en hot path; binarios mayores).
**Alternativa rechazada**: C/C++ (mejor en runtime, peor en seguridad memoria). En el sector telecom es un riesgo conocido.

### AD-002 · `smoltcp` como pila TCP/IP en espacio de usuario
**Decisión**: Usar [`smoltcp`](https://github.com/smoltcp-rs/smoltcp) en el cliente para reensamblar TCP desde paquetes IP crudos.
**Razón**: Pure Rust, `no_std` opcional, diseñado para entornos embebidos pero suficientemente completo para nuestro caso.
**Consecuencia**: No es la pila de Linux, hay que probar comportamiento bajo congestión.
**Alternativa rechazada**: `lwIP` (C, requiere FFI doble).
**Alternativa rechazada**: Reescribir desde cero (años de trabajo, sin upside).

### AD-003 · Separación estricta servidor/cliente en mismo APK
**Decisión**: Una sola app, dos modos. El usuario elige al iniciar; cambiar requiere parar y arrancar el servicio correcto.
**Razón**: Ahorra distribución, simplifica updates, permite que un mismo dispositivo cumpla ambos roles en distintos momentos.
**Consecuencia**: La UI debe ser muy clara sobre el modo activo.

### AD-004 · MTU 1280 por defecto
**Decisión**: La interfaz TUN del cliente nace con MTU 1280.
**Razón**: Margen seguro contra el overhead que añaden VPN, NAT carrier-grade y túneles celulares con Path MTU agresivo. Es una práctica documentada (ver `docs/06-research/01-vpn2share-analysis.md`).
**Consecuencia**: Algo de fragmentación en LANs limpias. Aceptable.

### AD-005 · Wintun en lugar de TAP-Windows
**Decisión**: Cliente Windows usa exclusivamente Wintun.
**Razón**: Capa 3 puro, mucho menor overhead, firmado por Microsoft, mantenido por WireGuard (proyecto activo).
**Consecuencia**: No soporta bridging nativo. Irrelevante para nuestro caso.

### AD-006 · Logs JSON estructurados desde el día uno
**Decisión**: Cero `println!` o `Log.d` con string interpolation. Todo log es un evento JSON con esquema versionado.
**Razón**: Permite a agentes IA (incluyendo nuestro propio Gravital IA) leer el contexto del runtime sin parsing frágil. Compatible con cualquier ingester (Loki, Elastic, Datadog).
**Consecuencia**: Más boilerplate inicial. Pagado en horas durante el primer bug serio.

### AD-007 · Visa Económica Digital como ID interno del usuario
**Decisión**: La cuenta del usuario es una VED (`VE-YYYY-XXXXXXX`) emitida por Gravital ID. No tenemos sistema de auth propio.
**Razón**: Consistencia de ecosistema. Una cuenta para todo Gravital.
**Consecuencia**: Dependencia de disponibilidad de Gravital ID. Mitigada con caché local del JWT.

### AD-008 · Nada de bibliotecas con red implícita en runtime
**Decisión**: Ninguna dependencia que llame a casa, descargue actualizaciones, telemetría no consentida.
**Razón**: Trust. Auditabilidad. No queremos sorpresas en `cargo audit`.
**Consecuencia**: Implementar más cosas en casa. Aceptable.

## Patrón de comunicación entre capas

Todo cruce de la frontera FFI sigue **un único patrón**:

```
[Kotlin]            [JNI]              [Rust]
SessionManager  →  jni_call()      →   pub extern "C" fn ...
                                       returns Result<JniResult>
                                       
TelemetryCallback ←  callback_obj  ←   pasa al engine para que llame de vuelta
```

- Kotlin **nunca** mantiene un puntero a memoria Rust.
- Rust **nunca** mantiene una `JNIEnv` cruzando hilos.
- Las callbacks viajan como `GlobalRef` que Rust libera al apagarse.
- Toda función expuesta vía `extern "C"` valida sus argumentos y retorna un `i32` de estado + payload por out-parameter.

Detalle completo en `docs/02-architecture/02-data-plane-rust.md` sección "Diseño de la superficie FFI".

## Hilos y concurrencia

### En el motor Rust
- Un runtime `tokio` *multi-threaded* con hilos = `num_cpus::get().min(4)` para evitar cargar dispositivos de gama baja.
- Tareas claves: `tun_reader`, `tun_writer`, `socks_client`, `dns_interceptor`, `telemetry_pump`.
- Comunicación entre tareas exclusivamente por canales (`tokio::sync::mpsc`, `broadcast`, `watch`).
- Cero `Mutex` salvo zonas de configuración leídas con frecuencia (`parking_lot::RwLock` permitido para configuración inmutable post-arranque).

### En la app Kotlin
- Coroutines + `Dispatchers.IO` para FFI calls.
- `StateFlow<UiState>` por pantalla.
- Cero acceso a la red desde el main thread, JAMÁS.

## Manejo de errores

Política de tres niveles:

1. **Errores recuperables localmente** (timeout transitorio, reintento de socket): el motor lo maneja silenciosamente y registra evento INFO.
2. **Errores recuperables con cambio de estado**: la sesión transiciona en su autómata (ej. `Connected → Reconnecting`) y emite un evento WARN con causa.
3. **Errores no recuperables** (config inválida, FD TUN inválido, panic): el motor cierra limpio, emite evento ERROR con stacktrace y causa-raíz, y el control plane decide si reiniciar.

**Nunca un panic cruza la frontera FFI.** `std::panic::catch_unwind` envuelve toda función `extern "C"`.

## Próximos documentos

- Detalles del data plane: `02-data-plane-rust.md`
- Detalles del control plane Android: `03-control-plane-kotlin.md`
- El motor de red propio: `04-network-engine.md`
- La sesión como autómata: `05-session-state-machine.md`
- Cliente Windows: `06-windows-client.md`
