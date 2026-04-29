use thiserror::Error;

#[derive(Debug, Error)]
pub enum UdpGwError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("frame too large: {0} bytes")]
    FrameTooLarge(usize),

    #[error("invalid frame version: {0}")]
    InvalidVersion(u8),

    #[error("truncated frame")]
    Truncated,

    #[error("connection closed")]
    Closed,
}
