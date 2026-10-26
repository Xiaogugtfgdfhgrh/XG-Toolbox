package com.xg.toolbox.core.rootops

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StartupRecoveryJournal @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun record(stage: String, ok: Boolean, detail: String) {
        val now = System.currentTimeMillis()
        val line = "${if (ok) "OK" else "FAIL"} ${format(now)} $stage: ${detail.take(240)}"
        val previous = prefs.getString(KEY_REPORT, "").orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(LAST_RECORDS - 1)
            .toList()
            .joinToString("\n")
        prefs.edit()
            .putLong("$KEY_STAGE_TIME_PREFIX$stage", now)
            .putBoolean("$KEY_STAGE_OK_PREFIX$stage", ok)
            .putString("$KEY_STAGE_DETAIL_PREFIX$stage", detail.take(500))
            .putString(KEY_REPORT, listOf(previous, line).filter { it.isNotBlank() }.joinToString("\n"))
            .commit()
    }

    fun lastReport(): String = prefs.getString(KEY_REPORT, "").orEmpty()

    fun stage(stage: String): String? {
        if (!prefs.contains("$KEY_STAGE_TIME_PREFIX$stage")) return null
        val ok = prefs.getBoolean("$KEY_STAGE_OK_PREFIX$stage", false)
        val detail = prefs.getString("$KEY_STAGE_DETAIL_PREFIX$stage", "").orEmpty()
        return "${if (ok) "OK" else "FAIL"} $stage: $detail"
    }

    private fun format(at: Long): String =
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(at))

    private companion object {
        const val PREFS = "startup_recovery_journal"
        const val KEY_REPORT = "last_report"
        const val KEY_STAGE_TIME_PREFIX = "stage_time_"
        const val KEY_STAGE_OK_PREFIX = "stage_ok_"
        const val KEY_STAGE_DETAIL_PREFIX = "stage_detail_"
        const val LAST_RECORDS = 12
    }
}
