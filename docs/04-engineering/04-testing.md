# 04.04 — Estrategia de Testing

## Tesis

> Un VPN-tethering que pierde paquetes silenciosamente, fuga DNS bajo presión, o se cuelga después de 6 horas, **es peor que no tener producto**. La gente pone su tráfico ahí confiando.

La estrategia de testing es asimétrica respecto al esfuerzo: 80% del tiempo se invierte en garantizar **comportamiento de red bajo condiciones reales**, no en cubrir branches de código de UI.

## Pirámide invertida

```
        ┌──────────────────────┐
        │  Soak / E2E (24h+)   │   ← donde se descubren los bugs reales
        ├──────────────────────┤
        │   Integración        │
        ├──────────────────────┤
        │  Property + Fuzz     │
        ├──────────────────────┤
        │   Unit               │   ← cobertura, no calidad
        └──────────────────────┘
```

## 1) Tests unitarios

**Alcance:** lógica pura, parsers, máquina de estados, conversiones.

| Crate | Targets prioritarios |
|-------|----------------------|
| `gravital-proto` | Parser SOCKS5 con vectores conocidos del RFC 1928 |
| `gravital-stack` | Reensamblado TCP, validación de checksums |
| `gravital-dns` | Encoding/decoding DNS con queries reales |
| `gravital-engine` | Transiciones legales/ilegales de la state machine |

```rust
#[test]
fn socks5_handshake_no_auth() {
    let input = [0x05, 0x01, 0x00];
    let parsed = parse_handshake(&input).expect("valid handshake");
    assert_eq!(parsed.version, 5);
    assert_eq!(parsed.methods, vec![AuthMethod::None]);
}

#[test]
fn session_cannot_jump_idle_to_connected() {
    let mut sm = Session::new();
    let result = sm.transition(Event::Connected);
    assert!(matches!(result, Err(StateError::IllegalTransition)));
}
```

> **Regla:** si un parser tiene un bug en producción y no había un test que lo capturara, lo primero que se hace es escribir el test que falla, *después* el fix. Sin excepciones.

## 2) Property-based tests

Para parsers y para la state machine, los unit tests son insuficientes. Usamos `proptest`:

```rust
proptest! {
    #[test]
    fn socks5_parser_never_panics(bytes in prop::collection::vec(any::<u8>(), 0..2048)) {
        // Pase lo que pase, no debe panic. Puede devolver Err.
        let _ = parse_request(&bytes);
    }

    #[test]
    fn session_transitions_consistent(
        events in prop::collection::vec(any::<SessionEvent>(), 0..100)
    ) {
        let mut sm = Session::new();
        for e in events {
            // Cada transición debe respetar el invariante:
            // estado actual ∈ tabla de transiciones legales
            let _ = sm.transition(e);
            prop_assert!(sm.invariants_hold());
        }
    }
}
```

## 3) Fuzzing

Detallado en [`docs/04-engineering/02-security.md`](02-security.md). Resumen: 5 targets, 60 s por PR, 2 h por noche. Cero crashes para mergear.

## 4) Tests de integración (engine end-to-end, sin Android)

Estos tests corren el motor completo en un proceso de test, sin Android, contra `tokio::net::TcpStream` simulando interfaces.

```rust
#[tokio::test]
async fn socks5_server_relays_tcp_traffic() {
    let server = start_test_server().await;
    let echo = start_test_echo_target().await;

    let mut client = SocksClient::connect(server.addr()).await.unwrap();
    client.handshake_no_auth().await.unwrap();
    client.connect(echo.addr()).await.unwrap();
    client.write_all(b"hello\n").await.unwrap();

    let mut buf = vec![0u8; 6];
    client.read_exact(&mut buf).await.unwrap();
    assert_eq!(&buf, b"hello\n");
}
```

Casos cubiertos:
- SOCKS5 CONNECT a destino válido — relay correcto.
- SOCKS5 CONNECT a host inexistente — REP=0x04 (Host unreachable).
- SOCKS5 con auth no soportada — rechazo limpio.
- HTTP CONNECT con request válido — establecimiento de túnel.
- HTTP CONNECT con request malformado — 400 + cierre.
- UDP ASSOCIATE feliz — tráfico ida y vuelta.
- UDP ASSOCIATE no soportado por proxy upstream — fallback a `udpgw`.
- Timeout de conexión — cierre limpio, no fd leak.
- 1000 conexiones concurrentes — sin degradación medible.

## 5) Tests de FFI

Cargar la `.so` desde un test JVM, llamar funciones, verificar:

```kotlin
class EngineFfiTest {
    @Test
    fun engineStartReturnsZeroOnValidConfig() {
        val cfg = EngineConfig(mode = "client", proxyHost = "192.168.43.1", proxyPort = 1080)
        val rc = NativeEngine.start(cfg)
        assertEquals(0, rc)
        NativeEngine.stop()
    }

    @Test
    fun engineSurvivesPanicAndReturnsCode() {
        val rc = NativeEngine.testPanic()  // función helper en debug builds
        assertEquals(ErrorCode.PANIC_ACROSS_FFI.code, rc)
    }
}
```

## 6) Tests instrumentados Android

Corren en emulador (API 26, 30, 35) en CI:

| Test | Verifica |
|------|----------|
| `VpnServiceLifecycleTest` | `prepare()` → `establish()` → `stopService()` sin leaks |
| `LoopPreventionTest` | El socket al proxy está `protect()`-ed |
| `DnsLeakTest` | Con sonda canaria, DNS no escapa al ISP |
| `ReconnectTest` | Apagar Wi-Fi → reconnect con backoff → reconectado |
| `MtuFragmentationTest` | MTU=1280 evita fragmentación; MTU=1500 lo causa |

## 7) Tests E2E entre dos dispositivos

Reproducibles vía `scripts/e2e/`:

```
[Phone A: server mode, hotspot on]  ←─ Wi-Fi ─→  [Phone B: client mode]
                                                   │
                                                   ▼
                                            [iperf3 server público]
```

Corre via ADB sobre dos dispositivos físicos enchufados a la misma máquina. Mide:
- Throughput TCP (objetivo ≥ 70% del baseline sin VPN)
- Throughput UDP (objetivo ≥ 60% del baseline)
- RTT añadido (objetivo ≤ 25 ms)
- Pérdida de paquetes en 60 s sostenidos (objetivo < 0.1%)

## 8) Soak tests

**24 horas continuas** con tráfico mixto generado:
- HTTP GET a 1000 sitios distintos
- Streaming de audio durante 4 h
- Videollamada simulada (UDP sostenido)
- Burst de descargas grandes cada 30 min

Criterios:
- Cero crashes del motor.
- Conteo de fd estable (no creciente).
- RAM estable después de las 2 primeras horas.
- Cero fugas de DNS (verificadas con `tcpdump` paralelo en la interfaz física).
- Throughput no degradado más de 10% al final vs primera hora.

Soak tests corren en hardware dedicado, no en CI. Se programan una vez por sprint y antes de cada release.

## 9) Verificación de fugas con `tcpdump`

```bash
# En el dispositivo cliente (rooted, solo para tests)
tcpdump -i wlan0 -nn -w /sdcard/leak.pcap

# Después: análisis offline
tshark -r leak.pcap -Y 'udp.port == 53 and ip.dst != 192.168.43.1'
# Cero líneas == cero fugas.
```

Procedimiento estándar antes de cada release.

## 10) Benchmarks

`criterion` para tareas críticas en hot path:

| Benchmark | Métrica |
|-----------|---------|
| `bench_socks5_handshake` | ns/op, allocs/op |
| `bench_packet_parse_v4` | ns/packet a 64B y 1500B |
| `bench_tun2socks_throughput` | Mbps simulado |
| `bench_dns_query_intercept` | µs/query |

Regresiones >10% bloquean merge.

## 11) Tests de UI (Compose)

Compose tests con `createComposeRule()`:
- Pantalla principal renderiza estado correcto en cada `SessionState`.
- Animaciones de transición no consumen CPU >5% en idle.
- Botón Start/Stop respeta estado del motor.
- Indicador de fuga DNS aparece y bloquea Connected.

## Matriz de cobertura mínima por release

| Capa | Cobertura mínima |
|------|------------------|
| Parsers (`gravital-proto`) | 95% line, 100% branch crítica |
| State machine (`gravital-engine`) | 100% transiciones |
| FFI surface | 100% funciones expuestas |
| Network engine I/O | 80% line, hot path 100% |
| Control plane Kotlin | 60% line |
| UI Compose | 40% line, todos los estados visuales |

## Tests "destructivos"

Una vez por sprint, en hardware dedicado:
- Activar/desactivar Wi-Fi 1000 veces. Sesión debe recuperarse cada vez.
- Llenar el storage del cliente. App debe degradarse limpio, sin crash.
- Mata el proceso del motor con `kill -9`. Control plane lo detecta y avisa.
- Cambia la IP del Hotspot mientras hay sesión. Cliente reconecta o reporta error legible.

## Cross-references

- Hardening en CI: [`docs/04-engineering/03-cicd.md`](03-cicd.md)
- Threat model: [`docs/04-engineering/02-security.md`](02-security.md)
- Esquema de eventos para asserts: [`docs/04-engineering/01-observability.md`](01-observability.md)
- State machine: [`docs/02-architecture/05-session-state-machine.md`](../02-architecture/05-session-state-machine.md)
