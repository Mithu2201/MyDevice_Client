package com.example.mydevice.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.example.mydevice.data.local.database.dao.DeviceStatusLogDao
import com.example.mydevice.data.local.database.dao.IncomingMessageDao
import com.example.mydevice.data.local.database.dao.UserDao
import com.example.mydevice.data.local.database.entity.DeviceStatusLogEntity
import com.example.mydevice.data.local.database.entity.IncomingMessageEntity
import com.example.mydevice.data.local.database.entity.UserEntity

@Database(
    entities = [
        UserEntity::class,
        DeviceStatusLogEntity::class,
        IncomingMessageEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class MyDevicesDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun deviceStatusLogDao(): DeviceStatusLogDao
    abstract fun incomingMessageDao(): IncomingMessageDao

    companion object {
        const val DATABASE_NAME = "mydevices.db"
    }
}
