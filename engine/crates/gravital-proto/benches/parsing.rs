// Placeholder bench — run with `cargo bench` when criterion is available.
fn main() {
    let pkt = vec![
        0x45u8, 0x00, 0x00, 0x28,
        0x00, 0x00, 0x40, 0x00,
        0x40, 0x06, 0x00, 0x00,
        10, 42, 0, 2,
        8, 8, 8, 8,
    ];
    for _ in 0..1_000_000 {
        let _ = gravital_proto::IpView::parse(&pkt);
    }
}
