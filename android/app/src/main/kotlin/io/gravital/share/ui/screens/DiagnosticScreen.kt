package io.gravital.share.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.gravital.share.ui.viewmodel.DiagnosticViewModel

// Level filter options: null = show all
private val LEVEL_FILTERS = listOf(null, "debug", "info", "warn", "error")
private val LEVEL_LABELS  = mapOf(null to "TODOS", "debug" to "DEBUG", "info" to "INFO",
    "warn" to "WARN", "error" to "ERROR")

@Composable
fun DiagnosticScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var levelFilter by remember { mutableStateOf<String?>(null) }

    val filtered = remember(state.logLines, levelFilter) {
        if (levelFilter == null) state.logLines
        else state.logLines.filter { extractField(it, "lvl") == levelFilter }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to the newest entry (top, because reverseLayout = true)
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
            // ── Live metrics row ───────────────────────────────────────────────
            MetricsRow(metrics = state.metrics)

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
                            selectedContainerColor = levelChipColor(lvl)
                                .copy(alpha = 0.22f),
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
                            if (levelFilter == null) "Sin eventos aún"
                            else "Sin eventos de nivel ${levelFilter!!.uppercase()}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                        Text(
                            "Los eventos aparecerán aquí durante la sesión",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            } else {
                LazyColumn(
                    state  = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    reverseLayout = true,
                ) {
                    items(filtered, key = { it.hashCode().toLong() * 31 + filtered.indexOf(it) }) { line ->
                        LogLine(json = line)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.08f),
                            thickness = 0.5.dp,
                        )
                    }
                }
            }
        }
    }
}

// ── Composables ───────────────────────────────────────────────────────────────

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

@Composable
fun LogLine(json: String) {
    val level   = extractField(json, "lvl")
    val kind    = extractField(json, "kind") ?: json.take(80)
    val rawTs   = extractField(json, "ts") ?: ""
    val module  = extractField(json, "module")
    // Format timestamp: "2026-05-04T12:34:56.789Z" → "12:34:56.789"
    val ts = rawTs.substringAfter("T").removeSuffix("Z").take(12)
    val payload = extractPayloadPairs(json)

    val levelColor = levelColor(level)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        // ── Main row: timestamp | level badge | kind ───────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                ts,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace, fontSize = 9.5.sp
                ),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.width(72.dp),
            )
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = levelColor.copy(alpha = 0.18f),
                modifier = Modifier.width(36.dp),
            ) {
                Text(
                    text = (level ?: "?").uppercase().take(4),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 9.sp
                    ),
                    color = levelColor,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
                )
            }
            Text(
                kind,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = levelColor,
                modifier = Modifier.weight(1f),
            )
        }

        // ── Module tag (if not obvious from kind) ─────────────────────────
        if (module != null && !kind.startsWith(module.lowercase())) {
            Row(modifier = Modifier.padding(start = 114.dp)) {
                Text(
                    "↳ $module",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 9.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                )
            }
        }

        // ── Payload key-value pairs ────────────────────────────────────────
        payload.forEach { (k, v) ->
            Row(
                modifier = Modifier.padding(start = 114.dp, top = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$k=",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 9.5.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                Text(
                    v,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace, fontSize = 9.5.sp
                    ),
                    color = levelColor.copy(alpha = 0.85f),
                )
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun extractField(json: String, key: String): String? =
    Regex(""""$key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.getOrNull(1)

/**
 * Extract flat key-value pairs from the "payload":{...} block.
 * Only handles string and number values (all our payloads are flat).
 */
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
            val key = m.groupValues[1]
            val value = m.groupValues[2].ifEmpty { m.groupValues[3] }
                .ifEmpty { m.groupValues[4] }
            if (value.isEmpty() || value == "null") null
            else key to value
        }
        .toList()
}

@Composable
private fun levelColor(level: String?) = when (level) {
    "error" -> MaterialTheme.colorScheme.error
    "warn"  -> MaterialTheme.colorScheme.tertiary
    "info"  -> MaterialTheme.colorScheme.onSurface
    "debug" -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    else    -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
}

@Composable
private fun levelChipColor(level: String?) = when (level) {
    "error" -> MaterialTheme.colorScheme.error
    "warn"  -> MaterialTheme.colorScheme.tertiary
    "info"  -> MaterialTheme.colorScheme.primary
    "debug" -> MaterialTheme.colorScheme.onSurface
    else    -> MaterialTheme.colorScheme.primary
}
