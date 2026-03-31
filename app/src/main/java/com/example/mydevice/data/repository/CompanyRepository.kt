package com.example.mydevice.data.repository

import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.remote.api.safeApiCall
import com.example.mydevice.data.remote.dto.AddDeviceToCompanyRequest
import com.example.mydevice.data.remote.dto.CompanyResponse

/**
 * Handles device ↔ company registration (licensee management).
 *
 * REGISTRATION FLOW:
 * 1. First try auto-register by MAC/device ID → getCompanyByDeviceId()
 * 2. If that fails, user enters a company code → addDeviceToCompany()
 * 3. On success, company ID is stored in DataStore
 */
class CompanyRepository(
    private val api: MyDevicesApi,
    private val appPrefs: AppPreferences
) {
    /** Try auto-registration by device ID (MAC address / ANDROID_ID) */
    suspend fun autoRegister(deviceId: String): NetworkResult<CompanyResponse> {
        val result = safeApiCall { api.getCompanyByDeviceId(deviceId) }
        if (result is NetworkResult.Success) {
            saveCompany(result.data)
        }
        return result
    }

    /** Register device with company using a company code */
    suspend fun registerWithCode(
        deviceId: String,
        companyCode: String,
        deviceModel: String? = null
    ): NetworkResult<CompanyResponse> {
        val result = safeApiCall {
            api.addDeviceToCompany(
                AddDeviceToCompanyRequest(
                    macAddress = deviceId,
                    companyCode = companyCode,
                    deviceModel = deviceModel
                )
            )
        }
        if (result is NetworkResult.Success) {
            saveCompany(result.data)
        }
        return result
    }

    private suspend fun saveCompany(company: CompanyResponse) {
        appPrefs.setCompanyId(company.id)
        appPrefs.setCompanyName(company.name ?: "")
    }
}
