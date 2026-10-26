package com.xg.toolbox.core.shell

import java.io.File

internal object SuBinary {

    private val CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su",
    )

    /** Known absolute fallbacks, in probe order. The user-selected path is prepended by callers. */
    fun candidates(): List<String> = CANDIDATES

    fun resolve(): String? =
        CANDIDATES.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }
            ?: CANDIDATES.firstOrNull { runCatching { File(it).exists() }.getOrDefault(false) }

    fun resolveOrPath(): String = resolve() ?: "su"
}