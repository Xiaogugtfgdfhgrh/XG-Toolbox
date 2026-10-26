package com.xg.toolbox.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val BEHAVIOR_FILE = "behavior"

data class AutostartOriginals(
    val appOpRunInBackground: String?,
    val appOpRunAnyInBackground: String?,
    val deviceIdleWhitelisted: Boolean,
    val bootReceiverEnabled: Boolean,
    val unlockReceiverEnabled: Boolean,
    val standbyBucket: String?,
    val oplusSupported: Boolean,
    val oplusStartupEnabled: Boolean?,
)

private val Context.behaviorDataStore: DataStore<Preferences> by preferencesDataStore(name = BEHAVIOR_FILE)

/** Persistence and privilege preferences. These survive a reboot and are copied by self-hide. */
@Singleton
class BehaviorPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.behaviorDataStore
    private val bootPrefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(BOOT_PREFS, Context.MODE_PRIVATE)

    val autostartEnabled: Flow<Boolean> = valueFlow(Keys.autostartEnabled, false)
    val rootKeepAliveEnabled: Flow<Boolean> = valueFlow(Keys.rootKeepAliveEnabled, false)
    val hiddenModeRequested: Flow<Boolean> = valueFlow(Keys.hiddenModeRequested, false)
    val adHostsEnabled: Flow<Boolean> = valueFlow(Keys.adHostsEnabled, false)

    suspend fun setAutostartEnabled(value: Boolean) {
        bootPrefs.edit().putBoolean(KEY_AUTOSTART, value).commit()
        put(Keys.autostartEnabled, value)
    }

    suspend fun syncBootState() {
        val enabled = autostartEnabled.first()
        bootPrefs.edit().putBoolean(KEY_AUTOSTART, enabled).commit()
    }

    suspend fun setRootKeepAliveEnabled(value: Boolean) = put(Keys.rootKeepAliveEnabled, value)
    suspend fun setHiddenModeRequested(value: Boolean) = put(Keys.hiddenModeRequested, value)
    suspend fun setAdHostsEnabled(value: Boolean) = put(Keys.adHostsEnabled, value)

    suspend fun standbyBucketBefore(): String? = store.data.first()[Keys.autostartStandbyBucketBefore]

    suspend fun setStandbyBucketBefore(value: String?) {
        store.edit { prefs ->
            if (value == null) prefs.remove(Keys.autostartStandbyBucketBefore)
            else prefs[Keys.autostartStandbyBucketBefore] = value
        }
    }

    suspend fun autostartOriginals(): AutostartOriginals? {
        val prefs = store.data.first()
        if (prefs[Keys.autostartOriginalCaptured] != true) return null
        val oplusSupported = prefs[Keys.autostartOplusSupported] ?: false
        return AutostartOriginals(
            appOpRunInBackground = prefs[Keys.autostartAppOpRunInBackground],
            appOpRunAnyInBackground = prefs[Keys.autostartAppOpRunAnyInBackground],
            deviceIdleWhitelisted = prefs[Keys.autostartDeviceIdleBefore] ?: false,
            bootReceiverEnabled = prefs[Keys.autostartBootReceiverBefore] ?: false,
            unlockReceiverEnabled = prefs[Keys.autostartUnlockReceiverBefore] ?: false,
            standbyBucket = prefs[Keys.autostartStandbyBucketBefore],
            oplusSupported = oplusSupported,
            oplusStartupEnabled = if (oplusSupported) {
                prefs[Keys.autostartOplusStartupBefore] ?: false
            } else {
                null
            },
        )
    }

    suspend fun saveAutostartOriginals(value: AutostartOriginals) {
        store.edit { prefs ->
            prefs[Keys.autostartOriginalCaptured] = true
            putNullable(prefs, Keys.autostartAppOpRunInBackground, value.appOpRunInBackground)
            putNullable(prefs, Keys.autostartAppOpRunAnyInBackground, value.appOpRunAnyInBackground)
            prefs[Keys.autostartDeviceIdleBefore] = value.deviceIdleWhitelisted
            prefs[Keys.autostartBootReceiverBefore] = value.bootReceiverEnabled
            prefs[Keys.autostartUnlockReceiverBefore] = value.unlockReceiverEnabled
            putNullable(prefs, Keys.autostartStandbyBucketBefore, value.standbyBucket)
            prefs[Keys.autostartOplusSupported] = value.oplusSupported
            value.oplusStartupEnabled?.let { prefs[Keys.autostartOplusStartupBefore] = it }
                ?: prefs.remove(Keys.autostartOplusStartupBefore)
        }
    }

    suspend fun clearAutostartOriginals() {
        store.edit { prefs ->
            prefs.remove(Keys.autostartOriginalCaptured)
            prefs.remove(Keys.autostartAppOpRunInBackground)
            prefs.remove(Keys.autostartAppOpRunAnyInBackground)
            prefs.remove(Keys.autostartDeviceIdleBefore)
            prefs.remove(Keys.autostartBootReceiverBefore)
            prefs.remove(Keys.autostartUnlockReceiverBefore)
            prefs.remove(Keys.autostartStandbyBucketBefore)
            prefs.remove(Keys.autostartOplusSupported)
            prefs.remove(Keys.autostartOplusStartupBefore)
        }
    }

    private fun putNullable(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value == null) prefs.remove(key) else prefs[key] = value
    }

    private fun valueFlow(key: Preferences.Key<Boolean>, default: Boolean): Flow<Boolean> =
        store.data.catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }.map { it[key] ?: default }.distinctUntilChanged()

    private suspend fun put(key: Preferences.Key<Boolean>, value: Boolean) {
        store.edit { it[key] = value }
    }

    companion object {
        const val BOOT_PREFS = "boot_state"
        const val KEY_AUTOSTART = "autostart"
    }

    private object Keys {
        val autostartEnabled = booleanPreferencesKey("autostart_enabled")
        val rootKeepAliveEnabled = booleanPreferencesKey("root_keepalive_enabled")
        val hiddenModeRequested = booleanPreferencesKey("hidden_mode_requested")
        val adHostsEnabled = booleanPreferencesKey("ad_hosts_enabled")
        val autostartStandbyBucketBefore = stringPreferencesKey("autostart_standby_bucket_before")
        val autostartOriginalCaptured = booleanPreferencesKey("autostart_original_captured")
        val autostartAppOpRunInBackground = stringPreferencesKey("autostart_appop_run_in_background")
        val autostartAppOpRunAnyInBackground = stringPreferencesKey("autostart_appop_run_any_in_background")
        val autostartDeviceIdleBefore = booleanPreferencesKey("autostart_deviceidle_before")
        val autostartBootReceiverBefore = booleanPreferencesKey("autostart_boot_receiver_before")
        val autostartUnlockReceiverBefore = booleanPreferencesKey("autostart_unlock_receiver_before")
        val autostartOplusSupported = booleanPreferencesKey("autostart_oplus_supported")
        val autostartOplusStartupBefore = booleanPreferencesKey("autostart_oplus_startup_before")
    }
}
