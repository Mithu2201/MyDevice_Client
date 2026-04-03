package com.example.mydevice

import android.os.Bundle
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.example.mydevice.data.local.database.entity.IncomingMessageEntity
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.signalr.DeviceHubConnection
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.data.repository.MessageRepository
import com.example.mydevice.service.device.DevicePolicyHelper
import com.example.mydevice.service.device.EmdkRebootHelper
import com.example.mydevice.service.device.EmdkWifiProfileHelper
import com.example.mydevice.service.worker.ConfigFileDownloadWorker
import com.example.mydevice.service.worker.ScriptSyncWorker
import com.example.mydevice.ui.navigation.AppNavigation
import com.example.mydevice.ui.theme.MyDeviceTheme
import com.example.mydevice.util.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import android.util.Log
import android.widget.Toast
import com.microsoft.signalr.HubConnectionState

/**
 * Single Activity — the entire UI is Jetpack Compose with Navigation.
 *
 * KIOSK BEHAVIOUR (Android 8.1 / API 27 compatible):
 * - Prompts for device admin on first launch
 * - Prompts for Usage Access permission (needed to monitor foreground app)
 * - If device owner → lock task mode, app hiding, user restrictions
 * - If device admin only → foreground service monitors and blocks non-whitelisted apps
 * - Home button → returns here (declared as HOME launcher in manifest)
 * - Back button → blocked when in kiosk screen
 * - Immersive sticky mode → hides navigation/status bars
 * - Auto-starts on boot via BootReceiver
 */
class MainActivity : ComponentActivity() {
    private var periodicMessageSyncJob: Job? = null
    private var messageObserverJob: Job? = null
    private var bannerHideJob: Job? = null
    private val announcedMessageIds = linkedSetOf<String>()
    private val _activeBannerNotice = MutableStateFlow<IncomingBannerNotice?>(null)
    private val activeBannerNotice = _activeBannerNotice.asStateFlow()

    private var hubConnection: DeviceHubConnection? = null
    private var deviceRepo: DeviceRepository? = null
    private var messageRepo: MessageRepository? = null
    private var appPrefs: AppPreferences? = null
    lateinit var dpmHelper: DevicePolicyHelper
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var signalRCollectorsStarted = false

    @Volatile
    private var lastRemoteRebootAtMs: Long = 0L

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (dpmHelper.isAdminActive()) {
            Toast.makeText(this, "Device admin activated", Toast.LENGTH_SHORT).show()
            if (dpmHelper.isDeviceOwner()) {
                dpmHelper.setAllowedLockTaskPackages(
                    arrayOf(packageName, "com.android.settings")
                )
            }
        } else {
            Toast.makeText(this, "Device admin not activated — kiosk limited", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_DEVICE_ADMIN = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        dpmHelper = DevicePolicyHelper(this)

        // Block the back button for kiosk mode using the modern dispatcher API.
        // OnBackPressedCallback replaces the deprecated onBackPressed() override
        // and requires no super call.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentionally empty — back is disabled in kiosk mode.
            }
        })

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        resolveDependencies()

        setContent {
            val bannerNotice by activeBannerNotice.collectAsState()
            MyDeviceTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        AppNavigation(navController = navController)
                    }

                    bannerNotice?.let { notice ->
                        IncomingMessageBanner(
                            notice = notice,
                            onClose = { _activeBannerNotice.value = null },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(horizontal = 16.dp, vertical = 20.dp)
                        )
                    }
                }
            }
        }

        ensureDeviceAdmin()
        promptUsageAccessIfNeeded()

        try { startSignalR() } catch (e: Exception) { Log.w(TAG, "startSignalR failed", e) }
        try { triggerInitialSync() } catch (e: Exception) { Log.w(TAG, "triggerInitialSync failed", e) }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        scope.launch {
            try { triggerMessageSync("resume") } catch (_: Exception) {}
            try {
                if (hubConnection?.isConnected() != true) {
                    refreshSignalRConnection()
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Kiosk toolbar sync: reconnect SignalR (fresh token / AddDeviceId) and pull config files.
     * Kiosk app list + scripts + messages are refreshed from [KioskViewModel.performFullSync].
     */
    fun onKioskToolbarSync() {
        Log.i(TAG, "onKioskToolbarSync")
        scope.launch {
            try { ConfigFileDownloadWorker.enqueueOnce(this@MainActivity) } catch (_: Exception) {}
        }
        refreshSignalRConnection()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    // ── Immersive Mode ──────────────────────────────────────────────────────

    private fun enableImmersiveMode() {
        // Tell the framework that our app handles its own insets so the window
        // extends edge-to-edge (required before hiding bars on API 30+).
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            // Hide both the status bar (top) and the navigation bar (bottom).
            hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())

            // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE: if the user swipes from
            // the edge the bars briefly appear then auto-hide again — preventing
            // permanent re-show while still allowing emergency swipe-out.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ── Device Admin / Kiosk Setup ──────────────────────────────────────────

    private fun ensureDeviceAdmin() {
        try {
            if (!dpmHelper.isAdminActive()) {
                deviceAdminLauncher.launch(dpmHelper.createAdminActivationIntent())
            } else if (dpmHelper.isDeviceOwner()) {
                dpmHelper.setAllowedLockTaskPackages(
                    arrayOf(packageName, "com.android.settings")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Device admin check failed", e)
        }
    }

    private fun promptUsageAccessIfNeeded() {
        if (dpmHelper.needsUsageStatsPermission()) {
            Toast.makeText(
                this,
                "Please grant Usage Access for kiosk mode to work",
                Toast.LENGTH_LONG
            ).show()
            dpmHelper.openUsageAccessSettings(this)
        }
    }

    // ── Koin Dependency Resolution ──────────────────────────────────────────

    private fun resolveDependencies() {
        try {
            val koin = GlobalContext.getOrNull() ?: return
            hubConnection = try { koin.get(DeviceHubConnection::class) } catch (_: Exception) { null }
            deviceRepo = try { koin.get(DeviceRepository::class) } catch (_: Exception) { null }
            messageRepo = try { koin.get(MessageRepository::class) } catch (_: Exception) { null }
            appPrefs = try { koin.get(AppPreferences::class) } catch (_: Exception) { null }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving Koin dependencies", e)
        }
    }

    // ── SignalR ─────────────────────────────────────────────────────────────

    private fun startSignalR() {
        scope.launch {
            try {
                val isRegistered = try { appPrefs?.isRegistered?.first() ?: false } catch (_: Exception) { false }
                if (isRegistered) {
                    if (!signalRCollectorsStarted) {
                        collectSignalRMessages()
                        signalRCollectorsStarted = true
                    }
                    ensureMessageObserver()
                    ensurePeriodicMessageSync()

                    try {
                        deviceRepo?.ensureServerDeviceIdFromLookup()
                    } catch (e: Exception) {
                        Log.w(TAG, "ensureServerDeviceIdFromLookup failed", e)
                    }

                    val registrationId = resolveSignalRRegistrationId()
                    if (registrationId.isNotBlank() && hubConnection?.isConnected() != true) {
                        hubConnection?.connect(registrationId)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start SignalR", e)
            }
        }
    }

    /**
     * Reconnect SignalR after auth/registration updates so the hub picks up the
     * latest token if one exists, or falls back to anonymous hub mode.
     */
    fun refreshSignalRConnection() {
        try { hubConnection?.disconnect() } catch (_: Exception) {}
        try { startSignalR() } catch (e: Exception) {
            Log.w(TAG, "refreshSignalRConnection failed", e)
        }
    }

    private suspend fun resolveSignalRRegistrationId(): String {
        val serverDeviceId = try { appPrefs?.serverDeviceId?.first() ?: 0 } catch (_: Exception) { 0 }
        val localDeviceId = try { deviceRepo?.getStableDeviceId() ?: "" } catch (_: Exception) { "" }
        val registrationId = if (serverDeviceId > 0) serverDeviceId.toString() else localDeviceId
        Log.i(
            TAG,
            "SignalR registration ID resolved. serverDeviceId=$serverDeviceId, localDeviceId=$localDeviceId, using=$registrationId " +
                "(must match admin /api/Message/SendRebootCall?deviceId= — if not, remote reboot will never reach this device)"
        )
        return registrationId
    }

    private suspend fun triggerMessageSync(reason: String) {
        val deviceId = try { deviceRepo?.getStableDeviceId() ?: "" } catch (_: Exception) { "" }
        if (deviceId.isBlank()) {
            Log.i(TAG, "Skipping message sync ($reason) because local deviceId is unavailable")
            return
        }
        try {
            val synced = messageRepo?.syncMessages(deviceId)
            Log.i(TAG, "Message sync triggered ($reason) for deviceId=$deviceId result=$synced")
        } catch (e: Exception) {
            Log.w(TAG, "Message sync failed ($reason) for deviceId=$deviceId", e)
        }
    }

    private fun ensureMessageObserver() {
        if (messageObserverJob?.isActive == true) return
        messageObserverJob = scope.launch {
            try {
                val repo = messageRepo ?: return@launch
                var initialized = false
                repo.getAllMessages().collect { messages ->
                    if (!initialized) {
                        announcedMessageIds.clear()
                        announcedMessageIds.addAll(messages.map { it.id })
                        initialized = true
                        return@collect
                    }

                    val newMessages = messages.filter { it.id !in announcedMessageIds }
                    if (newMessages.isNotEmpty()) {
                        announcedMessageIds.addAll(newMessages.map { it.id })
                        val latest = newMessages.first()
                        showIncomingMessageNotice(latest)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error observing stored messages", e)
            }
        }
    }

    private fun ensurePeriodicMessageSync() {
        if (periodicMessageSyncJob?.isActive == true) return
        periodicMessageSyncJob = scope.launch {
            while (isActive) {
                delay(30_000)
                try { triggerMessageSync("periodic_poll") } catch (_: Exception) {}
            }
        }
    }

    private fun collectSignalRMessages() {
        scope.launch {
            try {
                val conn = hubConnection ?: return@launch
                val repo = messageRepo ?: return@launch
                conn.messageCommand.collect { msg ->
                    try {
                        repo.saveMessage(msg)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to save message", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error collecting SignalR messages", e)
            }
        }
        scope.launch {
            try {
                val conn = hubConnection ?: return@launch
                conn.connectionState.collect { state ->
                    if (state == HubConnectionState.CONNECTED) {
                        triggerMessageSync("signalr_connected")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error collecting SignalR connection state", e)
            }
        }
        scope.launch {
            try {
                val conn = hubConnection ?: return@launch
                conn.rebootCommand.collect { delaySeconds ->
                    scope.launch {
                        try {
                            val prefs = appPrefs ?: run {
                                Log.w(TAG, "SignalR Reboot ignored: AppPreferences unavailable")
                                return@launch
                            }
                            val allowed = try {
                                prefs.allowRemoteRebootFromHub.first()
                            } catch (_: Exception) {
                                false
                            }
                            if (!allowed) {
                                Log.w(
                                    TAG,
                                    "SignalR Reboot event ignored — remote reboot disabled (Settings → Allow remote reboot)."
                                )
                                return@launch
                            }
                            val now = System.currentTimeMillis()
                            val cooldownMs = 30L * 60L * 1000L
                            if (now - lastRemoteRebootAtMs < cooldownMs) {
                                Log.w(
                                    TAG,
                                    "SignalR Reboot ignored — ${cooldownMs / 60000} min cooldown since last reboot command"
                                )
                                return@launch
                            }
                            lastRemoteRebootAtMs = now

                            val delayMs = (delaySeconds * 1000.0).toLong().coerceIn(0, 24 * 60 * 60 * 1000L)
                            if (delayMs > 0) {
                                Log.i(TAG, "Reboot: waiting ${delayMs}ms (server delay)")
                                delay(delayMs)
                            }
                            // Device owner: DPM reboot is reliable on managed devices (skip EMDK indirection).
                            if (dpmHelper.isDeviceOwner()) {
                                Log.i(TAG, "Reboot: executing DevicePolicyManager.reboot()")
                                dpmHelper.rebootDevice()
                            } else {
                                EmdkRebootHelper.requestReboot(this@MainActivity, 0.0) { emdkOk ->
                                    if (!emdkOk) {
                                        Log.w(
                                            TAG,
                                            "Reboot: EMDK could not reboot; app is not device owner — no DPM fallback"
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error handling reboot command", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error collecting reboot commands", e)
            }
        }
        scope.launch {
            try {
                val conn = hubConnection ?: return@launch
                conn.wifiProfileCommand.collect { profile ->
                    try {
                        if (profile.active == false) {
                            Log.i(TAG, "WifiProfile skipped (active=false): $profile")
                            return@collect
                        }
                        val essid = profile.resolvedEssid()
                        if (essid.isEmpty()) {
                            Log.w(TAG, "WifiProfile missing essid: $profile")
                            return@collect
                        }
                        Log.i(
                            TAG,
                            "WifiProfile from admin: essid=$essid encryption=${profile.encryption}"
                        )
                        EmdkWifiProfileHelper.applyAddSsid(
                            this@MainActivity,
                            essid,
                            profile.password.orEmpty()
                        ) { ok ->
                            Log.i(TAG, "WifiProfile EMDK apply finished: success=$ok")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "WifiProfile handling failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error collecting WifiProfile commands", e)
            }
        }
        scope.launch {
            try {
                val conn = hubConnection ?: return@launch
                conn.xmlCommand.collect { xml ->
                    Log.i(TAG, "SignalR XML command (${xml.length} chars), triggering data sync")
                    scope.launch {
                        try {
                            triggerMessageSync("signalr_xml")
                            ScriptSyncWorker.enqueueOnce(this@MainActivity)
                            ConfigFileDownloadWorker.enqueueOnce(this@MainActivity)
                        } catch (e: Exception) {
                            Log.w(TAG, "SignalR XML follow-up failed", e)
                        }
                    }
                    try {
                        refreshSignalRConnection()
                    } catch (e: Exception) {
                        Log.w(TAG, "SignalR refresh after XML failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error collecting SignalR XML commands", e)
            }
        }
    }

    // ── Initial Sync ────────────────────────────────────────────────────────

    private fun triggerInitialSync() {
        scope.launch {
            try {
                val isRegistered = try { appPrefs?.isRegistered?.first() ?: false } catch (_: Exception) { false }
                if (isRegistered) {
                    ensureMessageObserver()
                    ensurePeriodicMessageSync()
                    try { deviceRepo?.logEvent(Constants.EventTypes.APP_START, "App started") } catch (_: Exception) {}
                    try { triggerMessageSync("app_start") } catch (_: Exception) {}
                    try { ConfigFileDownloadWorker.enqueueOnce(this@MainActivity) } catch (_: Exception) {}
                    try { ScriptSyncWorker.enqueueOnce(this@MainActivity) } catch (_: Exception) {}
                    try { ScriptSyncWorker.enqueuePeriodic(this@MainActivity) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trigger initial sync", e)
            }
        }
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        bannerHideJob?.cancel()
        messageObserverJob?.cancel()
        periodicMessageSyncJob?.cancel()
        try { hubConnection?.disconnect() } catch (_: Exception) {}
        scope.cancel()
    }

    private fun showIncomingMessageNotice(message: IncomingMessageEntity) {
        val preview = message.description
            .trim()
            .takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: "New message received"
        val sender = message.sendBy.ifBlank { "Admin" }

        scope.launch(Dispatchers.Main) {
            _activeBannerNotice.value = IncomingBannerNotice(
                sender = sender,
                message = preview,
                timestamp = message.receivedAt
            )
            bannerHideJob?.cancel()
            bannerHideJob = scope.launch(Dispatchers.Main) {
                delay(8_000)
                _activeBannerNotice.value = null
            }
        }
    }
}

private data class IncomingBannerNotice(
    val sender: String,
    val message: String,
    val timestamp: String
)

@Composable
private fun IncomingMessageBanner(
    notice: IncomingBannerNotice,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradient = Brush.linearGradient(
        colors = listOf(Color(0xFF1565C0), Color(0xFF0D47A1))
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(gradient)
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MarkEmailUnread,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "New Message",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.3.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = notice.sender,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = formatBannerTimestamp(notice.timestamp),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(14.dp)
            ) {
                Text(
                    text = notice.message,
                    color = Color.White,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatBannerTimestamp(isoString: String): String {
    return try {
        val instant = java.time.Instant.parse(isoString)
        val local = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM dd, HH:mm")
        local.format(formatter)
    } catch (_: Exception) {
        "Now"
    }
}
