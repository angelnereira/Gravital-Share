/// Platform-specific TUN helpers.

/// On Linux/Android: open /dev/net/tun with IFF_TUN | IFF_NO_PI.
/// Returns the raw fd. Used for testing on Linux desktop without Android NDK.
#[cfg(target_os = "linux")]
pub fn open_tun(name: &str) -> Result<std::os::unix::io::RawFd, crate::TunError> {
    use std::ffi::CString;

    const TUNSETIFF: u64 = 0x400454CA;
    const IFF_TUN: i16   = 0x0001;
    const IFF_NO_PI: i16 = 0x1000;

    let fd = unsafe { libc::open(b"/dev/net/tun\0".as_ptr().cast(), libc::O_RDWR) };
    if fd < 0 {
        return Err(crate::TunError::Io(std::io::Error::last_os_error()));
    }

    #[repr(C)]
    struct Ifreq {
        ifr_name: [u8; 16],
        ifr_flags: i16,
        _pad: [u8; 22],
    }

    let mut ifr = Ifreq {
        ifr_name: [0u8; 16],
        ifr_flags: IFF_TUN | IFF_NO_PI,
        _pad: [0u8; 22],
    };

    let name_c = CString::new(name).map_err(|_| crate::TunError::InvalidFd(-1))?;
    let name_bytes = name_c.as_bytes_with_nul();
    let copy_len = name_bytes.len().min(15);
    ifr.ifr_name[..copy_len].copy_from_slice(&name_bytes[..copy_len]);

    // SAFETY: fd is valid, ifr is properly initialized.
    let ret = unsafe { libc::ioctl(fd, TUNSETIFF as _, &ifr) };
    if ret < 0 {
        unsafe { libc::close(fd) };
        return Err(crate::TunError::Io(std::io::Error::last_os_error()));
    }

    Ok(fd)
}

#[cfg(not(target_os = "linux"))]
pub fn open_tun(_name: &str) -> Result<std::os::unix::io::RawFd, crate::TunError> {
    Err(crate::TunError::Io(std::io::Error::new(
        std::io::ErrorKind::Unsupported,
        "open_tun not supported on this platform",
    )))
}
