#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::pedantic)]

pub mod error;
pub mod server;

pub use error::HttpProxyError;
pub use server::{HttpProxyServer, HttpProxyConfig};
