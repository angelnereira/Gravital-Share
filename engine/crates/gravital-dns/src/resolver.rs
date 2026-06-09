use std::net::{IpAddr, SocketAddr, Ipv4Addr};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::time::{timeout, Duration};
use tracing::warn;
use crate::error::DnsError;

pub const DEFAULT_RESOLVER: Ipv4Addr = Ipv4Addr::new(1, 1, 1, 1);
pub const DEFAULT_RESOLVER_SECONDARY: Ipv4Addr = Ipv4Addr::new(8, 8, 8, 8);
pub const DNS_PORT: u16 = 53;
pub const RESOLVE_TIMEOUT: Duration = Duration::from_secs(5);

/// Resolver that forwards DNS queries via SOCKS5 TCP (DNS-over-TCP, RFC 1035 §4.2.2).
///
/// Device B (client) has no direct internet — the app is excluded from its own VPN
/// via addDisallowedApplication, so plain UDP to 1.1.1.1 has no route.  Forwarding
/// via the SOCKS5 proxy (running on Device A, reachable at the hotspot gateway IP)
/// is the only working path.  Tries primary server first, falls back to secondary.
pub struct DnsResolver {
    primary:     SocketAddr,
    secondary:   SocketAddr,
    socks_proxy: SocketAddr,
}

impl DnsResolver {
    pub fn new(primary: IpAddr, secondary: IpAddr, socks_proxy: SocketAddr) -> Self {
        Self {
            primary:     SocketAddr::new(primary,   DNS_PORT),
            secondary:   SocketAddr::new(secondary, DNS_PORT),
            socks_proxy,
        }
    }

    pub fn with_default(socks_proxy: SocketAddr) -> Self {
        Self::new(
            IpAddr::V4(DEFAULT_RESOLVER),
            IpAddr::V4(DEFAULT_RESOLVER_SECONDARY),
            socks_proxy,
        )
    }

    pub fn server_addr(&self) -> SocketAddr { self.primary }

    /// Forward a raw DNS query.  Tries primary, falls back to secondary.
    pub async fn forward_raw(&self, query: &[u8]) -> Result<Vec<u8>, DnsError> {
        match self.forward_via_socks_tcp(query, self.primary).await {
            Ok(resp) => Ok(resp),
            Err(e) => {
                warn!(kind = "dns.primary_failed", error = %e);
                self.forward_via_socks_tcp(query, self.secondary).await
            }
        }
    }

    /// Open a SOCKS5 TCP tunnel to `dns_server:53` and exchange a DNS-over-TCP
    /// query/response (2-byte big-endian length prefix before query and response).
    async fn forward_via_socks_tcp(
        &self,
        query:      &[u8],
        dns_server: SocketAddr,
    ) -> Result<Vec<u8>, DnsError> {
        let mut stream = timeout(RESOLVE_TIMEOUT, TcpStream::connect(self.socks_proxy))
            .await
            .map_err(|_| DnsError::Timeout("socks5 connect".into()))?
            .map_err(DnsError::Io)?;

        // Greeting: v5, one method: no-auth (0x00)
        stream.write_all(&[0x05, 0x01, 0x00]).await.map_err(DnsError::Io)?;
        let mut auth = [0u8; 2];
        stream.read_exact(&mut auth).await.map_err(DnsError::Io)?;
        if auth[0] != 0x05 || auth[1] != 0x00 {
            return Err(DnsError::Protocol(format!("socks5 method={}", auth[1])));
        }

        // CONNECT to dns_server (IPv4 only)
        let ip = match dns_server.ip() {
            IpAddr::V4(v4) => v4.octets(),
            IpAddr::V6(_) => return Err(DnsError::Protocol("IPv6 DNS unsupported".into())),
        };
        let port = dns_server.port();
        let mut req = vec![0x05u8, 0x01, 0x00, 0x01]; // VER CMD RSV ATYP=IPv4
        req.extend_from_slice(&ip);
        req.push((port >> 8) as u8);
        req.push(port as u8);
        stream.write_all(&req).await.map_err(DnsError::Io)?;

        let mut rep = [0u8; 10];
        stream.read_exact(&mut rep).await.map_err(DnsError::Io)?;
        if rep[1] != 0x00 {
            return Err(DnsError::Protocol(format!("socks5 CONNECT rep={}", rep[1])));
        }

        // DNS-over-TCP: 2-byte length + query
        stream.write_all(&(query.len() as u16).to_be_bytes()).await.map_err(DnsError::Io)?;
        stream.write_all(query).await.map_err(DnsError::Io)?;

        // 2-byte length + response
        let mut len_buf = [0u8; 2];
        timeout(RESOLVE_TIMEOUT, stream.read_exact(&mut len_buf))
            .await
            .map_err(|_| DnsError::Timeout("dns tcp response".into()))?
            .map_err(DnsError::Io)?;
        let rlen = u16::from_be_bytes(len_buf) as usize;
        let mut resp = vec![0u8; rlen];
        stream.read_exact(&mut resp).await.map_err(DnsError::Io)?;
        Ok(resp)
    }
}
