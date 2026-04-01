package com.example.mydevice.data.repository

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.example.mydevice.data.local.database.dao.DeviceStatusLogDao
import com.example.mydevice.data.local.database.entity.DeviceStatusLogEntity
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.remote.api.safeApiCall
import com.example.mydevice.data.remote.dto.*
import com.example.mydevice.util.DeviceInfoUtil
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Manages device registration, telemetry reporting, config, and event logs.
 *
 * DEVICE IDENTITY: Uses Android Settings.Secure.ANDROID_ID as unique identifier.
 * TELEMETRY: Periodically sends model, OS, app version, IP, Wi-Fi signal, battery to server.
 * EVENT LOGS: Written to Room first, then bulk-uploaded and deleted after success.
 */
class DeviceRepository(
    private val context: Context,
    private val api: MyDevicesApi,
    private val statusLogDao: DeviceStatusLogDao,
    private val appPrefs: AppPreferences
) {
    /** Get this device's unique ID (ANDROID_ID) */
    fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "Unknown"
    }

    /** POST device telemetry to server */
    suspend fun updateDeviceDetails(): NetworkResult<DeviceResponse> {
        val deviceInfo = DeviceInfoUtil.collect(context)
        val companyName = appPrefs.companyName.first()
        val request = DeviceRequest(
            image = "",
            deviceModel = Build.MODEL,
            macAddress = getDeviceId(),
            os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            appVersion = deviceInfo.appVersion,
            username = "",
            ipAddress = deviceInfo.ipAddress,
            zone = deviceInfo.zone,
            signalStrength = deviceInfo.signalStrength,
            lastConnected = Instant.now().toString(),
            bssid = deviceInfo.bssid,
            linkSpeed = deviceInfo.linkSpeed,
            essid = deviceInfo.essid,
            latitude = 0.0,
            longitude = 0.0,
            licenseNumber = companyName,
            installedApps = deviceInfo.installedApps,
            appInfoList = deviceInfo.appInfoList
        )
        val result = safeApiCall { api.updateDevice(request) }
        if (result is NetworkResult.Success) {
            appPrefs.setServerDeviceId(result.data.id)
        }
        return result
    }

    /** Fetch remote configuration flags from server */
    suspend fun loadRemoteConfig(): NetworkResult<DeviceConfigurationResponse> {
        val result = safeApiCall { api.getDeviceConfiguration(getDeviceId()) }
        if (result is NetworkResult.Success) {
            val config = result.data
            appPrefs.setShowCheckInView(config.showCheckInView)
            appPrefs.setShowChargingView(config.showChargingView)
            appPrefs.setInactivityTimeoutMinutes(config.inactivityTimeoutMinutes)
            appPrefs.setStatusUpdateIntervalMinutes(config.statusUpdateIntervalMinutes)
        }
        return result
    }

    // ── Event Logs (local queue → server bulk upload) ───────────────────────

    /** Log an event locally in Room DB */
    suspend fun logEvent(type: String, description: String) {
        statusLogDao.insert(
            DeviceStatusLogEntity(
                type = type,
                description = description,
                time = java.time.Instant.now().toString()
            )
        )
    }

    /** Bulk-upload pending event logs, delete after success */
    suspend fun syncEventLogs(): NetworkResult<Unit> {
        val pending = statusLogDao.getBatch(50)
        if (pending.isEmpty()) return NetworkResult.Success(Unit)

        val requests = pending.map { entity ->
            DeviceStatusLogRequest(
                description = entity.description,
                type = entity.type,
                time = entity.time
            )
        }
        val result = safeApiCall { api.addStatusLogs(getDeviceId(), requests) }
        if (result is NetworkResult.Success) {
            statusLogDao.deleteByIds(pending.map { it.id })
        }
        return result
    }

    suspend fun getPendingLogCount(): Int = statusLogDao.count()
}
