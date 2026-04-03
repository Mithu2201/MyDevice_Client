package com.example.mydevice.service.device

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mydevice.MainActivity

/**
 * Device Admin receiver for DevicePolicyManager integration.
 *
 * IMPORTANT — QR / NFC provisioning:
 * [onProfileProvisioningComplete] must return quickly and start an activity.
 * **Do not** perform network I/O or heavy work here: blocking the main thread can
 * trigger the provisioning watchdog and a factory reset.
 *
 * Company enrollment is handled on the splash screen (user enters numeric company ID).
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

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Provisioning complete — launching MainActivity (no blocking work here)")
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        }
        context.startActivity(launchIntent)
    }

    companion object {
        private const val TAG = "DeviceAdmin"

        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, MyDeviceAdminReceiver::class.java)
    }
}
