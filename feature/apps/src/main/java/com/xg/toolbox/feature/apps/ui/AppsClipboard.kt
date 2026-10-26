package com.xg.toolbox.feature.apps.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

internal fun Context.copyPlainText(text: String): Boolean {
    if (text.isEmpty()) return false
    val manager = getSystemService(ClipboardManager::class.java) ?: return false
    return runCatching {
        manager.setPrimaryClip(ClipData.newPlainText("xg-toolbox", text))
        manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString() == text
    }.getOrDefault(false)
}
