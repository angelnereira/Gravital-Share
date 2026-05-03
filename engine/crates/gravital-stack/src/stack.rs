use bytes::BytesMut;
use parking_lot::Mutex;
use smoltcp::iface::{Config, Interface, SocketHandle, SocketSet};
use smoltcp::socket::tcp;
use smoltcp::time::Instant as SmolInstant;
use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr, IpEndpoint, Ipv4Address, Ipv4Cidr};
use std::collections::{HashMap, VecDeque};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
use std::sync::Arc;
use tracing::{debug, warn};

use crate::connection::{VirtualConnection, VirtualConnectionPeer};
use smoltcp::phy::Device as SmolDevice;
use crate::device::TunVirtualDevice;
use crate::error::StackError;
use crate::nat::NatTable;
use crate::rewrite::{extract_tcp_addrs, is_tcp_syn, rewrite_inbound, rewrite_outbound};

/// TCP socket buffer size per direction: 64 KiB.
const SOCK_BUF: usize = 65_536;

/// Max unaccepted (pending) VirtualConnections in the queue.
const PENDING_CAP: usize = 128;

/// smoltcp interface address — the virtual "gateway" the stack presents.
/// Any IP visible in the TUN device's routing table works; this is never
/// routed on a real network.
const IFACE_IP: Ipv4Addr = Ipv4Addr::new(10, 0, 2, 2);

// ── Inner state (single-lock) ─────────────────────────────────────────────────

struct Inner {
    iface: Interface,
    device: TunVirtualDevice,
    sockets: SocketSet<'static>,
    nat: NatTable,
    /// socket_handle → (real_remote_addr, channel peer held by poll loop)
    active: HashMap<SocketHandle, (SocketAddr, VirtualConnectionPeer)>,
    /// O(1) reverse lookup: socket_handle → virtual port (avoids 50k scan on close)
    handle_to_vport: HashMap<SocketHandle, u16>,
    /// Newly established connections waiting to be `accept()`ed by the engine.
    pending: VecDeque<VirtualConnection>,
    /// Outbound packets (already rewritten) ready to write to TUN.
    tun_tx: VecDeque<BytesMut>,
    /// Inbound raw packets from TUN waiting to be processed.
    tun_rx: VecDeque<BytesMut>,
}

impl Inner {
    fn new(mtu: u16) -> Self {
        let mut device = TunVirtualDevice::new(mtu as usize);
        let mut cfg = Config::new(HardwareAddress::Ip);
        cfg.random_seed = 0xDEAD_BEEF_CAFE_1984;
        let mut iface = Interface::new(cfg, &mut device, SmolInstant::ZERO);

        // Configure the virtual interface IP.
        iface.update_ip_addrs(|addrs| {
            let _ = addrs.push(IpCidr::Ipv4(Ipv4Cidr::new(
                Ipv4Address(IFACE_IP.octets()),
                32,
            )));
        });

        // Accept packets for ANY destination IP — required for transparent
        // proxying where the app's packets target arbitrary remote servers.
        iface.set_any_ip(true);

        Self {
            iface,
            device,
            sockets: SocketSet::new(vec![]),
            nat: NatTable::new(),
            active: HashMap::new(),
            handle_to_vport: HashMap::new(),
            pending: VecDeque::with_capacity(PENDING_CAP),
            tun_tx: VecDeque::with_capacity(256),
            tun_rx: VecDeque::with_capacity(256),
        }
    }

    /// Process one poll cycle.  Returns `true` if further immediate polling is
    /// needed (smoltcp made progress or there are pending sockets with data).
    fn poll(&mut self) -> bool {
        // ── 1. Classify & feed inbound packets ───────────────────────────────
        while let Some(raw) = self.tun_rx.pop_front() {
            self.process_inbound(raw);
        }

        // ── 2. Drive smoltcp ─────────────────────────────────────────────────
        let ts = smol_now();
        let progress = self.iface.poll(ts, &mut self.device, &mut self.sockets);

        // ── 3. Service active sockets ─────────────────────────────────────────
        let handles: Vec<SocketHandle> = self.active.keys().copied().collect();
        let mut need_more = progress;

        for handle in handles {
            let closed = self.service_socket(handle);
            if closed {
                let (_, peer) = self.active.remove(&handle).unwrap();
                if let Some(vp) = self.handle_to_vport.remove(&handle) {
                    self.nat.remove_by_vport(vp);
                }
                self.sockets.remove(handle);
                drop(peer);
                need_more = true;
            }
        }

        // ── 4. Drain smoltcp's transmit queue → rewrite → tun_tx ─────────────
        while let Some(pkt) = self.device.tx.pop_front() {
            let mut pkt = pkt;
            // The src port in the smoltcp packet is the virtual listen port.
            if let Some((_, real_remote)) = outbound_src_port(&pkt)
                .and_then(|vp| self.nat.lookup_reverse(vp))
            {
                if let SocketAddr::V4(r) = real_remote {
                    rewrite_outbound(&mut pkt, *r.ip(), r.port());
                }
                self.tun_tx.push_back(BytesMut::from(pkt.as_slice()));
            }
            // Drop packets we can't map (e.g., smoltcp internal traffic).
        }

        need_more
    }

    // ── Inbound processing ────────────────────────────────────────────────────

    fn process_inbound(&mut self, raw: BytesMut) {
        let pkt_slice = raw.as_ref();

        // Only handle TCP for now; other protocols were filtered upstream.
        let (src, real_dst) = match extract_tcp_addrs(pkt_slice) {
            Some(pair) => pair,
            None => return,
        };

        // Allocate or look up virtual port.
        let vport = if is_tcp_syn(pkt_slice) {
            match self.nat.insert(src, real_dst) {
                Some(vp) => {
                    // Always create a listener for each new SYN — each (src, dst)
                    // pair gets its own virtual port, so multiple simultaneous
                    // connections to the same server all work independently.
                    self.create_listener(vp, real_dst);
                    vp
                }
                None => {
                    warn!(kind = "stack.nat_full");
                    return;
                }
            }
        } else {
            match self.nat.lookup_forward(&src, &real_dst) {
                Some(vp) => vp,
                None => return, // mid-flow packet with no state; drop
            }
        };

        let mut pkt = raw.to_vec();
        if rewrite_inbound(&mut pkt, vport) {
            self.device.rx.push_back(pkt);
        }
    }

    /// Create a new smoltcp TCP socket listening on `vport`.
    fn create_listener(&mut self, vport: u16, real_remote: SocketAddr) {
        let rx_buf = tcp::SocketBuffer::new(vec![0u8; SOCK_BUF]);
        let tx_buf = tcp::SocketBuffer::new(vec![0u8; SOCK_BUF]);
        let mut socket = tcp::Socket::new(rx_buf, tx_buf);

        let endpoint = IpEndpoint::new(IpAddress::Ipv4(Ipv4Address(IFACE_IP.octets())), vport);
        if let Err(e) = socket.listen(endpoint) {
            warn!(kind = "stack.listen_failed", vport, error = ?e);
            return;
        }

        let handle = self.sockets.add(socket);

        let (conn, peer) = VirtualConnection::pair(
            SocketAddr::V4(SocketAddrV4::new(IFACE_IP, vport)),
            real_remote,
        );

        self.active.insert(handle, (real_remote, peer));
        self.handle_to_vport.insert(handle, vport);

        if self.pending.len() < PENDING_CAP {
            self.pending.push_back(conn);
        } else {
            warn!(kind = "stack.pending_overflow");
        }

        debug!(kind = "stack.listener_created", vport, remote = %real_remote);
    }

    /// Read from / write to a single active socket.
    /// Returns `true` if the socket is fully closed and should be removed.
    fn service_socket(&mut self, handle: SocketHandle) -> bool {
        let socket = self.sockets.get_mut::<tcp::Socket>(handle);

        if socket.state() == tcp::State::Closed || socket.state() == tcp::State::TimeWait {
            return true;
        }

        let (_, peer) = match self.active.get_mut(&handle) {
            Some(entry) => entry,
            None => return true,
        };

        // smoltcp → relay task: drain received bytes.
        if socket.can_recv() {
            let _ = socket.recv(|data| {
                if !data.is_empty() {
                    let chunk = BytesMut::from(&data[..]);
                    // Non-blocking; if the relay task is slow we drop and
                    // rely on TCP back-pressure from smoltcp.
                    let _ = peer.tx.try_send(chunk);
                }
                (data.len(), ())
            });
        }

        // relay task → smoltcp: write pending bytes.
        while socket.can_send() {
            match peer.rx.try_recv() {
                Ok(data) => {
                    let _ = socket.send_slice(&data);
                }
                Err(_) => break,
            }
        }

        // If the relay task closed its sender, half-close the socket.
        if socket.can_send() && peer.rx.is_closed() {
            socket.close();
        }

        false
    }

}

/// Extract source port from an IPv4/TCP packet (smoltcp outbound = virtual port).
fn outbound_src_port(pkt: &[u8]) -> Option<u16> {
    if pkt.len() < 20 || (pkt[0] >> 4) != 4 || pkt[9] != 6 {
        return None;
    }
    let ihl = (pkt[0] & 0x0F) as usize * 4;
    if pkt.len() < ihl + 4 {
        return None;
    }
    Some(u16::from_be_bytes([pkt[ihl], pkt[ihl + 1]]))
}

fn smol_now() -> SmolInstant {
    use std::time::{SystemTime, UNIX_EPOCH};
    let micros = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_micros() as i64;
    SmolInstant::from_micros(micros)
}

// ── Public API ────────────────────────────────────────────────────────────────

/// Userspace TCP/IP stack wrapping smoltcp.
///
/// All state lives in a single `Mutex<Inner>`.  The engine calls
/// `feed_inbound()` for every packet from the TUN fd, then calls `poll()`
/// on a regular schedule.  After each `poll()` the engine drains
/// `drain_outbound()` and `accept()`.
pub struct UserspaceStack {
    inner: Arc<Mutex<Inner>>,
}

impl UserspaceStack {
    pub fn new(mtu: u16) -> Result<Self, StackError> {
        if mtu < 576 || mtu > 9000 {
            return Err(StackError::InvalidMtu(mtu));
        }
        Ok(Self {
            inner: Arc::new(Mutex::new(Inner::new(mtu))),
        })
    }

    pub fn mtu(&self) -> u16 {
        SmolDevice::capabilities(&self.inner.lock().device).max_transmission_unit as u16
    }

    /// Feed a raw IP packet from TUN into the stack for processing.
    pub fn feed_inbound(&self, pkt: BytesMut) {
        self.inner.lock().tun_rx.push_back(pkt);
    }

    /// Drain the next outbound packet (written by smoltcp) to send to TUN.
    pub fn drain_outbound(&self) -> Option<BytesMut> {
        self.inner.lock().tun_tx.pop_front()
    }

    /// Drive the smoltcp poll cycle once.  Returns `true` if further immediate
    /// polling is needed.
    pub fn poll(&self) -> bool {
        self.inner.lock().poll()
    }

    /// Return the next intercepted TCP connection ready for the SOCKS5 relay,
    /// or `None` if none are available.
    pub fn accept(&self) -> Option<VirtualConnection> {
        self.inner.lock().pending.pop_front()
    }
}

// Override mtu() properly.
impl UserspaceStack {
    #[doc(hidden)]
    pub fn _mtu_inner(&self) -> u16 {
        // The mtu is baked into TunVirtualDevice.  Expose it properly.
        SmolDevice::capabilities(&self.inner.lock().device).max_transmission_unit as u16
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn invalid_mtu() {
        assert!(UserspaceStack::new(100).is_err());
        assert!(UserspaceStack::new(9001).is_err());
    }

    #[test]
    fn valid_mtu_constructs() {
        let stack = UserspaceStack::new(1280).unwrap();
        // Just verify construction doesn't panic.
        assert!(!stack.poll());
    }

    #[test]
    fn feed_and_poll_no_panic() {
        let stack = UserspaceStack::new(1280).unwrap();
        // Feed a non-TCP packet; should be silently dropped.
        let mut pkt = BytesMut::zeroed(40);
        pkt[0] = 0x45; // IPv4
        pkt[9] = 17;   // UDP
        stack.feed_inbound(pkt);
        stack.poll();
        assert!(stack.drain_outbound().is_none());
    }
}
