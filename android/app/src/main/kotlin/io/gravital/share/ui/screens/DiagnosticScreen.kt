package io.gravital.share.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
                title = { Text("Diagnóstico", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Atrás")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::clearLogs) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Limpiar")
                    }
                    IconButton(onClick = viewModel::exportLogs) {
                        Icon(Icons.Outlined.Share, contentDescription = "Exportar")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Metrics row
            MetricsRow(metrics = state.metrics)

            HorizontalDivider()

            // Empty state
            if (state.logLines.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Analytics, null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                        Text(
                            "Sin eventos del motor",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Text(
                            "Los eventos aparecerán aquí durante una sesión activa",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            } else {
                // Log stream
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    reverseLayout = true
                ) {
                    items(state.logLines) { line ->
                        LogLine(json = line)
                    }
                }
            }
        }
    }
}

@Composable
fun MetricsRow(metrics: Map<String, String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        metrics.forEach { (key, value) ->
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun LogLine(json: String) {
    val level = extractField(json, "lvl")
    val kind  = extractField(json, "kind") ?: json.take(60)
    val ts    = extractField(json, "ts")?.takeLast(8) ?: ""

    val levelColor = when (level) {
        "error" -> MaterialTheme.colorScheme.error
        "warn"  -> MaterialTheme.colorScheme.tertiary
        "info"  -> MaterialTheme.colorScheme.onSurface
        else    -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            ts,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace, fontSize = 10.sp
            ),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
            modifier = Modifier.width(58.dp)
        )
        Surface(
            shape = RoundedCornerShape(3.dp),
            color = levelColor.copy(alpha = 0.15f),
            modifier = Modifier.width(34.dp)
        ) {
            Text(
                text = (level ?: "?").uppercase().take(4),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 9.sp
                ),
                color = levelColor,
                modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
        Text(
            kind,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = levelColor,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun extractField(json: String, key: String): String? =
    Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)
