package io.gravital.share.ui.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gravital.share.domain.InternetStatus
import io.gravital.share.domain.SessionManager
import io.gravital.share.domain.SessionMode
import io.gravital.share.telemetry.GravitalLog
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import java.time.Instant

data class DiagnosticUiState(
    val logLines: List<String>       = emptyList(),
    val metrics: Map<String, String> = emptyMap(),
)

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val sessionManager: SessionManager,
) : ViewModel() {

    val uiState: StateFlow<DiagnosticUiState> = combine(
        GravitalLog.logBuffer,
        combine(
            sessionManager.state,
            sessionManager.mode,
            sessionManager.internetStatus,
        ) { s, m, i -> Triple(s, m, i) },
        combine(sessionManager.throughput, sessionManager.clientCount) { t, c -> t to c },
    ) { lines, stateInfo, tc ->
        val (state, mode, internet) = stateInfo
        val (throughput, clients) = tc
        DiagnosticUiState(
            logLines = lines,
            metrics  = buildMap {
                put("Modo",    mode.name)
                put("Estado",  state.javaClass.simpleName)
                if (internet != null)  put("Internet", internet.name)
                if (clients > 0)       put("Clientes", clients.toString())
                if (throughput > 0)    put("Tráfico",  formatBps(throughput))
                put("Eventos", lines.size.toString())
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DiagnosticUiState())

    fun clearLogs() = GravitalLog.clearBuffer()

    fun exportLogs() {
        val lines = GravitalLog.logBuffer.value
        val header = buildString {
            appendLine("=== GRAVITAL SHARE DEBUG LOG ===")
            appendLine("Exportado : ${Instant.now()}")
            appendLine("Estado    : ${sessionManager.state.value.javaClass.simpleName}")
            appendLine("Modo      : ${sessionManager.mode.value}")
            appendLine("Internet  : ${sessionManager.internetStatus.value ?: "N/A"}")
            appendLine("Eventos   : ${lines.size}")
            appendLine("================================")
            appendLine()
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Gravital Share — Debug Log ${Instant.now()}")
            putExtra(Intent.EXTRA_TEXT, header + lines.joinToString("\n"))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(
            Intent.createChooser(intent, "Exportar logs")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun formatBps(b: Long) = when {
        b > 1_000_000 -> "%.1f MB/s".format(b / 1_000_000.0)
        b > 1_000     -> "%.0f KB/s".format(b / 1_000.0)
        else          -> "$b B/s"
    }
}
