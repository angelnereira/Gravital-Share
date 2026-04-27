use crate::error::ParseError;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IcmpType {
    EchoReply,
    EchoRequest,
    DestinationUnreachable(u8),
    TimeExceeded(u8),
    Other(u8, u8),
}

pub struct IcmpView<'a> {
    bytes: &'a [u8],
}

impl<'a> IcmpView<'a> {
    const MIN_HEADER: usize = 4;

    pub fn parse(bytes: &'a [u8]) -> Result<Self, ParseError> {
        if bytes.len() < Self::MIN_HEADER {
            return Err(ParseError::TruncatedHeader {
                needed: Self::MIN_HEADER,
                got: bytes.len(),
            });
        }
        Ok(Self { bytes })
    }

    #[inline]
    pub fn icmp_type(&self) -> IcmpType {
        match (self.bytes[0], self.bytes[1]) {
            (0, 0) => IcmpType::EchoReply,
            (8, 0) => IcmpType::EchoRequest,
            (3, code) => IcmpType::DestinationUnreachable(code),
            (11, code) => IcmpType::TimeExceeded(code),
            (t, c) => IcmpType::Other(t, c),
        }
    }

    #[inline]
    pub fn checksum(&self) -> u16 {
        u16::from_be_bytes([self.bytes[2], self.bytes[3]])
    }

    #[inline]
    pub fn payload(&self) -> &'a [u8] {
        &self.bytes[Self::MIN_HEADER..]
    }
}
