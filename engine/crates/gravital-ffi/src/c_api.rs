/// C-compatible API surface. Stable across versions — version tracked by FFI_VERSION.
/// All strings are UTF-8 NUL-terminated. Rust never allocates memory for the caller.
/// Return 0 = OK; negative = error (see ffi_code constants).

pub const FFI_VERSION: u32 = 1;

macro_rules! catch_panic {
    ($body:expr) => {
        match std::panic::catch_unwind(|| $body) {
            Ok(v) => v,
            Err(_) => {
                tracing::error!(kind = "ffi.panic_caught");
                -6 // INTERNAL_PANIC
            }
        }
    };
}

/// Write a Rust &str into a caller-provided buffer. Returns bytes written or -7 if too small.
fn write_str_to_buf(s: &str, buf: *mut std::os::raw::c_char, len: usize) -> i32 {
    if buf.is_null() || len == 0 {
        return -7;
    }
    let bytes = s.as_bytes();
    if bytes.len() + 1 > len {
        return -7;
    }
    // SAFETY: caller guarantees buf is valid for `len` bytes.
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), buf as *mut u8, bytes.len());
        *buf.add(bytes.len()) = 0;
    }
    bytes.len() as i32
}

/// Initialize the engine. Must be called before any other function.
///
/// # Safety
/// `config_json` must be a valid NUL-terminated UTF-8 string or NULL.
#[no_mangle]
pub unsafe extern "C" fn gravital_engine_init(config_json: *const std::os::raw::c_char) -> i32 {
    catch_panic!({
        // config_json is optional at init time
        0 // OK — engine module init happens here in the real implementation
    })
}

/// Start the engine in server mode.
///
/// # Safety
/// `config_json` must be a valid NUL-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn gravital_engine_start_server(
    config_json: *const std::os::raw::c_char,
) -> i32 {
    catch_panic!({
        if config_json.is_null() {
            return -1; // INVALID_ARG
        }
        let json = unsafe { std::ffi::CStr::from_ptr(config_json) }
            .to_str()
            .unwrap_or_default();
        // Parse config and start server — real impl calls Engine::get().start_server()
        tracing::info!(kind = "ffi.start_server", config_len = json.len());
        0
    })
}

/// Start the engine in client mode, using the provided TUN file descriptor.
///
/// # Safety
/// `config_json` must be a valid NUL-terminated UTF-8 string. `tun_fd` must be valid.
#[no_mangle]
pub unsafe extern "C" fn gravital_engine_start_client(
    tun_fd: i32,
    config_json: *const std::os::raw::c_char,
) -> i32 {
    catch_panic!({
        if tun_fd < 0 {
            return -4; // INVALID_FD
        }
        if config_json.is_null() {
            return -1; // INVALID_ARG
        }
        let json = unsafe { std::ffi::CStr::from_ptr(config_json) }
            .to_str()
            .unwrap_or_default();
        tracing::info!(kind = "ffi.start_client", tun_fd = tun_fd, config_len = json.len());
        0
    })
}

/// Stop the active session gracefully.
#[no_mangle]
pub extern "C" fn gravital_engine_stop() -> i32 {
    catch_panic!({
        tracing::info!(kind = "ffi.stop");
        0
    })
}

/// Shutdown the engine completely (frees runtime).
#[no_mangle]
pub extern "C" fn gravital_engine_shutdown() -> i32 {
    catch_panic!({
        tracing::info!(kind = "ffi.shutdown");
        0
    })
}

/// Get current engine state as JSON.
///
/// # Safety
/// `out_buf` must be valid for `out_buf_len` bytes.
#[no_mangle]
pub unsafe extern "C" fn gravital_engine_get_state(
    out_buf: *mut std::os::raw::c_char,
    out_buf_len: usize,
) -> i32 {
    catch_panic!({
        let state = r#"{"state":"Idle"}"#;
        write_str_to_buf(state, out_buf, out_buf_len)
    })
}

/// Get metrics snapshot as JSON.
///
/// # Safety
/// `out_buf` must be valid for `out_buf_len` bytes.
#[no_mangle]
pub unsafe extern "C" fn gravital_engine_get_stats(
    out_buf: *mut std::os::raw::c_char,
    out_buf_len: usize,
) -> i32 {
    catch_panic!({
        let stats = r#"{"bytes_in":0,"bytes_out":0}"#;
        write_str_to_buf(stats, out_buf, out_buf_len)
    })
}

pub type GravitalEventCb = extern "C" fn(json_event: *const std::os::raw::c_char, user_data: *mut std::os::raw::c_void);

/// Register a callback for engine events (telemetry).
///
/// # Safety
/// `cb` must be a valid function pointer. `user_data` is passed through opaquely.
#[no_mangle]
pub unsafe extern "C" fn gravital_engine_set_event_callback(
    _cb: GravitalEventCb,
    _user_data: *mut std::os::raw::c_void,
) -> i32 {
    catch_panic!({
        tracing::info!(kind = "ffi.set_event_callback");
        0
    })
}

/// Return the engine version string (NUL-terminated static string).
#[no_mangle]
pub extern "C" fn gravital_engine_version() -> *const std::os::raw::c_char {
    b"0.1.0\0".as_ptr() as *const std::os::raw::c_char
}

/// Return the FFI contract version.
#[no_mangle]
pub extern "C" fn gravital_engine_ffi_version() -> u32 {
    FFI_VERSION
}
