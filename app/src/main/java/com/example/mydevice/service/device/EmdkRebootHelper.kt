package com.example.mydevice.service.device

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.symbol.emdk.EMDKManager
import com.symbol.emdk.EMDKResults
import com.symbol.emdk.ProfileManager
import java.util.concurrent.Executors

/**
 * Applies the same PowerMgr reboot profile used in mydevicesandroid [EMDKmanager.reboot]:
 * SignalR "Reboot" → processProfile SET with ResetAction=4 on Zebra hardware.
 *
 * If EMDK is unavailable or processing fails, the caller should fall back to
 * [DevicePolicyHelper.rebootDevice] when device owner.
 */
object EmdkRebootHelper {

    private const val TAG = "EmdkRebootHelper"

    /** Must match EMDKConfig.xml / legacy app profile name used for SET operations. */
    private const val PROFILE_NAME = "WifiProfile-1"

    /**
     * Same XML string as `au.com.softclient.mydevices.helpers.EMDKmanager.reboot()`.
     */
    private val REBOOT_XML =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<characteristic type=\"Profile\">" +
            "<characteristic type=\"DisplayMgr\" version=\"7.2\">" +
            "<parm name=\"emdk_name\" value=\"\"/>" +
            "<parm name=\"TimeoutInterval\" value=\"1800\"/>" +
            "</characteristic>" +
            "<characteristic type=\"UsbMgr\" version=\"4.2\">" +
            "<parm name=\"emdk_name\" value=\"\"/>" +
            "<parm name=\"UsbModuleUsage\" value=\"1\"/>" +
            "<parm name=\"UsbExternalStorageUsage\" value=\"1\"/>" +
            "<parm name=\"UsbADBUsage\" value=\"1\"/>" +
            "<parm name=\"UsbAllAccessDeviceStorageUsage\" value=\"1\"/>" +
            "</characteristic>" +
            "<characteristic type=\"PowerMgr\" version=\"4.2\">" +
            "<parm name=\"emdk_name\" value=\"\"/>" +
            "<parm name=\"ResetAction\" value=\"4\"/>" +
            "</characteristic>" +
            "</characteristic>"

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "emdk-reboot").apply { isDaemon = true }
    }

    /**
     * @param delaySeconds optional delay (API `/SendRebootCall?seconds=`) before applying reboot profile.
     * @param onEmdkAttemptFinished `true` if EMDK `processProfile` was invoked and returned (device may reboot before this).
     *        `false` if EMDK could not run — use DPM reboot when appropriate.
     */
    fun requestReboot(
        context: Context,
        delaySeconds: Double?,
        onEmdkAttemptFinished: (emdkInvoked: Boolean) -> Unit
    ) {
        val app = context.applicationContext
        val delayMs = ((delaySeconds ?: 0.0) * 1000.0).toLong().coerceIn(0, 24 * 60 * 60 * 1000L)

        fun finishMainThread(emdkInvoked: Boolean) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onEmdkAttemptFinished(emdkInvoked)
            } else {
                Handler(Looper.getMainLooper()).post { onEmdkAttemptFinished(emdkInvoked) }
            }
        }

        try {
            val openResults = EMDKManager.getEMDKManager(
                app,
                object : EMDKManager.EMDKListener {
                    override fun onOpened(manager: EMDKManager) {
                        val profileManager =
                            manager.getInstance(EMDKManager.FEATURE_TYPE.PROFILE) as? ProfileManager
                        if (profileManager == null) {
                            Log.w(TAG, "ProfileManager unavailable")
                            try {
                                manager.release()
                            } catch (_: Exception) {
                            }
                            finishMainThread(false)
                            return
                        }

                        ioExecutor.execute {
                            try {
                                if (delayMs > 0) Thread.sleep(delayMs)
                                val results = profileManager.processProfile(
                                    PROFILE_NAME,
                                    ProfileManager.PROFILE_FLAG.SET,
                                    arrayOf(REBOOT_XML)
                                )
                                val detail = try {
                                    results.statusString
                                } catch (_: Exception) {
                                    null
                                }
                                Log.i(
                                    TAG,
                                    "EMDK processProfile(reboot) status=${results.statusCode} detail=$detail"
                                )
                                // Anything except explicit FAILURE usually means the request reached MX;
                                // CHECK_XML / SUCCESS are typical; some builds return other non-FAILURE codes.
                                val ok = results.statusCode != EMDKResults.STATUS_CODE.FAILURE
                                finishMainThread(ok)
                            } catch (e: Exception) {
                                Log.e(TAG, "EMDK processProfile(reboot) failed", e)
                                finishMainThread(false)
                            } finally {
                                try {
                                    manager.release()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    override fun onClosed() {
                        Log.w(TAG, "EMDK closed unexpectedly before reboot")
                    }
                }
            )

            if (openResults.statusCode != EMDKResults.STATUS_CODE.SUCCESS) {
                Log.w(TAG, "getEMDKManager failed: ${openResults.statusCode}")
                scheduleDelayedFallback(delayMs) { finishMainThread(false) }
            }
        } catch (_: NoClassDefFoundError) {
            Log.w(TAG, "EMDK classes not found (not a Zebra device or library missing)")
            scheduleDelayedFallback(delayMs) { finishMainThread(false) }
        } catch (e: Exception) {
            Log.e(TAG, "getEMDKManager threw", e)
            scheduleDelayedFallback(delayMs) { finishMainThread(false) }
        }
    }

    private fun scheduleDelayedFallback(delayMs: Long, action: () -> Unit) {
        if (delayMs <= 0) {
            action()
            return
        }
        ioExecutor.execute {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
            }
            action()
        }
    }
}
