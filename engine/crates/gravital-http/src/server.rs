use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::watch;
use tracing::{info, warn};
use httparse::{Request, EMPTY_HEADER, Status};
use crate::error::HttpProxyError;

#[derive(Debug, Clone)]
pub struct HttpProxyConfig {
    pub bind_addr: SocketAddr,
    pub auth_enabled: bool,
}

impl Default for HttpProxyConfig {
    fn default() -> Self {
        Self {
            bind_addr: "0.0.0.0:8080".parse().unwrap(),
            auth_enabled: false,
        }
    }
}

pub struct HttpProxyServer {
    config: Arc<HttpProxyConfig>,
}

impl HttpProxyServer {
    pub fn new(config: HttpProxyConfig) -> Self {
        Self { config: Arc::new(config) }
    }

    pub async fn run(&self, mut shutdown: watch::Receiver<bool>) -> Result<(), HttpProxyError> {
        let listener = TcpListener::bind(self.config.bind_addr)
            .await
            .map_err(HttpProxyError::Io)?;

        info!(kind = "http.proxy.started", addr = %self.config.bind_addr);

        loop {
            tokio::select! {
                accept = listener.accept() => {
                    match accept {
                        Ok((stream, peer)) => {
                            let cfg = self.config.clone();
                            tokio::spawn(async move {
                                if let Err(e) = handle_http_proxy(stream, peer, cfg).await {
                                    warn!(kind = "http.connection.error", peer = %peer, error = %e);
                                }
                            });
                        }
                        Err(e) => warn!(kind = "http.accept.error", error = %e),
                    }
                }
                _ = shutdown.changed() => {
                    if *shutdown.borrow() {
                        info!(kind = "http.proxy.stopping");
                        break;
                    }
                }
            }
        }

        Ok(())
    }
}

async fn handle_http_proxy(
    mut sock: TcpStream,
    peer: SocketAddr,
    cfg: Arc<HttpProxyConfig>,
) -> Result<(), HttpProxyError> {
    const MAX_HEADER_SIZE: usize = 8192;
    let mut buf = [0u8; MAX_HEADER_SIZE];
    let mut filled = 0;

    // Read until we find \r\n\r\n
    let req_end = loop {
        let n = sock.read(&mut buf[filled..]).await.map_err(HttpProxyError::Io)?;
        if n == 0 {
            return Err(HttpProxyError::PrematureEof);
        }
        filled += n;

        let mut headers = [EMPTY_HEADER; 32];
        let mut req = Request::new(&mut headers);
        match req.parse(&buf[..filled]).map_err(|e| HttpProxyError::Parse(e.to_string()))? {
            Status::Complete(len) => break len,
            Status::Partial => {
                if filled >= buf.len() {
                    return Err(HttpProxyError::HeadersTooLarge);
                }
            }
        }
    };

    let mut headers = [EMPTY_HEADER; 32];
    let mut req = Request::new(&mut headers);
    req.parse(&buf[..filled]).map_err(|e| HttpProxyError::Parse(e.to_string()))?;

    let method = req.method.unwrap_or("");
    if method != "CONNECT" {
        send_error(&mut sock, 405, "Method Not Allowed").await?;
        return Err(HttpProxyError::UnsupportedMethod(method.to_owned()));
    }

    let target_str = req.path.ok_or(HttpProxyError::MissingTarget)?;
    let (host, port) = parse_host_port(target_str)?;

    info!(kind = "http.connect.request", peer = %peer, host = %host, port = port);

    let mut upstream = match TcpStream::connect((host.as_str(), port)).await {
        Ok(s) => s,
        Err(_) => {
            send_error(&mut sock, 502, "Bad Gateway").await?;
            return Err(HttpProxyError::UpstreamFailed);
        }
    };

    sock.write_all(b"HTTP/1.1 200 Connection established\r\n\r\n")
        .await
        .map_err(HttpProxyError::Io)?;

    // Forward any bytes that arrived after the CONNECT headers.
    if filled > req_end {
        upstream.write_all(&buf[req_end..filled]).await.map_err(HttpProxyError::Io)?;
    }

    relay_bidirectional(sock, upstream).await
}

fn parse_host_port(target: &str) -> Result<(String, u16), HttpProxyError> {
    let err = || HttpProxyError::InvalidTarget(target.to_owned());
    if let Some(pos) = target.rfind(':') {
        let host = target[..pos].to_owned();
        let port: u16 = target[pos+1..].parse().map_err(|_| err())?;
        Ok((host, port))
    } else {
        Err(err())
    }
}

async fn send_error(sock: &mut TcpStream, code: u16, msg: &str) -> Result<(), HttpProxyError> {
    let resp = format!("HTTP/1.1 {code} {msg}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    sock.write_all(resp.as_bytes()).await.map_err(HttpProxyError::Io)
}

// upstream_write removed — relay_bidirectional handles the data path.

async fn relay_bidirectional(a: TcpStream, b: TcpStream) -> Result<(), HttpProxyError> {
    let (mut ar, mut aw) = a.into_split();
    let (mut br, mut bw) = b.into_split();

    let to_b = tokio::spawn(async move { tokio::io::copy(&mut ar, &mut bw).await });
    let to_a = tokio::spawn(async move { tokio::io::copy(&mut br, &mut aw).await });
    let _ = tokio::try_join!(to_b, to_a);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_host_port_valid() {
        let (host, port) = parse_host_port("www.example.com:443").unwrap();
        assert_eq!(host, "www.example.com");
        assert_eq!(port, 443);
    }

    #[test]
    fn parse_host_port_ipv4() {
        let (host, port) = parse_host_port("1.2.3.4:80").unwrap();
        assert_eq!(host, "1.2.3.4");
        assert_eq!(port, 80);
    }

    #[test]
    fn parse_host_port_invalid() {
        assert!(parse_host_port("no-port").is_err());
        assert!(parse_host_port("host:notaport").is_err());
    }
}
