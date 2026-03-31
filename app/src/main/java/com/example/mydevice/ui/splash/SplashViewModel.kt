package com.example.mydevice.ui.splash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.repository.CompanyRepository
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.util.Constants
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Drives the splash/registration screen.
 *
 * STATE MACHINE:
 * Loading → CheckingRegistration → (already registered?) → NavigateToMain
 *                                → (not registered?) → ShowCompanyCodeInput
 *                                → (auto-register success?) → NavigateToMain
 * ShowCompanyCodeInput → user enters code → Registering → NavigateToMain / Error
 */
data class SplashUiState(
    val isLoading: Boolean = true,
    val isRegistered: Boolean = false,
    val showCompanyCodeInput: Boolean = false,
    val isRegistering: Boolean = false,
    val error: String? = null,
    val navigateToMain: Boolean = false
)

class SplashViewModel(
    private val companyRepo: CompanyRepository,
    private val deviceRepo: DeviceRepository,
    private val appPrefs: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SplashUiState())
    val uiState: StateFlow<SplashUiState> = _uiState.asStateFlow()

    init {
        checkRegistration()
    }

    /** Step 1: Check if device is already registered with a company */
    private fun checkRegistration() {
        viewModelScope.launch {
            val companyId = appPrefs.companyId.first()
            if (companyId != Constants.DEFAULT_COMPANY_ID) {
                loadRemoteConfigAndProceed()
            } else {
                tryAutoRegister()
            }
        }
    }

    /** Step 2: Try auto-register by device ID */
    private suspend fun tryAutoRegister() {
        val deviceId = deviceRepo.getDeviceId()
        appPrefs.setDeviceId(deviceId)

        when (val result = companyRepo.autoRegister(deviceId)) {
            is NetworkResult.Success -> loadRemoteConfigAndProceed()
            is NetworkResult.Error,
            is NetworkResult.NoInternet -> {
                _uiState.value = SplashUiState(
                    isLoading = false,
                    showCompanyCodeInput = true
                )
            }
            is NetworkResult.Loading -> {}
        }
    }

    /** Step 3 (manual path): User enters a company code */
    fun registerWithCode(companyCode: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRegistering = true, error = null)
            val deviceId = deviceRepo.getDeviceId()

            when (val result = companyRepo.registerWithCode(deviceId, companyCode)) {
                is NetworkResult.Success -> loadRemoteConfigAndProceed()
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isRegistering = false,
                        error = result.message
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

    /** Step 4: Load remote config then navigate to main screen */
    private suspend fun loadRemoteConfigAndProceed() {
        deviceRepo.updateDeviceDetails()
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
}
