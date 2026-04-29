package io.gravital.share.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gravital.share.domain.AppSettings
import io.gravital.share.domain.SettingsRepository
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val dnsServer: String           = "1.1.1.1",
    val mtu: Int                    = 1280,
    val mcpEndpointEnabled: Boolean = false,
    val isDirty: Boolean            = false,
    val savedEvent: Boolean         = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.first().let { s ->
                _state.value = SettingsUiState(
                    dnsServer          = s.dnsServer,
                    mtu                = s.mtu,
                    mcpEndpointEnabled = s.mcpEndpointEnabled,
                )
            }
        }
    }

    fun onDnsChanged(value: String) =
        _state.update { it.copy(dnsServer = value, isDirty = true, savedEvent = false) }

    fun onMtuChanged(value: Int) =
        _state.update { it.copy(mtu = value.coerceIn(576, 9000), isDirty = true, savedEvent = false) }

    fun onMcpToggled(enabled: Boolean) =
        _state.update { it.copy(mcpEndpointEnabled = enabled, isDirty = true, savedEvent = false) }

    fun save() {
        val s = _state.value
        viewModelScope.launch {
            repository.save(AppSettings(s.dnsServer, s.mtu, s.mcpEndpointEnabled))
            _state.update { it.copy(isDirty = false, savedEvent = true) }
        }
    }

    fun consumeSavedEvent() = _state.update { it.copy(savedEvent = false) }
}
