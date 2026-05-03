package io.gravital.share.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.gravital.share.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.savedEvent) {
        if (state.savedEvent) {
            snackbarHost.showSnackbar("Ajustes guardados")
            viewModel.consumeSavedEvent()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Atrás")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Button(
                    onClick = viewModel::save,
                    enabled = state.isDirty,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Guardar cambios", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {

            // Red
            SettingsGroup(label = "Red") {
                SettingsTextField(
                    value          = state.dnsServer,
                    onValueChange  = viewModel::onDnsChanged,
                    label          = "DNS primario",
                    placeholder    = "1.1.1.1",
                    icon           = Icons.Outlined.Dns,
                    keyboardType   = KeyboardType.Uri
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                SettingsTextField(
                    value          = state.dnsServerSecondary,
                    onValueChange  = viewModel::onDnsSecondaryChanged,
                    label          = "DNS secundario",
                    placeholder    = "8.8.8.8",
                    icon           = Icons.Outlined.Dns,
                    keyboardType   = KeyboardType.Uri
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                SettingsTextField(
                    value          = state.mtu.toString(),
                    onValueChange  = { viewModel.onMtuChanged(it.toIntOrNull() ?: 1280) },
                    label          = "MTU",
                    placeholder    = "1280",
                    icon           = Icons.Outlined.Tune,
                    keyboardType   = KeyboardType.Number
                )
            }

            // Privacidad
            SettingsGroup(label = "Privacidad") {
                SettingsSwitchRow(
                    icon        = Icons.Outlined.MonitorHeart,
                    title       = "Telemetría local",
                    subtitle    = "Endpoint MCP en loopback, solo lectura",
                    checked     = state.mcpEndpointEnabled,
                    onChanged   = viewModel::onMcpToggled
                )
            }

            // Sobre
            SettingsGroup(label = "Sobre la app") {
                SettingsInfoRow(label = "Versión", value = "0.1.0")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                SettingsInfoRow(label = "Motor", value = "Rust · FFI v1")
                HorizontalDivider(modifier = Modifier.padding(horizontal = 4.dp))
                SettingsInfoRow(label = "Licencia", value = "Propietaria")
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

// ── Section & row composables ─────────────────────────────────────────────────

@Composable
fun SettingsGroup(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(vertical = 4.dp),
                content = content
            )
        }
    }
}

@Composable
fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    keyboardType: KeyboardType
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(20.dp)) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContainerColor   = MaterialTheme.colorScheme.surface,
            unfocusedBorderColor    = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        )
    )
}

@Composable
fun SettingsSwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChanged: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}
