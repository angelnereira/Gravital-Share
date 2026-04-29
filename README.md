# Gravital Share

> Tethering VPN sin root. Motor de red en Rust, plano de control en Kotlin/Compose.

[![Versión](https://img.shields.io/badge/versión-0.1.0--alpha-blue)]() [![Plataforma](https://img.shields.io/badge/plataforma-Android%2026%2B-green)]() [![Rust](https://img.shields.io/badge/rust-1.84-orange)]() [![Licencia](https://img.shields.io/badge/licencia-Propietaria-lightgrey)]()

---

## El problema

Cuando un dispositivo Android comparte su conexión por hotspot, el tráfico de los clientes conectados no pasa por el túnel VPN del anfitrión. Android lo enruta directamente por `rmnet0`, fuera del `tun0`. Forzar el reenrutado requiere root.

Gravital Share lo resuelve sin root: actúa como proxy SOCKS5/HTTP local en el anfitrión y redirige el tráfico de los clientes mediante `VpnService` en Android. El motor de red corre en Rust; el plano de control en Kotlin.

## Cómo funciona para el usuario

El flujo está diseñado para requerir cero configuración en ambos lados.

**Dispositivo A — el que comparte la VPN:**
1. Activa el hotspot de Android desde Ajustes del sistema.
2. Abre Gravital Share → toca **Compartir**.
3. La app levanta el proxy SOCKS5 en `0.0.0.0:1080`. Listo.

**Dispositivo B — el que se beneficia:**
1. Se conecta al hotspot del dispositivo A por WiFi.
2. Abre Gravital Share → toca **Conectar**.
3. La app detecta automáticamente la IP del gateway, prueba el puerto 1080 con un timeout de 1.5 s y, al encontrar el servidor, solicita el permiso VPN del sistema operativo.
4. Al conceder el permiso, establece el túnel. Todo el tráfico del dispositivo B pasa por la VPN de A.

No se introduce IP ni puerto en ningún momento.

## Stack técnico

| Capa | Tecnología |
|------|------------|
| Motor de red | Rust 1.84, tokio, smoltcp 0.11 |
| Plano de control Android | Kotlin, Jetpack Compose, Material 3, VpnService API |
| Persistencia | DataStore Preferences (DNS, MTU, telemetría) |
| Cliente Windows | Rust + Wintun — Fase 2 |
| Puente FFI | JNI en crate raíz cdylib (`jni_android.rs`) |
| Observabilidad | Logs JSON `gs.event.v1`, endpoint MCP local |
| Build | cargo-ndk, AGP 8.7.3, Gradle 8.9 |

## Estructura

```
gravital-share/
├── engine/                    # Motor de red Rust
│   ├── Cargo.toml             # workspace (10 crates)
│   ├── crates/
│   │   ├── gravital-proto/    # parsers IPv4/TCP/UDP/ICMP + checksums RFC 1071
│   │   ├── gravital-obs/      # logs JSON, sink stdout/logcat, endpoint MCP
│   │   ├── gravital-tun/      # I/O async sobre el fd TUN de VpnService
│   │   ├── gravital-socks/    # servidor y cliente SOCKS5 (RFC 1928)
│   │   ├── gravital-http/     # proxy HTTP CONNECT
│   │   ├── gravital-stack/    # userspace TCP/IP sobre smoltcp, NAT virtual
│   │   ├── gravital-dns/      # interceptor DNS, resolver, canary leak check
│   │   ├── gravital-udpgw/    # multiplexor UDP-sobre-TCP (badvpn-udpgw v1)
│   │   ├── gravital-ffi/      # superficie C; JNI vive en gravital-engine
│   │   └── gravital-engine/   # orquestador, state machine, jni_android.rs
│   └── fuzz/                  # harnesses libfuzzer (proto, socks, stack)
├── android/                   # App Android
│   ├── app/                   # módulo principal
│   │   └── src/main/kotlin/
│   │       ├── domain/        # SessionManager, NetworkDiscovery, SettingsRepository
│   │       ├── service/       # GravitalVpnService, GravitalServerService
│   │       ├── ui/            # screens, viewmodels, theme Material 3
│   │       └── di/            # EngineModule (Hilt)
│   ├── core-ffi/              # EngineBridge.kt — bindings JNI
│   ├── core-design/           # GravitalColors, tipografía, tema base
│   ├── core-telemetry/        # GravitalLog — eventos JSON estructurados
│   └── gradle/                # version catalog (libs.versions.toml)
├── outputs/                   # APK más reciente por tipo de build
├── scripts/
│   ├── build-android.sh       # build canónico: Rust → .so → APK
│   └── verify-toolchain.sh    # valida versiones de herramientas
└── docs/                      # blueprints de arquitectura y diseño
```

## Arquitectura del motor

Flujo de datos en modo cliente:

```
VpnService (tun0)
    │ paquetes IPv4 raw
    ▼
gravital-tun  ──  TunReader / TunWriter  (tokio async)
    │
    ├── UDP/53 ──► gravital-dns  ──► respuesta sintética (sin fugas)
    │
    │  TCP: reescritura dst_port → vport virtual
    ▼
gravital-stack  (smoltcp + NatTable + rewrite.rs)
    │ VirtualConnection por sesión TCP
    ▼
gravital-socks  ──  SocksClient::connect()
    │ CONNECT hacia el proxy upstream
    ▼
internet
```

**NAT virtual**: `NatTable` asigna un puerto virtual (10 000–59 999) por par `(src, real_dst)`. `rewrite.rs` reescribe cabeceras IP/TCP con checksums RFC 1071; smoltcp opera con `set_any_ip(true)` para aceptar cualquier IP de destino sin modificarla.

**Descubrimiento de servidor**: `NetworkDiscovery` lee la IP del gateway desde `LinkProperties` (API 30+) o `DhcpInfo` (fallback). Prueba los puertos 1080 y 8080 con `Socket.connect()` a 1.5 s de timeout.

**JNI**: los símbolos `Java_io_gravital_share_ffi_EngineBridge_*` están definidos directamente en `gravital-engine/src/jni_android.rs` (el crate `cdylib`). Los símbolos en crates dependencia no aparecen en la tabla dinámica del `.so`; colocarlos en el crate raíz garantiza su exportación.

## App Android

### Módulos

| Módulo | Responsabilidad |
|--------|----------------|
| `app` | Actividad, navegación, ViewModels, servicios VPN/servidor |
| `core-ffi` | `EngineBridge.kt` — puente JNI con `System.loadLibrary` |
| `core-design` | Tokens de color, tipografía, `GravitalTheme` Material 3 |
| `core-telemetry` | `GravitalLog` — emisor de eventos `gs.event.v1` |

### Pantallas

**Home** — orb de estado animado, tarjetas de modo (Conectar / Compartir), chips de proxy y throughput cuando activo, banner de error con reintento.

**Ajustes** — DNS, MTU, toggle de telemetría MCP. Persiste en DataStore; botón Guardar activo solo cuando hay cambios; snackbar de confirmación.

**Diagnóstico** — stream de eventos del motor en tiempo real (últimos 500), badges de nivel con color tonal, métricas de estado/modo/contador, exportar vía `ACTION_SEND`.

### Flujo de permisos

`GravitalVpnService` usa el permiso `android.net.VpnService` estándar. La app llama a `VpnService.prepare()` desde `HomeScreen` vía `ActivityResultLauncher`; si el permiso ya fue concedido, conecta directamente sin mostrar diálogo.

## Build

### Requisitos

| Herramienta | Versión mínima |
|-------------|---------------|
| Rust | 1.84.0 |
| cargo-ndk | 3.5.4 |
| Android NDK | 27.2.12479018 |
| Java | 21 |
| Android SDK | API 35 + build-tools 35.0.0 |

```bash
./scripts/verify-toolchain.sh
```

### Compilar

```bash
# Debug — arm64-v8a, armeabi-v7a, x86_64
./scripts/build-android.sh

# Solo un ABI (ciclos de desarrollo más rápidos)
./scripts/build-android.sh --abi arm64-v8a

# Release (requiere keystore configurado)
./scripts/build-android.sh --release
```

Cada build elimina el APK anterior del mismo tipo y deposita uno nuevo en `outputs/`:

```
GravitalShare-{versionName}-{versionCode}-{tipo}-{abi}-{yyyymmdd_HHMM}.apk
```

El symlink `outputs/latest-debug.apk` apunta siempre al último debug.

### Instalar

```bash
adb install -r outputs/latest-debug.apk
```

## Estado

**Motor Rust** — compilado, enlazado, símbolos JNI exportados y verificados

- [x] `gravital-proto` — parsers zero-copy, checksums RFC 1071
- [x] `gravital-obs` — logs JSON, sink logcat, endpoint MCP
- [x] `gravital-tun` — I/O async TUN
- [x] `gravital-socks` — SOCKS5 cliente y servidor completo
- [x] `gravital-http` — proxy HTTP CONNECT
- [x] `gravital-stack` — smoltcp + NAT virtual + reescritura de paquetes
- [x] `gravital-dns` — interceptor sin fugas + canary leak check
- [x] `gravital-udpgw` — UDP-sobre-TCP
- [x] `gravital-ffi` — superficie C
- [x] `gravital-engine` — orquestador, relay SOCKS5, DNS hook, JNI android
- [x] Harnesses de fuzzing (proto, socks, stack)
- [ ] Tests de integración con TUN simulada
- [ ] x86 (i686) — target instalado, falta build

**App Android** — funcional, APK disponible en `outputs/`

- [x] `GravitalVpnService` — TUN builder, anti-loop, usa DNS/MTU del DataStore
- [x] `GravitalServerService` — proxy foreground service
- [x] `SessionManager` — estado reactivo con StateFlow, transiciones correctas
- [x] `NetworkDiscovery` — detección automática de servidor en la red local
- [x] `SettingsRepository` — persistencia real en DataStore (DNS, MTU, MCP)
- [x] `EngineBridge` — JNI bridge, verificación de versión FFI en startup
- [x] UI Material 3 — Home, Diagnóstico, Ajustes; tema claro/oscuro
- [x] Auto-descubrimiento zero-config del proxy en hotspot
- [x] Exportar logs vía `ACTION_SEND`
- [x] `.so` para arm64-v8a, armeabi-v7a, x86_64
- [ ] Integración completa engine ↔ app (networking real en curso)
- [ ] Firma para release (keystore pendiente)
- [ ] Icono definitivo

**Fase 2**

- [ ] Cliente Windows (Rust + Wintun)
- [ ] Beta interna
- [ ] Infraestructura de soak test (24 h)

## Licencia

Propiedad de **Nereira Technology and Business Solutions** — marca **Gravital**. Todos los derechos reservados. Para licenciamiento OEM o B2B contactar al equipo Gravital Business.
