package com.example.mydevice.service.script

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.example.mydevice.data.local.preferences.SecurePreferences
import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.service.device.DevicePolicyHelper
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Executes remote script payloads that can request APK download + install.
 *
 * Supported payload shapes:
 * 1) JSON:
 *    {"type":"install_apk","url":"https://.../app.apk","packageName":"com.example.app"}
 *    {"command":"install_apk","filePath":"configs/apps/myapp.apk"}
 * 2) Plain text containing an APK URL.
 */
class RemoteScriptExecutor(
    private val context: Context,
    private val api: MyDevicesApi,
    private val securePrefs: SecurePreferences,
    private val dpmHelper: DevicePolicyHelper
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    init {
        registerInstallResultReceiver()
    }

    fun executeScript(payload: String) {
        scope.launch {
            mutex.withLock {
                try {
                    val command = parseInstallCommand(payload) ?: run {
                        Log.i(TAG, "Ignoring script payload (no install command found)")
                        return@withLock
                    }

                    val apkFile = downloadApk(command)
                    if (apkFile == null || !apkFile.exists()) {
                        Log.w(TAG, "APK download failed for script command")
                        return@withLock
                    }

                    installApk(command, apkFile)
                } catch (e: Exception) {
                    Log.e(TAG, "Script execution failed", e)
                }
            }
        }
    }

    private fun parseInstallCommand(payload: String): InstallCommand? {
        val trimmed = payload.trim()
        if (trimmed.isBlank()) return null

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)
                val commandType = json.optString("type")
                    .ifBlank { json.optString("command") }
                    .ifBlank { json.optString("action") }
                    .lowercase(Locale.US)

                val url = json.optString("url")
                    .ifBlank { json.optString("downloadUrl") }
                    .ifBlank { json.optString("apkUrl") }
                    .ifBlank { json.optString("fileUrl") }
                    .ifBlank { "" }
                val filePath = json.optString("filePath")
                    .ifBlank { json.optString("path") }
                    .ifBlank { json.optString("apkPath") }
                    .ifBlank { "" }
                val packageName = json.optString("packageName").ifBlank { null }

                val isInstallCommand = commandType.contains("install") ||
                    commandType.contains("apk") ||
                    url.endsWith(".apk", ignoreCase = true) ||
                    filePath.endsWith(".apk", ignoreCase = true)

                if (!isInstallCommand) return null
                return InstallCommand(
                    url = url.takeIf { it.isNotBlank() },
                    filePath = filePath.takeIf { it.isNotBlank() },
                    packageName = packageName
                )
            } catch (_: Exception) {
                // Fall through to text parsing
            }
        }

        val apkUrlRegex = Regex("""https?://[^\s'"]+\.apk([^\s'"]*)?""", RegexOption.IGNORE_CASE)
        val urlMatch = apkUrlRegex.find(trimmed)?.value
        if (!urlMatch.isNullOrBlank()) {
            return InstallCommand(url = urlMatch, filePath = null, packageName = null)
        }

        return null
    }

    private suspend fun downloadApk(command: InstallCommand): File? {
        val targetDir = File(appContext.filesDir, "remote_scripts").apply { mkdirs() }
        val filename = command.url
            ?.substringAfterLast('/')
            ?.substringBefore('?')
            ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: command.filePath
                ?.substringAfterLast('/')
                ?.takeIf { it.endsWith(".apk", ignoreCase = true) }
            ?: "remote_${System.currentTimeMillis()}.apk"
        val targetFile = File(targetDir, filename)

        command.url?.let { url ->
            return downloadFromUrl(url, targetFile)
        }
        command.filePath?.let { path ->
            return downloadFromServerPath(path, targetFile)
        }
        return null
    }

    private fun downloadFromUrl(url: String, targetFile: File): File? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            requestMethod = "GET"
            securePrefs.accessToken?.takeIf { it.isNotBlank() }?.let { token ->
                setRequestProperty("Authorization", "Bearer $token")
            }
        }

        return try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "APK URL download failed: code=${connection.responseCode}")
                null
            } else {
                connection.inputStream.use { input ->
                    FileOutputStream(targetFile).use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "APK downloaded from URL to ${targetFile.absolutePath}")
                targetFile
            }
        } catch (e: Exception) {
            Log.e(TAG, "APK URL download failed", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadFromServerPath(filePath: String, targetFile: File): File? {
        return try {
            val response = api.downloadFile(filePath)
            val channel = response.bodyAsChannel()
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer)
                    if (read > 0) output.write(buffer, 0, read)
                }
            }
            Log.i(TAG, "APK downloaded from server path to ${targetFile.absolutePath}")
            targetFile
        } catch (e: Exception) {
            Log.e(TAG, "APK path download failed for $filePath", e)
            null
        }
    }

    private fun installApk(command: InstallCommand, apkFile: File) {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            command.packageName?.let { setAppPackageName(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            apkFile.inputStream().use { input ->
                session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val callbackIntent = Intent(ACTION_INSTALL_RESULT).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_PACKAGE_NAME, command.packageName ?: "")
            }
            val pendingIntent = PendingIntent.getBroadcast(
                appContext,
                sessionId,
                callbackIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            session.commit(pendingIntent.intentSender)
        } finally {
            session.close()
        }

        Log.i(
            TAG,
            "Install commit requested. sessionId=$sessionId, package=${command.packageName}, deviceOwner=${dpmHelper.isDeviceOwner()}"
        )
    }

    private fun registerInstallResultReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val sessionId = intent.getIntExtra(EXTRA_SESSION_ID, -1)
                val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()

                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        Log.i(TAG, "APK install success. sessionId=$sessionId package=$packageName")
                    }
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        Log.w(
                            TAG,
                            "Silent install requires user action. sessionId=$sessionId package=$packageName message=$statusMessage"
                        )
                        val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (confirmIntent != null) appContext.startActivity(confirmIntent)
                    }
                    else -> {
                        Log.e(
                            TAG,
                            "APK install failed. sessionId=$sessionId package=$packageName status=$status message=$statusMessage"
                        )
                    }
                }
            }
        }

        val filter = IntentFilter(ACTION_INSTALL_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private data class InstallCommand(
        val url: String?,
        val filePath: String?,
        val packageName: String?
    )

    companion object {
        private const val TAG = "RemoteScriptExecutor"
        private const val ACTION_INSTALL_RESULT = "com.example.mydevice.ACTION_APK_INSTALL_RESULT"
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_PACKAGE_NAME = "package_name"
    }
}
