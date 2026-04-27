#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::pedantic)]
#![allow(clippy::module_name_repetitions)]

pub(crate) mod device;
pub mod error;
pub mod nat;
pub mod rewrite;
pub mod stack;
pub mod connection;

pub use error::StackError;
pub use stack::UserspaceStack;
pub use connection::VirtualConnection;
