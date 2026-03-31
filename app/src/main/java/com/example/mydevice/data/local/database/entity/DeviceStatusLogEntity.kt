package com.example.mydevice.data.local.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_status_log")
data class DeviceStatusLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val description: String,
    val type: String,
    val time: String
)
