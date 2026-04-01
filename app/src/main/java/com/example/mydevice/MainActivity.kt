package com.example.mydevice

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.signalr.DeviceHubConnection
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.data.repository.MessageRepository
import com.example.mydevice.service.device.DevicePolicyHelper
import com.example.mydevice.service.worker.ConfigFileDownloadWorker
import com.example.mydevice.ui.navigation.AppNavigation
import com.example.mydevice.ui.theme.MyDeviceTheme
import com.example.mydevice.util.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.koin.core.context.GlobalContext
import android.util.Log
import android.widget.Toast

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

    private var hubConnection: DeviceHubConnection? = null
    private var deviceRepo: DeviceRepository? = null
    private var messageRepo: MessageRepository? = null
    private var appPrefs: AppPreferences? = null
    lateinit var dpmHelper: DevicePolicyHelper
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var signalRCollectorsStarted = false
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
            MyDeviceTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    AppNavigation(navController = navController)
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
                    val deviceId = try { deviceRepo?.getDeviceId() ?: "" } catch (_: Exception) { "" }
                    if (deviceId.isNotBlank()) {
                        if (hubConnection?.isConnected() != true) {
                            hubConnection?.connect(deviceId)
                        }
                        if (!signalRCollectorsStarted) {
                            collectSignalRMessages()
                            signalRCollectorsStarted = true
                        }
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
        signalRCollectorsStarted = false
        try { startSignalR() } catch (e: Exception) {
            Log.w(TAG, "refreshSignalRConnection failed", e)
        }
    }

    private fun collectSignalRMessages() {
        scope.launch {
            try {
                val conn = hubConnection ?: return@launch
                val repo = messageRepo ?: return@launch
                conn.messageCommand.collect { msg ->
                    try { repo.saveMessage(msg) } catch (e: Exception) {
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
                conn.rebootCommand.collect {
                    try {
                        if (dpmHelper.isDeviceOwner()) dpmHelper.rebootDevice()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error handling reboot command", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error collecting reboot commands", e)
            }
        }
    }

    // ── Initial Sync ────────────────────────────────────────────────────────

    private fun triggerInitialSync() {
        scope.launch {
            try {
                val isRegistered = try { appPrefs?.isRegistered?.first() ?: false } catch (_: Exception) { false }
                if (isRegistered) {
                    try { deviceRepo?.logEvent(Constants.EventTypes.APP_START, "App started") } catch (_: Exception) {}
                    try { ConfigFileDownloadWorker.enqueueOnce(this@MainActivity) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to trigger initial sync", e)
            }
        }
    }

    // ── Cleanup ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        try { hubConnection?.disconnect() } catch (_: Exception) {}
        scope.cancel()
    }
}
