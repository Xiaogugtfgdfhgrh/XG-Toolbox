package com.xg.toolbox.feature.security

import com.xg.toolbox.core.data.AppEntry

object MatrixAppSelector {

    const val SYSTEM_SAMPLE_SIZE = 12

    
    private val SYSTEM_SAMPLE_PRIORITY = listOf(
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.shell",
        "com.android.phone",
        "com.android.contacts",
        "com.android.providers.contacts",
        "com.android.camera",
        "com.android.gallery3d",
        "com.android.inputmethod.latin",
        "com.android.mms",
    )

    
    fun systemSample(apps: List<AppEntry>): List<AppEntry> {
        val systemApps = apps.filter { it.isSystem }
        if (systemApps.isEmpty()) return emptyList()
        val priority = SYSTEM_SAMPLE_PRIORITY
            .mapIndexed { index, packageName -> packageName to index }
            .toMap()
        val picked = systemApps
            .filter { it.packageName in priority }
            .sortedBy { priority[it.packageName] ?: Int.MAX_VALUE }
        val remaining = systemApps
            .filterNot { it.packageName in priority }
            .sortedBy { it.label.lowercase() }
        return (picked + remaining).distinctBy { it.packageName }.take(SYSTEM_SAMPLE_SIZE)
    }

    
    fun filter(
        apps: List<AppEntry>,
        filter: MatrixFilter,
        query: String,
        systemSample: List<AppEntry>,
    ): List<AppEntry> {
        val base = when (filter) {
            MatrixFilter.USER -> apps.filter { !it.isSystem }
            MatrixFilter.SYSTEM -> systemSample
            MatrixFilter.ALL -> apps.filter { !it.isSystem } + systemSample
        }
        val keyword = query.trim()
        if (keyword.isEmpty()) return base
        return base.filter {
            it.label.contains(keyword, ignoreCase = true) ||
                it.packageName.contains(keyword, ignoreCase = true)
        }
    }
}
