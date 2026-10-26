package com.xg.toolbox.feature.home

import android.content.Context
import androidx.compose.runtime.Immutable
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.device.MetricsSnapshot
import java.util.Locale
import kotlin.math.abs

@Immutable
data class HomeMetricUi(
    val value: String,
    val unit: String,
    val hint: String,
    val points: FloatArray,
    val pointsCount: Int,
    val revision: Int,
)

@Immutable
data class HomeMetricsUi(
    val hasData: Boolean = false,
    val revision: Int = 0,
    val cpu: HomeMetricUi = EMPTY_METRIC,
    val gpu: HomeMetricUi = EMPTY_METRIC,
    val memory: HomeMetricUi = EMPTY_METRIC,
    val temperature: HomeMetricUi = EMPTY_METRIC,
    val battery: HomeMetricUi = EMPTY_METRIC,
    val storage: HomeMetricUi = EMPTY_METRIC,
)

private const val DASH = "—"

private const val POINTS_CAPACITY = 60

private val EMPTY_METRIC = HomeMetricUi(
    value = DASH,
    unit = "",
    hint = DASH,
    points = FloatArray(0),
    pointsCount = 0,
    revision = 0,
)

internal val EmptyHomeMetricsUi: HomeMetricsUi = HomeMetricsUi()

internal class HomeMetricsMapper(private val context: Context) {

    private val cpuPoints = FloatArray(POINTS_CAPACITY)
    private val gpuPoints = FloatArray(POINTS_CAPACITY)
    private val memoryPoints = FloatArray(POINTS_CAPACITY)
    private val temperaturePoints = FloatArray(POINTS_CAPACITY)
    private val batteryPoints = FloatArray(POINTS_CAPACITY)
    private val storagePoints = FloatArray(POINTS_CAPACITY)

    private var revision: Int = 0

    fun map(history: List<MetricsSnapshot>): HomeMetricsUi {
        revision += 1
        val latest = history.lastOrNull()
        val cpuCount = fill(history, cpuPoints) { snapshot -> snapshot.cpuPercent }
        val gpuCount = fill(history, gpuPoints) { snapshot -> snapshot.gpuPercent }
        val memoryCount = fill(history, memoryPoints) { snapshot ->
            percentOf(snapshot.memUsedBytes, snapshot.memTotalBytes)
        }
        val temperatureCount = fill(history, temperaturePoints) { snapshot -> snapshot.tempC }
        val batteryCount = fill(history, batteryPoints) { snapshot ->
            snapshot.batteryPercent.takeIf { percent -> percent >= 0 }?.toFloat()
        }
        val storageCount = fill(history, storagePoints) { snapshot ->
            percentOf(snapshot.storageUsedBytes, snapshot.storageTotalBytes)
        }
        val rev = revision
        return HomeMetricsUi(
            hasData = latest != null,
            revision = rev,
            cpu = HomeMetricUi(
                value = latest?.cpuPercent?.let { Formatters.percent(it, 0) } ?: DASH,
                unit = if (latest?.cpuPercent != null) "%" else "",
                hint = cpuHint(latest),
                points = cpuPoints,
                pointsCount = cpuCount,
                revision = rev,
            ),
            gpu = HomeMetricUi(
                value = latest?.gpuPercent?.let { Formatters.percent(it, 0) } ?: DASH,
                unit = if (latest?.gpuPercent != null) "%" else "",
                hint = latest?.gpuName ?: DASH,
                points = gpuPoints,
                pointsCount = gpuCount,
                revision = rev,
            ),
            memory = HomeMetricUi(
                value = latest?.let { percentOf(it.memUsedBytes, it.memTotalBytes) }?.let {
                    Formatters.percent(it, 0)
                } ?: DASH,
                unit = if (latest != null && latest.memTotalBytes > 0) "%" else "",
                hint = ratioHint(latest?.memUsedBytes ?: 0L, latest?.memTotalBytes ?: 0L),
                points = memoryPoints,
                pointsCount = memoryCount,
                revision = rev,
            ),
            temperature = HomeMetricUi(
                value = latest?.tempC?.let(::formatDecimal) ?: DASH,
                unit = if (latest?.tempC != null) "°C" else "",
                hint = latest?.batteryTempC?.let { batteryTemp ->
                    context.getString(
                        R.string.home_metric_hint_battery_temp,
                        formatDecimal(batteryTemp),
                    )
                } ?: DASH,
                points = temperaturePoints,
                pointsCount = temperatureCount,
                revision = rev,
            ),
            battery = HomeMetricUi(
                value = latest?.batteryPercent?.takeIf { it >= 0 }?.toString() ?: DASH,
                unit = if (latest != null && latest.batteryPercent >= 0) "%" else "",
                hint = batteryHint(latest),
                points = batteryPoints,
                pointsCount = batteryCount,
                revision = rev,
            ),
            storage = HomeMetricUi(
                value = latest?.let { percentOf(it.storageUsedBytes, it.storageTotalBytes) }?.let {
                    Formatters.percent(it, 0)
                } ?: DASH,
                unit = if (latest != null && latest.storageTotalBytes > 0) "%" else "",
                hint = ratioHint(latest?.storageUsedBytes ?: 0L, latest?.storageTotalBytes ?: 0L),
                points = storagePoints,
                pointsCount = storageCount,
                revision = rev,
            ),
        )
    }

    
    private inline fun fill(
        history: List<MetricsSnapshot>,
        destination: FloatArray,
        valueOf: (MetricsSnapshot) -> Float?,
    ): Int {
        var count = 0
        val limit = minOf(history.size, destination.size)
        for (index in 0 until limit) {
            valueOf(history[index])?.let { value ->
                destination[count] = value
                count += 1
            }
        }
        return count
    }

    private fun cpuHint(latest: MetricsSnapshot?): String {
        if (latest == null) return DASH
        val frequency = latest.cpuFreqMhz?.takeIf { it > 0 }?.let { mhz ->
            formatDecimal(mhz / 1000f)
        }
        return if (frequency != null) {
            context.getString(R.string.home_metric_hint_cores_ghz, latest.cpuCores, frequency)
        } else {
            context.getString(R.string.home_metric_hint_cores, latest.cpuCores)
        }
    }

    private fun ratioHint(usedBytes: Long, totalBytes: Long): String =
        if (totalBytes > 0) {
            context.getString(
                R.string.home_metric_hint_mem,
                Formatters.bytes(usedBytes),
                Formatters.bytes(totalBytes),
            )
        } else {
            DASH
        }

    private fun batteryHint(latest: MetricsSnapshot?): String {
        if (latest == null) return DASH
        val current = latest.batteryCurrentMa
        return when {
            current == null && latest.batteryCharging ->
                context.getString(R.string.home_battery_charging)
            current == null ->
                context.getString(R.string.home_battery_discharging)
            latest.batteryCharging ->
                context.getString(R.string.home_battery_hint_charging, abs(current))
            else ->
                context.getString(R.string.home_battery_hint_discharging, abs(current))
        }
    }
}

private fun percentOf(used: Long, total: Long): Float? =
    if (total > 0) (used.toDouble() / total * 100.0).toFloat() else null

private fun formatDecimal(value: Float): String = String.format(Locale.US, "%.1f", value)
