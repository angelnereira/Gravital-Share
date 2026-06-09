package io.gravital.share.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gravital.share.domain.InternetStatus
import io.gravital.share.domain.NetworkDiscovery
import io.gravital.share.domain.SessionManager
import io.gravital.share.domain.SessionMode
import io.gravital.share.domain.SessionState
import io.gravital.share.service.FileShareServer
import io.gravital.share.service.GravitalServerService
import io.gravital.share.service.GravitalVpnService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val mode: SessionMode          = SessionMode.IDLE,
    val sessionState: SessionState = SessionState.Idle,
    val throughputBps: Long        = 0L,
    val connectedClients: Int      = 0,
    val discovering: Boolean       = false,
    val discoveryError: String?    = null,
    val hotspotRequired: Boolean   = false,
    val internetStatus: InternetStatus? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val sessionManager: SessionManager,
    private val networkDiscovery: NetworkDiscovery,
) : ViewModel() {

    sealed class UiEvent {
        data class RequestVpnPermission(val proxyAddr: String) : UiEvent()
        object OpenHotspotSettings : UiEvent()
    }

    val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)

    private data class Discovery(val discovering: Boolean = false, val error: String? = null)
    private val _discovery = MutableStateFlow(Discovery())
    private val _hotspotRequired = MutableStateFlow(false)
    private var discoveryJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        combine(sessionManager.mode, sessionManager.state) { m, s -> m to s },
        combine(sessionManager.throughput, sessionManager.clientCount) { t, c -> t to c },
        combine(_discovery, _hotspotRequired) { d, h -> d to h },
        sessionManager.internetStatus,
    ) { modeState, throughputClients, discHotspot, internetStatus ->
        val (mode, state) = modeState
        val (throughput, clients) = throughputClients
        val (disc, hotspotReq) = discHotspot
        HomeUiState(
            mode             = mode,
            sessionState     = state,
            throughputBps    = throughput,
            connectedClients = clients,
            discovering      = disc.discovering,
            discoveryError   = disc.error,
            hotspotRequired  = hotspotReq,
            internetStatus   = internetStatus,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    // ── Client: check WiFi first, then auto-discover proxy, then request VPN permission

    fun startClientMode() {
        if (!networkDiscovery.isWifiConnected()) {
            _discovery.value = Discovery(
                error = "No hay WiFi activo.\nConéctate al punto de acceso del servidor antes de iniciar."
            )
            return
        }
        discoveryJob?.cancel()
        discoveryJob = viewModelScope.launch {
            _discovery.value = Discovery(discovering = true)
            val proxy = networkDiscovery.findServer()
            if (proxy == null) {
                _discovery.value = Discovery(
                    error = "No se encontró ningún servidor Gravital en esta red.\n" +
                            "Asegúrate de estar conectado al hotspot del dispositivo que comparte."
                )
                return@launch
            }
            _discovery.value = Discovery()
            events.emit(UiEvent.RequestVpnPermission(proxy.toString()))
        }
    }

    fun cancelDiscovery() {
        discoveryJob?.cancel()
        _discovery.value = Discovery()
    }

    fun dismissDiscoveryError() {
        _discovery.value = Discovery()
    }

    // Called by UI once VPN permission is granted
    fun connectWithProxy(proxyAddr: String) {
        ctx.startForegroundService(
            Intent(ctx, GravitalVpnService::class.java)
                .setAction(GravitalVpnService.ACTION_START)
                .putExtra(GravitalVpnService.EXTRA_PROXY, proxyAddr)
        )
    }

    // ── Server: check hotspot is active before starting ────────────────────────

    fun startServerMode() {
        if (!networkDiscovery.isHotspotActive()) {
            _hotspotRequired.value = true
            return
        }
        ctx.startForegroundService(
            Intent(ctx, GravitalServerService::class.java)
                .setAction(GravitalServerService.ACTION_START)
        )
    }

    fun dismissHotspotDialog() {
        _hotspotRequired.value = false
    }

    fun openHotspotSettings() {
        _hotspotRequired.value = false
        viewModelScope.launch { events.emit(UiEvent.OpenHotspotSettings) }
    }

    // ── QR fallback ───────────────────────────────────────────────────────────

    // Called when the client scans the server's QR code; format "host:port"
    fun connectFromQr(scanned: String) {
        viewModelScope.launch {
            runCatching {
                val lastColon = scanned.lastIndexOf(':')
                scanned.substring(0, lastColon) to scanned.substring(lastColon + 1).toInt()
            }.onSuccess { (host, port) ->
                _discovery.value = Discovery()
                events.emit(UiEvent.RequestVpnPermission("$host:$port"))
            }
        }
    }

    // Returns "ip:1080" for the first non-loopback IPv4 on the server device
    fun getServerQrContent(): String? =
        networkDiscovery.getServerAddresses().firstOrNull()?.let { "$it:1080" }

    // Returns the file-share browser URL for the server device
    fun getFileShareUrl(): String? =
        networkDiscovery.getServerAddresses().firstOrNull()?.let { "http://$it:${FileShareServer.PORT}" }

    // ── Stop current session ───────────────────────────────────────────────────

    fun stop() {
        viewModelScope.launch {
            sessionManager.stop()
            when (uiState.value.mode) {
                SessionMode.SERVER -> ctx.startService(
                    Intent(ctx, GravitalServerService::class.java)
                        .setAction(GravitalServerService.ACTION_STOP)
                )
                SessionMode.CLIENT -> ctx.startService(
                    Intent(ctx, GravitalVpnService::class.java)
                        .setAction(GravitalVpnService.ACTION_STOP)
                )
                SessionMode.IDLE -> { /* nothing to stop */ }
            }
        }
    }

    fun acknowledgeError() = sessionManager.acknowledgeError()
}
