package io.gravital.share.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.gravital.share.domain.SessionState
import io.gravital.share.domain.SessionMode
import io.gravital.share.ui.theme.GravitalColors
import io.gravital.share.ui.viewmodel.HomeViewModel

@Composable
fun HomeScreen(
    onOpenDiagnostic: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Gravital Share",
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    )
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
            verticalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterVertically)
        ) {
            // Status orb
            StatusOrb(state = uiState.sessionState)

            // Status text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = uiState.sessionState.primaryLabel(),
                    style = MaterialTheme.typography.titleLarge,
                    color = uiState.sessionState.orbColor()
                )
                if (uiState.sessionState is SessionState.Connected) {
                    Spacer(Modifier.height(4.dp))
                    ThroughputRow(bytes = uiState.throughputBps)
                }
                if (uiState.sessionState is SessionState.Reconnecting) {
                    val s = uiState.sessionState as SessionState.Reconnecting
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Intento ${s.attempt}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Mode selector (when idle)
            if (uiState.sessionState is SessionState.Idle) {
                ModeSelector(
                    onSelectClient = viewModel::selectClientMode,
                    onSelectServer = viewModel::selectServerMode
                )
            }

            // Main toggle button
            if (uiState.sessionState !is SessionState.Idle) {
                MainToggleButton(
                    state = uiState.sessionState,
                    onClick = viewModel::toggle
                )
            }

            // Error banner
            val failed = uiState.sessionState as? SessionState.Failed
            if (failed != null) {
                ErrorBanner(
                    message = failed.error,
                    recoverable = failed.recoverable,
                    onDismiss = viewModel::acknowledgeError,
                    onRetry = if (failed.recoverable) viewModel::retry else null
                )
            }

            // Server info
            if (uiState.sessionState is SessionState.Connected &&
                uiState.mode == SessionMode.SERVER) {
                ServerInfoCard(clientCount = uiState.connectedClients)
            }
        }
    }
}

@Composable
fun StatusOrb(state: SessionState) {
    val color = state.orbColor()
    val isPulsing = state is SessionState.Preparing || state is SessionState.Reconnecting
    val isSpinning = state is SessionState.Connecting

    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val scale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = if (isPulsing) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(160.dp)
            .scale(scale)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(color = color.copy(alpha = 0.12f), radius = size.minDimension / 2)
            drawCircle(color = color.copy(alpha = 0.25f), radius = size.minDimension / 2.6f)
            drawCircle(color = color, radius = size.minDimension / 4f)
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
        Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(text = text, style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun ModeSelector(onSelectClient: () -> Unit, onSelectServer: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Elige tu rol",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onSelectClient) {
                Icon(Icons.Default.PhoneAndroid, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Conectarme")
            }
            Button(onClick = onSelectServer) {
                Icon(Icons.Default.Hub, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Compartir")
            }
        }
    }
}

@Composable
fun MainToggleButton(state: SessionState, onClick: () -> Unit) {
    val label = if (state is SessionState.Connected || state is SessionState.Reconnecting
        || state is SessionState.Connecting) "Detener" else "Iniciar"
    val isActive = state !is SessionState.Stopping

    Button(
        onClick = onClick,
        enabled = isActive,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (state is SessionState.Connected)
                GravitalColors.StatusGreen else MaterialTheme.colorScheme.primary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
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
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Error, contentDescription = null,
                tint = GravitalColors.StatusRed, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Conexión fallida", fontWeight = FontWeight.SemiBold,
                    color = GravitalColors.StatusRed)
                Text(text = message, style = MaterialTheme.typography.bodyMedium)
            }
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Text("Reintentar") }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar")
            }
        }
    }
}

@Composable
fun ServerInfoCard(clientCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Devices, contentDescription = null,
                modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = "Proxy activo", fontWeight = FontWeight.Medium)
                Text(
                    text = "$clientCount dispositivo${if (clientCount != 1) "s" else ""} conectado${if (clientCount != 1) "s" else ""}",
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
