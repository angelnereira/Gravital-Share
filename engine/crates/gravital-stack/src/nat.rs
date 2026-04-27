use std::collections::HashMap;
use std::net::SocketAddr;

/// Maps real 5-tuple connections ↔ virtual (smoltcp-facing) listen ports.
///
/// Strategy: each intercepted TCP connection gets a unique ephemeral port in
/// [BASE_PORT, BASE_PORT + CAPACITY). We rewrite the destination port in the
/// inbound packet to this virtual port so smoltcp can demux connections that
/// share the same real destination port (e.g., two HTTPS connections both
/// going to :443 on different servers).
pub(crate) struct NatTable {
    /// (src, real_dst) → virtual_port
    forward: HashMap<(SocketAddr, SocketAddr), u16>,
    /// virtual_port → (src, real_dst)
    reverse: HashMap<u16, (SocketAddr, SocketAddr)>,
    next: u16,
}

const BASE_PORT: u16 = 10_000;
const PORT_RANGE: u16 = 50_000; // 10_000 – 59_999

impl NatTable {
    pub fn new() -> Self {
        Self {
            forward: HashMap::new(),
            reverse: HashMap::new(),
            next: BASE_PORT,
        }
    }

    /// Allocate a virtual port for a new connection.
    /// Returns `None` if the table is full (50 000 concurrent connections).
    pub fn insert(&mut self, src: SocketAddr, real_dst: SocketAddr) -> Option<u16> {
        let key = (src, real_dst);
        if let Some(&vp) = self.forward.get(&key) {
            return Some(vp);
        }
        // Linear scan for a free slot — acceptable at MVP scale.
        let start = self.next;
        loop {
            let vp = self.next;
            self.next = BASE_PORT + (self.next - BASE_PORT + 1) % PORT_RANGE;
            if !self.reverse.contains_key(&vp) {
                self.forward.insert(key, vp);
                self.reverse.insert(vp, (src, real_dst));
                return Some(vp);
            }
            if self.next == start {
                return None; // full
            }
        }
    }

    /// Look up by (src, real_dst) — used on inbound packets after the first SYN.
    pub fn lookup_forward(&self, src: &SocketAddr, real_dst: &SocketAddr) -> Option<u16> {
        self.forward.get(&(*src, *real_dst)).copied()
    }

    /// Look up by virtual port — used when rewriting outbound packets from smoltcp.
    pub fn lookup_reverse(&self, vport: u16) -> Option<(SocketAddr, SocketAddr)> {
        self.reverse.get(&vport).copied()
    }

    /// Remove an entry once a connection is closed.
    pub fn remove_by_vport(&mut self, vport: u16) {
        if let Some(key) = self.reverse.remove(&vport) {
            self.forward.remove(&key);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, SocketAddrV4};

    fn sa(ip: [u8; 4], port: u16) -> SocketAddr {
        SocketAddr::V4(SocketAddrV4::new(Ipv4Addr::from(ip), port))
    }

    #[test]
    fn basic_insert_and_lookup() {
        let mut t = NatTable::new();
        let src = sa([10, 42, 0, 2], 54321);
        let dst = sa([8, 8, 8, 8], 443);

        let vp = t.insert(src, dst).unwrap();
        assert_eq!(vp, 10_000);
        assert_eq!(t.lookup_forward(&src, &dst), Some(vp));
        let (rs, rd) = t.lookup_reverse(vp).unwrap();
        assert_eq!(rs, src);
        assert_eq!(rd, dst);
    }

    #[test]
    fn idempotent_insert() {
        let mut t = NatTable::new();
        let src = sa([10, 42, 0, 2], 1);
        let dst = sa([1, 1, 1, 1], 80);
        let vp1 = t.insert(src, dst).unwrap();
        let vp2 = t.insert(src, dst).unwrap();
        assert_eq!(vp1, vp2);
    }

    #[test]
    fn different_dst_different_vport() {
        let mut t = NatTable::new();
        let src = sa([10, 42, 0, 2], 1000);
        let dst1 = sa([8, 8, 8, 8], 443);
        let dst2 = sa([1, 1, 1, 1], 443);
        let vp1 = t.insert(src, dst1).unwrap();
        let vp2 = t.insert(src, dst2).unwrap();
        assert_ne!(vp1, vp2);
    }

    #[test]
    fn remove_frees_port() {
        let mut t = NatTable::new();
        let src = sa([10, 42, 0, 2], 1);
        let dst = sa([9, 9, 9, 9], 80);
        let vp = t.insert(src, dst).unwrap();
        t.remove_by_vport(vp);
        assert!(t.lookup_forward(&src, &dst).is_none());
        assert!(t.lookup_reverse(vp).is_none());
    }
}
