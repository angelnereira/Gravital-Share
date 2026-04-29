use chrono::Utc;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use ulid::Ulid;

/// Log level following standard severity conventions.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Level {
    Trace,
    Debug,
    Info,
    Warn,
    Error,
}

impl Level {
    pub fn as_str(self) -> &'static str {
        match self {
            Level::Trace => "trace",
            Level::Debug => "debug",
            Level::Info  => "info",
            Level::Warn  => "warn",
            Level::Error => "error",
        }
    }
}

/// Dotted event kind identifier (e.g. "session.transition", "dns.leak.detected").
pub type EventKind = String;

/// Canonical event schema gs.event.v1.
/// Every event emitted by the engine or the Android app conforms to this structure.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct GsEvent {
    /// RFC 3339 timestamp in UTC with millisecond precision.
    pub ts: String,
    /// Schema version — always "gs.event.v1".
    pub schema: &'static str,
    /// Severity level.
    pub lvl: Level,
    /// Hierarchical dotted kind identifier.
    pub kind: EventKind,
    /// Trace ID (ULID) — identifies the full session.
    pub trace_id: String,
    /// Span ID — identifies the current sub-operation.
    pub span_id: String,
    /// Emitting crate or class.
    pub module: String,
    /// Event-specific structured payload.
    pub payload: Value,
}

impl GsEvent {
    pub fn new(lvl: Level, kind: impl Into<String>, module: impl Into<String>, payload: Value) -> Self {
        Self {
            ts: Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string(),
            schema: "gs.event.v1",
            lvl,
            kind: kind.into(),
            trace_id: Ulid::new().to_string(),
            span_id: format!("{:08x}", rand_span()),
            module: module.into(),
            payload,
        }
    }

    pub fn with_trace(mut self, trace_id: &str) -> Self {
        self.trace_id = trace_id.to_owned();
        self
    }
}

fn rand_span() -> u32 {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    use std::time::SystemTime;
    let mut h = DefaultHasher::new();
    SystemTime::now().hash(&mut h);
    h.finish() as u32
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn event_serializes_to_schema() {
        let ev = GsEvent::new(Level::Info, "session.transition", "gravital-engine", json!({
            "from": "Connecting",
            "to": "Connected",
            "ms": 412
        }));
        let json = serde_json::to_string(&ev).unwrap();
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed["schema"], "gs.event.v1");
        assert_eq!(parsed["lvl"], "info");
        assert_eq!(parsed["kind"], "session.transition");
        assert!(parsed["trace_id"].is_string());
    }
}
