package com.xg.toolbox.feature.system.domain.flash

data class PartitionInfo(
    val base: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val writable: Boolean,
    val slotted: Boolean,
    val slot: String? = null,
    val risk: PartitionRisk = PartitionRisk.CAUTION,
    val currentSlot: Boolean = false,
)

enum class PartitionRisk {
    SAFE,
    CAUTION,
    DANGEROUS,
}

data class FlashEnvironment(
    val scheme: String,
    val version: String,
    val evidence: List<String>,
    val kernelVersion: String,
    val bootMode: String,
    val availableParts: List<PartitionInfo>,
    val slotSuffix: String,
    val currentSlot: String?,
    val bootctl: Boolean,
    val byNameTotal: Int = availableParts.size,
)

data class FlashImageInfo(
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String,
    val uri: String,
)

data class FlashBackup(
    val partition: String,
    val path: String,
    val bytes: Long,
    val sha256: String? = null,
)

data class FlashProgress(
    val stage: String,
    val percent: Float,
    val message: String,
    val errorCode: Int? = null,
) {
    companion object {
        const val STAGE_VALIDATE = "validate"
        const val STAGE_BACKUP = "backup"
        const val STAGE_STAGING = "staging"
        const val STAGE_WRITING = "writing"
        const val STAGE_VERIFY = "verify"
        const val STAGE_ROLLBACK = "rollback"
        const val STAGE_DONE = "done"
        const val STAGE_FAILED = "failed"
    }
}
