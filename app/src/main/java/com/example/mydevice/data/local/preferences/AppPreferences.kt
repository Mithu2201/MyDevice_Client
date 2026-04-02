package com.example.mydevice.data.local.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.mydevice.util.Constants
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore-based preferences for non-secret app configuration.
 *
 * WHY DataStore over SharedPreferences:
 * - Fully async (coroutines + Flow) — never blocks UI thread
 * - Type-safe keys — no runtime cast errors
 * - Transactional writes — never partial/corrupt
 *
 * Stores: company ID, device identity, remote config flags, kiosk settings.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mydevice_app_prefs"
)

class AppPreferences(private val context: Context) {

    // ── Company / Licensee ──────────────────────────────────────────────────

    val companyId: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_COMPANY_ID] ?: Constants.DEFAULT_COMPANY_ID
    }

    suspend fun setCompanyId(id: Int) {
        context.dataStore.edit { it[KEY_COMPANY_ID] = id }
    }

    val companyName: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_COMPANY_NAME] ?: ""
    }

    suspend fun setCompanyName(name: String) {
        context.dataStore.edit { it[KEY_COMPANY_NAME] = name }
    }

    // ── Device Identity ─────────────────────────────────────────────────────

    val deviceId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ID] ?: Constants.DEFAULT_DEVICE_ID
    }

    suspend fun setDeviceId(id: String) {
        context.dataStore.edit { it[KEY_DEVICE_ID] = id }
    }

    val serverDeviceId: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_DEVICE_ID] ?: 0
    }

    suspend fun setServerDeviceId(id: Int) {
        context.dataStore.edit { it[KEY_SERVER_DEVICE_ID] = id }
    }

    // ── Remote Configuration Flags ──────────────────────────────────────────

    val showCheckInView: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_CHECK_IN] ?: false
    }

    suspend fun setShowCheckInView(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_CHECK_IN] = show }
    }

    val showChargingView: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_CHARGING] ?: true
    }

    suspend fun setShowChargingView(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_CHARGING] = show }
    }

    val inactivityTimeoutMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_INACTIVITY_TIMEOUT] ?: Constants.INACTIVITY_TIMEOUT_MINUTES
    }

    suspend fun setInactivityTimeoutMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_INACTIVITY_TIMEOUT] = minutes }
    }

    val statusUpdateIntervalMinutes: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_STATUS_INTERVAL] ?: Constants.STATUS_UPDATE_INTERVAL_MINUTES.toInt()
    }

    suspend fun setStatusUpdateIntervalMinutes(minutes: Int) {
        context.dataStore.edit { it[KEY_STATUS_INTERVAL] = minutes }
    }

    /**
     * When false (default), SignalR "Reboot" hub events are ignored.
     * Prevents unintended [DevicePolicyManager.reboot] from noisy or mistaken server pushes.
     */
    val allowRemoteRebootFromHub: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALLOW_REMOTE_REBOOT] ?: false
    }

    suspend fun setAllowRemoteRebootFromHub(allow: Boolean) {
        context.dataStore.edit { it[KEY_ALLOW_REMOTE_REBOOT] = allow }
    }

    // ── Registration state helper ───────────────────────────────────────────

    /** True only when a valid positive company ID is stored (0 and unset/-1 mean not enrolled). */
    val isRegistered: Flow<Boolean> = context.dataStore.data.map { prefs ->
        (prefs[KEY_COMPANY_ID] ?: Constants.DEFAULT_COMPANY_ID) > 0
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
    }

    /** Clears company/server IDs so the user can enter a new company ID on the splash screen. */
    suspend fun clearCompanyEnrollment() {
        context.dataStore.edit { prefs ->
            prefs[KEY_COMPANY_ID] = Constants.DEFAULT_COMPANY_ID
            prefs.remove(KEY_SERVER_DEVICE_ID)
        }
    }

    companion object {
        private val KEY_COMPANY_ID = intPreferencesKey("company_id")
        private val KEY_COMPANY_NAME = stringPreferencesKey("company_name")
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_SERVER_DEVICE_ID = intPreferencesKey("server_device_id")
        private val KEY_SHOW_CHECK_IN = booleanPreferencesKey("show_check_in")
        private val KEY_SHOW_CHARGING = booleanPreferencesKey("show_charging")
        private val KEY_INACTIVITY_TIMEOUT = intPreferencesKey("inactivity_timeout")
        private val KEY_STATUS_INTERVAL = intPreferencesKey("status_interval")
        private val KEY_ALLOW_REMOTE_REBOOT = booleanPreferencesKey("allow_remote_reboot_hub")
    }
}
