#![no_main]
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // Must not panic on any input.
    let _ = gravital_proto::IpView::parse(data);
});
