package com.example.mydevice.ui.checkin

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

/**
 * PIN-based Check-In screen (kiosk login).
 *
 * DESIGN: A numeric keypad with PIN dot indicators.
 * - User taps digits to build PIN
 * - Backspace to delete last digit
 * - Auto-submits after PIN length reaches threshold, or user taps "Login"
 * - Shows loading spinner during auth, error on failure
 * - On success, navigates to Kiosk grid
 */
@Composable
fun CheckInScreen(
    onLoginSuccess: () -> Unit,
    viewModel: CheckInViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(uiState.loginSuccess) {
        if (uiState.loginSuccess) onLoginSuccess()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Lock icon
        Surface(
            modifier = Modifier.size(80.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.padding(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Check In",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Enter your PIN to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(32.dp))

        // PIN dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(6) { index ->
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(
                            if (index < uiState.pin.length) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Error message
        AnimatedVisibility(visible = uiState.error != null) {
            Text(
                text = uiState.error ?: "",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Numeric keypad
        NumericKeypad(
            onDigit = { digit ->
                if (uiState.pin.length < 6) {
                    viewModel.onPinChanged(uiState.pin + digit)
                }
            },
            onBackspace = {
                if (uiState.pin.isNotEmpty()) {
                    viewModel.onPinChanged(uiState.pin.dropLast(1))
                }
            },
            enabled = !uiState.isLoading
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Login button
        Button(
            onClick = { viewModel.login() },
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(50.dp),
            enabled = uiState.pin.isNotBlank() && !uiState.isLoading,
            shape = RoundedCornerShape(12.dp)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text("Login", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // TODO: Remove this skip button before production release
        TextButton(onClick = onLoginSuccess) {
            Text(
                text = "Skip Login (Testing)",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

@Composable
private fun NumericKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    enabled: Boolean
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    )

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        keys.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                row.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(modifier = Modifier.size(72.dp))
                    } else {
                        FilledTonalButton(
                            onClick = {
                                if (key == "⌫") onBackspace()
                                else onDigit(key)
                            },
                            modifier = Modifier.size(72.dp),
                            enabled = enabled,
                            shape = CircleShape
                        ) {
                            if (key == "⌫") {
                                Icon(
                                    imageVector = Icons.Default.Backspace,
                                    contentDescription = "Delete",
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Text(
                                    text = key,
                                    fontSize = 24.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
