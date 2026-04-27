package io.gravital.share.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // DNS server
            SettingsSection(title = "Red") {
                OutlinedTextField(
                    value = state.dnsServer,
                    onValueChange = viewModel::onDnsChanged,
                    label = { Text("Servidor DNS") },
                    placeholder = { Text("1.1.1.1") },
                    leadingIcon = { Icon(Icons.Default.Dns, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.mtu.toString(),
                    onValueChange = { viewModel.onMtuChanged(it.toIntOrNull() ?: 1280) },
                    label = { Text("MTU") },
                    placeholder = { Text("1280") },
                    leadingIcon = { Icon(Icons.Default.Tune, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Telemetry
            SettingsSection(title = "Privacidad") {
                SettingsSwitchRow(
                    title = "Telemetría local (endpoint MCP)",
                    subtitle = "Solo loopback, solo lectura, autenticado",
                    checked = state.mcpEndpointEnabled,
                    onCheckedChange = viewModel::onMcpToggled
                )
            }

            // About
            SettingsSection(title = "Sobre") {
                ListItem(
                    headlineContent = { Text("Versión") },
                    trailingContent = { Text("0.1.0") }
                )
                ListItem(
                    headlineContent = { Text("Licencia") },
                    trailingContent = { Text("Propietaria") }
                )
                ListItem(
                    headlineContent = { Text("Motor Rust") },
                    trailingContent = { Text("FFI v1") }
                )
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Guardar cambios")
            }
        }
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
