package com.example.mydevice.data.repository

import com.example.mydevice.data.local.database.dao.UserDao
import com.example.mydevice.data.local.database.entity.UserEntity
import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.local.preferences.SecurePreferences
import com.example.mydevice.data.remote.api.MyDevicesApi
import com.example.mydevice.data.remote.api.NetworkResult
import com.example.mydevice.data.remote.api.safeApiCall
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
            api.login(LoginRequest(pin = pin, deviceId = deviceId))
        }
        if (result is NetworkResult.Success) {
            securePrefs.accessToken = result.data.accessToken
            securePrefs.refreshToken = result.data.refreshToken
            securePrefs.currentUserId = result.data.userId
            securePrefs.currentUsername = result.data.username
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
}
