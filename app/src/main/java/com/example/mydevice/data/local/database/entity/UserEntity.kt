package com.example.mydevice.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    val userId: Int,
    val username: String,
    val email: String? = null,
    val phoneNumber: String? = null,
    val pin: String? = null,
    val companyId: Int = 0,
    val isActive: Boolean = true
)
