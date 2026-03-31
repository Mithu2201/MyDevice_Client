package com.example.mydevice.service.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mydevice.MainActivity

/**
 * BroadcastReceiver that starts the app when the device boots.
 *
 * WHY: Enterprise devices need the MDM app to start automatically
 * after reboot without user interaction.
 *
 * DECLARED in AndroidManifest.xml with BOOT_COMPLETED permission.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Device booted — starting MyDevice")
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(launchIntent)
        }
    }
}
