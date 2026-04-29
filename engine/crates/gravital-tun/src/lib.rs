#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::pedantic)]

pub mod error;
pub mod device;
pub mod platform;

pub use device::{TunDevice, TunReader, TunWriter};
pub use error::TunError;
