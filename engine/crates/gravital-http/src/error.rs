use thiserror::Error;

#[derive(Debug, Error)]
pub enum HttpProxyError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("parse error: {0}")]
    Parse(String),

    #[error("only CONNECT method is supported, got: {0}")]
    UnsupportedMethod(String),

    #[error("missing CONNECT target")]
    MissingTarget,

    #[error("invalid host:port format: {0}")]
    InvalidTarget(String),

    #[error("upstream connection failed")]
    UpstreamFailed,

    #[error("headers too large (> 8 KiB)")]
    HeadersTooLarge,

    #[error("premature EOF")]
    PrematureEof,

    #[error("authentication required")]
    AuthRequired,
}
