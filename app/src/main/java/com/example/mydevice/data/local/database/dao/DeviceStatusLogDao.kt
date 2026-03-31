package com.example.mydevice.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.mydevice.data.local.database.entity.DeviceStatusLogEntity

@Dao
interface DeviceStatusLogDao {

    @Query("SELECT * FROM device_status_log ORDER BY id ASC")
    suspend fun getAll(): List<DeviceStatusLogEntity>

    @Query("SELECT * FROM device_status_log ORDER BY id ASC LIMIT :limit")
    suspend fun getBatch(limit: Int = 50): List<DeviceStatusLogEntity>

    @Insert
    suspend fun insert(log: DeviceStatusLogEntity): Long

    @Query("DELETE FROM device_status_log WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM device_status_log")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM device_status_log")
    suspend fun count(): Int
}
