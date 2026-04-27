package io.gravital.share.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gravital.share.domain.SessionManager
import io.gravital.share.domain.SessionMode
import io.gravital.share.domain.SessionState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val mode: SessionMode          = SessionMode.IDLE,
    val sessionState: SessionState = SessionState.Idle,
    val throughputBps: Long        = 0L,
    val connectedClients: Int      = 0,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionManager: SessionManager,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        sessionManager.mode,
        sessionManager.state,
        sessionManager.throughput,
        sessionManager.clientCount,
    ) { mode, state, throughput, clients ->
        HomeUiState(
            mode           = mode,
            sessionState   = state,
            throughputBps  = throughput,
            connectedClients = clients,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = HomeUiState()
    )

    fun selectClientMode() {
        // Start VpnService in client mode — for now uses default config
        // Full implementation: launch intent to GravitalVpnService
    }

    fun selectServerMode() {
        // Start GravitalServerService
    }

    fun toggle() {
        viewModelScope.launch {
            when (uiState.value.sessionState) {
                is SessionState.Connected,
                is SessionState.Connecting,
                is SessionState.Reconnecting -> sessionManager.stop()
                else -> { /* handled by mode selector */ }
            }
        }
    }

    fun acknowledgeError() {
        sessionManager.acknowledgeError()
    }

    fun retry() {
        // Re-trigger last known mode
    }
}
