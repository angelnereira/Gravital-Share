package io.gravital.share.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val dnsServer: String           = "1.1.1.1",
    val mtu: Int                    = 1280,
    val mcpEndpointEnabled: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _state.asStateFlow()

    fun onDnsChanged(value: String) {
        _state.update { it.copy(dnsServer = value) }
    }

    fun onMtuChanged(value: Int) {
        _state.update { it.copy(mtu = value.coerceIn(576, 9000)) }
    }

    fun onMcpToggled(enabled: Boolean) {
        _state.update { it.copy(mcpEndpointEnabled = enabled) }
    }

    fun save() {
        // TODO: persist to DataStore
    }
}
