package com.example.mydevice.data.remote.api

import android.util.Log
import com.example.mydevice.data.remote.dto.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ktor-based API service covering all 13 REST endpoints.
 * Each method maps directly to a backend route on mydevices.myweb.net.au.
 */
class MyDevicesApi(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    // ──────────────────────────── Authentication ────────────────────────────

    /** POST api/Authentication/login — PIN-based user login */
    suspend fun login(request: LoginRequest): ApiSuccessResponse<LoginResponse> =
        client.post("api/Authentication/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /** POST api/Authentication/login — device/service login for kiosk SignalR */
    suspend fun deviceLogin(request: DeviceLoginRequest): ApiSuccessResponse<LoginResponse> =
        client.post("api/Authentication/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /** GET api/Users/{companyId} — fetch all users for offline PIN cache */
    suspend fun getUsersByCompany(companyId: Int): List<UserDto> =
        client.get("api/Users/$companyId").body()

    /** GET api/Device/GetDeviceMessages — fallback inbox sync by macAddress */
    suspend fun getDeviceMessagesRaw(macAddress: String): String =
        client.get("api/Device/GetDeviceMessages") {
            parameter("macAddress", macAddress)
        }.bodyAsText()

    // ──────────────────────────── Company / Licensee ────────────────────────

    /** POST api/Company/AddDeviceToCompany — register device with company id */
    suspend fun addDeviceToCompany(request: AddDeviceToCompanyRequest): ApiSuccessResponse<Unit> =
        client.post("api/Company/AddDeviceToCompany") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /** GET api/Company/get-company-by-macaddress/{macaddress} — auto-register lookup */
    suspend fun getCompanyByDeviceId(macAddress: String): CompanyResponse =
        client.get("api/Company/get-company-by-macaddress/$macAddress").body()

    // ──────────────────────────── Device Telemetry ──────────────────────────

    /** POST api/Device — register or update device details (telemetry) */
    suspend fun updateDevice(request: DeviceRequest): DeviceResponse {
        val raw = client.post("api/Device") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.bodyAsText()
        return parseDeviceResponse(raw)
    }

    private fun parseDeviceResponse(raw: String): DeviceResponse {
        try {
            return json.decodeFromString(DeviceResponse.serializer(), raw)
        } catch (_: Exception) {
            val root = JSONObject(raw)
            val payload = when {
                root.has("data") && root.opt("data") is JSONObject -> root.getJSONObject("data")
                root.has("result") && root.opt("result") is JSONObject -> root.getJSONObject("result")
                else -> root
            }
            return DeviceResponse(
                id = payload.optInt("id", 0),
                macAddress = payload.optNullableString("macAddress"),
                deviceModel = payload.optNullableString("deviceModel"),
                osVersion = payload.optNullableString("osVersion"),
                appVersion = payload.optNullableString("appVersion"),
                ipAddress = payload.optNullableString("ipAddress"),
                companyId = payload.optInt("companyId", 0),
                lastSeen = payload.optNullableString("lastSeen"),
                isOnline = payload.optBoolean("isOnline", false)
            )
        }
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, null)
    }

    /** GET api/DeviceConfiguration?deviceid= — fetch remote configuration flags */
    suspend fun getDeviceConfiguration(deviceId: String): DeviceConfigurationResponse =
        client.get("api/DeviceConfiguration") {
            parameter("deviceid", deviceId)
        }.body()

    // ──────────────────────────── Device Config Files ───────────────────────

    /** GET api/DeviceConfig/GetDeviceConfigsByCompany?CompanyId= */
    suspend fun getDeviceConfigsByCompany(companyId: Int): List<DeviceConfigDto> =
        client.get("api/DeviceConfig/GetDeviceConfigsByCompany") {
            parameter("CompanyId", companyId)
        }.body()

    /** GET api/DeviceConfig/GetFilePathsByDeviceConfigs?DeviceConfigId= */
    suspend fun getFilePathsByDeviceConfig(configId: Int): List<DeviceConfigFilePathDto> =
        client.get("api/DeviceConfig/GetFilePathsByDeviceConfigs") {
            parameter("DeviceConfigId", configId)
        }.body()

    /** GET api/Device/DownloadFile?filePath= — stream-download a file */
    suspend fun downloadFile(filePath: String): HttpResponse =
        client.get("api/Device/DownloadFile") {
            parameter("filePath", filePath)
        }

    /** POST api/DeviceFile/AddDeviceFilesByDeviceId — upload file metadata */
    suspend fun addDeviceFiles(files: List<DeviceFileRequest>): Unit =
        client.post("api/DeviceFile/AddDeviceFilesByDeviceId") {
            contentType(ContentType.Application.Json)
            setBody(files)
        }.body()

    // ──────────────────────────── Event / Status Logs ───────────────────────

    /** POST api/DeviceStatusLog/AddLogs?deviceId= — bulk upload event logs */
    suspend fun addStatusLogs(deviceId: String, logs: List<DeviceStatusLogRequest>): Unit =
        client.post("api/DeviceStatusLog/AddLogs") {
            parameter("deviceId", deviceId)
            contentType(ContentType.Application.Json)
            setBody(logs)
        }.body()

    // ──────────────────────────── Kiosk Apps ────────────────────────────────

    /** GET api/CompanyKioskApp/companies/{id} — get approved kiosk apps */
    suspend fun getKioskAppsByCompany(companyId: Int): List<KioskAppDto> {
        val raw = client.get("api/CompanyKioskApp/companies/$companyId").bodyAsText()
        return parseKioskAppsResponse(raw)
    }

    /** GET api/DeviceKioskApp/device/{id} — get kiosk apps for specific device */
    suspend fun getKioskAppsByDevice(deviceId: String): List<KioskAppDto> {
        val raw = client.get("api/DeviceKioskApp/device/$deviceId").bodyAsText()
        return parseKioskAppsResponse(raw)
    }

    /**
     * The server wraps responses in several shapes:
     *   - { "success": true, "data": [ {...}, ... ] }   (list in wrapper)
     *   - { "success": true, "data": { ... } }          (single item in wrapper)
     *   - [ {...}, ... ]                                  (raw array)
     * This parser handles all three.
     */
    private fun parseKioskAppsResponse(raw: String): List<KioskAppDto> {
        val trimmed = raw.trim()
        Log.d(TAG, "parseKioskAppsResponse raw length=${trimmed.length}")

        val jsonArray: JSONArray = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            trimmed.startsWith("{") -> {
                val root = JSONObject(trimmed)
                when {
                    root.has("data") && root.opt("data") is JSONArray -> root.getJSONArray("data")
                    root.has("data") && root.opt("data") is JSONObject -> JSONArray().put(root.getJSONObject("data"))
                    else -> JSONArray().put(root)
                }
            }
            else -> return emptyList()
        }

        return (0 until jsonArray.length()).mapNotNull { i ->
            try {
                val obj = jsonArray.getJSONObject(i)
                KioskAppDto(
                    id = obj.optInt("id", 0),
                    title = obj.optNullableString("title"),
                    icon = obj.optNullableString("icon"),
                    type = obj.optNullableString("type"),
                    autoLaunch = obj.optBoolean("autoLaunch", false),
                    vpnConnect = obj.optBoolean("vpnConnect", false),
                    visible = obj.optBoolean("visible", false),
                    label = obj.optNullableString("label"),
                    activity = obj.optNullableString("activity"),
                    folderId = if (obj.isNull("folderId")) null else obj.optInt("folderId"),
                    folderOrder = obj.optInt("folderOrder", 0),
                    packageName = obj.optString("packageName", ""),
                    companyId = obj.optInt("companyId", 0)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse kiosk app at index $i", e)
                null
            }
        }
    }

    companion object {
        private const val TAG = "MyDevicesApi"
    }
}
