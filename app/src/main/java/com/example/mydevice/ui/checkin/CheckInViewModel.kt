package com.example.mydevice.ui.checkin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.repository.AuthRepository
import com.example.mydevice.data.repository.DeviceRepository
import com.example.mydevice.util.Constants
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Drives the Check-In (PIN login) screen.
 *
 * LOGIN STRATEGY:
 * 1. Try online login via REST API (api/Authentication/login)
 * 2. If network fails → fallback to offline PIN check against Room-cached users
 * 3. On success → sync users list, log event, navigate to kiosk
 * 4. On failure → show error message
 */
data class CheckInUiState(
    val pin: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginSuccess: Boolean = false,
    val username: String? = null
)

class CheckInViewModel(
    private val authRepo: AuthRepository,
    private val deviceRepo: DeviceRepository,
    private val appPrefs: AppPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckInUiState())
    val uiState: StateFlow<CheckInUiState> = _uiState.asStateFlow()

    fun onPinChanged(pin: String) {
        _uiState.value = _uiState.value.copy(pin = pin, error = null)
    }

    fun login() {
        val pin = _uiState.value.pin
        if (pin.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Please enter your PIN")
            return
        }

        // TODO: Remove this test bypass before production release
        if (pin == TEST_PIN) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loginSuccess = true,
                username = "Test User"
            )
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val deviceId = deviceRepo.getStableDeviceId()

            when (val result = authRepo.login(pin, deviceId)) {
                is NetworkResult.Success -> {
                    authRepo.syncUsers()
                    deviceRepo.logEvent(Constants.EventTypes.LOGIN, "User logged in")
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loginSuccess = true,
                        username = result.data.username
                    )
                }
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                is NetworkResult.NoInternet -> {
                    attemptOfflineLogin(pin)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    private suspend fun attemptOfflineLogin(pin: String) {
        val user = authRepo.offlineLogin(pin)
        if (user != null) {
            deviceRepo.logEvent(Constants.EventTypes.LOGIN, "Offline login: ${user.username}")
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                loginSuccess = true,
                username = user.username
            )
        } else {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Invalid PIN (offline mode — user not cached)"
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            deviceRepo.logEvent(Constants.EventTypes.LOGOUT, "User logged out")
        }
        authRepo.logout()
        _uiState.value = CheckInUiState()
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    companion object {
        // Dummy PIN for testing without a server — enter "1234" to bypass login
        private const val TEST_PIN = "1234"
    }
}
