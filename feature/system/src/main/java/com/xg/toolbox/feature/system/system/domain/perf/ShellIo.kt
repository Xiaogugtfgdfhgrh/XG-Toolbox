package com.xg.toolbox.feature.system.domain.perf

import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.ShellResult
import com.xg.toolbox.core.shell.rootError
import java.util.Locale

internal object ShellIo {

    
    private val ALLOWED_PATH_PREFIXES = listOf(
        "/sys/",
        "/proc/",
        "/dev/block/by-name/",
        "/data/local/tmp/xg/",
    )

    
    suspend fun readFile(shell: Shell, path: String, timeoutMs: Long = 8_000): String? {
        if (!isAllowedPath(path)) return null
        val result = shell.exec("cat ${quote(path)} 2>/dev/null", root = true, timeoutMs = timeoutMs)
        if (!result.ok) return null
        return result.stdout.trim().takeIf { it.isNotEmpty() }
    }

    
    suspend fun writeFile(
        shell: Shell,
        path: String,
        value: String,
        timeoutMs: Long = 8_000,
    ): ShellResult {
        if (!isAllowedPath(path)) {
            return ShellResult(
                code = ErrorCode.ERR_POLICY_BLOCKED,
                stdout = "",
                stderr = "路径不在允许范围：$path",
                durationMs = 0L,
            )
        }
        val command = "echo ${quote(value)} > ${quote(path)}"
        return shell.exec(command, root = true, timeoutMs = timeoutMs)
    }

    
    suspend fun settingsGet(shell: Shell, namespace: String, key: String): String? {
        if (namespace !in SETTINGS_NAMESPACES || !SETTINGS_KEY.matches(key)) return null
        val result = shell.exec(
            "settings get $namespace $key 2>/dev/null",
            root = true,
            timeoutMs = 8_000,
        )
        if (!result.ok) return null
        return result.stdout.trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    
    suspend fun settingsPut(
        shell: Shell,
        namespace: String,
        key: String,
        value: String,
        timeoutMs: Long = 8_000,
    ): ShellResult {
        if (namespace !in SETTINGS_NAMESPACES || !SETTINGS_KEY.matches(key)) {
            return ShellResult(
                code = ErrorCode.ERR_POLICY_BLOCKED,
                stdout = "",
                stderr = "settings 键不合法：$namespace $key",
                durationMs = 0L,
            )
        }
        return shell.exec(
            "settings put $namespace $key ${quote(value)}",
            root = true,
            timeoutMs = timeoutMs,
        )
    }

    
    fun requireRoot(rootState: RootState): XgResult.Err? = rootState.rootError()

    
    fun err(code: Int, message: String, detail: String? = null): XgResult.Err =
        XgResult.Err(code = code, message = message, detail = detail)

    
    fun errFromShell(result: ShellResult, message: String, code: Int = ErrorCode.ERR_CMD_FAILED): XgResult.Err {
        val raw = result.stdout.ifBlank { result.stderr }.trim()
        val first = raw.lineSequence().firstOrNull()?.take(200)
        return err(code, message, first?.takeIf { it.isNotEmpty() } ?: "退出码 ${result.code}")
    }

    
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    
    fun isAllowedPath(path: String): Boolean =
        path.startsWith("/") && ALLOWED_PATH_PREFIXES.any { path.startsWith(it) } && !path.contains('\n')

    
    fun isSafePartitionName(name: String): Boolean = PARTITION_NAME.matches(name)

    
    fun formatKhz(khz: Long): String = when {
        khz >= 1_000_000 -> String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
        khz >= 1_000 -> String.format(Locale.US, "%.0f MHz", khz / 1_000.0)
        else -> "$khz kHz"
    }

    private val SETTINGS_NAMESPACES = setOf("system", "secure", "global")

    private val SETTINGS_KEY = Regex("[A-Za-z0-9_.\\-]{1,96}")

    private val PARTITION_NAME = Regex("[a-z0-9_]{1,64}")
}
