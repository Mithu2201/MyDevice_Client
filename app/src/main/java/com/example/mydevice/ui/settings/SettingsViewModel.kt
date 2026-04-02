package com.example.mydevice.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.data.remote.api.NetworkResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Drives the Settings / Device Info screen.
 *
 * Shows device identity, company info, connection status,
 * battery, and pending event log count.
 * Also provides actions to force-sync telemetry or reload remote config.
 */
data class SettingsUiState(
    val deviceId: String = "",
    val companyName: String = "",
    val companyId: Int = -1,
    val appVersion: String = "1.0.0",
    val pendingLogs: Int = 0,
    val isSyncing: Boolean = false,
    val syncResult: String? = null,
    val showCheckIn: Boolean = true,
    val showCharging: Boolean = true,
    val inactivityMinutes: Int = 5,
    /** When true, SignalR hub "Reboot" events may call DevicePolicyManager.reboot (default off). */
    val allowRemoteRebootFromHub: Boolean = false
)

class SettingsViewModel(
    private val deviceRepo: DeviceRepository,
    private val appPrefs: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            combine(
                appPrefs.deviceId,
                appPrefs.companyName,
                appPrefs.companyId,
                appPrefs.showCheckInView,
                appPrefs.showChargingView,
                appPrefs.inactivityTimeoutMinutes,
                appPrefs.allowRemoteRebootFromHub
            ) { values ->
                _uiState.value.copy(
                    deviceId = values[0] as String,
                    companyName = values[1] as String,
                    companyId = values[2] as Int,
                    showCheckIn = values[3] as Boolean,
                    showCharging = values[4] as Boolean,
                    inactivityMinutes = values[5] as Int,
                    allowRemoteRebootFromHub = values[6] as Boolean
                )
            }.collect { state ->
                val count = deviceRepo.getPendingLogCount()
                _uiState.value = state.copy(pendingLogs = count)
            }
        }
    }

    fun forceSyncTelemetry() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, syncResult = null)
            val result = deviceRepo.updateDeviceDetails()
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                syncResult = when (result) {
                    is NetworkResult.Success -> "Telemetry synced successfully"
                    is NetworkResult.Error -> "Sync failed: ${result.message}"
                    is NetworkResult.NoInternet -> "No internet connection"
                    is NetworkResult.Loading -> null
                }
            )
        }
    }

    fun forceReloadConfig() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, syncResult = null)
            val result = deviceRepo.loadRemoteConfig()
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                syncResult = when (result) {
                    is NetworkResult.Success -> "Configuration reloaded"
                    is NetworkResult.Error -> "Reload failed: ${result.message}"
                    is NetworkResult.NoInternet -> "No internet connection"
                    is NetworkResult.Loading -> null
                }
            )
        }
    }

    fun syncEventLogs() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            deviceRepo.syncEventLogs()
            val count = deviceRepo.getPendingLogCount()
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                pendingLogs = count,
                syncResult = "Event logs synced ($count remaining)"
            )
        }
    }

    fun setAllowRemoteRebootFromHub(allow: Boolean) {
        viewModelScope.launch {
            appPrefs.setAllowRemoteRebootFromHub(allow)
        }
    }
}
