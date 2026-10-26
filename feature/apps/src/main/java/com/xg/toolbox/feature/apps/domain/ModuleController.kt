package com.xg.toolbox.feature.apps.domain

import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.rootError
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
internal class ModuleController @Inject constructor(
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val audit: AuditRepository,
    private val snapshots: SnapshotRepository,
) {

    suspend fun list(): XgResult<List<ModuleInfo>> = withContext(Dispatchers.IO) {
        rootStatus.state.value.rootError()?.let { return@withContext it }
        val listing = shell.exec("ls -1 $MODULES_ROOT 2>/dev/null", root = true, timeoutMs = READ_TIMEOUT_MS)
        if (!listing.ok) {
            return@withContext AppShellText.failure("读取模块目录失败", listing)
        }
        val ids = listing.stdout.lineSequence()
            .map { it.trim() }
            .filter { AppShellText.isValidModuleId(it) }
            .distinct()
            .sorted()
            .toList()
        val modules = mutableListOf<ModuleInfo>()
        for (id in ids) {
            val module = readModule(id).getOrNull()
            if (module == null) {
                XgLog.w(TAG, "模块信息读取失败：$id")
            } else {
                modules += module
            }
        }
        XgResult.Ok(modules)
    }

    suspend fun setEnabled(moduleId: String, enabled: Boolean): XgResult<Unit> =
        withContext(Dispatchers.IO) {
            if (!AppShellText.isValidModuleId(moduleId)) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法模块 ID：$moduleId")
            }
            rootStatus.state.value.rootError()?.let { return@withContext it }
            val dir = moduleDir(moduleId)
            val exists = shell.exec("test -d ${AppShellText.quote(dir)}", root = true, timeoutMs = READ_TIMEOUT_MS)
            if (exists.code != 0) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "模块不存在：$moduleId")
            }
            val disabledBefore = disabledState(dir)
                ?: return@withContext XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "无法读取模块当前启用状态")
            val snapshotId = snapshots.create(
                kind = KIND_MODULE_STATE,
                target = moduleId,
                payload = JSONObject()
                    .put("moduleId", moduleId)
                    .put("enabledBefore", !disabledBefore)
                    .put("path", dir)
                    .toString(),
                ttlMs = SNAPSHOT_TTL_MS,
            )
            val command = if (enabled) {
                "rm -f ${AppShellText.quote("$dir/disable")}"
            } else {
                "touch ${AppShellText.quote("$dir/disable")}"
            }
            val result = shell.exec(command, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
            val disabledAfter = disabledState(dir)
            val success = result.ok && disabledAfter == !enabled
            val action = if (enabled) ACTION_MODULE_ENABLE else ACTION_MODULE_DISABLE
            if (success) {
                recordAudit(action, moduleId, command, AuditResult.OK, "重启后生效", snapshotId)
                XgResult.Ok(Unit)
            } else {
                val note = when {
                    disabledAfter == null -> "回读失败，无法确认模块状态"
                    enabled && disabledAfter == true -> "回读显示 disable 文件仍存在"
                    !enabled && disabledAfter == false -> "回读显示 disable 文件未创建"
                    else -> "回读状态与预期不一致"
                }
                val error = AppShellText.failure(
                    if (enabled) "启用模块失败" else "禁用模块失败",
                    result,
                    note,
                )
                recordAudit(action, moduleId, command, AuditResult.FAILED, error.message, snapshotId)
                error
            }
        }

    suspend fun remove(moduleId: String): XgResult<Unit> = withContext(Dispatchers.IO) {
        if (!AppShellText.isValidModuleId(moduleId)) {
            return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法模块 ID：$moduleId")
        }
        rootStatus.state.value.rootError()?.let { return@withContext it }
        val dir = moduleDir(moduleId)
        val updateDir = "$MODULES_UPDATE_ROOT/$moduleId"
        val exists = shell.exec(
            "test -d ${AppShellText.quote(dir)} || test -d ${AppShellText.quote(updateDir)}",
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        )
        if (exists.code != 0) {
            return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "模块不存在：$moduleId")
        }
        val prop = shell.exec(
            "cat ${AppShellText.quote("$dir/module.prop")} 2>/dev/null",
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        ).stdout.take(MAX_PROP_CHARS)
        val snapshotId = snapshots.create(
            kind = KIND_MODULE_REMOVE,
            target = moduleId,
            payload = JSONObject()
                .put("moduleId", moduleId)
                .put("path", dir)
                .put("updatePath", updateDir)
                .put("moduleProp", prop)
                .toString(),
            ttlMs = SNAPSHOT_TTL_MS,
        )
        val command = "rm -rf ${AppShellText.quote(dir)} ${AppShellText.quote(updateDir)}"
        val result = shell.exec(command, root = true, timeoutMs = REMOVE_TIMEOUT_MS)
        val readback = shell.exec(
            "test -d ${AppShellText.quote(dir)} || test -d ${AppShellText.quote(updateDir)}",
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        )
        val success = result.ok && readback.code == 1
        if (success) {
            recordAudit(ACTION_MODULE_REMOVE, moduleId, command, AuditResult.OK, "模块目录已删除", snapshotId)
            XgResult.Ok(Unit)
        } else {
            val note = if (readback.code == 0) "回读显示模块目录仍存在" else "回读命令失败"
            val error = AppShellText.failure("删除模块失败", result, note)
            recordAudit(ACTION_MODULE_REMOVE, moduleId, command, AuditResult.FAILED, error.message, snapshotId)
            error
        }
    }

    private suspend fun readModule(moduleId: String): XgResult<ModuleInfo> {
        val dir = moduleDir(moduleId)
        val command = "test -d ${AppShellText.quote(dir)} || exit 3" +
            "; cat ${AppShellText.quote("$dir/module.prop")} 2>/dev/null" +
            "; echo" +
            "; echo '@@XG_DISABLE@@'" +
            "; test -f ${AppShellText.quote("$dir/disable")} && echo 1 || echo 0" +
            "; echo '@@XG_REMOVE@@'" +
            "; test -f ${AppShellText.quote("$dir/remove")} && echo 1 || echo 0"
        val result = shell.exec(command, root = true, timeoutMs = READ_TIMEOUT_MS)
        if (result.code == 3) return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "模块目录不存在：$moduleId")
        if (!result.ok) return AppShellText.failure("读取模块信息失败", result)
        val text = result.stdout
        if (!text.contains(DISABLE_MARKER) || !text.contains(REMOVE_MARKER)) {
            return XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "模块信息格式异常：$moduleId", AppShellText.detail(result))
        }
        val propText = text.substringBefore(DISABLE_MARKER).trimEnd()
        val afterDisable = text.substringAfter(DISABLE_MARKER)
        val disabled = afterDisable.substringBefore(REMOVE_MARKER).trim() == "1"
        val pendingRemove = afterDisable.substringAfter(REMOVE_MARKER).trim() == "1"
        val props = parseProp(propText)
        return XgResult.Ok(
            ModuleInfo(
                id = moduleId,
                name = props["name"].orEmpty().ifBlank { moduleId },
                version = props["version"].orEmpty(),
                author = props["author"].orEmpty(),
                description = props["description"].orEmpty(),
                enabled = !disabled,
                pendingRemove = pendingRemove,
            ),
        )
    }

    private suspend fun disabledState(dir: String): Boolean? {
        val result = shell.exec(
            "test -f ${AppShellText.quote("$dir/disable")}",
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        )
        return when (result.code) {
            0 -> true
            1 -> false
            else -> null
        }
    }

    private fun parseProp(text: String): Map<String, String> {
        val props = mutableMapOf<String, String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val index = trimmed.indexOf('=')
            if (index <= 0) return@forEach
            props[trimmed.substring(0, index).trim()] = trimmed.substring(index + 1).trim()
        }
        return props
    }

    private fun moduleDir(moduleId: String): String = "$MODULES_ROOT/$moduleId"

    private suspend fun recordAudit(
        action: String,
        target: String,
        command: String,
        result: String,
        message: String?,
        snapshotId: Long,
    ) {
        audit.record(
            category = AuditCategory.APP,
            action = action,
            target = target,
            commands = command,
            result = result,
            message = message,
            snapshotId = snapshotId,
        )
    }

    private companion object {
        const val MODULES_ROOT = "/data/adb/modules"
        const val TAG = "ModuleController"
        const val MODULES_UPDATE_ROOT = "/data/adb/modules_update"
        const val DISABLE_MARKER = "@@XG_DISABLE@@"
        const val REMOVE_MARKER = "@@XG_REMOVE@@"
        const val KIND_MODULE_STATE = "module_state"
        const val KIND_MODULE_REMOVE = "module_remove"
        const val ACTION_MODULE_ENABLE = "module_enable"
        const val ACTION_MODULE_DISABLE = "module_disable"
        const val ACTION_MODULE_REMOVE = "module_remove"
        const val READ_TIMEOUT_MS = 10_000L
        const val COMMAND_TIMEOUT_MS = 15_000L
        const val REMOVE_TIMEOUT_MS = 30_000L
        const val MAX_PROP_CHARS = 4096
        const val SNAPSHOT_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
