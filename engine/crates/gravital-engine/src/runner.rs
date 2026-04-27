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
use crate::session::{FailureKind, PeerInfo, Session, SessionEvent, SessionMode};

/// FFI contract version — bump on any breaking change.
pub const FFI_VERSION: u32 = 1;

pub struct Engine {
    runtime: Runtime,
    session: Arc<Session>,
    metrics: Arc<EngineMetrics>,
    shutdown_tx: watch::Sender<bool>,
    event_callback: Mutex<Option<Box<dyn Fn(String) + Send + Sync>>>,
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

        Ok(Arc::new(Self {
            runtime,
            session: Arc::new(Session::new()),
            metrics: EngineMetrics::new(),
            shutdown_tx,
            event_callback: Mutex::new(None),
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

        self.runtime.spawn(async move {
            let peer = PeerInfo { proxy_addr: cfg.socks_bind };
            let _ = session.dispatch(SessionEvent::ProxyConnected(peer));
            info!(kind = "engine.server.running");

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
                gravital_dns::DnsResolver::new(cfg.dns_server),
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
        tokio::spawn(async move {
            loop {
                match reader.read_packet().await {
                    Ok(pkt) => {
                        metrics_rx.packets_in.fetch_add(1, Ordering::Relaxed);
                        metrics_rx.bytes_in.fetch_add(pkt.len() as u64, Ordering::Relaxed);

                        // DNS interception: UDP port 53 — handled by DnsInterceptor.
                        // For now feed all TCP to the stack; UDP/DNS handled below.
                        use gravital_proto::{IpProtocol, IpView};
                        match IpView::parse(&pkt) {
                            Ok(ip) => match ip.protocol() {
                                IpProtocol::Tcp => {
                                    stack_rx.feed_inbound(pkt);
                                }
                                IpProtocol::Udp => {
                                    // TODO: route UDP/53 through DnsInterceptor,
                                    // other UDP through udpgw relay.
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
