package com.example.mydevice.ui.charging

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mydevice.ui.theme.GreenSuccess
import com.example.mydevice.ui.theme.OrangeWarning
import org.koin.androidx.compose.koinViewModel

/**
 * Charging screen — shown fullscreen when charger is plugged in.
 *
 * DESIGN:
 * - Large animated battery icon with percentage
 * - Device info cards: IP address, Wi-Fi signal, device model
 * - Pulsing charging indicator
 * - Auto-dismisses when charger is unplugged
 */
@Composable
fun ChargingScreen(
    onDismiss: () -> Unit,
    viewModel: ChargingViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.startMonitoring(context)
    }

    LaunchedEffect(uiState.shouldDismiss) {
        if (uiState.shouldDismiss) onDismiss()
    }

    // Pulsing animation for charging indicator
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Battery icon with pulsing effect
        Box(contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.size(120.dp),
                shape = CircleShape,
                color = GreenSuccess.copy(alpha = pulseAlpha * 0.2f)
            ) {}
            Surface(
                modifier = Modifier.size(90.dp),
                shape = CircleShape,
                color = GreenSuccess.copy(alpha = 0.3f)
            ) {
                Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    modifier = Modifier.padding(20.dp),
                    tint = GreenSuccess
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "${uiState.batteryLevel}%",
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = GreenSuccess
        )
        Text(
            text = "Charging",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Device info cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoCard(
                icon = Icons.Default.Wifi,
                label = "Wi-Fi",
                value = "${uiState.wifiSignal} dBm",
                modifier = Modifier.weight(1f)
            )
            InfoCard(
                icon = Icons.Default.Language,
                label = "IP Address",
                value = uiState.ipAddress,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        InfoCard(
            icon = Icons.Default.PhoneAndroid,
            label = "Device",
            value = uiState.deviceModel,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
