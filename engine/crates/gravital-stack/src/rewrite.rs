/// IPv4 / TCP packet rewriting for transparent proxying.
///
/// The stack uses a virtual-port NAT: destination port in inbound packets is
/// replaced by the allocated virtual port so smoltcp can demux connections.
/// On outbound packets the source IP + port are replaced with the real remote
/// address so the application receives packets from the expected peer.
///
/// Both operations require recomputing the IPv4 header checksum and the TCP
/// pseudo-header checksum (RFC 793 §3.1, RFC 1071).
use std::net::Ipv4Addr;

// ── Checksum ──────────────────────────────────────────────────────────────────

/// RFC 1071 one's-complement checksum over `data`.
pub(crate) fn checksum(data: &[u8]) -> u16 {
    let mut sum = 0u32;
    let mut chunks = data.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    if let [tail] = chunks.remainder() {
        sum += (*tail as u32) << 8;
    }
    // Fold 32-bit sum to 16 bits.
    while sum >> 16 != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    !(sum as u16)
}

// ── IPv4 header checksum ──────────────────────────────────────────────────────

/// Recompute and store the IPv4 header checksum in `pkt` (must be ≥ 20 bytes).
pub(crate) fn fix_ipv4_checksum(pkt: &mut [u8]) {
    debug_assert!(pkt.len() >= 20, "IPv4 packet shorter than 20 bytes");
    pkt[10] = 0;
    pkt[11] = 0;
    let ihl = (pkt[0] & 0x0F) as usize * 4;
    let csum = checksum(&pkt[..ihl]);
    pkt[10..12].copy_from_slice(&csum.to_be_bytes());
}

// ── TCP checksum ──────────────────────────────────────────────────────────────

/// Recompute and store the TCP checksum.
///
/// `pkt` is the full IPv4 datagram (header + TCP segment).
pub(crate) fn fix_tcp_checksum(pkt: &mut [u8]) {
    let ihl = (pkt[0] & 0x0F) as usize * 4;
    let tcp_len = pkt.len() - ihl;

    // Build pseudo-header: src_ip(4) + dst_ip(4) + zero(1) + proto(1) + tcp_len(2)
    let mut pseudo = [0u8; 12];
    pseudo[0..4].copy_from_slice(&pkt[12..16]); // src IP
    pseudo[4..8].copy_from_slice(&pkt[16..20]); // dst IP
    pseudo[8] = 0;
    pseudo[9] = 6; // TCP
    pseudo[10..12].copy_from_slice(&(tcp_len as u16).to_be_bytes());

    // Zero out the existing TCP checksum (bytes 16-17 of the TCP header).
    pkt[ihl + 16] = 0;
    pkt[ihl + 17] = 0;

    // Checksum = pseudo-header + TCP segment.
    let mut sum = 0u32;
    for chunk in pseudo.chunks_exact(2) {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    let tcp_seg = &pkt[ihl..];
    let mut chunks = tcp_seg.chunks_exact(2);
    for chunk in &mut chunks {
        sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32;
    }
    if let [tail] = chunks.remainder() {
        sum += (*tail as u32) << 8;
    }
    while sum >> 16 != 0 {
        sum = (sum & 0xFFFF) + (sum >> 16);
    }
    let csum = !(sum as u16);
    pkt[ihl + 16..ihl + 18].copy_from_slice(&csum.to_be_bytes());
}

// ── Inbound rewrite: TUN → smoltcp ───────────────────────────────────────────

/// Rewrite an IPv4/TCP packet arriving from the TUN device before feeding it
/// to smoltcp.  The destination port is replaced by `vport`.
///
/// Returns `false` if the packet is too short or is not IPv4/TCP.
pub(crate) fn rewrite_inbound(pkt: &mut Vec<u8>, vport: u16) -> bool {
    if pkt.len() < 20 {
        return false;
    }
    // IPv4 only for now (version nibble = 4).
    if (pkt[0] >> 4) != 4 {
        return false;
    }
    let proto = pkt[9];
    if proto != 6 {
        // not TCP
        return false;
    }
    let ihl = (pkt[0] & 0x0F) as usize * 4;
    if pkt.len() < ihl + 20 {
        return false;
    }

    // Replace dst port (bytes 2-3 of TCP header).
    pkt[ihl + 2..ihl + 4].copy_from_slice(&vport.to_be_bytes());

    fix_tcp_checksum(pkt);
    fix_ipv4_checksum(pkt);
    true
}

// ── Outbound rewrite: smoltcp → TUN ──────────────────────────────────────────

/// Rewrite an IPv4/TCP packet produced by smoltcp before writing it to the TUN
/// device.  The source IP is replaced by `real_dst_ip` and source port by
/// `real_dst_port`, restoring the illusion that the app is talking to the real
/// remote server.
///
/// Returns `false` if the packet is malformed or not IPv4/TCP.
pub(crate) fn rewrite_outbound(
    pkt: &mut Vec<u8>,
    real_dst_ip: Ipv4Addr,
    real_dst_port: u16,
) -> bool {
    if pkt.len() < 20 {
        return false;
    }
    if (pkt[0] >> 4) != 4 {
        return false;
    }
    if pkt[9] != 6 {
        return false;
    }
    let ihl = (pkt[0] & 0x0F) as usize * 4;
    if pkt.len() < ihl + 20 {
        return false;
    }

    // Replace src IP (bytes 12-15 of IPv4 header).
    pkt[12..16].copy_from_slice(&real_dst_ip.octets());

    // Replace src port (bytes 0-1 of TCP header).
    pkt[ihl..ihl + 2].copy_from_slice(&real_dst_port.to_be_bytes());

    fix_tcp_checksum(pkt);
    fix_ipv4_checksum(pkt);
    true
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/// Extract (src_addr, dst_addr) from an IPv4/TCP packet.
/// Returns `None` if the packet is too short or not IPv4/TCP.
pub(crate) fn extract_tcp_addrs(pkt: &[u8]) -> Option<(std::net::SocketAddr, std::net::SocketAddr)> {
    use std::net::{SocketAddr, SocketAddrV4};
    if pkt.len() < 20 {
        return None;
    }
    if (pkt[0] >> 4) != 4 || pkt[9] != 6 {
        return None;
    }
    let ihl = (pkt[0] & 0x0F) as usize * 4;
    if pkt.len() < ihl + 20 {
        return None;
    }
    let src_ip = Ipv4Addr::new(pkt[12], pkt[13], pkt[14], pkt[15]);
    let dst_ip = Ipv4Addr::new(pkt[16], pkt[17], pkt[18], pkt[19]);
    let src_port = u16::from_be_bytes([pkt[ihl], pkt[ihl + 1]]);
    let dst_port = u16::from_be_bytes([pkt[ihl + 2], pkt[ihl + 3]]);
    Some((
        SocketAddr::V4(SocketAddrV4::new(src_ip, src_port)),
        SocketAddr::V4(SocketAddrV4::new(dst_ip, dst_port)),
    ))
}

/// Return `true` if the TCP SYN flag is set (and ACK is clear — new connection).
pub(crate) fn is_tcp_syn(pkt: &[u8]) -> bool {
    let ihl = (pkt[0] & 0x0F) as usize * 4;
    if pkt.len() < ihl + 14 {
        return false;
    }
    let flags = pkt[ihl + 13];
    // SYN=0x02, ACK=0x10 — accept SYN-only
    (flags & 0x12) == 0x02
}

// ── Tests ─────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    /// Minimal valid IPv4/TCP SYN packet:
    /// src=10.42.0.2:54321, dst=8.8.8.8:443
    fn syn_packet() -> Vec<u8> {
        let mut pkt = vec![0u8; 40];
        // IPv4 header (20 bytes)
        pkt[0] = 0x45; // version=4, IHL=5
        pkt[1] = 0x00;
        pkt[2..4].copy_from_slice(&(40u16).to_be_bytes()); // total length
        pkt[8] = 64; // TTL
        pkt[9] = 6;  // TCP
        pkt[12..16].copy_from_slice(&[10, 42, 0, 2]);    // src IP
        pkt[16..20].copy_from_slice(&[8, 8, 8, 8]);      // dst IP
        // TCP header (20 bytes at offset 20)
        pkt[20..22].copy_from_slice(&54321u16.to_be_bytes()); // src port
        pkt[22..24].copy_from_slice(&443u16.to_be_bytes());   // dst port
        pkt[32] = 0x50; // data offset = 5 (20 bytes)
        pkt[33] = 0x02; // SYN flag
        // Compute checksums so the packet is valid.
        fix_tcp_checksum(&mut pkt);
        fix_ipv4_checksum(&mut pkt);
        pkt
    }

    #[test]
    fn checksum_known_vector() {
        // RFC 1071 example: the one's complement sum of 0x0001…0xF203 = 0x220A.
        // The COMPLEMENT (what the checksum field stores) = 0xDDF5.
        let data: &[u8] = &[0x00, 0x01, 0xF2, 0x03, 0xF4, 0xF5, 0xF6, 0xF7];
        let csum = checksum(data);
        // Verify that adding it back to the data yields all-ones (0xFFFF).
        let total = data
            .chunks_exact(2)
            .map(|c| u16::from_be_bytes([c[0], c[1]]) as u32)
            .fold(csum as u32, |a, b| a + b);
        let folded = {
            let mut s = total;
            while s >> 16 != 0 { s = (s & 0xFFFF) + (s >> 16); }
            s as u16
        };
        assert_eq!(folded, 0xFFFF);
    }

    #[test]
    fn extract_addrs_from_syn() {
        let pkt = syn_packet();
        let (src, dst) = extract_tcp_addrs(&pkt).unwrap();
        assert_eq!(src.port(), 54321);
        assert_eq!(dst.port(), 443);
    }

    #[test]
    fn is_syn_flag() {
        let pkt = syn_packet();
        assert!(is_tcp_syn(&pkt));
    }

    #[test]
    fn inbound_rewrite_changes_dst_port() {
        let mut pkt = syn_packet();
        assert!(rewrite_inbound(&mut pkt, 12345));
        let (_, dst) = extract_tcp_addrs(&pkt).unwrap();
        assert_eq!(dst.port(), 12345);
    }

    #[test]
    fn outbound_rewrite_changes_src() {
        let mut pkt = syn_packet();
        // Simulate smoltcp sending a SYN-ACK from 10.0.2.2:10000 to 10.42.0.2:54321.
        pkt[12..16].copy_from_slice(&[10, 0, 2, 2]);
        pkt[20..22].copy_from_slice(&10_000u16.to_be_bytes());
        pkt[33] = 0x12; // SYN+ACK
        fix_tcp_checksum(&mut pkt);
        fix_ipv4_checksum(&mut pkt);

        let real_ip = Ipv4Addr::new(8, 8, 8, 8);
        assert!(rewrite_outbound(&mut pkt, real_ip, 443));

        let (src, _) = extract_tcp_addrs(&pkt).unwrap();
        assert_eq!(src.ip(), std::net::IpAddr::V4(real_ip));
        assert_eq!(src.port(), 443);
    }
}
