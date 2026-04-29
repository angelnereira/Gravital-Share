use std::collections::HashMap;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU32, Ordering};
use parking_lot::Mutex;
use tokio::net::{TcpStream, UdpSocket};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use crate::error::UdpGwError;
use crate::frame::UdpGwFrame;

/// Multiplexes many UDP associations over a single TCP connection.
/// Used when the upstream SOCKS5 proxy does not support UDP ASSOCIATE.
pub struct UdpGwMux {
    next_id: AtomicU32,
    associations: Arc<Mutex<HashMap<u32, SocketAddr>>>,
}

impl UdpGwMux {
    pub fn new() -> Self {
        Self {
            next_id: AtomicU32::new(1),
            associations: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    pub fn allocate_id(&self, client_addr: SocketAddr) -> u32 {
        let id = self.next_id.fetch_add(1, Ordering::SeqCst);
        self.associations.lock().insert(id, client_addr);
        id
    }

    pub fn lookup(&self, id: u32) -> Option<SocketAddr> {
        self.associations.lock().get(&id).copied()
    }

    pub fn release(&self, id: u32) {
        self.associations.lock().remove(&id);
    }

    pub fn active_count(&self) -> usize {
        self.associations.lock().len()
    }
}

impl Default for UdpGwMux {
    fn default() -> Self {
        Self::new()
    }
}
