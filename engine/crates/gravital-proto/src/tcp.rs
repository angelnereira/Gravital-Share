use crate::error::ParseError;

bitflags::bitflags! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub struct TcpFlags: u8 {
        const FIN = 0x01;
        const SYN = 0x02;
        const RST = 0x04;
        const PSH = 0x08;
        const ACK = 0x10;
        const URG = 0x20;
    }
}

pub struct TcpView<'a> {
    bytes: &'a [u8],
}

impl<'a> TcpView<'a> {
    const MIN_HEADER: usize = 20;

    pub fn parse(bytes: &'a [u8]) -> Result<Self, ParseError> {
        if bytes.len() < Self::MIN_HEADER {
            return Err(ParseError::TruncatedHeader {
                needed: Self::MIN_HEADER,
                got: bytes.len(),
            });
        }
        let data_offset = ((bytes[12] >> 4) as usize) * 4;
        if data_offset < Self::MIN_HEADER {
            return Err(ParseError::InvalidHeaderLength(bytes[12] >> 4));
        }
        if bytes.len() < data_offset {
            return Err(ParseError::TruncatedHeader {
                needed: data_offset,
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
    pub fn seq_num(&self) -> u32 {
        u32::from_be_bytes([self.bytes[4], self.bytes[5], self.bytes[6], self.bytes[7]])
    }

    #[inline]
    pub fn ack_num(&self) -> u32 {
        u32::from_be_bytes([self.bytes[8], self.bytes[9], self.bytes[10], self.bytes[11]])
    }

    #[inline]
    pub fn flags(&self) -> TcpFlags {
        TcpFlags::from_bits_truncate(self.bytes[13])
    }

    #[inline]
    pub fn window_size(&self) -> u16 {
        u16::from_be_bytes([self.bytes[14], self.bytes[15]])
    }

    #[inline]
    fn header_len(&self) -> usize {
        ((self.bytes[12] >> 4) as usize) * 4
    }

    #[inline]
    pub fn payload(&self) -> &'a [u8] {
        &self.bytes[self.header_len()..]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_tcp_syn() {
        let mut seg = [0u8; 20];
        seg[0] = 0x04; seg[1] = 0xD2; // src_port = 1234
        seg[2] = 0x00; seg[3] = 0x50; // dst_port = 80
        seg[12] = 0x50; // data_offset=5 (20 bytes), flags=0
        seg[13] = TcpFlags::SYN.bits();
        let view = TcpView::parse(&seg).unwrap();
        assert_eq!(view.src_port(), 1234);
        assert_eq!(view.dst_port(), 80);
        assert!(view.flags().contains(TcpFlags::SYN));
    }
}
