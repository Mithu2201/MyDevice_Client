package com.example.mydevice.service.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/**
 * Handles APK installation in two modes:
 *
 * 1. DEVICE OWNER — uses PackageInstaller session API for truly silent,
 *    zero-interaction install. The system grants install permission automatically
 *    to the device owner app.
 *
 * 2. NON-DEVICE OWNER — falls back to ACTION_VIEW + FileProvider, which shows
 *    the system install prompt. Still works inside kiosk if REQUEST_INSTALL_PACKAGES
 *    is granted.
 */
object SilentInstallHelper {
    private const val TAG = "SilentInstallHelper"

    sealed class InstallResult {
        data object Success : InstallResult()
        data class Failure(val reason: String) : InstallResult()
    }

    fun installApk(context: Context, apkFile: File, isDeviceOwner: Boolean): InstallResult {
        if (!apkFile.exists()) {
            return InstallResult.Failure("APK file not found: ${apkFile.absolutePath}")
        }

        return if (isDeviceOwner) {
            silentInstall(context, apkFile)
        } else {
            intentInstall(context, apkFile)
        }
    }

    /**
     * Device-owner silent install via PackageInstaller session API.
     * No user interaction required.
     */
    private fun silentInstall(context: Context, apkFile: File): InstallResult {
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setSize(apkFile.length())

            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)

            session.openWrite("apk_install", 0, apkFile.length()).use { out ->
                FileInputStream(apkFile).use { input ->
                    val buf = ByteArray(65536)
                    var len: Int
                    while (input.read(buf).also { len = it } != -1) {
                        out.write(buf, 0, len)
                    }
                    session.fsync(out)
                }
            }

            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pi.intentSender)
            session.close()

            Log.i(TAG, "Silent install session committed for ${apkFile.name}")
            InstallResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Silent install failed", e)
            InstallResult.Failure(e.message ?: "PackageInstaller session failed")
        }
    }

    /**
     * Fallback install using ACTION_VIEW + FileProvider.
     * Shows the system installer dialog.
     */
    private fun intentInstall(context: Context, apkFile: File): InstallResult {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            Log.i(TAG, "Intent install launched for ${apkFile.name}")
            InstallResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "Intent install failed", e)
            InstallResult.Failure(e.message ?: "Intent install failed")
        }
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
