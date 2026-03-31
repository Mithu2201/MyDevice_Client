package com.example.mydevice.ui.charging

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.util.DeviceInfoUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Drives the Charging screen shown when device is plugged in.
 *
 * BEHAVIOR:
 * - Shown automatically when charger is plugged in (if config enables it)
 * - Displays battery %, IP address, Wi-Fi signal, device model
 * - Polls every 10 seconds for updated battery info
 * - When charger is unplugged → navigates back to check-in or kiosk
 */
data class ChargingUiState(
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val ipAddress: String = "N/A",
    val wifiSignal: Int = 0,
    val deviceModel: String = "",
    val shouldDismiss: Boolean = false
)

class ChargingViewModel(
    private val deviceRepo: DeviceRepository,
    private val appPrefs: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChargingUiState())
    val uiState: StateFlow<ChargingUiState> = _uiState.asStateFlow()

    fun startMonitoring(context: Context) {
        viewModelScope.launch {
            while (true) {
                val info = DeviceInfoUtil.collect(context)
                val charging = DeviceInfoUtil.isCharging(context)

                _uiState.value = ChargingUiState(
                    batteryLevel = info.batteryLevel,
                    isCharging = charging,
                    ipAddress = info.ipAddress ?: "N/A",
                    wifiSignal = info.wifiSignalStrength ?: 0,
                    deviceModel = android.os.Build.MODEL,
                    shouldDismiss = !charging
                )

                if (!charging) break
                delay(10_000)
            }
        }
    }
}
