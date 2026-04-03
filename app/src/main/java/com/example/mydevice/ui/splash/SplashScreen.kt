package com.example.mydevice.ui.splash

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.mydevice.MainActivity
import com.example.mydevice.ui.theme.Blue500
import com.example.mydevice.ui.theme.Blue700
import org.koin.androidx.compose.koinViewModel

/**
 * Splash / Device Registration screen.
 *
 * VISUAL FLOW:
 * 1. Shows app icon + "MyDevice" branding with loading spinner
 * 2. If already registered → auto-navigates to kiosk dashboard
 * 3. If not registered → slides in a company ID input card
 * 4. User enters numeric company ID → registers → navigates on success
 *
 * APP ICON: Replace Icons.Default.DevicesOther with:
 *   Image(painter = painterResource(R.mipmap.ic_launcher), ...)
 * Place your icon files in:
 *   app/src/main/res/mipmap-mdpi/ic_launcher.png        (48×48)
 *   app/src/main/res/mipmap-hdpi/ic_launcher.png        (72×72)
 *   app/src/main/res/mipmap-xhdpi/ic_launcher.png       (96×96)
 *   app/src/main/res/mipmap-xxhdpi/ic_launcher.png      (144×144)
 *   app/src/main/res/mipmap-xxxhdpi/ic_launcher.png     (192×192)
 *   app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml  (adaptive icon)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplashScreen(
    onNavigateToKiosk: () -> Unit,
    viewModel: SplashViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var companyId by remember { mutableStateOf("") }

    LaunchedEffect(uiState.showCompanyIdInput, uiState.companyIdPrefill) {
        if (uiState.showCompanyIdInput && uiState.companyIdPrefill.isNotEmpty()) {
            companyId = uiState.companyIdPrefill
        }
    }

    // Navigation logic — unchanged
    LaunchedEffect(uiState.navigateToMain) {
        if (uiState.navigateToMain) {
            (context as? MainActivity)?.refreshSignalRConnection()
            onNavigateToKiosk()
        }
    }

    // Entrance animations
    var started by remember { mutableStateOf(false) }
    val logoScale by animateFloatAsState(
        targetValue = if (started) 1f else 0.55f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "logoScale"
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 500, delayMillis = 150),
        label = "contentAlpha"
    )
    LaunchedEffect(Unit) { started = true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(colors = listOf(Blue700, Blue500))
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 32.dp)
                .alpha(contentAlpha),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            // ── Logo ────────────────────────────────────────────────────────
            // To use your own app icon replace the Icon below with:
            //   Image(painter = painterResource(R.mipmap.ic_launcher), ...)
            Surface(
                modifier = Modifier
                    .scale(logoScale)
                    .size(120.dp),
                shape = RoundedCornerShape(32.dp),
                color = Color.White.copy(alpha = 0.15f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                shadowElevation = 20.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.DevicesOther,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.White
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Branding ────────────────────────────────────────────────────
            Text(
                text = "MyDevice",
                style = MaterialTheme.typography.displayMedium,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Enterprise Device Management",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.75f),
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(52.dp))

            // ── Loading indicator ────────────────────────────────────────────
            AnimatedVisibility(
                visible = uiState.isLoading,
                enter = fadeIn(animationSpec = tween(300))
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.48f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(50)),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.28f)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "Connecting to server...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.68f),
                        letterSpacing = 0.3.sp
                    )
                }
            }

            // ── Registration card ────────────────────────────────────────────
            AnimatedVisibility(
                visible = uiState.showCompanyIdInput,
                enter = fadeIn() + slideInVertically { it / 2 }
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Card icon badge
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.DevicesOther,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Text(
                            text = "Device Not Registered",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Enter your company ID to enroll this device with the MDM server",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )

                        Spacer(Modifier.height(24.dp))

                        OutlinedTextField(
                            value = companyId,
                            onValueChange = { companyId = it.filter(Char::isDigit) },
                            label = { Text("Company ID") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.VpnKey,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isRegistering,
                            shape = RoundedCornerShape(14.dp)
                        )

                        // Error message
                        AnimatedVisibility(visible = uiState.error != null) {
                            uiState.error?.let { error ->
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ErrorOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = error,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        Button(
                            onClick = { viewModel.registerWithCompanyId(companyId) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            enabled = companyId.isNotBlank() && !uiState.isRegistering,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            if (uiState.isRegistering) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Registering...")
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Link,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Register Device",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Version watermark ────────────────────────────────────────────────
        Text(
            text = "v1.0",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
                .alpha(contentAlpha),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.35f),
            letterSpacing = 1.sp
        )
    }
}
