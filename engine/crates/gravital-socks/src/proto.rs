use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr, IpAddr};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use crate::error::SocksError;

// ── Constants ─────────────────────────────────────────────────────────────────

pub const VERSION: u8 = 0x05;
pub const METHOD_NO_AUTH: u8 = 0x00;
pub const METHOD_USERPASS: u8 = 0x02;
pub const METHOD_NONE_ACCEPTABLE: u8 = 0xFF;

// ── Commands ──────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Cmd {
    Connect,
    Bind,
    UdpAssociate,
}

impl TryFrom<u8> for Cmd {
    type Error = SocksError;
    fn try_from(v: u8) -> Result<Self, Self::Error> {
        match v {
            0x01 => Ok(Cmd::Connect),
            0x02 => Ok(Cmd::Bind),
            0x03 => Ok(Cmd::UdpAssociate),
            x    => Err(SocksError::CommandNotSupported(x)),
        }
    }
}

// ── Reply codes ───────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Rep {
    Succeeded         = 0x00,
    GeneralFailure    = 0x01,
    NotAllowed        = 0x02,
    NetUnreachable    = 0x03,
    HostUnreachable   = 0x04,
    ConnectionRefused = 0x05,
    TtlExpired        = 0x06,
    CommandNotSupported = 0x07,
    AddrTypeNotSupported = 0x08,
}

// ── Address spec ──────────────────────────────────────────────────────────────

#[derive(Debug, Clone)]
pub enum AddrSpec {
    Ipv4(Ipv4Addr),
    Ipv6(Ipv6Addr),
    Domain(String),
}

impl AddrSpec {
    pub async fn to_socket_addr(&self, port: u16) -> Result<SocketAddr, SocksError> {
        match self {
            AddrSpec::Ipv4(ip) => Ok(SocketAddr::new(IpAddr::V4(*ip), port)),
            AddrSpec::Ipv6(ip) => Ok(SocketAddr::new(IpAddr::V6(*ip), port)),
            AddrSpec::Domain(host) => {
                tokio::net::lookup_host((host.as_str(), port))
                    .await
                    .map_err(SocksError::Io)?
                    .next()
                    .ok_or(SocksError::HostUnreachable)
            }
        }
    }
}

// ── Request ───────────────────────────────────────────────────────────────────

pub struct Request {
    pub cmd: Cmd,
    pub addr: AddrSpec,
    pub port: u16,
}

pub async fn read_request<R: AsyncReadExt + Unpin>(r: &mut R) -> Result<Request, SocksError> {
    let mut hdr = [0u8; 4];
    r.read_exact(&mut hdr).await.map_err(map_eof)?;

    if hdr[0] != VERSION {
        return Err(SocksError::InvalidVersion(hdr[0]));
    }
    let cmd = Cmd::try_from(hdr[1])?;
    // hdr[2] is RSV, must be 0x00
    let atyp = hdr[3];

    let addr = match atyp {
        0x01 => {
            let mut ip = [0u8; 4];
            r.read_exact(&mut ip).await.map_err(map_eof)?;
            AddrSpec::Ipv4(Ipv4Addr::from(ip))
        }
        0x03 => {
            let len = r.read_u8().await.map_err(map_eof)? as usize;
            let mut domain = vec![0u8; len];
            r.read_exact(&mut domain).await.map_err(map_eof)?;
            AddrSpec::Domain(String::from_utf8_lossy(&domain).into_owned())
        }
        0x04 => {
            let mut ip = [0u8; 16];
            r.read_exact(&mut ip).await.map_err(map_eof)?;
            AddrSpec::Ipv6(Ipv6Addr::from(ip))
        }
        x => return Err(SocksError::AddressTypeNotSupported(x)),
    };

    let port = r.read_u16().await.map_err(map_eof)?;
    Ok(Request { cmd, addr, port })
}

/// Encode address into SOCKS5 wire format and append to buf.
pub fn encode_address(addr: &AddrSpec, port: u16, buf: &mut Vec<u8>) {
    match addr {
        AddrSpec::Ipv4(ip) => {
            buf.push(0x01);
            buf.extend_from_slice(&ip.octets());
        }
        AddrSpec::Ipv6(ip) => {
            buf.push(0x04);
            buf.extend_from_slice(&ip.octets());
        }
        AddrSpec::Domain(d) => {
            buf.push(0x03);
            let bytes = d.as_bytes();
            buf.push(bytes.len() as u8);
            buf.extend_from_slice(bytes);
        }
    }
    buf.extend_from_slice(&port.to_be_bytes());
}

/// Write a SOCKS5 reply to the writer.
pub async fn send_reply<W: AsyncWriteExt + Unpin>(
    w: &mut W,
    rep: Rep,
    bind_addr: &SocketAddr,
) -> Result<(), SocksError> {
    let mut buf = vec![VERSION, rep as u8, 0x00];
    match bind_addr.ip() {
        IpAddr::V4(ip) => {
            buf.push(0x01);
            buf.extend_from_slice(&ip.octets());
        }
        IpAddr::V6(ip) => {
            buf.push(0x04);
            buf.extend_from_slice(&ip.octets());
        }
    }
    buf.extend_from_slice(&bind_addr.port().to_be_bytes());
    w.write_all(&buf).await.map_err(SocksError::Io)
}

/// Negotiate auth method. Returns the chosen method byte.
pub async fn negotiate_method<S: AsyncReadExt + AsyncWriteExt + Unpin>(
    s: &mut S,
    server_auth_enabled: bool,
) -> Result<u8, SocksError> {
    let mut hdr = [0u8; 2];
    s.read_exact(&mut hdr).await.map_err(map_eof)?;

    if hdr[0] != VERSION {
        return Err(SocksError::InvalidVersion(hdr[0]));
    }
    let nmethods = hdr[1] as usize;
    let mut methods = vec![0u8; nmethods];
    s.read_exact(&mut methods).await.map_err(map_eof)?;

    let chosen = if methods.contains(&METHOD_NO_AUTH) {
        METHOD_NO_AUTH
    } else if server_auth_enabled && methods.contains(&METHOD_USERPASS) {
        METHOD_USERPASS
    } else {
        s.write_all(&[VERSION, METHOD_NONE_ACCEPTABLE]).await.map_err(SocksError::Io)?;
        return Err(SocksError::NoAcceptableMethod);
    };

    s.write_all(&[VERSION, chosen]).await.map_err(SocksError::Io)?;
    Ok(chosen)
}

/// Skip the BND.ADDR + BND.PORT from a reply (client-side after CONNECT response).
pub async fn skip_bound_address<R: AsyncReadExt + Unpin>(r: &mut R, atyp: u8) -> Result<(), SocksError> {
    let addr_len = match atyp {
        0x01 => 4usize,
        0x03 => {
            let len = r.read_u8().await.map_err(map_eof)?;
            len as usize
        }
        0x04 => 16usize,
        _ => return Ok(()),
    };
    let mut discard = vec![0u8; addr_len + 2]; // +2 for port
    r.read_exact(&mut discard).await.map_err(map_eof)
}

fn map_eof(e: std::io::Error) -> SocksError {
    if e.kind() == std::io::ErrorKind::UnexpectedEof {
        SocksError::PrematureEof
    } else {
        SocksError::Io(e)
    }
}

// ── UDP ASSOCIATE datagram header ─────────────────────────────────────────────

/// SOCKS5 UDP request header (RFC 1928 §7).
pub struct UdpHeader {
    pub frag: u8,
    pub addr: AddrSpec,
    pub port: u16,
    pub payload_offset: usize,
}

pub fn parse_udp_datagram(buf: &[u8]) -> Result<UdpHeader, SocksError> {
    if buf.len() < 4 {
        return Err(SocksError::PrematureEof);
    }
    // RSV (2 bytes) + FRAG (1 byte)
    let frag = buf[2];
    let atyp = buf[3];
    let mut offset = 4;

    let addr = match atyp {
        0x01 => {
            if buf.len() < offset + 4 { return Err(SocksError::PrematureEof); }
            let ip = Ipv4Addr::new(buf[offset], buf[offset+1], buf[offset+2], buf[offset+3]);
            offset += 4;
            AddrSpec::Ipv4(ip)
        }
        0x03 => {
            if buf.len() < offset + 1 { return Err(SocksError::PrematureEof); }
            let len = buf[offset] as usize;
            offset += 1;
            if buf.len() < offset + len { return Err(SocksError::PrematureEof); }
            let domain = String::from_utf8_lossy(&buf[offset..offset+len]).into_owned();
            offset += len;
            AddrSpec::Domain(domain)
        }
        0x04 => {
            if buf.len() < offset + 16 { return Err(SocksError::PrematureEof); }
            let mut ip = [0u8; 16];
            ip.copy_from_slice(&buf[offset..offset+16]);
            offset += 16;
            AddrSpec::Ipv6(Ipv6Addr::from(ip))
        }
        x => return Err(SocksError::AddressTypeNotSupported(x)),
    };

    if buf.len() < offset + 2 { return Err(SocksError::PrematureEof); }
    let port = u16::from_be_bytes([buf[offset], buf[offset+1]]);
    offset += 2;

    Ok(UdpHeader { frag, addr, port, payload_offset: offset })
}
