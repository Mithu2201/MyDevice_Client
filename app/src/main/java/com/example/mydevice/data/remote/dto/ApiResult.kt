package com.example.mydevice.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class ApiResult<T>(
    val isSuccess: Boolean,
    val message: String? = null,
    val data: T? = null,
    val errorCode: Int? = null
)
