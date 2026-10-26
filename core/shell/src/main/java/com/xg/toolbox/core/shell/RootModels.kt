package com.xg.toolbox.core.shell

sealed interface RootState {
    data object Unknown : RootState
    data object Granted : RootState
    data class Denied(val reason: DenyReason) : RootState
    data class Unsupported(val reason: String) : RootState
}

enum class DenyReason { NO_BINARY, USER_REJECTED, TIMEOUT, POLICY_BLOCKED, SELINUX_BLOCKED }

enum class RootScheme { MAGISK, KERNELSU, APATCH, OTHER, NONE }

data class RootInfo(
    val scheme: RootScheme,
    val version: String,
    val suPath: String,
    val daemon: String,
    val modulesDir: String,
    val selinux: String,
    val uid: Int,
)

data class RootProbeResult(
    val state: RootState,
    val info: RootInfo?,
    val evidence: List<String>,
)

data class Capabilities(
    val canWriteBlockDevice: Boolean,
    val isAbDevice: Boolean,
    val hasBootctl: Boolean,
    val hasFastbootd: Boolean,
    val hasInitBoot: Boolean,
    val hasZram: Boolean,
    val hasKernelNodes: Boolean,
    val hasGpuNodes: Boolean,
    val hasTouchRateNode: Boolean,
    val hasEdlReboot: Boolean,
    val maxRefreshHz: Int,
    val androidSdk: Int,
)

fun DenyReason.displayText(): String = when (this) {
    DenyReason.NO_BINARY -> "未检测到可用的 su"
    DenyReason.USER_REJECTED -> "Root 授权被拒绝"
    DenyReason.TIMEOUT -> "Root 授权超时"
    DenyReason.POLICY_BLOCKED -> "系统策略阻止授权"
    DenyReason.SELINUX_BLOCKED -> "SELinux 阻止授权"
}
