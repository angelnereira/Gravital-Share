#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::pedantic)]

/// FFI surface exposed to Android via JNI.
/// Every public extern "C" fn catches panics — panics NEVER cross the FFI boundary.
pub mod c_api;

#[cfg(target_os = "android")]
pub mod jni_api;
