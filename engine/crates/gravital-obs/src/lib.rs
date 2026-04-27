#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::pedantic)]
#![allow(clippy::module_name_repetitions)]

pub mod event;
pub mod layer;
pub mod sink;
pub mod mcp;

pub use event::{GsEvent, Level, EventKind};
pub use layer::GravitalLayer;
pub use sink::{Sink, StdoutSink};

use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use std::sync::Arc;

static GLOBAL_SINK: OnceCell<Arc<Mutex<dyn Sink>>> = OnceCell::new();

/// Initialize the global observability sink. Call once at startup.
pub fn init(sink: Arc<Mutex<dyn Sink>>) {
    let _ = GLOBAL_SINK.set(sink);
}

/// Emit a structured event to the global sink.
pub fn emit(event: GsEvent) {
    if let Some(sink) = GLOBAL_SINK.get() {
        let json = match serde_json::to_string(&event) {
            Ok(j) => j,
            Err(_) => return,
        };
        sink.lock().write(&json);
    }
}

/// Convenience macro for emitting structured events.
#[macro_export]
macro_rules! gs_event {
    ($level:expr, $kind:expr, $module:expr, $payload:expr) => {{
        $crate::emit($crate::event::GsEvent::new($level, $kind, $module, $payload));
    }};
    ($level:expr, $kind:expr, $payload:expr) => {{
        $crate::emit($crate::event::GsEvent::new(
            $level,
            $kind,
            module_path!(),
            $payload,
        ));
    }};
}
