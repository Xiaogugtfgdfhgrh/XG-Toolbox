package com.xg.toolbox.feature.system.ui.flash

import com.xg.toolbox.feature.system.domain.flash.FlashEnvironment
import com.xg.toolbox.feature.system.domain.flash.PartitionInfo
import com.xg.toolbox.feature.system.domain.flash.PartitionRisk

enum class FlashPartitionRisk {
    SAFE,
    CAUTION,
    DANGEROUS,
}

enum class FlashSlotFilter {
    ALL,
    CURRENT,
    A,
    B,
    NONE,
}

enum class FlashPartitionGroup {
    CURRENT,
    A,
    B,
    OTHER,
}

data class FlashPartitionUi(
    val base: String,
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val writable: Boolean,
    val slotted: Boolean,
    val slot: String?,
    val isCurrentSlot: Boolean,
    val risk: FlashPartitionRisk,
)

internal fun FlashEnvironment.partitionUis(): List<FlashPartitionUi> =
    availableParts.map { partition -> partition.toFlashPartitionUi() }

internal fun PartitionInfo.toFlashPartitionUi(): FlashPartitionUi = FlashPartitionUi(
    base = base,
    name = name,
    path = path,
    sizeBytes = sizeBytes,
    writable = writable,
    slotted = slotted,
    slot = slot,
    isCurrentSlot = currentSlot,
    risk = risk.toUiRisk(),
)

internal fun PartitionRisk.toUiRisk(): FlashPartitionRisk = when (this) {
    PartitionRisk.SAFE -> FlashPartitionRisk.SAFE
    PartitionRisk.CAUTION -> FlashPartitionRisk.CAUTION
    PartitionRisk.DANGEROUS -> FlashPartitionRisk.DANGEROUS
}

internal fun FlashPartitionRisk.canWriteWithExtraConfirm(): Boolean =
    this != FlashPartitionRisk.SAFE

internal const val SKIP_BACKUP_MIN_BYTES = 1024L * 1024L * 1024L

internal fun FlashPartitionUi.canSkipBackup(): Boolean =
    risk == FlashPartitionRisk.DANGEROUS || sizeBytes > SKIP_BACKUP_MIN_BYTES

internal fun List<FlashPartitionUi>.filterPartitions(
    query: String,
    risk: FlashPartitionRisk?,
    slot: FlashSlotFilter,
    currentSlot: String?,
    writableOnly: Boolean = false,
): List<FlashPartitionUi> {
    val needle = query.trim()
    return asSequence()
        .filter { partition ->
            needle.isEmpty() ||
                partition.name.contains(needle, ignoreCase = true) ||
                partition.base.contains(needle, ignoreCase = true)
        }
        .filter { partition -> risk == null || partition.risk == risk }
        .filter { partition -> !writableOnly || partition.writable }
        .filter { partition -> partition.matchesSlot(slot) }
        .sortedWith(
            compareBy<FlashPartitionUi> { partition -> partition.group(currentSlot).ordinal }
                .thenBy { partition -> partition.risk.ordinal }
                .thenBy { partition -> partition.name },
        )
        .toList()
}

internal fun FlashPartitionUi.matchesSlot(filter: FlashSlotFilter): Boolean = when (filter) {
    FlashSlotFilter.ALL -> true
    FlashSlotFilter.CURRENT -> isCurrentSlot
    FlashSlotFilter.A -> slot == "A"
    FlashSlotFilter.B -> slot == "B"
    FlashSlotFilter.NONE -> slot == null
}

internal fun FlashPartitionUi.group(currentSlot: String?): FlashPartitionGroup = when {
    isCurrentSlot -> FlashPartitionGroup.CURRENT
    currentSlot == null -> FlashPartitionGroup.OTHER
    slot == "A" -> FlashPartitionGroup.A
    slot == "B" -> FlashPartitionGroup.B
    else -> FlashPartitionGroup.OTHER
}
