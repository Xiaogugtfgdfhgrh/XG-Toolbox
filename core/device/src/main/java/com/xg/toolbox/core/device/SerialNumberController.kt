package com.xg.toolbox.core.device

import android.content.Context
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.ShellResult
import com.xg.toolbox.core.shell.rootError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class SerialEditCapability(
    val supported: Boolean,
    val resetpropPath: String? = null,
    val reason: String? = null,
)

data class SerialChangeResult(
    val before: String,
    val after: String,
    val requiresReboot: Boolean = true,
)

@Singleton
class SerialNumberController @Inject constructor(
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    @ApplicationContext context: Context,
) {

    private val journal: SerialChangeJournal = HiltSerialChangeJournal(context)
    private val mutex = Mutex()

    
    suspend fun readRaw(): XgResult<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            rootStatus.state.value.rootError()?.let { return@withLock it }
            readSerial()
        }
    }

    
    suspend fun editCapability(): XgResult<SerialEditCapability> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = rootStatus.state.value
            if (state !is RootState.Granted) {
                val reason = state.rootError()?.message ?: "需要 Root 授权"
                return@withLock XgResult.Ok(SerialEditCapability(supported = false, reason = reason))
            }
            when (val detected = detectResetprop()) {
                is XgResult.Ok -> XgResult.Ok(
                    SerialEditCapability(supported = true, resetpropPath = detected.data),
                )

                is XgResult.Err -> XgResult.Ok(
                    SerialEditCapability(supported = false, reason = detected.message),
                )
            }
        }
    }

    
    suspend fun setSerial(newSerial: String): XgResult<SerialChangeResult> = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!SERIAL_PATTERN.matches(newSerial)) {
                return@withLock failBeforeWrite(
                    code = ErrorCode.ERR_CMD_FAILED,
                    message = "序列号格式非法：仅允许 1–32 位 A-Z a-z 0-9 . _ -（禁止空白、换行与引号）",
                )
            }

            rootStatus.state.value.rootError()?.let { error ->
                auditFailure(error.message, "Root 门禁未通过")
                return@withLock error
            }

            val resetprop = when (val detected = detectResetprop()) {
                is XgResult.Ok -> detected.data
                is XgResult.Err -> {
                    auditFailure(detected.message, "resetprop 能力检测失败")
                    return@withLock detected
                }
            }

            val before = when (val read = readSerial()) {
                is XgResult.Ok -> read.data
                is XgResult.Err -> {
                    auditFailure(read.message, "读取旧序列号失败")
                    return@withLock read
                }
            }

            val payload = JSONObject()
                .put("before", before)
                .put("after", newSerial)
                .toString()
            val snapshotId = when (val snapshot = journal.snapshot(
                kind = SNAPSHOT_KIND,
                target = PROPERTY,
                payload = payload,
                ttlMs = SNAPSHOT_TTL_MS,
            )) {
                is XgResult.Ok -> snapshot.data
                is XgResult.Err -> {
                    auditFailure(snapshot.message, "创建序列号快照失败，已中止修改")
                    return@withLock XgResult.Err(
                        snapshot.code,
                        "创建序列号快照失败，已中止修改：${snapshot.message}",
                        snapshot.detail,
                    )
                }
            }

            val outcome = writeAndVerify(resetprop, newSerial)
            if (outcome.success) {
                val message = buildString {
                    append(maskForAudit(before)).append(" → ").append(maskForAudit(newSerial))
                    append("；ro.serialno 已回读一致")
                    append("；Build.getSerial 缓存需重启后复核（resetprop 运行时覆盖可能不跨重启保留）")
                }
                audit(
                    action = ACTION,
                    commands = outcome.commands,
                    result = RESULT_OK,
                    message = message,
                    snapshotId = snapshotId,
                )
                return@withLock XgResult.Ok(
                    SerialChangeResult(before = before, after = newSerial, requiresReboot = true),
                )
            }

            val restored = restoreSerial(resetprop, before)
            val message = buildString {
                append("修改序列号失败：").append(outcome.message)
                if (restored) {
                    append("；已恢复原值并回读一致")
                } else {
                    append("；恢复原值失败，请立即检查 ro.serialno")
                }
                append("（").append(maskForAudit(before)).append(" → ").append(maskForAudit(newSerial)).append("）")
            }
            audit(
                action = ACTION,
                commands = outcome.commands,
                result = if (restored) RESULT_ROLLED_BACK else RESULT_FAILED,
                message = message,
                snapshotId = snapshotId,
            )
            XgResult.Err(outcome.code, message, outcome.detail)
        }
    }

    
    private suspend fun readSerial(): XgResult<String> {
        var last: ShellResult? = null
        for (property in SERIAL_PROPERTIES) {
            val result = shell.exec("getprop $property", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
            last = result
            if (!result.ok) continue
            val value = result.stdout.trim()
            if (value.isNotEmpty() && !value.equals("unknown", ignoreCase = true)) {
                return XgResult.Ok(value)
            }
        }
        val failure = last
        return if (failure != null && !failure.ok) {
            XgResult.Err(
                errorCode(failure),
                "读取序列号失败：${detail(failure)}",
                detail(failure),
            )
        } else {
            XgResult.Err(
                ErrorCode.ERR_PARSE_FAILED,
                "未读到有效序列号（已尝试 ${SERIAL_PROPERTIES.joinToString("/")}）",
            )
        }
    }

    
    private suspend fun detectResetprop(): XgResult<String> {
        val result = shell.exec(CAPABILITY_COMMAND, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        if (!result.ok) {
            return XgResult.Err(
                errorCode(result),
                "检测 resetprop 失败：${detail(result)}",
                detail(result),
            )
        }
        val path = result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(RESETPROP_MARKER) }
            ?.removePrefix(RESETPROP_MARKER)
            ?.trim()
            .orEmpty()
        if (path.isEmpty()) {
            return XgResult.Err(
                ErrorCode.ERR_UNSUPPORTED_DEVICE,
                "当前 ROM 未提供 resetprop；ro.serialno 为只读属性，setprop 无法修改",
            )
        }
        if (!isSafeResetpropPath(path)) {
            return XgResult.Err(
                ErrorCode.ERR_UNSUPPORTED_DEVICE,
                "resetprop 路径异常，已拒绝执行：$path",
            )
        }
        return XgResult.Ok(path)
    }

    
    private suspend fun writeAndVerify(path: String, value: String): WriteOutcome {
        val commands = mutableListOf<String>()
        var last: ShellResult? = null

        var result = shell.exec(
            writeCommand(path, value, noCollect = true),
            root = true,
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        commands += auditCommand(path, noCollect = true)
        last = result
        var readback = readSerial()
        if (readback.getOrNull() == value) {
            return WriteOutcome(success = true, commands = commands.joinToString("\n"))
        }

        if (!result.ok || isReadOnly(result)) {
            result = shell.exec(
                writeCommand(path, value, noCollect = false),
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
            commands += auditCommand(path, noCollect = false)
            last = result
            readback = readSerial()
            if (readback.getOrNull() == value) {
                return WriteOutcome(success = true, commands = commands.joinToString("\n"))
            }
        }

        if (commands.size == 1) {
            result = shell.exec(
                writeCommand(path, value, noCollect = false),
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
            commands += auditCommand(path, noCollect = false)
            last = result
            readback = readSerial()
            if (readback.getOrNull() == value) {
                return WriteOutcome(success = true, commands = commands.joinToString("\n"))
            }
        }

        val message = when (readback) {
            is XgResult.Err -> "写入后回读 ro.serialno 失败：${readback.message}"
            else -> "resetprop 写入后回读不一致，目标值未生效"
        }
        return WriteOutcome(
            success = false,
            code = last?.let(::errorCode) ?: ErrorCode.ERR_CMD_FAILED,
            message = message,
            detail = last?.let(::detail),
            commands = commands.joinToString("\n"),
        )
    }

    
    private suspend fun restoreSerial(path: String, before: String): Boolean {
        for (noCollect in listOf(true, false)) {
            shell.exec(
                writeCommand(path, before, noCollect),
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
            if (readSerial().getOrNull() == before) return true
        }
        return false
    }

    private fun writeCommand(path: String, value: String, noCollect: Boolean): String {
        val args = if (noCollect) "-n $PROPERTY" else PROPERTY
        return "${shellQuote(path)} $args ${shellQuote(value)}"
    }

    private fun auditCommand(path: String, noCollect: Boolean): String {
        val args = if (noCollect) "-n $PROPERTY" else PROPERTY
        return "${shellQuote(path)} $args <redacted>"
    }

    
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun isSafeResetpropPath(path: String): Boolean =
        path.length <= MAX_PATH_CHARS && SAFE_PATH_PATTERN.matches(path)

    private fun isReadOnly(result: ShellResult): Boolean {
        val text = (result.stdout + "\n" + result.stderr).lowercase(Locale.US)
        return text.contains("read-only file system") ||
            text.contains("read-only property") ||
            text.contains("readonly") ||
            text.contains("read only") ||
            text.contains("ro property")
    }

    private fun hasPermissionDenied(result: ShellResult): Boolean {
        val text = (result.stdout + "\n" + result.stderr).lowercase(Locale.US)
        return text.contains("permission denied") ||
            text.contains("operation not permitted") ||
            text.contains("avc: denied")
    }

    private fun errorCode(result: ShellResult): Int = when {
        result.code in SHELL_ERROR_CODES -> result.code
        isReadOnly(result) -> ErrorCode.ERR_READONLY_FS
        hasPermissionDenied(result) -> ErrorCode.ERR_SELINUX
        else -> ErrorCode.ERR_CMD_FAILED
    }

    private fun detail(result: ShellResult): String {
        val text = result.stderr.ifBlank { result.stdout }.trim()
        val line = text.lineSequence().firstOrNull { it.isNotBlank() }
        return line?.take(MAX_DETAIL_CHARS) ?: "退出码 ${result.code}"
    }

    private suspend fun failBeforeWrite(code: Int, message: String): XgResult.Err {
        auditFailure(message, "写入前校验失败")
        return XgResult.Err(code, message)
    }

    private suspend fun auditFailure(message: String, detail: String) {
        audit(
            action = ACTION,
            commands = "",
            result = RESULT_FAILED,
            message = "$detail：$message",
            snapshotId = null,
        )
    }

    private suspend fun audit(
        action: String,
        commands: String,
        result: String,
        message: String?,
        snapshotId: Long?,
    ) {
        val outcome = journal.audit(
            category = AUDIT_CATEGORY,
            action = action,
            target = PROPERTY,
            commands = commands.take(MAX_AUDIT_COMMANDS),
            result = result,
            message = message,
            snapshotId = snapshotId,
        )
        if (outcome is XgResult.Err) {
            XgLog.w(TAG, "写序列号审计失败：${outcome.message}")
        }
    }

    
    private fun maskForAudit(value: String): String {
        val text = value.trim()
        return when {
            text.isEmpty() -> "—"
            text.length <= 4 -> text.take(1) + "…"
            text.length <= 8 -> text.take(4) + "…"
            else -> text.take(4) + "…" + text.takeLast(4)
        }
    }

    private data class WriteOutcome(
        val success: Boolean,
        val code: Int = 0,
        val message: String = "",
        val detail: String? = null,
        val commands: String = "",
    )

    private companion object {
        const val PROPERTY = "ro.serialno"
        const val SNAPSHOT_KIND = "device_serial"
        const val AUDIT_CATEGORY = "system"
        const val ACTION = "set_device_serial"
        const val RESULT_OK = "ok"
        const val RESULT_FAILED = "failed"
        const val RESULT_ROLLED_BACK = "rolled_back"
        const val TAG = "SerialNumberController"

        const val COMMAND_TIMEOUT_MS = 5_000L
        const val SNAPSHOT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_AUDIT_COMMANDS = 2_048
        const val MAX_DETAIL_CHARS = 200
        const val MAX_PATH_CHARS = 256

        
        const val RESETPROP_MARKER = "XG_RESETPROP="
        val SERIAL_PROPERTIES = listOf("ro.serialno", "ro.boot.serialno", "persist.sys.serialno")
        const val CAPABILITY_COMMAND =
            "p=\$(command -v resetprop 2>/dev/null); " +
                "if [ -n \"\$p\" ] && [ -x \"\$p\" ]; then echo \"XG_RESETPROP=\$p\"; " +
                "elif [ -x /data/adb/ksu/bin/resetprop ]; then echo \"XG_RESETPROP=/data/adb/ksu/bin/resetprop\"; " +
                "elif [ -x /data/adb/magisk/resetprop ]; then echo \"XG_RESETPROP=/data/adb/magisk/resetprop\"; " +
                "elif [ -x /data/adb/ap/bin/resetprop ]; then echo \"XG_RESETPROP=/data/adb/ap/bin/resetprop\"; " +
                "else echo \"XG_RESETPROP=\"; fi"

        val SERIAL_PATTERN = Regex("^[A-Za-z0-9._-]{1,32}$")
        val SAFE_PATH_PATTERN = Regex("^/[A-Za-z0-9._/-]+$")
        val SHELL_ERROR_CODES: Set<Int> = (ErrorCode.ERR_SU_MISSING..ErrorCode.ERR_SELINUX).toSet()
    }
}
