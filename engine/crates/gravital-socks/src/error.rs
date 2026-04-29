use thiserror::Error;

#[derive(Debug, Error)]
pub enum SocksError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("invalid SOCKS version: {0}")]
    InvalidVersion(u8),

    #[error("no acceptable authentication method")]
    NoAcceptableMethod,

    #[error("authentication rejected")]
    AuthRejected,

    #[error("host unreachable")]
    HostUnreachable,

    #[error("connection refused")]
    ConnectionRefused,

    #[error("command not supported: {0:?}")]
    CommandNotSupported(u8),

    #[error("address type not supported: {0}")]
    AddressTypeNotSupported(u8),

    #[error("upstream error: {0}")]
    Upstream(std::io::Error),

    #[error("server reply {0}")]
    ServerReply(u8),

    #[error("premature EOF")]
    PrematureEof,

    #[error("timeout")]
    Timeout,
}
