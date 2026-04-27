package io.gravital.share.ui.screens

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.gravital.share.domain.SessionMode
import io.gravital.share.domain.SessionState
import io.gravital.share.ui.theme.GravitalColors
import io.gravital.share.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    onOpenDiagnostic: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Pending proxy addr while VPN permission dialog is showing
    var pendingProxy by remember { mutableStateOf<String?>(null) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingProxy?.let { viewModel.connectWithProxy(it) }
        }
        pendingProxy = null
    }

    // Collect one-shot events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeViewModel.UiEvent.RequestVpnPermission -> {
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        pendingProxy = event.proxyAddr
                        vpnLauncher.launch(intent)
                    } else {
                        viewModel.connectWithProxy(event.proxyAddr)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Gravital Share", fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                },
                actions = {
                    IconButton(onClick = onOpenDiagnostic) {
                        Icon(Icons.Default.Analytics, contentDescription = "Diagnóstico")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ajustes")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically)
        ) {
            StatusOrb(state = uiState.sessionState, discovering = uiState.discovering)

            // Status label
            Text(
                text = when {
                    uiState.discovering -> "Buscando en la red…"
                    else -> uiState.sessionState.primaryLabel()
                },
                style = MaterialTheme.typography.titleLarge,
                color = if (uiState.discovering) GravitalColors.StatusAmber
                        else uiState.sessionState.orbColor()
            )

            // Throughput when connected
            if (uiState.sessionState is SessionState.Connected && uiState.throughputBps > 0) {
                ThroughputRow(bytes = uiState.throughputBps)
            }

            // Reconnect attempt counter
            if (uiState.sessionState is SessionState.Reconnecting) {
                val s = uiState.sessionState as SessionState.Reconnecting
                Text(
                    text = "Intento ${s.attempt}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            // ── Action area ────────────────────────────────────────────────────

            when {
                // Discovery in progress
                uiState.discovering -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                           verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                            color = GravitalColors.StatusAmber
                        )
                        TextButton(onClick = viewModel::cancelDiscovery) {
                            Text("Cancelar")
                        }
                    }
                }

                // Idle — choose role
                uiState.sessionState is SessionState.Idle -> {
                    ModeSelector(
                        onConnect = viewModel::startClientMode,
                        onShare   = viewModel::startServerMode
                    )
                }

                // Active session — show stop button
                uiState.sessionState !is SessionState.Stopping -> {
                    Button(
                        onClick = viewModel::stop,
                        enabled = uiState.sessionState !is SessionState.Stopping,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (uiState.sessionState is SessionState.Connected)
                                GravitalColors.StatusGreen else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Text(
                            text = if (uiState.sessionState is SessionState.Connected ||
                                       uiState.sessionState is SessionState.Reconnecting ||
                                       uiState.sessionState is SessionState.Connecting)
                                "Detener" else "Cancelar",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }

            // Discovery error
            if (uiState.discoveryError != null) {
                DiscoveryErrorCard(
                    message = uiState.discoveryError!!,
                    onRetry = viewModel::startClientMode,
                    onDismiss = viewModel::dismissDiscoveryError
                )
            }

            // Engine error
            val failed = uiState.sessionState as? SessionState.Failed
            if (failed != null) {
                ErrorBanner(
                    message = failed.error,
                    recoverable = failed.recoverable,
                    onDismiss = viewModel::acknowledgeError,
                    onRetry = if (failed.recoverable) viewModel::acknowledgeError else null
                )
            }

            // Server info card
            if (uiState.sessionState is SessionState.Connected &&
                uiState.mode == SessionMode.SERVER) {
                ServerInfoCard(clientCount = uiState.connectedClients)
            }
        }
    }
}

// ── Composables ───────────────────────────────────────────────────────────────

@Composable
fun ModeSelector(onConnect: () -> Unit, onShare: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Elige tu rol",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onConnect,
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Conectar")
            }
            Button(
                onClick = onShare,
                modifier = Modifier.weight(1f).height(56.dp)
            ) {
                Icon(Icons.Default.Hub, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Compartir")
            }
        }
    }
}

@Composable
fun StatusOrb(state: SessionState, discovering: Boolean = false) {
    val color = if (discovering) GravitalColors.StatusAmber else state.orbColor()
    val isPulsing = discovering ||
        state is SessionState.Preparing ||
        state is SessionState.Reconnecting ||
        state is SessionState.Connecting

    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(160.dp).scale(scale)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color.copy(alpha = 0.12f), size.minDimension / 2)
            drawCircle(color.copy(alpha = 0.25f), size.minDimension / 2.6f)
            drawCircle(color, size.minDimension / 4f)
        }
    }
}

@Composable
fun ThroughputRow(bytes: Long) {
    val text = when {
        bytes > 1_000_000 -> "%.1f MB/s".format(bytes / 1_000_000.0)
        bytes > 1_000     -> "%.0f KB/s".format(bytes / 1_000.0)
        else              -> "$bytes B/s"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.SwapVert, null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun DiscoveryErrorCard(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.WifiOff, null,
                    tint = GravitalColors.StatusAmber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Servidor no encontrado",
                    fontWeight = FontWeight.SemiBold,
                    color = GravitalColors.StatusAmber
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Cerrar")
                }
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) {
                    Text("Reintentar")
                }
            }
        }
    }
}

@Composable
fun ErrorBanner(
    message: String,
    recoverable: Boolean,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = GravitalColors.StatusRed.copy(alpha = 0.15f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Error, null, tint = GravitalColors.StatusRed, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Conexión fallida", fontWeight = FontWeight.SemiBold, color = GravitalColors.StatusRed)
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Reintentar") }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Cerrar")
            }
        }
    }
}

@Composable
fun ServerInfoCard(clientCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Devices, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Proxy activo", fontWeight = FontWeight.Medium)
                Text(
                    text = "$clientCount dispositivo${if (clientCount != 1) "s" else ""} " +
                           "conectado${if (clientCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ── State helpers ─────────────────────────────────────────────────────────────

fun SessionState.orbColor(): Color = when (this) {
    is SessionState.Idle         -> GravitalColors.StatusGray
    is SessionState.Preparing    -> GravitalColors.StatusAmber
    is SessionState.Connecting   -> GravitalColors.StatusAmber
    is SessionState.Connected    -> GravitalColors.StatusGreen
    is SessionState.Reconnecting -> GravitalColors.StatusOrange
    is SessionState.Stopping     -> GravitalColors.StatusGray
    is SessionState.Failed       -> GravitalColors.StatusRed
}

fun SessionState.primaryLabel(): String = when (this) {
    is SessionState.Idle         -> "Listo para conectar"
    is SessionState.Preparing    -> "Preparando…"
    is SessionState.Connecting   -> "Conectando…"
    is SessionState.Connected    -> "Conectado"
    is SessionState.Reconnecting -> "Reconectando…"
    is SessionState.Stopping     -> "Cerrando…"
    is SessionState.Failed       -> "Error de conexión"
}
