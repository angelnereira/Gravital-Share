use std::net::{Ipv4Addr, Ipv6Addr};
use crate::error::ParseError;
use crate::checksum;

/// IP protocol numbers we care about.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum IpProtocol {
    Icmp = 1,
    Tcp  = 6,
    Udp  = 17,
    Ipv6Icmp = 58,
    Other(u8),
}

impl From<u8> for IpProtocol {
    fn from(v: u8) -> Self {
        match v {
            1  => Self::Icmp,
            6  => Self::Tcp,
            17 => Self::Udp,
            58 => Self::Ipv6Icmp,
            x  => Self::Other(x),
        }
    }
}

/// Zero-copy view over a raw IP packet buffer.
pub enum IpView<'a> {
    V4(Ipv4View<'a>),
    V6(Ipv6View<'a>),
}

impl<'a> IpView<'a> {
    /// Parse a raw IP packet. Does NOT verify checksum; call `verify_checksum()` separately.
    pub fn parse(buf: &'a [u8]) -> Result<Self, ParseError> {
        if buf.is_empty() {
            return Err(ParseError::TruncatedHeader { needed: 1, got: 0 });
        }
        match buf[0] >> 4 {
            4 => Ok(IpView::V4(Ipv4View::parse(buf)?)),
            6 => Ok(IpView::V6(Ipv6View::parse(buf)?)),
            v => Err(ParseError::InvalidVersion(v)),
        }
    }

    pub fn protocol(&self) -> IpProtocol {
        match self {
            IpView::V4(v) => v.protocol(),
            IpView::V6(v) => v.protocol(),
        }
    }

    pub fn payload(&self) -> &'a [u8] {
        match self {
            IpView::V4(v) => v.payload(),
            IpView::V6(v) => v.payload(),
        }
    }
}

// ── IPv4 ──────────────────────────────────────────────────────────────────────

pub struct Ipv4View<'a> {
    bytes: &'a [u8],
}

impl<'a> Ipv4View<'a> {
    const MIN_HEADER: usize = 20;

    pub fn parse(bytes: &'a [u8]) -> Result<Self, ParseError> {
        if bytes.len() < Self::MIN_HEADER {
            return Err(ParseError::TruncatedHeader {
                needed: Self::MIN_HEADER,
                got: bytes.len(),
            });
        }
        let ihl = (bytes[0] & 0x0F) as usize * 4;
        if ihl < Self::MIN_HEADER {
            return Err(ParseError::InvalidHeaderLength(bytes[0] & 0x0F));
        }
        if bytes.len() < ihl {
            return Err(ParseError::TruncatedHeader { needed: ihl, got: bytes.len() });
        }
        Ok(Self { bytes })
    }

    #[inline]
    fn ihl(&self) -> usize {
        (self.bytes[0] & 0x0F) as usize * 4
    }

    #[inline]
    pub fn src(&self) -> Ipv4Addr {
        Ipv4Addr::new(self.bytes[12], self.bytes[13], self.bytes[14], self.bytes[15])
    }

    #[inline]
    pub fn dst(&self) -> Ipv4Addr {
        Ipv4Addr::new(self.bytes[16], self.bytes[17], self.bytes[18], self.bytes[19])
    }

    #[inline]
    pub fn protocol(&self) -> IpProtocol {
        IpProtocol::from(self.bytes[9])
    }

    #[inline]
    pub fn total_length(&self) -> u16 {
        u16::from_be_bytes([self.bytes[2], self.bytes[3]])
    }

    #[inline]
    pub fn ttl(&self) -> u8 {
        self.bytes[8]
    }

    #[inline]
    pub fn payload(&self) -> &'a [u8] {
        let total = self.total_length() as usize;
        let end = total.min(self.bytes.len());
        &self.bytes[self.ihl()..end]
    }

    pub fn verify_checksum(&self) -> Result<(), ParseError> {
        let hdr = &self.bytes[..self.ihl()];
        let csum = checksum::internet_checksum(hdr);
        if csum != 0 {
            let stored = u16::from_be_bytes([self.bytes[10], self.bytes[11]]);
            return Err(ParseError::InvalidChecksum { expected: 0, got: stored });
        }
        Ok(())
    }

    pub fn raw(&self) -> &'a [u8] {
        self.bytes
    }
}

// ── IPv6 ──────────────────────────────────────────────────────────────────────

pub struct Ipv6View<'a> {
    bytes: &'a [u8],
}

impl<'a> Ipv6View<'a> {
    const HEADER_LEN: usize = 40;

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
    pub fn src(&self) -> Ipv6Addr {
        let mut b = [0u8; 16];
        b.copy_from_slice(&self.bytes[8..24]);
        Ipv6Addr::from(b)
    }

    #[inline]
    pub fn dst(&self) -> Ipv6Addr {
        let mut b = [0u8; 16];
        b.copy_from_slice(&self.bytes[24..40]);
        Ipv6Addr::from(b)
    }

    #[inline]
    pub fn protocol(&self) -> IpProtocol {
        IpProtocol::from(self.bytes[6])
    }

    #[inline]
    pub fn payload_length(&self) -> u16 {
        u16::from_be_bytes([self.bytes[4], self.bytes[5]])
    }

    #[inline]
    pub fn payload(&self) -> &'a [u8] {
        let end = (Self::HEADER_LEN + self.payload_length() as usize).min(self.bytes.len());
        &self.bytes[Self::HEADER_LEN..end]
    }

    pub fn raw(&self) -> &'a [u8] {
        self.bytes
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ipv4_packet() -> Vec<u8> {
        // Minimal IPv4/TCP packet (no options, no payload)
        let mut pkt = vec![
            0x45, 0x00, 0x00, 0x28, // ver/ihl, dscp, total_len=40
            0x00, 0x00, 0x40, 0x00, // id, flags+frag
            0x40, 0x06, 0x00, 0x00, // ttl=64, proto=TCP(6), checksum=0
            0xC0, 0xA8, 0x01, 0x01, // src=192.168.1.1
            0xC0, 0xA8, 0x01, 0x02, // dst=192.168.1.2
            // TCP header stub (20 bytes)
            0x04, 0xD2, 0x00, 0x50, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x50, 0x02, 0x20, 0x00,
            0x00, 0x00, 0x00, 0x00,
        ];
        // Fix IP checksum
        let csum = checksum::internet_checksum(&pkt[..20]);
        pkt[10] = (csum >> 8) as u8;
        pkt[11] = (csum & 0xFF) as u8;
        pkt
    }

    #[test]
    fn parse_ipv4() {
        let pkt = ipv4_packet();
        let view = Ipv4View::parse(&pkt).unwrap();
        assert_eq!(view.src(), Ipv4Addr::new(192, 168, 1, 1));
        assert_eq!(view.dst(), Ipv4Addr::new(192, 168, 1, 2));
        assert_eq!(view.protocol(), IpProtocol::Tcp);
        view.verify_checksum().unwrap();
    }

    #[test]
    fn truncated_ipv4() {
        let pkt = [0x45u8, 0x00];
        assert!(Ipv4View::parse(&pkt).is_err());
    }

    #[test]
    fn invalid_version() {
        let pkt = [0x35u8; 20]; // version=3
        assert!(matches!(IpView::parse(&pkt), Err(ParseError::InvalidVersion(3))));
    }
}
