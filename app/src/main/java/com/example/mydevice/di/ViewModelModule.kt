package com.example.mydevice.di

import com.example.mydevice.ui.checkin.CheckInViewModel
import com.example.mydevice.ui.charging.ChargingViewModel
import com.example.mydevice.ui.kiosk.KioskViewModel
import com.example.mydevice.ui.messages.MessagesViewModel
import com.example.mydevice.ui.settings.SettingsViewModel
import com.example.mydevice.ui.splash.SplashViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin module for all ViewModels.
 *
 * WHY viewModel { } instead of single { }:
 * - viewModel scope ties to the Android lifecycle
 * - Survives configuration changes (rotation) but cleans up on Activity destroy
 * - Koin handles SavedStateHandle injection automatically
 */
val viewModelModule = module {
    viewModel { SplashViewModel(get(), get(), get()) }
    viewModel { CheckInViewModel(get(), get(), get()) }
    viewModel { KioskViewModel(get(), get(), get(), get(), androidContext()) }
    viewModel { ChargingViewModel(get(), get()) }
    viewModel { MessagesViewModel(get()) }
    viewModel { SettingsViewModel(get(), get()) }
}
