package com.xg.toolbox.feature.apps.domain

enum class BatchAction { FREEZE, UNFREEZE, UNINSTALL, DISABLE_AUTOSTART, CHANGE_ANDROID_ID }

data class BatchTarget(val packageName: String, val label: String)

data class PreflightIssue(val packageName: String, val reason: String, val blocking: Boolean)

data class BatchPreflight(
    val targets: List<BatchTarget>,
    val issues: List<PreflightIssue>,
    val estimatedMs: Long,
    val canRun: Boolean,
)

data class BatchItemResult(
    val packageName: String,
    val status: String,
    val message: String? = null,
)

data class BatchProgress(
    val action: BatchAction,
    val done: Int,
    val total: Int,
    val current: String?,
    val results: List<BatchItemResult>,
)

data class BatchReceipt(
    val id: Long,
    val action: BatchAction,
    val total: Int,
    val ok: Int,
    val failed: Int,
    val skipped: Int,
    val items: List<BatchItemResult>,
)

data class AbiStatus(
    val packageName: String,
    val currentAbi: String?,
    val apkAbis: List<String>,
    val canSwitchTo32: Boolean,
    val canSwitchTo64: Boolean,
    val reason: String?,
)

data class ModuleInfo(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val enabled: Boolean,
    val pendingRemove: Boolean,
)
