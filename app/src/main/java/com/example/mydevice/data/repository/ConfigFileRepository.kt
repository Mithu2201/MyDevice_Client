package com.example.mydevice.data.repository

import android.content.Context
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.remote.api.safeApiCall
import com.example.mydevice.data.remote.dto.DeviceConfigDto
import com.example.mydevice.data.remote.dto.DeviceConfigFilePathDto
import com.example.mydevice.data.remote.dto.DeviceFileRequest
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant

/**
 * Handles downloading config files from the server.
 *
 * FLOW:
 * 1. Fetch device configs for the company
 * 2. For each config, get file paths
 * 3. Stream-download each file to local storage
 * 4. Report downloaded file metadata back to server
 */
class ConfigFileRepository(
    private val context: Context,
    private val api: MyDevicesApi,
    private val appPrefs: AppPreferences
) {
    /** Get all device configs for this company */
    suspend fun getConfigs(): NetworkResult<List<DeviceConfigDto>> {
        val companyId = appPrefs.companyId.first()
        return safeApiCall { api.getDeviceConfigsByCompany(companyId) }
    }

    /** Get file paths for a specific config */
    suspend fun getFilePaths(configId: Int): NetworkResult<List<DeviceConfigFilePathDto>> =
        safeApiCall { api.getFilePathsByDeviceConfig(configId) }

    /** Download a file from server to local storage */
    suspend fun downloadFile(filePath: String, fileName: String): NetworkResult<File> {
        return try {
            val response = api.downloadFile(filePath)
            val dir = File(context.filesDir, "config_files")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            val channel: ByteReadChannel = response.bodyAsChannel()
            file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read > 0) output.write(buffer, 0, read)
                }
            }
            NetworkResult.Success(file)
        } catch (e: Exception) {
            NetworkResult.Error(e.message ?: "Download failed")
        }
    }

    /** Report downloaded files to server */
    suspend fun reportDownloadedFiles(
        deviceId: String,
        files: List<Pair<String, File>>
    ): NetworkResult<Unit> {
        val requests = files.map { (path, file) ->
            DeviceFileRequest(
                deviceId = deviceId,
                fileName = file.name,
                filePath = path,
                fileSize = file.length(),
                downloadedAt = Instant.now().toString()
            )
        }
        return safeApiCall { api.addDeviceFiles(requests) }
    }
}
