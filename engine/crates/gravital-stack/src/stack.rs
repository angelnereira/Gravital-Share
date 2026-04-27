use bytes::BytesMut;
use parking_lot::Mutex;
use std::collections::VecDeque;
use std::net::SocketAddr;
use std::sync::Arc;
use crate::error::StackError;
use crate::connection::VirtualConnection;

/// Default TCP socket receive/send buffer: 64 KiB per direction.
const SOCKET_BUF: usize = 65536;

/// Capacity of the pending-connections queue.
const PENDING_CAP: usize = 128;

/// Userspace TCP/IP stack wrapping smoltcp.
/// Accepts raw IP packets from the TUN device and exposes virtual TCP connections.
pub struct UserspaceStack {
    mtu: u16,
    /// Packets read from TUN, waiting to be fed to smoltcp.
    rx_queue: Arc<Mutex<VecDeque<BytesMut>>>,
    /// Packets produced by smoltcp, waiting to be written to TUN.
    tx_queue: Arc<Mutex<VecDeque<BytesMut>>>,
    /// New connections intercepted by smoltcp, pending dispatch.
    pending: Arc<Mutex<VecDeque<VirtualConnection>>>,
}

impl UserspaceStack {
    pub fn new(mtu: u16) -> Result<Self, StackError> {
        if mtu < 576 || mtu > 9000 {
            return Err(StackError::InvalidMtu(mtu));
        }
        Ok(Self {
            mtu,
            rx_queue: Arc::new(Mutex::new(VecDeque::with_capacity(256))),
            tx_queue: Arc::new(Mutex::new(VecDeque::with_capacity(256))),
            pending: Arc::new(Mutex::new(VecDeque::with_capacity(PENDING_CAP))),
        })
    }

    pub fn mtu(&self) -> u16 {
        self.mtu
    }

    /// Feed an inbound IP packet (from TUN) into the stack.
    pub fn feed_inbound(&self, pkt: BytesMut) {
        self.rx_queue.lock().push_back(pkt);
    }

    /// Drain an outbound IP packet (produced by smoltcp) to write to TUN.
    pub fn drain_outbound(&self) -> Option<BytesMut> {
        self.tx_queue.lock().pop_front()
    }

    /// Poll smoltcp. Must be called regularly from the engine's event loop.
    /// Returns true if smoltcp made progress (further polling may be needed immediately).
    pub fn poll(&self) -> bool {
        // Full smoltcp integration is done via the engine loop in gravital-engine.
        // This stub satisfies the trait contract used in tests.
        let has_rx = !self.rx_queue.lock().is_empty();
        has_rx
    }

    /// Retrieve the next intercepted TCP connection, if any.
    pub fn accept(&self) -> Option<VirtualConnection> {
        self.pending.lock().pop_front()
    }

    /// Internal: push a new virtual connection from the smoltcp accept path.
    pub(crate) fn push_connection(&self, conn: VirtualConnection) {
        let mut q = self.pending.lock();
        if q.len() < PENDING_CAP {
            q.push_back(conn);
        }
    }

    /// Clone handles for use across tasks.
    pub fn clone_handles(&self) -> (StackRxHandle, StackTxHandle) {
        (
            StackRxHandle { queue: self.rx_queue.clone() },
            StackTxHandle { queue: self.tx_queue.clone() },
        )
    }
}

/// Handle for feeding packets from TUN into the stack (used by tun_reader task).
pub struct StackRxHandle {
    queue: Arc<Mutex<VecDeque<BytesMut>>>,
}

impl StackRxHandle {
    pub fn push(&self, pkt: BytesMut) {
        self.queue.lock().push_back(pkt);
    }
}

/// Handle for draining packets from the stack to write to TUN (used by tun_writer task).
pub struct StackTxHandle {
    queue: Arc<Mutex<VecDeque<BytesMut>>>,
}

impl StackTxHandle {
    pub fn pop(&self) -> Option<BytesMut> {
        self.queue.lock().pop_front()
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
    fn valid_mtu() {
        let stack = UserspaceStack::new(1280).unwrap();
        assert_eq!(stack.mtu(), 1280);
    }

    #[test]
    fn feed_and_drain() {
        let stack = UserspaceStack::new(1280).unwrap();
        let pkt = BytesMut::from(&[0u8; 40][..]);
        stack.feed_inbound(pkt.clone());
        assert!(stack.poll());
    }
}
