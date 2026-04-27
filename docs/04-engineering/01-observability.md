# 04.01 — Observabilidad y Telemetría AI-Ready

## Tesis

> Si no puedes responder en 30 segundos *qué está haciendo el motor en este instante*, no tienes un producto de infraestructura. Tienes un script con suerte.

Gravital Share trata observabilidad como ciudadano de primera clase, no como afterthought. La telemetría debe servir a tres consumidores con prioridades distintas:

1. **El usuario humano** — necesita saber estado, fugas, throughput. UI, no logs.
2. **El operador / soporte técnico** — necesita reproducir incidencias. Logs estructurados + dump de estado.
3. **El agente de IA local (MCP)** — necesita parsear el contexto sin copy-paste manual. JSON canónico + endpoint local.

Todo lo que sigue está orientado a estos tres consumidores, en ese orden de frecuencia inverso a su prioridad de diseño.

## Esquema canónico de evento

Todo evento (Rust o Kotlin) cumple este esquema. Es **append-only** y **versionado**.

```json
{
  "ts": "2026-04-26T18:32:11.482Z",
  "schema": "gs.event.v1",
  "lvl": "info",
  "kind": "session.transition",
  "trace_id": "01HXXXXXXXXXXXXXXXXXXXXXXX",
  "span_id": "abf3...",
  "module": "gravital-engine",
  "payload": {
    "from": "Connecting",
    "to": "Connected",
    "ms": 412
  }
}
```

| Campo | Tipo | Descripción |
|-------|------|-------------|
| `ts` | RFC 3339 | Timestamp en UTC con ms |
| `schema` | string | Versión del esquema. Hoy `gs.event.v1` |
| `lvl` | enum | `trace` \| `debug` \| `info` \| `warn` \| `error` |
| `kind` | dotted | Categoría jerárquica del evento (ver tabla siguiente) |
| `trace_id` | ULID | Identificador de la sesión completa |
| `span_id` | hex(8) | Identificador del span actual |
| `module` | string | Crate/clase emisor |
| `payload` | object | Datos específicos del evento |

## Taxonomía de `kind`

La taxonomía se mantiene plana y predecible. Prefijos reservados:

| Prefijo | Dominio |
|---------|---------|
| `session.*` | Máquina de estados de sesión |
| `engine.*` | Eventos del data plane Rust |
| `socks.*` | Servidor/cliente SOCKS5 |
| `http.*` | Servidor/cliente HTTP CONNECT |
| `dns.*` | Resolución DNS y verificación de fugas |
| `udp.*` | Asociaciones UDP (UDP ASSOCIATE / udpgw) |
| `net.*` | I/O de red, errores de socket, timeouts |
| `ui.*` | Eventos del control plane Kotlin |
| `vpn.*` | Lifecycle del VpnService de Android |
| `audit.*` | Eventos de auditoría (start/stop, leak detected) |

Ejemplos: `session.transition`, `engine.boot`, `socks.handshake.fail`, `dns.leak.detected`, `udp.assoc.timeout`, `audit.session.started`.

## Niveles, no decoración

- `trace` — solo en debug builds. Cada paquete leído del TUN.
- `debug` — desarrollador investiga algo. Apagado por defecto en release.
- `info` — eventos que importan a operadores. Transiciones de estado, conexiones aceptadas.
- `warn` — algo se degrada pero el sistema sigue. Reintentos, fallback a udpgw.
- `error` — fallo que aborta una operación. El sistema se recupera.

> **Regla:** un `error` que se repite cada segundo no es un `error`, es un `warn` con cooldown. Spam de logs es ceguera disfrazada.

## Implementación Rust

Usamos `tracing` + `tracing-subscriber` con un capa custom que serializa al esquema canónico.

```rust
use tracing::{info, instrument};
use serde_json::json;

#[instrument(skip(socket), fields(peer = %socket.peer_addr()?))]
pub async fn handle_socks_handshake(socket: TcpStream) -> Result<()> {
    info!(
        kind = "socks.handshake.start",
        version = 5,
        "client initiated SOCKS5 handshake"
    );
    // ...
}
```

El subscriber custom (`gravital-obs::JsonLayer`) intercepta el `Event` de `tracing` y emite la línea JSON al destino configurado: stdout en desarrollo, archivo rotativo en producción, MCP socket si está habilitado.

## Implementación Kotlin

```kotlin
object GravitalLog {
    fun event(
        kind: String,
        lvl: Level = Level.INFO,
        traceId: String,
        payload: Map<String, Any?> = emptyMap()
    ) {
        val event = mapOf(
            "ts" to Instant.now().toString(),
            "schema" to "gs.event.v1",
            "lvl" to lvl.label,
            "kind" to kind,
            "trace_id" to traceId,
            "module" to currentModule(),
            "payload" to payload
        )
        sink.write(jsonAdapter.toJson(event))
    }
}
```

El `sink` es intercambiable: `LogcatSink` en debug, `RollingFileSink` en producción, `McpSink` cuando hay agente conectado.

## Endpoint local MCP-compatible

Cuando el usuario activa **Modo Diagnóstico** en la app, Gravital Share expone un endpoint local en `127.0.0.1:7423` que sigue la convención Model Context Protocol. Un agente de IA local puede:

| Recurso | Qué devuelve |
|---------|--------------|
| `mcp://gravital-share/state` | Snapshot completo de la sesión: estado, IP, MTU, contadores |
| `mcp://gravital-share/events?since=ts` | Stream de eventos JSON desde un timestamp |
| `mcp://gravital-share/metrics` | Throughput, RTT, packet loss, fd count |
| `mcp://gravital-share/dns/check` | Ejecuta verificación de fugas en demanda |

> **Por qué importa:** un agente de código que desarrolla sobre Gravital Share no necesita pedirle al usuario que copie logs. Pregunta directamente y recibe estructura.

El endpoint es de **solo lectura**, está autenticado con un token efímero generado por sesión, y se expone solo en loopback. Nunca a la red.

## Trazas distribuidas (light)

No corremos OpenTelemetry pesado en un cliente móvil. Pero respetamos la idea: cada operación cruza el FFI con su `trace_id` y `span_id`. La correlación entre lo que vio Kotlin y lo que vio Rust se hace por `trace_id`.

```
Kotlin: trace_id=01HXXX span_id=a1 — "user pressed Start"
Rust:   trace_id=01HXXX span_id=b2 — "engine boot"
Rust:   trace_id=01HXXX span_id=b3 — "session Connecting"
Rust:   trace_id=01HXXX span_id=b4 — "session Connected"
Kotlin: trace_id=01HXXX span_id=a5 — "UI transitioned to Connected"
```

Ver una sesión completa es un grep por `trace_id`.

## Métricas internas

El motor mantiene un struct `EngineMetrics` actualizado atómicamente:

```rust
pub struct EngineMetrics {
    pub bytes_in: AtomicU64,
    pub bytes_out: AtomicU64,
    pub packets_in: AtomicU64,
    pub packets_out: AtomicU64,
    pub active_tcp_sessions: AtomicU32,
    pub active_udp_assocs: AtomicU32,
    pub dns_queries: AtomicU64,
    pub dns_leaks_detected: AtomicU32,
    pub reconnects: AtomicU32,
    pub last_rtt_ms: AtomicU32,
}
```

Se expone vía FFI con `gs_engine_metrics_snapshot()`. El control plane lo lee cada 500 ms para alimentar la UI y cada 5 s para emitir un evento `engine.metrics.snapshot`.

## Reglas duras

1. **Nunca loguear payloads de usuario.** Ni un solo byte que cruce el túnel se imprime. Eso incluye URLs.
2. **Nunca loguear IPs de destino del cliente final.** Loguear `dest_addr_class=public_v4` es suficiente.
3. **Sí loguear tamaños, RTTs, conteos.** Eso es operacional, no es vigilancia.
4. **Rotación obligatoria.** Archivo de log rota cada 10 MB y se borra después de 7 días.
5. **El usuario puede borrar todos los logs desde la UI con un botón.** Acción irreversible y confirmada.

## Tests

- `tests/obs_schema.rs` — todo evento producido valida contra el JSON Schema `gs.event.v1`.
- `tests/obs_no_pii.rs` — fuzzing de eventos: ninguno contiene strings que parezcan URLs, hosts del cliente, o cabeceras HTTP.
- `tests/obs_mcp_endpoint.rs` — el endpoint MCP responde a las 4 rutas, requiere token, rechaza requests no-loopback.

## Cross-references

- Esquema de estado consumido: [`docs/02-architecture/05-session-state-machine.md`](../02-architecture/05-session-state-machine.md)
- Métricas usadas por la UI: [`docs/05-design/01-ui-design-system.md`](../05-design/01-ui-design-system.md)
- Hardening del endpoint MCP: [`docs/04-engineering/02-security.md`](02-security.md)
