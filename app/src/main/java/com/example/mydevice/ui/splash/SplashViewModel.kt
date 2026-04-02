package com.example.mydevice.ui.splash

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.repository.AuthRepository
import com.example.mydevice.data.repository.CompanyRepository
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.data.local.preferences.AppPreferences
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Drives the splash / device registration screen.
 *
 * Enrollment uses the numeric **company ID** only (same value as the admin portal / backend).
 * After QR provisioning, the user enters company ID here if auto-lookup by device fails.
 */
data class SplashUiState(
    val isLoading: Boolean = true,
    val isRegistered: Boolean = false,
    val showCompanyIdInput: Boolean = false,
    /** When showing the registration card, pre-fill from prefs if a company ID was already stored */
    val companyIdPrefill: String = "",
    val isRegistering: Boolean = false,
    val error: String? = null,
    val navigateToMain: Boolean = false
)

class SplashViewModel(
    private val companyRepo: CompanyRepository,
    private val deviceRepo: DeviceRepository,
    private val appPrefs: AppPreferences,
    private val authRepo: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        checkRegistration()
    }

    private fun checkRegistration() {
        viewModelScope.launch {
            val companyId = appPrefs.companyId.first()
            // Must use > 0: companyId 0 is invalid but (0 != -1) was true and skipped enrollment → kiosk showed "Company not registered"
            if (companyId > 0) {
                loadRemoteConfigAndProceed()
            } else {
                tryAutoRegister()
            }
        }
    }

    private suspend fun tryAutoRegister() {
        val deviceId = deviceRepo.getStableDeviceId()
        appPrefs.setDeviceId(deviceId)

        when (companyRepo.autoRegister(deviceId)) {
            is NetworkResult.Success -> loadRemoteConfigAndProceed()
            is NetworkResult.Error,
            is NetworkResult.NoInternet -> {
                showRegistrationCard(prefill = "", error = null)
            }
            is NetworkResult.Loading -> {}
        }
    }

    private suspend fun showRegistrationCard(prefill: String, error: String?) {
        val fromPrefs = appPrefs.companyId.first()
        val resolvedPrefill = when {
            prefill.isNotEmpty() -> prefill
            fromPrefs > 0 -> fromPrefs.toString()
            else -> ""
        }
        _uiState.value = SplashUiState(
            isLoading = false,
            showCompanyIdInput = true,
            companyIdPrefill = resolvedPrefill,
            error = error
        )
    }

    fun registerWithCompanyId(companyIdText: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRegistering = true, error = null)
            val deviceId = deviceRepo.getStableDeviceId()
            appPrefs.setDeviceId(deviceId)
            val companyId = companyIdText.toIntOrNull()
            if (companyId == null || companyId <= 0) {
                _uiState.value = _uiState.value.copy(
                    isRegistering = false,
                    error = "Enter a valid company ID (positive number)"
                )
                return@launch
            }

            when (val result = companyRepo.registerWithCompanyId(deviceId, companyId)) {
                is NetworkResult.Success -> loadRemoteConfigAndProceed()
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isRegistering = false,
                        error = result.message.ifBlank { "Registration failed" }
                    )
                }
                is NetworkResult.NoInternet -> {
                    _uiState.value = _uiState.value.copy(
                        isRegistering = false,
                        error = "No internet connection"
                    )
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    private suspend fun loadRemoteConfigAndProceed() {
        val deviceId = deviceRepo.getStableDeviceId()
        val authResult = authRepo.loginDevice(deviceId)
        Log.i(TAG, "Device login result: $authResult")
        if (authResult is NetworkResult.Error) {
            showRegistrationCard(
                prefill = appPrefs.companyId.first().takeIf { it > 0 }?.toString().orEmpty(),
                error = authResult.message
            )
            return
        }
        if (authResult is NetworkResult.NoInternet) {
            showRegistrationCard(prefill = "", error = "No internet connection")
            return
        }

        val updateDeviceResult = deviceRepo.updateDeviceDetails()
        Log.i(TAG, "Device telemetry update result: $updateDeviceResult")
        if (updateDeviceResult is NetworkResult.Error) {
            showRegistrationCard(
                prefill = appPrefs.companyId.first().takeIf { it > 0 }?.toString().orEmpty(),
                error = updateDeviceResult.message
            )
            return
        }
        if (updateDeviceResult is NetworkResult.NoInternet) {
            showRegistrationCard(prefill = "", error = "No internet connection")
            return
        }

        val enrolledCompanyId = appPrefs.companyId.first()
        val backendDeviceId = appPrefs.serverDeviceId.first()
        Log.i(TAG, "After telemetry: companyId=$enrolledCompanyId, serverDeviceId=$backendDeviceId")
        if (enrolledCompanyId <= 0) {
            showRegistrationCard(
                prefill = "",
                error = "Company ID missing. Enter your numeric company ID."
            )
            return
        }
        // Telemetry already succeeded; some APIs omit numeric id or use names we don't parse — still allow kiosk
        if (backendDeviceId <= 0) {
            Log.w(
                TAG,
                "serverDeviceId is 0 after successful POST api/Device; continuing (hub may use MAC until id is returned)"
            )
        }

        deviceRepo.loadRemoteConfig()
        _uiState.value = SplashUiState(
            isLoading = false,
            isRegistered = true,
            navigateToMain = true
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    companion object {
        private const val TAG = "SplashViewModel"
    }
}
