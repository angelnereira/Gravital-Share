use thiserror::Error;

#[derive(Debug, Error)]
pub enum DnsError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("DNS resolution failed for {host}: {reason}")]
    ResolveFailed { host: String, reason: String },

    #[error("DNS leak detected — query escaped the tunnel")]
    LeakDetected,

    #[error("invalid DNS packet")]
    InvalidPacket,

    #[error("timeout resolving {0}")]
    Timeout(String),
}
