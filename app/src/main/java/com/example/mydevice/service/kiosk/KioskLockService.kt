package com.example.mydevice.service.kiosk

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.mydevice.MainActivity
import com.example.mydevice.R

/**
 * Foreground service that enforces kiosk mode by monitoring which app is
 * in the foreground. If a non-whitelisted app is detected, it immediately
 * brings our app back.
 *
 * Works on Android 8.1 (API 27) using UsageStatsManager event queries.
 * Requires the user to grant "Usage Access" permission in Settings.
 */
class KioskLockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkForegroundApp()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_WHITELIST -> {
                val packages = intent.getStringArrayExtra(EXTRA_PACKAGES)
                if (packages != null) {
                    whitelistedPackages.clear()
                    whitelistedPackages.addAll(packages)
                    whitelistedPackages.addAll(SYSTEM_ALWAYS_ALLOWED)
                    Log.i(TAG, "Whitelist updated: ${whitelistedPackages.size} packages")
                }
                return START_STICKY
            }
        }

        val packages = intent?.getStringArrayExtra(EXTRA_PACKAGES)
        if (packages != null) {
            whitelistedPackages.clear()
            whitelistedPackages.addAll(packages)
        }
        whitelistedPackages.addAll(SYSTEM_ALWAYS_ALLOWED)
        whitelistedPackages.add(packageName)

        startForeground(NOTIFICATION_ID, buildNotification())
        isRunning = true
        handler.post(checkRunnable)
        Log.i(TAG, "Kiosk lock service started with ${whitelistedPackages.size} whitelisted packages")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(checkRunnable)
        Log.i(TAG, "Kiosk lock service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkForegroundApp() {
        val foreground = getForegroundPackage() ?: return
        if (foreground == packageName) return
        if (whitelistedPackages.contains(foreground)) return

        Log.w(TAG, "Blocked non-whitelisted app: $foreground — bringing kiosk back")
        bringAppToFront()
    }

    private fun getForegroundPackage(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null

        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 2000, now)
        val event = UsageEvents.Event()
        var lastForeground: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForeground = event.packageName
            }
        }
        return lastForeground
    }

    private fun bringAppToFront() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kiosk Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Device is in managed kiosk mode"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kiosk Mode Active")
            .setContentText("Device is managed. Only approved apps are available.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "KioskLockService"
        private const val CHANNEL_ID = "kiosk_lock_channel"
        private const val NOTIFICATION_ID = 9001
        private const val CHECK_INTERVAL_MS = 500L

        const val ACTION_STOP = "com.example.mydevice.STOP_KIOSK_LOCK"
        const val ACTION_UPDATE_WHITELIST = "com.example.mydevice.UPDATE_WHITELIST"
        const val EXTRA_PACKAGES = "extra_packages"

        private val whitelistedPackages = mutableSetOf<String>()

        private val SYSTEM_ALWAYS_ALLOWED = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.sec.android.inputmethod",
            "com.vivo.ime",
            "com.baidu.input_vivo",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller"
        )

        fun hasUsageStatsPermission(context: Context): Boolean {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return false
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 60_000, now
            )
            return stats != null && stats.isNotEmpty()
        }

        fun start(context: Context, allowedPackages: List<String>) {
            val intent = Intent(context, KioskLockService::class.java).apply {
                putExtra(EXTRA_PACKAGES, allowedPackages.toTypedArray())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateWhitelist(context: Context, allowedPackages: List<String>) {
            val intent = Intent(context, KioskLockService::class.java).apply {
                action = ACTION_UPDATE_WHITELIST
                putExtra(EXTRA_PACKAGES, allowedPackages.toTypedArray())
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, KioskLockService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
