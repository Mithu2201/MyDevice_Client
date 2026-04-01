package com.example.mydevice.data.repository

import com.example.mydevice.data.local.database.dao.UserDao
import com.example.mydevice.data.local.database.entity.UserEntity
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.local.preferences.SecurePreferences
import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.remote.api.safeApiCall
import com.example.mydevice.data.remote.dto.DeviceLoginRequest
import com.example.mydevice.data.remote.dto.LoginRequest
import com.example.mydevice.data.remote.dto.LoginResponse
import com.example.mydevice.data.remote.dto.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Handles login, token management, and user caching.
 *
 * LOGIN FLOW:
 * 1. Try online PIN login → api/Authentication/login
 * 2. On success → store token in EncryptedSharedPreferences, cache users in Room
 * 3. On network failure → attempt offline login against cached PINs in Room DB
 */
class AuthRepository(
    private val api: MyDevicesApi,
    private val userDao: UserDao,
    private val securePrefs: SecurePreferences,
    private val appPrefs: AppPreferences
) {
    /** Online PIN login via REST API */
    suspend fun login(pin: String, deviceId: String): NetworkResult<LoginResponse> {
        val result = safeApiCall {
            val response = api.login(LoginRequest(pin = pin, deviceId = deviceId))
            response.data ?: throw IllegalStateException(
                response.message ?: "Login response did not include token data"
            )
        }
        if (result is NetworkResult.Success) {
            saveLogin(result.data)
        }
        return result
    }

    /**
     * Device/service authentication used for kiosk-only devices that do not
     * expose a user PIN login. The backend issues a JWT based on macAddress.
     */
    suspend fun loginDevice(macAddress: String): NetworkResult<LoginResponse> {
        val result = safeApiCall {
            val response = api.deviceLogin(DeviceLoginRequest(macAddress = macAddress))
            response.data ?: throw IllegalStateException(
                response.message ?: "Device login response did not include token data"
            )
        }
        if (result is NetworkResult.Success) {
            saveLogin(result.data)
        }
        return result
    }

    /** Offline PIN login against Room-cached users */
    suspend fun offlineLogin(pin: String): UserEntity? {
        val companyId = appPrefs.companyId.first()
        return userDao.findByPin(pin, companyId)
    }

    /** Download all company users from server and cache in Room */
    suspend fun syncUsers(): NetworkResult<List<UserDto>> {
        val companyId = appPrefs.companyId.first()
        val result = safeApiCall { api.getUsersByCompany(companyId) }
        if (result is NetworkResult.Success) {
            val entities = result.data.map { dto ->
                UserEntity(
                    userId = dto.userId,
                    username = dto.username,
                    email = dto.email,
                    phoneNumber = dto.phoneNumber,
                    pin = dto.pin,
                    companyId = dto.companyId,
                    isActive = dto.isActive
                )
            }
            userDao.deleteByCompany(companyId)
            userDao.insertAll(entities)
        }
        return result
    }

    fun getLocalUsers(companyId: Int): Flow<List<UserEntity>> =
        userDao.getUsersByCompany(companyId)

    fun hasToken(): Boolean = securePrefs.accessToken != null

    fun logout() {
        securePrefs.clearTokens()
        securePrefs.clearUser()
    }

    fun getCurrentUsername(): String? = securePrefs.currentUsername

    private fun saveLogin(response: LoginResponse) {
        val accessToken = response.accessToken ?: response.token
        require(!accessToken.isNullOrBlank()) { "Login token is missing in response" }
        securePrefs.accessToken = accessToken
        securePrefs.refreshToken = response.refreshToken
        securePrefs.currentUserId = response.userId
        securePrefs.currentUsername = response.username ?: response.user ?: "device"
    }
}
