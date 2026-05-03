use std::net::SocketAddr;
use std::os::unix::io::RawFd;
use std::sync::Arc;
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use tokio::runtime::Runtime;
use tokio::sync::watch;
use tracing::{debug, info, warn};

use crate::config::{ClientConfig, EngineConfig, ServerConfig};
use crate::error::EngineError;
use crate::metrics::EngineMetrics;
use crate::session::{FailureKind, PeerInfo, Session, SessionEvent, SessionMode, SessionState};

/// FFI contract version — bump on any breaking change.
pub const FFI_VERSION: u32 = 1;

pub struct Engine {
    runtime: Runtime,
    session: Arc<Session>,
    metrics: Arc<EngineMetrics>,
    shutdown_tx: watch::Sender<bool>,
    // Wrapped in Arc so the session listener closure can hold a reference.
    event_callback: Arc<Mutex<Option<Box<dyn Fn(String) + Send + Sync>>>>,
}

/// Global singleton — one engine per process.
static INSTANCE: OnceCell<Arc<Engine>> = OnceCell::new();

impl Engine {
    fn new() -> Result<Arc<Self>, EngineError> {
        let cpus = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(2)
            .min(4);

        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(cpus)
            .enable_all()
            .thread_name("gravital-engine")
            .build()
            .map_err(EngineError::Io)?;

        let (shutdown_tx, _) = watch::channel(false);

        let event_callback: Arc<Mutex<Option<Box<dyn Fn(String) + Send + Sync>>>> =
            Arc::new(Mutex::new(None));
        let session = Arc::new(Session::new());

        // Wire every session state change to event_callback so Kotlin
        // receives transition JSON and can update its own state flow.
        let cb = Arc::clone(&event_callback);
        session.add_listener(move |state| {
            if let Some(f) = cb.lock().as_ref() {
                f(session_state_to_json(state));
            }
        });

        Ok(Arc::new(Self {
            runtime,
            session,
            metrics: EngineMetrics::new(),
            shutdown_tx,
            event_callback,
        }))
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    pub fn init() -> Result<(), EngineError> {
        let engine = Self::new()?;
        INSTANCE.set(engine).map_err(|_| EngineError::AlreadyInitialized)?;
        info!(kind = "engine.boot", ffi_version = FFI_VERSION);
        Ok(())
    }

    pub fn get() -> Result<Arc<Self>, EngineError> {
        INSTANCE.get().cloned().ok_or(EngineError::NotInitialized)
    }

    pub fn start_server(&self, cfg: ServerConfig) -> Result<(), EngineError> {
        // If a previous session left us in a non-Idle state (e.g. Stopping / Failed),
        // force-reset so the state machine accepts UserStart.
        if self.session.state() != SessionState::Idle {
            self.session.force_idle();
            let _ = self.shutdown_tx.send(false);
        }
        self.session.dispatch(SessionEvent::UserStart(SessionMode::Server))?;

        let session = self.session.clone();
        let mut shutdown_rx = self.shutdown_tx.subscribe();

        let socks_srv = gravital_socks::SocksServer::new(gravital_socks::server::ServerConfig {
            bind_addr: cfg.socks_bind,
            auth_enabled: false,
            handshake_timeout_secs: 30,
        });
        let http_srv = gravital_http::HttpProxyServer::new(gravital_http::HttpProxyConfig {
            bind_addr: cfg.http_bind,
            auth_enabled: false,
        });

        self.session.dispatch(SessionEvent::EngineReady)?;

        let conn_counter = socks_srv.active_connections();
        let event_cb = Arc::clone(&self.event_callback);

        self.runtime.spawn(async move {
            let peer = PeerInfo { proxy_addr: cfg.socks_bind };
            let _ = session.dispatch(SessionEvent::ProxyConnected(peer));
            info!(kind = "engine.server.running");

            // Poll the SOCKS connection counter and fire events on changes.
            let counter = conn_counter.clone();
            let cb = event_cb.clone();
            tokio::spawn(async move {
                let mut last = u32::MAX; // force first emission at 0
                loop {
                    tokio::time::sleep(std::time::Duration::from_millis(500)).await;
                    let current = counter.load(std::sync::atomic::Ordering::Relaxed);
                    if current != last {
                        last = current;
                        if let Some(f) = cb.lock().as_ref() {
                            f(format!("{{\"kind\":\"engine.client_count\",\"count\":{}}}", current));
                        }
                    }
                }
            });

            let sr = shutdown_rx.clone();
            let hr = shutdown_rx.clone();
            tokio::join!(socks_srv.run(sr), http_srv.run(hr));

            let _ = session.dispatch(SessionEvent::EngineStopped);
            info!(kind = "engine.server.stopped");
        });

        Ok(())
    }

    pub fn start_client(&self, tun_fd: RawFd, cfg: ClientConfig) -> Result<(), EngineError> {
        if tun_fd < 0 {
            return Err(EngineError::InvalidFd);
        }
        if self.session.state() != SessionState::Idle {
            self.session.force_idle();
            let _ = self.shutdown_tx.send(false);
        }
        self.session.dispatch(SessionEvent::UserStart(SessionMode::Client))?;
        self.session.dispatch(SessionEvent::EngineReady)?;

        let session = self.session.clone();
        let metrics = self.metrics.clone();
        let mut shutdown_rx = self.shutdown_tx.subscribe();

        self.runtime.spawn(async move {
            info!(kind = "engine.client.starting", proxy = %cfg.proxy_addr, mtu = cfg.mtu);

            // Open TUN device from the fd passed by VpnService.
            let tun = match gravital_tun::TunDevice::from_fd(tun_fd, cfg.mtu) {
                Ok(t) => t,
                Err(e) => {
                    tracing::error!(kind = "engine.client.tun_open_failed", error = %e);
                    let _ = session.dispatch(SessionEvent::FatalError(FailureKind::TunOpenFailed));
                    return;
                }
            };

            let (tun_reader, tun_writer) = tun.split();
            let stack = match gravital_stack::UserspaceStack::new(cfg.mtu) {
                Ok(s) => Arc::new(s),
                Err(e) => {
                    tracing::error!(kind = "engine.client.stack_init_failed", error = %e);
                    let _ = session.dispatch(SessionEvent::FatalError(FailureKind::TunOpenFailed));
                    return;
                }
            };

            let socks_client = Arc::new(gravital_socks::SocksClient::new(
                gravital_socks::client::ClientConfig {
                    proxy_addr: cfg.proxy_addr,
                    max_backoff_ms: 30_000,
                },
            ));

            let dns_interceptor = Arc::new(gravital_dns::DnsInterceptor::new(
                gravital_dns::DnsResolver::new(cfg.dns_server, cfg.dns_server_secondary, cfg.proxy_addr),
            ));

            let peer = PeerInfo { proxy_addr: cfg.proxy_addr };
            let _ = session.dispatch(SessionEvent::ProxyConnected(peer));
            info!(kind = "engine.client.connected");

            tokio::select! {
                _ = run_client_loop(
                    tun_reader, tun_writer, stack, socks_client, dns_interceptor, metrics,
                ) => {}
                _ = shutdown_rx.changed() => {}
            }

            let _ = session.dispatch(SessionEvent::EngineStopped);
            info!(kind = "engine.client.stopped");
        });

        Ok(())
    }

    pub fn stop(&self) -> Result<(), EngineError> {
        self.session.dispatch(SessionEvent::UserStop)?;
        let _ = self.shutdown_tx.send(true);
        Ok(())
    }

    pub fn shutdown(&self) {
        let _ = self.shutdown_tx.send(true);
        info!(kind = "engine.shutdown");
    }

    pub fn state_json(&self) -> String {
        let state = self.session.state();
        serde_json::to_string(&format!("{state:?}")).unwrap_or_default()
    }

    pub fn metrics_json(&self) -> String {
        serde_json::to_string(&self.metrics.snapshot()).unwrap_or_default()
    }

    pub fn set_event_callback<F: Fn(String) + Send + Sync + 'static>(&self, f: F) {
        *self.event_callback.lock() = Some(Box::new(f));
    }
}

// ── Session state → JSON transition event ────────────────────────────────────

fn session_state_to_json(state: &SessionState) -> String {
    match state {
        SessionState::Idle =>
            r#"{"kind":"session.transition","to":"Idle"}"#.to_owned(),
        SessionState::Preparing { .. } =>
            r#"{"kind":"session.transition","to":"Preparing"}"#.to_owned(),
        SessionState::Connecting { .. } =>
            r#"{"kind":"session.transition","to":"Connecting"}"#.to_owned(),
        SessionState::Connected { peer, .. } =>
            format!("{{\"kind\":\"session.transition\",\"to\":\"Connected\",\"peer\":\"{}\"}}", peer.proxy_addr),
        SessionState::Reconnecting { .. } =>
            r#"{"kind":"session.transition","to":"Reconnecting"}"#.to_owned(),
        SessionState::Stopping { .. } =>
            r#"{"kind":"session.transition","to":"Stopping"}"#.to_owned(),
        SessionState::Failed { error, .. } =>
            format!("{{\"kind\":\"session.transition\",\"to\":\"Failed\",\"error\":\"{error:?}\"}}"),
    }
}

// ── Client main loop ──────────────────────────────────────────────────────────

async fn run_client_loop(
    reader: gravital_tun::TunReader,
    writer: gravital_tun::TunWriter,
    stack: Arc<gravital_stack::UserspaceStack>,
    socks: Arc<gravital_socks::SocksClient>,
    dns: Arc<gravital_dns::DnsInterceptor>,
    metrics: Arc<EngineMetrics>,
) {
    use std::sync::atomic::Ordering;
    use tokio::time::{interval, Duration};

    // Two concurrent tasks:
    //   • tun_to_stack: reads TUN → feeds stack
    //   • stack_poll: drives smoltcp + drains TUN-write + accepts new conns
    let stack_rx = stack.clone();
    let stack_tx = stack.clone();
    let metrics_rx = metrics.clone();
    let metrics_tx = metrics.clone();
    let writer = Arc::new(tokio::sync::Mutex::new(writer));

    let tun_read_task = {
        let writer_dns = writer.clone();
        tokio::spawn(async move {
            loop {
                match reader.read_packet().await {
                    Ok(pkt) => {
                        metrics_rx.packets_in.fetch_add(1, Ordering::Relaxed);
                        metrics_rx.bytes_in.fetch_add(pkt.len() as u64, Ordering::Relaxed);

                        use gravital_proto::{IpProtocol, IpView};
                        match IpView::parse(&pkt) {
                            Ok(ip) => match ip.protocol() {
                                IpProtocol::Tcp => {
                                    stack_rx.feed_inbound(pkt);
                                }
                                IpProtocol::Udp if udp_dst_port(&pkt) == Some(53) => {
                                    // DNS leak prevention: intercept, resolve via
                                    // protected socket, return SERVFAIL on error.
                                    if let Some(query) = extract_udp_payload(&pkt) {
                                        let dns = dns.clone();
                                        let writer2 = writer_dns.clone();
                                        let pkt_clone = pkt.clone();
                                        let m = metrics_rx.clone();
                                        tokio::spawn(async move {
                                            if let Ok(resp) = dns.handle_query(&query).await {
                                                if let Some(reply) = build_udp_reply(&pkt_clone, &resp) {
                                                    m.packets_out.fetch_add(1, Ordering::Relaxed);
                                                    m.bytes_out.fetch_add(reply.len() as u64, Ordering::Relaxed);
                                                    let _ = writer2.lock().await.write_packet(&bytes::BytesMut::from(reply.as_slice())).await;
                                                }
                                            }
                                        });
                                    }
                                }
                                IpProtocol::Udp => {
                                    // Non-DNS UDP — dropped in MVP (udpgw relay is Phase 2).
                                    metrics_rx.drops_parse_error
                                        .fetch_add(1, Ordering::Relaxed);
                                }
                                _ => {
                                    metrics_rx.drops_parse_error
                                        .fetch_add(1, Ordering::Relaxed);
                                }
                            },
                            Err(_) => {
                                metrics_rx.drops_parse_error
                                    .fetch_add(1, Ordering::Relaxed);
                            }
                        }
                    }
                    Err(e) => {
                        warn!(kind = "engine.tun_read_error", error = %e);
                        break;
                    }
                }
            }
        })
    };

    let poll_task = {
        let socks = socks.clone();
        let writer = writer.clone();
        let metrics = metrics_tx;
        tokio::spawn(async move {
            // Poll smoltcp at most every 5 ms; smoltcp itself drives retransmit
            // timers internally once we call poll() with accurate timestamps.
            let mut ticker = interval(Duration::from_millis(5));
            ticker.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);

            loop {
                ticker.tick().await;

                // Drive the stack.
                stack_tx.poll();

                // Write any packets smoltcp generated back to TUN.
                while let Some(pkt) = stack_tx.drain_outbound() {
                    metrics.bytes_out.fetch_add(pkt.len() as u64, Ordering::Relaxed);
                    metrics.packets_out.fetch_add(1, Ordering::Relaxed);
                    let _ = writer.lock().await.write_packet(&pkt).await;
                }

                // Accept and spawn a relay task for each new virtual connection.
                while let Some(conn) = stack_tx.accept() {
                    let socks = socks.clone();
                    tokio::spawn(async move {
                        relay_connection(conn, socks).await;
                    });
                }
            }
        })
    };

    let _ = tokio::join!(tun_read_task, poll_task);
}

// ── Per-connection SOCKS5 relay ───────────────────────────────────────────────

async fn relay_connection(
    conn: gravital_stack::VirtualConnection,
    socks: Arc<gravital_socks::SocksClient>,
) {
    use gravital_socks::proto::AddrSpec;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    let remote = conn.remote;
    debug!(kind = "relay.start", remote = %remote);

    // Determine SOCKS5 target.
    let (addr_spec, port) = match remote {
        SocketAddr::V4(a) => (AddrSpec::Ipv4(*a.ip()), a.port()),
        SocketAddr::V6(a) => (AddrSpec::Ipv6(*a.ip()), a.port()),
    };

    // Open SOCKS5 CONNECT tunnel to the real remote server.
    let upstream = match socks.connect(addr_spec, port).await {
        Ok(s) => s,
        Err(e) => {
            warn!(kind = "relay.socks_connect_failed", remote = %remote, error = %e);
            return;
        }
    };

    debug!(kind = "relay.socks_connected", remote = %remote);

    let (mut up_rx, mut up_tx) = upstream.into_split();
    let mut conn_rx = conn.rx;
    let mut conn_tx = conn.tx;

    // App → upstream (data from smoltcp socket → SOCKS5 proxy).
    let app_to_up = async move {
        while let Some(data) = conn_rx.recv().await {
            if up_tx.write_all(&data).await.is_err() {
                break;
            }
        }
        let _ = up_tx.shutdown().await;
    };

    // Upstream → app (response from proxy → smoltcp socket).
    let up_to_app = async move {
        let mut buf = vec![0u8; 16_384];
        loop {
            match up_rx.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    let chunk = bytes::BytesMut::from(&buf[..n]);
                    if conn_tx.send(chunk).await.is_err() {
                        break;
                    }
                }
            }
        }
    };

    tokio::join!(app_to_up, up_to_app);
    debug!(kind = "relay.done", remote = %remote);
}

// ── DNS packet helpers ────────────────────────────────────────────────────────

/// Return the UDP destination port from an IPv4/UDP packet, or None.
fn udp_dst_port(pkt: &bytes::BytesMut) -> Option<u16> {
    let p = pkt.as_ref();
    if p.len() < 20 || (p[0] >> 4) != 4 || p[9] != 17 { return None; }
    let ihl = (p[0] & 0x0F) as usize * 4;
    if p.len() < ihl + 8 { return None; }
    Some(u16::from_be_bytes([p[ihl + 2], p[ihl + 3]]))
}

/// Extract the UDP payload from an IPv4/UDP packet.
fn extract_udp_payload(pkt: &bytes::BytesMut) -> Option<Vec<u8>> {
    let p = pkt.as_ref();
    if p.len() < 20 || (p[0] >> 4) != 4 || p[9] != 17 { return None; }
    let ihl = (p[0] & 0x0F) as usize * 4;
    if p.len() < ihl + 8 { return None; }
    let udp_len = u16::from_be_bytes([p[ihl + 4], p[ihl + 5]]) as usize;
    if udp_len < 8 || p.len() < ihl + udp_len { return None; }
    Some(p[ihl + 8..ihl + udp_len].to_vec())
}

/// Build an IPv4/UDP reply from a request packet and a DNS response payload.
/// Swaps src/dst addresses so the reply arrives from the right sender.
fn build_udp_reply(req: &bytes::BytesMut, dns_resp: &[u8]) -> Option<Vec<u8>> {
    let p = req.as_ref();
    if p.len() < 28 || (p[0] >> 4) != 4 || p[9] != 17 { return None; }
    let ihl = (p[0] & 0x0F) as usize * 4;

    let src_ip = &p[12..16];
    let dst_ip = &p[16..20];
    let src_port = &p[ihl..ihl + 2];
    let dst_port = &p[ihl + 2..ihl + 4];

    let udp_len = (8 + dns_resp.len()) as u16;
    let total = 20 + udp_len as usize;
    let mut out = vec![0u8; total];

    // IPv4 header (swap src/dst)
    out[0] = 0x45;
    out[2..4].copy_from_slice(&(total as u16).to_be_bytes());
    out[8] = 64;   // TTL
    out[9] = 17;   // UDP
    out[12..16].copy_from_slice(dst_ip); // reply src = original dst
    out[16..20].copy_from_slice(src_ip); // reply dst = original src

    // UDP header (swap ports)
    out[20..22].copy_from_slice(dst_port); // reply src port = DNS (53)
    out[22..24].copy_from_slice(src_port); // reply dst port = original src port
    out[24..26].copy_from_slice(&udp_len.to_be_bytes());
    out[28..].copy_from_slice(dns_resp);

    // Checksums via gravital-stack rewrite helpers (reuse RFC 1071 logic inline)
    fix_ip4_checksum(&mut out);
    fix_udp_checksum(&mut out);
    Some(out)
}

fn fix_ip4_checksum(pkt: &mut [u8]) {
    pkt[10] = 0; pkt[11] = 0;
    let c = rfc1071(&pkt[..20]);
    pkt[10..12].copy_from_slice(&c.to_be_bytes());
}

fn fix_udp_checksum(pkt: &mut [u8]) {
    let ihl = (pkt[0] & 0x0F) as usize * 4;
    let udp_len = pkt.len() - ihl;
    pkt[ihl + 6] = 0; pkt[ihl + 7] = 0;
    let mut sum = 0u32;
    // Pseudo-header
    for chunk in pkt[12..16].chunks_exact(2) { sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32; }
    for chunk in pkt[16..20].chunks_exact(2) { sum += u16::from_be_bytes([chunk[0], chunk[1]]) as u32; }
    sum += 17u32;
    sum += udp_len as u32;
    // UDP segment
    let seg = &pkt[ihl..];
    let mut chunks = seg.chunks_exact(2);
    for c in &mut chunks { sum += u16::from_be_bytes([c[0], c[1]]) as u32; }
    if let [t] = chunks.remainder() { sum += (*t as u32) << 8; }
    while sum >> 16 != 0 { sum = (sum & 0xFFFF) + (sum >> 16); }
    let csum = !(sum as u16);
    pkt[ihl + 6..ihl + 8].copy_from_slice(&csum.to_be_bytes());
}

fn rfc1071(data: &[u8]) -> u16 {
    let mut sum = 0u32;
    let mut chunks = data.chunks_exact(2);
    for c in &mut chunks { sum += u16::from_be_bytes([c[0], c[1]]) as u32; }
    if let [t] = chunks.remainder() { sum += (*t as u32) << 8; }
    while sum >> 16 != 0 { sum = (sum & 0xFFFF) + (sum >> 16); }
    !(sum as u16)
}
