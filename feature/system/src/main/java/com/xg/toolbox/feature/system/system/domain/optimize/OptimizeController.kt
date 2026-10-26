package com.xg.toolbox.feature.system.domain.optimize

import android.content.Context
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.SingleFlightResultCache
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.rootError
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class OptimizeController @Inject constructor(
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val auditRepository: AuditRepository,
    private val snapshotRepository: SnapshotRepository,
    @ApplicationContext private val context: Context,
) {

    private val probe = OptimizeProbe(shell)
    private val actionMutex = Mutex()
    private val cache = SingleFlightResultCache<OptimizeScan>()

    
    @Volatile
    private var bloatEntriesCache: List<BloatEntry>? = null

    
    @Volatile
    private var lastScanStorageUsedBytes: Long? = null

    
    suspend fun scan(forceRefresh: Boolean = false): XgResult<OptimizeScan> =
        cache.get(forceRefresh) {
            withContext(Dispatchers.IO) {
                rootStatus.state.value.rootError()?.let { return@withContext it }
                val raw = probe.scanRaw()
                lastScanStorageUsedBytes = raw.storageUsedBytes
                XgResult.Ok(buildScan(raw))
            }
        }

    
    fun run(itemIds: Set<String>): Flow<OptimizeProgress> = flow {
        rootStatus.state.value.rootError()?.let { error ->
            emit(OptimizeProgress(itemId = "", status = STATUS_FAILED, percent = 0f, message = error.message))
            return@flow
        }
        val selected = OptimizeIds.ORDER.filter { it in itemIds }
        if (selected.isEmpty()) {
            emit(OptimizeProgress(itemId = "", status = STATUS_SKIPPED, percent = 0f, message = "未选择任何优化项"))
            return@flow
        }
        actionMutex.withLock {
            try {
                val raw = probe.scanRaw()
                val snapshotId = createSnapshot(raw, selected)
                if (snapshotId == null) {
                    emit(
                        OptimizeProgress(
                            itemId = "",
                            status = STATUS_FAILED,
                            percent = 0f,
                            message = "无法写入原值快照，已中止；未执行任何写操作",
                        ),
                    )
                    return@withLock
                }
                var completed = 0
                selected.forEach { id ->
                    val name = OptimizeCatalog.byId(id)?.name ?: id
                    emit(
                        OptimizeProgress(
                            itemId = id,
                            status = STATUS_RUNNING,
                            percent = completed / selected.size.toFloat(),
                            message = "开始执行：$name",
                        ),
                    )
                    val result = executeItem(id, raw, snapshotId) { stage, message ->
                        emit(
                            OptimizeProgress(
                                itemId = id,
                                status = STATUS_RUNNING,
                                percent = (completed + stage.coerceIn(0f, 0.99f)) / selected.size.toFloat(),
                                message = message,
                            ),
                        )
                    }
                    completed++
                    emit(
                        OptimizeProgress(
                            itemId = id,
                            status = result.status,
                            percent = completed / selected.size.toFloat(),
                            message = result.message,
                        ),
                    )
                }
            } finally {
                cache.invalidate()
            }
        }
    }

    
    suspend fun compare(before: OptimizeScan): XgResult<OptimizeCompare> = withContext(Dispatchers.IO) {
        val mem = probe.readMemInfo(root = false)
            ?: return@withContext err(ErrorCode.ERR_CMD_FAILED, "无法读取内存状态，无法对比")
        val used = probe.readStorageUsedBytes(root = false)
            ?: return@withContext err(ErrorCode.ERR_CMD_FAILED, "无法读取 /data 占用，无法对比")
        val disabled = probe.listDisabledPackages()
            ?: return@withContext err(ErrorCode.ERR_CMD_FAILED, "无法读取已禁用预装组件清单，无法对比")
        val bloat = bloatEntries()
        XgResult.Ok(
            OptimizeCompare(
                beforeUsedBytes = lastScanStorageUsedBytes ?: used,
                afterUsedBytes = used,
                beforeMemBytes = before.availableMemBytes,
                afterMemBytes = mem.availableBytes,
                beforeDisabledBloatCount = before.disabledBloatCount,
                afterDisabledBloatCount = bloat.count { it.pkg in disabled },
            ),
        )
    }

    private fun buildScan(raw: OptimizeScanRaw): OptimizeScan {
        val bloat = bloatEntries()
        val installed = raw.installed
        val disabled = raw.disabled.orEmpty()
        val hits = if (installed != null && bloat.isNotEmpty()) {
            bloat.filter { it.pkg in installed }
        } else {
            emptyList()
        }
        val items = OptimizeIds.ORDER.mapNotNull { id -> buildItem(id, raw, bloat, hits, disabled) }
        return OptimizeScan(
            items = items,
            reclaimBytes = raw.cacheBytes ?: 0L,
            availableMemBytes = raw.mem?.availableBytes ?: 0L,
            disabledBloatCount = bloat.count { it.pkg in disabled },
        )
    }

    private fun buildItem(
        id: String,
        raw: OptimizeScanRaw,
        bloat: List<BloatEntry>,
        bloatHits: List<BloatEntry>,
        disabled: Set<String>,
    ): OptimizeItem? {
        val catalog = OptimizeCatalog.byId(id) ?: return null
        return when (id) {
            OptimizeIds.CACHE -> OptimizeItem(
                id = catalog.id,
                name = catalog.name,
                description = "缓存可释放 ${bytesText(raw.cacheBytes)}",
                benefit = catalog.benefit,
                risk = catalog.risk,
                rollback = catalog.rollback,
                available = true,
            )

            OptimizeIds.MEM -> OptimizeItem(
                id = catalog.id,
                name = catalog.name,
                description = raw.mem?.let {
                    "当前可用 ${Formatters.bytes(it.availableBytes)} / 共 ${Formatters.bytes(it.totalBytes)}"
                } ?: "内存状态读取失败",
                benefit = catalog.benefit,
                risk = catalog.risk,
                rollback = catalog.rollback,
                available = raw.mem != null,
                unavailableReason = "无法读取 /proc/meminfo".takeIf { raw.mem == null },
            )

            OptimizeIds.FSTRIM -> OptimizeItem(
                id = catalog.id,
                name = catalog.name,
                description = when {
                    raw.fstrim.hasSm -> "可用命令：sm fstrim"
                    raw.fstrim.hasFstrim -> "可用命令：fstrim -v /data"
                    else -> "系统未提供 sm fstrim / fstrim 命令"
                },
                benefit = catalog.benefit,
                risk = catalog.risk,
                rollback = catalog.rollback,
                available = raw.fstrim.available,
                unavailableReason = "系统未提供 sm fstrim / fstrim 命令".takeIf { !raw.fstrim.available },
            )

            OptimizeIds.ANIM -> OptimizeItem(
                id = catalog.id,
                name = catalog.name,
                description = raw.anim?.let {
                    "当前窗口 ${it.windowValue} · 过渡 ${it.transitionValue} · Animator ${it.animatorValue}"
                } ?: "动画缩放读取失败",
                benefit = catalog.benefit,
                risk = catalog.risk,
                rollback = catalog.rollback,
                available = raw.anim != null,
                unavailableReason = "无法读取 settings 动画缩放".takeIf { raw.anim == null },
            )

            OptimizeIds.ZRAM -> {
                val zram = raw.zram
                OptimizeItem(
                    id = catalog.id,
                    name = catalog.name,
                    description = when {
                        zram == null -> "未检测到 /sys/block/zram0"
                        zram.hybridSwapPresent ->
                            "厂商混合交换（hybridswap）接管，容量 ${bytesText(zram.disksizeBytes)}"

                        else -> "当前容量 ${bytesText(zram.disksizeBytes)}（换出 ${zram.swapTotalKb} KB）"
                    },
                    benefit = catalog.benefit,
                    risk = catalog.risk,
                    rollback = catalog.rollback,
                    available = zram?.available == true,
                    unavailableReason = when {
                        zram == null -> "未检测到 /sys/block/zram0/"
                        zram.hybridSwapPresent -> "厂商混合交换（hybridswap）接管 ZRAM，禁止改写"
                        !zram.available -> "zram 节点不可读或未启用"
                        else -> null
                    },
                )
            }

            OptimizeIds.BLOAT -> {
                val disabledHits = bloatHits.count { it.pkg in disabled }
                when {
                    bloat.isEmpty() -> OptimizeItem(
                        id = catalog.id,
                        name = catalog.name,
                        description = "内置预装清单为空或加载失败",
                        benefit = catalog.benefit,
                        risk = catalog.risk,
                        rollback = catalog.rollback,
                        available = false,
                        unavailableReason = "内置清单 assets/scripts/bloatlist.json 为空或无法解析",
                    )

                    raw.installed == null -> OptimizeItem(
                        id = catalog.id,
                        name = catalog.name,
                        description = "无法读取已安装包列表",
                        benefit = catalog.benefit,
                        risk = catalog.risk,
                        rollback = catalog.rollback,
                        available = false,
                        unavailableReason = "pm list packages 执行失败",
                    )

                    else -> OptimizeItem(
                        id = catalog.id,
                        name = catalog.name,
                        description = "命中 ${bloatHits.size} 个可禁用组件（已禁用 $disabledHits 个 / 清单 ${bloat.size} 条）",
                        benefit = catalog.benefit,
                        risk = catalog.risk,
                        rollback = catalog.rollback,
                        available = true,
                    )
                }
            }

            else -> null
        }
    }

    private suspend fun executeItem(
        id: String,
        raw: OptimizeScanRaw,
        snapshotId: Long,
        report: suspend (stage: Float, message: String) -> Unit,
    ): OptimizeExecResult = when (id) {
        OptimizeIds.CACHE -> executeCache(snapshotId, report)
        OptimizeIds.MEM -> executeMem(snapshotId, raw, report)
        OptimizeIds.FSTRIM -> executeFstrim(snapshotId, raw, report)
        OptimizeIds.ANIM -> executeAnim(snapshotId, raw, report)
        OptimizeIds.ZRAM -> executeZram(snapshotId, raw, report)
        OptimizeIds.BLOAT -> executeBloat(snapshotId, raw, report)
        else -> OptimizeExecResult(STATUS_FAILED, "未知优化项：$id")
    }

    private suspend fun executeCache(
        snapshotId: Long,
        report: suspend (Float, String) -> Unit,
    ): OptimizeExecResult {
        report(0.1f, "测量缓存占用…")
        val before = probe.readCacheBytes()
            ?: return fail(OptimizeIds.CACHE, "du -sk /data/data/*/cache", snapshotId, "无法读取缓存占用")
        report(0.4f, "执行 pm trim-caches 999G…")
        val result = shell.exec("pm trim-caches 999G", root = true, timeoutMs = CACHE_TRIM_TIMEOUT_MS)
        if (!result.ok) {
            return fail(
                OptimizeIds.CACHE,
                "pm trim-caches 999G",
                snapshotId,
                "pm trim-caches 执行失败（退出码 ${result.code}）",
                firstLine(result.stdout.ifBlank { result.stderr }),
            )
        }
        report(0.75f, "回读缓存占用…")
        val after = probe.readCacheBytes()
            ?: return fail(OptimizeIds.CACHE, "du -sk /data/data/*/cache", snapshotId, "执行成功但回读失败，无法确认释放量")
        val freed = (before - after).coerceAtLeast(0L)
        val message = "释放 ${Formatters.bytes(freed)}（缓存 ${Formatters.bytes(before)} → ${Formatters.bytes(after)}）"
        recordAudit(OptimizeIds.CACHE, "pm trim-caches 999G", AuditResult.OK, message, snapshotId)
        return OptimizeExecResult(STATUS_OK, message)
    }

    private suspend fun executeMem(
        snapshotId: Long,
        raw: OptimizeScanRaw,
        report: suspend (Float, String) -> Unit,
    ): OptimizeExecResult {
        report(0.1f, "测量可用内存…")
        val before = raw.mem ?: probe.readMemInfo() ?: return fail(
            OptimizeIds.MEM, MEM_COMMAND, snapshotId, "无法读取 /proc/meminfo",
        )
        report(0.4f, "执行 am kill-all / sync / drop_caches…")
        val result = shell.exec(MEM_COMMAND, root = true, timeoutMs = MEM_TIMEOUT_MS)
        if (!result.ok) {
            return fail(
                OptimizeIds.MEM,
                MEM_COMMAND,
                snapshotId,
                "释放后台内存失败（退出码 ${result.code}）",
                firstLine(result.stdout.ifBlank { result.stderr }),
            )
        }
        report(0.75f, "回读可用内存…")
        val after = probe.readMemInfo()
            ?: return fail(OptimizeIds.MEM, MEM_COMMAND, snapshotId, "执行成功但回读 /proc/meminfo 失败")
        val gained = after.availableBytes - before.availableBytes
        val message = if (gained >= 0) {
            "可用内存 ${Formatters.bytes(before.availableBytes)} → ${Formatters.bytes(after.availableBytes)}（+${Formatters.bytes(gained)}）"
        } else {
            "可用内存 ${Formatters.bytes(before.availableBytes)} → ${Formatters.bytes(after.availableBytes)}（${Formatters.formatSignedBytes(gained)}）"
        }
        recordAudit(OptimizeIds.MEM, MEM_COMMAND, AuditResult.OK, message, snapshotId)
        return OptimizeExecResult(STATUS_OK, message)
    }

    private suspend fun executeFstrim(
        snapshotId: Long,
        raw: OptimizeScanRaw,
        report: suspend (Float, String) -> Unit,
    ): OptimizeExecResult {
        val tools = raw.fstrim.takeIf { it.available } ?: probe.readFstrimTools()
        if (!tools.available) {
            recordAudit(OptimizeIds.FSTRIM, "sm fstrim", AuditResult.FAILED, "系统未提供 fstrim 命令", snapshotId)
            return OptimizeExecResult(STATUS_SKIPPED, "系统未提供 sm fstrim / fstrim 命令")
        }
        report(0.2f, "执行 fstrim（耗时较长，请勿离开）…")
        if (tools.hasSm) {
            val result = shell.exec("sm fstrim", root = true, timeoutMs = FSTRIM_TIMEOUT_MS)
            if (result.ok) {
                val message = "sm fstrim 完成，退出码 0（该操作无状态可回读，以命令退出码为准）"
                recordAudit(OptimizeIds.FSTRIM, "sm fstrim", AuditResult.OK, message, snapshotId)
                return OptimizeExecResult(STATUS_OK, message)
            }
            if (!tools.hasFstrim) {
                return fail(
                    OptimizeIds.FSTRIM,
                    "sm fstrim",
                    snapshotId,
                    "sm fstrim 失败（退出码 ${result.code}）且无 fstrim 回退命令",
                    firstLine(result.stdout.ifBlank { result.stderr }),
                )
            }
            report(0.6f, "sm fstrim 不可用，回退 fstrim -v /data…")
        }
        val fallback = shell.exec("fstrim -v /data", root = true, timeoutMs = FSTRIM_TIMEOUT_MS)
        if (!fallback.ok) {
            return fail(
                OptimizeIds.FSTRIM,
                "fstrim -v /data",
                snapshotId,
                "fstrim -v /data 失败（退出码 ${fallback.code}）",
                firstLine(fallback.stdout.ifBlank { fallback.stderr }),
            )
        }
        val output = fallback.stdout.trim().take(160)
        val message = if (output.isEmpty()) {
            "fstrim -v /data 完成，退出码 0（无状态可回读）"
        } else {
            "fstrim -v /data 完成：$output"
        }
        recordAudit(OptimizeIds.FSTRIM, "fstrim -v /data", AuditResult.OK, message, snapshotId)
        return OptimizeExecResult(STATUS_OK, message)
    }

    private suspend fun executeAnim(
        snapshotId: Long,
        raw: OptimizeScanRaw,
        report: suspend (Float, String) -> Unit,
    ): OptimizeExecResult {
        val before = raw.anim ?: probe.readAnimScales() ?: return fail(
            OptimizeIds.ANIM, ANIM_PUT_COMMAND, snapshotId, "无法读取动画缩放原值",
        )
        report(0.3f, "写入三档动画缩放 0.5…")
        val result = shell.exec(ANIM_PUT_COMMAND, root = true, timeoutMs = 15_000)
        if (!result.ok) {
            return fail(
                OptimizeIds.ANIM,
                ANIM_PUT_COMMAND,
                snapshotId,
                "写入动画缩放失败（退出码 ${result.code}）",
                firstLine(result.stdout.ifBlank { result.stderr }),
            )
        }
        report(0.7f, "回读动画缩放…")
        val after = probe.readAnimScales()
            ?: return fail(OptimizeIds.ANIM, ANIM_PUT_COMMAND, snapshotId, "写入成功但回读失败")
        val message =
            "动画缩放已置 ${OptimizeIds.ANIM_TARGET_SCALE}（原值 ${before.windowValue} / " +
                "${before.transitionValue} / ${before.animatorValue}）"
        if (!after.allAtTarget) {
            return fail(
                OptimizeIds.ANIM,
                ANIM_PUT_COMMAND,
                snapshotId,
                "回读不一致：窗口 ${after.windowValue} / 过渡 ${after.transitionValue} / Animator ${after.animatorValue}",
            )
        }
        recordAudit(OptimizeIds.ANIM, ANIM_PUT_COMMAND, AuditResult.OK, "$message，回读一致", snapshotId)
        return OptimizeExecResult(STATUS_OK, "$message，回读一致")
    }

    private suspend fun executeZram(
        snapshotId: Long,
        raw: OptimizeScanRaw,
        report: suspend (Float, String) -> Unit,
    ): OptimizeExecResult {
        val state = raw.zram ?: probe.readZramState() ?: return fail(
            OptimizeIds.ZRAM, ZRAM_COMMAND_PREFIX, snapshotId, "无法读取 zram 状态",
        )
        if (state.hybridSwapPresent) {
            recordAudit(OptimizeIds.ZRAM, ZRAM_COMMAND_PREFIX, AuditResult.FAILED, "厂商 hybridswap 接管 ZRAM，禁止改写", snapshotId)
            return OptimizeExecResult(STATUS_SKIPPED, "厂商混合交换（hybridswap）接管 ZRAM，禁止改写")
        }
        val original = state.disksizeBytes
        if (original == null || original <= 0L) {
            return fail(OptimizeIds.ZRAM, ZRAM_COMMAND_PREFIX, snapshotId, "zram0 未启用（disksize 为 0）")
        }
        val mem = raw.mem ?: probe.readMemInfo() ?: return fail(
            OptimizeIds.ZRAM, ZRAM_COMMAND_PREFIX, snapshotId, "无法读取物理内存，无法确定 ZRAM 目标容量",
        )
        val target = alignToPage(mem.totalBytes / 2)
        if (target < MIN_ZRAM_BYTES) {
            return fail(OptimizeIds.ZRAM, ZRAM_COMMAND_PREFIX, snapshotId, "物理内存过小，无法设置合理的 ZRAM 容量")
        }
        if (original == target && state.swapTotalKb > 0L) {
            val message = "ZRAM 已是目标容量 ${Formatters.bytes(target)}，无需调整"
            recordAudit(OptimizeIds.ZRAM, ZRAM_COMMAND_PREFIX, AuditResult.OK, message, snapshotId)
            return OptimizeExecResult(STATUS_OK, message)
        }
        report(0.3f, "调整 ZRAM 容量 ${Formatters.bytes(original)} → ${Formatters.bytes(target)}…")
        val command = "swapoff /dev/block/zram0 2>/dev/null; echo $target > /sys/block/zram0/disksize; " +
            "mkswap /dev/block/zram0; swapon /dev/block/zram0"
        val result = shell.exec(command, root = true, timeoutMs = ZRAM_TIMEOUT_MS)
        report(0.7f, "回读 ZRAM 状态…")
        val after = probe.readZramState()
        val matched = result.ok && after != null &&
            after.disksizeBytes == target && after.swapTotalKb > 0L && !after.hybridSwapPresent
        if (!matched) {
            val rollback = rollbackZram(original)
            val detail = firstLine(result.stdout.ifBlank { result.stderr })
            val rollbackText = if (rollback) {
                "已恢复原容量 ${Formatters.bytes(original)}（回读一致）"
            } else {
                "自动恢复失败，请手动执行：swapoff /dev/block/zram0; echo $original > /sys/block/zram0/disksize; " +
                    "mkswap /dev/block/zram0; swapon /dev/block/zram0"
            }
            return fail(
                OptimizeIds.ZRAM,
                command,
                snapshotId,
                "ZRAM 调整失败（退出码 ${result.code}），$rollbackText",
                detail,
            )
        }
        val message = "ZRAM 容量 ${Formatters.bytes(original)} → ${Formatters.bytes(target)}，回读一致" +
            "（swap ${after?.swapTotalKb ?: 0L} KB）"
        recordAudit(OptimizeIds.ZRAM, command, AuditResult.OK, message, snapshotId)
        return OptimizeExecResult(STATUS_OK, message)
    }

    
    private suspend fun rollbackZram(originalBytes: Long): Boolean {
        val command = "swapoff /dev/block/zram0 2>/dev/null; echo $originalBytes > /sys/block/zram0/disksize; " +
            "mkswap /dev/block/zram0; swapon /dev/block/zram0"
        val result = shell.exec(command, root = true, timeoutMs = ZRAM_TIMEOUT_MS)
        if (!result.ok) return false
        val readback = probe.readZramState() ?: return false
        return readback.disksizeBytes == originalBytes && readback.swapTotalKb > 0L
    }

    private suspend fun executeBloat(
        snapshotId: Long,
        raw: OptimizeScanRaw,
        report: suspend (Float, String) -> Unit,
    ): OptimizeExecResult {
        val entries = bloatEntries()
        if (entries.isEmpty()) {
            recordAudit(OptimizeIds.BLOAT, "pm disable-user", AuditResult.FAILED, "内置预装清单为空", snapshotId)
            return OptimizeExecResult(STATUS_FAILED, "内置预装清单为空，无法执行")
        }
        val installed = raw.installed ?: probe.listInstalledPackages() ?: return fail(
            OptimizeIds.BLOAT, "pm list packages", snapshotId, "无法读取已安装包列表",
        )
        val disabledBefore = raw.disabled ?: probe.listDisabledPackages().orEmpty()
        val hits = entries.filter { it.pkg in installed && it.pkg !in disabledBefore }
        if (hits.isEmpty()) {
            val message = "未命中清单内可禁用组件（清单 ${entries.size} 条，已禁用 ${entries.count { it.pkg in disabledBefore }} 条）"
            recordAudit(OptimizeIds.BLOAT, "pm disable-user", AuditResult.OK, message, snapshotId)
            return OptimizeExecResult(STATUS_SKIPPED, message)
        }
        val commands = StringBuilder()
        val failures = mutableListOf<String>()
        hits.forEachIndexed { index, entry ->
            report(
                (index.toFloat() / hits.size).coerceIn(0f, 0.9f),
                "禁用 ${entry.name}（${entry.pkg}） ${index + 1}/${hits.size}",
            )
            val result = probe.disablePackage(entry.pkg)
            commands.appendLine(
                "pm disable-user --user ${OptimizeIds.ANDROID_USER} ${shellQuoteForAudit(entry.pkg)}",
            )
            if (!result.ok) {
                failures += "${entry.pkg}（退出码 ${result.code}）"
            }
        }
        report(0.95f, "回读禁用状态…")
        val disabledAfter = probe.listDisabledPackages()
            ?: return fail(
                OptimizeIds.BLOAT,
                commands.toString().trim(),
                snapshotId,
                "执行后回读 `pm list packages -d` 失败，无法确认结果",
            )
        val verified = hits.count { it.pkg in disabledAfter }
        if (verified != hits.size || failures.isNotEmpty()) {
            val missing = hits.filter { it.pkg !in disabledAfter }.map { it.pkg }
            val message = buildString {
                append("部分失败：回读已禁用 $verified/${hits.size} 个")
                if (missing.isNotEmpty()) append("；未生效：${missing.joinToString(", ")}")
                if (failures.isNotEmpty()) append("；命令失败：${failures.joinToString(", ")}")
            }
            recordAudit(OptimizeIds.BLOAT, commands.toString().trim(), AuditResult.FAILED, message, snapshotId)
            return OptimizeExecResult(STATUS_FAILED, message)
        }
        val message = "已禁用 ${hits.size} 个预装组件并回读一致（用户 0）"
        recordAudit(OptimizeIds.BLOAT, commands.toString().trim(), AuditResult.OK, message, snapshotId)
        return OptimizeExecResult(STATUS_OK, message)
    }

    private suspend fun createSnapshot(raw: OptimizeScanRaw, selected: List<String>): Long? {
        val payload = JSONObject().apply {
            put("items", JSONArray(selected))
            put("at", System.currentTimeMillis())
            put(
                "anim",
                JSONObject().apply {
                    put("window", raw.anim?.window ?: JSONObject.NULL)
                    put("transition", raw.anim?.transition ?: JSONObject.NULL)
                    put("animator", raw.anim?.animator ?: JSONObject.NULL)
                },
            )
            put("zramDisksize", raw.zram?.disksizeBytes ?: JSONObject.NULL)
            put("memAvailable", raw.mem?.availableBytes ?: JSONObject.NULL)
            put("storageUsed", raw.storageUsedBytes ?: JSONObject.NULL)
            put("cacheBytes", raw.cacheBytes ?: JSONObject.NULL)
            put("disabledBloat", JSONArray(raw.disabled.orEmpty().toList()))
        }.toString()
        return runCatching {
            snapshotRepository.create(KIND_OPTIMIZE, TARGET_OPTIMIZE, payload, SNAPSHOT_TTL_MS)
        }.onFailure { XgLog.e(TAG, "创建优化快照失败", it) }.getOrNull()
    }

    private suspend fun recordAudit(
        itemId: String,
        commands: String,
        result: String,
        message: String,
        snapshotId: Long,
    ) {
        runCatching {
            auditRepository.record(
                category = AuditCategory.SYSTEM,
                action = itemId,
                target = TARGET_OPTIMIZE,
                commands = commands.take(AUDIT_COMMAND_LIMIT),
                result = result,
                message = message.take(AUDIT_MESSAGE_LIMIT),
                snapshotId = snapshotId,
            )
        }.onFailure { XgLog.e(TAG, "写入优化审计失败：$itemId", it) }
    }

    private suspend fun fail(
        itemId: String,
        commands: String,
        snapshotId: Long,
        message: String,
        detail: String? = null,
    ): OptimizeExecResult {
        val full = if (detail.isNullOrBlank()) message else "$message（$detail）"
        recordAudit(itemId, commands, AuditResult.FAILED, full, snapshotId)
        return OptimizeExecResult(STATUS_FAILED, full)
    }

    private fun bloatEntries(): List<BloatEntry> {
        bloatEntriesCache?.let { return it }
        val loaded = runCatching {
            parseBloatEntries(readAsset(BLOAT_ASSET))
        }.onFailure { XgLog.e(TAG, "读取内置预装清单失败", it) }.getOrDefault(emptyList())
        bloatEntriesCache = loaded
        return loaded
    }

    private fun readAsset(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun bytesText(bytes: Long?): String =
        bytes?.let { Formatters.bytes(it) } ?: "未知"

    private fun alignToPage(bytes: Long): Long {
        if (bytes <= 0L) return 0L
        return bytes / PAGE_SIZE * PAGE_SIZE
    }

    private fun firstLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200)

    
    private fun shellQuoteForAudit(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun err(code: Int, message: String, detail: String? = null): XgResult.Err =
        XgResult.Err(code = code, message = message, detail = detail)

    companion object {
        private const val TAG = "OptimizeController"
        private const val BLOAT_ASSET = "scripts/bloatlist.json"
        private const val KIND_OPTIMIZE = "system_optimize"
        private const val TARGET_OPTIMIZE = "optimize"
        private const val SNAPSHOT_TTL_MS = 7L * 24 * 3600 * 1000
        private const val PAGE_SIZE = 4096L
        private const val MIN_ZRAM_BYTES = 512L * 1024 * 1024
        private const val CACHE_TRIM_TIMEOUT_MS = 60_000L
        private const val MEM_TIMEOUT_MS = 60_000L
        private const val FSTRIM_TIMEOUT_MS = 180_000L
        private const val ZRAM_TIMEOUT_MS = 60_000L
        private const val AUDIT_COMMAND_LIMIT = 2000
        private const val AUDIT_MESSAGE_LIMIT = 1000
        private const val STATUS_RUNNING = "running"
        private const val STATUS_OK = "ok"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_SKIPPED = "skipped"

        private const val MEM_COMMAND = "am kill-all; sync; echo 3 > /proc/sys/vm/drop_caches"
        private const val ANIM_PUT_COMMAND =
            "settings put global window_animation_scale 0.5; " +
                "settings put global transition_animation_scale 0.5; " +
                "settings put global animator_duration_scale 0.5"
        private const val ZRAM_COMMAND_PREFIX = "swapoff /dev/block/zram0; echo <bytes> > /sys/block/zram0/disksize; " +
            "mkswap /dev/block/zram0; swapon /dev/block/zram0"
    }
}
