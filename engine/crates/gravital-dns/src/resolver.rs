use std::net::{IpAddr, SocketAddr, Ipv4Addr};
use tokio::net::UdpSocket;
use tokio::time::{timeout, Duration};
use crate::error::DnsError;

/// Default public DNS resolver — Cloudflare.
pub const DEFAULT_RESOLVER: Ipv4Addr = Ipv4Addr::new(1, 1, 1, 1);
pub const DNS_PORT: u16 = 53;
pub const RESOLVE_TIMEOUT: Duration = Duration::from_secs(5);

/// Resolver that sends queries through a protected socket (bypassing the VPN tunnel).
/// The caller is responsible for calling protect() on the underlying fd before use.
pub struct DnsResolver {
    server: SocketAddr,
}

impl DnsResolver {
    pub fn new(server: IpAddr) -> Self {
        Self {
            server: SocketAddr::new(server, DNS_PORT),
        }
    }

    pub fn with_default() -> Self {
        Self::new(IpAddr::V4(DEFAULT_RESOLVER))
    }

    /// Forward a raw DNS query and return the raw response.
    /// The `protected_fd` has already had VpnService.protect() applied.
    pub async fn forward_raw(&self, query: &[u8]) -> Result<Vec<u8>, DnsError> {
        let sock = UdpSocket::bind("0.0.0.0:0").await.map_err(DnsError::Io)?;
        sock.connect(self.server).await.map_err(DnsError::Io)?;

        sock.send(query).await.map_err(DnsError::Io)?;

        let mut resp = vec![0u8; 4096];
        let n = timeout(RESOLVE_TIMEOUT, sock.recv(&mut resp))
            .await
            .map_err(|_| DnsError::Timeout("query".into()))?
            .map_err(DnsError::Io)?;

        resp.truncate(n);
        Ok(resp)
    }

    pub fn server_addr(&self) -> SocketAddr {
        self.server
    }
}
