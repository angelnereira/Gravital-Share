# Gravital Share

> Tethering VPN sin root. Motor de red en Rust, plano de control en Kotlin/Compose.

[![Versión](https://img.shields.io/badge/versión-0.1.0--alpha-blue)]() [![Plataforma](https://img.shields.io/badge/plataforma-Android%2026%2B-green)]() [![Rust](https://img.shields.io/badge/rust-1.84-orange)]() [![Licencia](https://img.shields.io/badge/licencia-Propietaria-lightgrey)]()

---

## El problema

Cuando un dispositivo Android comparte su conexión por hotspot, el tráfico de los clientes conectados no pasa por el túnel VPN del anfitrión. Android lo enruta directamente por `rmnet0`, fuera del `tun0`. Forzar el reenrutado requiere root.

Gravital Share lo resuelve sin root: actúa como proxy SOCKS5/HTTP local en el anfitrión y redirige el tráfico de los clientes mediante `VpnService` en Android o un adaptador Wintun en Windows. El motor de red corre en Rust; el plano de control en Kotlin.

## Stack técnico

| Capa | Tecnología |
|------|------------|
| Motor de red | Rust 1.84, tokio, smoltcp 0.11 |
| Plano de control Android | Kotlin, Jetpack Compose, VpnService API |
| Cliente Windows | Rust + Wintun — Fase 2 |
| Puente FFI | JNI (Android), cdylib (Windows) |
| Observabilidad | Logs JSON estructurados (gs.event.v1), endpoint MCP |
| Build | cargo-ndk, AGP 8.7.3, Gradle 8.9 |

## Estructura

```
gravital-share/
├── engine/                    # Motor de red Rust
│   ├── Cargo.toml             # workspace (10 crates)
│   ├── crates/
│   │   ├── gravital-proto/    # parsers IPv4/TCP/UDP/ICMP + checksums
│   │   ├── gravital-obs/      # logs JSON, sink stdout/logcat, endpoint MCP
│   │   ├── gravital-tun/      # I/O async sobre el fd TUN de VpnService
│   │   ├── gravital-socks/    # servidor y cliente SOCKS5 (RFC 1928)
│   │   ├── gravital-http/     # proxy HTTP CONNECT
│   │   ├── gravital-stack/    # userspace TCP/IP sobre smoltcp, NAT virtual
│   │   ├── gravital-dns/      # interceptor DNS, resolver, canary leak check
│   │   ├── gravital-udpgw/    # multiplexor UDP-sobre-TCP (badvpn-udpgw v1)
│   │   ├── gravital-ffi/      # superficie C y JNI para Android
│   │   └── gravital-engine/   # orquestador, state machine, config, métricas
│   └── fuzz/                  # harnesses libfuzzer (proto, socks, stack)
├── android/                   # App Android
│   ├── app/                   # módulo principal
│   ├── core-ffi/              # EngineBridge + bindings JNI
│   ├── core-design/           # sistema de diseño Compose
│   ├── core-telemetry/        # GravitalLog, eventos estructurados
│   └── gradle/                # version catalog (libs.versions.toml)
├── outputs/                   # APKs listos para instalar
├── scripts/
│   ├── build-android.sh       # build canónico: Rust → .so → APK
│   └── verify-toolchain.sh    # valida versiones de herramientas
└── docs/                      # blueprints de arquitectura y diseño
```

## Arquitectura del motor

El flujo de datos en modo cliente:

```
VpnService (tun0)
    │ paquetes IPv4 raw
    ▼
gravital-tun → TunReader/TunWriter (tokio async)
    │
    ▼
gravital-engine (runner)
    │ intercepción DNS (UDP/53)
    ├──────────────────────────────► gravital-dns → respuesta sintética
    │
    │ TCP → reescritura de cabeceras (dst_port → vport virtual)
    ▼
gravital-stack (smoltcp + NatTable)
    │ VirtualConnection por cada sesión TCP
    ▼
gravital-socks (SocksClient)
    │ CONNECT hacia el proxy upstream
    ▼
internet
```

**NAT virtual**: cada conexión TCP recibe un puerto virtual (10 000–59 999) que la identifica dentro de smoltcp. El módulo `rewrite.rs` reescribe cabeceras IP/TCP y recalcula checksums (RFC 1071) sin modificar la IP de destino; smoltcp opera con `set_any_ip(true)`.

## Crates del motor

### gravital-proto
Parsers zero-copy sobre `bytes::Buf`. Cubre IPv4, IPv6, TCP, UDP, ICMP. Todos los checksums validados contra vectores RFC.

### gravital-stack
Puente entre el fd TUN y smoltcp. `TunVirtualDevice` implementa el trait `Device` de smoltcp con dos colas (`VecDeque`) para separar el path de recepción del de transmisión sin conflictos de borrow. `NatTable` mapea `(src, real_dst) ↔ vport` y libera entradas cuando cierra la sesión.

### gravital-socks
Cliente y servidor SOCKS5 completo. Soporta CONNECT y UDP ASSOCIATE. El cliente expone `SocksClient::connect(addr, port) → TcpStream` que el relay usa para establecer el túnel upstream.

### gravital-dns
Intercepta todos los paquetes UDP/53 antes de que lleguen al stack. Resuelve por el canal cifrado o devuelve SERVFAIL; nunca deja escapar consultas a la interfaz de red real. Incluye `LeakCheck` con dominio canary.

### gravital-obs
Emite eventos `gs.event.v1` en JSON por stdout o logcat. Expone un endpoint MCP local para consumo por agentes externos. Sin dependencia de `std::io::stderr` en Android.

### gravital-ffi
Superficie C pura (`c_api.rs`) y bindings JNI (`jni_api.rs`, compilados solo en `target_os = "android"`). Maneja pánico en la frontera FFI con `catch_unwind`. El `EngineBridge` de Kotlin es un object singleton que llama a estas funciones nativas.

## App Android

Cuatro módulos Gradle:

- **app** — actividad principal, servicios VPN y servidor, ViewModels, navegación
- **core-ffi** — `EngineBridge.kt`, bindings al motor nativo, Hilt module
- **core-design** — tokens de color (`GravitalColors`), tipografía, tema Material3
- **core-telemetry** — `GravitalLog.kt`, eventos JSON, integración con gravital-obs

La app pide permiso `VPN` al usuario mediante `VpnService.prepare()`. Una vez concedido, `GravitalVpnService` construye la interfaz TUN con `Builder.establish()` y pasa el fd al motor Rust vía JNI.

## Build

### Requisitos

| Herramienta | Versión mínima |
|-------------|---------------|
| Rust | 1.84.0 |
| cargo-ndk | 3.5.4 |
| Android NDK | 27.2.12479018 |
| Java | 21 |
| Android SDK | API 35 + build-tools 35.0.0 |

Verifica el entorno:

```bash
./scripts/verify-toolchain.sh
```

### Compilar

```bash
# Debug — todos los ABIs (arm64-v8a, armeabi-v7a, x86_64)
./scripts/build-android.sh

# Release (requiere keystore configurado)
./scripts/build-android.sh --release

# Un solo ABI, más rápido en desarrollo
./scripts/build-android.sh --abi arm64-v8a
```

Cada build deposita el APK en `outputs/` con nombre versionado:

```
GravitalShare-{versionName}-{versionCode}-{tipo}-{abi}-{yyyymmdd_HHMM}.apk
```

El symlink `outputs/latest-debug.apk` siempre apunta al último build de debug.

### Instalar en dispositivo

```bash
adb install -r outputs/latest-debug.apk
```

## Estado

**Motor Rust** — compilado y enlazado

- [x] `gravital-proto` — parsers y checksums
- [x] `gravital-obs` — observabilidad, endpoint MCP
- [x] `gravital-tun` — I/O async TUN
- [x] `gravital-socks` — SOCKS5 cliente y servidor
- [x] `gravital-http` — proxy HTTP CONNECT
- [x] `gravital-stack` — smoltcp + NAT virtual + reescritura de paquetes
- [x] `gravital-dns` — interceptor sin fugas + canary
- [x] `gravital-udpgw` — UDP-sobre-TCP
- [x] `gravital-ffi` — superficie C + JNI
- [x] `gravital-engine` — orquestador completo, relay SOCKS5, DNS hook
- [x] Harnesses de fuzzing (proto, socks, stack)
- [ ] Tests de integración con TUN simulada
- [ ] x86 (i686) — target instalado, falta build

**App Android** — APK de debug disponible en `outputs/`

- [x] `GravitalVpnService` — modo cliente, TUN builder, protect(), anti-loop
- [x] `GravitalServerService` — modo servidor, foreground service
- [x] `SessionManager` — estado reactivo con StateFlow
- [x] `EngineBridge` — JNI bridge con verificación de versión FFI
- [x] UI — Home, Diagnóstico, Ajustes, tema Gravital
- [x] Hilt DI, Coroutines, Navigation Compose
- [x] `.so` para arm64-v8a, armeabi-v7a, x86_64
- [ ] Firma para release (keystore pendiente)
- [ ] Icono definitivo (placeholder actual)

**Pendiente — Fase 2**

- [ ] Cliente Windows (Rust + Wintun)
- [ ] Beta interna
- [ ] Infraestructura de soak test (24h)

## Licencia

Propiedad de **Nereira Technology and Business Solutions** — marca **Gravital**. Todos los derechos reservados. Para licenciamiento OEM o B2B contactar al equipo Gravital Business.
