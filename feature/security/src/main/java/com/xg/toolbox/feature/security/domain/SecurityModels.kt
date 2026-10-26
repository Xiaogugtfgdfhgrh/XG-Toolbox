package com.xg.toolbox.feature.security.domain

enum class AppOpState { ALLOW, ASK, DENY }

data class AppOpDefinition(val op: String, val label: String)

data class AppOpRow(
    val packageName: String,
    val label: String,
    val cells: Map<String, AppOpState?>,
    val error: String? = null,
)

data class AccessibilityState(
    val enabledServices: List<String>,
    val locked: Boolean,
    val whitelist: Set<String>,
)

data class BootReceiverInfo(
    val packageName: String,
    val label: String,
    val component: String,
    val enabled: Boolean,
    val source: String,
)

data class VendorAutostartManager(
    val packageName: String,
    val label: String,
)

data class AdHit(
    val packageName: String,
    val appLabel: String,
    val sdk: String,
    val evidence: String,
)
