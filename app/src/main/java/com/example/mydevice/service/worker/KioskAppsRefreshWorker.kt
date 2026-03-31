package com.example.mydevice.service.worker

import android.content.Context
import androidx.work.*
import com.example.mydevice.data.repository.KioskRepository
import com.example.mydevice.util.Constants
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that refreshes the kiosk app whitelist every hour.
 *
 * WHAT IT DOES:
 * - GETs api/CompanyKioskApp/companies/{id}
 * - Updates the in-memory whitelist used by kiosk mode enforcement
 */
class KioskAppsRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val kioskRepo: KioskRepository by inject()

    override suspend fun doWork(): Result {
        kioskRepo.refreshKioskApps()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "kiosk_apps_refresh"

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<KioskAppsRefreshWorker>(
                Constants.KIOSK_APPS_REFRESH_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
