package com.xg.toolbox.core.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaDrm
import android.provider.Settings
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.common.XgLog
import java.io.File
import java.security.MessageDigest
import java.util.Locale

internal data class FieldValue(
    val value: String?,
    val restriction: FieldRestriction = FieldRestriction.NONE,
    val note: String? = null,
)

internal fun makeField(
    id: String,
    groupId: String,
    name: String,
    propertyKey: String,
    unavailableNote: String,
    resolver: () -> FieldValue,
): DeviceField {
    val resolved = try {
        resolver()
    } catch (t: Throwable) {
        FieldValue(value = null)
    }
    val value = resolved.value?.trim()?.takeIf { it.isNotEmpty() }
    val restriction = when {
        value == null && resolved.restriction == FieldRestriction.NONE -> FieldRestriction.UNAVAILABLE
        value == null && resolved.restriction == FieldRestriction.MASKED -> FieldRestriction.UNAVAILABLE
        else -> resolved.restriction
    }
    return DeviceField(
        id = id,
        groupId = groupId,
        name = name,
        propertyKey = propertyKey,
        value = value,
        restriction = restriction,
        note = resolved.note ?: if (value == null) unavailableNote else null,
    )
}

internal fun readGlobalSetting(context: Context, key: String): String? = try {
    Settings.Global.getString(context.contentResolver, key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
} catch (t: Throwable) {
    null
}

internal fun readSecureSetting(context: Context, key: String): String? = try {
    Settings.Secure.getString(context.contentResolver, key)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
} catch (t: Throwable) {
    null
}

internal fun readBatteryIntent(context: Context): Intent? = try {
    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
} catch (t: Throwable) {
    null
}

internal fun readTextFile(path: String): String? = try {
    val file = File(path)
    when {
        !file.exists() -> null
        !file.canRead() -> {
            logReadFailureOnce(path, "不可读（可能被 SELinux 或权限策略拒绝）")
            null
        }
        else -> file.readText(Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }
} catch (t: Throwable) {
    logReadFailureOnce(path, "${t.javaClass.simpleName}: ${t.message}")
    null
}

private val loggedReadFailures: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

private const val MAX_READ_FAILURE_LOGS = 8

private fun logReadFailureOnce(path: String, reason: String) {
    if (loggedReadFailures.size >= MAX_READ_FAILURE_LOGS) return
    if (!loggedReadFailures.add(path)) return
    XgLog.w("DeviceField", "读取失败 $path（$reason）")
}

internal fun firstNonBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }?.trim()

internal fun isUsableMac(mac: String?): Boolean {
    val text = mac?.trim() ?: return false
    if (text == "02:00:00:00:00:00") return false
    return Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$").matches(text)
}

internal fun parseWmSize(raw: String?, useOverride: Boolean = false): String? {
    val text = raw ?: return null
    val entries = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    val physical = entries.firstOrNull { it.startsWith("Physical size:") }
    val override = entries.firstOrNull { it.startsWith("Override size:") }
    val chosen = when {
        useOverride -> override ?: physical
        else -> physical ?: override
    } ?: return null
    val match = Regex("(\\d+)\\s*x\\s*(\\d+)").find(chosen) ?: return null
    val width = match.groupValues[1]
    val height = match.groupValues[2]
    return "$width × $height"
}

internal fun parseWmDensity(raw: String?): String? {
    val text = raw ?: return null
    val entries = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }
    val override = entries.firstOrNull { it.startsWith("Override density:") }
    val physical = entries.firstOrNull { it.startsWith("Physical density:") }
    val chosen = override ?: physical ?: return null
    val dpi = Regex("(\\d+)").find(chosen.substringAfter(':'))?.groupValues?.getOrNull(1) ?: return null
    return "$dpi dpi"
}

internal fun parseMaxRefreshHz(raw: String?): Float? {
    val text = raw ?: return null
    return Regex("fps=([0-9]+(?:\\.[0-9]+)?)").findAll(text)
        .mapNotNull { it.groupValues.getOrNull(1)?.toFloatOrNull() }
        .filter { it > 0f }
        .maxOrNull()
}

internal fun parseGlesName(line: String?): String? {
    val text = line?.trim() ?: return null
    if (!text.startsWith("GLES:")) return null
    val parts = text.removePrefix("GLES:").split(',').map { it.trim() }
    val vendor = parts.getOrNull(0)?.takeIf { it.isNotEmpty() }
    val renderer = parts.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: return null
    val cleanRenderer = renderer
        .replace("(TM)", "")
        .replace("(R)", "")
        .replace("  ", " ")
        .trim()
    return if (vendor.isNullOrBlank() || vendor.equals("Qualcomm", ignoreCase = true)) {
        cleanRenderer
    } else {
        "$cleanRenderer（$vendor）"
    }
}

internal fun formatGpuModel(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return text.replace(Regex("([A-Za-z])(\\d)"), "$1 $2")
}

@Suppress("DEPRECATION")
internal fun readWidevineDeviceIdHash(): String? {
    var mediaDrm: MediaDrm? = null
    return try {
        mediaDrm = MediaDrm(WIDEVINE_UUID)
        val uniqueId = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
        if (uniqueId.isEmpty()) {
            null
        } else {
            MessageDigest.getInstance("SHA-256").digest(uniqueId)
                .let { Formatters.hex16(it).take(16) }
        }
    } catch (t: Throwable) {
        null
    } finally {
        runCatching { mediaDrm?.release() }
    }
}

internal fun readMemTotalBytes(): Long? {
    val text = readTextFile("/proc/meminfo") ?: return null
    return text.lineSequence()
        .firstOrNull { it.startsWith("MemTotal:") }
        ?.let { Regex("(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
        ?.times(1024L)
}

internal fun readWebViewPackage(): String? = try {
    android.webkit.WebView.getCurrentWebViewPackage()
        ?.versionName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
} catch (t: Throwable) {
    null
}

internal fun formatPercent(value: Float): String = String.format(Locale.US, "%.0f%%", value)

internal fun formatMilliampHours(microAmpHours: Long): String =
    "${Math.round(microAmpHours / 1000.0)} mAh"

private val WIDEVINE_UUID: java.util.UUID = java.util.UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
