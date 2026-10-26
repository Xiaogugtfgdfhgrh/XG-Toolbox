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
import kotlinx.coroutines.flow.map

private const val APPEARANCE_FILE = "appearance"

internal val Context.appearanceDataStore: DataStore<Preferences> by preferencesDataStore(name = APPEARANCE_FILE)

/** UI-only preferences shared by the shell, settings page and liquid-glass components. */
@Singleton
class AppearancePreferences @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.appearanceDataStore

    val customBackgroundEnabled: Flow<Boolean> = valueFlow(Keys.customBackgroundEnabled, false)
    val backgroundFileName: Flow<String?> = nullableFlow(Keys.backgroundFileName)

    suspend fun setCustomBackgroundEnabled(value: Boolean) = put(Keys.customBackgroundEnabled, value)
    suspend fun setBackgroundFileName(value: String?) = putNullable(Keys.backgroundFileName, value)

    private fun <T> valueFlow(key: Preferences.Key<T>, default: T): Flow<T> =
        store.data.catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }.map { it[key] ?: default }.distinctUntilChanged()

    private fun <T> nullableFlow(key: Preferences.Key<T>): Flow<T?> =
        store.data.catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }.map { it[key] }.distinctUntilChanged()

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        store.edit { it[key] = value }
    }

    private suspend fun <T> putNullable(key: Preferences.Key<T>, value: T?) {
        store.edit { prefs ->
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
    }

    private object Keys {
        val customBackgroundEnabled = booleanPreferencesKey("custom_background_enabled")
        val backgroundFileName = stringPreferencesKey("background_file_name")
    }
}
