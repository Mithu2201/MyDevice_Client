package com.example.mydevice.service.worker

import android.content.Context
import androidx.work.*
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.repository.ConfigFileRepository
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.util.Constants
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that downloads configuration files from the server.
 *
 * FLOW:
 * 1. GET api/DeviceConfig/GetDeviceConfigsByCompany → list of configs
 * 2. For each config → GET file paths
 * 3. For each file path → stream-download the file
 * 4. POST api/DeviceFile/AddDeviceFilesByDeviceId → report what was downloaded
 *
 * WHY WorkManager:
 * - File downloads can be large; WorkManager handles retries on failure
 * - Runs with CONNECTED network constraint
 * - Doesn't block the UI thread
 */
class ConfigFileDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val configFileRepo: ConfigFileRepository by inject()
    private val deviceRepo: DeviceRepository by inject()

    override suspend fun doWork(): Result {
        val configsResult = configFileRepo.getConfigs()
        if (configsResult !is NetworkResult.Success) return Result.retry()

        val downloadedFiles = mutableListOf<Pair<String, File>>()

        for (config in configsResult.data) {
            val pathsResult = configFileRepo.getFilePaths(config.id)
            if (pathsResult !is NetworkResult.Success) continue

            for (filePath in pathsResult.data) {
                val path = filePath.filePath ?: continue
                val name = filePath.fileName ?: path.substringAfterLast("/")
                val downloadResult = configFileRepo.downloadFile(path, name)
                if (downloadResult is NetworkResult.Success) {
                    downloadedFiles.add(path to downloadResult.data)
                }
            }
        }

        if (downloadedFiles.isNotEmpty()) {
            configFileRepo.reportDownloadedFiles(deviceRepo.getStableDeviceId(), downloadedFiles)
        }

        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "config_file_download"

        fun enqueueOnce(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ConfigFileDownloadWorker>()
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

            val request = PeriodicWorkRequestBuilder<ConfigFileDownloadWorker>(
                Constants.FILE_SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "${WORK_NAME}_periodic",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
