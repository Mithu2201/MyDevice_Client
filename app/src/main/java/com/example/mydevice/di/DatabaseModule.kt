package com.example.mydevice.di

import androidx.room.Room
import com.example.mydevice.data.local.database.MyDevicesDatabase
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * Koin module for Room database.
 *
 * WHAT IT PROVIDES:
 * - Single Room database instance (MyDevices.db)
 * - Individual DAOs: UserDao, DeviceStatusLogDao, IncomingMessageDao
 *
 * WHY single: Room databases are expensive to create;
 * one instance is shared across the entire app lifecycle.
 */
val databaseModule = module {

    single {
        Room.databaseBuilder(
            androidContext(),
            MyDevicesDatabase::class.java,
            MyDevicesDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    single { get<MyDevicesDatabase>().userDao() }
    single { get<MyDevicesDatabase>().deviceStatusLogDao() }
    single { get<MyDevicesDatabase>().incomingMessageDao() }
}
