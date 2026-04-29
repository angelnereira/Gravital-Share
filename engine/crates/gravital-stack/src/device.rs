use smoltcp::phy::{Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::time::Instant as SmolInstant;
use std::collections::VecDeque;

/// In-memory smoltcp Device backed by two VecDeques.
/// The engine feeds raw (rewritten) IP packets into `rx` before calling
/// `Interface::poll`, then drains `tx` afterward to write back to the TUN fd.
pub(crate) struct TunVirtualDevice {
    pub rx: VecDeque<Vec<u8>>,
    pub tx: VecDeque<Vec<u8>>,
    mtu: usize,
}

impl TunVirtualDevice {
    pub fn new(mtu: usize) -> Self {
        Self {
            rx: VecDeque::with_capacity(256),
            tx: VecDeque::with_capacity(256),
            mtu,
        }
    }

    pub fn has_rx(&self) -> bool {
        !self.rx.is_empty()
    }
}

// ── RxToken: owns its packet bytes so there is no borrow of the device ────────

pub(crate) struct VirtualRxToken(Vec<u8>);

impl RxToken for VirtualRxToken {
    fn consume<R, F>(mut self, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        f(&mut self.0)
    }
}

// ── TxToken: borrows the tx queue; only the tx field of the device is held ────

pub(crate) struct VirtualTxToken<'a>(&'a mut VecDeque<Vec<u8>>);

impl<'a> TxToken for VirtualTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buf = vec![0u8; len];
        let r = f(&mut buf);
        self.0.push_back(buf);
        r
    }
}

// ── Device impl ───────────────────────────────────────────────────────────────

impl Device for TunVirtualDevice {
    type RxToken<'a>
        = VirtualRxToken
    where
        Self: 'a;
    type TxToken<'a>
        = VirtualTxToken<'a>
    where
        Self: 'a;

    fn receive(&mut self, _ts: SmolInstant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        // Pop from rx (owned), borrow tx — disjoint fields, NLL allows this.
        let pkt = self.rx.pop_front()?;
        Some((VirtualRxToken(pkt), VirtualTxToken(&mut self.tx)))
    }

    fn transmit(&mut self, _ts: SmolInstant) -> Option<Self::TxToken<'_>> {
        Some(VirtualTxToken(&mut self.tx))
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.max_transmission_unit = self.mtu;
        caps.medium = Medium::Ip;
        caps
    }
}
