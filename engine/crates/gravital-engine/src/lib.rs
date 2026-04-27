#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::pedantic)]
#![allow(clippy::module_name_repetitions)]

pub mod config;
pub mod error;
pub mod metrics;
pub mod session;
pub mod runner;

pub use config::{EngineConfig, ClientConfig, ServerConfig};
pub use error::EngineError;
pub use metrics::EngineMetrics;
pub use session::{SessionState, SessionMode, SessionEvent, FailureKind};
pub use runner::Engine;
