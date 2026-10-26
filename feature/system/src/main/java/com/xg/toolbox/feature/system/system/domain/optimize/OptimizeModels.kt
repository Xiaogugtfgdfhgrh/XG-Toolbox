package com.xg.toolbox.feature.system.domain.optimize

data class OptimizeItem(
    val id: String,
    val name: String,
    val description: String,
    val benefit: String,
    val risk: String,
    val rollback: String,
    val available: Boolean,
    val unavailableReason: String? = null,
)

data class OptimizeScan(
    val items: List<OptimizeItem>,
    val reclaimBytes: Long,
    val availableMemBytes: Long,
    val disabledBloatCount: Int,
)

data class OptimizeProgress(
    val itemId: String,
    val status: String,
    val percent: Float,
    val message: String? = null,
)

data class OptimizeCompare(
    val beforeUsedBytes: Long,
    val afterUsedBytes: Long,
    val beforeMemBytes: Long,
    val afterMemBytes: Long,
    val beforeDisabledBloatCount: Int,
    val afterDisabledBloatCount: Int,
)

internal object OptimizeIds {
    const val CACHE = "opt-cache"
    const val MEM = "opt-mem"
    const val FSTRIM = "opt-fstrim"
    const val ANIM = "opt-anim"
    const val ZRAM = "opt-zram"
    const val BLOAT = "opt-bloat"

    
    val ORDER = listOf(CACHE, MEM, FSTRIM, ANIM, ZRAM, BLOAT)

    
    const val ANIM_TARGET_SCALE = "0.5"

    
    const val ANDROID_USER = "0"
}

internal data class OptimizeCatalogEntry(
    val id: String,
    val name: String,
    val benefit: String,
    val risk: String,
    val rollback: String,
)

internal object OptimizeCatalog {
    val entries: List<OptimizeCatalogEntry> = listOf(
        OptimizeCatalogEntry(
            id = OptimizeIds.CACHE,
            name = "清理应用缓存",
            benefit = "释放缓存占用",
            risk = "首次启动变慢",
            rollback = "无需",
        ),
        OptimizeCatalogEntry(
            id = OptimizeIds.MEM,
            name = "释放后台内存",
            benefit = "立即可用内存上升",
            risk = "后台任务被杀",
            rollback = "无需（系统自动重建）",
        ),
        OptimizeCatalogEntry(
            id = OptimizeIds.FSTRIM,
            name = "存储整理",
            benefit = "写入性能恢复",
            risk = "耗时较长",
            rollback = "无需",
        ),
        OptimizeCatalogEntry(
            id = OptimizeIds.ANIM,
            name = "降低动画缩放",
            benefit = "操作更跟手",
            risk = "视觉变化",
            rollback = "恢复 1.0",
        ),
        OptimizeCatalogEntry(
            id = OptimizeIds.ZRAM,
            name = "调整 ZRAM 容量",
            benefit = "多任务更稳",
            risk = "设错会导致内存压力",
            rollback = "恢复原 disksize 并重建",
        ),
        OptimizeCatalogEntry(
            id = OptimizeIds.BLOAT,
            name = "禁用预装组件",
            benefit = "减少后台",
            risk = "误禁影响功能",
            rollback = "pm enable",
        ),
    )

    fun byId(id: String): OptimizeCatalogEntry? = entries.firstOrNull { it.id == id }
}

internal data class AnimScales(
    val window: String?,
    val transition: String?,
    val animator: String?,
) {
    val windowValue: String get() = window ?: "未设置"
    val transitionValue: String get() = transition ?: "未设置"
    val animatorValue: String get() = animator ?: "未设置"

    
    val allAtTarget: Boolean
        get() = window == OptimizeIds.ANIM_TARGET_SCALE &&
            transition == OptimizeIds.ANIM_TARGET_SCALE &&
            animator == OptimizeIds.ANIM_TARGET_SCALE
}

internal data class MemInfo(
    val totalBytes: Long,
    val freeBytes: Long,
    val availableBytes: Long,
)

internal data class ZramState(
    val disksizeBytes: Long?,
    val hybridSwapPresent: Boolean,
    val swapTotalKb: Long,
) {
    val available: Boolean get() = disksizeBytes != null && disksizeBytes > 0 && !hybridSwapPresent
}

internal data class FstrimTools(
    val hasSm: Boolean,
    val hasFstrim: Boolean,
) {
    val available: Boolean get() = hasSm || hasFstrim
}

internal data class BloatEntry(
    val pkg: String,
    val name: String,
    val risk: String,
    val note: String,
)

internal data class BloatStatus(
    val entries: List<BloatEntry>,
    val installedHits: List<BloatEntry>,
    val alreadyDisabled: Set<String>,
)

internal data class OptimizeSnapshot(
    val anim: AnimScales?,
    val zramDisksizeBytes: Long?,
    val disabledBloat: Set<String>,
    val mem: MemInfo?,
    val storageUsedBytes: Long?,
    val cacheBytes: Long?,
)

internal data class OptimizeExecResult(val status: String, val message: String)
