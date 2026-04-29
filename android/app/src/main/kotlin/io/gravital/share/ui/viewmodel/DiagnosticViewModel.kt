package io.gravital.share.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gravital.share.domain.SessionManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiagnosticUiState(
    val logLines: List<String>      = emptyList(),
    val metrics: Map<String, String> = emptyMap(),
)

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
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
        val text = _logLines.value.joinToString("\n").ifEmpty { "(sin eventos)" }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Gravital Share — logs")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(Intent.createChooser(intent, "Exportar logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun buildMetrics(): Map<String, String> = mapOf(
        "Estado" to sessionManager.state.value.javaClass.simpleName,
        "Modo"   to sessionManager.mode.value.name,
        "Eventos" to _logLines.value.size.toString(),
    )
}
