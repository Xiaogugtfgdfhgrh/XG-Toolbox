package com.xg.toolbox.core.common

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object XgLog {
    private const val TAG = "XgToolbox"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_FILES = 7

    private val lock = Any()
    private var logDir: File? = null
    private val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        synchronized(lock) {
            logDir = File(context.filesDir, "logs").apply { mkdirs() }
            pruneOldFiles()
        }
    }

    fun d(tag: String, message: String) = write("D", tag, message, null)

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String) = write("W", tag, message, null)

    fun e(tag: String, message: String, t: Throwable? = null) = write("E", tag, message, t)

    private fun write(level: String, tag: String, message: String, t: Throwable?) {
        if (t == null) {
            Log.println(level.toLogLevel(), "$TAG/$tag", message)
        } else {
            Log.e("$TAG/$tag", message, t)
        }
        val dir = logDir ?: return
        val line = buildString {
            append(timeFormat.format(Date()))
            append(' ')
            append(level)
            append(' ')
            append(tag)
            append(' ')
            append(message.replace('\n', ' '))
            if (t != null) append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
            append('\n')
        }
        synchronized(lock) {
            try {
                val file = File(dir, "xg-${dateFormat.format(Date())}.log")
                if (file.exists() && file.length() > MAX_FILE_BYTES) return
                file.appendText(line, Charsets.UTF_8)
                if (t != null) {
                    file.appendText(t.stackTraceToString().take(64_000), Charsets.UTF_8)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun pruneOldFiles() {
        val dir = logDir ?: return
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("xg-") }
            ?.sortedByDescending { it.name } ?: return
        files.drop(MAX_FILES).forEach { runCatching { it.delete() } }
    }

    private fun String.toLogLevel(): Int = when (this) {
        "D" -> Log.DEBUG
        "I" -> Log.INFO
        "W" -> Log.WARN
        else -> Log.ERROR
    }
}
