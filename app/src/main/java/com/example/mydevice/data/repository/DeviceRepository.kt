package com.example.mydevice.data.repository

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
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
    /** Get the raw current device ID from Android settings. */
    fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "Unknown"
    }

    /**
     * Returns the device key used with the backend.
     *
     * If the current raw Android ID differs from the previously stored device ID,
     * prefer the current raw ID and persist it so the app re-registers using the
     * identity reported by the current device.
     */
    suspend fun getStableDeviceId(): String {
        val stored = appPrefs.deviceId.first()
        val raw = getDeviceId()
        val hasUsableRaw = raw.isNotBlank() && raw != "Unknown"

        if (hasUsableRaw && raw != stored) {
            Log.i(TAG, "Device ID changed. stored=$stored, current=$raw. Persisting current device ID.")
            appPrefs.setDeviceId(raw)
            appPrefs.setServerDeviceId(0)
            return raw
        }

        if (stored.isNotBlank()) return stored
        return raw
    }

    /** POST device telemetry to server */
    suspend fun updateDeviceDetails(): NetworkResult<DeviceResponse> {
        val deviceInfo = DeviceInfoUtil.collect(context)
        val companyName = appPrefs.companyName.first()
        val stableDeviceId = getStableDeviceId()
        val request = DeviceRequest(
            image = "",
            deviceModel = Build.MODEL,
            macAddress = stableDeviceId,
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
            var id = result.data.id
            if (id <= 0) {
                Log.w(TAG, "Telemetry POST returned id=0; resolving numeric id via GetByMAC")
                when (val lookup = safeApiCall { api.getDeviceByMac(stableDeviceId) }) {
                    is NetworkResult.Success -> {
                        if (lookup.data.id > 0) {
                            id = lookup.data.id
                            Log.i(TAG, "Resolved server device id from GetByMAC: $id")
                        }
                    }
                    else -> { }
                }
            }
            if (id > 0) {
                appPrefs.setServerDeviceId(id)
            }
        }
        return result
    }

    /**
     * Ensures [AppPreferences.serverDeviceId] is set using [MyDevicesApi.getDeviceByMac]
     * when it is still 0 (matches admin `SendRebootCall?deviceId=` / SignalR AddDeviceId).
     */
    suspend fun ensureServerDeviceIdFromLookup(): Boolean {
        val existing = appPrefs.serverDeviceId.first()
        if (existing > 0) return true
        val mac = getStableDeviceId()
        if (mac.isBlank()) return false
        return when (val res = safeApiCall { api.getDeviceByMac(mac) }) {
            is NetworkResult.Success -> {
                val id = res.data.id
                if (id > 0) {
                    appPrefs.setServerDeviceId(id)
                    Log.i(TAG, "serverDeviceId set from GetByMAC: id=$id mac=$mac")
                    true
                } else {
                    Log.w(TAG, "GetByMAC returned id=0 for mac=$mac")
                    false
                }
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "GetByMAC failed: ${res.message}")
                false
            }
            is NetworkResult.NoInternet -> false
            is NetworkResult.Loading -> false
        }
    }

    /** Fetch remote configuration flags from server */
    suspend fun loadRemoteConfig(): NetworkResult<DeviceConfigurationResponse> {
        val result = safeApiCall { api.getDeviceConfiguration(getStableDeviceId()) }
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
        val result = safeApiCall { api.addStatusLogs(getStableDeviceId(), requests) }
        if (result is NetworkResult.Success) {
            statusLogDao.deleteByIds(pending.map { it.id })
        }
        return result
    }

    suspend fun getPendingLogCount(): Int = statusLogDao.count()

    companion object {
        private const val TAG = "DeviceRepository"
    }
}
