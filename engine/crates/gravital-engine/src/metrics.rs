use std::sync::atomic::{AtomicU32, AtomicU64, Ordering};
use std::sync::Arc;
use serde::Serialize;

/// Engine-wide metrics updated atomically — safe across tasks.
#[derive(Default)]
pub struct EngineMetrics {
    pub bytes_in: AtomicU64,
    pub bytes_out: AtomicU64,
    pub packets_in: AtomicU64,
    pub packets_out: AtomicU64,
    pub active_tcp_sessions: AtomicU32,
    pub active_udp_assocs: AtomicU32,
    pub dns_queries: AtomicU64,
    pub dns_leaks_detected: AtomicU32,
    pub reconnects: AtomicU32,
    pub last_rtt_ms: AtomicU32,
    pub drops_buffer_full: AtomicU64,
    pub drops_parse_error: AtomicU64,
    pub socks_handshake_failures: AtomicU64,
}

impl EngineMetrics {
    pub fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    pub fn snapshot(&self) -> MetricsSnapshot {
        MetricsSnapshot {
            bytes_in: self.bytes_in.load(Ordering::Relaxed),
            bytes_out: self.bytes_out.load(Ordering::Relaxed),
            packets_in: self.packets_in.load(Ordering::Relaxed),
            packets_out: self.packets_out.load(Ordering::Relaxed),
            active_tcp_sessions: self.active_tcp_sessions.load(Ordering::Relaxed),
            active_udp_assocs: self.active_udp_assocs.load(Ordering::Relaxed),
            dns_queries: self.dns_queries.load(Ordering::Relaxed),
            dns_leaks_detected: self.dns_leaks_detected.load(Ordering::Relaxed),
            reconnects: self.reconnects.load(Ordering::Relaxed),
            last_rtt_ms: self.last_rtt_ms.load(Ordering::Relaxed),
            drops_buffer_full: self.drops_buffer_full.load(Ordering::Relaxed),
            drops_parse_error: self.drops_parse_error.load(Ordering::Relaxed),
            socks_handshake_failures: self.socks_handshake_failures.load(Ordering::Relaxed),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct MetricsSnapshot {
    pub bytes_in: u64,
    pub bytes_out: u64,
    pub packets_in: u64,
    pub packets_out: u64,
    pub active_tcp_sessions: u32,
    pub active_udp_assocs: u32,
    pub dns_queries: u64,
    pub dns_leaks_detected: u32,
    pub reconnects: u32,
    pub last_rtt_ms: u32,
    pub drops_buffer_full: u64,
    pub drops_parse_error: u64,
    pub socks_handshake_failures: u64,
}
