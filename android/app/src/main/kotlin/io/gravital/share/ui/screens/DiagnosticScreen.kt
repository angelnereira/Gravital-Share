package io.gravital.share.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.gravital.share.domain.NetworkAuditor
import io.gravital.share.ui.theme.GravitalColors
import io.gravital.share.ui.viewmodel.DiagnosticViewModel

private val LEVEL_FILTERS = listOf(null, "debug", "info", "warn", "error")
private val LEVEL_LABELS  = mapOf(
    null    to "TODOS",
    "debug" to "DEBUG",
    "info"  to "INFO",
    "warn"  to "WARN",
    "error" to "ERROR",
)

@Composable
fun DiagnosticScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var levelFilter by remember { mutableStateOf<String?>(null) }
    var netExpanded by remember { mutableStateOf(true) }

    val filtered = remember(state.logLines, levelFilter) {
        if (levelFilter == null) state.logLines
        else state.logLines.filter { extractField(it, "lvl") == levelFilter }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.animateScrollToItem(0)
    }

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
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = "Limpiar logs")
                    }
                    IconButton(onClick = viewModel::exportLogs) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "Exportar .jsonl")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // ── Session metrics ────────────────────────────────────────────────
            MetricsRow(metrics = state.metrics)

            HorizontalDivider()

            // ── Network audit card (collapsible) ───────────────────────────────
            NetworkAuditCard(
                info     = state.networkInfo,
                expanded = netExpanded,
                onToggle = { netExpanded = !netExpanded },
            )

            HorizontalDivider()

            // ── Level filter chips ─────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LEVEL_FILTERS.forEach { lvl ->
                    FilterChip(
                        selected = levelFilter == lvl,
                        onClick  = { levelFilter = lvl },
                        label = {
                            Text(
                                LEVEL_LABELS[lvl] ?: "?",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = levelChipColor(lvl).copy(alpha = 0.20f),
                        ),
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "${filtered.size} eventos",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }

            HorizontalDivider()

            // ── Log stream ─────────────────────────────────────────────────────
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Analytics, null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                        Text(
                            if (levelFilter == null) "Sin eventos aún"
                            else "Sin eventos ${LEVEL_LABELS[levelFilter]}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                        Text(
                            "Los eventos aparecen en tiempo real durante la sesión",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    reverseLayout = true,
                ) {
                    items(filtered) { line ->
                        LogLine(json = line)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.07f),
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }
    }
}

// ── Network audit card ────────────────────────────────────────────────────────

@Composable
fun NetworkAuditCard(
    info: NetworkAuditor.NetworkInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        // Header row — always visible, tap to expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Outlined.NetworkCheck, null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Text(
                "Red",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // Transport badge
            NetBadge(
                text  = info.transport,
                color = when (info.transport) {
                    "WIFI"     -> GravitalColors.StatusGreen
                    "MOBILE"   -> GravitalColors.StatusAmber
                    "ETHERNET" -> GravitalColors.StatusAmber
                    else       -> GravitalColors.StatusRed
                },
            )
            // Internet validation badge
            NetBadge(
                text  = if (info.isInternetValidated) "Internet ✓" else "Sin internet",
                color = if (info.isInternetValidated) GravitalColors.StatusGreen else GravitalColors.StatusRed,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }

        // Expanded details
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // WiFi details
                if (info.wifiSsid != null) {
                    val bars  = "●".repeat(info.wifiSignalBars) + "○".repeat(4 - info.wifiSignalBars)
                    val band  = info.wifiFreqMhz?.let { if (it < 3000) "2.4 GHz" else "5.0 GHz" }
                    NetRow("SSID",   info.wifiSsid)
                    NetRow("Señal",  "${info.wifiRssiDbm ?: "?"} dBm  $bars")
                    if (info.wifiLinkSpeedMbps != null) NetRow("Enlace", "${info.wifiLinkSpeedMbps} Mbps")
                    if (band != null)                   NetRow("Banda",  band)
                }

                // Estimated capacity
                if (info.downstreamKbps > 0) {
                    NetRow("Est. bajada",  "${info.downstreamKbps / 1000}.${(info.downstreamKbps % 1000) / 100} Mbps")
                }
                if (info.upstreamKbps > 0) {
                    NetRow("Est. subida",  "${info.upstreamKbps / 1000}.${(info.upstreamKbps % 1000) / 100} Mbps")
                }

                // Hotspot
                if (info.isHotspotActive) {
                    NetRow("Hotspot", "activo ✓")
                }

                // Proxy latency
                if (info.proxyAddr != null) {
                    val latText = if (info.proxyLatencyMs >= 0)
                        "${info.proxyLatencyMs} ms" else "sin respuesta"
                    NetRow("Proxy ${info.proxyAddr}", latText)
                }

                // App traffic
                val txKb = info.appTxBytes / 1024
                val rxKb = info.appRxBytes / 1024
                if (txKb > 0 || rxKb > 0) {
                    NetRow("App ↑ enviado",   formatKb(txKb))
                    NetRow("App ↓ recibido",  formatKb(rxKb))
                }
            }
        }
    }
}

@Composable
private fun NetBadge(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun NetRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.width(110.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
    }
}

private fun formatKb(kb: Long) = when {
    kb > 1_000_000 -> "%.1f GB".format(kb / 1_000_000.0)
    kb > 1_000     -> "%.1f MB".format(kb / 1_000.0)
    else           -> "$kb KB"
}

// ── Session metrics row ───────────────────────────────────────────────────────

@Composable
fun MetricsRow(metrics: Map<String, String>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        metrics.forEach { (key, value) ->
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = key,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}

// ── Log line ─────────────────────────────────────────────────────────────────

@Composable
fun LogLine(json: String) {
    val level  = extractField(json, "lvl")
    val kind   = extractField(json, "kind") ?: json.take(80)
    val rawTs  = extractField(json, "ts") ?: ""
    val module = extractField(json, "module")
    // "2026-05-04T12:34:56.789Z" → "12:34:56.789"
    val ts = rawTs.substringAfter("T").removeSuffix("Z").take(12)
    val payload = extractPayloadPairs(json)
    val color = levelColor(level)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                ts,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.5.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.width(72.dp),
            )
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = color.copy(alpha = 0.18f),
                modifier = Modifier.width(36.dp),
            ) {
                Text(
                    (level ?: "?").uppercase().take(4),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                    color = color,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                )
            }
            Text(
                kind,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = color,
                modifier = Modifier.weight(1f),
            )
        }

        // Module tag — only when it doesn't match the kind prefix
        if (module != null && !kind.startsWith(module.lowercase().take(8))) {
            Row(modifier = Modifier.padding(start = 114.dp)) {
                Text(
                    "↳ $module",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.27f),
                )
            }
        }

        // Payload key=value pairs
        payload.forEach { (k, v) ->
            Row(
                modifier = Modifier.padding(start = 114.dp, top = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "$k=",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.5.sp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                Text(
                    v,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 9.5.sp),
                    color = color.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun extractField(json: String, key: String): String? =
    Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)

private fun extractPayloadPairs(json: String): List<Pair<String, String>> {
    val marker = """"payload":{"""
    val start  = json.indexOf(marker)
    if (start < 0) return emptyList()
    val braceOpen  = json.indexOf('{', start + marker.length - 1)
    val braceClose = json.indexOf('}', braceOpen + 1)
    if (braceOpen < 0 || braceClose < 0) return emptyList()
    val inner = json.substring(braceOpen + 1, braceClose).trim()
    if (inner.isEmpty()) return emptyList()
    return Regex(""""(\w+)"\s*:\s*(?:"([^"]*)"|([\d.]+)|(true|false|null))""")
        .findAll(inner)
        .mapNotNull { m ->
            val key   = m.groupValues[1]
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
                .ifEmpty { m.groupValues[4] }
            if (value.isEmpty() || value == "null") null else key to value
        }
        .toList()
}

@Composable
private fun levelColor(level: String?) = when (level) {
    "error" -> MaterialTheme.colorScheme.error
    "warn"  -> MaterialTheme.colorScheme.tertiary
    "info"  -> MaterialTheme.colorScheme.onSurface
    "debug" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    else    -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
}

@Composable
private fun levelChipColor(level: String?): Color = when (level) {
    "error" -> MaterialTheme.colorScheme.error
    "warn"  -> MaterialTheme.colorScheme.tertiary
    "info"  -> MaterialTheme.colorScheme.primary
    "debug" -> MaterialTheme.colorScheme.onSurface
    else    -> MaterialTheme.colorScheme.primary
}
