package com.xg.toolbox.feature.apps.domain

import com.xg.toolbox.core.common.ErrorCatalog
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.ShellResult

internal object AppShellText {

    val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

    val MODULE_ID = Regex("[A-Za-z0-9._-]+")

    val ANDROID_ID = Regex("^[0-9a-f]{16}$")

    fun isValidPackage(pkg: String): Boolean = pkg.isNotBlank() && PACKAGE_NAME.matches(pkg)

    fun isValidModuleId(id: String): Boolean =
        id.isNotBlank() && MODULE_ID.matches(id) && id != "." && id != ".." && !id.contains("..")

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun detail(result: ShellResult, maxLines: Int = 3): String {
        val text = listOf(result.stdout, result.stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        if (text.isBlank()) {
            return if (result.ok) "" else ErrorCatalog.message(normalizeCode(result.code))
        }
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(maxLines)
            .joinToString(" | ")
            .take(MAX_DETAIL_CHARS)
    }

    fun failure(label: String, result: ShellResult, readbackNote: String? = null): XgResult.Err {
        val code = if (result.ok) ErrorCode.ERR_CMD_FAILED else normalizeCode(result.code)
        val detail = buildString {
            val raw = detail(result)
            if (raw.isNotBlank()) append(raw)
            if (readbackNote != null) {
                if (isNotEmpty()) append(" | ")
                append(readbackNote)
            }
            if (isNotEmpty()) append(" | exit=").append(result.code)
        }
        return XgResult.Err(
            code = code,
            message = "$label：${ErrorCatalog.message(code)}",
            detail = detail.ifBlank { null },
        )
    }

    fun normalizeCode(code: Int): Int = when (code) {
        ErrorCode.ERR_SU_MISSING,
        ErrorCode.ERR_SU_DENIED,
        ErrorCode.ERR_SU_TIMEOUT,
        ErrorCode.ERR_CMD_FAILED,
        ErrorCode.ERR_CMD_TIMEOUT,
        ErrorCode.ERR_READONLY_FS,
        ErrorCode.ERR_POLICY_BLOCKED,
        ErrorCode.ERR_SELINUX,
        ErrorCode.ERR_BACKUP_FAILED,
        ErrorCode.ERR_UNSUPPORTED_ABI,
        ErrorCode.ERR_UNSUPPORTED_DEVICE,
        ErrorCode.ERR_UNSUPPORTED_ANDROID,
        ErrorCode.ERR_PARSE_FAILED,
        ErrorCode.ERR_PERMISSION_GRANT -> code

        else -> ErrorCode.ERR_CMD_FAILED
    }

    fun firstLine(text: String): String =
        text.trim().lineSequence().firstOrNull().orEmpty().take(MAX_DETAIL_CHARS)

    private const val MAX_DETAIL_CHARS = 800
}
