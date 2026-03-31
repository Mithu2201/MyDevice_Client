package com.example.mydevice

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.example.mydevice.service.kiosk.KioskLockService
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

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_DEVICE_ADMIN = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        dpmHelper = DevicePolicyHelper(this)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Block back button in kiosk mode — do nothing
    }

    // ── Immersive Mode ──────────────────────────────────────────────────────

    private fun enableImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    // ── Device Admin / Kiosk Setup ──────────────────────────────────────────

    private fun ensureDeviceAdmin() {
        try {
            if (!dpmHelper.isAdminActive()) {
                dpmHelper.requestAdminActivation(this, REQUEST_CODE_DEVICE_ADMIN)
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_DEVICE_ADMIN) {
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
                        hubConnection?.connect(deviceId)
                        collectSignalRMessages()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start SignalR", e)
            }
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
