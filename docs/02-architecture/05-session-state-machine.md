# 02.05 — La Sesión como Máquina de Estados

## Por qué un autómata explícito

En clientes VPN reales el bug más caro es el de estado mal manejado. Banderas como `isConnected`, `isReconnecting`, `isPaused` se vuelven inconsistentes en cuestión de semanas. La sesión de Gravital Share es **un solo objeto** con **un solo estado en cualquier momento**, y todas las transiciones son explícitas.

## Estados

```
                    ┌────────────────┐
                    │     Idle       │  ← estado inicial y final
                    └───────┬────────┘
                            │ user.start
                            ▼
                    ┌────────────────┐
                    │   Preparing    │  permisos, validar config
                    └───────┬────────┘
                            │ engine.ready
                            ▼
                    ┌────────────────┐
                    │   Connecting   │  abriendo proxy, handshake
                    └───────┬────────┘
                            │ proxy.connected
                            ▼
                    ┌────────────────┐
                ┌──▶│   Connected    │  ── flujo de datos normal ──┐
                │   └───────┬────────┘                              │
                │           │ network.lost                          │
                │           ▼                                        │
                │   ┌────────────────┐                              │
                │   │  Reconnecting  │  backoff exponencial         │
                │   └───────┬────────┘                              │
                │           │                                        │
                └───────────┘ proxy.connected                       │
                            │                                        │
                            │ user.stop / fatal.error                │
                            ▼                                        │
                    ┌────────────────┐  ◀──────── user.stop ─────────┘
                    │   Stopping     │  cierre limpio
                    └───────┬────────┘
                            │ engine.stopped
                            ▼
                    ┌────────────────┐
                    │     Idle       │
                    └────────────────┘
```

Un estado especial transversal:

```
                    ┌────────────────┐
                    │     Failed     │  ← se entra desde cualquier estado
                    └───────┬────────┘     en evento fatal.error
                            │ user.acknowledge
                            ▼
                    ┌────────────────┐
                    │     Idle       │
                    └────────────────┘
```

## Definición formal en Rust

```rust
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SessionState {
    Idle,
    Preparing { mode: SessionMode },
    Connecting { mode: SessionMode, attempt: u32 },
    Connected { mode: SessionMode, since: Instant, peer: PeerInfo },
    Reconnecting { mode: SessionMode, attempt: u32, last_error: Option<String> },
    Stopping { mode: SessionMode, reason: StopReason },
    Failed { mode: SessionMode, error: FailureKind, recoverable: bool },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SessionMode { Client, Server }

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum StopReason { UserRequest, FatalError, Shutdown }

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FailureKind {
    PermissionDenied,
    ConfigInvalid,
    ProxyUnreachable,
    DnsResolveFailed,
    TunOpenFailed,
    EngineCrashed,
    UpstreamVpnDown,
    Unknown,
}
```

## Eventos que provocan transición

```rust
pub enum SessionEvent {
    UserStart(SessionMode, Config),
    UserStop,
    UserAcknowledge,
    
    EnginePrepared,
    EngineReady,
    EngineStopped,
    
    ProxyConnected(PeerInfo),
    ProxyDisconnected(Option<String>),
    
    NetworkLost,
    NetworkRestored,
    
    FatalError(FailureKind),
}
```

## Tabla de transiciones válidas

| Estado actual | Evento | Estado siguiente | Acción |
|---|---|---|---|
| Idle | UserStart | Preparing | validar config, pedir permisos |
| Preparing | EngineReady | Connecting | abrir proxy / FD |
| Preparing | FatalError | Failed | mostrar error |
| Connecting | ProxyConnected | Connected | iniciar bombas IO |
| Connecting | NetworkLost | Reconnecting | backoff |
| Connecting | FatalError | Failed | mostrar error |
| Connected | NetworkLost | Reconnecting | iniciar reintento |
| Connected | UserStop | Stopping | señal de cierre |
| Connected | FatalError | Failed | cierre, mostrar error |
| Reconnecting | ProxyConnected | Connected | reanudar |
| Reconnecting | UserStop | Stopping | abortar reintento |
| Reconnecting | FatalError | Failed | abortar, mostrar error |
| Stopping | EngineStopped | Idle | limpiar |
| Failed | UserAcknowledge | Idle | limpiar |
| * | Shutdown | Stopping | cierre forzoso |

**Cualquier evento no listado en una fila significa transición no permitida**. El motor lo registra como `obs.kind=session.invalid_transition` y deja el estado intacto.

## Implementación

```rust
pub struct Session {
    state: RwLock<SessionState>,
    listeners: Vec<Box<dyn Fn(&SessionState) + Send + Sync>>,
    metrics: Arc<Metrics>,
}

impl Session {
    pub fn dispatch(&self, event: SessionEvent) -> Result<()> {
        let mut state = self.state.write();
        let next = transition(&state, &event)?;
        if next != *state {
            obs::event!(INFO, "session.transition", from = ?*state, to = ?next, event = ?event);
            *state = next.clone();
            drop(state);
            for l in &self.listeners {
                l(&next);
            }
        }
        Ok(())
    }
}

fn transition(s: &SessionState, e: &SessionEvent) -> Result<SessionState> {
    use SessionState::*;
    use SessionEvent::*;
    Ok(match (s, e) {
        (Idle, UserStart(mode, _cfg)) => Preparing { mode: mode.clone() },
        (Preparing { mode }, EngineReady) => Connecting { mode: mode.clone(), attempt: 1 },
        (Connecting { mode, .. }, ProxyConnected(peer)) => Connected {
            mode: mode.clone(),
            since: Instant::now(),
            peer: peer.clone(),
        },
        (Connected { mode, .. }, NetworkLost) => Reconnecting {
            mode: mode.clone(),
            attempt: 1,
            last_error: None,
        },
        (Reconnecting { mode, .. }, ProxyConnected(peer)) => Connected {
            mode: mode.clone(),
            since: Instant::now(),
            peer: peer.clone(),
        },
        (Connected { mode, .. }, UserStop) => Stopping {
            mode: mode.clone(),
            reason: StopReason::UserRequest,
        },
        (Stopping { .. }, EngineStopped) => Idle,
        (Failed { .. }, UserAcknowledge) => Idle,
        (state, FatalError(kind)) => Failed {
            mode: extract_mode(state).unwrap_or(SessionMode::Client),
            error: kind.clone(),
            recoverable: matches!(kind, FailureKind::ProxyUnreachable | FailureKind::DnsResolveFailed),
        },
        (current, event) => return Err(Error::InvalidTransition(current.clone(), event.clone())),
    })
}
```

## Backoff de reconexión

En `Reconnecting`:

```rust
fn delay_for_attempt(n: u32) -> Duration {
    let ms = 250u64.saturating_mul(2u64.saturating_pow(n.saturating_sub(1)));
    Duration::from_millis(ms.min(30_000)) // cap a 30s
}
```

Secuencia: 250ms → 500ms → 1s → 2s → 4s → 8s → 16s → 30s → 30s ...

Si después de 10 reintentos consecutivos no hemos podido conectar, transición a `Failed { error: ProxyUnreachable, recoverable: true }` para que el usuario decida.

## Reflejo en la UI

La UI **observa** el `StateFlow<SessionState>` y deriva propiedades visuales:

| Estado | Color del indicador | Texto principal | Animación |
|---|---|---|---|
| Idle | Gris | "Listo para conectar" | Estática |
| Preparing | Ámbar | "Preparando…" | Pulso lento |
| Connecting | Ámbar | "Conectando…" | Spinner |
| Connected | Verde | "Conectado" | Estática + indicador de tráfico |
| Reconnecting | Naranja | "Reconectando (intento N)" | Pulso rápido |
| Stopping | Gris | "Cerrando…" | Estática |
| Failed | Rojo | "Error: <causa amigable>" | Estática + botón "Reintentar" |

## Tests obligatorios

- Para cada par (estado, evento) listado en la tabla: test que confirma la transición esperada.
- Para cada par no listado: test que confirma que se rechaza con `InvalidTransition`.
- Test de race conditions: dos eventos casi simultáneos producen exactamente una transición coherente.
- Test de persistencia: serializar el estado, deserializar, comparar. (Importante para diagnostics dump.)

## Por qué este diseño paga

- **Debugging**: el estado actual se imprime en cualquier log relevante. Cuando un usuario reporta "no me conecta", el `state` en el último evento dice exactamente dónde se quedó.
- **UI predecible**: los diseñadores no inventan estados. Si quieren un nuevo color, primero hay que añadir un estado en el autómata.
- **Reintentos correctos**: la lógica de backoff vive en un solo lugar y se prueba una vez.
- **Telemetría útil**: cada transición es un evento estructurado. Las métricas de "tiempo medio en Reconnecting" salen gratis.
