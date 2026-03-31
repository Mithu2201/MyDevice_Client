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
 * WorkManager worker that bulk-uploads pending event logs to the server.
 *
 * WHAT IT DOES:
 * - Reads queued events from Room (device_status_log table)
 * - POSTs batch to api/DeviceStatusLog/AddLogs?deviceId=
 * - Deletes successfully uploaded rows from Room
 * - Repeats until queue is empty or network fails
 *
 * EVENT EXAMPLES: login, logout, app_start, battery_low, device_boot
 */
class EventLogSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val deviceRepo: DeviceRepository by inject()

    override suspend fun doWork(): Result {
        var attempts = 0
        while (attempts < 5) {
            val pendingCount = deviceRepo.getPendingLogCount()
            if (pendingCount == 0) break

            when (deviceRepo.syncEventLogs()) {
                is NetworkResult.Success -> attempts++
                is NetworkResult.NoInternet -> return Result.retry()
                is NetworkResult.Error -> return Result.retry()
                is NetworkResult.Loading -> break
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "event_log_sync_periodic"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<EventLogSyncWorker>(
                Constants.EVENT_SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
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
