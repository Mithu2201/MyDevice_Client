package com.example.mydevice.ui.kiosk

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun KioskScreen(
    onNavigateToMessages: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCharging: () -> Unit,
    onLogout: () -> Unit,
    viewModel: KioskViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity
    var showExitPinDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (activity != null) {
            viewModel.activateFullKiosk(activity)
        }
    }

    // Exit PIN dialog — shown on long-press of the title bar
    if (showExitPinDialog) {
        ExitPinDialog(
            onDismiss = { showExitPinDialog = false },
            onConfirm = {
                showExitPinDialog = false
                onLogout()
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Long-press the title to open the admin exit dialog
                    Column(
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { showExitPinDialog = true },
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        )
                    ) {
                        Text(
                            text = uiState.companyName.ifEmpty { "MyDevice" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (uiState.kioskActive) "Kiosk Mode — Active" else "Kiosk Mode",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.kioskActive)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                },
                actions = {
                    BadgedBox(
                        badge = {
                            if (uiState.unreadMessageCount > 0) {
                                Badge { Text("${uiState.unreadMessageCount}") }
                            }
                        }
                    ) {
                        IconButton(onClick = onNavigateToMessages) {
                            Icon(Icons.Default.Email, contentDescription = "Messages")
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (uiState.apps.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.AppBlocking,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Apps Available",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Contact your administrator to add apps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { viewModel.refresh() }) {
                        Text("Refresh")
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 140.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.apps) { app ->
                        val isInstalled = viewModel.isAppInstalled(context, app.packageName)
                        val icon = remember(app.packageName) {
                            viewModel.getAppIcon(context, app.packageName)
                        }
                        KioskAppCard(
                            app = app,
                            isInstalled = isInstalled,
                            icon = icon,
                            onClick = { viewModel.launchApp(context, app.packageName) }
                        )
                    }
                }
            }

            if (uiState.error != null) {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.refresh() }) {
                            Text("Retry")
                        }
                    }
                ) {
                    Text(uiState.error!!)
                }
            }
        }
    }
}

/**
 * Admin exit dialog with a 4-digit PIN pad.
 *
 * Triggered by long-pressing the kiosk title bar.
 * UI only — wire real PIN validation in [onConfirm] when ready.
 */
@Composable
private fun ExitPinDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    val pinLength = 4

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AdminPanelSettings,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = "Admin Exit",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Enter the 4-digit PIN to exit kiosk mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(24.dp))

                // PIN dot indicators
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    repeat(pinLength) { index ->
                        Box(
                            modifier = Modifier
                                .size(15.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index < pin.length)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }

                Spacer(Modifier.height(22.dp))

                // Numpad
                val numpadRows = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("⌫", "0", "✓")
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    numpadRows.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            row.forEach { key ->
                                when (key) {
                                    "⌫" -> OutlinedButton(
                                        onClick = {
                                            if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                        },
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Backspace,
                                            contentDescription = "Delete",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    "✓" -> Button(
                                        onClick = {
                                            if (pin.length == pinLength) {
                                                // TODO: validate against stored admin PIN
                                                onConfirm()
                                            }
                                        },
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        shape = RoundedCornerShape(14.dp),
                                        enabled = pin.length == pinLength
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Confirm",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    else -> OutlinedButton(
                                        onClick = {
                                            if (pin.length < pinLength) pin += key
                                        },
                                        modifier = Modifier.weight(1f).height(52.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Text(
                                            text = key,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 18.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KioskAppCard(
    app: KioskApp,
    isInstalled: Boolean,
    icon: Drawable?,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.85f),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isInstalled)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isInstalled) 2.dp else 0.dp
        ),
        enabled = isInstalled
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                val bitmap = remember(icon) {
                    val software = icon.toBitmap(width = 56, height = 56)
                    // Hardware bitmaps live in GPU memory and bypass the deprecated
                    // Bitmap.prepareToDraw() pinning path (warned on Android Q+).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        software.copy(Bitmap.Config.HARDWARE, false)
                            .also { software.recycle() }
                    } else {
                        software
                    }
                }
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = app.appName,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Apps,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (!isInstalled) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Not installed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                )
            }
        }
    }
}
