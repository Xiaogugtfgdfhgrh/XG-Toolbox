package com.xg.toolbox.core.device

import com.xg.toolbox.core.common.ErrorCatalog
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.Capabilities
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.displayText
import javax.inject.Inject
import javax.inject.Singleton

data class RebootTargetInfo(
    val id: String,
    val name: String,
    val note: String,
    val available: Boolean,
    val unavailableReason: String? = null,
    val dangerous: Boolean = false,
)

@Singleton
class RebootController @Inject constructor(
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
) {

    
    fun targets(): List<RebootTargetInfo> {
        val rootState = rootStatus.state.value
        val rootGranted = rootState is RootState.Granted
        val capabilities = rootStatus.capabilities.value
        val rootReason = rootUnavailableReason(rootState)
        return listOf(
            RebootTargetInfo(
                id = TARGET_SYSTEM,
                name = "系统",
                note = "设备将正常重启，回到当前系统",
                available = rootGranted,
                unavailableReason = if (rootGranted) null else rootReason,
            ),
            RebootTargetInfo(
                id = TARGET_RECOVERY,
                name = "Recovery",
                note = recoveryNote(capabilities),
                available = rootGranted,
                unavailableReason = if (rootGranted) null else rootReason,
            ),
            RebootTargetInfo(
                id = TARGET_FASTBOOT,
                name = "Fastboot",
                note = fastbootNote(capabilities),
                available = rootGranted,
                unavailableReason = if (rootGranted) null else rootReason,
            ),
            RebootTargetInfo(
                id = TARGET_EDL,
                name = "EDL",
                note = "设备将进入 EDL（9008）下载模式；仅用于救砖，操作不当可能导致无法开机",
                available = rootGranted && capabilities?.hasEdlReboot == true,
                unavailableReason = when {
                    !rootGranted -> rootReason
                    capabilities == null -> "设备能力尚未探测，请先重新检测 Root 状态"
                    !capabilities.hasEdlReboot -> "未检测到高通平台属性，当前设备可能不支持 reboot edl"
                    else -> null
                },
                dangerous = true,
            ),
            RebootTargetInfo(
                id = TARGET_SHUTDOWN,
                name = "关机",
                note = "设备将关机，需长按电源键重新开机",
                available = rootGranted,
                unavailableReason = if (rootGranted) null else rootReason,
                dangerous = true,
            ),
        )
    }

    
    suspend fun reboot(targetId: String): XgResult<Unit> {
        val target = targets().firstOrNull { it.id == targetId }
            ?: return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "未知重启目标：$targetId")
        if (!target.available) {
            val code = if (rootStatus.state.value !is RootState.Granted) {
                ErrorCode.ERR_SU_DENIED
            } else {
                ErrorCode.ERR_UNSUPPORTED_DEVICE
            }
            return XgResult.Err(code, target.unavailableReason ?: ErrorCatalog.message(code))
        }
        val command = commandFor(targetId)
            ?: return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "未知重启目标：$targetId")
        val result = shell.exec(command, root = true, timeoutMs = REBOOT_TIMEOUT_MS)
        if (result.ok) return XgResult.Ok(Unit)
        val normalized = ErrorCatalog.fromOutput(result.stdout + "\n" + result.stderr)
        val code = when {
            result.code in SHELL_ERROR_CODES -> result.code
            normalized != null -> normalized
            else -> ErrorCode.ERR_CMD_FAILED
        }
        val detail = result.stdout.ifBlank { result.stderr }
            .trim()
            .lineSequence()
            .firstOrNull()
            ?.take(200)
            ?.takeIf { it.isNotEmpty() }
        return XgResult.Err(code, ErrorCatalog.message(code), detail)
    }

    
    private fun commandFor(targetId: String): String? {
        val capabilities = rootStatus.capabilities.value
        return when (targetId) {
            TARGET_SYSTEM -> "svc power reboot"
            TARGET_RECOVERY -> "reboot recovery"
            TARGET_FASTBOOT -> if (capabilities?.hasFastbootd == true) "reboot fastboot" else "reboot bootloader"
            TARGET_EDL -> "reboot edl"
            TARGET_SHUTDOWN -> "svc power shutdown"
            else -> null
        }
    }

    
    private fun recoveryNote(capabilities: Capabilities?): String = when {
        capabilities == null -> "重启进入恢复模式；设备能力尚未探测，部分 A/B 设备可能没有独立 recovery 分区"
        capabilities.isAbDevice -> "重启进入恢复模式；A/B 设备若已无独立 recovery 分区，命令会失败并提示"
        else -> "重启进入恢复模式，可用于刷入更新包或清除数据"
    }

    
    private fun fastbootNote(capabilities: Capabilities?): String = when {
        capabilities == null -> "重启到刷机模式；设备能力尚未探测，将尝试 reboot bootloader，失败会提示"
        capabilities.hasFastbootd -> "重启到 Fastbootd（用户空间刷机模式），等待 USB 连接后由电脑端操作"
        else -> "系统未声明 fastbootd，将尝试 reboot bootloader；若设备不支持，命令会失败并提示"
    }

    
    private fun rootUnavailableReason(rootState: RootState): String = when (rootState) {
        is RootState.Granted -> "Root 权限不可用"
        is RootState.Denied -> rootState.reason.displayText()
        is RootState.Unsupported -> "该设备不支持 Root 操作"
        is RootState.Unknown -> "Root 状态尚未确认，请先重新检测"
    }

    private companion object {
        const val TARGET_SYSTEM = "system"
        const val TARGET_RECOVERY = "recovery"
        const val TARGET_FASTBOOT = "fastboot"
        const val TARGET_EDL = "edl"
        const val TARGET_SHUTDOWN = "shutdown"

        const val REBOOT_TIMEOUT_MS = 15_000L

        
        val SHELL_ERROR_CODES: Set<Int> = (ErrorCode.ERR_SU_MISSING..ErrorCode.ERR_SELINUX).toSet()
    }
}
