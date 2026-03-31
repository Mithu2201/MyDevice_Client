package com.example.mydevice.ui.kiosk

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.repository.KioskRepository
import com.example.mydevice.data.repository.MessageRepository
import com.example.mydevice.service.device.DevicePolicyHelper
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class KioskApp(
    val packageName: String,
    val appName: String,
    val iconUrl: String? = null
)

data class KioskUiState(
    val apps: List<KioskApp> = emptyList(),
    val isLoading: Boolean = true,
    val unreadMessageCount: Int = 0,
    val error: String? = null,
    val companyName: String = "",
    val isDeviceOwner: Boolean = false,
    val kioskActive: Boolean = false
)

class KioskViewModel(
    private val kioskRepo: KioskRepository,
    private val messageRepo: MessageRepository,
    private val appPrefs: AppPreferences,
    private val dpmHelper: DevicePolicyHelper,
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

    private fun loadKioskApps() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            when (val result = kioskRepo.refreshKioskApps()) {
                is NetworkResult.Success -> {
                    val serverApps = result.data.filter { it.isActive }.map { dto ->
                        KioskApp(
                            packageName = dto.packageName,
                            appName = dto.appName ?: dto.packageName,
                            iconUrl = dto.iconUrl
                        )
                    }
                    val testApps = getTestApps(appContext)
                    val apps = serverApps.ifEmpty { testApps }
                    _uiState.value = _uiState.value.copy(apps = apps, isLoading = false)
                    enforceKiosk(apps.map { it.packageName })
                }
                is NetworkResult.Error -> {
                    val testApps = getTestApps(appContext)
                    _uiState.value = _uiState.value.copy(
                        apps = testApps,
                        isLoading = false,
                        error = null
                    )
                    enforceKiosk(testApps.map { it.packageName })
                }
                else -> {
                    val testApps = getTestApps(appContext)
                    _uiState.value = _uiState.value.copy(
                        apps = testApps,
                        isLoading = false
                    )
                    enforceKiosk(testApps.map { it.packageName })
                }
            }
        }
    }

    /**
     * Start kiosk enforcement: monitoring service + device-owner policies.
     * Called after app list loads so the whitelist is accurate.
     */
    private fun enforceKiosk(whitelistedPackages: List<String>) {
        dpmHelper.startKioskService(whitelistedPackages)
        if (dpmHelper.isDeviceOwner()) {
            dpmHelper.hideNonWhitelistedApps(whitelistedPackages.toSet())
            dpmHelper.applyKioskRestrictions()
        }
        _uiState.value = _uiState.value.copy(kioskActive = true)
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
