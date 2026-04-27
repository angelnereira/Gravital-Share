# AGENT.md — Contexto Operativo para Agentes de Código

> Este archivo es lo primero que debe leer cualquier asistente de código (Claude Code, Cursor, agentes CLI internos) antes de tocar el repositorio.

## 1. Identidad del proyecto

**Nombre**: Gravital Share
**Tipo**: Sistema de tethering VPN sin root, multiplataforma (Android primario, Windows secundario, Linux futuro).
**Dueño**: Angel Nereira — Nereira Technology and Business Solutions — marca Gravital.
**Idioma de comunicación interna**: Español. Los commits, comentarios estratégicos y documentación viven en español. Los identificadores de código, *strings* técnicos de protocolos, mensajes de log y términos universalmente en inglés (TUN, socket, proxy, etc.) se mantienen en inglés.

## 2. Reglas no negociables

1. **Separación estricta Data Plane (Rust) / Control Plane (Kotlin)**. La lógica de red vive en Rust. La UI, ciclo de vida y permisos viven en Kotlin. La comunicación es por JNI a través de una superficie mínima y bien tipada (ver `docs/02-architecture/02-data-plane-rust.md`).
2. **La sesión es una máquina de estados explícita**. No hay banderas booleanas dispersas (`isConnected`, `isReconnecting`). Todo cambio de estado pasa por la transición declarada (ver `docs/02-architecture/05-session-state-machine.md`).
3. **Logs estructurados desde el día uno**. Nunca `println!`, nunca `Log.d` con texto plano. Todo evento es JSON con esquema (`docs/04-engineering/01-observability.md`).
4. **MTU 1280 por defecto**. No tocar sin autorización explícita y prueba en redes celulares reales.
5. **Bucle de enrutamiento prevenido siempre**. Todo socket saliente del cliente Android pasa por `VpnService.protect()`. Se prueba en CI.
6. **Sin DNS leaks**. El cliente fuerza un resolver enrutable (`1.1.1.1` por defecto, configurable) y captura los datagramas UDP/53. Cualquier PR que rompa esto se rechaza.
7. **Sin dependencias no auditadas**. `cargo audit` y `cargo deny` corren en CI. Una sola advertencia de severidad alta bloquea el merge.

## 3. Convenciones de código

### Rust
- Edición 2021 o superior.
- `#![deny(unsafe_op_in_unsafe_fn)]` en todos los crates.
- `unsafe` solo en módulos `ffi`. Siempre con bloque `// SAFETY:` justificando.
- `clippy::pedantic` activado. Las excepciones se documentan línea por línea.
- Tests unitarios obligatorios para todo módulo de parsing (TCP, UDP, ICMP).
- Fuzzing con `cargo-fuzz` para parsers críticos.

### Kotlin
- Kotlin 2.0+, JDK 17.
- Coroutines + Flow para todo lo asíncrono. Nada de `Thread` crudo.
- Compose para toda la UI nueva. Nada de XML salvo `AndroidManifest`.
- ViewModel por pantalla. Estado expuesto como `StateFlow<UiState>`.
- Inyección con Hilt.
- Detekt + ktlint en pre-commit.

### Estructura de commits
```
<tipo>(<scope>): <resumen en imperativo, máx 72 chars>

<cuerpo opcional explicando qué y por qué, no cómo>

Refs: #<issue>
```
Tipos: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `ci`, `chore`, `sec`.
Scopes sugeridos: `engine`, `android`, `windows`, `ffi`, `proto`, `obs`, `ui`.

## 4. Flujo de trabajo del agente

Cuando recibas una tarea:

1. **Lee el blueprint relevante** (`docs/00-INDEX.md` te dice cuál).
2. **Verifica el estado de la máquina**. ¿Estás en `IDLE`, `BUILDING`, `INTEGRATING`? El blueprint marca el orden.
3. **No saltes fases del MVP**. Si la tarea pertenece a `Phase 2` y la `Phase 1` no está cerrada, detente y reporta.
4. **Cambios chicos, PRs chicos**. Un PR = un comportamiento agregado o un bug arreglado.
5. **Tests primero o tests con**. Nunca tests después.
6. **Si modificas un blueprint, declarar la razón**. Los blueprints son contrato; cambian con propósito.

## 5. Cosas que NO debe hacer un agente

- ❌ Ejecutar `git push` directo a `main`. Siempre PR.
- ❌ Hacer scrubbing/normalización de tráfico real de usuarios para debug. La telemetría se ofusca o se sintetiza.
- ❌ Mezclar Rust y código de UI. Si necesita un nuevo punto FFI, primero diseña la API en `docs/02-architecture/02-data-plane-rust.md` y abre un issue.
- ❌ Importar bibliotecas que requieran red en runtime para funciones críticas del data plane (telemetría incluida).
- ❌ Asumir que el dispositivo tiene root. Cero llamadas a `su`, cero `iptables`.

## 6. Referencias rápidas

| Necesito… | Ir a |
|---|---|
| Entender la arquitectura general | `docs/02-architecture/01-overview.md` |
| Implementar el motor de red | `docs/02-architecture/02-data-plane-rust.md` |
| Implementar el cliente Android | `docs/02-architecture/03-control-plane-kotlin.md` |
| Entender el bridge tun↔socks | `docs/03-protocols/03-tun2socks-bridge.md` |
| Implementar SOCKS5 | `docs/03-protocols/01-socks5.md` |
| Configurar logs/telemetría | `docs/04-engineering/01-observability.md` |
| Diseñar UI nueva | `docs/05-design/01-ui-design-system.md` |
| Pipeline de CI/CD | `docs/04-engineering/03-cicd.md` |
| Glosario | `docs/08-glossary.md` |

## 7. Contacto humano de escalado

Si algo no encaja con los blueprints, **detén el trabajo** y reporta a Angel directamente. No improvises arquitectura. La integridad del diseño vale más que la velocidad.
