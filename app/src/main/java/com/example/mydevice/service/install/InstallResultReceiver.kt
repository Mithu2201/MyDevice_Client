package com.example.mydevice.service.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * Receives the result of a PackageInstaller session commit.
 * Logs the outcome for diagnostics.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS ->
                Log.i(TAG, "APK installed successfully")
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                    Log.i(TAG, "User confirmation required for install")
                }
            }
            PackageInstaller.STATUS_FAILURE ->
                Log.e(TAG, "Install failed: $message")
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                Log.w(TAG, "Install aborted: $message")
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                Log.e(TAG, "Install blocked: $message")
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                Log.e(TAG, "Install conflict: $message")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                Log.e(TAG, "Install incompatible: $message")
            PackageInstaller.STATUS_FAILURE_INVALID ->
                Log.e(TAG, "Install invalid APK: $message")
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                Log.e(TAG, "Install storage issue: $message")
            else ->
                Log.w(TAG, "Install unknown status=$status: $message")
        }
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
    }
}
