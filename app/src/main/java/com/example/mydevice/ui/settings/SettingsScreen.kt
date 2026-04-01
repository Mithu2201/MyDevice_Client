package com.example.mydevice.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mydevice.ui.theme.GreenSuccess
import com.example.mydevice.ui.theme.OrangeWarning
import org.koin.androidx.compose.koinViewModel

/**
 * Settings / Device Info screen.
 *
 * DESIGN:
 * - Device identity section (ID, company, app version)
 * - Configuration flags section (check-in, charging, inactivity)
 * - Sync actions (force telemetry, reload config, sync logs)
 * - Pending log count indicator
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings & Device Info", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Device Identity ─────────────────────────────────────────
            SectionHeader("Device Identity")
            SettingsInfoCard {
                InfoRow(
                    icon = Icons.Default.Fingerprint,
                    label = "Device ID",
                    value = uiState.deviceId,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(uiState.deviceId))
                        Toast.makeText(context, "Device ID copied", Toast.LENGTH_SHORT).show()
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                InfoRow(icon = Icons.Default.Business, label = "Company", value = uiState.companyName)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                InfoRow(icon = Icons.Default.Info, label = "Company ID", value = "${uiState.companyId}")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                InfoRow(icon = Icons.Default.Update, label = "App Version", value = uiState.appVersion)
            }

            // ── Configuration ───────────────────────────────────────────
            SectionHeader("Remote Configuration")
            SettingsInfoCard {
                ConfigRow(
                    icon = Icons.Default.Login,
                    label = "Check-In Screen",
                    enabled = uiState.showCheckIn
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ConfigRow(
                    icon = Icons.Default.BatteryChargingFull,
                    label = "Charging Screen",
                    enabled = uiState.showCharging
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                InfoRow(
                    icon = Icons.Default.Timer,
                    label = "Inactivity Timeout",
                    value = "${uiState.inactivityMinutes} min"
                )
            }

            // ── Sync Status ─────────────────────────────────────────────
            SectionHeader("Sync Status")
            SettingsInfoCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = if (uiState.pendingLogs > 0) OrangeWarning else GreenSuccess,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Pending Event Logs", style = MaterialTheme.typography.bodyMedium)
                    }
                    Badge(
                        containerColor = if (uiState.pendingLogs > 0)
                            OrangeWarning else GreenSuccess
                    ) {
                        Text("${uiState.pendingLogs}")
                    }
                }
            }

            // ── Sync result message ─────────────────────────────────────
            if (uiState.syncResult != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Text(
                        text = uiState.syncResult!!,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // ── Action Buttons ──────────────────────────────────────────
            SectionHeader("Actions")

            ActionButton(
                text = "Force Sync Telemetry",
                icon = Icons.Default.Sync,
                isLoading = uiState.isSyncing,
                onClick = { viewModel.forceSyncTelemetry() }
            )

            ActionButton(
                text = "Reload Remote Config",
                icon = Icons.Default.CloudDownload,
                isLoading = uiState.isSyncing,
                onClick = { viewModel.forceReloadConfig() }
            )

            ActionButton(
                text = "Sync Event Logs",
                icon = Icons.Default.Upload,
                isLoading = uiState.isSyncing,
                onClick = { viewModel.syncEventLogs() }
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp)
    )
}

@Composable
private fun SettingsInfoCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            if (onCopy != null) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigRow(icon: ImageVector, label: String, enabled: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon, contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = if (enabled) GreenSuccess.copy(alpha = 0.15f)
            else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                text = if (enabled) "Enabled" else "Disabled",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) GreenSuccess
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    icon: ImageVector,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        enabled = !isLoading,
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}
