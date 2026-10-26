package com.xg.toolbox.feature.security.domain

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.SingleFlightValueCache
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.rootError
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class AccessibilityGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val settingsStore: SettingsStore,
    private val audit: AuditRepository,
    private val snapshots: SnapshotRepository,
    private val rootStatus: RootStatusRepository,
) {

    
    private val cache = SingleFlightValueCache<AccessibilityState>()

    suspend fun read(forceRefresh: Boolean = false): AccessibilityState {
        val loaded = cache.get(forceRefresh) {
            val whitelist = try {
                settingsStore.a11yWhitelist.first()
                    .mapNotNull(::normalizeServiceId)
                    .toSet()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.w(TAG, "读取无障碍白名单失败: ${t.javaClass.simpleName}")
                return@get null
            }
            val snapshot = readSettings() ?: return@get null
            AccessibilityState(
                enabledServices = snapshot.services,
                locked = whitelist.isNotEmpty(),
                whitelist = whitelist,
            )
        }
        return loaded ?: AccessibilityState(
            enabledServices = emptyList(),
            locked = false,
            whitelist = emptySet(),
        )
    }

    suspend fun applyWhitelist(whitelist: Set<String>): XgResult<List<String>> =
        withContext(Dispatchers.IO) {
            val normalizedWhitelist = linkedSetOf<String>()
            for (raw in whitelist) {
                val id = normalizeServiceId(raw)
                if (id == null) {
                    val message = "白名单包含非法的无障碍服务标识：$raw"
                    recordAudit(
                        action = ACTION_APPLY_WHITELIST,
                        target = SETTING_SERVICES,
                        commands = "",
                        result = AuditResult.FAILED,
                        message = message,
                    )
                    return@withContext XgResult.Err(
                        ErrorCode.ERR_PARSE_FAILED,
                        message,
                        raw,
                    )
                }
                normalizedWhitelist += id
            }

            if (normalizedWhitelist.isEmpty()) {
                return@withContext unlock()
            }

            rootStatus.state.value.rootError()?.let { error ->
                recordAudit(
                    action = ACTION_APPLY_WHITELIST,
                    target = SETTING_SERVICES,
                    commands = "",
                    result = AuditResult.FAILED,
                    message = error.message,
                )
                return@withContext error
            }

            val current = readSettings()
            if (current == null) {
                val message = "读取当前无障碍服务失败"
                recordAudit(
                    action = ACTION_APPLY_WHITELIST,
                    target = SETTING_SERVICES,
                    commands = readCommand(),
                    result = AuditResult.FAILED,
                    message = message,
                )
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, message)
            }

            val snapshotId = try {
                snapshots.create(
                    kind = SECURITY_SNAPSHOT_KIND,
                    target = SETTING_SERVICES,
                    payload = settingsSnapshotPayload(current),
                    ttlMs = SECURITY_SNAPSHOT_TTL_MS,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.e(TAG, "创建无障碍设置快照失败", t)
                val message = "创建快照失败，未执行写入"
                recordAudit(
                    action = ACTION_APPLY_WHITELIST,
                    target = SETTING_SERVICES,
                    commands = readCommand(),
                    result = AuditResult.FAILED,
                    message = "$message：${t.message.orEmpty()}".take(500),
                )
                return@withContext XgResult.Err(
                    ErrorCode.ERR_BACKUP_FAILED,
                    message,
                    t.message,
                )
            }

            if (!checkPermission()) {
                val message = "无法授予必要权限：${Manifest.permission.WRITE_SECURE_SETTINGS}"
                recordAudit(
                    action = ACTION_APPLY_WHITELIST,
                    target = SETTING_SERVICES,
                    commands = grantCommand(),
                    result = AuditResult.FAILED,
                    message = message,
                    snapshotId = snapshotId,
                )
                return@withContext XgResult.Err(
                    ErrorCode.ERR_PERMISSION_GRANT,
                    message,
                    "pm grant 未生效或 Root 不可用",
                )
            }

            val filtered = current.services.filter { it in normalizedWhitelist }.distinct()
            val value = filtered.joinToString(":")
            val writeServices = "settings put secure $SETTING_SERVICES ${shellQuote(value)}"
            val writeEnabled = "settings put secure $SETTING_ENABLED 1"
            val writeServicesResult = try {
                shell.exec(writeServices, root = true, timeoutMs = WRITE_TIMEOUT_MS)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.w(TAG, "写入无障碍服务异常: ${t.javaClass.simpleName}")
                null
            }
            val writeEnabledResult = if (writeServicesResult?.ok == true) {
                try {
                    shell.exec(writeEnabled, root = true, timeoutMs = WRITE_TIMEOUT_MS)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    XgLog.w(TAG, "写入无障碍总开关异常: ${t.javaClass.simpleName}")
                    null
                }
            } else {
                null
            }

            val readback = if (writeServicesResult?.ok == true && writeEnabledResult?.ok == true) {
                readSettings()
            } else {
                null
            }
            val readbackServices = readback?.services.orEmpty()
            val listMatches = readback != null &&
                readbackServices.size == filtered.size &&
                readbackServices.toSet() == filtered.toSet()
            val enabledMatches = readback != null &&
                (filtered.isEmpty() || readback?.accessibilityEnabled == true)
            val success = writeServicesResult?.ok == true &&
                writeEnabledResult?.ok == true &&
                listMatches &&
                enabledMatches
            val commands = "$writeServices; $writeEnabled"

            if (success) {
                val persistError = try {
                    settingsStore.setA11yWhitelist(normalizedWhitelist)
                    null
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    XgLog.e(TAG, "本地保存无障碍白名单失败", t)
                    t.message ?: t.javaClass.simpleName
                }
                if (persistError != null) {
                    recordAudit(
                        action = ACTION_APPLY_WHITELIST,
                        target = SETTING_SERVICES,
                        commands = commands,
                        result = AuditResult.FAILED,
                        message = "系统设置已写入，但白名单本地保存失败：$persistError",
                        snapshotId = snapshotId,
                    )
                    cache.invalidate()
                    return@withContext XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "系统设置已写入，但白名单本地保存失败",
                        persistError,
                    )
                }
                recordAudit(
                    action = ACTION_APPLY_WHITELIST,
                    target = SETTING_SERVICES,
                    commands = commands,
                    result = AuditResult.OK,
                    message = "已写入 ${filtered.size} 项服务：${filtered.joinToString(":")}".take(500),
                    snapshotId = snapshotId,
                )
                cache.invalidate()
                return@withContext XgResult.Ok(filtered)
            }

            val error = when {
                writeServicesResult == null -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "写入无障碍服务列表失败",
                    "命令未能执行",
                )

                !writeServicesResult.ok -> XgResult.Err(
                    shellFailureCode(writeServicesResult),
                    "写入无障碍服务列表失败：${shellFailureMessage(writeServicesResult)}",
                    shellDetail(writeServicesResult),
                )

                writeEnabledResult == null -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "写入无障碍总开关失败",
                    "命令未能执行",
                )

                !writeEnabledResult.ok -> XgResult.Err(
                    shellFailureCode(writeEnabledResult),
                    "写入无障碍总开关失败：${shellFailureMessage(writeEnabledResult)}",
                    shellDetail(writeEnabledResult),
                )

                readback == null -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "无障碍设置回读失败",
                    "写入命令成功但无法读取系统设置",
                )

                !listMatches -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "无障碍服务回读列表与写入不一致",
                    "期望 ${filtered.joinToString(":")}；实际 ${readbackServices.joinToString(":")}",
                )

                else -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "无障碍总开关回读不为 1",
                    "accessibility_enabled=${if (readback?.accessibilityEnabled == true) "1" else "0"}",
                )
            }
            cache.invalidate()
            recordAudit(
                action = ACTION_APPLY_WHITELIST,
                target = SETTING_SERVICES,
                commands = commands,
                result = AuditResult.FAILED,
                message = "${error.message}：${error.detail.orEmpty()}".take(500),
                snapshotId = snapshotId,
            )
            error
        }

    suspend fun checkPermission(): Boolean = withContext(Dispatchers.IO) {
        if (hasWriteSecureSettings()) return@withContext true
        rootStatus.state.value.rootError()?.let { error ->
            XgLog.w(TAG, "Root 未授权，跳过 pm grant：${error.message}")
            return@withContext false
        }
        val command = grantCommand()
        val result = try {
            shell.exec(command, root = true, timeoutMs = GRANT_TIMEOUT_MS)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.w(TAG, "授予 WRITE_SECURE_SETTINGS 异常: ${t.javaClass.simpleName}")
            null
        }
        val granted = hasWriteSecureSettings()
        recordAudit(
            action = ACTION_GRANT_PERMISSION,
            target = context.packageName,
            commands = command,
            result = if (granted) AuditResult.OK else AuditResult.FAILED,
            message = if (granted) {
                "已授予 WRITE_SECURE_SETTINGS"
            } else {
                val detail = result?.let(::shellDetail).orEmpty()
                "无法授予 WRITE_SECURE_SETTINGS${if (detail.isBlank()) "" else "：$detail"}".take(500)
            },
        )
        granted
    }

    private suspend fun unlock(): XgResult<List<String>> {
        val previous = try {
            settingsStore.a11yWhitelist.first().toSet()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.w(TAG, "读取原无障碍白名单失败: ${t.javaClass.simpleName}")
            val message = "读取原白名单失败，未关闭锁定"
            recordAudit(
                action = ACTION_APPLY_WHITELIST,
                target = A11Y_WHITELIST_TARGET,
                commands = "",
                result = AuditResult.FAILED,
                message = "$message：${t.message.orEmpty()}".take(500),
            )
            return XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, message, t.message)
        }
        val snapshotId = try {
            snapshots.create(
                kind = SECURITY_SNAPSHOT_KIND,
                target = A11Y_WHITELIST_TARGET,
                payload = buildString {
                    append('{')
                    append("\"whitelist\":").append(jsonStringArray(previous))
                    append('}')
                },
                ttlMs = SECURITY_SNAPSHOT_TTL_MS,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.e(TAG, "创建无障碍白名单快照失败", t)
            val message = "创建快照失败，未关闭锁定"
            recordAudit(
                action = ACTION_APPLY_WHITELIST,
                target = A11Y_WHITELIST_TARGET,
                commands = "",
                result = AuditResult.FAILED,
                message = "$message：${t.message.orEmpty()}".take(500),
            )
            return XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, message, t.message)
        }

        val persistError = try {
            settingsStore.setA11yWhitelist(emptySet())
            null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.e(TAG, "清空无障碍白名单失败", t)
            t.message ?: t.javaClass.simpleName
        }
        if (persistError != null) {
            recordAudit(
                action = ACTION_APPLY_WHITELIST,
                target = A11Y_WHITELIST_TARGET,
                commands = "",
                result = AuditResult.FAILED,
                message = "关闭无障碍锁定失败：$persistError",
                snapshotId = snapshotId,
            )
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "关闭无障碍锁定失败",
                persistError,
            )
        }
        recordAudit(
            action = ACTION_APPLY_WHITELIST,
            target = A11Y_WHITELIST_TARGET,
            commands = "",
            result = AuditResult.OK,
            message = "已关闭无障碍锁定（系统服务列表未修改）",
            snapshotId = snapshotId,
        )
        cache.invalidate()
        return XgResult.Ok(emptyList())
    }

    private suspend fun readSettings(): SettingsSnapshot? {
        val command = readCommand()
        val result = try {
            shell.exec(command, root = true, timeoutMs = READ_TIMEOUT_MS)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.w(TAG, "读取无障碍设置异常: ${t.javaClass.simpleName}")
            return null
        }
        if (!result.ok) {
            XgLog.w(TAG, "读取无障碍设置失败: ${shellFailureMessage(result)}")
            return null
        }
        val lines = result.stdout.lines()
        val markerIndex = lines.indexOfFirst { it.trim() == ENABLED_MARKER }
        if (markerIndex < 0) {
            XgLog.w(TAG, "无障碍设置输出缺少分隔标记")
            return null
        }
        val servicesRaw = lines.subList(0, markerIndex).joinToString("\n").trim()
        val enabledRaw = lines.drop(markerIndex + 1).joinToString("\n").trim()
        return SettingsSnapshot(
            services = parseServiceList(servicesRaw),
            accessibilityEnabled = enabledRaw == "1",
        )
    }

    private fun readCommand(): String =
        "settings get secure $SETTING_SERVICES; echo '$ENABLED_MARKER'; " +
            "settings get secure $SETTING_ENABLED"

    
    private fun parseServiceList(raw: String): List<String> {
        if (raw.isBlank() || raw == "null") return emptyList()
        val tokens = raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
        val normalized = tokens.mapNotNull(::normalizeServiceId).distinct()
        if (normalized.size != tokens.size) {
            XgLog.w(TAG, "无障碍服务列表含无法解析的项：${tokens.size - normalized.size} 项")
        }
        return normalized
    }

    private fun settingsSnapshotPayload(snapshot: SettingsSnapshot): String = buildString {
        append('{')
        append("\"setting\":").append(jsonString(SETTING_SERVICES)).append(',')
        append("\"services\":").append(jsonStringArray(snapshot.services)).append(',')
        append("\"accessibilityEnabled\":").append(snapshot.accessibilityEnabled)
        append('}')
    }

    private fun hasWriteSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun grantCommand(): String =
        "pm grant ${shellQuote(context.packageName)} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    private suspend fun recordAudit(
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String?,
        snapshotId: Long? = null,
    ) {
        try {
            audit.record(
                category = AuditCategory.SECURITY,
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
            XgLog.w(TAG, "写入审计失败: ${t.javaClass.simpleName}")
        }
    }

    private data class SettingsSnapshot(
        val services: List<String>,
        val accessibilityEnabled: Boolean,
    )

    private companion object {
        const val TAG = "AccessibilityGuard"
        const val SETTING_SERVICES = "enabled_accessibility_services"
        const val SETTING_ENABLED = "accessibility_enabled"
        const val A11Y_WHITELIST_TARGET = "a11y_whitelist"
        const val ENABLED_MARKER = "@@A11Y_ENABLED"
        const val ACTION_APPLY_WHITELIST = "a11y_apply_whitelist"
        const val ACTION_GRANT_PERMISSION = "grant_write_secure_settings"
        const val READ_TIMEOUT_MS = 15_000L
        const val WRITE_TIMEOUT_MS = 15_000L
        const val GRANT_TIMEOUT_MS = 10_000L
    }
}
