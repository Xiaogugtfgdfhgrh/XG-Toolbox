package com.xg.toolbox.feature.security.domain

import android.content.Context
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class AppOpsController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val audit: AuditRepository,
    private val snapshots: SnapshotRepository,
    private val rootStatus: RootStatusRepository,
) {

    private val definitions: List<AppOpDefinition> = listOf(
        AppOpDefinition(OP_CAMERA, "相机"),
        AppOpDefinition(OP_RECORD_AUDIO, "麦克风"),
        AppOpDefinition(OP_FINE_LOCATION, "精确位置"),
        AppOpDefinition(OP_COARSE_LOCATION, "粗略位置"),
        AppOpDefinition(OP_READ_CONTACTS, "通讯录"),
        AppOpDefinition(OP_READ_CLIPBOARD, "剪贴板"),
        AppOpDefinition(OP_RUN_IN_BACKGROUND, "后台运行"),
    )

    private val definitionByOp: Map<String, AppOpDefinition> = definitions.associateBy { it.op }
    private val cacheMutex = Mutex()
    private val rowCache = object : LinkedHashMap<String, AppOpRow>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AppOpRow>?): Boolean =
            size > MAX_CACHED_PACKAGES
    }

    
    fun definitions(): List<AppOpDefinition> = definitions

    
    suspend fun load(packageNames: List<String>, forceRefresh: Boolean = false): List<AppOpRow> =
        withContext(Dispatchers.IO) {
            cacheMutex.withLock {
                packageNames.distinct().map { packageName ->
                    val cached = rowCache[packageName]
                    if (!forceRefresh && cached != null) {
                        cached
                    } else {
                        val fresh = loadRow(packageName)
                        if (fresh.error == null) rowCache[packageName] = fresh
                        if (fresh.error == null) fresh else cached ?: fresh
                    }
                }
            }
        }

    
    suspend fun set(pkg: String, op: String, state: AppOpState): XgResult<Unit> =
        withContext(Dispatchers.IO) {
            val definition = definitionByOp[op]
            if (definition == null) {
                return@withContext failedBeforeWrite(
                    pkg = pkg,
                    op = op,
                    commands = "",
                    code = ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    message = "不支持的权限项：$op",
                )
            }
            if (!isValidPackageName(pkg)) {
                return@withContext failedBeforeWrite(
                    pkg = pkg,
                    op = op,
                    commands = "",
                    code = ErrorCode.ERR_CMD_FAILED,
                    message = "非法包名：$pkg",
                )
            }
            rootStatus.state.value.rootError()?.let { error ->
                recordAudit(
                    action = ACTION_SET_APPOP,
                    target = "$pkg/$op",
                    commands = "",
                    result = AuditResult.FAILED,
                    message = error.message,
                )
                return@withContext error
            }

            val readCommand = "appops get ${shellQuote(pkg)} $op"
            val preResult = try {
                shell.exec(readCommand, root = true, timeoutMs = READ_TIMEOUT_MS)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.w(TAG, "读取 appops 原状态异常: $pkg/$op: ${t.javaClass.simpleName}")
                return@withContext failedBeforeWrite(
                    pkg = pkg,
                    op = op,
                    commands = readCommand,
                    code = ErrorCode.ERR_CMD_FAILED,
                    message = "读取 appops 原状态失败",
                    detail = t.message,
                )
            }
            if (!preResult.ok) {
                return@withContext failedBeforeWrite(
                    pkg = pkg,
                    op = op,
                    commands = readCommand,
                    code = shellFailureCode(preResult),
                    message = "读取 appops 原状态失败：${shellFailureMessage(preResult)}",
                    detail = shellDetail(preResult),
                )
            }
            val preError = preResult.stdout.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Error", ignoreCase = true) }
            if (preError != null) {
                return@withContext failedBeforeWrite(
                    pkg = pkg,
                    op = op,
                    commands = readCommand,
                    code = ErrorCode.ERR_CMD_FAILED,
                    message = "读取 appops 原状态失败：$preError",
                    detail = preResult.stdout.take(500),
                )
            }

            val original = parseSnapshot(preResult.stdout, op)
            val channel = resolveChannel(op, original)
                ?: return@withContext failedBeforeWrite(
                    pkg = pkg,
                    op = op,
                    commands = readCommand,
                    code = ErrorCode.ERR_PARSE_FAILED,
                    message = "无法确认 appops 模式（未出现 UID/包模式），未执行写入",
                    detail = original.raw.take(500),
                )

            val snapshotId = try {
                snapshots.create(
                    kind = SECURITY_SNAPSHOT_KIND,
                    target = "$pkg/$op",
                    payload = snapshotPayload(pkg, op, channel, original),
                    ttlMs = SECURITY_SNAPSHOT_TTL_MS,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.e(TAG, "创建 appops 快照失败: $pkg/$op", t)
                return@withContext failedBeforeWrite(
                    pkg = pkg,
                    op = op,
                    commands = readCommand,
                    code = ErrorCode.ERR_BACKUP_FAILED,
                    message = "创建快照失败，未执行写入",
                    detail = t.message,
                )
            }

            val mode = state.toAppOpsMode()
            val writeCommand = if (channel == WriteChannel.UID) {
                "appops set --uid ${shellQuote(pkg)} $op $mode"
            } else {
                "appops set ${shellQuote(pkg)} $op $mode"
            }
            val writeResult = try {
                shell.exec(writeCommand, root = true, timeoutMs = WRITE_TIMEOUT_MS)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.w(TAG, "写入 appops 异常: $pkg/$op: ${t.javaClass.simpleName}")
                null
            }
            if (writeResult != null) {
                cacheMutex.withLock { rowCache.remove(pkg) }
            }
            val postAttempted = writeResult?.ok == true
            val postResult = if (postAttempted) {
                try {
                    shell.exec(readCommand, root = true, timeoutMs = READ_TIMEOUT_MS)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    XgLog.w(TAG, "回读 appops 异常: $pkg/$op: ${t.javaClass.simpleName}")
                    null
                }
            } else {
                null
            }
            val readback = postResult?.let { parseSnapshot(it.stdout, op) }
            val readbackMode = readback?.let {
                if (channel == WriteChannel.UID) it.uidMode else it.packageMode
            }
            val readbackState = modeToState(readbackMode)
            val success = writeResult?.ok == true &&
                postResult?.ok == true &&
                readbackMode != null &&
                readbackState == state
            val commands = "$readCommand; $writeCommand" +
                (if (postAttempted) "; $readCommand" else "")

            if (success) {
                recordAudit(
                    action = ACTION_SET_APPOP,
                    target = "$pkg/$op",
                    commands = commands,
                    result = AuditResult.OK,
                    message = "已设置${definition.label}为${state.label()}",
                    snapshotId = snapshotId,
                )
                return@withContext XgResult.Ok(Unit)
            }

            val error = when {
                writeResult == null -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "设置权限命令未能执行",
                    "shell 调用异常",
                )

                !writeResult.ok -> XgResult.Err(
                    shellFailureCode(writeResult),
                    "设置权限失败：${shellFailureMessage(writeResult)}",
                    shellDetail(writeResult),
                )

                postResult == null -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "权限回读失败",
                    "写入后未能执行 appops get",
                )

                !postResult.ok -> XgResult.Err(
                    shellFailureCode(postResult),
                    "权限回读失败：${shellFailureMessage(postResult)}",
                    shellDetail(postResult),
                )

                readbackMode == null && channel == WriteChannel.UID -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "无法回读 UID 模式，写入未确认（不回退包模式）",
                    readback?.raw?.take(500).orEmpty()
                        .ifBlank { "appops get 未返回 Uid mode 行" },
                )

                readbackMode == null -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "无法回读包模式，写入未确认",
                    readback?.raw?.take(500).orEmpty()
                        .ifBlank { "appops get 未返回显式包模式行" },
                )

                else -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "回读状态与预期不符",
                    "期望 ${state.label()}，实际 ${readbackState?.label() ?: readbackMode}；" +
                        postResult.stdout.trim().take(300),
                )
            }
            recordAudit(
                action = ACTION_SET_APPOP,
                target = "$pkg/$op",
                commands = commands,
                result = AuditResult.FAILED,
                message = "${error.message}：${error.detail.orEmpty()}".take(500),
                snapshotId = snapshotId,
            )
            error
        }

    private suspend fun loadRow(packageName: String): AppOpRow {
        if (!isValidPackageName(packageName)) {
            return AppOpRow(
                packageName = packageName,
                label = packageName,
                cells = emptyCells(),
                error = "非法包名：$packageName",
            )
        }

        val label = labelOf(packageName)
        val command = buildString {
            for (definition in definitions) {
                append("echo '@@OP:")
                append(definition.op)
                append("'; appops get ")
                append(shellQuote(packageName))
                append(' ')
                append(definition.op)
                append("; ")
            }
        }
        val result = try {
            shell.exec(command, root = true, timeoutMs = LOAD_TIMEOUT_MS)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.w(TAG, "appops 读取异常: $packageName: ${t.javaClass.simpleName}")
            return AppOpRow(packageName, label, emptyCells(), "读取失败：${t.message.orEmpty()}")
        }

        val blocks = parseBlocks(result.stdout)
        val cells = linkedMapOf<String, AppOpState?>()
        val errors = linkedSetOf<String>()
        for (definition in definitions) {
            val raw = blocks[definition.op].orEmpty()
            cells[definition.op] = parseSnapshot(raw, definition.op).effective
            raw.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("Error", ignoreCase = true) }
                ?.let { errors += it }
        }

        val error = when {
            errors.isNotEmpty() -> errors.joinToString("；").take(300)
            !result.ok -> shellFailureMessage(result)
            else -> null
        }
        return AppOpRow(packageName, label, cells, error)
    }

    private fun emptyCells(): Map<String, AppOpState?> =
        linkedMapOf<String, AppOpState?>().apply {
            definitions.forEach { put(it.op, null) }
        }

    
    private fun parseBlocks(output: String): Map<String, String> {
        val blocks = linkedMapOf<String, StringBuilder>()
        var current: String? = null
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(BLOCK_PREFIX)) {
                val name = trimmed.removePrefix(BLOCK_PREFIX)
                current = name
                blocks.getOrPut(name) { StringBuilder() }
                continue
            }
            current?.let { name -> blocks[name]?.appendLine(line) }
        }
        return blocks.mapValues { (_, value) -> value.toString() }
    }

    
    private data class AppOpsSnapshot(
        val uidMode: String?,
        val packageMode: String?,
        val defaultMode: String?,
        val raw: String,
        val effective: AppOpState?,
    )

    private enum class WriteChannel { UID, PACKAGE }

    private fun parseSnapshot(raw: String, op: String): AppOpsSnapshot {
        var uidMode: String? = null
        var packageMode: String? = null
        var defaultMode: String? = null
        val packageRegex = Regex("^${Regex.escape(op)}:\\s*([A-Za-z]+)")
        val uidRegex = Regex(
            "^Uid mode:\\s*${Regex.escape(op)}:\\s*([A-Za-z]+)",
            RegexOption.IGNORE_CASE,
        )
        val defaultRegex = Regex("^Default mode:\\s*([A-Za-z]+)", RegexOption.IGNORE_CASE)

        for (line in raw.lineSequence()) {
            val text = line.trim()
            if (text.startsWith("Uid mode:", ignoreCase = true)) {
                if (uidMode == null) {
                    uidMode = uidRegex.find(text)?.groupValues?.get(1)?.lowercase()
                }
                continue
            }
            if (text.startsWith("Default mode:", ignoreCase = true)) {
                if (defaultMode == null) {
                    defaultMode = defaultRegex.find(text)?.groupValues?.get(1)?.lowercase()
                }
                continue
            }
            if (packageMode == null) {
                packageMode = packageRegex.find(text)?.groupValues?.get(1)?.lowercase()
            }
        }
        return AppOpsSnapshot(
            uidMode = uidMode,
            packageMode = packageMode,
            defaultMode = defaultMode,
            raw = raw,
            effective = modeToState(uidMode)
                ?: modeToState(packageMode)
                ?: modeToState(defaultMode),
        )
    }

    private fun resolveChannel(op: String, snapshot: AppOpsSnapshot): WriteChannel? = when {
        snapshot.uidMode != null -> WriteChannel.UID
        op in UID_MODE_OPS -> when {
            snapshot.packageMode != null || snapshot.defaultMode != null -> WriteChannel.UID
            else -> null
        }
        snapshot.packageMode != null || snapshot.defaultMode != null -> WriteChannel.PACKAGE
        else -> null
    }

    private fun modeToState(mode: String?): AppOpState? = when (mode?.lowercase()) {
        "allow" -> AppOpState.ALLOW
        "ignore" -> AppOpState.ASK
        "deny" -> AppOpState.DENY
        "foreground" -> AppOpState.ALLOW
        else -> null
    }

    private fun AppOpState.toAppOpsMode(): String = when (this) {
        AppOpState.ALLOW -> "allow"
        AppOpState.ASK -> "ignore"
        AppOpState.DENY -> "deny"
    }

    private fun AppOpState.label(): String = when (this) {
        AppOpState.ALLOW -> "允许"
        AppOpState.ASK -> "询问"
        AppOpState.DENY -> "拒绝"
    }

    private fun labelOf(packageName: String): String = runCatching {
        val info = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrDefault(packageName)

    private fun snapshotPayload(
        pkg: String,
        op: String,
        channel: WriteChannel,
        original: AppOpsSnapshot,
    ): String = buildString {
        append('{')
        append("\"package\":").append(jsonString(pkg)).append(',')
        append("\"op\":").append(jsonString(op)).append(',')
        append("\"channel\":")
            .append(jsonString(if (channel == WriteChannel.UID) "uid" else "package"))
            .append(',')
        append("\"uidMode\":").append(jsonNullableString(original.uidMode)).append(',')
        append("\"packageMode\":").append(jsonNullableString(original.packageMode)).append(',')
        append("\"defaultMode\":").append(jsonNullableString(original.defaultMode)).append(',')
        append("\"effective\":")
            .append(jsonNullableString(original.effective?.name?.lowercase()))
            .append(',')
        append("\"raw\":").append(jsonString(original.raw.take(2000)))
        append('}')
    }

    private suspend fun failedBeforeWrite(
        pkg: String,
        op: String,
        commands: String,
        code: Int,
        message: String,
        detail: String? = null,
    ): XgResult.Err {
        recordAudit(
            action = ACTION_SET_APPOP,
            target = "$pkg/$op",
            commands = commands,
            result = AuditResult.FAILED,
            message = "$message${detail?.let { "：$it" }.orEmpty()}".take(500),
        )
        return XgResult.Err(code, message, detail)
    }

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

    private companion object {
        const val TAG = "AppOpsController"
        const val MAX_CACHED_PACKAGES = 512
        const val BLOCK_PREFIX = "@@OP:"
        const val ACTION_SET_APPOP = "appops_set"

        const val OP_CAMERA = "CAMERA"
        const val OP_RECORD_AUDIO = "RECORD_AUDIO"
        const val OP_FINE_LOCATION = "FINE_LOCATION"
        const val OP_COARSE_LOCATION = "COARSE_LOCATION"
        const val OP_READ_CONTACTS = "READ_CONTACTS"
        const val OP_READ_CLIPBOARD = "READ_CLIPBOARD"
        const val OP_RUN_IN_BACKGROUND = "RUN_IN_BACKGROUND"

        
        val UID_MODE_OPS = setOf(OP_CAMERA, OP_RECORD_AUDIO)

        const val LOAD_TIMEOUT_MS = 20_000L
        const val WRITE_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 10_000L
    }
}
