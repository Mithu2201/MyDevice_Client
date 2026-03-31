package com.example.mydevice.service.device

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Admin receiver for DevicePolicyManager integration.
 *
 * WHAT THIS ENABLES:
 * - Lock screen enforcement (force PIN/password)
 * - Camera disable (if enterprise policy requires)
 * - Device wipe (remote wipe via server command)
 * - Lock task mode (true kiosk pinning)
 *
 * HOW IT WORKS:
 * 1. Declared in AndroidManifest.xml with device_admin_policies meta-data
 * 2. User/IT admin activates it via Settings → Security → Device Admins
 * 3. Or provisioned automatically via QR code / NFC during device setup
 * 4. Once active, DevicePolicyManager APIs become available
 */
class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled")
    }

    override fun onPasswordChanged(context: Context, intent: Intent, userHandle: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, userHandle)
        Log.i(TAG, "Device password changed")
    }

    companion object {
        private const val TAG = "DeviceAdmin"

        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, MyDeviceAdminReceiver::class.java)
    }
}
