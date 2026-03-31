package com.example.mydevice.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class AddDeviceToCompanyRequest(
    val macAddress: String,
    val companyCode: String,
    val deviceModel: String? = null
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
    val packageName: String,
    val appName: String? = null,
    val iconUrl: String? = null,
    val companyId: Int = 0,
    val isActive: Boolean = true
)
