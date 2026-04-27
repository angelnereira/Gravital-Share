/// JNI bindings for Android — mirrors the C API but adapted to JNI calling conventions.
use jni::JNIEnv;
use jni::objects::{JClass, JString, JObject};
use jni::sys::{jint, jstring};

/// io.gravital.share.ffi.EngineBridge.init(configJson: String): Int
#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_init(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jint {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _json: String = env.get_string(&config_json)
            .map(|s| s.into())
            .unwrap_or_default();
        0i32
    })) {
        Ok(v) => v,
        Err(_) => -6,
    }
}

/// io.gravital.share.ffi.EngineBridge.startClient(tunFd: Int, configJson: String): Int
#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_startClient(
    mut env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
    config_json: JString,
) -> jint {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        if tun_fd < 0 { return -4i32; }
        let _json: String = env.get_string(&config_json)
            .map(|s| s.into())
            .unwrap_or_default();
        // Real implementation: Engine::get().start_client(tun_fd, cfg)
        0i32
    })) {
        Ok(v) => v,
        Err(_) => -6,
    }
}

/// io.gravital.share.ffi.EngineBridge.startServer(configJson: String): Int
#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_startServer(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jint {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let _json: String = env.get_string(&config_json)
            .map(|s| s.into())
            .unwrap_or_default();
        0i32
    })) {
        Ok(v) => v,
        Err(_) => -6,
    }
}

/// io.gravital.share.ffi.EngineBridge.stop(): Int
#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_stop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    0
}

/// io.gravital.share.ffi.EngineBridge.shutdown(): Int
#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_shutdown(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    0
}

/// io.gravital.share.ffi.EngineBridge.getState(): String
#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_getState(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let state = r#"{"state":"Idle"}"#;
    env.new_string(state)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// io.gravital.share.ffi.EngineBridge.getStats(): String
#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_getStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let stats = r#"{"bytes_in":0,"bytes_out":0}"#;
    env.new_string(stats)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// io.gravital.share.ffi.EngineBridge.ffiVersion(): Int
#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_ffiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    super::c_api::FFI_VERSION as jint
}
