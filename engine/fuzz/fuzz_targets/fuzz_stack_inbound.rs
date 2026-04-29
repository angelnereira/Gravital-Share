#![no_main]
use libfuzzer_sys::fuzz_target;
use bytes::BytesMut;

fuzz_target!(|data: &[u8]| {
    if let Ok(stack) = gravital_stack::UserspaceStack::new(1280) {
        stack.feed_inbound(BytesMut::from(data));
        stack.poll();
        while stack.drain_outbound().is_some() {}
        // accept() should never panic regardless of input.
        drop(stack.accept());
    }
});
