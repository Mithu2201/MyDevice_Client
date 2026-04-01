package com.example.mydevice.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.example.mydevice.ui.charging.ChargingScreen
import com.example.mydevice.ui.kiosk.KioskScreen
import com.example.mydevice.ui.messages.MessagesScreen
import com.example.mydevice.ui.settings.SettingsScreen
import com.example.mydevice.ui.splash.SplashScreen

/**
 * Navigation routes for the app.
 *
 * FLOW:
 * Splash → (registered?) → Kiosk
 * Kiosk → Messages / Settings / Charging
 * Any screen → (charger plugged in) → Charging
 * Charging → (unplugged) → back to previous
 */
object Routes {
    const val SPLASH = "splash"
    const val KIOSK = "kiosk"
    const val CHARGING = "charging"
    const val MESSAGES = "messages"
    const val SETTINGS = "settings"
}

@Composable
fun AppNavigation(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onNavigateToKiosk = {
                    navController.navigate(Routes.KIOSK) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.KIOSK) {
            KioskScreen(
                onNavigateToMessages = { navController.navigate(Routes.MESSAGES) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToCharging = { navController.navigate(Routes.CHARGING) },
                onLogout = {
                    navController.navigate(Routes.SPLASH) {
                        popUpTo(Routes.KIOSK) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CHARGING) {
            ChargingScreen(
                onDismiss = { navController.popBackStack() }
            )
        }

        composable(Routes.MESSAGES) {
            MessagesScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
