// JNI entry points live in the cdylib root crate so the linker places them
// in the dynamic export table of libgravital_engine.so.
// Symbols defined only in dependency rlibs are not re-exported from a cdylib.

use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use gravital_ffi::c_api::FFI_VERSION;

macro_rules! ffi_catch {
    ($body:expr) => {
        match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| $body)) {
            Ok(v) => v,
            Err(_) => -6,
        }
    };
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_init(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jint {
    ffi_catch!({
        let _json: String = env.get_string(&config_json)
            .map(|s| s.into())
            .unwrap_or_default();
        0
    })
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_startClient(
    mut env: JNIEnv,
    _class: JClass,
    tun_fd: jint,
    config_json: JString,
) -> jint {
    ffi_catch!({
        if tun_fd < 0 { return -4; }
        let _json: String = env.get_string(&config_json)
            .map(|s| s.into())
            .unwrap_or_default();
        0
    })
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_startServer(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jint {
    ffi_catch!({
        let _json: String = env.get_string(&config_json)
            .map(|s| s.into())
            .unwrap_or_default();
        0
    })
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_stop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    0
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_shutdown(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    0
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_getState(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(r#"{"state":"Idle"}"#)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_getStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    env.new_string(r#"{"bytes_in":0,"bytes_out":0}"#)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_ffiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    FFI_VERSION as jint
}
