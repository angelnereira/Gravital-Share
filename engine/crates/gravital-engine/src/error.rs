use thiserror::Error;

#[derive(Debug, Error)]
pub enum EngineError {
    #[error("already initialized")]
    AlreadyInitialized,

    #[error("not initialized")]
    NotInitialized,

    #[error("invalid configuration: {0}")]
    ConfigInvalid(String),

    #[error("invalid file descriptor")]
    InvalidFd,

    #[error("invalid state transition")]
    InvalidTransition,

    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("internal panic: {0}")]
    Panic(String),
}

/// FFI error codes — stable across versions.
pub mod ffi_code {
    pub const OK: i32                  =  0;
    pub const INVALID_ARG: i32         = -1;
    pub const ALREADY_INITIALIZED: i32 = -2;
    pub const NOT_INITIALIZED: i32     = -3;
    pub const INVALID_FD: i32          = -4;
    pub const CONFIG_INVALID: i32      = -5;
    pub const INTERNAL_PANIC: i32      = -6;
    pub const BUFFER_TOO_SMALL: i32    = -7;
    pub const IO_ERROR: i32            = -8;
    pub const STATE_TRANSITION_INVALID: i32 = -9;
}
