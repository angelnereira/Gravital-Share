# Gravital Share

> Tethering de VPN sin root, con motor de red en Rust y plano de control en Kotlin/Compose. Parte del ecosistema **Gravital** (división Gravital Technology).

[![Estado](https://img.shields.io/badge/estado-blueprint-blue)]() [![Plataforma](https://img.shields.io/badge/plataforma-Android%2014%2B%20%7C%20Windows%2010%2B-green)]() [![Licencia](https://img.shields.io/badge/licencia-Propietaria-lightgrey)]()

---

## Qué es

Gravital Share resuelve un problema concreto: cuando un dispositivo Android comparte su conexión por hotspot, el tráfico de los dispositivos conectados **no atraviesa el túnel VPN** del anfitrión. Android enruta ese tráfico directamente por la interfaz celular (`rmnet0`), evadiendo `tun0`. Forzar el reenrutado tradicionalmente exige acceso root.

Gravital Share consigue el mismo resultado **sin root**, operando en la Capa 7 (proxy SOCKS5/HTTP local en el anfitrión) y reenrutando en los clientes mediante `VpnService` (Android) o un adaptador `Wintun` (Windows). El núcleo de red se compila en Rust y se enlaza por JNI/FFI; el plano de control vive en Kotlin con Jetpack Compose.

## Por qué existe

1. **Producto**: democratizar el tethering protegido para hogares con un solo plan VPN, oficinas pequeñas, viajeros y dispositivos sin soporte VPN nativo (Smart TVs, consolas, IoT).
2. **Ecosistema**: ser una pieza de Gravital Technology que demuestre la calidad de ingeniería del stack y alimente con telemetría real al *Network Layer* de Gravital Cloud.
3. **Soberanía técnica**: poseer un motor de red propio y auditable, sin depender de SDK cerrados de terceros.

## Stack

| Capa | Tecnología |
|---|---|
| Data Plane (motor de red) | **Rust** (`no_std` donde aplica), `tokio`, `smoltcp` |
| Control Plane Android | **Kotlin** + Jetpack Compose, `VpnService` API |
| Cliente Windows | **Rust** + `Wintun` (capa 3) + servicio NT |
| FFI / Bridge | JNI (Android) y `cdylib` (Windows) |
| Observabilidad | Logs JSON estructurados, traces compatibles con MCP |
| CI/CD | GitHub Actions, builds multiarquitectura, fuzzing |

## Estructura del repositorio

```
gravital-share/
├── README.md                # este archivo
├── AGENT.md                 # contexto rápido para agentes de código
├── docs/                    # blueprints completos (entrar por 00-INDEX.md)
│   ├── 00-INDEX.md
│   ├── 01-vision/
│   ├── 02-architecture/
│   ├── 03-protocols/
│   ├── 04-engineering/
│   ├── 05-design/
│   ├── 06-research/
│   ├── 07-roadmap/
│   └── 08-glossary.md
├── android/                 # app Android (Kotlin + Compose) — por crear
├── engine/                  # motor de red Rust — por crear
├── windows/                 # cliente Windows Rust + Wintun — por crear
├── ci/                      # pipelines, scripts de release
└── tools/                   # utilidades de desarrollo (fuzzing, perf)
```

## Cómo entrar al proyecto

1. Lee `docs/00-INDEX.md`. Es el mapa.
2. Si vas a programar, lee `docs/04-engineering/05-agent-guidelines.md` antes de tocar código.
3. Antes de cualquier diseño visual, lee `docs/05-design/01-ui-design-system.md`.
4. Para entender por qué cada decisión es como es, `docs/06-research/01-vpn2share-analysis.md` explica el estado del arte que estamos superando.

## Estado actual

- ✅ Blueprint completo (este repositorio)
- ⬜ Scaffold del workspace Rust
- ⬜ Scaffold de la app Android
- ⬜ Implementación del Modo Servidor (proxy local)
- ⬜ Implementación del Modo Cliente (VpnService + tun↔socks)
- ⬜ Cliente Windows (Wintun)
- ⬜ Beta interna
- ⬜ Beta cerrada
- ⬜ Lanzamiento público

## Licencia y autoría

Propiedad de **Nereira Technology and Business Solutions** — marca comercial **Gravital**. Todos los derechos reservados. Para licenciamiento OEM o B2B, contactar al equipo Gravital Business.

---

*"No estamos compitiendo en un mercado, estamos definiendo una capa."*
