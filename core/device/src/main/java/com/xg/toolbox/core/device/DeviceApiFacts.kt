package com.xg.toolbox.core.device

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import java.net.Inet4Address
import java.net.Inet6Address
import java.util.Locale

internal fun readActivityManagerTotalMem(context: Context): Long? = try {
    val manager = context.getSystemService(ActivityManager::class.java)
    if (manager == null) {
        null
    } else {
        ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.totalMem.takeIf { it > 0 }
    }
} catch (t: Throwable) {
    null
}

internal fun readDataTotalBytes(): Long? = try {
    StatFs(Environment.getDataDirectory().absolutePath).totalBytes.takeIf { it > 0 }
} catch (t: Throwable) {
    null
}

internal fun readBatteryHealthCode(context: Context): Int? {
    val health = readBatteryIntent(context)?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
    return health.takeIf { it > 0 }
}

internal fun mapBatteryHealthCode(code: Int?): String? = when (code) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
    BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "过压"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "异常"
    BatteryManager.BATTERY_HEALTH_COLD -> "过冷"
    BatteryManager.BATTERY_HEALTH_UNKNOWN -> "未知"
    else -> null
}

internal fun mapBatteryHealthText(raw: String?): String? {
    val text = raw?.lowercase(Locale.US) ?: return null
    return when {
        text.contains("good") -> "良好"
        text.contains("overheat") -> "过热"
        text.contains("dead") -> "损坏"
        text.contains("over voltage") || text.contains("overvoltage") -> "过压"
        text.contains("cold") -> "过冷"
        text.contains("warm") -> "偏热"
        text.contains("unspecified") || text.contains("failure") -> "异常"
        else -> raw?.trim()?.takeIf { it.isNotEmpty() }
    }
}

@Suppress("DEPRECATION")
internal fun readWifiSsid(context: Context): String? = try {
    val wifiManager = context.getSystemService(WifiManager::class.java)
    val ssid = wifiManager?.connectionInfo?.ssid?.removeSurrounding("\"")?.trim()
    ssid?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != "0x" }
} catch (t: Throwable) {
    null
}

internal fun parseWifiSsidLine(raw: String?): String? {
    val text = raw?.trim() ?: return null
    val ssid = Regex("SSID:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)?.trim()
    return ssid?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" && it != "0x" }
}

internal fun readIpAddresses(context: Context): Pair<String?, String?> {
    return try {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val linkProperties = connectivity?.activeNetwork?.let { network ->
            runCatching { connectivity.getLinkProperties(network) }.getOrNull()
        }
        val addresses = linkProperties?.linkAddresses.orEmpty().map { it.address }
        val ipv4 = addresses.firstOrNull { it is Inet4Address }?.hostAddress
        val ipv6 = addresses.firstOrNull {
            it is Inet6Address && !it.isLinkLocalAddress && !it.isLoopbackAddress
        }?.hostAddress
        ipv4 to ipv6
    } catch (t: Throwable) {
        null to null
    }
}

@Suppress("DEPRECATION")
internal fun readWifiMac(context: Context): String? = try {
    val wifiManager = context.getSystemService(WifiManager::class.java)
    wifiManager?.connectionInfo?.macAddress?.takeIf { isUsableMac(it) }
} catch (t: Throwable) {
    null
}

@Suppress("DEPRECATION")
internal fun readBluetoothMac(context: Context): String? = try {
    val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
    adapter?.address?.takeIf { isUsableMac(it) }
} catch (t: Throwable) {
    null
}

internal fun readDataNetworkType(context: Context): Int? = try {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    telephony?.dataNetworkType?.takeIf { it != TelephonyManager.NETWORK_TYPE_UNKNOWN }
} catch (t: Throwable) {
    null
}

internal fun readSimStateCode(context: Context): Int? = try {
    context.getSystemService(TelephonyManager::class.java)?.simState
} catch (t: Throwable) {
    null
}

internal fun readSimOperatorName(context: Context): String? = try {
    context.getSystemService(TelephonyManager::class.java)
        ?.simOperatorName
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
} catch (t: Throwable) {
    null
}

@Suppress("DEPRECATION")
internal fun readImei(context: Context): String? = try {
    context.getSystemService(TelephonyManager::class.java)?.imei?.takeIf { validImei(it) }
} catch (t: Throwable) {
    null
}

internal fun validImei(value: String?): Boolean {
    val text = value?.trim().orEmpty()
    return text.length in 14..16 && text.all { it.isDigit() } && text.any { it != '0' }
}

@Suppress("DEPRECATION")
internal fun mapDataNetworkType(type: Int): String = when (type) {
    TelephonyManager.NETWORK_TYPE_NR -> "5G"
    TelephonyManager.NETWORK_TYPE_LTE -> "4G"
    TelephonyManager.NETWORK_TYPE_IWLAN -> "VoWiFi"
    TelephonyManager.NETWORK_TYPE_HSPAP,
    TelephonyManager.NETWORK_TYPE_HSDPA,
    TelephonyManager.NETWORK_TYPE_HSUPA,
    TelephonyManager.NETWORK_TYPE_UMTS,
    TelephonyManager.NETWORK_TYPE_EVDO_0,
    TelephonyManager.NETWORK_TYPE_EVDO_A,
    TelephonyManager.NETWORK_TYPE_EVDO_B,
    TelephonyManager.NETWORK_TYPE_EHRPD,
    -> "3G"
    TelephonyManager.NETWORK_TYPE_EDGE,
    TelephonyManager.NETWORK_TYPE_GPRS,
    TelephonyManager.NETWORK_TYPE_GSM,
    TelephonyManager.NETWORK_TYPE_CDMA,
    TelephonyManager.NETWORK_TYPE_1xRTT,
    -> "2G"
    else -> "未知"
}

internal fun parseNetworkTypeProp(raw: String?): String? {
    val first = raw?.substringBefore(',')?.trim() ?: return null
    if (first.isEmpty() || first.equals("unknown", ignoreCase = true)) return null
    return when {
        first.contains("nr", ignoreCase = true) || first.contains("5g", ignoreCase = true) -> "5G"
        first.contains("lte", ignoreCase = true) -> "4G"
        first.contains("hspa", ignoreCase = true) ||
            first.contains("hsdpa", ignoreCase = true) ||
            first.contains("umts", ignoreCase = true) ||
            first.contains("evdo", ignoreCase = true) -> "3G"
        first.contains("edge", ignoreCase = true) ||
            first.contains("gprs", ignoreCase = true) ||
            first.contains("gsm", ignoreCase = true) -> "2G"
        else -> "未知"
    }
}

internal fun mapSimState(state: Int): String = when (state) {
    TelephonyManager.SIM_STATE_READY -> "已插卡"
    TelephonyManager.SIM_STATE_ABSENT -> "未插卡"
    TelephonyManager.SIM_STATE_PIN_REQUIRED -> "待输入 PIN"
    TelephonyManager.SIM_STATE_PUK_REQUIRED -> "待输入 PUK"
    TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "网络锁定"
    TelephonyManager.SIM_STATE_NOT_READY -> "未就绪"
    else -> "未知"
}

internal fun parseSimStateProp(raw: String?): Int? {
    val first = raw?.substringBefore(',')?.trim()?.uppercase(Locale.US) ?: return null
    return when (first) {
        "READY" -> TelephonyManager.SIM_STATE_READY
        "ABSENT" -> TelephonyManager.SIM_STATE_ABSENT
        "PIN_REQUIRED" -> TelephonyManager.SIM_STATE_PIN_REQUIRED
        "PUK_REQUIRED" -> TelephonyManager.SIM_STATE_PUK_REQUIRED
        "NETWORK_LOCKED" -> TelephonyManager.SIM_STATE_NETWORK_LOCKED
        "NOT_READY" -> TelephonyManager.SIM_STATE_NOT_READY
        else -> null
    }
}

internal fun mapSelinux(raw: String?): String? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        text == "1" || text.contains("Enforcing", ignoreCase = true) -> "Enforcing（强制）"
        text == "0" || text.contains("Permissive", ignoreCase = true) -> "Permissive（宽容）"
        text.contains("Disabled", ignoreCase = true) -> "Disabled（已禁用）"
        else -> text
    }
}

internal fun inferPlayIntegrity(source: FieldSource): String {
    val bootState = source.prop("ro.boot.verifiedbootstate")
    val flashLocked = source.prop("ro.boot.flash.locked")
    val verityMode = source.prop("ro.boot.veritymode")
    val reasons = mutableListOf<String>()
    if (bootState == null && flashLocked == null) {
        reasons += "Bootloader 状态未知"
    } else {
        if (bootState != null && !bootState.equals("green", ignoreCase = true)) {
            reasons += "verifiedbootstate=$bootState"
        }
        if (flashLocked == "0") reasons += "flash.locked=0"
    }
    if (verityMode == null) {
        reasons += "dm-verity 状态未知"
    } else if (!verityMode.equals("enforcing", ignoreCase = true)) {
        reasons += "veritymode=$verityMode"
    }
    if (Build.TAGS?.contains("release-keys") != true) reasons += "构建标签非 release-keys"
    if (Build.TYPE != "user") reasons += "构建类型=${Build.TYPE}"
    return if (reasons.isEmpty()) {
        "本地推断：通过（Bootloader 锁定 · dm-verity 强制 · release-keys）"
    } else {
        "本地推断：设备完整性可能未通过（${reasons.joinToString(" · ")}）"
    }
}

internal fun mapToggleState(raw: String?): String? = when (raw?.trim()?.lowercase(Locale.US)) {
    "1", "true" -> "已开启"
    "0", "false" -> "已关闭"
    else -> raw?.trim()?.takeIf { it.isNotEmpty() }
}

internal fun parseWebViewVersion(raw: String?): String? {
    val text = raw?.trim() ?: return null
    val matches = Regex("\\(([^,]+),\\s*([^)]+)\\)").findAll(text).toList()
    return matches.lastOrNull()?.groupValues?.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
}