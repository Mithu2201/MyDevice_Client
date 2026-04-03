package com.example.mydevice.data.repository

import android.content.Context
import android.util.Log
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.remote.api.safeApiCall
import com.example.mydevice.data.remote.dto.DeviceConfigDto
import com.example.mydevice.data.remote.dto.DeviceConfigFilePathDto
import com.example.mydevice.service.device.DevicePolicyHelper
import com.example.mydevice.service.install.SilentInstallHelper
import io.ktor.client.statement.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest

/**
 * Polls the backend for "script" configurations (which include APK files),
 * downloads them, and triggers silent install for any .apk files found.
 *
 * The admin uploads APKs via the SoftClient Scripts admin panel:
 *   1. Admin creates a DeviceConfig with a name + devicePath
 *   2. Admin uploads files (APKs, XMLs, etc.) to that config
 *   3. This repository fetches configs → file paths → downloads → installs APKs
 *
 * API FLOW:
 *   GET api/DeviceConfig/GetDeviceConfigsByCompany?CompanyId=
 *   GET api/DeviceConfig/GetFilePathsByDeviceConfigs?DeviceConfigId=
 *   GET api/Device/DownloadFile?filePath=
 */
class ScriptRepository(
    private val context: Context,
    private val api: MyDevicesApi,
    private val appPrefs: AppPreferences,
    private val dpmHelper: DevicePolicyHelper
) {
    data class SyncResult(
        val downloaded: Int = 0,
        val installed: Int = 0,
        val skipped: Int = 0,
        val errors: List<String> = emptyList()
    )

    /**
     * Full pipeline: fetch configs → download files → install APKs silently.
     */
    suspend fun syncAndInstallScripts(isDeviceOwner: Boolean): NetworkResult<SyncResult> {
        val companyId = appPrefs.companyId.first()
        if (companyId <= 0) {
            return NetworkResult.Error("Company not registered")
        }

        val configsResult = safeApiCall { api.getDeviceConfigsByCompany(companyId) }
        if (configsResult !is NetworkResult.Success) {
            return when (configsResult) {
                is NetworkResult.Error -> NetworkResult.Error(configsResult.message)
                is NetworkResult.NoInternet -> NetworkResult.NoInternet
                else -> NetworkResult.Error("Failed to fetch configs")
            }
        }

        val configs = configsResult.data
        if (configs.isEmpty()) {
            Log.i(TAG, "No device configs found for companyId=$companyId")
            return NetworkResult.Success(SyncResult())
        }

        var downloaded = 0
        var installed = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        for (config in configs) {
            Log.i(TAG, "Processing config: id=${config.id}, name=${config.name}, devicePath=${config.devicePath}")
            processConfig(config, isDeviceOwner).let { result ->
                downloaded += result.downloaded
                installed += result.installed
                skipped += result.skipped
                errors.addAll(result.errors)
            }
        }

        val summary = SyncResult(downloaded, installed, skipped, errors)
        Log.i(TAG, "Script sync complete: $summary")
        return NetworkResult.Success(summary)
    }

    private suspend fun processConfig(
        config: DeviceConfigDto,
        isDeviceOwner: Boolean
    ): SyncResult {
        val filesResult = safeApiCall { api.getFilePathsByDeviceConfig(config.id) }
        if (filesResult !is NetworkResult.Success) {
            return SyncResult(errors = listOf("Failed to fetch file paths for config ${config.id}"))
        }

        val files = filesResult.data
        if (files.isEmpty()) return SyncResult()

        var downloaded = 0
        var installed = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        for (fileDto in files) {
            val remotePath = fileDto.path ?: fileDto.filePath ?: continue
            val fileName = fileDto.name ?: fileDto.fileName ?: remotePath.substringAfterLast("/")
            val devicePath = config.devicePath ?: "scripts"

            try {
                val localFile = getLocalFile(devicePath, fileName)

                if (localFile.exists() && verifyChecksum(localFile, fileDto.md5Checksum)) {
                    Log.i(TAG, "File unchanged (checksum match), skipping: $fileName")
                    if (fileName.endsWith(".apk", ignoreCase = true)) {
                        skipped++
                    }
                    continue
                }

                val downloadResult = downloadFile(remotePath, localFile)
                if (!downloadResult) {
                    errors.add("Download failed: $fileName")
                    continue
                }
                downloaded++
                Log.i(TAG, "Downloaded: $fileName → ${localFile.absolutePath}")

                if (fileName.endsWith(".apk", ignoreCase = true)) {
                    if (isDeviceOwner) dpmHelper.allowInstalls()
                    try {
                        val installResult = SilentInstallHelper.installApk(
                            context, localFile, isDeviceOwner
                        )
                        when (installResult) {
                            is SilentInstallHelper.InstallResult.Success -> {
                                installed++
                                Log.i(TAG, "Installed APK: $fileName")
                            }
                            is SilentInstallHelper.InstallResult.Failure -> {
                                errors.add("Install failed ($fileName): ${installResult.reason}")
                                Log.w(TAG, "Install failed for $fileName: ${installResult.reason}")
                            }
                        }
                    } finally {
                        if (isDeviceOwner) dpmHelper.blockInstalls()
                    }
                }
            } catch (e: Exception) {
                errors.add("Error processing $fileName: ${e.message}")
                Log.e(TAG, "Error processing file $fileName", e)
            }
        }

        return SyncResult(downloaded, installed, skipped, errors)
    }

    private suspend fun downloadFile(remotePath: String, localFile: File): Boolean {
        return try {
            val response = api.downloadFile(remotePath)
            localFile.parentFile?.let { if (!it.exists()) it.mkdirs() }

            val bytes = response.readBytes()
            localFile.writeBytes(bytes)
            localFile.length() > 0
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $remotePath", e)
            false
        }
    }

    private fun getLocalFile(devicePath: String, fileName: String): File {
        val dir = File(context.filesDir, "scripts/$devicePath")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, fileName)
    }

    private fun verifyChecksum(file: File, expectedMd5: String?): Boolean {
        if (expectedMd5.isNullOrBlank()) return false
        return try {
            val md = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    md.update(buffer, 0, read)
                }
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            actual.equals(expectedMd5, ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "MD5 check failed for ${file.name}", e)
            false
        }
    }

    companion object {
        private const val TAG = "ScriptRepository"
    }
}
