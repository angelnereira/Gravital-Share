#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::pedantic)]
#![allow(clippy::module_name_repetitions)]

pub mod error;
pub mod stack;
pub mod connection;

pub use error::StackError;
pub use stack::UserspaceStack;
pub use connection::VirtualConnection;
