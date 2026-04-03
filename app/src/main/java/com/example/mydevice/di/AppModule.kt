package com.example.mydevice.di

import com.example.mydevice.data.local.preferences.AppPreferences
import com.example.mydevice.data.local.preferences.SecurePreferences
import com.example.mydevice.data.remote.signalr.DeviceHubConnection
import com.example.mydevice.data.repository.*
import com.example.mydevice.service.device.DevicePolicyHelper
import com.example.mydevice.service.script.RemoteScriptExecutor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appModule = module {

    // ── Preferences ─────────────────────────────────────────────────────────
    single { SecurePreferences(androidContext()) }
    single { AppPreferences(androidContext()) }

    // ── Device Policy ───────────────────────────────────────────────────────
    single { DevicePolicyHelper(androidContext()) }

    // ── SignalR ─────────────────────────────────────────────────────────────
    single { DeviceHubConnection(get()) }
    single { RemoteScriptExecutor(androidContext(), get(), get(), get()) }

    // ── Repositories ────────────────────────────────────────────────────────
    single { AuthRepository(get(), get(), get(), get()) }
    single { DeviceRepository(androidContext(), get(), get(), get()) }
    single { CompanyRepository(get(), get()) }
    single { KioskRepository(get(), get()) }
    single { MessageRepository(get(), get()) }
    single { ConfigFileRepository(androidContext(), get(), get()) }
}
