use std::net::SocketAddr;
use tokio::net::TcpStream;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tracing::{debug, warn};
use crate::error::SocksError;
use crate::proto::{self, AddrSpec, METHOD_NO_AUTH};

#[derive(Debug, Clone)]
pub struct ClientConfig {
    pub proxy_addr: SocketAddr,
    /// Max reconnect backoff (ms)
    pub max_backoff_ms: u64,
}

pub struct SocksClient {
    config: ClientConfig,
}

impl SocksClient {
    pub fn new(config: ClientConfig) -> Self {
        Self { config }
    }

    /// Open a SOCKS5 CONNECT tunnel to `target` via the configured proxy.
    /// Returns the underlying TcpStream positioned at the start of the data phase.
    pub async fn connect(&self, target: AddrSpec, port: u16) -> Result<TcpStream, SocksError> {
        let mut sock = TcpStream::connect(self.config.proxy_addr)
            .await
            .map_err(SocksError::Io)?;

        // Fase 1 — method negotiation (offer NO AUTH only)
        sock.write_all(&[0x05, 0x01, METHOD_NO_AUTH])
            .await
            .map_err(SocksError::Io)?;

        let mut resp = [0u8; 2];
        sock.read_exact(&mut resp).await.map_err(|e| {
            if e.kind() == std::io::ErrorKind::UnexpectedEof {
                SocksError::PrematureEof
            } else {
                SocksError::Io(e)
            }
        })?;

        if resp[0] != 0x05 {
            return Err(SocksError::InvalidVersion(resp[0]));
        }
        if resp[1] != METHOD_NO_AUTH {
            return Err(SocksError::AuthRejected);
        }

        // Fase 2 — CONNECT request
        let mut req = vec![0x05, 0x01, 0x00];
        proto::encode_address(&target, port, &mut req);
        sock.write_all(&req).await.map_err(SocksError::Io)?;

        // Read reply header
        let mut hdr = [0u8; 4];
        sock.read_exact(&mut hdr).await.map_err(|e| {
            if e.kind() == std::io::ErrorKind::UnexpectedEof {
                SocksError::PrematureEof
            } else {
                SocksError::Io(e)
            }
        })?;

        if hdr[1] != 0x00 {
            return Err(SocksError::ServerReply(hdr[1]));
        }

        proto::skip_bound_address(&mut sock, hdr[3]).await?;

        debug!(kind = "socks.client.connected", target_port = port);
        Ok(sock)
    }

    /// Exponential backoff delay for reconnection attempts.
    pub fn backoff_delay(attempt: u32) -> std::time::Duration {
        let ms = 250u64.saturating_mul(2u64.saturating_pow(attempt.saturating_sub(1)));
        std::time::Duration::from_millis(ms.min(30_000))
    }

    /// Connect with automatic retry using exponential backoff.
    pub async fn connect_with_retry(
        &self,
        target: AddrSpec,
        port: u16,
        max_attempts: u32,
    ) -> Result<TcpStream, SocksError> {
        for attempt in 1..=max_attempts {
            match self.connect(target.clone(), port).await {
                Ok(stream) => return Ok(stream),
                Err(e) => {
                    if attempt == max_attempts {
                        return Err(e);
                    }
                    let delay = Self::backoff_delay(attempt);
                    warn!(
                        kind = "socks.client.retry",
                        attempt = attempt,
                        delay_ms = delay.as_millis(),
                        error = %e
                    );
                    tokio::time::sleep(delay).await;
                }
            }
        }
        unreachable!()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn backoff_sequence() {
        // 250ms → 500ms → 1s → 2s → 4s → ... → 30s
        assert_eq!(SocksClient::backoff_delay(1).as_millis(), 250);
        assert_eq!(SocksClient::backoff_delay(2).as_millis(), 500);
        assert_eq!(SocksClient::backoff_delay(3).as_millis(), 1000);
        assert_eq!(SocksClient::backoff_delay(10).as_millis(), 30_000); // capped
    }
}
