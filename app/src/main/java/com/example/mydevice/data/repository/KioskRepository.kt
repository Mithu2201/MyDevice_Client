package com.example.mydevice.data.repository

import android.util.Log
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.remote.api.safeApiCall
import com.example.mydevice.data.remote.dto.KioskAppDto
import com.example.mydevice.util.Constants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Manages the kiosk app whitelist fetched from the admin panel.
 *
 * API: GET api/CompanyKioskApp/companies/{companyId}
 * Response wraps approved apps with fields: title, icon, packageName,
 * autoLaunch, visible, label, etc.
 *
 * Only apps returned by this API are shown and launchable in kiosk mode.
 */
class KioskRepository(
    private val api: MyDevicesApi,
    private val appPrefs: AppPreferences
) {
    private val _kioskApps = MutableStateFlow<List<KioskAppDto>>(emptyList())
    val kioskApps = _kioskApps.asStateFlow()

    /** Fetch approved kiosk apps from server */
    suspend fun refreshKioskApps(): NetworkResult<List<KioskAppDto>> {
        val companyId = appPrefs.companyId.first()
        if (companyId <= 0) {
            Log.w(TAG, "Cannot fetch kiosk apps: invalid companyId=$companyId")
            return NetworkResult.Error("Company not registered")
        }
        Log.i(TAG, "Fetching kiosk apps for companyId=$companyId")
        val result = safeApiCall { api.getKioskAppsByCompany(companyId) }
        if (result is NetworkResult.Success) {
            _kioskApps.value = result.data
            Log.i(TAG, "Loaded ${result.data.size} kiosk apps from server")
            result.data.forEach { app ->
                Log.d(TAG, "  app: id=${app.id}, pkg=${app.packageName}, title=${app.title}, visible=${app.visible}, autoLaunch=${app.autoLaunch}")
            }
        } else {
            Log.w(TAG, "Failed to fetch kiosk apps: $result")
        }
        return result
    }

    /** Check if a given package name is in the approved list */
    fun isAppAllowed(packageName: String): Boolean {
        if (packageName == "com.example.mydevice") return true
        if (packageName.contains("inputmethod")) return true
        if (packageName.contains("keyboard", ignoreCase = true)) return true
        return _kioskApps.value.any {
            it.packageName.equals(packageName, ignoreCase = true)
        }
    }

    /**
     * Builds the public URL for a kiosk icon.
     * API often returns `Images/Icon/file.png` but the server serves files under
     * `Resources/Images/Icon/file.png` (full URL example:
     * https://host/Resources/Images/Icon/xxx.png).
     */
    fun getIconUrl(relativePath: String?): String? {
        if (relativePath.isNullOrBlank()) return null
        val trimmed = relativePath.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        var path = trimmed.trimStart('/')
        if (!path.startsWith("Resources/", ignoreCase = true)) {
            path = "Resources/$path"
        }
        val base = Constants.BASE_URL.trimEnd('/')
        return "$base/$path"
    }

    companion object {
        private const val TAG = "KioskRepository"
    }
}
