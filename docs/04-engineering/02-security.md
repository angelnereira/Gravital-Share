# 04.02 — Postura de Seguridad

## Tesis

> Un motor que toca paquetes IP crudos venidos de cualquier red es **superficie de ataque**. Cada parser es una puerta. Cada `unwrap()` es un crash potencial. Cada dependencia es un ataque diferido en el tiempo.

Gravital Share trata seguridad como propiedad estructural del data plane, no como capa añadida. La elección de Rust no es estética: es una decisión de threat model.

## Modelo de amenazas (resumido)

| Actor | Capacidad | Mitigación principal |
|-------|-----------|----------------------|
| **Cliente malicioso en la LAN del Hotspot** | Envía paquetes SOCKS5/HTTP malformados al servidor | Parser fuzzed + límites estrictos + timeouts |
| **Atacante en internet** | Responde con TCP/UDP malformado al motor | Userspace stack `smoltcp` (memory-safe) + checksums |
| **Atacante con acceso físico al dispositivo** | Lee logs, intenta extraer estado | Sin PII en logs, rotación, cifrado opcional |
| **App maliciosa en el mismo Android** | Intenta conectarse al puerto del proxy | Bind a IP del Hotspot, no `0.0.0.0` global |
| **Cadena de suministro (deps)** | Crate comprometido en `cargo` o lib en Maven | `cargo audit` + `cargo deny` + lockfile commit |
| **Operador del Hotspot adversarial** | Captura tráfico en su Hotspot | Out of scope: Gravital Share no es VPN, es relé. La VPN primaria del usuario es el cifrado. |

## Reglas duras del data plane (Rust)

```rust
// Crate root: gravital-engine/src/lib.rs

#![deny(unsafe_op_in_unsafe_fn)]
#![deny(clippy::unwrap_used)]
#![deny(clippy::expect_used)]
#![deny(clippy::panic)]
#![warn(clippy::indexing_slicing)]
#![warn(clippy::integer_arithmetic)]
```

| Regla | Por qué |
|-------|---------|
| `unsafe_op_in_unsafe_fn` | El `unsafe` debe ser explícito incluso dentro de `extern "C"` |
| Sin `unwrap()` ni `expect()` | Cualquier panic en el motor mata el proceso de Android |
| Sin `panic!()` directo | Los errores se propagan como `Result` o se loguean y absorben |
| Sin indexing crudo (`a[i]`) | Usar `.get(i)` para evitar panics out-of-bounds |
| Sin aritmética sin checks en parsers | `checked_add`, `saturating_sub`, etc. |

### Aislamiento del FFI

Toda función `extern "C"` está envuelta en `catch_unwind`:

```rust
#[no_mangle]
pub extern "C" fn gs_engine_start(cfg: *const EngineConfig) -> i32 {
    let result = std::panic::catch_unwind(|| {
        // SAFETY: el caller garantiza que cfg es válido o NULL.
        let cfg = unsafe { cfg.as_ref() }.ok_or(EngineError::NullArg)?;
        engine_start_impl(cfg)
    });
    match result {
        Ok(Ok(())) => 0,
        Ok(Err(e)) => e.code(),
        Err(_) => ErrorCode::PanicAcrossFfi as i32,
    }
}
```

> Si el motor entra en pánico, **el proceso de Android no muere**. Devuelve `PanicAcrossFfi`, el control plane lo ve, registra evento `engine.panic.recovered`, y reinicia.

## Fuzzing

Targets de fuzzing obligatorios desde el día 1, todos en `gravital-proto/fuzz/`:

| Target | Qué fuzzea |
|--------|------------|
| `fuzz_socks5_handshake` | Bytes arbitrarios al parser de la fase de negociación |
| `fuzz_socks5_request` | Bytes arbitrarios al parser de CONNECT/UDP ASSOCIATE |
| `fuzz_http_connect` | Líneas HTTP malformadas |
| `fuzz_dns_query` | Paquetes DNS arbitrarios al interceptor |
| `fuzz_ip_header` | Cabeceras IPv4/IPv6 mutadas (vía `smoltcp`) |

Ejecución: `cargo fuzz run fuzz_socks5_request -- -max_total_time=300` en cada PR. CI corre 5 minutos por target. Nightly job corre 2 horas por target.

Criterio de aceptación: **cero crashes, cero hangs, cero leaks**. Cualquier corpus que reproduzca un crash bloquea el merge.

## Auditoría de dependencias

```toml
# .cargo/audit.toml — fail en cualquier vulnerabilidad
[advisories]
vulnerability = "deny"
unmaintained = "warn"
yanked = "deny"
```

```toml
# deny.toml — control de superficie de dependencias
[licenses]
allow = ["MIT", "Apache-2.0", "BSD-3-Clause", "ISC"]
deny = ["GPL-3.0", "AGPL-3.0"]

[bans]
multiple-versions = "warn"
deny = [
    { name = "openssl" },  # usamos rustls
]
```

CI corre en cada PR:
- `cargo audit`
- `cargo deny check`
- `cargo outdated --exit-code 1` (warning, no blocking)
- En Kotlin: `gradle dependencyCheck` (OWASP DC plugin)

## Hardening del control plane (Kotlin)

| Regla | Implementación |
|-------|----------------|
| Sin `WRITE_EXTERNAL_STORAGE` | Storage interno solo |
| `usesCleartextTraffic="false"` | Forzado en `AndroidManifest.xml` |
| `android:allowBackup="false"` | Sesiones y tokens no se respaldan |
| `android:debuggable="false"` en release | Sin excepciones |
| Tokens del MCP en `EncryptedSharedPreferences` | AES-256 GCM con clave del Keystore |
| Sin reflection arbitraria | ProGuard/R8 con reglas estrictas |

## DNS y fugas

Cualquier consulta DNS que **escape del túnel** es una fuga. La mitigación es de tres capas:

1. **Estructural** — el Builder de `VpnService` declara `addDnsServer("1.1.1.1")` (configurable). El kernel enruta `:53` UDP al TUN.
2. **Activa** — el motor intercepta todo paquete con `dst_port == 53` y lo procesa él mismo (forward sobre el proxy o resolución directa cuando el túnel maneje DNS-over-TCP).
3. **Verificación** — al pasar a `Connected`, el motor lanza una sonda: resuelve un dominio canario y confirma que la respuesta vino del resolver esperado. Si no, transición a `Failed` con `kind=dns.leak.detected`.

Ver [`docs/02-architecture/04-network-engine.md`](../02-architecture/04-network-engine.md) para el flujo concreto.

## Servidor: bind y access control

El servidor proxy (`gravital-engine` en modo servidor):

- Hace bind **a la IP de la interfaz del Hotspot**, no a `0.0.0.0`. Si la app no logra resolver la IP del Hotspot, no arranca.
- Mantiene una **allowlist opcional de subredes** del cliente. Por defecto: la subred del Hotspot (`192.168.43.0/24` típicamente).
- Aplica un **rate limit por IP origen**: máximo N nuevas conexiones por segundo, para evitar agotamiento de fd.
- Cierra conexiones en `idle_timeout` configurable (default 90 s).

## Auditoría: eventos críticos

Estos eventos se loguean siempre, con `lvl=info` mínimo, en una tabla aparte protegida:

| Kind | Cuándo |
|------|--------|
| `audit.session.started` | Usuario inició sesión, modo, MTU |
| `audit.session.stopped` | Sesión terminó, razón |
| `audit.dns.leak.detected` | Sonda detectó fuga |
| `audit.ffi.panic.recovered` | Motor entró en pánico y se recuperó |
| `audit.config.changed` | Usuario cambió configuración crítica |

El usuario puede ver y exportar este log desde la UI. Es la "caja negra" del producto.

## Code signing

| Plataforma | Mecanismo |
|------------|-----------|
| Android | `apksigner` con clave en HSM offline. Una clave por canal (debug, beta, stable). |
| Windows | EV Code Signing Certificate, firma de binarios `.exe`, `.dll` y MSI. Driver Wintun ya viene firmado por WireGuard LLC, no requerimos firmar el driver. |

Las claves nunca tocan máquinas de desarrollador. CI accede mediante GitHub OIDC + KMS.

## Sin telemetría externa

> **Decisión arquitectónica:** Gravital Share **no envía un solo byte** a servidores de Anthropic, Google, Microsoft, Anthropic, Cloudflare, ni a Gravital. Cero crash reporters externos. Cero analytics.

Si el usuario quiere reportar un bug, **exporta un bundle local** que él comparte voluntariamente. Esa decisión es del usuario, no de la app.

Esto es una postura de producto, no técnica. Y es la única coherente con vender "the traffic crosses, that's all you need to know".

## Tests

- `cargo audit` — sin findings High/Critical en deps.
- `cargo deny check` — todas las licencias y bans pasan.
- `cargo fuzz run --all -- -max_total_time=60` — sin crashes en CI por PR.
- Test de integración: enviar 10000 paquetes SOCKS5 mutados al servidor — debe seguir respondiendo, sin crash, sin leak de fd.
- Test de DNS leak: con sonda canaria, asegurar que `Connected` con DNS mal configurado transiciona a `Failed`.

## Cross-references

- FFI surface: [`docs/02-architecture/02-data-plane-rust.md`](../02-architecture/02-data-plane-rust.md)
- State machine: [`docs/02-architecture/05-session-state-machine.md`](../02-architecture/05-session-state-machine.md)
- CI hooks: [`docs/04-engineering/03-cicd.md`](03-cicd.md)
- Riesgos pendientes: [`docs/07-roadmap/03-risks-limitations.md`](../07-roadmap/03-risks-limitations.md)
