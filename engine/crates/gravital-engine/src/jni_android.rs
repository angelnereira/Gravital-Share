// JNI entry points live in the cdylib root crate so the linker places them
// in the dynamic export table of libgravital_engine.so.
// Symbols defined only in dependency rlibs are not re-exported from a cdylib.

use jni::JNIEnv;
use jni::objects::{JClass, JObject, JString, JValue};
use jni::sys::{jint, jstring};
use std::sync::{Mutex, OnceLock};
use crate::runner::FFI_VERSION;
use crate::error::ffi_code;

// ── JVM / callback globals ────────────────────────────────────────────────────

/// JVM reference kept for attaching native Tokio threads when firing callbacks.
static JVM_INSTANCE: OnceLock<jni::JavaVM> = OnceLock::new();

/// GlobalRef to the Kotlin EventCallback instance registered by Kotlin.
static EVENT_CALLBACK: Mutex<Option<jni::objects::GlobalRef>> = Mutex::new(None);

/// Called from the session listener (any Tokio thread) to invoke the Kotlin callback.
fn fire_jvm_event(json: String) {
    let Some(jvm) = JVM_INSTANCE.get() else { return };
    let guard = match EVENT_CALLBACK.lock() {
        Ok(g) => g,
        Err(_) => return,
    };
    let Some(cb) = guard.as_ref() else { return };
    let mut env = match jvm.attach_current_thread() {
        Ok(e) => e,
        Err(_) => return,
    };
    if let Ok(json_jstr) = env.new_string(&json) {
        // jni 0.21 has no From<JString> for JValue directly; go via JObject.
        let json_obj = JObject::from(json_jstr);
        let _ = env.call_method(
            cb.as_obj(),
            "onEvent",
            "(Ljava/lang/String;)V",
            &[JValue::Object(&json_obj)],
        );
    }
}

macro_rules! ffi_catch {
    ($body:expr) => {
        match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| $body)) {
            Ok(v) => v,
            Err(_) => ffi_code::INTERNAL_PANIC,
        }
    };
}

/// Get the engine singleton, initialising it on first call.
fn get_or_init() -> Result<std::sync::Arc<crate::runner::Engine>, crate::error::EngineError> {
    if let Ok(e) = crate::runner::Engine::get() {
        return Ok(e);
    }
    match crate::runner::Engine::init() {
        Ok(()) | Err(crate::error::EngineError::AlreadyInitialized) => {}
        Err(e) => return Err(e),
    }
    crate::runner::Engine::get()
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
        match crate::runner::Engine::init() {
            Ok(()) => ffi_code::OK,
            Err(crate::error::EngineError::AlreadyInitialized) => ffi_code::OK,
            Err(_) => ffi_code::INVALID_ARG,
        }
    })
}

/// Register the Kotlin EventCallback so the engine can call back into JVM.
/// Must be called before startClient / startServer to ensure events are delivered.
#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_nativeSetEventCallback(
    env: JNIEnv,
    _class: JClass,
    callback: JObject,
) -> jint {
    ffi_catch!({
        // Store the JVM so Tokio threads can attach when firing callbacks.
        if JVM_INSTANCE.get().is_none() {
            match env.get_java_vm() {
                Ok(jvm) => { let _ = JVM_INSTANCE.set(jvm); }
                Err(_) => return ffi_code::INTERNAL_PANIC,
            }
        }

        // Create a global ref that survives past this JNI call.
        match env.new_global_ref(callback) {
            Ok(global_ref) => {
                if let Ok(mut guard) = EVENT_CALLBACK.lock() {
                    *guard = Some(global_ref);
                }
            }
            Err(_) => return ffi_code::INTERNAL_PANIC,
        }

        // Wire fire_jvm_event into the engine — initialises the engine if needed.
        match get_or_init() {
            Err(_) => ffi_code::NOT_INITIALIZED,
            Ok(engine) => {
                engine.set_event_callback(fire_jvm_event);
                ffi_code::OK
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_startServer(
    mut env: JNIEnv,
    _class: JClass,
    config_json: JString,
) -> jint {
    ffi_catch!({
        let json: String = env.get_string(&config_json)
            .map(|s| s.into())
            .unwrap_or_default();

        let server_cfg = match serde_json::from_str::<crate::config::EngineConfig>(&json) {
            Ok(crate::config::EngineConfig::Server(c)) => c,
            _ => crate::config::ServerConfig::default(),
        };

        match get_or_init() {
            Err(_) => ffi_code::NOT_INITIALIZED,
            Ok(engine) => match engine.start_server(server_cfg) {
                Ok(()) => ffi_code::OK,
                Err(crate::error::EngineError::InvalidFd) => ffi_code::INVALID_FD,
                Err(_) => ffi_code::INVALID_ARG,
            },
        }
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
        if tun_fd < 0 { return ffi_code::INVALID_FD; }

        let json: String = env.get_string(&config_json)
            .map(|s| s.into())
            .unwrap_or_default();

        let client_cfg = match serde_json::from_str::<crate::config::EngineConfig>(&json) {
            Ok(crate::config::EngineConfig::Client(c)) => c,
            _ => crate::config::ClientConfig::default(),
        };

        match get_or_init() {
            Err(_) => ffi_code::NOT_INITIALIZED,
            Ok(engine) => match engine.start_client(tun_fd, client_cfg) {
                Ok(()) => ffi_code::OK,
                Err(crate::error::EngineError::InvalidFd) => ffi_code::INVALID_FD,
                Err(_) => ffi_code::INVALID_ARG,
            },
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_stop(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ffi_catch!({
        match crate::runner::Engine::get() {
            Ok(engine) => match engine.stop() {
                Ok(()) => ffi_code::OK,
                Err(_) => ffi_code::INVALID_ARG,
            },
            Err(_) => ffi_code::OK, // already stopped
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_shutdown(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    ffi_catch!({
        if let Ok(engine) = crate::runner::Engine::get() {
            engine.shutdown();
        }
        ffi_code::OK
    })
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_getState(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = crate::runner::Engine::get()
        .map(|e| e.state_json())
        .unwrap_or_else(|_| r#"{"state":"Idle"}"#.to_string());
    env.new_string(json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_gravital_share_ffi_EngineBridge_getStats(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let json = crate::runner::Engine::get()
        .map(|e| e.metrics_json())
        .unwrap_or_else(|_| r#"{"bytes_in":0,"bytes_out":0}"#.to_string());
    env.new_string(json)
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
