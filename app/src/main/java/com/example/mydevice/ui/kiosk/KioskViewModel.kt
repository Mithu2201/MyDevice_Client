package com.example.mydevice.ui.kiosk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydevice.MainActivity
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.data.repository.KioskRepository
import com.example.mydevice.data.repository.MessageRepository
import com.example.mydevice.service.device.DevicePolicyHelper
import com.example.mydevice.service.worker.ScriptSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class KioskApp(
    val id: Int = 0,
    val packageName: String,
    val appName: String,
    val iconUrl: String? = null,
    val label: String? = null,
    val autoLaunch: Boolean = false
)

data class KioskUiState(
    val apps: List<KioskApp> = emptyList(),
    val isLoading: Boolean = true,
    val isSyncing: Boolean = false,
    val unreadMessageCount: Int = 0,
    val error: String? = null,
    val companyName: String = "",
    val isDeviceOwner: Boolean = false,
    val kioskActive: Boolean = false,
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

    init {
        _uiState.value = _uiState.value.copy(isDeviceOwner = dpmHelper.isDeviceOwner())
        loadKioskApps()
        observeUnreadMessages()
        observeCompanyName()
    }

    fun startKioskBackgroundTasks() {
        // Reserved for periodic install-state polling; grid uses installSnapshotVersion after refresh.
    }

    fun stopKioskBackgroundTasks() {}

    fun performFullSync(mainActivity: MainActivity?) {
        viewModelScope.launch {
            if (_uiState.value.isSyncing) return@launch
            _uiState.update { it.copy(isSyncing = true) }
            try {
                withContext(Dispatchers.IO) {
                    ScriptSyncWorker.enqueueOnce(appContext)
                    val deviceId = appPrefs.deviceId.first()
                    if (deviceId.isNotBlank()) {
                        messageRepo.syncMessages(deviceId)
                    }
                    deviceRepo.updateDeviceDetails()
                }
                mainActivity?.onKioskToolbarSync()
                withContext(Dispatchers.IO) {
                    loadKioskAppsBody()
                }
            } catch (e: Exception) {
                Log.w(TAG, "performFullSync failed", e)
            } finally {
                _uiState.update { it.copy(isSyncing = false) }
            }
        }
    }

    private fun loadKioskApps() {
        viewModelScope.launch(Dispatchers.IO) {
            loadKioskAppsBody()
        }
    }

    private suspend fun loadKioskAppsBody() {
            withContext(Dispatchers.Main) {
                _uiState.value = _uiState.value.copy(isLoading = true)
            }
            when (val result = kioskRepo.refreshKioskApps()) {
                is NetworkResult.Success -> {
                    val serverApps = result.data.filter { it.visible }.map { dto ->
                        KioskApp(
                            id = dto.id,
                            packageName = dto.packageName,
                            appName = dto.label ?: dto.title ?: dto.packageName,
                            iconUrl = kioskRepo.getIconUrl(dto.icon),
                            label = dto.label,
                            autoLaunch = dto.autoLaunch
                        )
                    }
                    val testApps = getTestApps(appContext)
                    val apps = serverApps.ifEmpty { testApps }
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(apps = apps, isLoading = false)
                        refreshInstallSnapshot(apps)
                    }
                    enforceKiosk(apps.map { it.packageName })
                }
                is NetworkResult.Error -> {
                    val testApps = getTestApps(appContext)
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            apps = testApps,
                            isLoading = false,
                            error = null
                        )
                        refreshInstallSnapshot(testApps)
                    }
                    enforceKiosk(testApps.map { it.packageName })
                }
                else -> {
                    val testApps = getTestApps(appContext)
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(
                            apps = testApps,
                            isLoading = false
                        )
                        refreshInstallSnapshot(testApps)
                    }
                    enforceKiosk(testApps.map { it.packageName })
                }
            }
    }

    private fun refreshInstallSnapshot(apps: List<KioskApp>) {
        val installed = apps
            .filter { isAppInstalled(appContext, it.packageName) }
            .map { it.packageName }
            .toSet()
        _uiState.update {
            it.copy(
                installedPackageNames = installed,
                installSnapshotVersion = it.installSnapshotVersion + 1
            )
        }
    }

    /**
     * Start kiosk enforcement: monitoring service + device-owner policies.
     * Heavy DPM binder calls (hideNonWhitelistedApps iterates every installed app)
     * are offloaded to a background dispatcher to prevent main-thread ANR.
     */
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

    /**
     * Activate full kiosk lockdown including lock task mode.
     * Must be called from an Activity context.
     */
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
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
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

    companion object {
        private const val TAG = "KioskViewModel"

        private val CALCULATOR_PACKAGES = listOf(
            "com.vivo.calculator",
            "com.android.bbkcalculator",
            "com.android.calculator2",
            "com.google.android.calculator",
            "com.sec.android.app.popupcalculator"
        )

        private val CALENDAR_PACKAGES = listOf(
            "com.android.calendar",
            "com.bbk.calendar",
            "com.google.android.calendar",
            "com.samsung.android.calendar"
        )

        // TODO: Remove before production release
        fun getTestApps(context: Context): List<KioskApp> {
            val pm = context.packageManager
            val apps = mutableListOf<KioskApp>()

            val calcPkg = CALCULATOR_PACKAGES.firstOrNull { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            }
            if (calcPkg != null) {
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(calcPkg, 0)).toString()
                } catch (_: Exception) { "Calculator" }
                apps.add(KioskApp(packageName = calcPkg, appName = label))
            }

            val calPkg = CALENDAR_PACKAGES.firstOrNull { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (_: Exception) { false }
            }
            if (calPkg != null) {
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(calPkg, 0)).toString()
                } catch (_: Exception) { "Calendar" }
                apps.add(KioskApp(packageName = calPkg, appName = label))
            }

            return apps
        }
    }
}
