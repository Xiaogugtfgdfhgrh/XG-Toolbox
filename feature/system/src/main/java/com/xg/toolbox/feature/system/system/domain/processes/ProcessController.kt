package com.xg.toolbox.feature.system.domain.processes

import android.os.Process
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.SingleFlightResultCache
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class ProcessController @Inject constructor(
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val auditRepository: AuditRepository,
    private val snapshotRepository: SnapshotRepository,
) {

    private val source = ProcessSource(shell)
    private val actionMutex = Mutex()
    private val cache = SingleFlightResultCache<List<ProcessItem>>()
    private val _lastLoad = MutableStateFlow<List<ProcessItem>>(emptyList())

    
    val lastLoad: StateFlow<List<ProcessItem>> = _lastLoad.asStateFlow()

    
    @Volatile
    private var lastBootTimeSec: Long? = null

    
    suspend fun list(forceRefresh: Boolean = false): XgResult<List<ProcessItem>> =
        cache.get(forceRefresh) {
            withContext(Dispatchers.IO) {
                rootStatus.state.value.rootError()?.let { return@withContext it }
                val load = when (val result = source.load(Process.myUid())) {
                    is XgResult.Err -> return@withContext result
                    is XgResult.Ok -> result.data
                }
                lastBootTimeSec = load.bootTimeSec
                if (load.processes.isEmpty()) {
                    return@withContext XgResult.Err(
                        code = ErrorCode.ERR_PARSE_FAILED,
                        message = "未能解析出任何进程",
                    )
                }
                val baseline = load.psTotalCount
                if (baseline != null && baseline > 0) {
                    val rate = kotlin.math.abs(load.processes.size - baseline).toDouble() / baseline
                    val percent = String.format(java.util.Locale.US, "%.1f", rate * 100)
                    if (rate > COUNT_TOLERANCE) {
                        XgLog.w(
                            TAG,
                            "进程数量与 ps -A 差异 $percent%（列表 ${load.processes.size} / ps $baseline，" +
                                "过滤噪音 ${load.filteredNoise}）",
                        )
                    } else {
                        XgLog.i(
                            TAG,
                            "进程列表 ${load.processes.size} 个（ps -A $baseline，差异 $percent%，" +
                                "过滤噪音 ${load.filteredNoise}）",
                        )
                    }
                } else if (load.usedProcFallback) {
                    XgLog.w(TAG, "ps 不可用，已回退 /proc 遍历（列表 ${load.processes.size} 个）")
                }
                _lastLoad.value = load.processes
                XgResult.Ok(load.processes)
            }
        }

    
    suspend fun stop(item: ProcessItem, force: Boolean): XgResult<Unit> = withContext(Dispatchers.IO) {
        rootStatus.state.value.rootError()?.let { return@withContext it }
        if (item.pid <= 0) {
            return@withContext XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "非法 PID：${item.pid}")
        }
        if (item.protected) {
            return@withContext XgResult.Err(
                code = ErrorCode.ERR_POLICY_BLOCKED,
                message = item.protectedReason ?: "该进程受保护，禁止结束",
            )
        }
        actionMutex.withLock {
            val live = readLiveProc(item.pid)
            val guardError = evaluateStopGuard(item, live, lastBootTimeSec, Process.myUid())
            if (guardError != null) {
                recordAudit(item, "（未执行停止：保护校验拒绝）", AuditResult.FAILED, guardError.message, null)
                return@withLock guardError
            }
            val packageName = if (force) null else resolvePackage(item)
            val command = when {
                force -> "kill -9 ${item.pid}"
                packageName != null -> "am force-stop ${shellQuote(packageName)}"
                else -> "kill -TERM ${item.pid}"
            }
            val snapshotId = createSnapshot(item, force)
            if (snapshotId == null) {
                val message = "无法写入进程停止快照，已中止（未发送停止命令）"
                recordAudit(item, "（未执行停止：快照失败）", AuditResult.FAILED, message, null)
                return@withLock XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, message)
            }
            val result = shell.exec(command, root = true, timeoutMs = STOP_TIMEOUT_MS)
            cache.invalidate()
            var alive = checkAlive(item.pid, item)
            if (alive == true) {
                delay(GRACE_RECHECK_MS)
                alive = checkAlive(item.pid, item)
            }
            val restartHint = if (item.ppid <= 2) "；父进程为 init，可能被系统重启" else ""
            val detail = firstLine(result.stdout.ifBlank { result.stderr })
            when (alive) {
                null -> {
                    recordAudit(
                        item = item,
                        commands = command,
                        result = AuditResult.FAILED,
                        message = "无法回读进程状态，不能确认是否结束$restartHint",
                        snapshotId = snapshotId,
                    )
                    XgResult.Err(
                        code = ErrorCode.ERR_CMD_FAILED,
                        message = "无法回读进程状态，不能确认是否结束$restartHint",
                        detail = detail,
                    )
                }

                false -> {
                    val message = "已结束（$command）$restartHint"
                    recordAudit(item, command, AuditResult.OK, message, snapshotId)
                    XgResult.Ok(Unit)
                }

                true -> {
                    val message = "进程 ${item.pid} 仍在运行（退出码 ${result.code}）$restartHint"
                    recordAudit(item, command, AuditResult.FAILED, message, snapshotId)
                    XgResult.Err(
                        code = ErrorCode.ERR_CMD_FAILED,
                        message = message,
                        detail = detail,
                    )
                }
            }
        }
    }

    
    private suspend fun readLiveProc(pid: Int): LiveProcInfo? {
        if (pid <= 0) return null
        val result = shell.exec(
            "if [ -d /proc/$pid ]; then " +
                "read -r comm < /proc/$pid/comm 2>/dev/null; " +
                "cmd=; read -r cmd < /proc/$pid/cmdline 2>/dev/null; " +
                "s=; read -r s < /proc/$pid/stat 2>/dev/null; " +
                "r=\${s##*) }; set -- \$r; state=\$1; ppid=\$2; start=\${20}; " +
                "u=-1; while read -r k v rest; do case \$k in Uid:*) u=\$v; break;; esac; done < /proc/$pid/status 2>/dev/null; " +
                "echo XG_EXISTS:1; echo XG_COMM:\$comm; echo XG_STATE:\$state; echo XG_PPID:\$ppid; " +
                "echo XG_UID:\$u; echo XG_START:\$start; echo XG_CMD:\$cmd; " +
                "else echo XG_EXISTS:0; fi",
            root = true,
            timeoutMs = CHECK_TIMEOUT_MS,
        )
        if (result.code >= SYNTHETIC_ERROR_FLOOR || result.stdout.isBlank()) return null
        var exists = false
        var comm = ""
        var state = ""
        var ppid = 0
        var uid = -1
        var startTicks = 0L
        var cmdline = ""
        result.stdout.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line == "XG_EXISTS:0" -> exists = false
                line == "XG_EXISTS:1" -> exists = true
                line.startsWith("XG_COMM:") -> comm = line.removePrefix("XG_COMM:").trim()
                line.startsWith("XG_STATE:") -> state = line.removePrefix("XG_STATE:").trim()
                line.startsWith("XG_PPID:") -> ppid = line.removePrefix("XG_PPID:").trim().toIntOrNull() ?: 0
                line.startsWith("XG_UID:") -> uid = line.removePrefix("XG_UID:").trim().toIntOrNull() ?: -1
                line.startsWith("XG_START:") -> startTicks = line.removePrefix("XG_START:").trim().toLongOrNull() ?: 0L
                line.startsWith("XG_CMD:") -> cmdline = line.removePrefix("XG_CMD:").trim()
            }
        }
        return LiveProcInfo(
            exists = exists,
            comm = comm,
            state = state,
            ppid = ppid,
            uid = uid,
            startTicks = startTicks,
            cmdline = cmdline,
        )
    }

    
    private suspend fun checkAlive(pid: Int, item: ProcessItem): Boolean? {
        val result = shell.exec(
            "sleep 0.4; if kill -0 $pid 2>/dev/null; then echo XG_KILL0:alive; else echo XG_KILL0:dead; fi; " +
                "if [ -d /proc/$pid ]; then read -r s < /proc/$pid/stat 2>/dev/null; " +
                "r=\${s##*) }; set -- \$r; echo XG_STATE:\$1; echo XG_START:\${20}; else echo XG_STATE:GONE; fi",
            root = true,
            timeoutMs = CHECK_TIMEOUT_MS,
        )
        if (result.code >= SYNTHETIC_ERROR_FLOOR || result.stdout.isBlank()) return null
        var state: String? = null
        var startTicks: Long? = null
        result.stdout.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("XG_STATE:") -> state = line.removePrefix("XG_STATE:").trim()
                line.startsWith("XG_START:") -> startTicks = line.removePrefix("XG_START:").trim().toLongOrNull()
            }
        }
        val currentState = state ?: return null
        if (currentState == STATE_GONE) return false
        if (currentState.startsWith(STATE_ZOMBIE)) return false
        val bootTimeSec = lastBootTimeSec
        val expectedStartMs = item.startTimeMs
        val observedStartTicks = startTicks
        if (bootTimeSec != null && expectedStartMs != null && observedStartTicks != null) {
            val expectedTicks = (expectedStartMs - bootTimeSec * 1000L) / TICK_MS
            if (kotlin.math.abs(expectedTicks - observedStartTicks) > TICK_TOLERANCE) {
                return false
            }
        }
        return true
    }

    
    private suspend fun resolvePackage(item: ProcessItem): String? {
        val firstToken = item.cmdline.trim().split(WHITESPACE).firstOrNull()
            ?: item.name.trim().split(WHITESPACE).firstOrNull()
            ?: return null
        val candidate = firstToken.substringBefore(':').trim()
        if (candidate.length !in 3..200 || !PACKAGE_NAME.matches(candidate)) return null
        val result = shell.exec(
            "pm list packages ${shellQuote(candidate)}",
            root = true,
            timeoutMs = 10_000,
        )
        if (!result.ok) return null
        val matched = result.stdout.lineSequence().any { line -> line.trim() == "package:$candidate" }
        return candidate.takeIf { matched }
    }

    private suspend fun createSnapshot(item: ProcessItem, force: Boolean): Long? {
        val payload = JSONObject().apply {
            put("pid", item.pid)
            put("name", item.name)
            put("ppid", item.ppid)
            put("cmdline", item.cmdline)
            put("uid", item.uid)
            put("force", force)
            put("at", System.currentTimeMillis())
        }.toString()
        return runCatching {
            snapshotRepository.create(KIND_PROCESS_STOP, "pid:${item.pid}", payload, SNAPSHOT_TTL_MS)
        }.onFailure { XgLog.e(TAG, "创建进程停止快照失败：${item.pid}", it) }.getOrNull()
    }

    private suspend fun recordAudit(
        item: ProcessItem,
        commands: String,
        result: String,
        message: String,
        snapshotId: Long?,
    ) {
        runCatching {
            auditRepository.record(
                category = AuditCategory.SYSTEM,
                action = ACTION_STOP_PROCESS,
                target = "pid:${item.pid} ${item.name}",
                commands = commands.take(AUDIT_COMMAND_LIMIT),
                result = result,
                message = message.take(AUDIT_MESSAGE_LIMIT),
                snapshotId = snapshotId,
            )
        }.onFailure { XgLog.e(TAG, "写入进程停止审计失败：${item.pid}", it) }
    }

    private fun firstLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200)

    
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        private const val TAG = "ProcessController"
        private const val KIND_PROCESS_STOP = "process_stop"
        private const val ACTION_STOP_PROCESS = "stop_process"
        private const val SNAPSHOT_TTL_MS = 24L * 3600 * 1000
        private const val STOP_TIMEOUT_MS = 30_000L
        private const val CHECK_TIMEOUT_MS = 15_000L
        private const val GRACE_RECHECK_MS = 800L
        private const val SYNTHETIC_ERROR_FLOOR = 1000
        private const val AUDIT_COMMAND_LIMIT = 2000
        private const val AUDIT_MESSAGE_LIMIT = 1000
        private const val COUNT_TOLERANCE = 0.05
        private const val TICK_MS = 10L
        private const val TICK_TOLERANCE = 2L
        private const val STATE_GONE = "GONE"
        private const val STATE_ZOMBIE = "Z"
        private val WHITESPACE = Regex("\\s+")

        
        private val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")

        
        internal fun evaluateStopGuard(
            item: ProcessItem,
            live: LiveProcInfo?,
            bootTimeSec: Long?,
            myUid: Int,
        ): XgResult.Err? {
            if (live == null) {
                return XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "无法回读进程 ${item.pid} 的 /proc 信息，已拒绝停止",
                )
            }
            if (!live.exists) {
                return XgResult.Err(
                    ErrorCode.ERR_POLICY_BLOCKED,
                    "进程 ${item.pid} 已不存在（列表可能过期），未执行停止",
                )
            }
            if (live.state.startsWith(STATE_ZOMBIE)) {
                return XgResult.Err(
                    ErrorCode.ERR_POLICY_BLOCKED,
                    "进程 ${item.pid} 已结束（僵尸待内核回收），无需停止",
                )
            }
            if (item.pid <= 2) {
                return XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "pid<=2（init/内核线程）受保护，禁止结束")
            }
            if (live.comm == "kthreadd") {
                return XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "kthreadd 内核线程受保护，禁止结束")
            }
            if (item.pid == 1 || live.comm == "init") {
                return XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "init 进程受保护，禁止结束")
            }
            if (live.comm == "system_server" || live.cmdline.contains("system_server")) {
                return XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "system_server 关键进程受保护，禁止结束")
            }
            if (myUid > 0 && live.uid == myUid) {
                return XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "本应用进程受保护，禁止结束")
            }
            if (live.ppid == 2 || live.cmdline.isBlank()) {
                return XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "内核线程不可结束")
            }
            val expectedStartMs = item.startTimeMs
            if (bootTimeSec != null && expectedStartMs != null && live.startTicks > 0) {
                val expectedTicks = (expectedStartMs - bootTimeSec * 1000L) / TICK_MS
                if (kotlin.math.abs(expectedTicks - live.startTicks) > TICK_TOLERANCE) {
                    return XgResult.Err(
                        ErrorCode.ERR_POLICY_BLOCKED,
                        "进程 ${item.pid} 已变化（pid 可能被复用），请刷新进程列表后再试",
                    )
                }
            }
            return null
        }
    }
}
