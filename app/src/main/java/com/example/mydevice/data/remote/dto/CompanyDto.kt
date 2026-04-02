package com.example.mydevice.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class AddDeviceToCompanyRequest(
    val companyId: Int,
    val deviceId: String
)

@Serializable
data class ApiSuccessResponse<T>(
    val success: Boolean = false,
    val message: String? = null,
    val data: T? = null
)

@Serializable
data class CompanyResponse(
    val id: Int = 0,
    val name: String? = null,
    val companyCode: String? = null,
    val isActive: Boolean = true
)

@Serializable
data class KioskAppDto(
    val id: Int = 0,
    val title: String? = null,
    val icon: String? = null,
    val type: String? = null,
    val autoLaunch: Boolean = false,
    val vpnConnect: Boolean = false,
    val visible: Boolean = false,
    val label: String? = null,
    val activity: String? = null,
    val folderId: Int? = null,
    val folderOrder: Int = 0,
    val packageName: String = "",
    val companyId: Int = 0
)
