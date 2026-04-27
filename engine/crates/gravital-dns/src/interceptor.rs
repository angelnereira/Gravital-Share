use bytes::BytesMut;
use tracing::{debug, warn};
use crate::error::DnsError;
use crate::resolver::DnsResolver;

/// Intercepts UDP packets destined for port 53 and resolves them through
/// the protected resolver, preventing DNS leaks.
pub struct DnsInterceptor {
    resolver: DnsResolver,
    metrics_dns_queries: std::sync::atomic::AtomicU64,
    metrics_dns_failures: std::sync::atomic::AtomicU64,
}

impl DnsInterceptor {
    pub fn new(resolver: DnsResolver) -> Self {
        Self {
            resolver,
            metrics_dns_queries: std::sync::atomic::AtomicU64::new(0),
            metrics_dns_failures: std::sync::atomic::AtomicU64::new(0),
        }
    }

    /// Handle a UDP payload known to be a DNS query.
    /// Returns the DNS response payload to inject back to the client.
    pub async fn handle_query(&self, query_payload: &[u8]) -> Result<Vec<u8>, DnsError> {
        self.metrics_dns_queries
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);

        debug!(kind = "dns.query.intercepted", len = query_payload.len());

        match self.resolver.forward_raw(query_payload).await {
            Ok(resp) => {
                debug!(kind = "dns.query.resolved", resp_len = resp.len());
                Ok(resp)
            }
            Err(e) => {
                self.metrics_dns_failures
                    .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                warn!(kind = "dns.resolve.failed", error = %e);
                // Return SERVFAIL to the client — do NOT fall back to the carrier resolver.
                Ok(build_servfail(query_payload))
            }
        }
    }

    pub fn dns_queries_total(&self) -> u64 {
        self.metrics_dns_queries.load(std::sync::atomic::Ordering::Relaxed)
    }

    pub fn dns_failures_total(&self) -> u64 {
        self.metrics_dns_failures.load(std::sync::atomic::Ordering::Relaxed)
    }
}

/// Build a minimal DNS SERVFAIL response from a query packet.
fn build_servfail(query: &[u8]) -> Vec<u8> {
    if query.len() < 2 {
        return vec![];
    }
    let mut resp = query.to_vec();
    // Set QR=1 (response), RCODE=2 (SERVFAIL)
    if resp.len() >= 4 {
        resp[2] = 0x81; // QR=1, Opcode=0, AA=0, TC=0, RD=1
        resp[3] = 0x82; // RA=1, Z=0, RCODE=2 (SERVFAIL)
    }
    resp
}
