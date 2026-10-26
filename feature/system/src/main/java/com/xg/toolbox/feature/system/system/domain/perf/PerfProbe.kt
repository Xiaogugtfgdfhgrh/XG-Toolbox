package com.xg.toolbox.feature.system.domain.perf

import com.xg.toolbox.core.shell.Shell

internal data class CpuClusterInfo(
    val policyPath: String,
    val cpuIds: String,
    val governor: String,
    val availableGovernors: List<String>,
    val minFreqKhz: Long,
    val maxFreqKhz: Long,
    val availableFreqKhz: List<Long>,
)

internal data class GpuFreqInfo(
    val minFreqHz: Long,
    val maxFreqHz: Long,
    val availableFreqHz: List<Long>,
)

internal data class ThermalZoneInfo(
    val path: String,
    val type: String,
    val policy: String,
    val availablePolicies: List<String>,
    val tempC: Float?,
)

internal data class ZramInfo(
    val disksizeBytes: Long,
    val swapTotalKb: Long,
    val swapUsedKb: Long,
    val memTotalBytes: Long,
) {
    val enabled: Boolean get() = disksizeBytes > 0L && swapTotalKb > 0L

    
    val maxAllowedBytes: Long get() = memTotalBytes / 2
}

internal data class IoInfo(
    val path: String,
    val current: String,
    val available: List<String>,
)

internal data class VendorPerfNode(val path: String, val current: String)

internal data class PerfSnapshot(
    val clusters: List<CpuClusterInfo>,
    val gpu: GpuFreqInfo?,
    val thermalZones: List<ThermalZoneInfo>,
    val zram: ZramInfo?,
    val io: IoInfo?,
    val lowPower: Boolean?,
    val vendorPerf: VendorPerfNode?,
) {
    val hasCpuNodes: Boolean get() = clusters.isNotEmpty()

    val maxCpuTempC: Float? get() = thermalZones.mapNotNull { it.tempC }.maxOrNull()

    
    val commonGovernor: String?
        get() = clusters.map { it.governor }.distinct().singleOrNull()
}

internal class PerfProbe(private val shell: Shell) {

    suspend fun probe(): PerfSnapshot? {
        val result = shell.exec(PROBE_SCRIPT, root = true, timeoutMs = PROBE_TIMEOUT_MS)
        if (!result.ok) return null
        val output = result.stdout.substringBefore(SECTION_END)
        val sections = splitSections(output)
        val clusters = parseClusters(sections[SECTION_CPU].orEmpty())
        val gpu = parseGpu(sections[SECTION_GPU].orEmpty())
        val thermalZones = parseThermal(sections[SECTION_THERMAL].orEmpty())
        val zram = parseZram(sections[SECTION_ZRAM].orEmpty())
        val io = parseIo(sections[SECTION_IO].orEmpty())
        val lowPower = sections[SECTION_RUN].orEmpty()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it == "0" || it == "1" }
            ?.let { it == "1" }
        val vendorPerf = parseVendor(sections[SECTION_VENDOR].orEmpty())
        if (clusters.isEmpty() && gpu == null && thermalZones.isEmpty() && zram == null &&
            io == null && lowPower == null && vendorPerf == null
        ) {
            return null
        }
        return PerfSnapshot(
            clusters = clusters,
            gpu = gpu,
            thermalZones = thermalZones,
            zram = zram,
            io = io,
            lowPower = lowPower,
            vendorPerf = vendorPerf,
        )
    }

    private fun splitSections(output: String): Map<String, String> {
        val sections = mutableMapOf<String, String>()
        var current = ""
        val buffer = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) sections[current] = buffer.toString()
            buffer.clear()
        }
        output.lineSequence().forEach { line ->
            val marker = line.trim()
            if (marker.startsWith("@@") && marker.length > 2) {
                flush()
                current = marker.removePrefix("@@").lowercase()
            } else {
                buffer.appendLine(line)
            }
        }
        flush()
        return sections
    }

    private fun parseClusters(section: String): List<CpuClusterInfo> {
        val related = mutableMapOf<String, String>()
        val governors = mutableMapOf<String, String>()
        val availableGovernors = mutableMapOf<String, List<String>>()
        val mins = mutableMapOf<String, Long>()
        val maxs = mutableMapOf<String, Long>()
        val freqs = mutableMapOf<String, List<Long>>()

        section.lineSequence().forEach { line ->
            val split = splitPathLine(line) ?: return@forEach
            val (path, raw) = split
            when {
                path.endsWith("/related_cpus") -> related[dirOf(path)] = raw.trim()
                path.endsWith("/scaling_governor") -> governors[dirOf(path)] = raw.trim()
                path.endsWith("/scaling_available_governors") ->
                    availableGovernors[dirOf(path)] = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
                path.endsWith("/scaling_min_freq") -> raw.trim().toLongOrNull()?.let { mins[dirOf(path)] = it }
                path.endsWith("/scaling_max_freq") -> raw.trim().toLongOrNull()?.let { maxs[dirOf(path)] = it }
                path.endsWith("/scaling_available_frequencies") ->
                    freqs[dirOf(path)] = raw.trim().split(WHITESPACE)
                        .mapNotNull { it.toLongOrNull() }
                        .sorted()
                else -> Unit
            }
        }
        return related.keys.sortedWith(POLICY_ORDER).mapNotNull { policy ->
            val governor = governors[policy] ?: return@mapNotNull null
            CpuClusterInfo(
                policyPath = policy,
                cpuIds = related[policy].orEmpty(),
                governor = governor,
                availableGovernors = availableGovernors[policy].orEmpty(),
                minFreqKhz = mins[policy] ?: 0L,
                maxFreqKhz = maxs[policy] ?: 0L,
                availableFreqKhz = freqs[policy].orEmpty(),
            )
        }
    }

    private fun parseGpu(section: String): GpuFreqInfo? {
        var minHz: Long? = null
        var maxHz: Long? = null
        var available: List<Long> = emptyList()
        section.lineSequence().forEach { line ->
            val split = splitPathLine(line) ?: return@forEach
            val (path, raw) = split
            when {
                path.endsWith("/available_frequencies") -> available = raw.trim()
                    .split(WHITESPACE)
                    .mapNotNull { it.toLongOrNull() }
                    .sorted()
                path.endsWith("/min_freq") -> minHz = raw.trim().toLongOrNull()
                path.endsWith("/max_freq") -> maxHz = raw.trim().toLongOrNull()
                else -> Unit
            }
        }
        val min = minHz ?: return null
        val max = maxHz ?: return null
        if (available.isEmpty() || min <= 0L || max <= 0L) return null
        return GpuFreqInfo(minFreqHz = min, maxFreqHz = max, availableFreqHz = available)
    }

    private fun parseThermal(section: String): List<ThermalZoneInfo> {
        val types = mutableMapOf<String, String>()
        val policies = mutableMapOf<String, String>()
        val availablePolicies = mutableMapOf<String, List<String>>()
        val temps = mutableMapOf<String, Float>()
        section.lineSequence().forEach { line ->
            val split = splitPathLine(line) ?: return@forEach
            val (path, raw) = split
            val zone = dirOf(path)
            when {
                path.endsWith("/type") -> types[zone] = raw.trim()
                path.endsWith("/policy") -> policies[zone] = raw.trim()
                path.endsWith("/available_policies") ->
                    availablePolicies[zone] = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
                path.endsWith("/temp") -> raw.trim().toFloatOrNull()?.let { temps[zone] = it / 1000f }
                else -> Unit
            }
        }
        return types.mapNotNull { (zone, type) ->
            val lower = type.lowercase()
            if (THERMAL_TYPE_KEYWORDS.none { lower.startsWith(it) || lower.contains("-$it") }) return@mapNotNull null
            val policy = policies[zone] ?: return@mapNotNull null
            val available = availablePolicies[zone].orEmpty().ifEmpty { listOf(policy) }
            ThermalZoneInfo(
                path = zone,
                type = type,
                policy = policy,
                availablePolicies = available,
                tempC = temps[zone]?.takeIf { it > 0f },
            )
        }.sortedBy { it.path }
    }

    private fun parseZram(section: String): ZramInfo? {
        var disksize = 0L
        var swapTotalKb = 0L
        var swapUsedKb = 0L
        var memTotalBytes = 0L
        val lines = section.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        lines.firstOrNull { it.matches(Regex("\\d+")) }?.toLongOrNull()?.let { disksize = it }
        lines.firstOrNull { it.startsWith("/dev/block/zram") }?.let { line ->
            val parts = line.split(WHITESPACE).filter { it.isNotEmpty() }
            if (parts.size >= 3) {
                swapTotalKb = parts[2].toLongOrNull() ?: 0L
                swapUsedKb = parts.getOrNull(3)?.toLongOrNull() ?: 0L
            }
        }
        lines.firstOrNull { it.startsWith("MemTotal:") }?.let { line ->
            MemTotalRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()?.let { memTotalBytes = it * 1024L }
        }
        if (memTotalBytes <= 0L) return null
        return ZramInfo(
            disksizeBytes = disksize,
            swapTotalKb = swapTotalKb,
            swapUsedKb = swapUsedKb,
            memTotalBytes = memTotalBytes,
        )
    }

    private fun parseIo(section: String): IoInfo? {
        val candidates = mutableListOf<IoInfo>()
        section.lineSequence().forEach { line ->
            val split = splitPathLine(line) ?: return@forEach
            val (path, raw) = split
            if (!path.endsWith("/queue/scheduler")) return@forEach
            if (!BLOCK_DEVICE_REGEX.containsMatchIn(path)) return@forEach
            val tokens = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return@forEach
            val current = tokens.firstOrNull { it.startsWith("[") && it.endsWith("]") }
                ?.removeSurrounding("[", "]")
                ?: tokens.first()
            val available = tokens.map { it.removeSurrounding("[", "]") }
            candidates += IoInfo(path = path, current = current, available = available)
        }
        if (candidates.isEmpty()) return null
        val preferred = candidates.firstOrNull { it.path.contains("/block/sda/queue") }
            ?: candidates.firstOrNull { it.path.contains("/block/mmcblk") }
            ?: candidates.first()
        return preferred
    }

    private fun parseVendor(section: String): VendorPerfNode? {
        section.lineSequence().forEach { line ->
            val split = splitPathLine(line) ?: return@forEach
            val (path, raw) = split
            if (path.startsWith("/sys/") && raw.isNotBlank()) {
                return VendorPerfNode(path = path, current = raw.trim())
            }
        }
        return null
    }

    
    private fun splitPathLine(line: String): Pair<String, String>? {
        val index = line.indexOf(':')
        if (index <= 0) return null
        val path = line.substring(0, index)
        if (!path.startsWith("/sys/") && !path.startsWith("/proc/")) return null
        return path to line.substring(index + 1)
    }

    private fun dirOf(path: String): String = path.substringBeforeLast('/')

    private companion object {
        const val PROBE_TIMEOUT_MS = 20_000L
        const val SECTION_CPU = "cpu"
        const val SECTION_GPU = "gpu"
        const val SECTION_THERMAL = "thermal"
        const val SECTION_ZRAM = "zram"
        const val SECTION_IO = "io"
        const val SECTION_RUN = "run"
        const val SECTION_VENDOR = "vendor"
        const val SECTION_END = "@@END"

        val WHITESPACE = Regex("\\s+")
        val POLICY_ORDER = compareBy<String>({ POLICY_NUMBER.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE }, { it })
        val POLICY_NUMBER = Regex("/policy(\\d+)$")
        val MemTotalRegex = Regex("MemTotal:\\s*(\\d+)")
        val BLOCK_DEVICE_REGEX = Regex("/sys/block/(sd[a-z]+|mmcblk\\d+)/queue/scheduler$")

        
        val THERMAL_TYPE_KEYWORDS = listOf("cpuss", "gpuss", "soc")

        
        val PROBE_SCRIPT: String = """
            echo '@@CPU'
            grep -H '' /sys/devices/system/cpu/cpufreq/policy*/related_cpus 2>/dev/null
            grep -H '' /sys/devices/system/cpu/cpufreq/policy*/scaling_governor 2>/dev/null
            grep -H '' /sys/devices/system/cpu/cpufreq/policy*/scaling_available_governors 2>/dev/null
            grep -H '' /sys/devices/system/cpu/cpufreq/policy*/scaling_min_freq 2>/dev/null
            grep -H '' /sys/devices/system/cpu/cpufreq/policy*/scaling_max_freq 2>/dev/null
            grep -H '' /sys/devices/system/cpu/cpufreq/policy*/scaling_available_frequencies 2>/dev/null
            echo '@@GPU'
            grep -H '' /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies 2>/dev/null
            grep -H '' /sys/class/kgsl/kgsl-3d0/devfreq/min_freq 2>/dev/null
            grep -H '' /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null
            echo '@@THERMAL'
            grep -H '' /sys/class/thermal/thermal_zone*/type 2>/dev/null
            grep -H '' /sys/class/thermal/thermal_zone*/policy 2>/dev/null
            grep -H '' /sys/class/thermal/thermal_zone*/available_policies 2>/dev/null
            grep -H '' /sys/class/thermal/thermal_zone*/temp 2>/dev/null
            echo '@@ZRAM'
            cat /sys/block/zram0/disksize 2>/dev/null
            grep zram /proc/swaps 2>/dev/null
            grep MemTotal /proc/meminfo 2>/dev/null
            echo '@@IO'
            grep -H '' /sys/block/sd*/queue/scheduler 2>/dev/null
            grep -H '' /sys/block/mmcblk*/queue/scheduler 2>/dev/null
            echo '@@RUN'
            settings get global low_power
            echo '@@VENDOR'
            grep -H '' /sys/kernel/oplus_performance/mode /sys/kernel/performance/mode /sys/class/oplus_performance/mode /sys/kernel/game_opt/mode 2>/dev/null
            echo '@@END'
        """.trimIndent()
    }
}
