package com.example.mydevice.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.example.mydevice.data.remote.dto.AppInfoRequest
import com.example.mydevice.data.remote.dto.InstalledAppRequest
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class DeviceInfo(
    val appVersion: String,
    val ipAddress: String,
    val wifiSignalStrength: Int?,
    val signalStrength: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val essid: String,
    val bssid: String,
    val linkSpeed: String,
    val zone: String,
    val installedApps: List<InstalledAppRequest>,
    val appInfoList: List<AppInfoRequest>
)

object DeviceInfoUtil {

    fun collect(context: Context): DeviceInfo {
        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) { null }

        val wifiInfo = getWifiDetails(context)
        val installedApps = getInstalledAppInventory(context)

        return DeviceInfo(
            appVersion = packageInfo?.versionName ?: "1.0.0",
            ipAddress = getIpAddress().orEmpty(),
            wifiSignalStrength = wifiInfo.rssi,
            signalStrength = wifiInfo.rssi?.toString().orEmpty(),
            batteryLevel = getBatteryLevel(context),
            isCharging = isCharging(context),
            essid = wifiInfo.essid,
            bssid = wifiInfo.bssid,
            linkSpeed = wifiInfo.linkSpeed,
            zone = ZoneId.systemDefault().id,
            installedApps = installedApps.installedApps,
            appInfoList = installedApps.appInfoList
        )
    }

    fun getBatteryLevel(context: Context): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100) / scale else -1
    }

    fun isCharging(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getWifiSignalStrength(context: Context): Int? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = wifiManager?.connectionInfo
        return info?.rssi
    }

    private data class WifiDetails(
        val rssi: Int? = null,
        val essid: String = "",
        val bssid: String = "",
        val linkSpeed: String = ""
    )

    private data class AppInventory(
        val installedApps: List<InstalledAppRequest>,
        val appInfoList: List<AppInfoRequest>
    )

    private fun getWifiDetails(context: Context): WifiDetails {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return WifiDetails()
        val info = wifiManager.connectionInfo ?: return WifiDetails()
        val essid = sanitizeSsid(info.ssid)
        return WifiDetails(
            rssi = info.rssi,
            essid = essid,
            bssid = info.bssid.orEmpty(),
            linkSpeed = if (info.linkSpeed > 0) "${info.linkSpeed} Mbps" else ""
        )
    }

    private fun sanitizeSsid(ssid: String?): String {
        if (ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID) return ""
        return ssid.removePrefix("\"").removeSuffix("\"")
    }

    private fun getInstalledAppInventory(context: Context): AppInventory {
        val pm = context.packageManager
        val installedApplications = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            emptyList()
        }

        val installedApps = mutableListOf<InstalledAppRequest>()
        val appInfoList = mutableListOf<AppInfoRequest>()

        installedApplications
            .sortedBy { app ->
                try {
                    pm.getApplicationLabel(app).toString()
                } catch (_: Exception) {
                    app.packageName
                }
            }
            .forEachIndexed { index, app ->
                val appName = try {
                    pm.getApplicationLabel(app).toString()
                } catch (_: Exception) {
                    app.packageName
                }

                installedApps += InstalledAppRequest(applicationName = appName)

                val packageInfo = try {
                    getPackageInfoCompat(pm, app.packageName)
                } catch (_: Exception) {
                    null
                }

                appInfoList += AppInfoRequest(
                    id = index + 1,
                    name = appName,
                    packageName = app.packageName,
                    version = packageInfo?.versionName.orEmpty(),
                    minSDK = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        packageInfo?.applicationInfo?.minSdkVersion ?: 0
                    } else {
                        0
                    },
                    targetSDK = packageInfo?.applicationInfo?.targetSdkVersion ?: 0,
                    activities = packageInfo?.activities?.joinToString { it.name }.orEmpty(),
                    receivers = packageInfo?.receivers?.joinToString { it.name }.orEmpty(),
                    isSystemApp = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    firstInstallTime = packageInfo?.firstInstallTime?.toIsoString().orEmpty(),
                    lastUpdateTime = packageInfo?.lastUpdateTime?.toIsoString().orEmpty(),
                    mobileStatusId = 0
                )
            }

        return AppInventory(
            installedApps = installedApps,
            appInfoList = appInfoList
        )
    }

    private fun getPackageInfoCompat(pm: PackageManager, packageName: String): PackageInfo {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_META_DATA
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }
    }

    private fun Long?.toIsoString(): String {
        if (this == null || this <= 0L) return ""
        return try {
            Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        } catch (_: Exception) {
            ""
        }
    }

    private fun getIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
}
