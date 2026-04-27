#![no_main]
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // Exercise SOCKS5 UDP datagram header parsing.
    let _ = gravital_proto::IpView::parse(data);
});
