package com.xg.toolbox.core.common

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object Formatters {

    fun bytes(bytes: Long, decimals: Int = 1): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = -1
        while (value >= 1024 && unitIndex < units.lastIndex) {
            value /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.${decimals}f %s", value, units[unitIndex])
    }

    
    fun uptime(ms: Long): String {
        val totalMinutes = ms / 60_000
        val days = totalMinutes / (24 * 60)
        val hours = totalMinutes % (24 * 60) / 60
        val minutes = totalMinutes % 60
        return when {
            days > 0 -> String.format(Locale.US, "%d 天 %02d 小时 %02d 分", days, hours, minutes)
            hours > 0 -> String.format(Locale.US, "%d 小时 %02d 分", hours, minutes)
            else -> "$minutes 分"
        }
    }

    fun duration(ms: Long): String {
        val seconds = ms / 1000.0
        return when {
            ms < 1000 -> "$ms ms"
            seconds < 60 -> String.format(Locale.US, "%.1f s", seconds)
            else -> String.format(Locale.US, "%d 分 %02d 秒", (ms / 60_000).toInt(), (ms / 1000 % 60).toInt())
        }
    }

    fun time(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

    fun dateTime(ts: Long): String =
        SimpleDateFormat("MM 月 dd 日 HH:mm", Locale.getDefault()).format(Date(ts))

    fun fullDateTime(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

    fun relative(ts: Long, now: Long = System.currentTimeMillis()): String {
        val delta = now - ts
        return when {
            delta < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
            delta < TimeUnit.HOURS.toMillis(1) -> "${delta / 60_000} 分钟前"
            delta < TimeUnit.DAYS.toMillis(1) -> "${delta / 3_600_000} 小时前"
            delta < TimeUnit.DAYS.toMillis(2) -> "昨天 ${time(ts)}"
            delta < TimeUnit.DAYS.toMillis(7) -> "${delta / TimeUnit.DAYS.toMillis(1)} 天前"
            else -> dateTime(ts)
        }
    }

    fun percent(value: Float, decimals: Int = 0): String =
        String.format(Locale.US, "%.${decimals}f", value)

    fun mhz(khz: Long): String = if (khz >= 1_000_000) {
        String.format(Locale.US, "%.1f GHz", khz / 1_000_000.0)
    } else {
        String.format(Locale.US, "%.0f MHz", khz / 1000.0)
    }

    fun hex16(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return hex16(digest)
    }

    fun isValidAndroidId(value: String): Boolean =
        value.length == 16 && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun safeLong(text: String?): Long? = text?.trim()?.let { runCatching { it.toLong() }.getOrNull() }

    fun safeInt(text: String?): Int? = text?.trim()?.let { runCatching { it.toInt() }.getOrNull() }

    fun formatSignedBytes(value: Long, decimals: Int = 1): String =
        (if (value < 0) "-" else "") + bytes(abs(value), decimals)
}
