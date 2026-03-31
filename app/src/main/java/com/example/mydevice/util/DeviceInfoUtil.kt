package com.example.mydevice.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import java.net.Inet4Address
import java.net.NetworkInterface

data class DeviceInfo(
    val appVersion: String,
    val ipAddress: String?,
    val wifiSignalStrength: Int?,
    val batteryLevel: Int,
    val isCharging: Boolean
)

object DeviceInfoUtil {

    fun collect(context: Context): DeviceInfo {
        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) { null }

        return DeviceInfo(
            appVersion = packageInfo?.versionName ?: "1.0.0",
            ipAddress = getIpAddress(),
            wifiSignalStrength = getWifiSignalStrength(context),
            batteryLevel = getBatteryLevel(context),
            isCharging = isCharging(context)
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

    private fun getIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (_: Exception) { null }
    }
}
