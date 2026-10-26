package com.xg.toolbox.core.shell

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-selected su executable. Persist this independently of core/data so RootShell can use it
 * without introducing a shell -> data dependency.
 */
@Singleton
class SuPathStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _customPath = MutableStateFlow(prefs.getString(KEY_PATH, null))
    val customPath: StateFlow<String?> = _customPath.asStateFlow()

    val effectivePath: String
        get() = customPath.value?.takeIf { File(it).exists() } ?: SuBinary.resolveOrPath()

    fun setCustomPath(path: String?) {
        val normalized = path?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit().apply {
            if (normalized == null) remove(KEY_PATH) else putString(KEY_PATH, normalized)
        }.apply()
        _customPath.value = normalized
    }

    fun clear() = setCustomPath(null)

    private companion object {
        const val PREFS = "su_path"
        const val KEY_PATH = "custom_su_path"
    }
}
