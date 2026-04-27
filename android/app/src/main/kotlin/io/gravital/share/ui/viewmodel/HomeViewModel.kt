package io.gravital.share.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gravital.share.domain.NetworkDiscovery
import io.gravital.share.domain.SessionManager
import io.gravital.share.domain.SessionMode
import io.gravital.share.domain.SessionState
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
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val sessionManager: SessionManager,
    private val networkDiscovery: NetworkDiscovery,
) : ViewModel() {

    sealed class UiEvent {
        data class RequestVpnPermission(val proxyAddr: String) : UiEvent()
    }

    val events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 1)

    private data class Discovery(val discovering: Boolean = false, val error: String? = null)
    private val _discovery = MutableStateFlow(Discovery())
    private var discoveryJob: Job? = null

    val uiState: StateFlow<HomeUiState> = combine(
        combine(sessionManager.mode, sessionManager.state) { m, s -> m to s },
        combine(sessionManager.throughput, sessionManager.clientCount) { t, c -> t to c },
        _discovery,
    ) { (mode, state), (throughput, clients), disc ->
        HomeUiState(
            mode             = mode,
            sessionState     = state,
            throughputBps    = throughput,
            connectedClients = clients,
            discovering      = disc.discovering,
            discoveryError   = disc.error,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    // ── Client: auto-discover proxy on current WiFi, then request VPN permission

    fun startClientMode() {
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

    // ── Server: start proxy on hotspot interface ───────────────────────────────

    fun startServerMode() {
        ctx.startForegroundService(
            Intent(ctx, GravitalServerService::class.java)
                .setAction(GravitalServerService.ACTION_START)
        )
    }

    // ── Stop current session ───────────────────────────────────────────────────

    fun stop() {
        viewModelScope.launch {
            val state = uiState.value.sessionState
            sessionManager.stop()
            if (state is SessionState.Connected && state.mode == SessionMode.SERVER) {
                ctx.startService(
                    Intent(ctx, GravitalServerService::class.java)
                        .setAction(GravitalServerService.ACTION_STOP)
                )
            }
        }
    }

    fun acknowledgeError() = sessionManager.acknowledgeError()
}
