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
 *
 * IMPORTANT — QR / NFC provisioning:
 * After Android Enterprise (fully managed device) QR provisioning, the system
 * calls onProfileProvisioningComplete(). If this is not handled, the Android
 * provisioning watchdog considers setup incomplete and factory-resets the
 * device after a timeout. This override launches MainActivity to finalize setup.
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

    /**
     * Called by the system after QR / NFC fully-managed-device provisioning
     * completes and this app has been set as device owner.
     *
     * MUST be handled: if MainActivity is not launched here, the provisioning
     * watchdog times out and the device performs an automatic factory reset.
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Provisioning complete — launching MainActivity to finish setup")
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
