package com.xg.toolbox.feature.system.domain.flash

import android.content.Context
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.SingleFlightResultCache
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.PartBackupRepository
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.feature.system.domain.perf.ShellIo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class FlashController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val auditRepository: AuditRepository,
    private val snapshotRepository: SnapshotRepository,
    private val partBackupRepository: PartBackupRepository,
) {

    private val probe = FlashProbe(shell)
    private val inspector = FlashImageInspector(context)
    private val busy = AtomicBoolean(false)
    private val flashMutex = Mutex()
    private val cache = SingleFlightResultCache<FlashEnvironment>()

    
    fun isBusy(): Boolean = busy.get()

    
    suspend fun environment(forceRefresh: Boolean = false): XgResult<FlashEnvironment> =
        cache.get(forceRefresh) {
            withContext(Dispatchers.IO) {
                ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
                val scan = probe.probe()
                    ?: return@withContext ShellIo.err(
                        ErrorCode.ERR_PARSE_FAILED,
                        "无法执行分区/槽位探测命令（请确认 root shell 可用）",
                    )
                val rootInfo = rootStatus.rootInfo.value
                val capabilities = rootStatus.capabilities.value
                val evidence = rootStatus.evidence.value.takeLast(EVIDENCE_LIMIT) + buildList {
                    val risks = scan.riskCounts()
                    add(
                        "分区枚举 → ${scan.sourceLabel}，${scan.byNameTotal} 个条目，可刷分区 ${scan.partitions.size} 个" +
                            "（SAFE ${risks[PartitionRisk.SAFE] ?: 0} / CAUTION ${risks[PartitionRisk.CAUTION] ?: 0}" +
                            " / DANGEROUS ${risks[PartitionRisk.DANGEROUS] ?: 0}）",
                    )
                    if (scan.skipped.isNotEmpty()) {
                        add(
                            "安全跳过 ${scan.skipped.size} 个条目：" +
                                scan.skipped.take(4).joinToString("；") { "${it.name}（${it.reason}）" } +
                                if (scan.skipped.size > 4) "…" else "",
                        )
                    }
                    add(
                        "槽位 → ro.boot.slot_suffix=${scan.slotSuffix.ifBlank { "(空)" }}" +
                            "，当前槽 ${scan.currentSlot ?: "未知"}" +
                            "，bootctl=${if (scan.hasBootctl) "可用" else "未找到"}" +
                            if (scan.hasSlotSuffix) "" else "（A-only：不生成 _a/_b 槽位名）",
                    )
                    scan.notes.forEach { note -> add(note) }
                }
                XgResult.Ok(
                    FlashEnvironment(
                        scheme = rootInfo?.scheme?.name ?: "NONE",
                        version = rootInfo?.version.orEmpty(),
                        evidence = evidence,
                        kernelVersion = scan.kernelVersion,
                        bootMode = if (capabilities?.hasFastbootd == true) {
                            "fastbootd 可用（当前走系统内 dd 写入）"
                        } else {
                            "系统内 dd（root）"
                        },
                        availableParts = scan.partitions,
                        slotSuffix = scan.slotSuffix,
                        currentSlot = scan.currentSlot,
                        bootctl = scan.hasBootctl,
                        byNameTotal = scan.byNameTotal,
                    ),
                )
            }
        }

    
    suspend fun inspectImage(uri: String): XgResult<FlashImageInfo> = withContext(Dispatchers.IO) {
        inspector.inspect(uri)
    }

    
    suspend fun backup(partition: String): XgResult<FlashBackup> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        flashMutex.withLock {
            if (busy.get()) {
                return@withLock ShellIo.err(ErrorCode.ERR_CMD_FAILED, "正在刷入镜像，备份操作已被拒绝，请等待写入结束")
            }
            performBackup(partition) { }
        }
    }

    
    suspend fun switchSlot(): XgResult<String> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        flashMutex.withLock {
            if (busy.get()) {
                return@withLock ShellIo.err(ErrorCode.ERR_CMD_FAILED, "正在刷入镜像，槽位切换已被拒绝，请等待写入结束")
            }
            val scan = probe.probe()
                ?: return@withLock ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法读取槽位信息")
            if (!scan.hasBootctl) {
                return@withLock ShellIo.err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    "该设备不支持在系统内切换槽位（未找到 bootctl）",
                )
            }
            val current = when {
                scan.bootctlCurrentSlot == 0 || scan.bootctlCurrentSlot == 1 -> scan.bootctlCurrentSlot
                scan.slotSuffix == "_a" -> 0
                scan.slotSuffix == "_b" -> 1
                else -> null
            } ?: return@withLock ShellIo.err(
                ErrorCode.ERR_UNSUPPORTED_DEVICE,
                "无法判定当前启动槽（bootctl/slot_suffix 均不可用），已拒绝切换",
            )
            val target = 1 - current
            val slotLabel = if (target == 0) "A" else "B"
            val bootctl = scan.bootctlPath ?: "bootctl"
            val command = "${ShellIo.quote(bootctl)} set-active-boot-slot $target"
            try {
                snapshotRepository.create(
                    kind = KIND_SLOT_SWITCH,
                    target = slotLabel,
                    payload = JSONObject().apply {
                        put("fromSlot", if (current == 0) "A" else "B")
                        put("toSlot", slotLabel)
                        put("command", command)
                    }.toString(),
                    ttlMs = SLOT_SNAPSHOT_TTL_MS,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                return@withLock ShellIo.err(
                    ErrorCode.ERR_CMD_FAILED,
                    "无法创建槽位切换快照，已中止：${t.message}",
                )
            }
            val result = shell.exec(command, root = true, timeoutMs = 15_000)
            if (!result.ok) {
                recordAudit(
                    action = "flash_switch_slot",
                    target = slotLabel,
                    commands = command,
                    result = AuditResult.FAILED,
                    message = "slot 切换命令失败",
                )
                return@withLock ShellIo.errFromShell(result, "切换启动槽位失败")
            }
            val readBack = readActiveSlot(bootctl)
            val verified = readBack == target
            val message = if (verified) {
                "已切换到 $slotLabel 槽，并回读确认（重启后生效）"
            } else {
                "槽位切换命令已被系统接受，但无法回读验证（" +
                    (readBack?.toString() ?: "无有效输出") +
                    "）；重启后请检查实际启动槽，本次未修改分区数据"
            }
            recordAudit(
                action = "flash_switch_slot",
                target = slotLabel,
                commands = command,
                result = if (verified) AuditResult.OK else AuditResult.FAILED,
                message = message,
            )
            cache.invalidate()
            XgResult.Ok(message)
        }
    }

    
    private suspend fun readActiveSlot(bootctl: String): Int? {
        for (sub in listOf("get-current-slot", "get-active-boot-slot")) {
            val result = shell.exec("${ShellIo.quote(bootctl)} $sub", root = true, timeoutMs = 15_000)
            if (!result.ok) continue
            val text = result.stdout.trim()
            if (text.isEmpty()) continue
            if (text.equals("a", ignoreCase = true)) return 0
            if (text.equals("b", ignoreCase = true)) return 1
            Regex("(?:^|\\D)([01])(?:\\D|$)").find(text)?.groupValues
                ?.getOrNull(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    
    fun flash(
        partition: String,
        image: FlashImageInfo,
        force: Boolean = false,
        skipBackup: Boolean = false,
    ): Flow<FlashProgress> = channelFlow {
        flashMutex.withLock {
            if (!busy.compareAndSet(false, true)) {
                send(
                    FlashProgress(
                        stage = FlashProgress.STAGE_FAILED,
                        percent = 0f,
                        message = "已有刷入任务进行中，请等待当前任务结束",
                        errorCode = ErrorCode.ERR_CMD_FAILED,
                    ),
                )
                return@withLock
            }
            try {
                runFlashTransaction(partition, image, force, skipBackup)
            } finally {
                busy.set(false)
            }
        }
    }

    
    private suspend fun ProducerScope<FlashProgress>.runFlashTransaction(
        partition: String,
        image: FlashImageInfo,
        force: Boolean,
        skipBackup: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            var backup: FlashBackup? = null
            var target: PartitionInfo? = null
            var writeStarted = false
            var localTemp: File? = null
            var stagingPath: String? = null
            var auditCommands = StringBuilder()
            try {
                send(FlashProgress(FlashProgress.STAGE_VALIDATE, 0f, "正在校验 Root、分区与镜像"))
                ShellIo.requireRoot(rootStatus.state.value)?.let { throw FlashFailure(it) }
                if (!FlashPolicy.isSafeName(partition)) {
                    throw FlashFailure(
                        ShellIo.err(ErrorCode.ERR_POLICY_BLOCKED, "分区名包含非法字符：$partition"),
                    )
                }
                val scan = probe.probe()
                    ?: throw FlashFailure(
                        ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法执行分区探测命令（by-name/内核分区表均不可用）"),
                    )
                val info = scan.findPartition(partition)
                    ?: throw FlashFailure(
                        ShellIo.err(
                            ErrorCode.ERR_PART_NOT_FOUND,
                            "未在本次枚举到的 ${scan.partitions.size} 个分区中找到 $partition" +
                                "（来源 ${scan.sourceLabel}，共 ${scan.byNameTotal} 个条目，" +
                                "槽位 ${scan.slotSuffix.ifBlank { "无" }}）；" +
                                scan.notes.firstOrNull()?.let { "原因：$it；" }.orEmpty() +
                                "请刷新分区列表后重试",
                        ),
                    )
                target = info
                validateResolvedPartition(info, partition, scan)?.let { throw FlashFailure(it) }
                if (!info.writable) {
                    throw FlashFailure(
                        ShellIo.err(ErrorCode.ERR_PART_WRITE, "分区 ${info.name} 不可写（块设备只读或权限不足）"),
                    )
                }
                FlashPolicy.riskGate(info.name, force)?.let { throw FlashFailure(it) }
                if (!image.displayName.lowercase().endsWith(".img")) {
                    throw FlashFailure(
                        ShellIo.err(ErrorCode.ERR_CMD_FAILED, "仅接受 .img 镜像文件（当前：${image.displayName}）"),
                    )
                }
                if (image.sizeBytes <= 0L) {
                    throw FlashFailure(ShellIo.err(ErrorCode.ERR_CMD_FAILED, "镜像为空，已拒绝写入"))
                }
                if (image.sizeBytes > info.sizeBytes) {
                    throw FlashFailure(
                        ShellIo.err(
                            ErrorCode.ERR_CMD_FAILED,
                            "镜像 ${Formatters.bytes(image.sizeBytes)} 大于分区容量 ${Formatters.bytes(info.sizeBytes)}",
                        ),
                    )
                }
                if (skipBackup) {
                    send(
                        FlashProgress(
                            FlashProgress.STAGE_VALIDATE,
                            1f,
                            "已按请求跳过备份（skipBackup=true）：${info.name} 写入失败将无法自动回滚；" +
                                "风险 ${info.risk}，${FlashPolicy.riskDetail(info.name)}",
                        ),
                    )
                } else if (info.sizeBytes > LARGE_PARTITION_BYTES) {
                    send(
                        FlashProgress(
                            FlashProgress.STAGE_VALIDATE,
                            1f,
                            "分区 ${info.name} 为 ${Formatters.bytes(info.sizeBytes)}（>512 MB），" +
                                "默认仍会完整备份；如空间不足或耗时过长，请显式传 skipBackup=true 跳过（无自动回滚）",
                        ),
                    )
                }
                val requiredBytes = (if (skipBackup) 0L else info.sizeBytes) +
                    image.sizeBytes * 2L + RESERVE_BYTES
                if (checkFreeSpace(requiredBytes) != true) {
                    val hint = if (skipBackup) {
                        "请清理 /data/local/tmp 后重试"
                    } else {
                        "可显式传 skipBackup=true 跳过备份后重试（写入失败将无法自动回滚）"
                    }
                    throw FlashFailure(
                        ShellIo.err(
                            ErrorCode.ERR_NOT_ENOUGH_SPACE,
                            "临时目录剩余空间不足（需要约 ${Formatters.bytes(requiredBytes)}）；$hint",
                        ),
                    )
                }

                try {
                    FlashForegroundService.start(context, info.name)
                } catch (t: Exception) {
                    throw FlashFailure(
                        ShellIo.err(
                            ErrorCode.ERR_POLICY_BLOCKED,
                            "无法启动前台服务（${t.javaClass.simpleName}），写入已中止，未修改任何分区",
                        ),
                    )
                }
                send(FlashProgress(FlashProgress.STAGE_VALIDATE, 2f, "校验通过：目标 ${info.name}（${Formatters.bytes(info.sizeBytes)}）"))

                if (skipBackup) {
                    auditCommands.appendLine("skipBackup=true（未备份，写入失败无法自动回滚）")
                    send(
                        FlashProgress(
                            FlashProgress.STAGE_BACKUP,
                            3f + BACKUP_WEIGHT,
                            "已跳过备份（skipBackup=true）；${info.name} 写入失败将无法自动回滚",
                        ),
                    )
                } else {
                    send(FlashProgress(FlashProgress.STAGE_BACKUP, 3f, "正在备份 ${info.name}"))
                    val backupResult = performBackup(info) { percent ->
                        send(
                            FlashProgress(
                                FlashProgress.STAGE_BACKUP,
                                3f + BACKUP_WEIGHT * percent / 100f,
                                "正在备份 ${info.name} · ${percent.toInt()}%",
                            ),
                        )
                    }
                    if (backupResult is XgResult.Err) throw FlashFailure(backupResult)
                    val backupValue = backupResult.getOrNull()
                        ?: throw FlashFailure(ShellIo.err(ErrorCode.ERR_BACKUP_FAILED, "备份结果为空，已中止"))
                    backup = backupValue
                    auditCommands.appendLine("备份：${backupValue.path}（${backupValue.bytes} 字节）")
                    send(
                        FlashProgress(
                            FlashProgress.STAGE_BACKUP,
                            3f + BACKUP_WEIGHT,
                            "备份完成：${backupValue.path}",
                        ),
                    )
                }
                snapshotRepository.create(
                    kind = KIND_FLASH_STATE,
                    target = info.name,
                    payload = JSONObject().apply {
                        put("partition", info.name)
                        put("partitionPath", info.path)
                        put("image", image.displayName)
                        put("imageSha256", image.sha256)
                        put("imageBytes", image.sizeBytes)
                        put("risk", info.risk.name)
                        put("force", force)
                        put("skipBackup", skipBackup)
                        put("backupPath", backup?.path.orEmpty())
                        put("backupSha256", backup?.sha256.orEmpty())
                    }.toString(),
                    ttlMs = SNAPSHOT_TTL_MS,
                )

                send(FlashProgress(FlashProgress.STAGE_STAGING, STAGING_START, "正在暂存镜像并复算 SHA-256"))
                val stagingRoot = File(context.cacheDir, LOCAL_STAGING_DIR)
                if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
                    throw FlashFailure(ShellIo.err(ErrorCode.ERR_CMD_FAILED, "无法创建本地暂存目录"))
                }
                val temp = File(stagingRoot, "${info.name}.img").canonicalFile
                if (temp.parentFile?.canonicalFile != stagingRoot.canonicalFile) {
                    throw FlashFailure(ShellIo.err(ErrorCode.ERR_POLICY_BLOCKED, "本地暂存路径越界，已拒绝写入"))
                }
                localTemp = temp
                val copyResult = inspector.copyToFile(
                    uri = image.uri,
                    target = temp,
                    expectedSha256 = image.sha256,
                    expectedSize = image.sizeBytes,
                    onProgress = { percent ->
                        send(
                            FlashProgress(
                                FlashProgress.STAGE_STAGING,
                                STAGING_START + (STAGING_END - STAGING_START) * percent / 100f,
                                "正在暂存镜像 · ${percent.toInt()}%",
                            ),
                        )
                    },
                )
                if (copyResult is XgResult.Err) throw FlashFailure(copyResult)
                val staged = "$STAGING_DIR${info.name}.img"
                if (!STAGING_FILE_REGEX.matches(staged)) {
                    throw FlashFailure(ShellIo.err(ErrorCode.ERR_POLICY_BLOCKED, "暂存目标不在固定目录，已拒绝写入"))
                }
                stagingPath = staged
                val copyCommand = "mkdir -p /data/local/tmp/xg/staging; " +
                    "cp ${ShellIo.quote(temp.absolutePath)} ${ShellIo.quote(staged)}"
                auditCommands.appendLine(copyCommand)
                val copyShell = shell.exec(copyCommand, root = true, timeoutMs = 120_000)
                if (!copyShell.ok) {
                    throw FlashFailure(ShellIo.errFromShell(copyShell, "复制镜像到暂存目录失败"))
                }
                val stagedHash = sha256OfFile(
                    path = staged,
                    timeoutMs = timeoutForBytes(
                        bytes = image.sizeBytes,
                        baseMs = HASH_BASE_TIMEOUT_MS,
                        minRateBytesPerSecond = HASH_MIN_RATE_BPS,
                        maxMs = HASH_MAX_TIMEOUT_MS,
                    ),
                )
                if (stagedHash == null || !stagedHash.equals(image.sha256, ignoreCase = true)) {
                    throw FlashFailure(
                        ShellIo.err(
                            ErrorCode.ERR_HASH_MISMATCH,
                            "暂存文件 SHA-256 与所选镜像不一致，已中止（未写入分区）",
                        ),
                    )
                }
                send(FlashProgress(FlashProgress.STAGE_STAGING, STAGING_END, "暂存校验通过"))

                writeStarted = true
                send(FlashProgress(FlashProgress.STAGE_WRITING, WRITE_START, "开始写入 ${info.name}"))
                writeImage(info, image.sizeBytes, staged) { percent ->
                    send(
                        FlashProgress(
                            FlashProgress.STAGE_WRITING,
                            WRITE_START + (WRITE_END - WRITE_START) * percent / 100f,
                            "正在写入 ${info.name} · ${percent.toInt()}%",
                        ),
                    )
                }
                auditCommands.appendLine("dd 分块写入 ${image.sizeBytes} 字节 → ${info.path}")

                send(FlashProgress(FlashProgress.STAGE_VERIFY, VERIFY_START, "正在回读 SHA-256 校验"))
                val actual = sha256OfPartitionHead(
                    path = info.path,
                    bytes = image.sizeBytes,
                    timeoutMs = timeoutForBytes(
                        bytes = image.sizeBytes,
                        baseMs = HASH_BASE_TIMEOUT_MS,
                        minRateBytesPerSecond = HASH_MIN_RATE_BPS,
                        maxMs = HASH_MAX_TIMEOUT_MS,
                    ),
                )
                    ?: throw FlashFailure(ShellIo.err(ErrorCode.ERR_CMD_FAILED, "回读分区失败，无法校验"))
                if (!actual.equals(image.sha256, ignoreCase = true)) {
                    throw FlashFailure(
                        ShellIo.err(
                            ErrorCode.ERR_HASH_MISMATCH,
                            "回读 SHA-256 不一致（实际 ${actual.take(16)}…），已触发回滚",
                        ),
                    )
                }
                send(FlashProgress(FlashProgress.STAGE_VERIFY, 98f, "回读校验通过"))
                shell.exec("sync", root = true, timeoutMs = 30_000)

                recordAudit(
                    action = "flash_${info.base}",
                    target = info.name,
                    commands = auditCommands.toString(),
                    result = AuditResult.OK,
                    message = "写入完成并回读校验通过：${image.displayName} → ${info.name}",
                )
                send(
                    FlashProgress(
                        FlashProgress.STAGE_DONE,
                        100f,
                        "写入完成且回读校验通过；请重启设备使镜像生效",
                    ),
                )
            } catch (failure: FlashFailure) {
                if (writeStarted) {
                    send(
                        FlashProgress(
                            stage = FlashProgress.STAGE_ROLLBACK,
                            percent = WRITE_END,
                            message = rollbackStageMessage(backup, failure.error.message),
                            errorCode = failure.error.code,
                        ),
                    )
                }
                val rollback = rollbackOrExplain(writeStarted, backup, target, auditCommands)
                val finalCode = failure.error.code
                recordAudit(
                    action = "flash_${target?.base ?: partition}",
                    target = target?.name ?: partition,
                    commands = auditCommands.toString(),
                    result = if (rollback.verified) AuditResult.ROLLED_BACK else AuditResult.FAILED,
                    message = "${failure.error.message}；${rollback.message}",
                )
                send(
                    FlashProgress(
                        stage = FlashProgress.STAGE_FAILED,
                        percent = 0f,
                        message = "${failure.error.message}；${rollback.message}",
                        errorCode = finalCode,
                    ),
                )
            } catch (cancelled: CancellationException) {
                val rollbackMessage = withContext(NonCancellable) {
                    rollbackOrExplain(writeStarted, backup, target, auditCommands).message
                }
                XgLog.w(TAG, "刷入被取消：$rollbackMessage")
                throw cancelled
            } catch (t: Throwable) {
                if (writeStarted) {
                    send(
                        FlashProgress(
                            stage = FlashProgress.STAGE_ROLLBACK,
                            percent = WRITE_END,
                            message = rollbackStageMessage(backup, "写入异常 ${t.javaClass.simpleName}"),
                            errorCode = ErrorCode.ERR_CMD_FAILED,
                        ),
                    )
                }
                val rollback = rollbackOrExplain(writeStarted, backup, target, auditCommands)
                recordAudit(
                    action = "flash_${target?.base ?: partition}",
                    target = target?.name ?: partition,
                    commands = auditCommands.toString(),
                    result = if (rollback.verified) AuditResult.ROLLED_BACK else AuditResult.FAILED,
                    message = "写入异常：${t.javaClass.simpleName}；${rollback.message}",
                )
                send(
                    FlashProgress(
                        FlashProgress.STAGE_FAILED,
                        0f,
                        "写入异常（${t.javaClass.simpleName}）；${rollback.message}",
                        ErrorCode.ERR_CMD_FAILED,
                    ),
                )
            } finally {
                if (writeStarted) cache.invalidate()
                FlashForegroundService.stop(context)
                localTemp?.let { temp -> runCatching { temp.delete() } }
                stagingPath?.let { path -> removeStagingFile(path) }
            }
        }
    }

    private suspend fun performBackup(
        partition: String,
        onProgress: suspend (Float) -> Unit,
    ): XgResult<FlashBackup> {
        val scan = probe.probe()
            ?: return ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法枚举分区表")
        val info = scan.findPartition(partition)
            ?: return ShellIo.err(
                ErrorCode.ERR_PART_NOT_FOUND,
                "未在本次枚举到的 ${scan.partitions.size} 个分区中找到 $partition" +
                    "（来源 ${scan.sourceLabel}，共 ${scan.byNameTotal} 条）；" +
                    scan.notes.firstOrNull()?.let { "原因：$it" }.orEmpty(),
            )
        validateResolvedPartition(info, partition, scan)?.let { return it }
        if (!info.writable) {
            return ShellIo.err(ErrorCode.ERR_PART_WRITE, "分区 ${info.name} 不可写（块设备只读或权限不足）")
        }
        return performBackup(info, onProgress)
    }

    
    private suspend fun performBackup(
        info: PartitionInfo,
        onProgress: suspend (Float) -> Unit,
    ): XgResult<FlashBackup> {
        if (FlashPolicy.baseOf(info.name).equals("userdata", ignoreCase = true)) {
            return ShellIo.err(
                ErrorCode.ERR_BACKUP_FAILED,
                "userdata 的备份目标 /data/local/tmp 位于 userdata 自身，无法做一致备份；" +
                    "请以 force=true, skipBackup=true 明确跳过备份，或使用 fastboot/外部存储备份",
            )
        }
        val required = info.sizeBytes + RESERVE_BYTES
        if (checkFreeSpace(required) != true) {
            return ShellIo.err(
                ErrorCode.ERR_NOT_ENOUGH_SPACE,
                "备份空间不足：需要约 ${Formatters.bytes(required)}" +
                    "（分区 ${Formatters.bytes(info.sizeBytes)} + 保留空间）；" +
                    "可在刷入时显式传 skipBackup=true 跳过备份（无自动回滚）",
            )
        }
        shell.exec("mkdir -p $BACKUP_DIR $STAGING_DIR", root = true, timeoutMs = 15_000)
        val timestamp = System.currentTimeMillis()
        val path = "$BACKUP_DIR${info.name}_$timestamp.img"
        if (!BACKUP_FILE_REGEX.matches(path)) {
            return ShellIo.err(ErrorCode.ERR_POLICY_BLOCKED, "备份路径未通过固定目录校验，已拒绝写入：$path")
        }
        onProgress(5f)
        val ddCommand = "dd if=${ShellIo.quote(info.path)} of=${ShellIo.quote(path)} bs=4M conv=fsync"
        val backupTimeout = timeoutForBytes(
            bytes = info.sizeBytes,
            baseMs = BACKUP_BASE_TIMEOUT_MS,
            minRateBytesPerSecond = BACKUP_MIN_RATE_BPS,
            maxMs = BACKUP_MAX_TIMEOUT_MS,
        )
        val ddResult = shell.exec(ddCommand, root = true, timeoutMs = backupTimeout)
        if (!ddResult.ok) {
            removeBackupFile(path)
            recordAudit(
                action = "flash_backup_${info.base}",
                target = info.name,
                commands = ddCommand,
                result = AuditResult.FAILED,
                message = "备份 dd 失败",
            )
            return ShellIo.errFromShell(ddResult, "分区备份失败", ErrorCode.ERR_BACKUP_FAILED)
        }
        onProgress(70f)
        val backupBytes = fileSize(path)
        if (backupBytes == null || backupBytes != info.sizeBytes) {
            removeBackupFile(path)
            recordAudit(
                action = "flash_backup_${info.base}",
                target = info.name,
                commands = ddCommand,
                result = AuditResult.FAILED,
                message = "备份大小 ${backupBytes ?: "读取失败"} 与分区容量 ${info.sizeBytes} 不一致",
            )
            return ShellIo.err(
                ErrorCode.ERR_BACKUP_FAILED,
                "备份大小 ${backupBytes ?: "读取失败"} 与分区容量 ${info.sizeBytes} 不一致，已中止",
            )
        }
        val sha = sha256OfFile(
            path = path,
            timeoutMs = timeoutForBytes(
                bytes = info.sizeBytes,
                baseMs = HASH_BASE_TIMEOUT_MS,
                minRateBytesPerSecond = HASH_MIN_RATE_BPS,
                maxMs = HASH_MAX_TIMEOUT_MS,
            ),
        )
        if (sha == null) {
            removeBackupFile(path)
            recordAudit(
                action = "flash_backup_${info.base}",
                target = info.name,
                commands = "$ddCommand; sha256sum $path",
                result = AuditResult.FAILED,
                message = "备份 SHA-256 计算失败",
            )
            return ShellIo.err(ErrorCode.ERR_BACKUP_FAILED, "备份文件 SHA-256 计算失败，已中止")
        }
        onProgress(90f)
        partBackupRepository.record(
            partition = info.name,
            path = path,
            bytes = backupBytes,
            sha256 = sha,
        )
        pruneBackups(info.name)
        onProgress(100f)
        recordAudit(
            action = "flash_backup_${info.base}",
            target = info.name,
            commands = "$ddCommand; sha256sum $path",
            result = AuditResult.OK,
            message = "备份完成：${Formatters.bytes(backupBytes)}，SHA-256 ${sha.take(16)}…",
        )
        return XgResult.Ok(FlashBackup(partition = info.name, path = path, bytes = backupBytes, sha256 = sha))
    }

    private suspend fun writeImage(
        info: PartitionInfo,
        imageBytes: Long,
        stagingPath: String,
        onProgress: suspend (Float) -> Unit,
    ) {
        val blockSize = DD_BLOCK_BYTES
        val totalBlocks = (imageBytes + blockSize - 1) / blockSize
        var writtenBlocks = 0L
        while (writtenBlocks < totalBlocks) {
            if (FlashForegroundService.abortRequested()) {
                throw FlashFailure(ShellIo.err(ErrorCode.ERR_CMD_FAILED, "用户已中止写入"))
            }
            val count = minOf(BLOCKS_PER_CHUNK, totalBlocks - writtenBlocks)
            val command = "dd if=${ShellIo.quote(stagingPath)} of=${ShellIo.quote(info.path)} " +
                "bs=4M skip=$writtenBlocks seek=$writtenBlocks count=$count conv=fsync 2>&1"
            val result = shell.exec(command, root = true, timeoutMs = WRITE_TIMEOUT_MS)
            if (!result.ok) {
                val firstLine = result.stdout.lineSequence().firstOrNull().orEmpty().take(160)
                throw FlashFailure(
                    ShellIo.err(
                        ErrorCode.ERR_PART_WRITE,
                        "dd 写入失败（块 $writtenBlocks）：${firstLine.ifBlank { "退出码 ${result.code}" }}",
                    ),
                )
            }
            writtenBlocks += count
            onProgress((writtenBlocks * 100L / totalBlocks).toFloat())
        }
    }

    
    private suspend fun rollback(
        info: PartitionInfo,
        backup: FlashBackup,
        auditCommands: StringBuilder,
    ): RollbackOutcome {
        val backupSha = backup.sha256
            ?: return RollbackOutcome(
                message = "回滚中止：备份缺少 SHA-256 记录，无法确认回写结果；备份路径 ${backup.path}",
                verified = false,
                attempted = true,
            )
        val command = "dd if=${ShellIo.quote(backup.path)} of=${ShellIo.quote(info.path)} bs=4M conv=fsync"
        auditCommands.appendLine("回滚：$command")
        val result = shell.exec(
            command,
            root = true,
            timeoutMs = timeoutForBytes(
                bytes = backup.bytes,
                baseMs = ROLLBACK_TIMEOUT_MS,
                minRateBytesPerSecond = BACKUP_MIN_RATE_BPS,
                maxMs = BACKUP_MAX_TIMEOUT_MS,
            ),
        )
        if (!result.ok) {
            XgLog.e(TAG, "回滚 dd 失败：${result.stdout.take(160)}")
            return RollbackOutcome(
                message = "回滚失败（dd 退出码 ${result.code}），请用 fastboot 从备份恢复：${backup.path}",
                verified = false,
                attempted = true,
            )
        }
        val actual = sha256OfPartitionHead(
            path = info.path,
            bytes = backup.bytes,
            timeoutMs = timeoutForBytes(
                bytes = backup.bytes,
                baseMs = HASH_BASE_TIMEOUT_MS,
                minRateBytesPerSecond = HASH_MIN_RATE_BPS,
                maxMs = HASH_MAX_TIMEOUT_MS,
            ),
        )
        return if (actual != null && actual.equals(backupSha, ignoreCase = true)) {
            RollbackOutcome(
                message = "已从备份回写并回读校验通过（备份 ${backup.path}）",
                verified = true,
                attempted = true,
            )
        } else {
            RollbackOutcome(
                message = "回滚回读不一致（实际 ${actual?.take(16) ?: "读取失败"}…），请用 fastboot 从备份恢复：${backup.path}",
                verified = false,
                attempted = true,
            )
        }
    }

    
    private suspend fun validateResolvedPartition(
        info: PartitionInfo,
        requested: String,
        scan: FlashScan,
    ): XgResult.Err? {
        if (!FlashPolicy.isSafeName(info.name)) {
            return ShellIo.err(ErrorCode.ERR_POLICY_BLOCKED, "分区名包含非法字符：${info.name}")
        }
        if (requested != info.name) {
            return ShellIo.err(
                ErrorCode.ERR_POLICY_BLOCKED,
                "请求分区 $requested 与枚举结果 ${info.name} 不一致，已拒绝写入",
            )
        }
        if (scan.partitions.none { it.name == info.name }) {
            return ShellIo.err(
                ErrorCode.ERR_POLICY_BLOCKED,
                "分区 ${info.name} 不在本次枚举结果中，已拒绝写入",
            )
        }
        val expectedPath = when (scan.source) {
            FlashPartitionSource.BY_NAME -> scan.partitionDir?.let { "$it/${info.name}" }
            FlashPartitionSource.PROCFS -> info.path.takeIf {
                isSafeBlockPath(it) && !it.removePrefix(BLOCK_DEVICE_PREFIX).contains('/')
            }
            FlashPartitionSource.NONE -> null
        }
        if (expectedPath == null || info.path != expectedPath) {
            return ShellIo.err(
                ErrorCode.ERR_POLICY_BLOCKED,
                "分区 ${info.name} 的路径与本次探测来源不一致，已拒绝写入",
            )
        }
        if (!isSafeBlockPath(info.path)) {
            return ShellIo.err(
                ErrorCode.ERR_POLICY_BLOCKED,
                "分区 ${info.name} 的路径不在 /dev/block/ 白名单内，已拒绝写入",
            )
        }
        val resolved = resolvePartitionRealPath(info)
            ?: return ShellIo.err(
                ErrorCode.ERR_POLICY_BLOCKED,
                "无法用 root 解析分区 ${info.name} 的真实块设备路径，已拒绝写入",
            )
        if (!isSafeBlockPath(resolved)) {
            return ShellIo.err(
                ErrorCode.ERR_POLICY_BLOCKED,
                "分区 ${info.name} 的符号链接逃逸出 /dev/block/（$resolved），已拒绝写入",
            )
        }
        val blockCheck = shell.exec(
            "test -b ${ShellIo.quote(info.path)} && echo 1 || echo 0",
            root = true,
            timeoutMs = 8_000,
        )
        if (!blockCheck.ok || blockCheck.stdout.trim() != "1") {
            return ShellIo.err(
                ErrorCode.ERR_POLICY_BLOCKED,
                "分区 ${info.name} 不是可访问的块设备（${resolved}），已拒绝写入",
            )
        }
        return null
    }

    
    private suspend fun resolvePartitionRealPath(info: PartitionInfo): String? {
        val result = shell.exec(
            "readlink -f ${ShellIo.quote(info.path)}",
            root = true,
            timeoutMs = 8_000,
        )
        if (!result.ok) return null
        return result.stdout.trim().takeIf { it.isNotEmpty() }
    }

    
    private suspend fun rollbackOrExplain(
        writeStarted: Boolean,
        backup: FlashBackup?,
        target: PartitionInfo?,
        auditCommands: StringBuilder,
    ): RollbackOutcome {
        if (!writeStarted) {
            return RollbackOutcome(message = "尚未写入分区，无需回滚", verified = false, attempted = false)
        }
        val saved = backup
        val info = target
        if (saved == null || info == null) {
            return RollbackOutcome(
                message = "本次未备份（skipBackup=true），无法自动回滚；请用 fastboot 刷回原镜像或从其它备份恢复",
                verified = false,
                attempted = false,
            )
        }
        return rollback(info, saved, auditCommands)
    }

    
    private fun rollbackStageMessage(backup: FlashBackup?, failure: String): String =
        if (backup == null) {
            "写入失败，且本次未备份，无法自动回滚：$failure"
        } else {
            "写入失败，正在从备份回滚：$failure"
        }

    
    private data class RollbackOutcome(
        val message: String,
        val verified: Boolean,
        val attempted: Boolean,
    )

    private suspend fun sha256OfFile(path: String, timeoutMs: Long = HASH_BASE_TIMEOUT_MS): String? {
        if (!ShellIo.isAllowedPath(path)) return null
        val result = shell.exec("sha256sum ${ShellIo.quote(path)}", root = true, timeoutMs = timeoutMs)
        if (!result.ok) return null
        return result.stdout.trim().substringBefore(' ').takeIf { it.length == 64 }
    }

    
    private suspend fun sha256OfPartitionHead(
        path: String,
        bytes: Long,
        timeoutMs: Long = VERIFY_TIMEOUT_MS,
    ): String? {
        if (!isSafeBlockPath(path)) return null
        val command = "head -c $bytes ${ShellIo.quote(path)} | sha256sum"
        val result = shell.exec(command, root = true, timeoutMs = timeoutMs)
        if (!result.ok) return null
        return result.stdout.trim().substringBefore(' ').takeIf { it.length == 64 }
    }

    
    private fun isSafeBlockPath(path: String): Boolean {
        if (!path.startsWith(BLOCK_DEVICE_PREFIX)) return false
        if (path.contains("..") || path.contains('\n') || path.contains('\r')) return false
        if (path.contains("//") || path.endsWith('/')) return false
        val tail = path.removePrefix(BLOCK_DEVICE_PREFIX)
        return tail.isNotEmpty() && !tail.startsWith('/') && REAL_BLOCK_PATH.matches(path)
    }

    
    private fun timeoutForBytes(
        bytes: Long,
        baseMs: Long,
        minRateBytesPerSecond: Long,
        maxMs: Long,
    ): Long {
        if (bytes <= 0L || minRateBytesPerSecond <= 0L) return baseMs
        val transferMs = (bytes / minRateBytesPerSecond) * 1000L
        return (baseMs + transferMs).coerceAtMost(maxMs)
    }

    private suspend fun fileSize(path: String): Long? {
        if (!ShellIo.isAllowedPath(path)) return null
        val result = shell.exec("wc -c ${ShellIo.quote(path)}", root = true, timeoutMs = 15_000)
        if (!result.ok) return null
        return result.stdout.trim().substringBefore(' ').toLongOrNull()
    }

    private suspend fun checkFreeSpace(requiredBytes: Long): Boolean? {
        val result = shell.exec("df -k /data/local/tmp", root = true, timeoutMs = 15_000)
        if (!result.ok) return null
        val availableKb = result.stdout.lineSequence()
            .drop(1)
            .map { it.trim().split(WHITESPACE).filter { token -> token.isNotEmpty() } }
            .firstOrNull { it.size >= 4 }
            ?.getOrNull(3)
            ?.toLongOrNull()
            ?: return null
        return availableKb * 1024L >= requiredBytes
    }

    
    private suspend fun pruneBackups(partition: String) {
        val result = shell.exec("ls -1 $BACKUP_DIR", root = true, timeoutMs = 15_000)
        if (!result.ok) return
        val prefix = "${partition}_"
        val candidates = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(prefix) && it.endsWith(".img") }
            .map { "$BACKUP_DIR$it" }
            .filter { BACKUP_FILE_REGEX.matches(it) }
            .sortedDescending()
            .toList()
        candidates.drop(MAX_BACKUPS_PER_PARTITION).forEach { stale ->
            shell.exec("rm -f ${ShellIo.quote(stale)}", root = true, timeoutMs = 15_000)
        }
    }

    private suspend fun removeBackupFile(path: String) {
        if (!BACKUP_FILE_REGEX.matches(path)) return
        shell.exec("rm -f ${ShellIo.quote(path)}", root = true, timeoutMs = 15_000)
    }

    private suspend fun removeStagingFile(path: String) {
        if (!STAGING_FILE_REGEX.matches(path)) return
        shell.exec("rm -f ${ShellIo.quote(path)}", root = true, timeoutMs = 15_000)
    }

    private suspend fun recordAudit(
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String? = null,
    ) {
        runCatching {
            auditRepository.record(
                category = AuditCategory.FLASH,
                action = action,
                target = target,
                commands = commands.take(MAX_AUDIT_COMMANDS),
                result = result,
                message = message,
            )
        }.onFailure { XgLog.w(TAG, "审计写入失败：${it.javaClass.simpleName}") }
    }

    
    private class FlashFailure(val error: XgResult.Err) : Exception(error.message)

    private companion object {
        const val TAG = "FlashController"
        const val BACKUP_DIR = "/data/local/tmp/xg/backups/"
        const val STAGING_DIR = "/data/local/tmp/xg/staging/"
        const val LOCAL_STAGING_DIR = "flash_staging"
        const val KIND_FLASH_STATE = "flash_state"
        const val KIND_SLOT_SWITCH = "flash_slot_switch"
        const val SNAPSHOT_TTL_MS = 7L * 24 * 3600 * 1000
        const val SLOT_SNAPSHOT_TTL_MS = 7L * 24 * 3600 * 1000
        const val MAX_AUDIT_COMMANDS = 4_000
        const val EVIDENCE_LIMIT = 8

        const val DD_BLOCK_BYTES = 4L * 1024 * 1024
        const val BLOCKS_PER_CHUNK = 8L
        const val MAX_BACKUPS_PER_PARTITION = 3

        const val BACKUP_BASE_TIMEOUT_MS = 180_000L
        const val BACKUP_MIN_RATE_BPS = 32L * 1024 * 1024
        const val BACKUP_MAX_TIMEOUT_MS = 60L * 60 * 1000
        const val HASH_BASE_TIMEOUT_MS = 180_000L
        const val HASH_MIN_RATE_BPS = 48L * 1024 * 1024
        const val HASH_MAX_TIMEOUT_MS = 60L * 60 * 1000
        const val WRITE_TIMEOUT_MS = 120_000L
        const val VERIFY_TIMEOUT_MS = 180_000L
        const val ROLLBACK_TIMEOUT_MS = 180_000L
        const val LARGE_PARTITION_BYTES = 512L * 1024 * 1024
        const val RESERVE_BYTES = 64L * 1024 * 1024
        const val BLOCK_DEVICE_PREFIX = "/dev/block/"

        const val BACKUP_WEIGHT = 12f
        const val STAGING_START = 16f
        const val STAGING_END = 30f
        const val WRITE_START = 30f
        const val WRITE_END = 85f
        const val VERIFY_START = 86f

        val BACKUP_FILE_REGEX = Regex("/data/local/tmp/xg/backups/[A-Za-z0-9_-]+_\\d+\\.img")
        val STAGING_FILE_REGEX = Regex("/data/local/tmp/xg/staging/[A-Za-z0-9_-]+\\.img")
        val REAL_BLOCK_PATH = Regex("/dev/block/[A-Za-z0-9._/-]+")
        val WHITESPACE = Regex("\\s+")
    }
}
