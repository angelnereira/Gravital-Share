use thiserror::Error;

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum ParseError {
    #[error("buffer too short: need {needed} bytes, got {got}")]
    TruncatedHeader { needed: usize, got: usize },

    #[error("invalid IP version: {0}")]
    InvalidVersion(u8),

    #[error("invalid checksum: expected {expected:#06x}, got {got:#06x}")]
    InvalidChecksum { expected: u16, got: u16 },

    #[error("unsupported protocol: {0}")]
    UnsupportedProtocol(u8),

    #[error("invalid header length: {0}")]
    InvalidHeaderLength(u8),

    #[error("packet too large: {0} bytes")]
    PacketTooLarge(usize),
}
