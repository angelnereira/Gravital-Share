use std::net::{IpAddr, SocketAddr, Ipv4Addr};
use serde::{Deserialize, Serialize};

pub const DEFAULT_MTU: u16 = 1280;
pub const DEFAULT_DNS: Ipv4Addr = Ipv4Addr::new(1, 1, 1, 1);

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "mode")]
pub enum EngineConfig {
    Client(ClientConfig),
    Server(ServerConfig),
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClientConfig {
    /// SOCKS5 proxy address on the host device.
    pub proxy_addr: SocketAddr,
    /// MTU for the TUN interface.
    #[serde(default = "default_mtu")]
    pub mtu: u16,
    /// DNS resolver for the interceptor.
    #[serde(default = "default_dns")]
    pub dns_server: IpAddr,
    /// Enable UDP ASSOCIATE (true by default; falls back to udpgw if server rejects).
    #[serde(default = "default_true")]
    pub udp_associate_enabled: bool,
}

impl Default for ClientConfig {
    fn default() -> Self {
        Self {
            proxy_addr: SocketAddr::new(IpAddr::V4(Ipv4Addr::new(192, 168, 43, 1)), 1080),
            mtu: DEFAULT_MTU,
            dns_server: IpAddr::V4(DEFAULT_DNS),
            udp_associate_enabled: true,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ServerConfig {
    /// SOCKS5 bind address. Default: 0.0.0.0:1080
    #[serde(default = "default_socks_bind")]
    pub socks_bind: SocketAddr,
    /// HTTP CONNECT bind address. Default: 0.0.0.0:8080
    #[serde(default = "default_http_bind")]
    pub http_bind: SocketAddr,
    /// Maximum concurrent connections.
    #[serde(default = "default_max_connections")]
    pub max_connections: usize,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            socks_bind: "0.0.0.0:1080".parse().unwrap(),
            http_bind: "0.0.0.0:8080".parse().unwrap(),
            max_connections: 512,
        }
    }
}

fn default_mtu() -> u16 { DEFAULT_MTU }
fn default_dns() -> IpAddr { IpAddr::V4(DEFAULT_DNS) }
fn default_true() -> bool { true }
fn default_socks_bind() -> SocketAddr { "0.0.0.0:1080".parse().unwrap() }
fn default_http_bind() -> SocketAddr { "0.0.0.0:8080".parse().unwrap() }
fn default_max_connections() -> usize { 512 }
