/// MCP-compatible local diagnostic endpoint.
/// Listens on 127.0.0.1:7423 when diagnostic mode is active.
/// Routes: /state, /events, /metrics, /dns/check
use std::net::SocketAddr;
use tokio::net::TcpListener;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

pub const MCP_PORT: u16 = 7423;
pub const MCP_BIND: &str = "127.0.0.1";

pub struct McpEndpoint {
    token: String,
}

impl McpEndpoint {
    pub fn new() -> Self {
        // Ephemeral session token — regenerated each time.
        let token: String = (0..32)
            .map(|_| format!("{:02x}", rand_byte()))
            .collect();
        Self { token }
    }

    pub fn token(&self) -> &str {
        &self.token
    }

    /// Spawn the MCP endpoint — call from within a tokio runtime.
    pub async fn serve(self, mut shutdown: tokio::sync::watch::Receiver<bool>) {
        let addr: SocketAddr = format!("{MCP_BIND}:{MCP_PORT}").parse().unwrap();
        let listener = match TcpListener::bind(addr).await {
            Ok(l) => l,
            Err(e) => {
                tracing::warn!(kind = "mcp.bind.failed", error = %e);
                return;
            }
        };

        tracing::info!(kind = "mcp.started", port = MCP_PORT);

        loop {
            tokio::select! {
                Ok((mut stream, peer)) = listener.accept() => {
                    // Reject non-loopback connections
                    if !peer.ip().is_loopback() {
                        let _ = stream.shutdown().await;
                        continue;
                    }
                    let token = self.token.clone();
                    tokio::spawn(async move {
                        let _ = handle_mcp_request(&mut stream, &token).await;
                    });
                }
                _ = shutdown.changed() => {
                    if *shutdown.borrow() { break; }
                }
            }
        }
    }
}

impl Default for McpEndpoint {
    fn default() -> Self {
        Self::new()
    }
}

async fn handle_mcp_request(
    stream: &mut tokio::net::TcpStream,
    token: &str,
) -> std::io::Result<()> {
    let mut buf = [0u8; 4096];
    let n = stream.read(&mut buf).await?;
    let req = std::str::from_utf8(&buf[..n]).unwrap_or("");

    if !req.contains(&format!("Authorization: Bearer {token}")) {
        let resp = b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n";
        stream.write_all(resp).await?;
        return Ok(());
    }

    let path = extract_path(req);
    let body = match path {
        "/state"      => get_state_json(),
        "/metrics"    => get_metrics_json(),
        "/dns/check"  => get_dns_check_json(),
        _             => r#"{"error":"not found"}"#.to_owned(),
    };

    let status = if path == "/state" || path == "/metrics" || path == "/dns/check" {
        "200 OK"
    } else {
        "404 Not Found"
    };

    let resp = format!(
        "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    );
    stream.write_all(resp.as_bytes()).await
}

fn extract_path(req: &str) -> &str {
    req.lines()
        .next()
        .and_then(|l| l.split_whitespace().nth(1))
        .unwrap_or("/")
}

fn get_state_json() -> String {
    // Returns snapshot from global engine state — real impl integrates with Session
    serde_json::json!({
        "schema": "gs.event.v1",
        "state": "Idle",
        "note": "engine not started"
    }).to_string()
}

fn get_metrics_json() -> String {
    serde_json::json!({
        "bytes_in": 0,
        "bytes_out": 0,
        "active_tcp_sessions": 0,
        "active_udp_assocs": 0,
    }).to_string()
}

fn get_dns_check_json() -> String {
    serde_json::json!({
        "status": "not_running",
    }).to_string()
}

fn rand_byte() -> u8 {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    use std::time::SystemTime;
    let mut h = DefaultHasher::new();
    SystemTime::now().hash(&mut h);
    h.finish() as u8
}
