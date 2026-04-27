use std::net::SocketAddr;
use bytes::BytesMut;
use tokio::sync::mpsc;

/// A virtual TCP connection intercepted by the userspace stack.
/// Bridges between smoltcp's socket and the SOCKS5 relay task.
pub struct VirtualConnection {
    pub local: SocketAddr,
    pub remote: SocketAddr,
    /// Inbound data from the user-side TCP stream (to be sent upstream via SOCKS).
    pub rx: mpsc::Receiver<BytesMut>,
    /// Outbound data from upstream (to be injected back into the user-side stack).
    pub tx: mpsc::Sender<BytesMut>,
}

impl VirtualConnection {
    pub fn pair(local: SocketAddr, remote: SocketAddr) -> (Self, VirtualConnectionPeer) {
        let (tx_a, rx_a) = mpsc::channel::<BytesMut>(256);
        let (tx_b, rx_b) = mpsc::channel::<BytesMut>(256);

        let conn = VirtualConnection {
            local,
            remote,
            rx: rx_a,
            tx: tx_b,
        };
        let peer = VirtualConnectionPeer {
            rx: rx_b,
            tx: tx_a,
        };
        (conn, peer)
    }
}

/// The other end of a VirtualConnection — held by the stack's poll loop.
pub struct VirtualConnectionPeer {
    pub rx: mpsc::Receiver<BytesMut>,
    pub tx: mpsc::Sender<BytesMut>,
}
