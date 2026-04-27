use thiserror::Error;

#[derive(Debug, Error)]
pub enum TunError {
    #[error("invalid file descriptor: {0}")]
    InvalidFd(i32),

    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("TUN device closed")]
    Closed,

    #[error("packet too large: {size} > MTU {mtu}")]
    PacketTooLarge { size: usize, mtu: u16 },
}
