package io.gravital.share.ui.viewmodel

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gravital.share.domain.NetworkAuditor
import io.gravital.share.domain.SessionManager
import io.gravital.share.telemetry.GravitalLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import javax.inject.Inject

data class DiagnosticUiState(
    val logLines: List<String>                  = emptyList(),
    val metrics: Map<String, String>            = emptyMap(),
    val networkInfo: NetworkAuditor.NetworkInfo  = NetworkAuditor.NetworkInfo(),
)

@HiltViewModel
class DiagnosticViewModel @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val sessionManager: SessionManager,
    private val networkAuditor: NetworkAuditor,
) : ViewModel() {

    // Whether this ViewModel owns the polling lifecycle (started it itself)
    private var ownedPolling = false

    val uiState: StateFlow<DiagnosticUiState> = combine(
        GravitalLog.logBuffer,
        combine(
            sessionManager.state,
            sessionManager.mode,
            sessionManager.internetStatus,
        ) { s, m, i -> Triple(s, m, i) },
        combine(sessionManager.throughput, sessionManager.clientCount) { t, c -> t to c },
        networkAuditor.info,
    ) { lines, stateInfo, tc, netInfo ->
        val (state, mode, internet) = stateInfo
        val (throughput, clients) = tc
        DiagnosticUiState(
            logLines    = lines,
            networkInfo = netInfo,
            metrics     = buildMap {
                put("Modo",    mode.name)
                put("Estado",  state.javaClass.simpleName)
                if (internet != null)  put("Internet", internet.name)
                if (clients > 0)       put("Clientes", clients.toString())
                if (throughput > 0)    put("Tráfico",  formatBps(throughput))
                put("Eventos", lines.size.toString())
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DiagnosticUiState())

    init {
        if (!networkAuditor.isPolling) {
            networkAuditor.startPolling()
            ownedPolling = true
        } else {
            // Session is already running — take an immediate snapshot for fresh display
            viewModelScope.launch(Dispatchers.IO) { networkAuditor.snapshot() }
        }
    }

    override fun onCleared() {
        if (ownedPolling) networkAuditor.stopPolling()
        super.onCleared()
    }

    fun clearLogs() = GravitalLog.clearBuffer()

    fun exportLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val lines   = GravitalLog.logBuffer.value
            val netInfo = networkAuditor.info.value
            val now     = Instant.now()

            val header = buildString {
                appendLine("=== GRAVITAL SHARE DEBUG LOG ===")
                appendLine("Exportado    : $now")
                appendLine("Estado       : ${sessionManager.state.value.javaClass.simpleName}")
                appendLine("Modo         : ${sessionManager.mode.value}")
                appendLine("Internet     : ${sessionManager.internetStatus.value ?: "N/A"}")
                appendLine("Transporte   : ${netInfo.transport}")
                if (netInfo.wifiSsid != null)    appendLine("WiFi SSID    : ${netInfo.wifiSsid}")
                if (netInfo.wifiRssiDbm != null) appendLine("WiFi RSSI    : ${netInfo.wifiRssiDbm} dBm")
                if (netInfo.proxyAddr != null)   appendLine("Proxy        : ${netInfo.proxyAddr}")
                if (netInfo.proxyLatencyMs >= 0) appendLine("Latencia     : ${netInfo.proxyLatencyMs} ms")
                appendLine("Total eventos: ${lines.size}")
                appendLine("================================")
                appendLine()
            }
            val content = header + lines.joinToString("\n")
            val fileName = "gravital-debug-${now.toEpochMilli()}.jsonl"

            try {
                val dir  = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                val file = File(dir, fileName)
                file.writeText(content)

                GravitalLog.info(
                    kind = "diagnostic.export.ok",
                    payload = mapOf("path" to file.absolutePath, "events" to lines.size)
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Log guardado:\n${file.absolutePath}", Toast.LENGTH_LONG).show()
                }

                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Gravital Debug Log — $now")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(
                    Intent.createChooser(intent, "Exportar log")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                GravitalLog.error("diagnostic.export.failed", payload = mapOf("err" to (e.message ?: "?")))
                // Fallback: share raw text (no file needed)
                withContext(Dispatchers.Main) {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, content)
                        putExtra(Intent.EXTRA_SUBJECT, "Gravital Debug Log — $now")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(
                        Intent.createChooser(intent, "Exportar log")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    private fun formatBps(b: Long) = when {
        b > 1_000_000 -> "%.1f MB/s".format(b / 1_000_000.0)
        b > 1_000     -> "%.0f KB/s".format(b / 1_000.0)
        else          -> "$b B/s"
    }
}
