package com.example.mydevice.data.repository

import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.remote.api.safeApiCall
import com.example.mydevice.data.remote.dto.KioskAppDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Manages the kiosk app whitelist.
 *
 * KIOSK MODE: Only apps in this whitelist can be launched.
 * - Fetched from api/CompanyKioskApp/companies/{id}
 * - Refreshed every 1 hour (by WorkManager) or forced on company update
 * - The app's own package + system keyboard are always whitelisted
 */
class KioskRepository(
    private val api: MyDevicesApi,
    private val appPrefs: AppPreferences
) {
    private val _kioskApps = MutableStateFlow<List<KioskAppDto>>(emptyList())
    val kioskApps = _kioskApps.asStateFlow()

    /** Fetch whitelisted apps from server */
    suspend fun refreshKioskApps(): NetworkResult<List<KioskAppDto>> {
        val companyId = appPrefs.companyId.first()
        val result = safeApiCall { api.getKioskAppsByCompany(companyId) }
        if (result is NetworkResult.Success) {
            _kioskApps.value = result.data
        }
        return result
    }

    /** Check if a given package name is whitelisted */
    fun isAppAllowed(packageName: String): Boolean {
        if (packageName == "com.example.mydevice") return true
        if (packageName.contains("inputmethod")) return true
        return _kioskApps.value.any { it.packageName == packageName && it.isActive }
    }
}
