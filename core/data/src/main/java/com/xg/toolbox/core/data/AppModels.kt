package com.xg.toolbox.core.data

data class AppEntry(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val isFrozen: Boolean,
    val installedAt: Long,
    val updatedAt: Long,
    val apkSizeBytes: Long,
    val primaryAbi: String?,
    val uid: Int,
)

data class AppDetail(
    val packageName: String,
    val label: String,
    val signingSha256: String,
    val signatureCount: Int,
    val sourceDir: String,
    val dataDir: String,
    val nativeLibraryDir: String?,
    val primaryAbi: String?,
    val abis: List<String>,
    val versionName: String,
    val versionCode: Long,
    val firstInstallTime: Long,
    val lastUpdateTime: Long,
    val uid: Int,
    val isSystem: Boolean,
    val isFrozen: Boolean,
)

data class UninstallOutcome(
    val packageName: String,
    val backupDir: String?,
    val apkCount: Int,
    val wasSystem: Boolean,
)

data class AppUsage(
    val memoryBytes: Long?,
    val memoryRunning: Boolean,
    val trafficRxBytes: Long?,
    val trafficTxBytes: Long?,
    val trafficReason: String?,
    val windowDays: Int,
)
