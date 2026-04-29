use std::os::unix::io::RawFd;
use std::sync::Arc;
use tokio::io::unix::AsyncFd;
use tokio::sync::mpsc;
use bytes::BytesMut;
use crate::error::TunError;

/// Default packet buffer size — slightly above MTU 1280 for headroom.
const BUF_SIZE: usize = 1600;

/// A non-blocking TUN device backed by an `AsyncFd`.
/// On Android, the `RawFd` is borrowed from `VpnService.Builder.establish().detachFd()`.
/// Rust does NOT close this fd — Android retains ownership.
pub struct TunDevice {
    fd: Arc<AsyncFd<std::fs::File>>,
    mtu: u16,
}

impl TunDevice {
    /// Create from a file descriptor. Sets fd to O_NONBLOCK.
    pub fn from_fd(raw_fd: RawFd, mtu: u16) -> Result<Self, TunError> {
        if raw_fd < 0 {
            return Err(TunError::InvalidFd(raw_fd));
        }

        // SAFETY: We're given a valid file descriptor owned by the caller.
        // We wrap it to avoid double-close: we must not drop the File as it closes the fd.
        let file = unsafe {
            use std::os::unix::io::FromRawFd;
            std::fs::File::from_raw_fd(raw_fd)
        };

        // Set non-blocking
        // SAFETY: fd is valid, we own file.
        unsafe {
            let flags = libc::fcntl(raw_fd, libc::F_GETFL, 0);
            if flags == -1 {
                return Err(TunError::Io(std::io::Error::last_os_error()));
            }
            if libc::fcntl(raw_fd, libc::F_SETFL, flags | libc::O_NONBLOCK) == -1 {
                return Err(TunError::Io(std::io::Error::last_os_error()));
            }
        }

        let async_fd = AsyncFd::new(file).map_err(TunError::Io)?;

        Ok(Self {
            fd: Arc::new(async_fd),
            mtu,
        })
    }

    /// Split into reader and writer halves.
    pub fn split(self) -> (TunReader, TunWriter) {
        let fd = self.fd;
        let mtu = self.mtu;
        (
            TunReader { fd: fd.clone(), mtu },
            TunWriter { fd, mtu },
        )
    }
}

pub struct TunReader {
    fd: Arc<AsyncFd<std::fs::File>>,
    mtu: u16,
}

impl TunReader {
    pub async fn read_packet(&self) -> Result<BytesMut, TunError> {
        let mut buf = BytesMut::with_capacity(BUF_SIZE);
        buf.resize(BUF_SIZE, 0);

        loop {
            let mut guard = self.fd.readable().await.map_err(TunError::Io)?;
            match guard.try_io(|inner| {
                use std::io::Read;
                // SAFETY: AsyncFd gives us &File, which implements Read via the fd.
                let n = unsafe {
                    let fd_raw = std::os::unix::io::AsRawFd::as_raw_fd(inner.get_ref());
                    libc::read(fd_raw, buf.as_mut_ptr().cast(), buf.len())
                };
                if n < 0 {
                    Err(std::io::Error::last_os_error())
                } else {
                    Ok(n as usize)
                }
            }) {
                Ok(Ok(n)) => {
                    buf.truncate(n);
                    return Ok(buf);
                }
                Ok(Err(e)) => return Err(TunError::Io(e)),
                Err(_would_block) => continue,
            }
        }
    }
}

pub struct TunWriter {
    fd: Arc<AsyncFd<std::fs::File>>,
    mtu: u16,
}

impl TunWriter {
    pub async fn write_packet(&self, data: &[u8]) -> Result<(), TunError> {
        if data.len() > self.mtu as usize {
            return Err(TunError::PacketTooLarge { size: data.len(), mtu: self.mtu });
        }

        loop {
            let mut guard = self.fd.writable().await.map_err(TunError::Io)?;
            match guard.try_io(|inner| {
                // SAFETY: fd is valid.
                let n = unsafe {
                    let fd_raw = std::os::unix::io::AsRawFd::as_raw_fd(inner.get_ref());
                    libc::write(fd_raw, data.as_ptr().cast(), data.len())
                };
                if n < 0 {
                    Err(std::io::Error::last_os_error())
                } else {
                    Ok(n as usize)
                }
            }) {
                Ok(Ok(_)) => return Ok(()),
                Ok(Err(e)) => return Err(TunError::Io(e)),
                Err(_would_block) => continue,
            }
        }
    }
}

/// Channel-based pair for passing packet buffers between tasks.
pub fn packet_channel(capacity: usize) -> (mpsc::Sender<BytesMut>, mpsc::Receiver<BytesMut>) {
    mpsc::channel(capacity)
}
