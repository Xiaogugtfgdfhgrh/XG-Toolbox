package com.xg.toolbox.feature.security.domain

import com.xg.toolbox.core.common.ErrorCatalog
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.shell.ShellResult

internal val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")
internal val COMPONENT_CLASS_PATTERN = Regex("[A-Za-z0-9_.$]+")

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

internal fun isValidPackageName(packageName: String): Boolean =
    packageName.isNotBlank() && PACKAGE_NAME_PATTERN.matches(packageName)

internal fun normalizeComponent(packageName: String, component: String): String? {
    if (!isValidPackageName(packageName)) return null
    val trimmed = component.trim()
    if (trimmed.isEmpty()) return null

    val slash = trimmed.indexOf('/')
    val classRaw = if (slash >= 0) {
        val owner = trimmed.substring(0, slash)
        if (owner != packageName) return null
        trimmed.substring(slash + 1)
    } else {
        trimmed
    }
    if (classRaw.isBlank()) return null

    val fullClass = when {
        classRaw.startsWith(".") -> packageName + classRaw
        classRaw.contains('.') -> classRaw
        else -> "$packageName.$classRaw"
    }
    if (!COMPONENT_CLASS_PATTERN.matches(fullClass)) return null
    return "$packageName/$fullClass"
}

internal fun componentClass(component: String): String? {
    val norm = component.substringAfter('/', "").trim()
    return norm.takeIf { it.isNotEmpty() && COMPONENT_CLASS_PATTERN.matches(it) }
}

internal fun normalizeServiceId(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty() || !trimmed.contains('/')) return null
    val packageName = trimmed.substringBefore('/')
    val classRaw = trimmed.substringAfter('/')
    if (!isValidPackageName(packageName) || classRaw.isBlank()) return null
    val fullClass = when {
        classRaw.startsWith(".") -> packageName + classRaw
        classRaw.contains('.') -> classRaw
        else -> "$packageName.$classRaw"
    }
    if (!COMPONENT_CLASS_PATTERN.matches(fullClass)) return null
    return "$packageName/$fullClass"
}

internal fun shellFailureCode(result: ShellResult): Int = when (result.code) {
    ErrorCode.ERR_SU_MISSING,
    ErrorCode.ERR_SU_DENIED,
    ErrorCode.ERR_SU_TIMEOUT,
    ErrorCode.ERR_CMD_FAILED,
    ErrorCode.ERR_CMD_TIMEOUT,
    ErrorCode.ERR_READONLY_FS,
    ErrorCode.ERR_POLICY_BLOCKED,
    ErrorCode.ERR_SELINUX -> result.code

    else -> ErrorCode.ERR_CMD_FAILED
}

internal fun shellDetail(result: ShellResult): String {
    val raw = result.stderr.ifBlank { result.stdout }.trim()
    if (raw.isEmpty()) {
        return if (result.ok) "" else ErrorCatalog.message(shellFailureCode(result))
    }
    return raw.lineSequence().take(3).joinToString(" | ").take(500)
}

internal fun shellFailureMessage(result: ShellResult): String {
    val code = shellFailureCode(result)
    val detail = shellDetail(result)
    return if (detail.isBlank() || detail == ErrorCatalog.message(code)) {
        ErrorCatalog.message(code)
    } else {
        "${ErrorCatalog.message(code)}：$detail"
    }.take(500)
}

internal const val SECURITY_SNAPSHOT_KIND = "security_state"
internal const val SECURITY_SNAPSHOT_TTL_MS = 7L * 24 * 60 * 60 * 1000

internal fun jsonString(value: String): String = buildString(value.length + 2) {
    append('"')
    for (ch in value) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            else -> if (ch < ' ') {
                append("\\u")
                append(ch.code.toString(16).padStart(4, '0'))
            } else {
                append(ch)
            }
        }
    }
    append('"')
}

internal fun jsonNullableString(value: String?): String =
    if (value == null) "null" else jsonString(value)

internal fun jsonStringArray(values: Collection<String>): String =
    values.joinToString(prefix = "[", postfix = "]", separator = ",") { jsonString(it) }
