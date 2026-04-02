package com.example.mydevice.ui.kiosk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.data.repository.KioskRepository
import com.example.mydevice.data.repository.MessageRepository
import com.example.mydevice.service.device.DevicePolicyHelper
import com.example.mydevice.service.worker.ScriptSyncWorker
import com.example.mydevice.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KioskApp(
    val id: Int = 0,
    val packageName: String,
    val appName: String,
    val label: String? = null,
    val iconUrl: String? = null,
    val autoLaunch: Boolean = false,
    val visible: Boolean = true
)

data class KioskUiState(
    val apps: List<KioskApp> = emptyList(),
    val isLoading: Boolean = true,
    val unreadMessageCount: Int = 0,
    val error: String? = null,
    val companyName: String = "",
    val isDeviceOwner: Boolean = false,
    val kioskActive: Boolean = false,
    /** Refreshed periodically so dashboard shows “Installed” after silent install */
    val installedPackageNames: Set<String> = emptySet(),
    val installSnapshotVersion: Int = 0
)

class KioskViewModel(
    private val kioskRepo: KioskRepository,
    private val messageRepo: MessageRepository,
    private val appPrefs: AppPreferences,
    private val dpmHelper: DevicePolicyHelper,
    private val deviceRepo: DeviceRepository,
    private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(KioskUiState())
    val uiState: StateFlow<KioskUiState> = _uiState.asStateFlow()

    private var autoLaunchHandled = false
    private var kioskBackgroundJob: Job? = null
    private var packageReceiver: BroadcastReceiver? = null

    init {
        _uiState.value = _uiState.value.copy(isDeviceOwner = dpmHelper.isDeviceOwner())
        loadKioskApps()
        observeUnreadMessages()
        observeCompanyName()
    }

    /**
     * Call when Kiosk screen is shown: keeps script/APK sync and install status updates
     * running for the whole time the user stays in kiosk (not only on first open).
     */
    fun startKioskBackgroundTasks() {
        stopKioskBackgroundTasks()
        registerPackageInstallReceiver()
        kioskBackgroundJob = viewModelScope.launch {
            coroutineScope {
                launch {
                    while (isActive) {
                        delay(Constants.KIOSK_SCRIPT_SYNC_INTERVAL_MS)
                        try {
                            ScriptSyncWorker.enqueueOnce(appContext)
                        } catch (e: Exception) {
                            Log.w(TAG, "Script sync enqueue failed", e)
                        }
                    }
                }
                launch {
                    while (isActive) {
                        delay(Constants.KIOSK_INSTALL_STATUS_POLL_MS)
                        refreshInstallStatus()
                    }
                }
            }
        }
        try {
            ScriptSyncWorker.enqueueOnce(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Initial script sync enqueue failed", e)
        }
        refreshInstallStatus()
    }

    fun stopKioskBackgroundTasks() {
        kioskBackgroundJob?.cancel()
        kioskBackgroundJob = null
        unregisterPackageInstallReceiver()
    }

    private fun registerPackageInstallReceiver() {
        if (packageReceiver != null) return
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshInstallStatus()
                syncInstalledAppsTelemetry()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(
                    packageReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(packageReceiver, filter)
            }
        } catch (e: Exception) {
            Log.w(TAG, "registerReceiver failed", e)
            packageReceiver = null
        }
    }

    private fun unregisterPackageInstallReceiver() {
        try {
            packageReceiver?.let { appContext.unregisterReceiver(it) }
        } catch (_: Exception) {}
        packageReceiver = null
    }

    private fun refreshInstallStatus() {
        val apps = _uiState.value.apps
        if (apps.isEmpty()) return
        val installed = apps.mapNotNull { app ->
            if (isAppInstalled(appContext, app.packageName)) app.packageName else null
        }.toSet()
        val prev = _uiState.value.installedPackageNames
        if (installed == prev) return
        _uiState.value = _uiState.value.copy(
            installedPackageNames = installed,
            installSnapshotVersion = _uiState.value.installSnapshotVersion + 1
        )
        if (installed.size > prev.size) {
            syncInstalledAppsTelemetry()
        }
    }

    private fun syncInstalledAppsTelemetry() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = deviceRepo.updateDeviceDetails()
                Log.i(TAG, "Device telemetry sync after install status change: $result")
            } catch (e: Exception) {
                Log.w(TAG, "Device telemetry sync failed", e)
            }
        }
    }

    private fun loadKioskApps() {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            }
            when (val result = kioskRepo.refreshKioskApps()) {
                is NetworkResult.Success -> {
                    val apps = result.data
                        .filter { it.packageName.isNotBlank() }
                        .map { dto ->
                            KioskApp(
                                id = dto.id,
                                packageName = dto.packageName,
                                appName = dto.title ?: dto.label ?: dto.packageName,
                                label = dto.label,
                                iconUrl = kioskRepo.getIconUrl(dto.icon),
                                autoLaunch = dto.autoLaunch,
                                visible = dto.visible
                            )
                        }
                    Log.i(TAG, "Loaded ${apps.size} kiosk apps from API")
                    apps.forEach { a ->
                        Log.d(TAG, "  app: pkg=${a.packageName}, name=${a.appName}, iconUrl=${a.iconUrl}")
                    }
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            apps = apps,
                            isLoading = false,
                            error = if (apps.isEmpty()) null else null
                        )
                    }
                    enforceKiosk(apps.map { it.packageName })
                    handleAutoLaunch(apps)
                    refreshInstallStatus()
                    // Push full app inventory whenever approved kiosk list is loaded
                    syncInstalledAppsTelemetry()
                }
                is NetworkResult.Error -> {
                    Log.w(TAG, "Kiosk apps fetch error: ${result.message}")
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            apps = emptyList(),
                            isLoading = false,
                            error = "Failed to load apps: ${result.message}"
                        )
                    }
                }
                is NetworkResult.NoInternet -> {
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            apps = emptyList(),
                            isLoading = false,
                            error = "No internet connection"
                        )
                    }
                }
                else -> {
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            apps = emptyList(),
                            isLoading = false
                        )
                    }
                }
            }
        }
    }

    private fun handleAutoLaunch(apps: List<KioskApp>) {
        if (autoLaunchHandled) return
        autoLaunchHandled = true

        val autoLaunchApp = apps.firstOrNull { it.autoLaunch && it.packageName.isNotBlank() }
        if (autoLaunchApp != null && isAppInstalled(appContext, autoLaunchApp.packageName)) {
            Log.i(TAG, "Auto-launching: ${autoLaunchApp.packageName}")
            launchApp(appContext, autoLaunchApp.packageName)
        }
    }

    private fun enforceKiosk(whitelistedPackages: List<String>) {
        viewModelScope.launch(Dispatchers.Default) {
            dpmHelper.startKioskService(whitelistedPackages)
            if (dpmHelper.isDeviceOwner()) {
                dpmHelper.hideNonWhitelistedApps(whitelistedPackages.toSet())
                dpmHelper.applyKioskRestrictions()
            }
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(kioskActive = true)
            }
        }
    }

    fun activateFullKiosk(activity: Activity) {
        val packages = _uiState.value.apps.map { it.packageName }
        dpmHelper.activateFullKiosk(activity, packages)
        _uiState.value = _uiState.value.copy(kioskActive = true)
    }

    fun deactivateKiosk(activity: Activity) {
        dpmHelper.deactivateKiosk(activity)
        _uiState.value = _uiState.value.copy(kioskActive = false)
    }

    private fun observeUnreadMessages() {
        viewModelScope.launch {
            messageRepo.getUnreadCount().collect { count ->
                _uiState.value = _uiState.value.copy(unreadMessageCount = count)
            }
        }
    }

    private fun observeCompanyName() {
        viewModelScope.launch {
            appPrefs.companyName.collect { name ->
                _uiState.value = _uiState.value.copy(companyName = name)
            }
        }
    }

    fun launchApp(context: Context, packageName: String) {
        if (!kioskRepo.isAppAllowed(packageName)) {
            Log.w(TAG, "Blocked launch of non-approved app: $packageName")
            return
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            Log.w(TAG, "No launch intent for: $packageName")
        }
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun refresh() = loadKioskApps()

    override fun onCleared() {
        stopKioskBackgroundTasks()
        super.onCleared()
    }

    companion object {
        private const val TAG = "KioskViewModel"
    }
}
