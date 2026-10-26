package com.xg.toolbox.feature.system.domain.perf

data class PerfChoice(val id: String, val label: String)

data class PerfControl(
    val id: String,
    val name: String,
    val description: String,
    val current: String,
    val choices: List<PerfChoice>,
    val available: Boolean,
    val unavailableReason: String? = null,
)

data class PerfState(
    val controls: List<PerfControl>,
    val memoryCompression: Boolean,
    val thermalGuard: Boolean,
    val summary: Map<String, String>,
)

object PerfControlIds {
    const val CPU_GOVERNOR = "cpu_governor"
    const val CPU_FREQ_CAP = "cpu_freq_cap"
    const val GPU_LEVEL = "gpu_level"
    const val IO_SCHEDULER = "io_scheduler"
    const val THERMAL_POLICY = "thermal_policy"
    const val RUN_MODE = "run_mode"
}
