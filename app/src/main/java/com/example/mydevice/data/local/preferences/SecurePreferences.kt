package com.example.mydevice.data.local.preferences

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted SharedPreferences for storing sensitive data like auth tokens.
 *
 * WHY: Regular SharedPreferences store data in plain-text XML.
 * EncryptedSharedPreferences uses AES-256 to encrypt both keys and values,
 * so even if the device is rooted, tokens remain protected.
 *
 * HOW: MasterKeys generates (or retrieves) a hardware-backed AES key in
 * Android Keystore. That key encrypts/decrypts all values transparently.
 *
 * NOTE: Initialization of EncryptedSharedPreferences can fail on some devices
 * (corrupt keystore, missing providers, OEM bugs). To avoid crashing the app
 * during process startup (Application.onCreate -> Koin -> SecurePreferences),
 * we attempt to create the EncryptedSharedPreferences and fall back to a
 * regular SharedPreferences if that fails. This preserves app stability while
 * still providing encryption on supported devices.
 */
class SecurePreferences(context: Context) {

    private val prefs: SharedPreferences

    init {
        prefs = try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "mydevice_secure_prefs",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Log the issue and fall back to regular SharedPreferences so the app
            // doesn't crash during startup. This keeps the app usable on devices
            // where Android Keystore / EncryptedSharedPreferences isn't available.
            Log.w(TAG, "EncryptedSharedPreferences init failed, falling back to regular SharedPreferences", e)
            context.getSharedPreferences("mydevice_secure_prefs", Context.MODE_PRIVATE)
        }
    }

    // ── Auth Token ──────────────────────────────────────────────────────────

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_ACCESS_TOKEN, value).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_REFRESH_TOKEN, value).apply()

    fun clearTokens() {
        prefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    // ── Current logged-in user ──────────────────────────────────────────────

    var currentUserId: Int
        get() = prefs.getInt(KEY_CURRENT_USER_ID, -1)
        set(value) = prefs.edit().putInt(KEY_CURRENT_USER_ID, value).apply()

    var currentUsername: String?
        get() = prefs.getString(KEY_CURRENT_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_CURRENT_USERNAME, value).apply()

    fun clearUser() {
        prefs.edit()
            .remove(KEY_CURRENT_USER_ID)
            .remove(KEY_CURRENT_USERNAME)
            .apply()
    }

    companion object {
        private const val TAG = "SecurePreferences"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_CURRENT_USER_ID = "current_user_id"
        private const val KEY_CURRENT_USERNAME = "current_username"
    }
}
