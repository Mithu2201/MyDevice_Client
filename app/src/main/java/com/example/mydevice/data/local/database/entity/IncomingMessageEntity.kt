package com.example.mydevice.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "incoming_messages")
data class IncomingMessageEntity(
    @PrimaryKey
    val id: String,
    val sendBy: String,
    val description: String,
    val sentAt: String,
    val receivedAt: String,
    val isRead: Boolean = false
)
