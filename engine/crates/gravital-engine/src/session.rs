use std::time::Instant;
use parking_lot::RwLock;
use serde::{Deserialize, Serialize};
use crate::error::EngineError;

// ── State ──────────────────────────────────────────────────────────────────────

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum SessionMode {
    Client,
    Server,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum StopReason {
    UserRequest,
    FatalError,
    Shutdown,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
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

impl FailureKind {
    pub fn is_recoverable(&self) -> bool {
        matches!(self, FailureKind::ProxyUnreachable | FailureKind::DnsResolveFailed)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PeerInfo {
    pub proxy_addr: std::net::SocketAddr,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SessionState {
    Idle,
    Preparing { mode: SessionMode },
    Connecting { mode: SessionMode, attempt: u32 },
    Connected { mode: SessionMode, peer: PeerInfo },
    Reconnecting { mode: SessionMode, attempt: u32, last_error: Option<String> },
    Stopping { mode: SessionMode, reason: StopReason },
    Failed { mode: SessionMode, error: FailureKind, recoverable: bool },
}

impl SessionState {
    pub fn mode(&self) -> Option<&SessionMode> {
        match self {
            SessionState::Idle => None,
            SessionState::Preparing { mode } => Some(mode),
            SessionState::Connecting { mode, .. } => Some(mode),
            SessionState::Connected { mode, .. } => Some(mode),
            SessionState::Reconnecting { mode, .. } => Some(mode),
            SessionState::Stopping { mode, .. } => Some(mode),
            SessionState::Failed { mode, .. } => Some(mode),
        }
    }

    pub fn is_active(&self) -> bool {
        matches!(self, SessionState::Connected { .. })
    }
}

// ── Events ────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone)]
pub enum SessionEvent {
    UserStart(SessionMode),
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
    Shutdown,
}

// ── Automaton ─────────────────────────────────────────────────────────────────

pub struct Session {
    state: RwLock<SessionState>,
    listeners: parking_lot::Mutex<Vec<Box<dyn Fn(&SessionState) + Send + Sync>>>,
}

impl Session {
    pub fn new() -> Self {
        Self {
            state: RwLock::new(SessionState::Idle),
            listeners: parking_lot::Mutex::new(Vec::new()),
        }
    }

    pub fn state(&self) -> SessionState {
        self.state.read().clone()
    }

    pub fn add_listener<F: Fn(&SessionState) + Send + Sync + 'static>(&self, f: F) {
        self.listeners.lock().push(Box::new(f));
    }

    pub fn dispatch(&self, event: SessionEvent) -> Result<(), EngineError> {
        let current = self.state.read().clone();
        let next = transition(&current, &event)?;

        if next != current {
            tracing::info!(
                kind = "session.transition",
                from = ?current,
                to = ?next,
                event = ?event
            );
            *self.state.write() = next.clone();
            let listeners = self.listeners.lock();
            for listener in listeners.iter() {
                listener(&next);
            }
        }
        Ok(())
    }
}

impl Default for Session {
    fn default() -> Self {
        Self::new()
    }
}

// ── Transition function ───────────────────────────────────────────────────────

fn transition(s: &SessionState, e: &SessionEvent) -> Result<SessionState, EngineError> {
    use SessionState::*;
    use SessionEvent::*;

    // Fatal error and Shutdown can come from any state
    if let FatalError(kind) = e {
        let mode = s.mode().cloned().unwrap_or(SessionMode::Client);
        let recoverable = kind.is_recoverable();
        return Ok(Failed { mode, error: kind.clone(), recoverable });
    }
    if let Shutdown = e {
        let mode = s.mode().cloned().unwrap_or(SessionMode::Client);
        return Ok(Stopping { mode, reason: StopReason::Shutdown });
    }

    Ok(match (s, e) {
        (Idle, UserStart(mode)) => Preparing { mode: mode.clone() },

        (Preparing { mode }, EngineReady) => Connecting { mode: mode.clone(), attempt: 1 },
        (Preparing { mode }, EnginePrepared) => Preparing { mode: mode.clone() },

        (Connecting { mode, .. }, ProxyConnected(peer)) => Connected {
            mode: mode.clone(),
            peer: peer.clone(),
        },
        (Connecting { mode, attempt }, NetworkLost | ProxyDisconnected(_)) => Reconnecting {
            mode: mode.clone(),
            attempt: *attempt,
            last_error: None,
        },

        (Connected { mode, .. }, NetworkLost | ProxyDisconnected(_)) => Reconnecting {
            mode: mode.clone(),
            attempt: 1,
            last_error: None,
        },
        (Connected { mode, .. }, UserStop) => Stopping {
            mode: mode.clone(),
            reason: StopReason::UserRequest,
        },

        (Reconnecting { mode, attempt, .. }, ProxyConnected(peer)) => Connected {
            mode: mode.clone(),
            peer: peer.clone(),
        },
        (Reconnecting { mode, .. }, UserStop) => Stopping {
            mode: mode.clone(),
            reason: StopReason::UserRequest,
        },

        (Stopping { .. }, EngineStopped) => Idle,

        (Failed { .. }, UserAcknowledge) => Idle,

        (current, event) => {
            tracing::warn!(
                kind = "session.invalid_transition",
                state = ?current,
                event = ?event
            );
            return Err(EngineError::InvalidTransition);
        }
    })
}

// ── Backoff ───────────────────────────────────────────────────────────────────

pub fn backoff_delay(attempt: u32) -> std::time::Duration {
    let ms = 250u64.saturating_mul(2u64.saturating_pow(attempt.saturating_sub(1)));
    std::time::Duration::from_millis(ms.min(30_000))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn peer() -> PeerInfo {
        PeerInfo { proxy_addr: "127.0.0.1:1080".parse().unwrap() }
    }

    #[test]
    fn happy_path_client() {
        let session = Session::new();
        session.dispatch(SessionEvent::UserStart(SessionMode::Client)).unwrap();
        assert!(matches!(session.state(), SessionState::Preparing { .. }));
        session.dispatch(SessionEvent::EngineReady).unwrap();
        assert!(matches!(session.state(), SessionState::Connecting { .. }));
        session.dispatch(SessionEvent::ProxyConnected(peer())).unwrap();
        assert!(session.state().is_active());
        session.dispatch(SessionEvent::UserStop).unwrap();
        assert!(matches!(session.state(), SessionState::Stopping { .. }));
        session.dispatch(SessionEvent::EngineStopped).unwrap();
        assert_eq!(session.state(), SessionState::Idle);
    }

    #[test]
    fn reconnect_path() {
        let session = Session::new();
        session.dispatch(SessionEvent::UserStart(SessionMode::Client)).unwrap();
        session.dispatch(SessionEvent::EngineReady).unwrap();
        session.dispatch(SessionEvent::ProxyConnected(peer())).unwrap();
        session.dispatch(SessionEvent::NetworkLost).unwrap();
        assert!(matches!(session.state(), SessionState::Reconnecting { .. }));
        session.dispatch(SessionEvent::ProxyConnected(peer())).unwrap();
        assert!(session.state().is_active());
    }

    #[test]
    fn fatal_error_from_any_state() {
        let session = Session::new();
        session.dispatch(SessionEvent::UserStart(SessionMode::Server)).unwrap();
        session.dispatch(SessionEvent::FatalError(FailureKind::TunOpenFailed)).unwrap();
        assert!(matches!(session.state(), SessionState::Failed { .. }));
        session.dispatch(SessionEvent::UserAcknowledge).unwrap();
        assert_eq!(session.state(), SessionState::Idle);
    }

    #[test]
    fn invalid_transition_rejected() {
        let session = Session::new();
        // Can't send ProxyConnected from Idle
        assert!(session.dispatch(SessionEvent::ProxyConnected(peer())).is_err());
        assert_eq!(session.state(), SessionState::Idle);
    }

    #[test]
    fn backoff_sequence() {
        assert_eq!(backoff_delay(1).as_millis(), 250);
        assert_eq!(backoff_delay(2).as_millis(), 500);
        assert_eq!(backoff_delay(3).as_millis(), 1000);
        assert_eq!(backoff_delay(10).as_millis(), 30_000);
    }
}
