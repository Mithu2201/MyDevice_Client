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
 * Applies Wi‑Fi network profiles pushed from the admin portal via SignalR **WifiProfile**,
 * using the same EMDK XML as `au.com.softclient.mydevices.helpers.EMDKmanager.addSSID`.
 *
 * Zebra / Symbol devices with EMDK; no-op fallback on other hardware.
 */
object EmdkWifiProfileHelper {

    private const val TAG = "EmdkWifiProfileHelper"
    private const val PROFILE_NAME = "WifiProfile-1"

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "emdk-wifi").apply { isDaemon = true }
    }

    /**
     * Escape minimal XML special chars in SSID/passphrase (legacy did not; improves robustness).
     */
    private fun xmlEscape(s: String): String =
        s.replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    /**
     * Same profile XML as legacy [EMDKmanager.addSSID].
     */
    private fun buildAddSsidXml(essid: String, password: String): String {
        val ssid = xmlEscape(essid.trim())
        val pass = xmlEscape(password)
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<characteristic type=\"Profile\">" +
            "<parm name=\"ProfileName\" value=\"WifiProfile-1\"/>" +
            "<characteristic type=\"Wi-Fi\" version=\"2.7\">" +
            "<characteristic type=\"System\">" +
            "<parm name=\"WiFiAction\" value=\"1\"/>" +
            "</characteristic>" +
            "<parm name=\"NetworkAction\" value=\"Add\"/>" +
            "<characteristic type=\"network-profile\">" +
            "<parm name=\"SSID\" value=\"$ssid\"/>" +
            "<parm name=\"SecurityMode\" value=\"1\"/>" +
            "<parm name=\"WPAMode\" value=\"2\"/>" +
            "<characteristic type=\"auth-details\">" +
            "<characteristic type=\"encryption-details\">" +
            "<parm name=\"EncryptionWPA2\" value=\"1\"/>" +
            "</characteristic>" +
            "</characteristic>" +
            "<characteristic type=\"key-details\">" +
            "<parm name=\"KeyType\" value=\"Passphrase\"/>" +
            "<parm name=\"ProtectKey\" value=\"0\"/>" +
            "<parm name=\"PassphraseWPAClear\" value=\"$pass\"/>" +
            "</characteristic>" +
            "<parm name=\"UseDHCP\" value=\"1\"/>" +
            "<parm name=\"UseProxy\" value=\"0\"/>" +
            "</characteristic></characteristic>" +
            "</characteristic>"
    }

    fun applyAddSsid(
        context: Context,
        essid: String,
        password: String,
        onFinished: (success: Boolean) -> Unit
    ) {
        val app = context.applicationContext
        if (essid.isBlank()) {
            Log.w(TAG, "applyAddSsid: empty essid")
            Handler(Looper.getMainLooper()).post { onFinished(false) }
            return
        }

        val xml = buildAddSsidXml(essid, password)

        fun finishMain(ok: Boolean) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                onFinished(ok)
            } else {
                Handler(Looper.getMainLooper()).post { onFinished(ok) }
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
                            finishMain(false)
                            return
                        }

                        ioExecutor.execute {
                            try {
                                val results = profileManager.processProfile(
                                    PROFILE_NAME,
                                    ProfileManager.PROFILE_FLAG.SET,
                                    arrayOf(xml)
                                )
                                val detail = try {
                                    results.statusString
                                } catch (_: Exception) {
                                    null
                                }
                                Log.i(
                                    TAG,
                                    "processProfile(WiFi add) status=${results.statusCode} detail=$detail"
                                )
                                val ok = results.statusCode != EMDKResults.STATUS_CODE.FAILURE
                                finishMain(ok)
                            } catch (e: Exception) {
                                Log.e(TAG, "processProfile WiFi failed", e)
                                finishMain(false)
                            } finally {
                                try {
                                    manager.release()
                                } catch (_: Exception) {
                                }
                            }
                        }
                    }

                    override fun onClosed() {
                        Log.w(TAG, "EMDK closed unexpectedly during WiFi profile")
                    }
                }
            )

            if (openResults.statusCode != EMDKResults.STATUS_CODE.SUCCESS) {
                Log.w(TAG, "getEMDKManager failed: ${openResults.statusCode}")
                finishMain(false)
            }
        } catch (_: NoClassDefFoundError) {
            Log.w(TAG, "EMDK not available on this device")
            finishMain(false)
        } catch (e: Exception) {
            Log.e(TAG, "getEMDKManager threw", e)
            finishMain(false)
        }
    }
}
