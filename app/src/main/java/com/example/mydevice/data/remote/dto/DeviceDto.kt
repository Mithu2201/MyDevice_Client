package com.example.mydevice.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class DeviceRequest(
    val macAddress: String,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val ipAddress: String? = null,
    val wifiSignalStrength: Int? = null,
    val batteryLevel: Int? = null,
    val isCharging: Boolean = false,
    val installedApps: String? = null,
    val companyId: Int = 0,
    val lastSeen: String? = null
)

@Serializable
data class DeviceResponse(
    val id: Int = 0,
    val macAddress: String? = null,
    val deviceModel: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
    val ipAddress: String? = null,
    val companyId: Int = 0,
    val lastSeen: String? = null,
    val isOnline: Boolean = false
)

@Serializable
data class DeviceConfigurationResponse(
    val deviceId: String? = null,
    val showCheckInView: Boolean = true,
    val showChargingView: Boolean = true,
    val inactivityTimeoutMinutes: Int = 5,
    val statusUpdateIntervalMinutes: Int = 15,
    val enableHttpLogging: Boolean = false,
    val enableVersionUpdate: Boolean = false
)

@Serializable
data class DeviceConfigDto(
    val id: Int = 0,
    val companyId: Int = 0,
    val name: String? = null,
    val description: String? = null
)

@Serializable
data class DeviceConfigFilePathDto(
    val id: Int = 0,
    val deviceConfigId: Int = 0,
    val filePath: String? = null,
    val fileName: String? = null
)

@Serializable
data class DeviceFileRequest(
    val deviceId: String,
    val fileName: String,
    val filePath: String,
    val fileSize: Long = 0,
    val downloadedAt: String? = null
)

@Serializable
data class DeviceStatusLogRequest(
    val description: String,
    val type: String,
    val time: String
)
