package io.gravital.share.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import io.gravital.share.ui.PortraitCaptureActivity
import io.gravital.share.domain.InternetStatus
import io.gravital.share.domain.QrCodeHelper
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
    var pendingProxy by remember { mutableStateOf<String?>(null) }
    var showServerQrDialog   by remember { mutableStateOf(false) }
    var showFileShareDialog  by remember { mutableStateOf(false) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingProxy?.let { viewModel.connectWithProxy(it) }
        }
        pendingProxy = null
    }

    val scanQrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.takeIf { it.isNotBlank() }?.let { viewModel.connectFromQr(it) }
    }

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
                is HomeViewModel.UiEvent.OpenHotspotSettings -> {
                    runCatching {
                        context.startActivity(Intent("android.settings.TETHER_SETTINGS").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }.onFailure {
                        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "Gravital Share",
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.3).sp
                    )
                },
                actions = {
                    IconButton(onClick = onOpenDiagnostic) {
                        Icon(Icons.Outlined.Analytics, contentDescription = "Diagnóstico")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Ajustes")
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            Spacer(Modifier.weight(1f))

            // Status orb
            StatusOrb(
                state          = uiState.sessionState,
                mode           = uiState.mode,
                internetStatus = uiState.internetStatus,
                discovering    = uiState.discovering,
            )

            Spacer(Modifier.height(28.dp))

            // Primary status text — for client Connected, reflect real internet probe result
            val isClientConnected = uiState.sessionState is SessionState.Connected
                && uiState.mode == SessionMode.CLIENT
            Text(
                text = when {
                    uiState.discovering -> "Buscando en la red…"
                    isClientConnected -> when (uiState.internetStatus) {
                        InternetStatus.OK          -> "Conectado"
                        InternetStatus.UNREACHABLE -> "Sin internet"
                        else                       -> "Verificando internet…"
                    }
                    else -> uiState.sessionState.primaryLabel()
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = when {
                    uiState.discovering -> GravitalColors.StatusAmber
                    isClientConnected -> when (uiState.internetStatus) {
                        InternetStatus.OK          -> GravitalColors.StatusGreen
                        InternetStatus.UNREACHABLE -> GravitalColors.StatusRed
                        else                       -> GravitalColors.StatusAmber
                    }
                    else -> uiState.sessionState.orbColor()
                },
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

            // Secondary info row
            when {
                uiState.sessionState is SessionState.Connected -> {
                    val s = uiState.sessionState as SessionState.Connected
                    // For server mode, show the reachable LAN IP instead of 0.0.0.0
                    val displayAddr = if (uiState.mode == SessionMode.SERVER) {
                        remember(uiState.sessionState) { viewModel.getServerQrContent() } ?: s.proxyAddr
                    } else {
                        s.proxyAddr
                    }
                    ConnectedInfoRow(
                        proxyAddr = displayAddr,
                        throughput = uiState.throughputBps
                    )
                }
                uiState.sessionState is SessionState.Reconnecting -> {
                    val s = uiState.sessionState as SessionState.Reconnecting
                    Text(
                        "Intento ${s.attempt}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
                uiState.sessionState is SessionState.Idle && !uiState.discovering -> {
                    Text(
                        "Elige cómo quieres usar esta sesión",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
                else -> Spacer(Modifier.height(20.dp))
            }

            Spacer(Modifier.weight(1f))

            // ── Action area ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    uiState.discovering -> DiscoveringActions(onCancel = viewModel::cancelDiscovery)

                    uiState.sessionState is SessionState.Idle ->
                        ModeCards(
                            onConnect = viewModel::startClientMode,
                            onShare   = viewModel::startServerMode
                        )

                    uiState.sessionState !is SessionState.Stopping ->
                        StopButton(
                            isConnected = uiState.sessionState is SessionState.Connected,
                            onClick = viewModel::stop
                        )
                }
            }

            // Discovery error
            if (uiState.discoveryError != null) {
                DiscoveryErrorCard(
                    message   = uiState.discoveryError!!,
                    onRetry   = viewModel::startClientMode,
                    onDismiss = viewModel::dismissDiscoveryError,
                    onScanQr  = {
                        scanQrLauncher.launch(
                            ScanOptions().apply {
                                setCaptureActivity(PortraitCaptureActivity::class.java)
                                setOrientationLocked(true)
                                setBeepEnabled(false)
                                setPrompt("Apunta al QR del dispositivo que comparte")
                            }
                        )
                    }
                )
                Spacer(Modifier.height(16.dp))
            }

            // Engine error
            val failed = uiState.sessionState as? SessionState.Failed
            if (failed != null) {
                EngineErrorCard(message = failed.error, onDismiss = viewModel::acknowledgeError)
                Spacer(Modifier.height(16.dp))
            }

            // Server info
            if (uiState.sessionState is SessionState.Connected && uiState.mode == SessionMode.SERVER) {
                ServerInfoCard(
                    clientCount     = uiState.connectedClients,
                    onShowQr        = { showServerQrDialog = true },
                    onShowFileShare = { showFileShareDialog = true }
                )
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // QR dialog — proxy connection (for client app)
    if (showServerQrDialog) {
        val qrContent = remember { viewModel.getServerQrContent() }
        if (qrContent != null) {
            QrCodeDialog(
                title   = "Código QR del servidor",
                hint    = "El cliente escanea este código si la detección automática no funciona",
                content = qrContent,
                onDismiss = { showServerQrDialog = false }
            )
        } else {
            showServerQrDialog = false
        }
    }

    // QR dialog — file share URL (for any browser)
    if (showFileShareDialog) {
        val url = remember { viewModel.getFileShareUrl() }
        if (url != null) {
            QrCodeDialog(
                title   = "Compartir archivos",
                hint    = "Abre esta URL en el navegador de cualquier dispositivo conectado",
                content = url,
                onDismiss = { showFileShareDialog = false }
            )
        } else {
            showFileShareDialog = false
        }
    }

    // Hotspot required dialog
    if (uiState.hotspotRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissHotspotDialog,
            icon = { Icon(Icons.Filled.WifiTethering, null) },
            title = { Text("Punto de acceso requerido") },
            text = {
                Text(
                    "Para compartir internet debes activar el punto de acceso (hotspot) " +
                    "de este dispositivo. ¿Quieres ir a los ajustes ahora?"
                )
            },
            confirmButton = {
                Button(onClick = viewModel::openHotspotSettings) { Text("Abrir ajustes") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissHotspotDialog) { Text("Cancelar") }
            }
        )
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
fun ModeCards(onConnect: () -> Unit, onShare: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ModeCard(
            modifier  = Modifier.weight(1f),
            icon      = Icons.Outlined.PhoneAndroid,
            title     = "Conectar",
            subtitle  = "Únete a una VPN compartida",
            primary   = false,
            onClick   = onConnect
        )
        ModeCard(
            modifier  = Modifier.weight(1f),
            icon      = Icons.Outlined.Hub,
            title     = "Compartir",
            subtitle  = "Comparte tu conexión VPN",
            primary   = true,
            onClick   = onShare
        )
    }
}

@Composable
fun ModeCard(
    modifier: Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String,
    primary: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (primary)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    val contentColor = if (primary)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, null, tint = contentColor, modifier = Modifier.size(26.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.72f)
                )
            }
        }
    }
}

@Composable
fun DiscoveringActions(onCancel: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(36.dp),
            strokeWidth = 3.dp,
            color = GravitalColors.StatusAmber
        )
        OutlinedButton(onClick = onCancel) {
            Text("Cancelar")
        }
    }
}

@Composable
fun StopButton(isConnected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = if (isConnected)
                GravitalColors.StatusGreen.copy(alpha = 0.18f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Icon(
            imageVector = if (isConnected) Icons.Filled.Stop else Icons.Filled.Cancel,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (isConnected) GravitalColors.StatusGreen
                   else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (isConnected) "Detener" else "Cancelar",
            style = MaterialTheme.typography.titleMedium,
            color = if (isConnected) GravitalColors.StatusGreen
                    else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ConnectedInfoRow(proxyAddr: String, throughput: Long) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SuggestionChip(
            onClick = {},
            label = {
                Text(
                    proxyAddr,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelMedium
                )
            },
            icon = {
                Icon(
                    Icons.Filled.CheckCircle,
                    null,
                    tint = GravitalColors.StatusGreen,
                    modifier = Modifier.size(14.dp)
                )
            }
        )
        if (throughput > 0) {
            val label = when {
                throughput > 1_000_000 -> "%.1f MB/s".format(throughput / 1_000_000.0)
                throughput > 1_000     -> "%.0f KB/s".format(throughput / 1_000.0)
                else                   -> "$throughput B/s"
            }
            SuggestionChip(
                onClick = {},
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                icon = { Icon(Icons.Filled.SwapVert, null, modifier = Modifier.size(14.dp)) }
            )
        }
    }
}

@Composable
fun DiscoveryErrorCard(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onScanQr: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.WifiOff, null,
                    tint = GravitalColors.StatusAmber,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Servidor no encontrado",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = GravitalColors.StatusAmber
                )
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cerrar") }
                Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Reintentar") }
            }
            FilledTonalButton(
                onClick = onScanQr,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Outlined.QrCodeScanner, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Escanear QR del servidor")
            }
        }
    }
}

@Composable
fun EngineErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, "Cerrar",
                    tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
fun ServerInfoCard(clientCount: Int, onShowQr: () -> Unit, onShowFileShare: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Devices, null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Proxy activo",
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    "$clientCount dispositivo${if (clientCount != 1) "s" else ""} conectado${if (clientCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                )
            }
            // QR to connect a new client
            IconButton(onClick = onShowQr) {
                Icon(
                    Icons.Outlined.QrCode2, "Mostrar QR de conexión",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            // QR / URL for the file sharing browser
            IconButton(onClick = onShowFileShare) {
                Icon(
                    Icons.Outlined.FolderOpen, "Compartir archivos",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
fun StatusOrb(
    state: SessionState,
    mode: SessionMode = SessionMode.IDLE,
    internetStatus: InternetStatus? = null,
    discovering: Boolean = false,
) {
    val isClientConnected = state is SessionState.Connected && mode == SessionMode.CLIENT
    val color = when {
        discovering -> GravitalColors.StatusAmber
        isClientConnected -> when (internetStatus) {
            InternetStatus.OK          -> GravitalColors.StatusGreen
            InternetStatus.UNREACHABLE -> GravitalColors.StatusRed
            else                       -> GravitalColors.StatusAmber
        }
        else -> state.orbColor()
    }
    val isPulsing = discovering || state is SessionState.Preparing
        || state is SessionState.Reconnecting || state is SessionState.Connecting
        || (isClientConnected && internetStatus != InternetStatus.OK && internetStatus != InternetStatus.UNREACHABLE)

    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue  = if (isPulsing) 1.07f else 1f,
        animationSpec = infiniteRepeatable(
            tween(950, easing = FastOutSlowInEasing), RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(148.dp).scale(scale)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(color.copy(alpha = 0.08f), size.minDimension / 2f)
            drawCircle(color.copy(alpha = 0.16f), size.minDimension / 2.7f)
            drawCircle(color, size.minDimension / 4.2f)
        }
    }
}

@Composable
fun QrCodeDialog(
    content: String,
    onDismiss: () -> Unit,
    title: String = "Código QR del servidor",
    hint: String = "El cliente escanea este código si la detección automática no funciona",
) {
    val bitmap = remember(content) { QrCodeHelper.generateQrBitmap(content, 512) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, fontWeight = FontWeight.SemiBold)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                Text(
                    content,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}

// ── State extension helpers ───────────────────────────────────────────────────

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
