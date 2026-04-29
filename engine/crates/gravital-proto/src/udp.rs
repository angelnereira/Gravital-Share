use crate::error::ParseError;

pub struct UdpView<'a> {
    bytes: &'a [u8],
}

impl<'a> UdpView<'a> {
    const HEADER_LEN: usize = 8;

    pub fn parse(bytes: &'a [u8]) -> Result<Self, ParseError> {
        if bytes.len() < Self::HEADER_LEN {
            return Err(ParseError::TruncatedHeader {
                needed: Self::HEADER_LEN,
                got: bytes.len(),
            });
        }
        Ok(Self { bytes })
    }

    #[inline]
    pub fn src_port(&self) -> u16 {
        u16::from_be_bytes([self.bytes[0], self.bytes[1]])
    }

    #[inline]
    pub fn dst_port(&self) -> u16 {
        u16::from_be_bytes([self.bytes[2], self.bytes[3]])
    }

    #[inline]
    pub fn length(&self) -> u16 {
        u16::from_be_bytes([self.bytes[4], self.bytes[5]])
    }

    #[inline]
    pub fn checksum(&self) -> u16 {
        u16::from_be_bytes([self.bytes[6], self.bytes[7]])
    }

    #[inline]
    pub fn payload(&self) -> &'a [u8] {
        let end = (self.length() as usize).min(self.bytes.len());
        &self.bytes[Self::HEADER_LEN..end]
    }

    pub fn is_dns(&self) -> bool {
        self.dst_port() == 53
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_udp_dns() {
        let mut seg = [0u8; 12];
        seg[0] = 0xC0; seg[1] = 0x1F; // src_port = 49183
        seg[2] = 0x00; seg[3] = 0x35; // dst_port = 53 (DNS)
        seg[4] = 0x00; seg[5] = 0x0C; // length = 12
        let view = UdpView::parse(&seg).unwrap();
        assert_eq!(view.dst_port(), 53);
        assert!(view.is_dns());
    }
}
