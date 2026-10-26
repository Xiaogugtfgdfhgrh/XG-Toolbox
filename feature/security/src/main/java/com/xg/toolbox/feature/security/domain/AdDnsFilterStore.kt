package com.xg.toolbox.feature.security.domain

import android.content.Context

enum class AdDnsFilterMode { ALL, BLACKLIST, WHITELIST }

object AdDnsFilterStore {
    private const val PREFS = "ad_dns_filter"
    private const val KEY_MODE = "mode"
    private const val KEY_APPS = "apps"

    fun mode(context: Context): AdDnsFilterMode = runCatching {
        AdDnsFilterMode.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_MODE, AdDnsFilterMode.ALL.name)!!,
        )
    }.getOrDefault(AdDnsFilterMode.ALL)

    fun selected(context: Context): Set<String> = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_APPS, emptySet())!!.toSet()
    }.getOrDefault(emptySet())

    fun save(context: Context, mode: AdDnsFilterMode, selected: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, mode.name)
            .putStringSet(KEY_APPS, selected)
            .apply()
    }
}
