package com.xg.toolbox.feature.system.domain.processes

enum class ProcessGroup {
    FOREGROUND,
    VISIBLE,
    SERVICE,
    CACHED,
    SYSTEM,
    KERNEL,
}

data class ProcessItem(
    val pid: Int,
    val ppid: Int,
    val uid: Int,
    val user: String,
    val name: String,
    val cmdline: String,
    val rssKb: Long,
    val vszKb: Long,
    val stat: String,
    val threads: Int,
    val oomAdj: Int,
    val group: ProcessGroup,
    val protected: Boolean,
    val protectedReason: String? = null,
    val selinux: String? = null,
    val startTimeMs: Long? = null,
    val cgroup: String? = null,
) {
    companion object {
        
        const val NO_OOM_ADJ = Int.MIN_VALUE / 2
    }
}

internal data class PsRow(
    val pid: Int,
    val ppid: Int,
    val user: String,
    val vszKb: Long,
    val rssKb: Long,
    val stat: String,
    val name: String,
    val cmdline: String,
)

internal data class ProcEnrichment(
    val pid: Int,
    val oomAdj: Int,
    val uid: Int,
    val state: String,
    val ppid: Int,
    val utimeTicks: Long,
    val stimeTicks: Long,
    val threads: Int,
    val startTicks: Long,
    val selinux: String?,
    val cgroup: String?,
)

internal data class ProcessLoad(
    val processes: List<ProcessItem>,
    val bootTimeSec: Long?,
    
    val psTotalCount: Int?,
    
    val filteredNoise: Int,
    val usedProcFallback: Boolean,
)

internal data class LiveProcInfo(
    val exists: Boolean,
    val comm: String,
    val state: String,
    val ppid: Int,
    val uid: Int,
    val startTicks: Long,
    val cmdline: String,
)
