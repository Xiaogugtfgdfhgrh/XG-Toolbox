package com.xg.toolbox.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SYSTEM_ORDER_FILE = "system_order"

internal val Context.systemOrderDataStore: DataStore<Preferences> by preferencesDataStore(name = SYSTEM_ORDER_FILE)

@Singleton
class SystemOrderStore @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.systemOrderDataStore
    private val json = Json { ignoreUnknownKeys = true }

    val entryOrder: Flow<List<String>> = store.data
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { preferences -> decode(preferences[Keys.entryOrder]) }
        .distinctUntilChanged()

    fun mergeOrder(saved: List<String>, knownIds: List<String>): List<String> {
        val known = knownIds.toSet()
        val merged = LinkedHashSet<String>(knownIds.size)
        saved.forEach { id ->
            if (id in known) merged.add(id)
        }
        knownIds.forEach(merged::add)
        return merged.toList()
    }

    suspend fun save(order: List<String>, knownIds: List<String>) {
        val merged = mergeOrder(order, knownIds)
        store.edit { preferences ->
            preferences[Keys.entryOrder] = json.encodeToString(merged)
        }
    }

    private fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.isNotBlank() }
            .distinct()
    }

    private object Keys {
        val entryOrder = stringPreferencesKey("system_entry_order_v1")
    }
}
