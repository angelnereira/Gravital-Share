package io.gravital.share.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.gravital.share.ui.viewmodel.DiagnosticViewModel

@Composable
fun DiagnosticScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Diagnóstico") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::clearLogs) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Limpiar logs")
                    }
                    IconButton(onClick = viewModel::exportLogs) {
                        Icon(Icons.Default.Share, contentDescription = "Exportar")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Metrics summary
            MetricsSummary(metrics = state.metrics)

            HorizontalDivider()

            // Log stream
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                reverseLayout = true
            ) {
                items(state.logLines) { line ->
                    LogLine(json = line)
                }
            }
        }
    }
}

@Composable
fun MetricsSummary(metrics: Map<String, String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        metrics.forEach { (key, value) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = value, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun LogLine(json: String) {
    val level = extractField(json, "lvl")
    val kind  = extractField(json, "kind") ?: "event"
    val ts    = extractField(json, "ts")?.takeLast(12) ?: ""

    val color = when (level) {
        "error" -> MaterialTheme.colorScheme.error
        "warn"  -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
        "info"  -> MaterialTheme.colorScheme.onSurface
        else    -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = ts,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
        Text(
            text = (level ?: "?").uppercase().take(4),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
        Text(
            text = kind,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = color,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun extractField(json: String, key: String): String? {
    val pattern = Regex(""""$key"\s*:\s*"([^"]+)"""")
    return pattern.find(json)?.groupValues?.getOrNull(1)
}
