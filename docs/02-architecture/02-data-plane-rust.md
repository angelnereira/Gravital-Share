# 02.02 — Data Plane en Rust

## Filosofía

El data plane es donde se gana o se pierde el producto. Latencia, batería, fugas y crashes nacen aquí. Reglas:

1. Cero asignaciones en hot path después del arranque (preasignar buffers).
2. Sin bloqueos en hot path (canales, no `Mutex`).
3. Cada parser válida exhaustivamente. Lo que no entendemos, lo dropeamos con métrica.
4. Cada panic es un bug de criticidad alta.
5. La superficie FFI es un contrato versionado.

## Estructura del workspace

```
engine/
├── Cargo.toml                  # workspace virtual
├── crates/
│   ├── gravital-engine/        # orquestador, expone FFI
│   ├── gravital-proto/         # parsers IP/TCP/UDP/ICMP
│   ├── gravital-stack/         # pila TCP/IP en user-space (smoltcp wrapper)
│   ├── gravital-tun/           # IO con la interfaz TUN/Wintun
│   ├── gravital-socks/         # cliente y servidor SOCKS5
│   ├── gravital-http/          # cliente y servidor HTTP CONNECT
│   ├── gravital-udpgw/         # multiplexor UDP-sobre-TCP
│   ├── gravital-dns/           # interceptor y resolver DNS
│   ├── gravital-obs/           # observabilidad (logs JSON, metrics)
│   └── gravital-ffi/           # capa FFI (JNI + cdylib)
├── ffi/
│   └── android/                # generación de bindings Kotlin
└── tests/
    └── integration/            # pruebas end-to-end con TUN simulada
```

### Por qué un workspace y no un solo crate

- Compilación incremental: tocar `gravital-socks` no recompila `gravital-proto`.
- Tests aislados por capa.
- Permite publicar selectivamente (eventualmente `gravital-proto` podría ser open-source independiente).
- Permite que `gravital-engine` use `cfg(target_os)` para discriminar rutas Android/Windows/Linux.

## Crate por crate

### `gravital-proto`

Parsers exhaustivos de protocolos de red, sin asignación dinámica.

**Responsabilidades**:
- Parsear y validar encabezados IPv4/IPv6, TCP, UDP, ICMPv4/ICMPv6.
- Calcular y verificar checksums (incluyendo checksum offload semantics).
- Construir paquetes salientes (operación inversa).

**Reglas**:
- API basada en *zero-copy*: `IpPacket::parse(buf: &[u8]) -> Result<IpView<'_>, ParseError>`.
- Errores específicos por tipo: `TruncatedHeader`, `InvalidChecksum`, `UnsupportedProtocol`, etc.
- Sin `unwrap()` en código de producción. Solo en tests.
- Fuzzing obligatorio (`cargo-fuzz` corpora vivos en `tests/fuzz/`).

**Ejemplo de signature**:
```rust
pub enum IpView<'a> {
    V4(Ipv4View<'a>),
    V6(Ipv6View<'a>),
}

pub struct Ipv4View<'a> {
    bytes: &'a [u8],
}

impl<'a> Ipv4View<'a> {
    pub fn src(&self) -> Ipv4Addr { /* ... */ }
    pub fn dst(&self) -> Ipv4Addr { /* ... */ }
    pub fn protocol(&self) -> IpProtocol { /* ... */ }
    pub fn payload(&self) -> &'a [u8] { /* ... */ }
    pub fn verify_checksum(&self) -> Result<(), ParseError> { /* ... */ }
}
```

### `gravital-stack`

Pila TCP/IP en espacio de usuario. Wrapper opinionado sobre `smoltcp`.

**Responsabilidades**:
- Recibir paquetes IP desde `gravital-tun`, alimentarlos a `smoltcp`.
- Exponer sockets virtuales que el resto del motor consume.
- Manejar timers de retransmisión, ventana TCP, cierre limpio.
- Para UDP, mantener mapa de "asociaciones" (`(client_ip:port) → (proxy_assoc_id)`).

**Reglas**:
- Una sola instancia de `Interface` de smoltcp por sesión. No multiplexamos varias.
- Buffers de recepción/envío de tamaño configurado al arranque, sin redimensionar.
- MTU se fija al arranque (1280 default). Cambiarlo requiere reset de sesión.

### `gravital-tun`

IO con la interfaz virtual de red.

**Responsabilidades**:
- En Android: abrir el `RawFd` recibido vía JNI, leer/escribir paquetes IP.
- En Windows: usar `wintun` crate, abrir adaptador, leer/escribir paquetes IP.
- En Linux/Desktop: abrir `/dev/net/tun` con `IFF_TUN | IFF_NO_PI`.

**Reglas**:
- API uniforme: `pub trait TunDevice { async fn read(&self, buf: &mut [u8]) -> usize; async fn write(&self, buf: &[u8]) -> usize; }`.
- En Android, el `RawFd` se setea en `O_NONBLOCK` y se integra con el reactor de tokio vía `AsyncFd`.
- Cierre seguro: `close(fd)` solo desde Rust si el ownership es nuestro. Si nos lo presta Android, devolvemos sin cerrar.

### `gravital-socks`

Cliente y servidor SOCKS5 (RFC 1928).

**Responsabilidades** (cliente, usado en modo cliente):
- Establecer conexión TCP al proxy del anfitrión.
- Negociar autenticación (none, opcionalmente username/password).
- Enviar `CONNECT` por cada conexión TCP del usuario.
- Soportar `UDP ASSOCIATE` para tráfico UDP cuando el servidor lo soporta.

**Responsabilidades** (servidor, usado en modo servidor):
- Aceptar conexiones del Listener (`gravital-engine`).
- Implementar handshake completo, incluyendo auth.
- Para `CONNECT`: abrir socket saliente al destino.
- Para `UDP ASSOCIATE`: reservar un puerto UDP local, asociarlo al cliente, hacer relay bidireccional.

**Reglas**:
- Implementar el RFC al pie de la letra. Tests con vectores conocidos del propio RFC.
- En servidor: timeout configurable por conexión (default 30s para handshake, sin timeout para data idle).
- En cliente: backoff exponencial al reconectar (250ms, 500ms, 1s, 2s, ... cap 30s).

### `gravital-http`

Cliente y servidor HTTP CONNECT como modo de compatibilidad.

**Responsabilidades**:
- Implementar el método `CONNECT host:port HTTP/1.1` con respuesta `200`.
- Soportar headers de auth básico opcional.

**Razón de existir**: clientes terceros (configuración manual de proxy en consolas, smart TVs) suelen hablar HTTP CONNECT, no SOCKS5.

### `gravital-udpgw`

Multiplexor de datagramas UDP sobre una conexión TCP cuando el servidor SOCKS no soporta `UDP ASSOCIATE`.

**Razón**: ver `docs/06-research/01-vpn2share-analysis.md` sección 4.4. Muchos proxies SOCKS5 simples no implementan UDP. Si caemos a un anfitrión con esa limitación, levantamos un canal udpgw inspirado en `badvpn-udpgw`.

**Responsabilidades**:
- Encapsular datagramas UDP con un encabezado pequeño que incluye `(client_id, dst_addr, dst_port, payload)`.
- Multiplexar muchos clientes y muchas asociaciones sobre **una** conexión TCP al servidor.
- Demultiplexar respuestas y entregarlas al socket virtual correcto.

**Formato del frame udpgw** (versión 1):
```
+--------+--------+----------+----------+----------+--------+
| ver(1) | flags(1)| client_id(4) | addr_type(1) | addr(N) | port(2) | payload_len(2) | payload(M) |
+--------+--------+----------+----------+----------+--------+
```

### `gravital-dns`

Interceptor y resolver DNS.

**Responsabilidades**:
- Detectar paquetes UDP con destino al puerto 53.
- Resolver vía resolver configurado (1.1.1.1 por defecto, configurable a 8.8.8.8, 9.9.9.9, o privado del anfitrión).
- Soporte futuro: DoH (DNS over HTTPS) y DoT (DNS over TLS) para resolver desde el cliente.
- Bloqueo de fuga: si la resolución falla, **no caer** al resolver del operador. Fallar limpio y emitir evento.

### `gravital-obs`

Observabilidad: logs JSON, métricas, traces.

**Responsabilidades**:
- Macro `obs::event!(level, kind, ...)` que serializa a JSON.
- Sink configurable: stdout (en debug), Android `Log` (en release), file (opt-in).
- Endpoint local MCP-compatible que un asistente IA puede consultar (ver `docs/04-engineering/01-observability.md`).

**Esquema base de evento**:
```json
{
  "ts": "2026-04-26T15:23:11.482Z",
  "lvl": "INFO",
  "kind": "session.transition",
  "trace_id": "01HW...",
  "span_id": "01HW...",
  "module": "gravital-engine",
  "payload": { /* específico del evento */ }
}
```

### `gravital-ffi`

Frontera con el mundo exterior (Kotlin / Tauri / WinUI).

**Responsabilidades**:
- Declarar funciones `pub extern "C"` o `extern "system"` (JNI).
- Convertir tipos Rust ↔ tipos foráneos.
- Capturar panics con `std::panic::catch_unwind`.
- Mantener `JavaVM` global y `GlobalRef` de callbacks.

**Reglas**:
- La superficie es **mínima**. Si algo se puede hacer del lado nativo, no se expone.
- Versión del contrato declarada como constante: `pub const FFI_VERSION: u32 = 1;`. Cualquier ruptura incrementa el major.

### `gravital-engine`

El crate de top-level. Coordina todos los demás.

**Responsabilidades**:
- Inicializar runtime tokio, logger, métricas.
- Construir el grafo de tareas según el modo (servidor o cliente).
- Manejar el ciclo de vida (start, pause, resume, stop).
- Exponer una API de alto nivel que `gravital-ffi` envuelve.

## Diseño de la superficie FFI

### Funciones `extern "C"` expuestas

```c
// Lifecycle
int32_t gravital_engine_init(const char* config_json);
int32_t gravital_engine_start_client(int32_t tun_fd, const char* config_json);
int32_t gravital_engine_start_server(const char* config_json);
int32_t gravital_engine_stop(void);
int32_t gravital_engine_shutdown(void);

// Information
int32_t gravital_engine_get_state(char* out_buf, size_t out_buf_len);
int32_t gravital_engine_get_stats(char* out_buf, size_t out_buf_len);

// Telemetry callback registration
typedef void (*gravital_event_cb)(const char* json_event, void* user_data);
int32_t gravital_engine_set_event_callback(gravital_event_cb cb, void* user_data);

// Constants
const char* gravital_engine_version(void);
uint32_t gravital_engine_ffi_version(void);
```

**Convenciones**:
- Retorno `0` = éxito. Retornos negativos = error con código.
- Todos los buffers de salida son provistos por el caller. Rust nunca asigna memoria que el caller deba liberar.
- Strings JSON viajan UTF-8, terminados en `\0`.
- Una sola instancia activa por proceso. Llamar `init` dos veces sin `shutdown` retorna error.

### Códigos de error estables

| Código | Nombre | Significado |
|---|---|---|
| 0 | `OK` | Éxito |
| -1 | `INVALID_ARG` | Argumento inválido (NULL, longitud cero, JSON malformado) |
| -2 | `ALREADY_INITIALIZED` | Doble init |
| -3 | `NOT_INITIALIZED` | Llamada antes de init |
| -4 | `INVALID_FD` | FD TUN inválido |
| -5 | `CONFIG_INVALID` | JSON de config no pasa esquema |
| -6 | `INTERNAL_PANIC` | Panic capturado |
| -7 | `BUFFER_TOO_SMALL` | El buffer del caller no es suficiente |
| -8 | `IO_ERROR` | Error de IO al abrir socket o TUN |
| -9 | `STATE_TRANSITION_INVALID` | Operación no válida en el estado actual |

### Construcción del .aar para Android

```bash
# Targets Android (NDK r26+)
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android

# Compilar el cdylib para cada arquitectura
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 \
  -o ./android-libs build --release -p gravital-engine

# El resultado en android-libs/{arm64-v8a,...}/libgravital_engine.so
# Empaquetar en .aar mediante el plugin de Gradle de la app
```

## Manejo de memoria

- Buffers en hot path: piscina `Vec<Bytes>` (crate `bytes`) preasignada en arranque, tamaño configurable (`POOL_SIZE = 4096`, `BUF_SIZE = 1600` por default).
- Si la piscina se agota: dropeamos paquetes y emitimos métrica. *No bloqueamos.*
- En cold path: `Box<dyn Trait>` aceptable. Configuración, eventos, etc.

## Performance objetivos por crate

| Crate | Métrica | Objetivo |
|---|---|---|
| gravital-proto | Throughput de parsing | ≥ 1 Gbps en hardware mid-range |
| gravital-tun | Latencia de IO ida y vuelta | ≤ 50 µs |
| gravital-stack | Overhead vs kernel TCP | ≤ 2x en pruebas iperf3 |
| gravital-socks | Sobrecarga handshake | ≤ 10 ms |
| gravital-engine | Memoria residente RSS | ≤ 30 MB con 10 conexiones activas |

Estos números son del MVP. Se vigilan en CI con benchmarks `criterion` y se publican en `docs/04-engineering/04-testing.md`.

## Crates externos auditados

Lista corta de dependencias permitidas para el data plane:

```toml
[dependencies]
tokio = { version = "1", features = ["rt-multi-thread", "macros", "net", "sync", "time"] }
smoltcp = { version = "0.11", default-features = false, features = ["medium-ip", "proto-ipv4", "proto-ipv6", "socket-tcp", "socket-udp"] }
bytes = "1"
parking_lot = "0.12"
thiserror = "1"
tracing = "0.1"
serde = { version = "1", features = ["derive"] }
serde_json = "1"
once_cell = "1"
```

Para la capa FFI:
```toml
jni = "0.21"          # solo en target Android
wintun = "0.5"        # solo en target Windows
```

Cualquier nueva dependencia requiere PR con justificación, revisión de licencia y `cargo audit` limpio.

## Antipatrones explícitamente prohibidos

- ❌ `tokio::spawn` con futures sin `Send` (rompe el runtime multi-threaded).
- ❌ `Mutex<Option<...>>` para estado mutable global. Usar `RwLock` o canal.
- ❌ `Arc<Mutex<Vec<...>>>` que crece sin límite. Mejor `bounded` channels.
- ❌ `unwrap()` en código no-test.
- ❌ Conversión silenciosa entre `u32`/`usize` con `as`. Usar `try_from`.
- ❌ `tokio::time::sleep` dentro de funciones que parsean paquetes (penalización masiva).
