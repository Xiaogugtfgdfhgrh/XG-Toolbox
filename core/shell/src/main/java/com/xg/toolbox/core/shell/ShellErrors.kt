package com.xg.toolbox.core.shell

import com.xg.toolbox.core.common.ErrorCode

internal object ShellErrors {

    
    fun normalize(output: String): Int? {
        if (output.isBlank()) return null
        val text = output.lowercase()
        return when {
            text.contains("read-only file system") -> ErrorCode.ERR_READONLY_FS
            text.contains("avc: denied") -> ErrorCode.ERR_SELINUX
            text.contains("device policy") || text.contains("securityexception") -> ErrorCode.ERR_POLICY_BLOCKED
            text.contains("su: not found") || text.contains("su: inaccessible or not found") -> ErrorCode.ERR_SU_MISSING
            text.contains("permission denied") || text.contains("operation not permitted") -> ErrorCode.ERR_SU_DENIED
            else -> null
        }
    }

    
    fun isSuLevelDenial(output: String): Boolean {
        val text = output.lowercase()
        if (!text.contains("su:")) return false
        return text.contains("permission denied") ||
            text.contains("not allowed") ||
            text.contains("not found") ||
            text.contains("denied")
    }

    
    fun toDenyReason(code: Int): DenyReason? = when (code) {
        ErrorCode.ERR_SU_MISSING -> DenyReason.NO_BINARY
        ErrorCode.ERR_SU_DENIED -> DenyReason.USER_REJECTED
        ErrorCode.ERR_SU_TIMEOUT -> DenyReason.TIMEOUT
        ErrorCode.ERR_POLICY_BLOCKED -> DenyReason.POLICY_BLOCKED
        ErrorCode.ERR_SELINUX -> DenyReason.SELINUX_BLOCKED
        else -> null
    }
}

internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
