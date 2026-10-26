package com.xg.toolbox.feature.system.domain.perf

import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.SingleFlightResultCache
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PerfController @Inject constructor(
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val auditRepository: AuditRepository,
    private val snapshotRepository: SnapshotRepository,
) {

    private val probe = PerfProbe(shell)
    private val actionMutex = Mutex()
    private val cache = SingleFlightResultCache<PerfState>()
    private val guardMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var guardJob: Job? = null

    
    private val downgradedGovernors = mutableMapOf<String, String>()

    
    suspend fun readState(forceRefresh: Boolean = false): XgResult<PerfState> =
        cache.get(forceRefresh) {
            withContext(Dispatchers.IO) {
                ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
                val snapshot = probe.probe() ?: return@withContext noNodesError()
                XgResult.Ok(buildState(snapshot))
            }
        }

    
    suspend fun apply(controlId: String, choiceId: String): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            val snapshot = probe.probe() ?: return@withLock noNodesError()
            val control = buildState(snapshot).controls.firstOrNull { it.id == controlId }
                ?: return@withLock ShellIo.err(ErrorCode.ERR_CMD_FAILED, "未知的调节项：$controlId")
            if (!control.available) {
                return@withLock ShellIo.err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    control.unavailableReason ?: "该调节项在当前设备不可用",
                )
            }
            if (control.choices.none { it.id == choiceId }) {
                return@withLock ShellIo.err(ErrorCode.ERR_CMD_FAILED, "未知的档位：$choiceId")
            }
            val originals = ensureOriginals(snapshot)
            when (controlId) {
                PerfControlIds.CPU_GOVERNOR -> writeGovernor(snapshot, control, choiceId)
                PerfControlIds.CPU_FREQ_CAP -> writeCpuFreqCap(snapshot, control, choiceId, originals)
                PerfControlIds.GPU_LEVEL -> writeGpuLevel(snapshot, control, choiceId)
                PerfControlIds.IO_SCHEDULER -> writeIoScheduler(snapshot, control, choiceId)
                PerfControlIds.THERMAL_POLICY -> writeThermalPolicy(snapshot, control, choiceId)
                PerfControlIds.RUN_MODE -> writeRunMode(snapshot, control, choiceId)
                else -> ShellIo.err(ErrorCode.ERR_CMD_FAILED, "未知的调节项：$controlId")
            }
        }
    }

    
    suspend fun setMemoryCompression(enabled: Boolean): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            val snapshot = probe.probe() ?: return@withLock noNodesError()
            val zram = snapshot.zram
                ?: return@withLock ShellIo.err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    "未检测到 zram 节点，无法调节内存压缩",
                )
            if (zram.enabled == enabled) {
                cache.invalidate()
                return@withLock XgResult.Ok(Unit)
            }
            ensureOriginals(snapshot)
            val beforeJson = zramJson(zram)
            val snapshotId = snapshotRepository.create(
                KIND_STATE,
                TARGET_ZRAM,
                beforeJson,
                SNAPSHOT_TTL_MS,
            )
            val targetBytes = alignToPage(zram.maxAllowedBytes)
            val commands: List<String>
            if (enabled) {
                if (targetBytes < MIN_ZRAM_BYTES) {
                    return@withLock ShellIo.err(ErrorCode.ERR_CMD_FAILED, "物理内存过小，无法启用 zram")
                }
                commands = listOf(
                    "swapoff /dev/block/zram0",
                    "echo 1 > /sys/block/zram0/reset",
                    "echo $targetBytes > /sys/block/zram0/disksize",
                    "mkswap /dev/block/zram0",
                    "swapon /dev/block/zram0",
                )
            } else {
                commands = listOf(
                    "swapoff /dev/block/zram0",
                    "echo 1 > /sys/block/zram0/reset",
                )
            }
            val results = shell.execMany(commands, root = true, timeoutMs = 20_000)
            val failedCommand = commands.indices.firstOrNull { !results[it].ok }
            if (failedCommand != null) {
                return@withLock recordFailure(
                    action = "perf_zram",
                    target = "zram",
                    commands = commands.joinToString("\n"),
                    snapshotId = snapshotId,
                    message = "内存压缩${if (enabled) "启用" else "关闭"}命令失败",
                    error = ShellIo.errFromShell(results[failedCommand], "ZRAM 写入失败：${commands[failedCommand]}"),
                )
            }
            val readback = readZramState()
                ?: return@withLock recordFailure(
                    action = "perf_zram",
                    target = "zram",
                    commands = commands.joinToString("\n"),
                    snapshotId = snapshotId,
                    message = "内存压缩回读失败",
                    error = ShellIo.err(ErrorCode.ERR_CMD_FAILED, "ZRAM 写入后无法回读状态"),
                )
            val expectedDisksize = if (enabled) targetBytes else 0L
            val matches = readback.disksizeBytes == expectedDisksize && readback.enabled == enabled
            if (!matches) {
                return@withLock recordFailure(
                    action = "perf_zram",
                    target = "zram",
                    commands = commands.joinToString("\n"),
                    snapshotId = snapshotId,
                    message = "回读不一致：disksize=${readback.disksizeBytes}，期望 $expectedDisksize",
                    error = ShellIo.err(
                        ErrorCode.ERR_CMD_FAILED,
                        "内存压缩回读不一致（disksize=${readback.disksizeBytes}，期望 $expectedDisksize）",
                    ),
                )
            }
            recordAudit(
                action = "perf_zram",
                target = "zram",
                commands = commands.joinToString("\n"),
                result = AuditResult.OK,
                message = if (enabled) "已启用 zram ${Formatters.bytes(targetBytes)}" else "已关闭 zram",
                snapshotId = snapshotId,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        }
    }

    
    suspend fun setThermalGuard(enabled: Boolean): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            if (enabled) {
                if (guardJob?.isActive == true) {
                    cache.invalidate()
                    return@withLock XgResult.Ok(Unit)
                }
                guardJob = scope.launch {
                    while (isActive) {
                        runThermalGuardOnce()
                        delay(GUARD_INTERVAL_MS)
                    }
                }
                recordAudit(
                    action = "perf_thermal_guard",
                    target = "thermal",
                    commands = "应用内 15s 采样；≥${GUARD_TRIGGER_C}°C 回落 governor，≤${GUARD_RESUME_C}°C 恢复",
                    result = AuditResult.OK,
                    message = "已开启温控保护（仅应用进程存活期间生效）",
                )
                cache.invalidate()
                XgResult.Ok(Unit)
            } else {
                guardJob?.cancel()
                guardJob = null
                restoreDowngradedGovernors()
                recordAudit(
                    action = "perf_thermal_guard",
                    target = "thermal",
                    commands = "取消温控保护协程",
                    result = AuditResult.OK,
                    message = "已关闭温控保护",
                )
                cache.invalidate()
                XgResult.Ok(Unit)
            }
        }
    }

    
    suspend fun restoreDefaults(): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            val entity = snapshotRepository.latest(KIND_ORIGINALS, TARGET_ORIGINALS)
                ?: return@withLock ShellIo.err(
                    ErrorCode.ERR_CMD_FAILED,
                    "没有可恢复的原值记录（尚未通过本应用修改性能设置）",
                )
            if (entity.expiresAt <= System.currentTimeMillis()) {
                return@withLock ShellIo.err(
                    ErrorCode.ERR_CMD_FAILED,
                    "原值记录已过期（保留 7 天），无法恢复默认；请重新读取当前状态后再调整",
                )
            }
            val originals = PerfOriginals.fromJson(entity.payload)
                ?: return@withLock ShellIo.err(ErrorCode.ERR_CMD_FAILED, "原值记录损坏，无法解析")
            val commands = StringBuilder()
            val failures = mutableListOf<String>()

            originals.governors.forEach { (policy, governor) ->
                val command = "echo $governor > $policy/scaling_governor"
                commands.appendLine(command)
                val readback = writeAndRead(command, "$policy/scaling_governor")
                if (readback != governor) failures += "$policy governor 期望 $governor 实际 ${readback ?: "读取失败"}"
            }
            originals.cpuMaxFreqKhz.forEach { (policy, khz) ->
                val command = "echo $khz > $policy/scaling_max_freq"
                commands.appendLine(command)
                val readback = writeAndRead(command, "$policy/scaling_max_freq")?.toLongOrNull()
                if (readback != khz) failures += "$policy max_freq 期望 $khz 实际 ${readback ?: "读取失败"}"
            }
            originals.cpuMinFreqKhz.forEach { (policy, khz) ->
                val command = "echo $khz > $policy/scaling_min_freq"
                commands.appendLine(command)
                val readback = writeAndRead(command, "$policy/scaling_min_freq")?.toLongOrNull()
                if (readback != khz) failures += "$policy min_freq 期望 $khz 实际 ${readback ?: "读取失败"}"
            }
            val gpuMin = originals.gpuMinFreqHz
            val gpuMax = originals.gpuMaxFreqHz
            if (gpuMin != null && gpuMax != null) {
                val maxCommand = "echo $gpuMax > $GPU_DEVFREQ_MAX"
                val minCommand = "echo $gpuMin > $GPU_DEVFREQ_MIN"
                commands.appendLine(maxCommand).appendLine(minCommand)
                shell.exec(maxCommand, root = true, timeoutMs = 8_000)
                shell.exec(minCommand, root = true, timeoutMs = 8_000)
                val readbackMax = ShellIo.readFile(shell, GPU_DEVFREQ_MAX)?.toLongOrNull()
                val readbackMin = ShellIo.readFile(shell, GPU_DEVFREQ_MIN)?.toLongOrNull()
                if (readbackMax != gpuMax) failures += "GPU max_freq 期望 $gpuMax 实际 ${readbackMax ?: "读取失败"}"
                if (readbackMin != gpuMin) failures += "GPU min_freq 期望 $gpuMin 实际 ${readbackMin ?: "读取失败"}"
            }
            val ioScheduler = originals.ioScheduler
            val ioPath = originals.ioScheduler?.let { readIoSchedulerPath() }
            if (ioScheduler != null && ioPath != null) {
                val command = "echo $ioScheduler > $ioPath"
                commands.appendLine(command)
                ShellIo.writeFile(shell, ioPath, ioScheduler)
                val readback = readIoSchedulerCurrent(ioPath)
                if (readback != ioScheduler) failures += "I/O 调度器 期望 $ioScheduler 实际 ${readback ?: "读取失败"}"
            }
            originals.thermalPolicies.forEach { (zone, policy) ->
                val command = "echo $policy > $zone/policy"
                commands.appendLine(command)
                val readback = writeAndRead(command, "$zone/policy")
                if (readback != policy) failures += "$zone policy 期望 $policy 实际 ${readback ?: "读取失败"}"
            }
            val lowPower = originals.lowPower
            if (lowPower != null) {
                val command = "settings put global low_power $lowPower"
                commands.appendLine(command)
                ShellIo.settingsPut(shell, "global", "low_power", lowPower)
                val readback = ShellIo.settingsGet(shell, "global", "low_power")
                if (readback != lowPower) failures += "low_power 期望 $lowPower 实际 ${readback ?: "读取失败"}"
            }
            val zramDisksize = originals.zramDisksizeBytes
            if (zramDisksize != null) {
                val restore = restoreZram(zramDisksize)
                commands.appendLine(restore.command)
                if (!restore.ok) failures += "zram 期望 disksize=$zramDisksize 实际 ${restore.actual}"
            }

            if (failures.isNotEmpty()) {
                val message = "恢复默认未完全生效：" + failures.joinToString("；")
                recordAudit(
                    action = "perf_restore",
                    target = "perf",
                    commands = commands.toString(),
                    result = AuditResult.FAILED,
                    message = message,
                    snapshotId = entity.id,
                )
                cache.invalidate()
                return@withLock ShellIo.err(ErrorCode.ERR_CMD_FAILED, message)
            }
            recordAudit(
                action = "perf_restore",
                target = "perf",
                commands = commands.toString(),
                result = AuditResult.OK,
                message = "已恢复首次修改前的性能设置",
                snapshotId = entity.id,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        }
    }

    private suspend fun writeGovernor(
        snapshot: PerfSnapshot,
        control: PerfControl,
        governor: String,
    ): XgResult<Unit> {
        val snapshotId = snapshotRepository.create(
            KIND_STATE,
            control.id,
            controlSnapshotPayload(snapshot, control),
            SNAPSHOT_TTL_MS,
        )
        val commands = StringBuilder()
        val failures = mutableListOf<String>()
        snapshot.clusters.forEach { cluster ->
            val path = "${cluster.policyPath}/scaling_governor"
            val command = "echo $governor > $path"
            commands.appendLine(command)
            val readback = writeAndRead(command, path)
            if (readback != governor) {
                failures += "簇 ${cluster.cpuIds}：期望 $governor 实际 ${readback ?: "读取失败"}"
            }
        }
        return finishControl(
            control = control,
            choiceId = governor,
            commands = commands.toString(),
            failures = failures,
            successMessage = "CPU 调度已切换为 ${labelOfGovernor(governor)}",
            snapshotId = snapshotId,
        )
    }

    private suspend fun writeCpuFreqCap(
        snapshot: PerfSnapshot,
        control: PerfControl,
        choiceId: String,
        originals: PerfOriginals,
    ): XgResult<Unit> {
        val globalTarget = when {
            choiceId == CHOICE_UNLIMITED -> null
            choiceId.startsWith(CHOICE_KHZ_PREFIX) -> choiceId.removePrefix(CHOICE_KHZ_PREFIX).toLongOrNull()
            else -> null
        }
        if (choiceId != CHOICE_UNLIMITED && globalTarget == null) {
            return ShellIo.err(ErrorCode.ERR_CMD_FAILED, "未知的频率档位：$choiceId")
        }
        val snapshotId = snapshotRepository.create(
            KIND_STATE,
            control.id,
            controlSnapshotPayload(snapshot, control),
            SNAPSHOT_TTL_MS,
        )
        val commands = StringBuilder()
        val failures = mutableListOf<String>()
        snapshot.clusters.forEach { cluster ->
            val expected = if (globalTarget == null) {
                originals.cpuMaxFreqKhz[cluster.policyPath]
                    ?: cluster.availableFreqKhz.lastOrNull()
                    ?: cluster.maxFreqKhz
            } else {
                snapDown(cluster.availableFreqKhz, globalTarget) ?: cluster.maxFreqKhz
            }
            if (expected <= 0L) {
                failures += "簇 ${cluster.cpuIds}：无可用频率档位，跳过写入"
                return@forEach
            }
            val path = "${cluster.policyPath}/scaling_max_freq"
            val command = "echo $expected > $path"
            commands.appendLine(command)
            ShellIo.writeFile(shell, path, expected.toString())
            val readback = ShellIo.readFile(shell, path)?.toLongOrNull()
            if (readback != expected) {
                failures += "簇 ${cluster.cpuIds}：期望 $expected 实际 ${readback ?: "读取失败"} kHz"
            }
        }
        val success = if (globalTarget == null) {
            "已取消 CPU 频率上限"
        } else {
            "CPU 频率上限已设为 ≤ ${ShellIo.formatKhz(globalTarget)}"
        }
        return finishControl(
            control = control,
            choiceId = choiceId,
            commands = commands.toString(),
            failures = failures,
            successMessage = success,
            snapshotId = snapshotId,
        )
    }

    private suspend fun writeGpuLevel(
        snapshot: PerfSnapshot,
        control: PerfControl,
        choiceId: String,
    ): XgResult<Unit> {
        val gpu = snapshot.gpu
            ?: return ShellIo.err(ErrorCode.ERR_UNSUPPORTED_DEVICE, "未检测到 KGSL devfreq 节点")
        val lowest = gpu.availableFreqHz.first()
        val highest = gpu.availableFreqHz.last()
        val middle = gpu.availableFreqHz[(gpu.availableFreqHz.size - 1) / 2]
        val target = when (choiceId) {
            CHOICE_GPU_LOW -> lowest to lowest
            CHOICE_GPU_BALANCED -> lowest to middle
            CHOICE_GPU_HIGH -> lowest to highest
            else -> return ShellIo.err(ErrorCode.ERR_CMD_FAILED, "未知的 GPU 档位：$choiceId")
        }
        val snapshotId = snapshotRepository.create(
            KIND_STATE,
            control.id,
            controlSnapshotPayload(snapshot, control),
            SNAPSHOT_TTL_MS,
        )
        val commands = listOf(
            "echo ${target.second} > $GPU_DEVFREQ_MAX",
            "echo ${target.first} > $GPU_DEVFREQ_MIN",
        )
        shell.execMany(commands, root = true, timeoutMs = 8_000)
        val readbackMax = ShellIo.readFile(shell, GPU_DEVFREQ_MAX)?.toLongOrNull()
        val readbackMin = ShellIo.readFile(shell, GPU_DEVFREQ_MIN)?.toLongOrNull()
        val failures = mutableListOf<String>()
        if (readbackMax != target.second) failures += "max_freq 期望 ${target.second} 实际 ${readbackMax ?: "读取失败"}"
        if (readbackMin != target.first) failures += "min_freq 期望 ${target.first} 实际 ${readbackMin ?: "读取失败"}"
        return finishControl(
            control = control,
            choiceId = choiceId,
            commands = commands.joinToString("\n"),
            failures = failures,
            successMessage = "GPU 频率档位已切换",
            snapshotId = snapshotId,
        )
    }

    private suspend fun writeIoScheduler(
        snapshot: PerfSnapshot,
        control: PerfControl,
        scheduler: String,
    ): XgResult<Unit> {
        val io = snapshot.io
            ?: return ShellIo.err(ErrorCode.ERR_UNSUPPORTED_DEVICE, "未检测到块设备调度器节点")
        if (scheduler !in io.available) {
            return ShellIo.err(ErrorCode.ERR_CMD_FAILED, "调度器 $scheduler 不在可用列表：${io.available.joinToString("/")}")
        }
        val snapshotId = snapshotRepository.create(
            KIND_STATE,
            control.id,
            controlSnapshotPayload(snapshot, control),
            SNAPSHOT_TTL_MS,
        )
        val command = "echo $scheduler > ${io.path}"
        val write = ShellIo.writeFile(shell, io.path, scheduler)
        if (!write.ok) {
            return recordFailure(
                action = "perf_io_scheduler",
                target = io.path,
                commands = command,
                snapshotId = snapshotId,
                message = "I/O 调度器写入失败",
                error = ShellIo.errFromShell(write, "I/O 调度器写入失败"),
            )
        }
        val readback = readIoSchedulerCurrent(io.path)
        val failures = if (readback == scheduler) {
            emptyList()
        } else {
            listOf("期望 $scheduler 实际 ${readback ?: "读取失败"}")
        }
        return finishControl(
            control = control,
            choiceId = scheduler,
            commands = command,
            failures = failures,
            successMessage = "I/O 调度器已切换为 $scheduler",
            snapshotId = snapshotId,
        )
    }

    private suspend fun writeThermalPolicy(
        snapshot: PerfSnapshot,
        control: PerfControl,
        policy: String,
    ): XgResult<Unit> {
        val targets = snapshot.thermalZones.filter { policy in it.availablePolicies }
        if (targets.isEmpty()) {
            return ShellIo.err(
                ErrorCode.ERR_UNSUPPORTED_DEVICE,
                "当前温控节点不支持策略 $policy（可用：${control.choices.joinToString("/") { it.id }}）",
            )
        }
        val commands = StringBuilder()
        val failures = mutableListOf<String>()
        val snapshotId = snapshotRepository.create(
            KIND_STATE,
            control.id,
            controlSnapshotPayload(snapshot, control),
            SNAPSHOT_TTL_MS,
        )
        targets.forEach { zone ->
            val path = "${zone.path}/policy"
            val command = "echo $policy > $path"
            commands.appendLine(command)
            val readback = writeAndRead(command, path)
            if (readback != policy) failures += "${zone.type}：期望 $policy 实际 ${readback ?: "读取失败"}"
        }
        return finishControl(
            control = control,
            choiceId = policy,
            commands = commands.toString(),
            failures = failures,
            successMessage = "温控策略已切换为 $policy（${targets.size} 个温区）",
            snapshotId = snapshotId,
        )
    }

    private suspend fun writeRunMode(
        snapshot: PerfSnapshot,
        control: PerfControl,
        choiceId: String,
    ): XgResult<Unit> {
        val targetLowPower = when (choiceId) {
            CHOICE_RUN_BALANCED -> "0"
            CHOICE_RUN_POWERSAVE -> "1"
            else -> return ShellIo.err(
                ErrorCode.ERR_UNSUPPORTED_DEVICE,
                "未发现可信的厂商性能模式节点，仅支持省电/均衡（不做未经验证的节点写入）",
            )
        }
        val snapshotId = snapshotRepository.create(
            KIND_STATE,
            control.id,
            controlSnapshotPayload(snapshot, control),
            SNAPSHOT_TTL_MS,
        )
        val command = "settings put global low_power $targetLowPower"
        val write = ShellIo.settingsPut(shell, "global", "low_power", targetLowPower)
        if (!write.ok) {
            return recordFailure(
                action = "perf_run_mode",
                target = "low_power",
                commands = command,
                snapshotId = snapshotId,
                message = "运行模式写入失败",
                error = ShellIo.errFromShell(write, "运行模式写入失败"),
            )
        }
        val readback = ShellIo.settingsGet(shell, "global", "low_power")
        val failures = if (readback == targetLowPower) {
            emptyList()
        } else {
            listOf("low_power 期望 $targetLowPower 实际 ${readback ?: "读取失败"}")
        }
        return finishControl(
            control = control,
            choiceId = choiceId,
            commands = command,
            failures = failures,
            successMessage = if (targetLowPower == "1") "已切换到省电模式" else "已切换到均衡模式",
            snapshotId = snapshotId,
        )
    }

    private suspend fun runThermalGuardOnce() {
        guardMutex.withLock {
            if (rootStatus.state.value !is RootState.Granted) return
            val snapshot = runCatching { probe.probe() }.getOrNull() ?: return
            val maxTemp = snapshot.maxCpuTempC ?: return
            if (maxTemp >= GUARD_TRIGGER_C && downgradedGovernors.isEmpty()) {
                val performanceClusters = snapshot.clusters.filter { it.governor == CHOICE_GOVERNOR_PERFORMANCE }
                if (performanceClusters.isEmpty()) return
                val fallback = pickFallbackGovernor(snapshot) ?: return
                val commands = StringBuilder()
                performanceClusters.forEach { cluster ->
                    val path = "${cluster.policyPath}/scaling_governor"
                    ShellIo.writeFile(shell, path, fallback)
                    commands.appendLine("echo $fallback > $path")
                    val readback = ShellIo.readFile(shell, path)
                    if (readback == fallback) downgradedGovernors[cluster.policyPath] = cluster.governor
                }
                if (downgradedGovernors.isNotEmpty()) {
                    cache.invalidate()
                    recordAudit(
                        action = "perf_thermal_guard_trigger",
                        target = "thermal",
                        commands = commands.toString(),
                        result = AuditResult.OK,
                        message = String.format(
                            Locale.US,
                            "CPU %.1f°C ≥ %d°C，已回落到 %s",
                            maxTemp,
                            GUARD_TRIGGER_C,
                            fallback,
                        ),
                    )
                }
            } else if (maxTemp <= GUARD_RESUME_C && downgradedGovernors.isNotEmpty()) {
                restoreDowngradedGovernors()
            }
        }
    }

    private suspend fun restoreDowngradedGovernors() {
        if (downgradedGovernors.isEmpty()) return
        val commands = StringBuilder()
        downgradedGovernors.forEach { (policy, governor) ->
            val path = "$policy/scaling_governor"
            ShellIo.writeFile(shell, path, governor)
            commands.appendLine("echo $governor > $path")
        }
        recordAudit(
            action = "perf_thermal_guard_release",
            target = "thermal",
            commands = commands.toString(),
            result = AuditResult.OK,
            message = "温度已回落，恢复原 CPU 调度：${downgradedGovernors.values.distinct().joinToString("/")}",
        )
        downgradedGovernors.clear()
        cache.invalidate()
    }

    private fun pickFallbackGovernor(snapshot: PerfSnapshot): String? {
        val candidates = snapshot.clusters.flatMap { it.availableGovernors }.distinct()
        return when {
            CHOICE_GOVERNOR_SCHEDUTIL in candidates -> CHOICE_GOVERNOR_SCHEDUTIL
            CHOICE_GOVERNOR_POWERSAVE in candidates -> CHOICE_GOVERNOR_POWERSAVE
            else -> null
        }
    }

    private fun buildState(snapshot: PerfSnapshot): PerfState {
        val controls = listOf(
            cpuGovernorControl(snapshot),
            cpuFreqCapControl(snapshot),
            gpuControl(snapshot),
            ioControl(snapshot),
            thermalPolicyControl(snapshot),
            runModeControl(snapshot),
        )
        val zram = snapshot.zram
        val summary = linkedMapOf(
            "CPU 调度" to labelOfGovernor(snapshot.commonGovernor),
            "GPU 渲染" to gpuLevelLabel(snapshot),
            "内存压缩" to when {
                zram == null -> "不可用"
                zram.enabled -> "已开启 ${Formatters.bytes(zram.disksizeBytes)}"
                else -> "已关闭"
            },
            "温控保护" to thermalSummary(snapshot),
        )
        return PerfState(
            controls = controls,
            memoryCompression = zram?.enabled == true,
            thermalGuard = guardJob?.isActive == true,
            summary = summary,
        )
    }

    private fun cpuGovernorControl(snapshot: PerfSnapshot): PerfControl {
        if (snapshot.clusters.isEmpty()) {
            return unavailableControl(
                id = PerfControlIds.CPU_GOVERNOR,
                name = "CPU 调度",
                description = "调整大小核频率响应策略。",
                reason = "未发现 cpufreq 节点（内核未启用 CPU 频率调节）",
            )
        }
        val available = snapshot.clusters.flatMap { it.availableGovernors }.distinct()
        if (available.isEmpty()) {
            return unavailableControl(
                id = PerfControlIds.CPU_GOVERNOR,
                name = "CPU 调度",
                description = "调整大小核频率响应策略。",
                reason = "cpufreq 未提供可写调度器列表",
            )
        }
        return PerfControl(
            id = PerfControlIds.CPU_GOVERNOR,
            name = "CPU 调度",
            description = "调整大小核频率响应与任务迁移；写入前读取 scaling_available_governors。",
            current = snapshot.commonGovernor ?: snapshot.clusters.first().governor,
            choices = available.map { PerfChoice(it, labelOfGovernor(it)) },
            available = true,
        )
    }

    private fun cpuFreqCapControl(snapshot: PerfSnapshot): PerfControl {
        val clustersWithFreqs = snapshot.clusters.filter { it.availableFreqKhz.isNotEmpty() }
        if (clustersWithFreqs.isEmpty()) {
            return unavailableControl(
                id = PerfControlIds.CPU_FREQ_CAP,
                name = "CPU 最大频率",
                description = "为每个簇设置最大频率上限（按各簇实际可用档位对齐）。",
                reason = "cpufreq 未提供 scaling_available_frequencies",
            )
        }
        val globalMax = clustersWithFreqs.flatMap { it.availableFreqKhz }.maxOrNull() ?: 0L
        val tiers = listOf(100L, 90L, 75L, 60L, 45L)
            .map { (globalMax * it / 100L) }
            .distinct()
            .sortedDescending()
        val choices = buildList {
            add(PerfChoice(CHOICE_UNLIMITED, "不限制"))
            tiers.forEach { add(PerfChoice("$CHOICE_KHZ_PREFIX$it", "≤ ${ShellIo.formatKhz(it)}")) }
        }
        val current = currentFreqCapChoiceId(clustersWithFreqs, choices) ?: run {
            val currentMax = clustersWithFreqs.map { it.maxFreqKhz }.filter { it > 0L }
            when {
                currentMax.isEmpty() -> "未知"
                currentMax.min() == currentMax.max() -> "当前上限 ${ShellIo.formatKhz(currentMax.max())}"
                else -> "当前上限 ${ShellIo.formatKhz(currentMax.min())} – ${ShellIo.formatKhz(currentMax.max())}"
            }
        }
        return PerfControl(
            id = PerfControlIds.CPU_FREQ_CAP,
            name = "CPU 最大频率",
            description = "降低上限可减少发热与功耗；按簇自动对齐到该簇支持的档位。",
            current = current,
            choices = choices,
            available = true,
        )
    }

    private fun gpuControl(snapshot: PerfSnapshot): PerfControl {
        val gpu = snapshot.gpu
        if (gpu == null) {
            return unavailableControl(
                id = PerfControlIds.GPU_LEVEL,
                name = "GPU 频率",
                description = "限制 KGSL 最低/最高频率。",
                reason = "未检测到 /sys/class/kgsl/kgsl-3d0/devfreq（非高通 GPU 或节点不可读）",
            )
        }
        val lowest = gpu.availableFreqHz.first()
        val highest = gpu.availableFreqHz.last()
        val middle = gpu.availableFreqHz[(gpu.availableFreqHz.size - 1) / 2]
        val currentId = when {
            gpu.minFreqHz == lowest && gpu.maxFreqHz == highest -> CHOICE_GPU_HIGH
            gpu.minFreqHz == lowest && gpu.maxFreqHz == middle -> CHOICE_GPU_BALANCED
            gpu.minFreqHz == lowest && gpu.maxFreqHz == lowest -> CHOICE_GPU_LOW
            else -> null
        }
        return PerfControl(
            id = PerfControlIds.GPU_LEVEL,
            name = "GPU 频率",
            description = "限制 GPU 最高频率与渲染档位；最低频率保持可用最低档。",
            current = currentId ?: "自定义 ${formatHz(gpu.minFreqHz)}–${formatHz(gpu.maxFreqHz)}",
            choices = listOf(
                PerfChoice(CHOICE_GPU_LOW, "省电（${formatHz(lowest)}）"),
                PerfChoice(CHOICE_GPU_BALANCED, "均衡（${formatHz(middle)}）"),
                PerfChoice(CHOICE_GPU_HIGH, "性能（${formatHz(highest)}）"),
            ),
            available = true,
        )
    }

    
    private fun gpuLevelLabel(snapshot: PerfSnapshot): String {
        val gpu = snapshot.gpu ?: return "不可用"
        val control = gpuControl(snapshot)
        if (!control.available) return "不可用"
        return control.choices.firstOrNull { it.id == control.current }?.label ?: control.current
    }

    private fun currentFreqCapChoiceId(
        clusters: List<CpuClusterInfo>,
        choices: List<PerfChoice>,
    ): String? {
        if (clusters.all { it.maxFreqKhz > 0L && it.maxFreqKhz == it.availableFreqKhz.lastOrNull() }) {
            return CHOICE_UNLIMITED
        }
        return choices.filter { it.id.startsWith(CHOICE_KHZ_PREFIX) }.firstOrNull { choice ->
            val target = choice.id.removePrefix(CHOICE_KHZ_PREFIX).toLongOrNull() ?: return@firstOrNull false
            clusters.all { cluster -> snapDown(cluster.availableFreqKhz, target) == cluster.maxFreqKhz }
        }?.id
    }

    private fun ioControl(snapshot: PerfSnapshot): PerfControl {
        val io = snapshot.io
        if (io == null) {
            return unavailableControl(
                id = PerfControlIds.IO_SCHEDULER,
                name = "I/O 调度器",
                description = "切换块设备 I/O 调度算法。",
                reason = "未检测到 /sys/block/*/queue/scheduler",
            )
        }
        return PerfControl(
            id = PerfControlIds.IO_SCHEDULER,
            name = "I/O 调度器",
            description = "写入 /sys/block/*/queue/scheduler；写入值必须来自可用列表。",
            current = io.current,
            choices = io.available.map { PerfChoice(it, it) },
            available = true,
        )
    }

    private fun thermalPolicyControl(snapshot: PerfSnapshot): PerfControl {
        if (snapshot.thermalZones.isEmpty()) {
            return unavailableControl(
                id = PerfControlIds.THERMAL_POLICY,
                name = "温控策略",
                description = "切换 CPU/GPU 温区的内核调控策略。",
                reason = "未发现 CPU/GPU 温区的 policy 节点，无法调节（不伪造温控档位）",
            )
        }
        val available = snapshot.thermalZones.flatMap { it.availablePolicies }.distinct()
        if (available.isEmpty()) {
            return unavailableControl(
                id = PerfControlIds.THERMAL_POLICY,
                name = "温控策略",
                description = "切换 CPU/GPU 温区的内核调控策略。",
                reason = "温区未提供可写策略列表",
            )
        }
        val policies = snapshot.thermalZones.map { it.policy }.distinct()
        val current = policies.singleOrNull() ?: "多温区不同（${policies.joinToString("/")}）"
        return PerfControl(
            id = PerfControlIds.THERMAL_POLICY,
            name = "温控策略",
            description = "写入各 CPU/GPU 温区 policy；不同温区可用策略可能不同。",
            current = current,
            choices = available.map { PerfChoice(it, it) },
            available = true,
        )
    }

    private fun runModeControl(snapshot: PerfSnapshot): PerfControl {
        if (snapshot.lowPower == null) {
            return unavailableControl(
                id = PerfControlIds.RUN_MODE,
                name = "运行模式",
                description = "切换系统省电模式。",
                reason = "settings global low_power 不可读写",
            )
        }
        val vendorHint = snapshot.vendorPerf?.let { " · 厂商节点 ${it.current}" }.orEmpty()
        return PerfControl(
            id = PerfControlIds.RUN_MODE,
            name = "运行模式",
            description = "省电模式基于 settings global low_power；厂商性能节点因语义因机型而异不提供写入档位$vendorHint。",
            current = if (snapshot.lowPower) CHOICE_RUN_POWERSAVE else CHOICE_RUN_BALANCED,
            choices = listOf(
                PerfChoice(CHOICE_RUN_BALANCED, "均衡"),
                PerfChoice(CHOICE_RUN_POWERSAVE, "省电"),
            ),
            available = true,
        )
    }

    private fun thermalSummary(snapshot: PerfSnapshot): String {
        if (snapshot.thermalZones.isEmpty()) return "不可用"
        val policy = snapshot.thermalZones.map { it.policy }.distinct().singleOrNull() ?: "多策略"
        val temp = snapshot.maxCpuTempC
        return if (temp != null) String.format(Locale.US, "%s · %.1f °C", policy, temp) else policy
    }

    private fun unavailableControl(id: String, name: String, description: String, reason: String) =
        PerfControl(
            id = id,
            name = name,
            description = description,
            current = "不可用",
            choices = emptyList(),
            available = false,
            unavailableReason = reason,
        )

    private fun labelOfGovernor(governor: String?): String = when (governor) {
        null -> "混合（多簇不同）"
        CHOICE_GOVERNOR_PERFORMANCE -> "性能"
        CHOICE_GOVERNOR_POWERSAVE -> "省电"
        CHOICE_GOVERNOR_SCHEDUTIL -> "均衡"
        CHOICE_GOVERNOR_CONSERVATIVE -> "保守"
        CHOICE_GOVERNOR_INTERACTIVE -> "交互"
        CHOICE_GOVERNOR_ONDEMAND -> "按需"
        CHOICE_GOVERNOR_UAG -> "厂商 UAG"
        CHOICE_GOVERNOR_WALT -> "WALT 调度"
        CHOICE_GOVERNOR_SCX -> "SCX 调度"
        else -> governor
    }

    private fun formatHz(hz: Long): String = ShellIo.formatKhz(hz / 1000L)

    private fun snapDown(availableAsc: List<Long>, target: Long): Long? =
        availableAsc.lastOrNull { it <= target } ?: availableAsc.firstOrNull()

    private suspend fun ensureOriginals(snapshot: PerfSnapshot): PerfOriginals {
        snapshotRepository.latest(KIND_ORIGINALS, TARGET_ORIGINALS)
            ?.let { PerfOriginals.fromJson(it.payload) }
            ?.let { return it }
        val originals = PerfOriginals.from(snapshot)
        snapshotRepository.create(
            kind = KIND_ORIGINALS,
            target = TARGET_ORIGINALS,
            payload = originals.toJson(),
            ttlMs = SNAPSHOT_TTL_MS,
        )
        return originals
    }

    
    private fun controlSnapshotPayload(snapshot: PerfSnapshot, control: PerfControl): String =
        org.json.JSONObject().apply {
            put("control", control.id)
            put("current", control.current)
            when (control.id) {
                PerfControlIds.CPU_GOVERNOR -> put(
                    "governors",
                    org.json.JSONObject(snapshot.clusters.associate { it.policyPath to it.governor }),
                )
                PerfControlIds.CPU_FREQ_CAP -> put(
                    "cpuMaxFreqKhz",
                    org.json.JSONObject(snapshot.clusters.associate { it.policyPath to it.maxFreqKhz }),
                )
                PerfControlIds.GPU_LEVEL -> snapshot.gpu?.let { gpu ->
                    put("gpuMinFreqHz", gpu.minFreqHz)
                    put("gpuMaxFreqHz", gpu.maxFreqHz)
                }
                PerfControlIds.IO_SCHEDULER -> snapshot.io?.let { put("scheduler", it.current) }
                PerfControlIds.THERMAL_POLICY -> put(
                    "thermalPolicies",
                    org.json.JSONObject(snapshot.thermalZones.associate { it.path to it.policy }),
                )
                PerfControlIds.RUN_MODE -> snapshot.lowPower?.let { put("lowPower", if (it) "1" else "0") }
            }
        }.toString()

    private suspend fun finishControl(
        control: PerfControl,
        choiceId: String,
        commands: String,
        failures: List<String>,
        successMessage: String,
        snapshotId: Long,
    ): XgResult<Unit> {
        return if (failures.isEmpty()) {
            recordAudit(
                action = "perf_${control.id}",
                target = choiceId,
                commands = commands,
                result = AuditResult.OK,
                message = successMessage,
                snapshotId = snapshotId,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        } else {
            val message = "${control.name}写入未全部生效：" + failures.joinToString("；")
            recordAudit(
                action = "perf_${control.id}",
                target = choiceId,
                commands = commands,
                result = AuditResult.FAILED,
                message = message,
                snapshotId = snapshotId,
            )
            cache.invalidate()
            ShellIo.err(ErrorCode.ERR_CMD_FAILED, message)
        }
    }

    private suspend fun recordFailure(
        action: String,
        target: String,
        commands: String,
        snapshotId: Long?,
        message: String,
        error: XgResult.Err,
    ): XgResult.Err {
        cache.invalidate()
        recordAudit(action, target, commands, AuditResult.FAILED, message, snapshotId)
        return error
    }

    private suspend fun recordAudit(
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String? = null,
        snapshotId: Long? = null,
    ) {
        runCatching {
            auditRepository.record(
                category = AuditCategory.SYSTEM,
                action = action,
                target = target,
                commands = commands.take(MAX_AUDIT_COMMANDS),
                result = result,
                message = message,
                snapshotId = snapshotId,
            )
        }.onFailure { XgLog.w(TAG, "审计写入失败：${it.javaClass.simpleName}") }
    }

    
    private suspend fun writeAndRead(command: String, path: String): String? {
        val result = shell.exec(command, root = true, timeoutMs = 8_000)
        if (!result.ok) {
            XgLog.w(TAG, "写入失败($path)：${result.stdout.take(120)}")
        }
        return ShellIo.readFile(shell, path)
    }

    private suspend fun readIoSchedulerCurrent(path: String): String? {
        val raw = ShellIo.readFile(shell, path) ?: return null
        val tokens = raw.split(WHITESPACE).filter { it.isNotEmpty() }
        return tokens.firstOrNull { it.startsWith("[") && it.endsWith("]") }
            ?.removeSurrounding("[", "]")
            ?: tokens.firstOrNull()
    }

    private suspend fun readIoSchedulerPath(): String? {
        val result = shell.exec(
            "grep -H '' /sys/block/sd*/queue/scheduler /sys/block/mmcblk*/queue/scheduler 2>/dev/null",
            root = true,
            timeoutMs = 8_000,
        )
        if (!result.ok) return null
        return result.stdout.lineSequence()
            .map { it.substringBefore(':') }
            .firstOrNull { it.contains("/block/") && it.endsWith("/queue/scheduler") }
    }

    private suspend fun readZramState(): ZramInfo? {
        val result = shell.exec(
            "cat /sys/block/zram0/disksize 2>/dev/null; grep zram /proc/swaps 2>/dev/null; grep MemTotal /proc/meminfo 2>/dev/null",
            root = true,
            timeoutMs = 8_000,
        )
        if (!result.ok) return null
        val lines = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val disksize = lines.firstOrNull { it.matches(Regex("\\d+")) }?.toLongOrNull() ?: 0L
        var swapTotal = 0L
        lines.firstOrNull { it.startsWith("/dev/block/zram") }?.let { line ->
            val parts = line.split(WHITESPACE).filter { it.isNotEmpty() }
            swapTotal = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        }
        val memTotalKb = lines.firstOrNull { it.startsWith("MemTotal:") }
            ?.let { MemTotalRegex.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
            ?: return null
        return ZramInfo(
            disksizeBytes = disksize,
            swapTotalKb = swapTotal,
            swapUsedKb = 0L,
            memTotalBytes = memTotalKb * 1024L,
        )
    }

    private suspend fun restoreZram(originalDisksize: Long): ZramRestoreResult {
        if (originalDisksize <= 0L) {
            val commands = listOf(
                "swapoff /dev/block/zram0",
                "echo 1 > /sys/block/zram0/reset",
            )
            val results = shell.execMany(commands, root = true, timeoutMs = 20_000)
            val ok = results.all { it.ok } && readZramState()?.enabled == false
            return ZramRestoreResult(
                command = commands.joinToString("\n"),
                ok = ok,
                actual = if (ok) "已关闭" else "关闭失败",
            )
        }
        val aligned = alignToPage(originalDisksize)
        val commands = listOf(
            "swapoff /dev/block/zram0",
            "echo 1 > /sys/block/zram0/reset",
            "echo $aligned > /sys/block/zram0/disksize",
            "mkswap /dev/block/zram0",
            "swapon /dev/block/zram0",
        )
        val results = shell.execMany(commands, root = true, timeoutMs = 20_000)
        val state = readZramState()
        val ok = results.all { it.ok } && state?.disksizeBytes == aligned && state.enabled
        return ZramRestoreResult(
            command = commands.joinToString("\n"),
            ok = ok,
            actual = state?.let { "disksize=${it.disksizeBytes}，enabled=${it.enabled}" } ?: "读取失败",
        )
    }

    private fun alignToPage(bytes: Long): Long = (bytes / PAGE_SIZE) * PAGE_SIZE

    private fun noNodesError(): XgResult.Err = ShellIo.err(
        ErrorCode.ERR_UNSUPPORTED_DEVICE,
        "未发现可用的性能节点（cpufreq / thermal / zram 均不可读）",
    )

    private data class ZramRestoreResult(val command: String, val ok: Boolean, val actual: String)

    private companion object {
        const val TAG = "PerfController"
        const val KIND_STATE = "perf_state"
        const val KIND_ORIGINALS = "perf_state"
        const val TARGET_ORIGINALS = "perf_originals"
        const val TARGET_ZRAM = "zram"
        const val SNAPSHOT_TTL_MS = 7L * 24 * 3600 * 1000
        const val MAX_AUDIT_COMMANDS = 4_000
        const val PAGE_SIZE = 4096L
        const val MIN_ZRAM_BYTES = 128L * 1024 * 1024

        const val CHOICE_UNLIMITED = "unlimited"
        const val CHOICE_KHZ_PREFIX = "khz:"
        const val CHOICE_GPU_LOW = "low"
        const val CHOICE_GPU_BALANCED = "balanced"
        const val CHOICE_GPU_HIGH = "high"
        const val CHOICE_RUN_BALANCED = "balanced"
        const val CHOICE_RUN_POWERSAVE = "powersave"

        const val CHOICE_GOVERNOR_PERFORMANCE = "performance"
        const val CHOICE_GOVERNOR_POWERSAVE = "powersave"
        const val CHOICE_GOVERNOR_SCHEDUTIL = "schedutil"
        const val CHOICE_GOVERNOR_CONSERVATIVE = "conservative"
        const val CHOICE_GOVERNOR_INTERACTIVE = "interactive"
        const val CHOICE_GOVERNOR_ONDEMAND = "ondemand"
        const val CHOICE_GOVERNOR_UAG = "uag"
        const val CHOICE_GOVERNOR_WALT = "walt"
        const val CHOICE_GOVERNOR_SCX = "scx"

        const val GPU_DEVFREQ_MIN = "/sys/class/kgsl/kgsl-3d0/devfreq/min_freq"
        const val GPU_DEVFREQ_MAX = "/sys/class/kgsl/kgsl-3d0/devfreq/max_freq"

        
        const val GUARD_TRIGGER_C = 42f
        const val GUARD_RESUME_C = 38f
        const val GUARD_INTERVAL_MS = 15_000L

        val WHITESPACE = Regex("\\s+")
        val MemTotalRegex = Regex("MemTotal:\\s*(\\d+)")

        
        fun jsonObject(key: String, value: String): String =
            org.json.JSONObject().put(key, value).toString()

        fun zramJson(zram: ZramInfo): String = org.json.JSONObject().apply {
            put("disksizeBytes", zram.disksizeBytes)
            put("enabled", zram.enabled)
            put("swapTotalKb", zram.swapTotalKb)
        }.toString()
    }
}
