use std::os::unix::io::RawFd;
use std::sync::Arc;
use once_cell::sync::OnceCell;
use parking_lot::Mutex;
use tokio::runtime::Runtime;
use tokio::sync::watch;
use tracing::info;

use crate::config::{EngineConfig, ClientConfig, ServerConfig};
use crate::error::{EngineError, ffi_code};
use crate::metrics::EngineMetrics;
use crate::session::{Session, SessionEvent, SessionMode, PeerInfo};

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
        let metrics = self.metrics.clone();
        let mut shutdown_rx = self.shutdown_tx.subscribe();

        let socks_cfg = gravital_socks::SocksServer::new(gravital_socks::server::ServerConfig {
            bind_addr: cfg.socks_bind,
            auth_enabled: false,
            handshake_timeout_secs: 30,
        });
        let http_cfg = gravital_http::HttpProxyServer::new(gravital_http::HttpProxyConfig {
            bind_addr: cfg.http_bind,
            auth_enabled: false,
        });

        self.session.dispatch(SessionEvent::EngineReady)?;

        self.runtime.spawn(async move {
            let peer = PeerInfo { proxy_addr: cfg.socks_bind };
            let _ = session.dispatch(SessionEvent::ProxyConnected(peer));

            info!(kind = "engine.server.running");

            let mut sr = shutdown_rx.clone();
            let mut hr = shutdown_rx.clone();

            let _ = tokio::try_join!(
                socks_cfg.run(sr),
                http_cfg.run(hr),
            );

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

            // Open TUN device from the fd passed by VpnService
            let tun = match gravital_tun::TunDevice::from_fd(tun_fd, cfg.mtu) {
                Ok(t) => t,
                Err(e) => {
                    tracing::error!(kind = "engine.client.tun_open_failed", error = %e);
                    let _ = session.dispatch(SessionEvent::FatalError(
                        crate::session::FailureKind::TunOpenFailed
                    ));
                    return;
                }
            };

            let (tun_reader, tun_writer) = tun.split();
            let stack = match gravital_stack::UserspaceStack::new(cfg.mtu) {
                Ok(s) => Arc::new(s),
                Err(e) => {
                    tracing::error!(kind = "engine.client.stack_init_failed", error = %e);
                    let _ = session.dispatch(SessionEvent::FatalError(
                        crate::session::FailureKind::TunOpenFailed
                    ));
                    return;
                }
            };

            let socks_client = Arc::new(gravital_socks::SocksClient::new(
                gravital_socks::client::ClientConfig {
                    proxy_addr: cfg.proxy_addr,
                    max_backoff_ms: 30_000,
                }
            ));

            let dns_interceptor = Arc::new(gravital_dns::DnsInterceptor::new(
                gravital_dns::DnsResolver::new(cfg.dns_server)
            ));

            let peer = PeerInfo { proxy_addr: cfg.proxy_addr };
            let _ = session.dispatch(SessionEvent::ProxyConnected(peer));
            info!(kind = "engine.client.connected");

            // Main packet loop — read from TUN, dispatch
            let stack_rx = Arc::clone(&stack);
            let metrics_rx = Arc::clone(&metrics);

            tokio::select! {
                _ = run_tun_loop(tun_reader, stack_rx, socks_client, dns_interceptor, metrics_rx) => {}
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

/// Main TUN read loop — reads IP packets, classifies, relays.
async fn run_tun_loop(
    reader: gravital_tun::TunReader,
    stack: Arc<gravital_stack::UserspaceStack>,
    socks: Arc<gravital_socks::SocksClient>,
    dns: Arc<gravital_dns::DnsInterceptor>,
    metrics: Arc<EngineMetrics>,
) {
    use gravital_proto::{IpView, IpProtocol};
    use std::sync::atomic::Ordering;

    loop {
        let pkt = match reader.read_packet().await {
            Ok(p) => p,
            Err(e) => {
                tracing::error!(kind = "engine.tun_read_error", error = %e);
                break;
            }
        };

        metrics.packets_in.fetch_add(1, Ordering::Relaxed);
        metrics.bytes_in.fetch_add(pkt.len() as u64, Ordering::Relaxed);

        match IpView::parse(&pkt) {
            Ok(ip) => {
                match ip.protocol() {
                    IpProtocol::Udp => {
                        // DNS interception handled here in the real implementation
                        stack.feed_inbound(pkt);
                    }
                    IpProtocol::Tcp => {
                        stack.feed_inbound(pkt);
                    }
                    _ => {
                        metrics.drops_unsupported_proto_inc();
                    }
                }
            }
            Err(e) => {
                metrics.drops_parse_error.fetch_add(1, Ordering::Relaxed);
                tracing::debug!(kind = "engine.parse_error", error = %e);
            }
        }
    }
}

// Helper trait to avoid having a method on AtomicU64 we don't have
trait MetricExt {
    fn drops_unsupported_proto_inc(&self);
}

impl MetricExt for Arc<EngineMetrics> {
    fn drops_unsupported_proto_inc(&self) {
        self.drops_parse_error.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    }
}
