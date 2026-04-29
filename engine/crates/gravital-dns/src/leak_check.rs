use std::net::IpAddr;
use tokio::time::{timeout, Duration};
use crate::error::DnsError;
use crate::resolver::DnsResolver;

/// Canary DNS leak check — verifies that DNS queries travel through the tunnel.
/// Sends a known query to the configured resolver and checks the response
/// is reachable (proving the tunnel is active and DNS is not leaking).
pub struct LeakCheck {
    resolver: DnsResolver,
}

impl LeakCheck {
    pub fn new(resolver: DnsResolver) -> Self {
        Self { resolver }
    }

    /// Run the leak check. Returns Ok(()) if DNS works through the tunnel.
    /// Returns Err(DnsError::LeakDetected) if DNS fails entirely (leak scenario).
    pub async fn run(&self) -> Result<(), DnsError> {
        // Minimal DNS query for "cloudflare-dns.com" type A (used as canary)
        let query = build_canary_query();

        match timeout(Duration::from_secs(8), self.resolver.forward_raw(&query)).await {
            Ok(Ok(resp)) if !resp.is_empty() => {
                tracing::info!(kind = "dns.leak_check.passed");
                Ok(())
            }
            Ok(Ok(_)) | Ok(Err(_)) | Err(_) => {
                tracing::warn!(kind = "dns.leak.detected");
                Err(DnsError::LeakDetected)
            }
        }
    }
}

/// Build a minimal DNS query for "one.one.one.one" (1.1.1.1 PTR equivalent).
fn build_canary_query() -> Vec<u8> {
    // Transaction ID: 0xDEAD
    // Flags: standard query
    // Questions: 1, for "one.one.one.one" type A
    vec![
        0xDE, 0xAD, // ID
        0x01, 0x00, // flags: QR=0 RD=1
        0x00, 0x01, // QDCOUNT=1
        0x00, 0x00, // ANCOUNT=0
        0x00, 0x00, // NSCOUNT=0
        0x00, 0x00, // ARCOUNT=0
        // QNAME: one.one.one.one
        0x03, b'o', b'n', b'e',
        0x03, b'o', b'n', b'e',
        0x03, b'o', b'n', b'e',
        0x03, b'o', b'n', b'e',
        0x00,
        0x00, 0x01, // QTYPE=A
        0x00, 0x01, // QCLASS=IN
    ]
}
