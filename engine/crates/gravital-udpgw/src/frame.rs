use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, IpAddr};
use bytes::{Bytes, BytesMut, BufMut};
use crate::error::UdpGwError;

/// udpgw v1 frame format (badvpn-udpgw compatible):
/// | ver(1) | flags(1) | client_id(4) | addr_type(1) | addr(4 or 16) | port(2) | payload_len(2) | payload |
pub const VERSION: u8 = 1;

bitflags::bitflags! {
    #[derive(Debug, Clone, Copy, PartialEq, Eq)]
    pub struct UdpGwFlags: u8 {
        const IPV6    = 0x01;
        const KEEPALIVE = 0x02;
    }
}

#[derive(Debug, Clone)]
pub struct UdpGwFrame {
    pub client_id: u32,
    pub flags: UdpGwFlags,
    pub remote_addr: SocketAddr,
    pub payload: Bytes,
}

impl UdpGwFrame {
    pub fn encode(&self) -> BytesMut {
        let mut buf = BytesMut::new();
        buf.put_u8(VERSION);
        buf.put_u8(self.flags.bits());
        buf.put_u32(self.client_id);

        match self.remote_addr.ip() {
            IpAddr::V4(ip) => {
                buf.put_u8(0x01); // addr_type IPv4
                buf.put_slice(&ip.octets());
            }
            IpAddr::V6(ip) => {
                buf.put_u8(0x04); // addr_type IPv6
                buf.put_slice(&ip.octets());
            }
        }

        buf.put_u16(self.remote_addr.port());
        buf.put_u16(self.payload.len() as u16);
        buf.put_slice(&self.payload);
        buf
    }

    pub fn decode(data: &[u8]) -> Result<Self, UdpGwError> {
        if data.is_empty() {
            return Err(UdpGwError::Truncated);
        }
        if data[0] != VERSION {
            return Err(UdpGwError::InvalidVersion(data[0]));
        }
        if data.len() < 8 {
            return Err(UdpGwError::Truncated);
        }

        let flags = UdpGwFlags::from_bits_truncate(data[1]);
        let client_id = u32::from_be_bytes([data[2], data[3], data[4], data[5]]);
        let addr_type = data[6];
        let mut offset = 7usize;

        let ip = match addr_type {
            0x01 => {
                if data.len() < offset + 4 { return Err(UdpGwError::Truncated); }
                let ip = Ipv4Addr::new(data[offset], data[offset+1], data[offset+2], data[offset+3]);
                offset += 4;
                IpAddr::V4(ip)
            }
            0x04 => {
                if data.len() < offset + 16 { return Err(UdpGwError::Truncated); }
                let mut b = [0u8; 16];
                b.copy_from_slice(&data[offset..offset+16]);
                offset += 16;
                IpAddr::V6(Ipv6Addr::from(b))
            }
            _ => return Err(UdpGwError::Truncated),
        };

        if data.len() < offset + 4 { return Err(UdpGwError::Truncated); }
        let port = u16::from_be_bytes([data[offset], data[offset+1]]);
        let payload_len = u16::from_be_bytes([data[offset+2], data[offset+3]]) as usize;
        offset += 4;

        if data.len() < offset + payload_len { return Err(UdpGwError::Truncated); }
        let payload = Bytes::copy_from_slice(&data[offset..offset+payload_len]);

        Ok(Self {
            client_id,
            flags,
            remote_addr: SocketAddr::new(ip, port),
            payload,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::SocketAddr;

    #[test]
    fn roundtrip_ipv4() {
        let frame = UdpGwFrame {
            client_id: 42,
            flags: UdpGwFlags::empty(),
            remote_addr: "8.8.8.8:53".parse::<SocketAddr>().unwrap(),
            payload: Bytes::from_static(b"hello"),
        };
        let encoded = frame.encode();
        let decoded = UdpGwFrame::decode(&encoded).unwrap();
        assert_eq!(decoded.client_id, 42);
        assert_eq!(decoded.remote_addr, frame.remote_addr);
        assert_eq!(decoded.payload, Bytes::from_static(b"hello"));
    }
}
