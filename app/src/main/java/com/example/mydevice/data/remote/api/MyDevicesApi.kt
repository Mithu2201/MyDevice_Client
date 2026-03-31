package com.example.mydevice.data.remote.api

import com.example.mydevice.data.remote.dto.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*

/**
 * Ktor-based API service covering all 13 REST endpoints.
 * Each method maps directly to a backend route on mydevices.myweb.net.au.
 */
class MyDevicesApi(private val client: HttpClient) {

    // ──────────────────────────── Authentication ────────────────────────────

    /** POST api/Authentication/login — PIN-based device login */
    suspend fun login(request: LoginRequest): LoginResponse =
        client.post("api/Authentication/login") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /** GET api/Users/{companyId} — fetch all users for offline PIN cache */
    suspend fun getUsersByCompany(companyId: Int): List<UserDto> =
        client.get("api/Users/$companyId").body()

    // ──────────────────────────── Company / Licensee ────────────────────────

    /** POST api/Company/AddDeviceToCompanyByCompanyCode — register device with company code */
    suspend fun addDeviceToCompany(request: AddDeviceToCompanyRequest): CompanyResponse =
        client.post("api/Company/AddDeviceToCompanyByCompanyCode") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    /** GET api/Company/get-company-by-macaddress/{macaddress} — auto-register lookup */
    suspend fun getCompanyByDeviceId(macAddress: String): CompanyResponse =
        client.get("api/Company/get-company-by-macaddress/$macAddress").body()

    // ──────────────────────────── Device Telemetry ──────────────────────────

    /** POST api/Device — register or update device details (telemetry) */
    suspend fun updateDevice(request: DeviceRequest): DeviceResponse =
        client.post("api/Device") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

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

    /** GET api/CompanyKioskApp/companies/{id} — get whitelisted kiosk apps */
    suspend fun getKioskAppsByCompany(companyId: Int): List<KioskAppDto> =
        client.get("api/CompanyKioskApp/companies/$companyId").body()

    /** GET api/DeviceKioskApp/device/{id} — get kiosk apps for specific device */
    suspend fun getKioskAppsByDevice(deviceId: String): List<KioskAppDto> =
        client.get("api/DeviceKioskApp/device/$deviceId").body()
}
