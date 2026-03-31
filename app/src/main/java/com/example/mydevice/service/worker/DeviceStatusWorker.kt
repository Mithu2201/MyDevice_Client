package com.example.mydevice.service.worker

import android.content.Context
import androidx.work.*
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.util.Constants
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that periodically sends device telemetry to the server.
 *
 * WHAT IT DOES:
 * - Collects device info (model, OS, app version, IP, Wi-Fi, battery)
 * - POSTs to api/Device
 * - Runs every N minutes (configured by remote config, default 15 min)
 *
 * WHY WorkManager instead of a foreground service:
 * - Battery-friendly: respects Doze mode and battery saver
 * - Guaranteed execution: survives app kills and device reboots
 * - Constraint-aware: only runs when network is available
 */
class DeviceStatusWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val deviceRepo: DeviceRepository by inject()

    override suspend fun doWork(): Result {
        return when (deviceRepo.updateDeviceDetails()) {
            is NetworkResult.Success -> Result.success()
            is NetworkResult.NoInternet -> Result.retry()
            is NetworkResult.Error -> Result.retry()
            is NetworkResult.Loading -> Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "device_status_periodic"

        fun enqueue(context: Context, intervalMinutes: Long = Constants.STATUS_UPDATE_INTERVAL_MINUTES) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<DeviceStatusWorker>(
                intervalMinutes, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
