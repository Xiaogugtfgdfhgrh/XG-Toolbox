package com.xg.toolbox.core.device

import android.content.Context
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.net.TrafficStats
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update

data class MetricsSnapshot(
    val ts: Long,
    val cpuPercent: Float?,
    val cpuFreqMhz: Int?,
    val cpuCores: Int,
    val gpuPercent: Float?,
    val gpuName: String?,
    val memUsedBytes: Long,
    val memTotalBytes: Long,
    val tempC: Float?,
    val batteryPercent: Int,
    val batteryTempC: Float?,
    val batteryCharging: Boolean,
    val batteryCurrentMa: Int?,
    val storageUsedBytes: Long,
    val storageTotalBytes: Long,
    val netRxBps: Long,
    val netTxBps: Long,
)

@Singleton
class MetricsSampler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val foregroundGate = ProcessForegroundGate(context)

    private val _history = MutableStateFlow<List<MetricsSnapshot>>(emptyList())

    
    val history: StateFlow<List<MetricsSnapshot>> = _history.asStateFlow()

    
    private val sharedStream: Flow<MetricsSnapshot> = samplingLoop()
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0), replay = 0)

    
    fun stream(): Flow<MetricsSnapshot> = sharedStream

    private fun samplingLoop(): Flow<MetricsSnapshot> = flow {
        var lastCpuTicks: CpuTicks? = null
        var lastNetBytes: NetBytes? = null
        var lastNetAt = 0L
        var storage: StorageReading? = null
        var storageAt = 0L
        var privileged: PrivilegedReading? = null
        var privilegedAt = 0L

        while (true) {
            if (!foregroundGate.isForeground.value) {
                foregroundGate.isForeground.first { it }
            }
            val loopStartedAt = SystemClock.elapsedRealtime()

            val rootGranted = rootStatus.state.value is RootState.Granted
            val cpuTicks = readCpuTicks(rootGranted)
            val cpuPercent = computeCpuPercent(lastCpuTicks, cpuTicks)
            if (cpuTicks != null) lastCpuTicks = cpuTicks

            val netBytes = readNetBytes()
            val netAt = SystemClock.elapsedRealtime()
            val netRates = computeNetRates(lastNetBytes, netBytes, lastNetAt, netAt)
            if (netBytes != null) lastNetBytes = netBytes
            lastNetAt = netAt

            val memory = readMemory()
            val battery = readBattery(context)
            if (storage == null || netAt - storageAt >= STORAGE_INTERVAL_MS) {
                storage = readStorage()
                storageAt = netAt
            }

            val directCpuFreq = readCpuFreqMhz()
            val directGpu = readGpu()
            val directTemp = readThermalCelsius()
            if (rootGranted &&
                (directCpuFreq == null || directGpu.percent == null || directTemp == null) &&
                netAt - privilegedAt >= PRIVILEGED_INTERVAL_MS
            ) {
                privileged = readPrivilegedMetrics()
                privilegedAt = netAt
            }

            val snapshot = MetricsSnapshot(
                ts = System.currentTimeMillis(),
                cpuPercent = cpuPercent,
                cpuFreqMhz = directCpuFreq ?: privileged?.cpuFreqMhz,
                cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                gpuPercent = directGpu.percent ?: privileged?.gpuPercent,
                gpuName = directGpu.name ?: privileged?.gpuName,
                memUsedBytes = memory?.used ?: 0L,
                memTotalBytes = memory?.total ?: 0L,
                tempC = directTemp ?: privileged?.temperatureC,
                batteryPercent = battery?.percent ?: -1,
                batteryTempC = battery?.temperatureC,
                batteryCharging = battery?.charging ?: false,
                batteryCurrentMa = battery?.currentMa,
                storageUsedBytes = storage?.used ?: 0L,
                storageTotalBytes = storage?.total ?: 0L,
                netRxBps = netRates.first,
                netTxBps = netRates.second,
            )
            emit(snapshot)
            _history.update { previous -> (previous + snapshot).takeLast(HISTORY_LIMIT) }

            val cost = SystemClock.elapsedRealtime() - loopStartedAt
            delay((SAMPLE_INTERVAL_MS - cost).coerceAtLeast(0L))
        }
    }.flowOn(Dispatchers.IO)

    
    private suspend fun readCpuTicks(rootGranted: Boolean): CpuTicks? {
        val direct = readTextFile("/proc/stat")
        if (direct != null) return parseCpuTicks(direct)
        if (!rootGranted) return null
        val fallback = shell.exec("cat /proc/stat", root = true, timeoutMs = CPU_STAT_TIMEOUT_MS)
        if (!fallback.ok) return null
        return parseCpuTicks(fallback.stdout)
    }

    
    private fun parseCpuTicks(content: String?): CpuTicks? {
        val statLine = content
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("cpu ") }
            ?: return null
        val parts = statLine.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (parts.size < 4) return null
        val total = parts.sum()
        val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
        return CpuTicks(total = total, idle = idle)
    }

    
    private fun computeCpuPercent(previous: CpuTicks?, current: CpuTicks?): Float? {
        if (previous == null || current == null) return null
        val totalDelta = current.total - previous.total
        if (totalDelta <= 0) return null
        val idleDelta = (current.idle - previous.idle).coerceIn(0L, totalDelta)
        val busyPercent = (totalDelta - idleDelta).toDouble() / totalDelta * 100.0
        return busyPercent.coerceIn(0.0, 100.0).toFloat()
    }

    
    private fun readMemory(): MemoryReading? {
        val text = readTextFile("/proc/meminfo") ?: return null
        var totalBytes = 0L
        var availableBytes = -1L
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("MemTotal:") -> totalBytes = parseKbValue(line)
                line.startsWith("MemAvailable:") -> availableBytes = parseKbValue(line)
            }
        }
        if (totalBytes <= 0) return null
        val available = if (availableBytes >= 0) availableBytes else 0L
        return MemoryReading(used = (totalBytes - available).coerceAtLeast(0L), total = totalBytes)
    }

    private fun parseKbValue(line: String): Long =
        Regex("(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()?.times(1024L) ?: -1L

    
    private fun readStorage(): StorageReading? = try {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.totalBytes
        val available = stat.availableBytes
        if (total > 0) StorageReading(used = (total - available).coerceAtLeast(0L), total = total) else null
    } catch (t: Throwable) {
        null
    }

    
    private fun readCpuFreqMhz(): Int? {
        val cpuRoot = File("/sys/devices/system/cpu")
        val cpuDirs = cpuRoot.listFiles { file ->
            file.isDirectory && Regex("cpu\\d+").matches(file.name)
        } ?: return null
        if (cpuDirs.isEmpty()) return null
        val clusterFrequencies = mutableMapOf<String, MutableList<Long>>()
        cpuDirs.forEach { cpuDir ->
            val currentKhz = readLongFile(File(cpuDir, "cpufreq/scaling_cur_freq")) ?: return@forEach
            val maxKhz = readLongFile(File(cpuDir, "cpufreq/cpuinfo_max_freq")) ?: 0L
            clusterFrequencies.getOrPut(maxKhz.toString()) { mutableListOf() }.add(currentKhz)
        }
        if (clusterFrequencies.isEmpty()) return null
        val averageKhz = clusterFrequencies.values.map { values -> values.average() }.average()
        if (averageKhz <= 0.0) return null
        return (averageKhz / 1000.0).roundToInt().takeIf { it > 0 }
    }

    
    private fun readGpu(): GpuReading {
        val percent = readTextFile("/sys/class/kgsl/kgsl-3d0/gpubusy")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.mapNotNull { it.toLongOrNull() }
            ?.takeIf { values -> values.size >= 2 }
            ?.let { values ->
                if (values[1] <= 0L) 0f else (values[0].toDouble() / values[1] * 100.0).toFloat().coerceIn(0f, 100f)
            }
        val name = formatGpuModel(readTextFile("/sys/class/kgsl/kgsl-3d0/gpu_model"))
        return GpuReading(percent = percent, name = name)
    }

    
    private fun readThermalCelsius(): Float? {
        val zones = File("/sys/class/thermal").listFiles { file ->
            file.isDirectory && file.name.startsWith("thermal_zone")
        } ?: return null
        var surfaceCelsius = -1f
        var hotspotCelsius = -1f
        zones.forEach { zone ->
            val type = readTextFile(File(zone, "type").path)?.lowercase(Locale.US) ?: return@forEach
            val rawValue = readTextFile(File(zone, "temp").path)?.toFloatOrNull() ?: return@forEach
            val celsius = if (rawValue > 200f) rawValue / 1000f else rawValue
            if (celsius <= 5f || celsius > 90f) return@forEach
            val isSurface = type.contains("battery") || type.contains("skin") ||
                type.contains("shell") || type.contains("surface") || type.contains("back")
            val isHotspot = type.contains("cpu") || type.contains("soc") || type.contains("gpu")
            if (isSurface) {
                if (celsius > surfaceCelsius) surfaceCelsius = celsius
            } else if (isHotspot && celsius > hotspotCelsius) {
                hotspotCelsius = celsius
            }
        }
        return when {
            surfaceCelsius > 0f -> surfaceCelsius
            hotspotCelsius > 0f -> hotspotCelsius
            else -> null
        }
    }

    
    private fun readBattery(context: Context): BatteryReading? {
        val intent = readBatteryIntent(context) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temperature = if (tempTenths != Int.MIN_VALUE) tempTenths / 10f else null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
        return BatteryReading(
            percent = percent,
            temperatureC = temperature,
            charging = charging,
            currentMa = readBatteryCurrentMa(context),
        )
    }

    
    private fun readBatteryCurrentMa(context: Context): Int? = try {
        val manager = context.getSystemService(BatteryManager::class.java)
        val microAmps = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Int.MIN_VALUE
        if (microAmps == Int.MIN_VALUE) null else microAmps / 1000
    } catch (t: Throwable) {
        null
    }

    
    private fun readNetBytes(): NetBytes? {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return null
        return NetBytes(rx = rx, tx = tx)
    }

    
    private fun computeNetRates(
        previous: NetBytes?,
        current: NetBytes?,
        previousAt: Long,
        currentAt: Long,
    ): Pair<Long, Long> {
        if (previous == null || current == null || previousAt <= 0L) return 0L to 0L
        val elapsedMs = currentAt - previousAt
        if (elapsedMs <= 0L) return 0L to 0L
        val rxDelta = (current.rx - previous.rx).coerceAtLeast(0L)
        val txDelta = (current.tx - previous.tx).coerceAtLeast(0L)
        return rxDelta * 1000L / elapsedMs to txDelta * 1000L / elapsedMs
    }

    
    private suspend fun readPrivilegedMetrics(): PrivilegedReading? {
        val result = shell.exec(PRIVILEGED_METRICS_SCRIPT, root = true, timeoutMs = PRIVILEGED_TIMEOUT_MS)
        if (!result.ok) return null
        val cpuFrequencies = mutableListOf<Long>()
        val thermalTemps = mutableListOf<Pair<String, Float>>()
        var gpuBusyRaw: String? = null
        var gpuModelRaw: String? = null
        var section = ""
        result.stdout.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line == "XG_CPU_FREQ_BEGIN" -> section = "cpu"
                line == "XG_CPU_FREQ_END" -> section = ""
                line == "XG_THERMAL_BEGIN" -> section = "thermal"
                line == "XG_THERMAL_END" -> section = ""
                line.startsWith("XG_GPU_BUSY=") -> gpuBusyRaw = line.substringAfter('=')
                line.startsWith("XG_GPU_MODEL=") -> gpuModelRaw = line.substringAfter('=')
                section == "cpu" -> line.toLongOrNull()?.let { cpuFrequencies += it }
                section == "thermal" -> {
                    val parts = line.split('|')
                    if (parts.size >= 2) {
                        val value = parts[1].trim().toFloatOrNull()
                        if (value != null) thermalTemps += parts[0].trim() to value
                    }
                }
            }
        }
        val gpuPercent = gpuBusyRaw
            ?.split(Regex("\\s+"))
            ?.mapNotNull { it.toLongOrNull() }
            ?.takeIf { values -> values.size >= 2 }
            ?.let { values ->
                if (values[1] <= 0L) 0f else (values[0].toDouble() / values[1] * 100.0).toFloat().coerceIn(0f, 100f)
            }
        val frequencyMhz = if (cpuFrequencies.isEmpty()) {
            null
        } else {
            (cpuFrequencies.average() / 1000.0).roundToInt().takeIf { it > 0 }
        }
        var surfaceCelsius = -1f
        var hotspotCelsius = -1f
        thermalTemps.forEach { (type, rawValue) ->
            val lower = type.lowercase(Locale.US)
            val celsius = if (rawValue > 200f) rawValue / 1000f else rawValue
            if (celsius <= 5f || celsius > 90f) return@forEach
            val isSurface = lower.contains("battery") || lower.contains("skin") ||
                lower.contains("shell") || lower.contains("surface") || lower.contains("back")
            val isHotspot = lower.contains("cpu") || lower.contains("soc") || lower.contains("gpu")
            if (isSurface) {
                if (celsius > surfaceCelsius) surfaceCelsius = celsius
            } else if (isHotspot && celsius > hotspotCelsius) {
                hotspotCelsius = celsius
            }
        }
        val temperature = when {
            surfaceCelsius > 0f -> surfaceCelsius
            hotspotCelsius > 0f -> hotspotCelsius
            else -> null
        }
        return PrivilegedReading(
            cpuFreqMhz = frequencyMhz,
            gpuPercent = gpuPercent,
            gpuName = formatGpuModel(gpuModelRaw),
            temperatureC = temperature,
        )
    }

    
    private fun readLongFile(file: File): Long? = try {
        if (!file.exists() || !file.canRead()) null else file.readText().trim().toLongOrNull()
    } catch (t: Throwable) {
        null
    }

    private data class CpuTicks(val total: Long, val idle: Long)

    private data class NetBytes(val rx: Long, val tx: Long)

    private data class MemoryReading(val used: Long, val total: Long)

    private data class StorageReading(val used: Long, val total: Long)

    private data class GpuReading(val percent: Float?, val name: String?)

    private data class BatteryReading(
        val percent: Int,
        val temperatureC: Float?,
        val charging: Boolean,
        val currentMa: Int?,
    )

    private data class PrivilegedReading(
        val cpuFreqMhz: Int?,
        val gpuPercent: Float?,
        val gpuName: String?,
        val temperatureC: Float?,
    )

    private companion object {
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val STORAGE_INTERVAL_MS = 10_000L
        const val PRIVILEGED_INTERVAL_MS = 10_000L
        const val PRIVILEGED_TIMEOUT_MS = 4_000L
        const val CPU_STAT_TIMEOUT_MS = 2_000L
        const val HISTORY_LIMIT = 60

        
        val PRIVILEGED_METRICS_SCRIPT: String = """
            echo XG_CPU_FREQ_BEGIN
            for c in /sys/devices/system/cpu/cpu*/cpufreq/scaling_cur_freq; do echo "${'$'}(cat "${'$'}c" 2>/dev/null)"; done
            echo XG_CPU_FREQ_END
            echo -n 'XG_GPU_BUSY='; echo "${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpubusy 2>/dev/null)"
            echo -n 'XG_GPU_MODEL='; echo "${'$'}(cat /sys/class/kgsl/kgsl-3d0/gpu_model 2>/dev/null)"
            echo XG_THERMAL_BEGIN
            for z in /sys/class/thermal/thermal_zone*; do t=${'$'}(cat "${'$'}z/type" 2>/dev/null); v=${'$'}(cat "${'$'}z/temp" 2>/dev/null); echo "${'$'}t|${'$'}v"; done
            echo XG_THERMAL_END
        """.trimIndent()
    }
}
