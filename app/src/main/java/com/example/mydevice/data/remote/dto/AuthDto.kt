package com.example.mydevice.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val pin: String,
    val deviceId: String
)

@Serializable
data class DeviceLoginRequest(
    val username: String = "Mithu",
    val password: String = "Mithu@123",
    val macAddress: String
)

@Serializable
data class LoginResponse(
    val accessToken: String? = null,
    val token: String? = null,
    val refreshToken: String? = null,
    val userId: Int = 0,
    val user: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val username: String? = null,
    val email: String? = null
)

@Serializable
data class UserDto(
    val userId: Int,
    val username: String,
    val email: String? = null,
    val phoneNumber: String? = null,
    val pin: String? = null,
    val companyId: Int = 0,
    val isActive: Boolean = true
)
