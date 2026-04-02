package com.example.mydevice.service.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.repository.ScriptRepository
import com.example.mydevice.service.device.DevicePolicyHelper
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that polls for admin-uploaded scripts/APKs
 * and silently installs them.
 *
 * FLOW:
 *   1. Fetch DeviceConfigs for the company
 *   2. For each config, fetch file paths
 *   3. Download new/changed files (MD5 checksum comparison)
 *   4. If file is .apk → silent install (device owner) or intent install (fallback)
 *
 * SCHEDULING:
 *   - One-shot on app start (enqueueOnce)
 *   - Periodic every 15 minutes (enqueuePeriodic)
 *   - Can be triggered manually via enqueueOnce after SignalR command
 */
class ScriptSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val scriptRepo: ScriptRepository by inject()
    private val dpmHelper: DevicePolicyHelper by inject()

    override suspend fun doWork(): Result {
        Log.i(TAG, "Script sync starting...")

        val isDeviceOwner = try { dpmHelper.isDeviceOwner() } catch (_: Exception) { false }
        val result = scriptRepo.syncAndInstallScripts(isDeviceOwner)

        return when (result) {
            is NetworkResult.Success -> {
                val summary = result.data
                Log.i(TAG, "Script sync done: downloaded=${summary.downloaded}, installed=${summary.installed}, skipped=${summary.skipped}, errors=${summary.errors.size}")
                if (summary.errors.isNotEmpty()) {
                    summary.errors.forEach { Log.w(TAG, "  error: $it") }
                }
                Result.success()
            }
            is NetworkResult.NoInternet -> {
                Log.w(TAG, "Script sync skipped: no internet")
                Result.retry()
            }
            is NetworkResult.Error -> {
                Log.e(TAG, "Script sync failed: ${result.message}")
                Result.retry()
            }
            else -> Result.retry()
        }
    }

    companion object {
        private const val TAG = "ScriptSyncWorker"
        private const val WORK_NAME = "script_sync"
        private const val PERIODIC_INTERVAL_MINUTES = 15L

        fun enqueueOnce(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ScriptSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

        fun enqueuePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ScriptSyncWorker>(
                PERIODIC_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "${WORK_NAME}_periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
