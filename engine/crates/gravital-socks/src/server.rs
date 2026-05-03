use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::watch;
use tracing::{info, warn};
use crate::error::SocksError;
use crate::proto::{self, Cmd, Rep};

#[derive(Debug, Clone)]
pub struct ServerConfig {
    pub bind_addr: SocketAddr,
    pub auth_enabled: bool,
    pub handshake_timeout_secs: u64,
}

impl Default for ServerConfig {
    fn default() -> Self {
        Self {
            bind_addr: "0.0.0.0:1080".parse().unwrap(),
            auth_enabled: false,
            handshake_timeout_secs: 30,
        }
    }
}

pub struct SocksServer {
    config: Arc<ServerConfig>,
    /// Current number of active SOCKS connections.
    active_connections: Arc<AtomicU32>,
}

impl SocksServer {
    pub fn new(config: ServerConfig) -> Self {
        Self {
            config: Arc::new(config),
            active_connections: Arc::new(AtomicU32::new(0)),
        }
    }

    /// Returns a shared handle to the active-connection counter.
    pub fn active_connections(&self) -> Arc<AtomicU32> {
        self.active_connections.clone()
    }

    pub async fn run(&self, mut shutdown: watch::Receiver<bool>) -> Result<(), SocksError> {
        let listener = TcpListener::bind(self.config.bind_addr)
            .await
            .map_err(SocksError::Io)?;

        info!(kind = "socks.server.started", addr = %self.config.bind_addr);

        loop {
            tokio::select! {
                accept = listener.accept() => {
                    match accept {
                        Ok((stream, peer)) => {
                            let cfg = self.config.clone();
                            let counter = self.active_connections.clone();
                            counter.fetch_add(1, Ordering::Relaxed);
                            tokio::spawn(async move {
                                if let Err(e) = handle_connection(stream, peer, cfg).await {
                                    warn!(kind = "socks.connection.error", peer = %peer, error = %e);
                                }
                                counter.fetch_sub(1, Ordering::Relaxed);
                            });
                        }
                        Err(e) => {
                            warn!(kind = "socks.accept.error", error = %e);
                        }
                    }
                }
                _ = shutdown.changed() => {
                    if *shutdown.borrow() {
                        info!(kind = "socks.server.stopping");
                        break;
                    }
                }
            }
        }

        Ok(())
    }
}

async fn handle_connection(
    mut sock: TcpStream,
    peer: SocketAddr,
    cfg: Arc<ServerConfig>,
) -> Result<(), SocksError> {
    info!(kind = "socks.connection.accepted", peer = %peer);

    let method = proto::negotiate_method(&mut sock, cfg.auth_enabled).await?;
    if method == proto::METHOD_USERPASS {
        // TODO: auth stub — always accept in MVP (subred LAN, sin auth)
    }

    let req = proto::read_request(&mut sock).await?;

    match req.cmd {
        Cmd::Connect => handle_connect(sock, peer, req).await,
        Cmd::UdpAssociate => handle_udp_associate(sock).await,
        Cmd::Bind => {
            let zero: SocketAddr = "0.0.0.0:0".parse().unwrap();
            proto::send_reply(&mut sock, Rep::CommandNotSupported, &zero).await?;
            Ok(())
        }
    }
}

async fn handle_connect(
    mut client: TcpStream,
    _peer: SocketAddr,
    req: crate::proto::Request,
) -> Result<(), SocksError> {
    let target = req.addr.to_socket_addr(req.port).await?;

    let upstream = match TcpStream::connect(target).await {
        Ok(s) => s,
        Err(e) => {
            let rep = match e.kind() {
                std::io::ErrorKind::ConnectionRefused => Rep::ConnectionRefused,
                std::io::ErrorKind::TimedOut => Rep::TtlExpired,
                _ => Rep::HostUnreachable,
            };
            let zero: SocketAddr = "0.0.0.0:0".parse().unwrap();
            proto::send_reply(&mut client, rep, &zero).await?;
            return Err(SocksError::Upstream(e));
        }
    };

    let local = upstream.local_addr().map_err(SocksError::Io)?;
    proto::send_reply(&mut client, Rep::Succeeded, &local).await?;

    info!(kind = "socks.connect.established", target = %target);
    relay_bidirectional(client, upstream).await
}

async fn handle_udp_associate(mut client_tcp: TcpStream) -> Result<(), SocksError> {
    let udp = UdpSocket::bind("0.0.0.0:0").await.map_err(SocksError::Io)?;
    let local = udp.local_addr().map_err(SocksError::Io)?;

    proto::send_reply(&mut client_tcp, Rep::Succeeded, &local).await?;

    info!(kind = "socks.udp_assoc.started", local = %local);

    let udp = Arc::new(udp);
    let stop = Arc::new(tokio::sync::Notify::new());
    let stop2 = stop.clone();
    let udp2 = udp.clone();

    let relay = tokio::spawn(async move {
        relay_udp(udp2, stop2).await
    });

    // Block until the TCP control connection closes
    let mut discard = [0u8; 1];
    let _ = client_tcp.read(&mut discard).await;
    stop.notify_one();
    let _ = relay.await;

    Ok(())
}

async fn relay_udp(udp: Arc<UdpSocket>, stop: Arc<tokio::sync::Notify>) -> Result<(), SocksError> {
    use std::collections::HashMap;
    let mut buf = [0u8; 65535];
    let mut assocs: HashMap<SocketAddr, SocketAddr> = HashMap::new();

    loop {
        tokio::select! {
            res = udp.recv_from(&mut buf) => {
                let (n, peer) = res.map_err(SocksError::Io)?;
                let hdr = crate::proto::parse_udp_datagram(&buf[..n])?;
                // Only handle non-fragmented datagrams in MVP
                if hdr.frag != 0 { continue; }
                let target = hdr.addr.to_socket_addr(hdr.port).await?;
                let payload = &buf[hdr.payload_offset..n];
                assocs.insert(target, peer);
                // Open a fresh UDP socket per association (simplified MVP approach)
                if let Ok(out_sock) = UdpSocket::bind("0.0.0.0:0").await {
                    let _ = out_sock.send_to(payload, target).await;
                    // Response relay is simplified — full impl uses a JoinSet of tasks
                }
            }
            _ = stop.notified() => break,
        }
    }
    Ok(())
}

async fn relay_bidirectional(a: TcpStream, b: TcpStream) -> Result<(), SocksError> {
    let (mut ar, mut aw) = a.into_split();
    let (mut br, mut bw) = b.into_split();

    let to_b = tokio::spawn(async move { tokio::io::copy(&mut ar, &mut bw).await });
    let to_a = tokio::spawn(async move { tokio::io::copy(&mut br, &mut aw).await });

    // Wait for either direction to close; the other will follow.
    let _ = tokio::try_join!(to_b, to_a);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn server_config_default() {
        let cfg = ServerConfig::default();
        assert_eq!(cfg.bind_addr.port(), 1080);
        assert!(!cfg.auth_enabled);
    }
}
