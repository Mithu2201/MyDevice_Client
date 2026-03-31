package com.example.mydevice

import android.app.Application
import android.util.Log
import com.example.mydevice.di.appModule
import com.example.mydevice.di.databaseModule
import com.example.mydevice.di.networkModule
import com.example.mydevice.di.viewModelModule
import com.example.mydevice.service.worker.ConfigFileDownloadWorker
import com.example.mydevice.service.worker.DeviceStatusWorker
import com.example.mydevice.service.worker.EventLogSyncWorker
import com.example.mydevice.service.worker.KioskAppsRefreshWorker
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

/**
 * Application class — the very first code that runs when the app process starts.
 *
 * RESPONSIBILITIES:
 * 1. Initialize Koin dependency injection with all modules
 * 2. Schedule all WorkManager periodic workers
 *
 * KOIN MODULES LOADED:
 * - networkModule → Ktor HttpClient + MyDevicesApi
 * - databaseModule → Room DB + DAOs
 * - appModule → Preferences + Repositories + SignalR
 * - viewModelModule → All ViewModels
 *
 * WORKERS SCHEDULED:
 * - DeviceStatusWorker → telemetry every 15 min
 * - EventLogSyncWorker → event logs every 10 min
 * - ConfigFileDownloadWorker → config files every 30 min
 * - KioskAppsRefreshWorker → app whitelist every 1 hour
 */
class MyDeviceApp : Application() {

    companion object {
        private const val TAG = "MyDeviceApp"
    }

    override fun onCreate() {
        super.onCreate()

        // Start Koin safely — if Koin startup throws, log and continue so the
        // app process doesn't crash immediately. Later DI lookups may fail,
        // but we'll guard usages in Activities/Services where needed.
        try {
            startKoin {
                androidLogger(Level.ERROR)
                androidContext(this@MyDeviceApp)
                workManagerFactory()
                modules(
                    networkModule,
                    databaseModule,
                    appModule,
                    viewModelModule
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Koin", e)
        }

        // Schedule worker tasks, but guard each in try/catch to avoid crashing
        // the app if WorkManager or any worker class throws during enqueue.
        scheduleWorkersSafely()
    }

    private fun scheduleWorkersSafely() {
        try {
            DeviceStatusWorker.enqueue(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule DeviceStatusWorker", e)
        }

        try {
            EventLogSyncWorker.enqueue(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule EventLogSyncWorker", e)
        }

        try {
            ConfigFileDownloadWorker.enqueuePeriodic(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule ConfigFileDownloadWorker", e)
        }

        try {
            KioskAppsRefreshWorker.enqueue(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule KioskAppsRefreshWorker", e)
        }
    }
}
