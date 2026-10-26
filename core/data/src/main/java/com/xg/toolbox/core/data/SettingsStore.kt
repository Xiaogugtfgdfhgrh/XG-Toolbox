package com.xg.toolbox.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SETTINGS_FILE = "settings"
private const val DEFAULT_LAST_SCREEN = "home"
private const val DEFAULT_PERF_PROFILE = "balanced"
private const val MAX_TERM_HISTORY = 200

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = SETTINGS_FILE)

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext context: Context) {

    private val store: DataStore<Preferences> = context.settingsDataStore
    private val json = Json { ignoreUnknownKeys = true }

    
    val data: Flow<Preferences> = store.data.catch { cause ->
        if (cause is IOException) emit(emptyPreferences()) else throw cause
    }

    val lastScreen: Flow<String> = valueFlow(SettingsKeys.lastScreen, DEFAULT_LAST_SCREEN)

    suspend fun setLastScreen(value: String) = put(SettingsKeys.lastScreen, value)

    val readOnly: Flow<Boolean> = valueFlow(SettingsKeys.readOnly, false)

    suspend fun setReadOnly(value: Boolean) = put(SettingsKeys.readOnly, value)

    
    fun groupCollapsed(groupId: String): Flow<Boolean> =
        valueFlow(booleanPreferencesKey(groupCollapsedKey(groupId)), false)

    suspend fun setGroupCollapsed(groupId: String, value: Boolean) =
        put(booleanPreferencesKey(groupCollapsedKey(groupId)), value)

    val cleanSelected: Flow<Set<String>> = valueFlow(SettingsKeys.cleanSelected, emptySet())

    suspend fun setCleanSelected(ids: Set<String>) = put(SettingsKeys.cleanSelected, ids)

    val optSelected: Flow<Set<String>> = valueFlow(SettingsKeys.optSelected, emptySet())

    suspend fun setOptSelected(ids: Set<String>) = put(SettingsKeys.optSelected, ids)

    val termHistory: Flow<List<String>> = data
        .map { prefs -> prefs[SettingsKeys.termHistory]?.let(::decodeHistory) ?: emptyList() }
        .distinctUntilChanged()

    
    suspend fun appendTermHistory(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        store.edit { prefs ->
            val current = prefs[SettingsKeys.termHistory]?.let(::decodeHistory) ?: emptyList()
            val updated = (current.filterNot { it == trimmed } + trimmed).takeLast(MAX_TERM_HISTORY)
            prefs[SettingsKeys.termHistory] = json.encodeToString(updated)
        }
    }

    suspend fun clearTermHistory() {
        store.edit { prefs -> prefs.remove(SettingsKeys.termHistory) }
    }

    val a11yWhitelist: Flow<Set<String>> = valueFlow(SettingsKeys.a11yWhitelist, emptySet())

    suspend fun setA11yWhitelist(ids: Set<String>) = put(SettingsKeys.a11yWhitelist, ids)

    val perfProfile: Flow<String> = valueFlow(SettingsKeys.perfProfile, DEFAULT_PERF_PROFILE)

    suspend fun setPerfProfile(value: String) = put(SettingsKeys.perfProfile, value)

    val displaySize: Flow<String?> = nullableFlow(SettingsKeys.displaySize)

    suspend fun setDisplaySize(value: String?) = putNullable(SettingsKeys.displaySize, value)

    val displayDensity: Flow<Int?> = nullableFlow(SettingsKeys.displayDensity)

    suspend fun setDisplayDensity(value: Int?) = putNullable(SettingsKeys.displayDensity, value)

    val displayHzPeak: Flow<Float?> = nullableFlow(SettingsKeys.displayHzPeak)

    suspend fun setDisplayHzPeak(value: Float?) = putNullable(SettingsKeys.displayHzPeak, value)

    val displayHzMin: Flow<Float?> = nullableFlow(SettingsKeys.displayHzMin)

    suspend fun setDisplayHzMin(value: Float?) = putNullable(SettingsKeys.displayHzMin, value)

    val displayDc: Flow<Boolean> = valueFlow(SettingsKeys.displayDc, false)

    suspend fun setDisplayDc(value: Boolean) = put(SettingsKeys.displayDc, value)

    
    val flashSchemeCache: Flow<String?> = nullableFlow(SettingsKeys.flashSchemeCache)

    suspend fun setFlashSchemeCache(value: String?) = putNullable(SettingsKeys.flashSchemeCache, value)

    private fun groupCollapsedKey(groupId: String): String = "group_collapsed_$groupId"

    private fun decodeHistory(raw: String): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())

    private fun <T> valueFlow(key: Preferences.Key<T>, default: T): Flow<T> =
        data.map { prefs -> prefs[key] ?: default }.distinctUntilChanged()

    private fun <T> nullableFlow(key: Preferences.Key<T>): Flow<T?> =
        data.map { prefs -> prefs[key] }.distinctUntilChanged()

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { prefs -> prefs[key] = value }
    }

    private suspend fun <T> putNullable(key: Preferences.Key<T>, value: T?) {
        store.edit { prefs ->
            if (value == null) {
                prefs.remove(key)
            } else {
                prefs[key] = value
            }
        }
    }

    private object SettingsKeys {
        val lastScreen = stringPreferencesKey("last_screen")
        val readOnly = booleanPreferencesKey("root_readonly")
        val cleanSelected = stringSetPreferencesKey("clean_selected")
        val optSelected = stringSetPreferencesKey("opt_selected")
        val termHistory = stringPreferencesKey("term_history")
        val a11yWhitelist = stringSetPreferencesKey("a11y_whitelist")
        val perfProfile = stringPreferencesKey("perf_profile")
        val displaySize = stringPreferencesKey("display_size")
        val displayDensity = intPreferencesKey("display_density")
        val displayHzPeak = floatPreferencesKey("display_hz_peak")
        val displayHzMin = floatPreferencesKey("display_hz_min")
        val displayDc = booleanPreferencesKey("display_dc")
        val flashSchemeCache = stringPreferencesKey("flash_scheme_cache")
    }
}
