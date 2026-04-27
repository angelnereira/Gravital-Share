package io.gravital.share.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gravital.share.domain.SessionManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticUiState(
    val logLines: List<String> = emptyList(),
    val metrics: Map<String, String> = emptyMap(),
)

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    private val sessionManager: SessionManager,
) : ViewModel() {

    private val _logLines = MutableStateFlow<List<String>>(emptyList())

    val uiState: StateFlow<DiagnosticUiState> = _logLines.map { lines ->
        DiagnosticUiState(logLines = lines, metrics = buildMetrics())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DiagnosticUiState())

    init {
        viewModelScope.launch {
            sessionManager.engineEvents.collect { event ->
                _logLines.update { (it + event).takeLast(500) }
            }
        }
    }

    fun clearLogs() { _logLines.value = emptyList() }

    fun exportLogs() {
        // TODO: share as text file via ACTION_SEND
    }

    private fun buildMetrics(): Map<String, String> = mapOf(
        "Estado" to sessionManager.state.value.javaClass.simpleName,
        "Modo"   to sessionManager.mode.value.name,
    )
}
