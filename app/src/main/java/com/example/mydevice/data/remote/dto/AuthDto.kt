package com.example.mydevice.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val pin: String,
    val deviceId: String
)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val refreshToken: String? = null,
    val userId: Int = 0,
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
