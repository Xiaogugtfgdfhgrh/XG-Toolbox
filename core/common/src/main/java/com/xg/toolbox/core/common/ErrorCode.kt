package com.xg.toolbox.core.common

object ErrorCode {
    const val ERR_SU_MISSING = 1001
    const val ERR_SU_DENIED = 1002
    const val ERR_SU_TIMEOUT = 1003
    const val ERR_CMD_FAILED = 1004
    const val ERR_CMD_TIMEOUT = 1005
    const val ERR_READONLY_FS = 1006
    const val ERR_POLICY_BLOCKED = 1007
    const val ERR_SELINUX = 1008

    const val ERR_PART_NOT_FOUND = 2001
    const val ERR_PART_WRITE = 2002
    const val ERR_HASH_MISMATCH = 2003
    const val ERR_BACKUP_FAILED = 2004
    const val ERR_NOT_ENOUGH_SPACE = 2005

    const val ERR_UNSUPPORTED_ABI = 3001
    const val ERR_UNSUPPORTED_DEVICE = 3002
    const val ERR_UNSUPPORTED_ANDROID = 3003

    const val ERR_PARSE_FAILED = 4001
    const val ERR_PERMISSION_GRANT = 4002
}

object ErrorCatalog {
    fun title(code: Int): String = when (code) {
        1001, 1002, 1003 -> "Root 权限不可用"
        in 1004..1008 -> "命令执行失败"
        in 2001..2005 -> "分区操作失败"
        in 3001..3003 -> "设备不支持"
        4001, 4002 -> "系统接口异常"
        else -> "操作失败"
    }

    fun message(code: Int): String = when (code) {
        ErrorCode.ERR_SU_MISSING -> "未检测到 Root"
        ErrorCode.ERR_SU_DENIED -> "Root 授权被拒绝"
        ErrorCode.ERR_SU_TIMEOUT -> "Root 授权超时"
        ErrorCode.ERR_CMD_FAILED -> "执行失败"
        ErrorCode.ERR_CMD_TIMEOUT -> "命令超时"
        ErrorCode.ERR_READONLY_FS -> "分区只读"
        ErrorCode.ERR_POLICY_BLOCKED -> "系统策略阻止"
        ErrorCode.ERR_SELINUX -> "SELinux 阻止"
        ErrorCode.ERR_PART_NOT_FOUND -> "未找到目标分区"
        ErrorCode.ERR_PART_WRITE -> "写入失败"
        ErrorCode.ERR_HASH_MISMATCH -> "校验失败，已回滚"
        ErrorCode.ERR_BACKUP_FAILED -> "备份失败，已中止"
        ErrorCode.ERR_NOT_ENOUGH_SPACE -> "空间不足"
        ErrorCode.ERR_UNSUPPORTED_ABI -> "该应用不含目标架构运行库"
        ErrorCode.ERR_UNSUPPORTED_DEVICE -> "该设备不支持此功能"
        ErrorCode.ERR_UNSUPPORTED_ANDROID -> "需要 Android 11 及以上"
        ErrorCode.ERR_PARSE_FAILED -> "无法解析系统输出"
        ErrorCode.ERR_PERMISSION_GRANT -> "无法授予必要权限"
        else -> "操作失败（$code）"
    }

    
    fun fromOutput(text: String): Int? {
        val t = text.lowercase()
        return when {
            t.contains("not found") || t.contains("no such file") -> ErrorCode.ERR_SU_MISSING
            t.contains("read-only file system") -> ErrorCode.ERR_READONLY_FS
            t.contains("avc: denied") -> ErrorCode.ERR_SELINUX
            t.contains("permission denied") || t.contains("operation not permitted") -> ErrorCode.ERR_SU_DENIED
            else -> null
        }
    }
}
