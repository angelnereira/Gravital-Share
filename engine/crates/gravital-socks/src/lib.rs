#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::pedantic)]
#![allow(clippy::module_name_repetitions)]

pub mod error;
pub mod proto;
pub mod server;
pub mod client;

pub use error::SocksError;
pub use proto::{AddrSpec, Cmd, Rep};
pub use server::SocksServer;
pub use client::SocksClient;
