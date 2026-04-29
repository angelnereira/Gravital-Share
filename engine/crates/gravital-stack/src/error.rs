use thiserror::Error;

#[derive(Debug, Error)]
pub enum StackError {
    #[error("MTU out of range: {0}")]
    InvalidMtu(u16),

    #[error("socket exhausted — no free handles")]
    SocketExhausted,

    #[error("connection reset by peer")]
    ConnectionReset,

    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
}
