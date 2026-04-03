package com.example.mydevice.service.device

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import com.example.mydevice.service.kiosk.KioskLockService

/**
 * Helper for DevicePolicyManager operations and kiosk enforcement.
 *
 * TWO ENFORCEMENT LEVELS:
 *
 * 1) DEVICE OWNER (strongest — provisioned via ADB or NFC):
 *    - Lock task mode with no navigation bars
 *    - Hide/disable non-whitelisted apps from the system
 *    - Block status bar, recent apps, home button
 *    - Disable safe boot, USB, factory reset
 *
 * 2) DEVICE ADMIN + SERVICE (fallback for non-device-owner):
 *    - KioskLockService monitors foreground app and pulls user back
 *    - App set as default launcher (HOME intent) so Home button → our app
 *    - Immersive sticky mode hides navigation bars
 *    - Back button overridden in MainActivity
 *
 * Compatible with Android 8.1 (API 27) — Vivo 1820 target device.
 */
class DevicePolicyHelper(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val adminComponent: ComponentName =
        MyDeviceAdminReceiver.getComponentName(context)

    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    fun createAdminActivationIntent(): Intent {
        return Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "MyDevice needs device admin access for enterprise management features."
            )
        }
    }

    fun lockScreen() {
        if (isAdminActive()) {
            dpm.lockNow()
            Log.i(TAG, "Screen locked")
        }
    }

    fun rebootDevice() {
        if (isDeviceOwner()) {
            dpm.reboot(adminComponent)
            Log.i(TAG, "Device rebooting")
        } else {
            Log.w(TAG, "Cannot reboot — not device owner")
        }
    }

    // ── Lock Task Mode (Device Owner only) ──────────────────────────────────

    fun startLockTaskMode(activity: Activity, allowedPackages: List<String>) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot start lock task — not device owner. Using service-based kiosk.")
            return
        }

        val packages = buildList {
            add(context.packageName)
            add("com.android.settings")
            addAll(allowedPackages)
        }.distinct().toTypedArray()

        try {
            dpm.setLockTaskPackages(adminComponent, packages)

            // LOCK_TASK_FEATURE_NONE (API 28+) strips all system chrome from the
            // screen while in lock task mode: navigation bar, status bar, home
            // button, recents, and the global actions menu are all hidden at the
            // OS level — stronger than immersive mode alone.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_NONE
                )
            }

            activity.startLockTask()
            Log.i(TAG, "Lock task mode started with ${packages.size} packages")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start lock task mode", e)
        }
    }

    fun stopLockTaskMode(activity: Activity) {
        try {
            activity.stopLockTask()
            // Restore default lock task features when leaving kiosk mode.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(
                    adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
                )
            }
            Log.i(TAG, "Lock task mode stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop lock task", e)
        }
    }

    // ── App Hiding (Device Owner only) ──────────────────────────────────────

    /**
     * Hide all installed apps except the whitelisted ones.
     * Hidden apps remain installed but become invisible and unlanchable.
     */
    fun hideNonWhitelistedApps(whitelistedPackages: Set<String>) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot hide apps — not device owner")
            return
        }

        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val alwaysAllowed = buildSet {
            add(context.packageName)
            add("com.android.systemui")
            add("com.android.settings")
            add("com.android.providers.settings")
            add("com.android.shell")
            add("android")
            addAll(whitelistedPackages)
        }

        var hiddenCount = 0
        for (app in installedApps) {
            if (alwaysAllowed.contains(app.packageName)) continue
            if (app.packageName.startsWith("com.android.inputmethod")) continue
            if (app.packageName.contains("keyboard", ignoreCase = true)) continue
            if (app.packageName.contains("inputmethod", ignoreCase = true)) continue

            try {
                val wasHidden = dpm.isApplicationHidden(adminComponent, app.packageName)
                if (!wasHidden) {
                    dpm.setApplicationHidden(adminComponent, app.packageName, true)
                    hiddenCount++
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hide ${app.packageName}", e)
            }
        }
        Log.i(TAG, "Hidden $hiddenCount non-whitelisted apps")
    }

    fun unhideApp(packageName: String) {
        if (!isDeviceOwner()) return
        try {
            dpm.setApplicationHidden(adminComponent, packageName, false)
            Log.i(TAG, "Unhid app: $packageName")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unhide $packageName", e)
        }
    }

    fun unhideAllApps() {
        if (!isDeviceOwner()) return
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in apps) {
            try {
                if (dpm.isApplicationHidden(adminComponent, app.packageName)) {
                    dpm.setApplicationHidden(adminComponent, app.packageName, false)
                }
            } catch (_: Exception) {}
        }
        Log.i(TAG, "All apps unhidden")
    }

    // ── Device Restrictions (Device Owner only) ─────────────────────────────

    /**
     * Temporarily allow APK installs while kiosk restrictions are active.
     * Called by ScriptRepository before silent install, restored after.
     */
    fun allowInstalls() {
        if (!isDeviceOwner()) return
        try {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            Log.i(TAG, "DISALLOW_INSTALL_APPS cleared for script install")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear install restriction", e)
        }
    }

    fun blockInstalls() {
        if (!isDeviceOwner()) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            Log.i(TAG, "DISALLOW_INSTALL_APPS re-applied after script install")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to re-apply install restriction", e)
        }
    }

    fun applyKioskRestrictions() {
        if (!isDeviceOwner()) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS)
            }

            // Block the user from expanding the status bar / notification panel.
            // This prevents access to quick settings and notifications from the top bar.
            dpm.setStatusBarDisabled(adminComponent, true)

            // Keep the screen on while the device is plugged in (AC=1, USB=2, Wireless=4).
            // Value 7 = all charging sources combined. Paired with FLAG_KEEP_SCREEN_ON in
            // the activity window this fully prevents the kiosk screen from sleeping.
            dpm.setGlobalSetting(adminComponent, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "7")

            Log.i(TAG, "Kiosk restrictions applied")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply kiosk restrictions", e)
        }
    }

    fun clearKioskRestrictions() {
        if (!isDeviceOwner()) return
        try {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_ADD_USER)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_USB_FILE_TRANSFER)

            // Re-enable the status bar and restore normal screen-on behaviour.
            dpm.setStatusBarDisabled(adminComponent, false)
            dpm.setGlobalSetting(adminComponent, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0")

            Log.i(TAG, "Kiosk restrictions cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear kiosk restrictions", e)
        }
    }

    // ── Camera Control ──────────────────────────────────────────────────────

    fun setCameraDisabled(disabled: Boolean) {
        if (isAdminActive()) {
            dpm.setCameraDisabled(adminComponent, disabled)
            Log.i(TAG, "Camera disabled: $disabled")
        }
    }

    // ── Remote Wipe ─────────────────────────────────────────────────────────

    fun wipeDevice(reason: String = "Remote wipe requested") {
        if (isAdminActive()) {
            Log.w(TAG, "WIPING DEVICE: $reason")
            dpm.wipeData(0)
        }
    }

    // ── Screen Lock ─────────────────────────────────────────────────────────

    fun setMaximumScreenLockTimeout(timeoutMs: Long) {
        if (isAdminActive()) {
            dpm.setMaximumTimeToLock(adminComponent, timeoutMs)
        }
    }

    // ── Lock Task Packages ──────────────────────────────────────────────────

    fun setAllowedLockTaskPackages(packages: Array<String>) {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Cannot set allowed packages — not device owner")
            return
        }
        try {
            dpm.setLockTaskPackages(adminComponent, packages)
            Log.i(TAG, "Allowed lock task packages: ${packages.joinToString()}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set lock task packages", e)
        }
    }

    // ── App Launching ───────────────────────────────────────────────────────

    fun launchApp(packageName: String) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            Log.i(TAG, "Launched: $packageName")
        } else {
            Log.w(TAG, "Package not found: $packageName")
        }
    }

    // ── Kiosk Service Control ───────────────────────────────────────────────

    fun startKioskService(allowedPackages: List<String>) {
        KioskLockService.start(context, allowedPackages)
    }

    fun stopKioskService() {
        KioskLockService.stop(context)
    }

    fun updateKioskWhitelist(allowedPackages: List<String>) {
        KioskLockService.updateWhitelist(context, allowedPackages)
    }

    // ── Utility ─────────────────────────────────────────────────────────────

    fun needsUsageStatsPermission(): Boolean {
        return !KioskLockService.hasUsageStatsPermission(context)
    }

    fun openUsageAccessSettings(activity: Activity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open usage access settings", e)
        }
    }

    /**
     * Full kiosk activation: applies all available enforcement based on
     * whether this app is a device owner or just a device admin.
     */
    fun activateFullKiosk(activity: Activity, whitelistedPackages: List<String>) {
        val packageSet = whitelistedPackages.toSet()

        if (isDeviceOwner()) {
            Log.i(TAG, "Activating DEVICE OWNER kiosk mode")
            applyKioskRestrictions()
            hideNonWhitelistedApps(packageSet)
            startLockTaskMode(activity, whitelistedPackages)
        } else {
            Log.i(TAG, "Activating SERVICE-BASED kiosk mode (not device owner)")
        }

        startKioskService(whitelistedPackages)
    }

    fun deactivateKiosk(activity: Activity) {
        stopKioskService()
        if (isDeviceOwner()) {
            clearKioskRestrictions()
            unhideAllApps()
            stopLockTaskMode(activity)
        }
        Log.i(TAG, "Kiosk mode deactivated")
    }

    companion object {
        private const val TAG = "DevicePolicyHelper"
    }
}
