package com.xg.toolbox.feature.apps.domain

import android.content.Context
import android.content.pm.PackageManager
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.ShellResult
import com.xg.toolbox.core.shell.rootError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
internal class AppDataController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val audit: AuditRepository,
    private val snapshots: SnapshotRepository,
) {

    private val selfPackage: String = context.packageName

    
    suspend fun clearCache(pkg: String): XgResult<Unit> =
        clear(pkg = pkg, cacheOnly = true)

    
    suspend fun clearData(pkg: String): XgResult<Unit> =
        clear(pkg = pkg, cacheOnly = false)

    private suspend fun clear(pkg: String, cacheOnly: Boolean): XgResult<Unit> =
        withContext(Dispatchers.IO) {
            val target = pkg.trim()
            when {
                !AppShellText.isValidPackage(target) -> {
                    return@withContext XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "非法包名：$pkg",
                    )
                }

                target == selfPackage -> {
                    return@withContext XgResult.Err(
                        ErrorCode.ERR_POLICY_BLOCKED,
                        "不能清除本应用数据",
                    )
                }

                !cacheOnly && target in CORE_DATA_PACKAGES -> {
                    return@withContext XgResult.Err(
                        ErrorCode.ERR_POLICY_BLOCKED,
                        "核心系统包禁止清除数据：$target",
                    )
                }
            }

            try {
                context.packageManager.getPackageInfo(target, 0)
            } catch (t: PackageManager.NameNotFoundException) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "应用未安装：$target",
                    t.message,
                )
            } catch (t: Throwable) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "无法读取应用信息：$target",
                    t.message,
                )
            }

            rootStatus.state.value.rootError()?.let { return@withContext it }

            val forceStopCommand = "am force-stop ${AppShellText.quote(target)}"
            val clearCommand = if (cacheOnly) {
                "pm clear --cache-only ${AppShellText.quote(target)}"
            } else {
                "pm clear ${AppShellText.quote(target)}"
            }
            val commands = listOf(forceStopCommand, clearCommand)
            val action = if (cacheOnly) ACTION_CLEAR_CACHE else ACTION_CLEAR_DATA
            val label = if (cacheOnly) "清除缓存" else "清除应用数据"

            val snapshotId = if (cacheOnly) {
                null
            } else {
                val now = System.currentTimeMillis()
                val payload = JSONObject()
                    .put("packageName", target)
                    .put("timestamp", now)
                    .put("timestampIso", Instant.ofEpochMilli(now).toString())
                    .put("action", KIND_APP_DATA_CLEARED)
                    .put("irreversible", true)
                    .put("commands", JSONArray().apply { commands.forEach { put(it) } })
                    .toString()
                try {
                    snapshots.create(
                        kind = KIND_APP_DATA_CLEARED,
                        target = target,
                        payload = payload,
                        ttlMs = SNAPSHOT_TTL_MS,
                    )
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    XgLog.e(TAG, "清除数据快照写入失败", t)
                    return@withContext XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "无法创建清除数据快照，已中止",
                        t.message,
                    )
                }
            }

            val forceStopResult = shell.exec(
                forceStopCommand,
                root = true,
                timeoutMs = FORCE_STOP_TIMEOUT_MS,
            )
            val clearResult = shell.exec(
                clearCommand,
                root = true,
                timeoutMs = CLEAR_TIMEOUT_MS,
            )
            val output = rawOutput(clearResult)
            var success = SUCCESS_WORD.containsMatchIn(output) &&
                !FAILURE_WORD.containsMatchIn(output)
            var viaCacheDir = false
            if (!success && cacheOnly) {
                val quoted = AppShellText.quote(target)
                val fallback = shell.exec(
                    "rm -rf /data/data/$quoted/cache/* /data/user/0/$quoted/cache/* 2>/dev/null; " +
                        "find /data/data/$quoted/cache /data/user/0/$quoted/cache -mindepth 1 2>/dev/null | wc -l",
                    root = true,
                    timeoutMs = CLEAR_TIMEOUT_MS,
                )
                val remaining = fallback.stdout.trim().lineSequence().lastOrNull()?.trim()?.toIntOrNull()
                if (fallback.code == 0 && remaining == 0) {
                    success = true
                    viaCacheDir = true
                }
            }
            if (success) {
                val message = buildString {
                    append(label).append("成功（pm clear 返回 Success）")
                    if (viaCacheDir) {
                        append("；pm clear --cache-only 不可用，已通过 Root 删除缓存目录")
                    }
                    if (cacheOnly) {
                        if (!forceStopResult.ok) {
                            append("；force-stop 失败但已忽略（exit=${forceStopResult.code}）")
                        }
                    } else {
                        append("；数据不可恢复")
                        if (!forceStopResult.ok) {
                            append("；force-stop 失败但已继续（exit=${forceStopResult.code}）")
                        }
                    }
                }
                safeAudit(
                    action = action,
                    target = target,
                    commands = commands.joinToString("; "),
                    result = AuditResult.OK,
                    message = message,
                    snapshotId = snapshotId,
                )
                XgResult.Ok(Unit)
            } else {
                val message = "$label 失败：pm clear 未返回 Success"
                safeAudit(
                    action = action,
                    target = target,
                    commands = commands.joinToString("; "),
                    result = AuditResult.FAILED,
                    message = message,
                    snapshotId = snapshotId,
                )
                XgResult.Err(
                    code = AppShellText.normalizeCode(clearResult.code),
                    message = message,
                    detail = failureDetail(clearResult, forceStopResult),
                )
            }
        }

    
    private suspend fun safeAudit(
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String?,
        snapshotId: Long?,
    ) {
        try {
            audit.record(
                category = AuditCategory.APP,
                action = action,
                target = target,
                commands = commands,
                result = result,
                message = message,
                snapshotId = snapshotId,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.w(TAG, "应用数据审计写入失败：${t.message}")
        }
    }

    
    private fun failureDetail(clearResult: ShellResult, forceStopResult: ShellResult): String {
        val stderr = clearResult.stderr.trim()
        val stdout = clearResult.stdout.trim()
        return buildString {
            when {
                stderr.isNotEmpty() -> append("stderr:\n").append(stderr)
                stdout.isNotEmpty() -> append("命令输出:\n").append(stdout)
                else -> append("pm clear 无输出")
            }
            append("\nexit=").append(clearResult.code)
            if (!forceStopResult.ok) {
                append("\nforce-stop exit=").append(forceStopResult.code)
                val forceStopErr = forceStopResult.stderr.trim()
                if (forceStopErr.isNotEmpty()) {
                    append("\nforce-stop stderr:\n").append(forceStopErr)
                }
            }
        }.take(MAX_DETAIL_CHARS)
    }

    private fun rawOutput(result: ShellResult): String =
        listOf(result.stdout, result.stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private companion object {
        const val TAG = "AppDataController"
        const val KIND_APP_DATA_CLEARED = "app_data_cleared"
        const val ACTION_CLEAR_CACHE = "clear_app_cache"
        const val ACTION_CLEAR_DATA = "clear_app_data"
        const val FORCE_STOP_TIMEOUT_MS = 15_000L
        const val CLEAR_TIMEOUT_MS = 60_000L
        const val SNAPSHOT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_DETAIL_CHARS = 4_000

        val SUCCESS_WORD = Regex("""\bSuccess\b""")
        val FAILURE_WORD = Regex("""(?i)\bFailure\b|\bError\b""")

        
        val CORE_DATA_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.phone",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.providers.settings",
            "com.android.providers.telephony",
            "com.android.providers.contacts",
            "com.android.providers.media",
            "com.android.shell",
            "com.android.bluetooth",
            "com.android.nfc",
            "com.android.server.telecom",
            "system_server",
        )
    }
}
