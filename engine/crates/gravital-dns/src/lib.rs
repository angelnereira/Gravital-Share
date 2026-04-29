#![deny(unsafe_op_in_unsafe_fn)]
#![warn(clippy::pedantic)]

pub mod error;
pub mod interceptor;
pub mod resolver;
pub mod leak_check;

pub use error::DnsError;
pub use interceptor::DnsInterceptor;
pub use resolver::DnsResolver;
pub use leak_check::LeakCheck;
