package com.xg.toolbox.feature.apps.domain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppRepository
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.rootError
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
internal class BootReceiverController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val appRepository: AppRepository,
    private val audit: AuditRepository,
    private val snapshots: SnapshotRepository,
) {

    fun components(pkg: String): List<String> {
        if (!AppShellText.isValidPackage(pkg)) return emptyList()
        val packageManager = context.packageManager
        val found = linkedSetOf<String>()
        BOOT_ACTIONS.forEach { action ->
            val intent = Intent(action).setPackage(pkg)
            val receivers = runCatching {
                packageManager.queryBroadcastReceivers(intent, PackageManager.MATCH_DISABLED_COMPONENTS)
            }.getOrDefault(emptyList())
            receivers.forEach { resolve ->
                val receiver = resolve.activityInfo ?: return@forEach
                if (receiver.packageName != pkg) return@forEach
                val className = if (receiver.name.startsWith(".")) pkg + receiver.name else receiver.name
                found += "$pkg/$className"
            }
        }
        return found.sorted()
    }

    suspend fun setEnabled(pkg: String, component: String, enabled: Boolean): XgResult<Unit> =
        withContext(Dispatchers.IO) {
            if (!AppShellText.isValidPackage(pkg)) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
            }
            val normalized = normalizeComponent(pkg, component)
            if (normalized is XgResult.Err) return@withContext normalized
            val componentPath = normalized.getOrNull()
                ?: return@withContext XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "组件名解析结果为空")
            if (appRepository.isProtected(pkg)) {
                return@withContext XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "受保护应用禁止修改组件：$pkg")
            }
            rootStatus.state.value.rootError()?.let { return@withContext it }
            val disabledBefore = disabledComponents(pkg)
                ?: return@withContext XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "无法读取组件当前状态")
            val wasDisabled = isDisabled(disabledBefore, componentPath, pkg)
            val snapshotId = snapshots.create(
                kind = KIND_BOOT_RECEIVERS,
                target = pkg,
                payload = JSONObject()
                    .put(
                        "components",
                        JSONArray().put(
                            JSONObject()
                                .put("component", componentPath)
                                .put("enabledBefore", !wasDisabled),
                        ),
                    )
                    .toString(),
                ttlMs = SNAPSHOT_TTL_MS,
            )
            val command = "pm ${if (enabled) "enable" else "disable"} ${AppShellText.quote(componentPath)}"
            val result = shell.exec(command, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
            val disabledAfter = disabledComponents(pkg)
            val success = result.ok &&
                disabledAfter != null &&
                isDisabled(disabledAfter, componentPath, pkg) == !enabled
            val action = if (enabled) ACTION_ENABLE_RECEIVER else ACTION_DISABLE_RECEIVER
            if (success) {
                recordAudit(action, pkg, command, AuditResult.OK, componentPath, snapshotId)
                XgResult.Ok(Unit)
            } else {
                val actualDisabled = disabledAfter?.let { isDisabled(it, componentPath, pkg) }
                val note = when {
                    actualDisabled == null -> "回读失败，无法确认组件状态"
                    actualDisabled == !enabled -> "命令退出码非 0"
                    else -> "回读状态与预期不一致"
                }
                val error = AppShellText.failure(
                    if (enabled) "启用开机接收器失败" else "禁用开机接收器失败",
                    result,
                    note,
                )
                recordAudit(action, pkg, command, AuditResult.FAILED, error.message, snapshotId)
                error
            }
        }

    suspend fun disableAll(pkg: String, components: List<String>): XgResult<Unit> =
        withContext(Dispatchers.IO) {
            if (!AppShellText.isValidPackage(pkg)) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
            }
            if (components.isEmpty()) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "未发现开机接收器")
            }
            if (appRepository.isProtected(pkg)) {
                return@withContext XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "受保护应用禁止修改组件：$pkg")
            }
            rootStatus.state.value.rootError()?.let { return@withContext it }
            val disabledBefore = disabledComponents(pkg)
                ?: return@withContext XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "无法读取组件当前状态")
            val prior = components.distinct().map { component ->
                component to !isDisabled(disabledBefore, component, pkg)
            }
            val payload = JSONArray().apply {
                prior.forEach { (component, enabledBefore) ->
                    put(JSONObject().put("component", component).put("enabledBefore", enabledBefore))
                }
            }
            val snapshotId = snapshots.create(
                kind = KIND_BOOT_RECEIVERS,
                target = pkg,
                payload = JSONObject().put("components", payload).toString(),
                ttlMs = SNAPSHOT_TTL_MS,
            )
            val commands = components.distinct().map { "pm disable ${AppShellText.quote(it)}" }
            val results = shell.execMany(commands, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
            val disabledAfter = disabledComponents(pkg)
            val allDisabled = disabledAfter != null && components.all { isDisabled(disabledAfter!!, it, pkg) }
            val allCommandsOk = results.all { it.ok }
            if (allCommandsOk && allDisabled) {
                recordAudit(
                    ACTION_DISABLE_AUTOSTART,
                    pkg,
                    commands.joinToString("\n"),
                    AuditResult.OK,
                    "已禁用 ${components.size} 个开机接收器",
                    snapshotId,
                )
                XgResult.Ok(Unit)
            } else {
                val failed = commands.zip(results).filter { !it.second.ok }.map { it.first }
                val detail = buildList {
                    if (failed.isNotEmpty()) add("失败命令：${failed.joinToString("; ")}")
                    if (!allDisabled) add("回读未显示全部组件已禁用")
                }.joinToString(" | ")
                val error = XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "批量禁用开机接收器失败",
                    detail.ifBlank { null },
                )
                recordAudit(
                    ACTION_DISABLE_AUTOSTART,
                    pkg,
                    commands.joinToString("\n"),
                    AuditResult.FAILED,
                    error.message,
                    snapshotId,
                )
                error
            }
        }

    suspend fun restoreLatest(pkg: String): XgResult<Unit> = withContext(Dispatchers.IO) {
        if (!AppShellText.isValidPackage(pkg)) {
            return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
        }
        rootStatus.state.value.rootError()?.let { return@withContext it }
        val snapshot = snapshots.latest(KIND_BOOT_RECEIVERS, pkg)
            ?: return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "未找到开机接收器快照")
        if (snapshot.expiresAt < System.currentTimeMillis()) {
            return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "开机接收器快照已过期")
        }
        val payload = runCatching { JSONObject(snapshot.payload) }.getOrNull()
            ?: return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "开机接收器快照内容损坏")
        val array = payload.optJSONArray("components")
            ?: return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "开机接收器快照缺少组件列表")
        val restoreTargets = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                if (!item.optBoolean("enabledBefore", false)) continue
                val component = item.optString("component")
                if (component.isNotBlank()) add(component)
            }
        }
        if (restoreTargets.isEmpty()) {
            return@withContext XgResult.Ok(Unit)
        }
        val commands = restoreTargets.map { "pm enable ${AppShellText.quote(it)}" }
        val results = shell.execMany(commands, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        val disabledAfter = disabledComponents(pkg)
        val allEnabled = disabledAfter != null && restoreTargets.none { isDisabled(disabledAfter!!, it, pkg) }
        if (results.all { it.ok } && allEnabled) {
            recordAudit(
                ACTION_UNDO_AUTOSTART,
                pkg,
                commands.joinToString("\n"),
                AuditResult.OK,
                "已恢复 ${restoreTargets.size} 个开机接收器",
                snapshot.id,
            )
            XgResult.Ok(Unit)
        } else {
            val error = XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "恢复开机接收器失败",
                "回读未显示全部组件已启用",
            )
            recordAudit(
                ACTION_UNDO_AUTOSTART,
                pkg,
                commands.joinToString("\n"),
                AuditResult.FAILED,
                error.message,
                snapshot.id,
            )
            error
        }
    }

    suspend fun disabledComponents(pkg: String): Set<String>? {
        val result = shell.exec(
            "dumpsys package ${AppShellText.quote(pkg)}",
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        )
        if (!result.ok) return null
        val lines = result.stdout.lines()
        val start = lines.indexOfFirst { it.trim() == "disabledComponents:" }
        if (start < 0) return emptySet()
        val baseIndent = indentOf(lines[start])
        val disabled = linkedSetOf<String>()
        for (index in start + 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            val indent = indentOf(line)
            if (indent <= baseIndent) break
            val entry = line.trim()
            if (entry.isBlank()) continue
            disabled += entry
            if (entry.contains("/")) disabled += entry.substringAfter("/")
        }
        return disabled
    }

    private fun normalizeComponent(pkg: String, component: String): XgResult<String> {
        val trimmed = component.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n')) {
            return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "组件名不能为空")
        }
        val hasSlash = trimmed.contains("/")
        val left = if (hasSlash) trimmed.substringBefore("/") else pkg
        if (left != pkg) {
            return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "组件必须属于包 $pkg")
        }
        val raw = if (hasSlash) trimmed.substringAfter("/") else trimmed
        val className = when {
            raw.startsWith(".") -> pkg + raw
            raw.startsWith("$pkg.") -> raw
            raw.contains(".") -> return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "组件必须属于包 $pkg")
            else -> "$pkg.$raw"
        }
        if (className.startsWith(".") ||
            className.endsWith(".") ||
            className.contains("..") ||
            !CLASS_NAME.matches(className)
        ) {
            return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法组件名：$component")
        }
        return XgResult.Ok("$pkg/$className")
    }

    private fun indentOf(line: String): Int = line.indexOfFirst { it != ' ' && it != '\t' }.let {
        if (it < 0) Int.MAX_VALUE else it
    }

    private fun isDisabled(disabled: Set<String>, componentPath: String, pkg: String): Boolean {
        val className = componentPath.substringAfter("/")
        return disabled.any { entry ->
            entry == componentPath || entry == className || entry == "$pkg/$className"
        }
    }

    private suspend fun recordAudit(
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String?,
        snapshotId: Long,
    ) {
        audit.record(
            category = AuditCategory.APP,
            action = action,
            target = target,
            commands = commands,
            result = result,
            message = message,
            snapshotId = snapshotId,
        )
    }

    private companion object {
        val BOOT_ACTIONS = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
        val CLASS_NAME = Regex("[A-Za-z0-9_.\\x24]+")

        const val KIND_BOOT_RECEIVERS = "app_boot_receivers"
        const val ACTION_DISABLE_RECEIVER = "boot_receiver_disable"
        const val ACTION_ENABLE_RECEIVER = "boot_receiver_enable"
        const val ACTION_DISABLE_AUTOSTART = "disable_autostart"
        const val ACTION_UNDO_AUTOSTART = "undo_autostart"
        const val READ_TIMEOUT_MS = 15_000L
        const val COMMAND_TIMEOUT_MS = 15_000L
        const val SNAPSHOT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
