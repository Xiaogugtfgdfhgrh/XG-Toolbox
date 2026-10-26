package com.xg.toolbox.feature.system.domain.clean

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
class CleanController @Inject constructor(
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val auditRepository: AuditRepository,
    private val snapshotRepository: SnapshotRepository,
) {

    private val scanner = CleanScanner(shell)
    private val actionMutex = Mutex()
    private val cache = SingleFlightResultCache<CleanScan>()

    
    @Volatile
    private var lastScanBytes: Map<String, Long> = emptyMap()

    
    suspend fun scan(
        forceRefresh: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ): XgResult<CleanScan> =
        cache.get(forceRefresh) {
            withContext(Dispatchers.IO) {
                rootStatus.state.value.rootError()?.let { return@withContext it }
                val specs = CleanCategories.specs
                val categories = mutableListOf<CleanCategory>()
                specs.forEachIndexed { index, spec ->
                    val resolved = scanner.resolve(spec)
                    if (resolved.error != null) {
                        return@withContext XgResult.Err(
                            code = ErrorCode.ERR_CMD_FAILED,
                            message = "扫描「${spec.name}」失败：${resolved.error}",
                        )
                    }
                    val targets = resolved.targets
                    val pathText = if (resolved.rejected.isEmpty()) {
                        spec.displayPath
                    } else {
                        val firstReason = resolved.rejected.first().reason
                        "${spec.displayPath} · 已排除 ${resolved.rejected.size} 个非法路径（$firstReason）"
                    }
                    categories += CleanCategory(
                        id = spec.id,
                        name = spec.name,
                        path = pathText,
                        bytes = targets.bytes,
                        fileCount = targets.fileCount,
                        samplePaths = targets.samplePaths,
                        group = spec.group,
                    )
                    onProgress((index + 1) / specs.size.toFloat())
                }
                val scan = CleanScan(categories = categories, totalBytes = categories.sumOf { it.bytes })
                lastScanBytes = categories.associate { it.id to it.bytes }
                XgResult.Ok(scan)
            }
        }

    
    fun clean(categoryIds: Set<String>): Flow<CleanProgress> = flow {
        rootStatus.state.value.rootError()?.let { error ->
            emit(CleanProgress(categoryId = "", percent = 0f, freedBytes = 0L, message = error.message))
            return@flow
        }
        val specs = CleanCategories.specs.filter { it.id in categoryIds }
        if (specs.isEmpty()) {
            emit(CleanProgress(categoryId = "", percent = 0f, freedBytes = 0L, message = "未选择清理项"))
            return@flow
        }
        actionMutex.withLock {
            try {
                specs.forEachIndexed { index, spec ->
                    val basePercent = index / specs.size.toFloat()
                    emit(
                        CleanProgress(
                            categoryId = spec.id,
                            percent = basePercent,
                            freedBytes = 0L,
                            message = "校验白名单路径…",
                        ),
                    )
                    val resolved = scanner.resolve(spec)
                    if (resolved.error != null) {
                        recordAudit(spec.id, "（未执行删除）", AuditResult.FAILED, "路径解析失败：${resolved.error}", null)
                        emit(
                            CleanProgress(
                                categoryId = spec.id,
                                percent = (index + 1) / specs.size.toFloat(),
                                freedBytes = 0L,
                                message = "已拒绝：${resolved.error}",
                            ),
                        )
                        return@forEachIndexed
                    }
                    if (resolved.rejected.isNotEmpty()) {
                        val reason = resolved.rejected.first()
                        emit(
                            CleanProgress(
                                categoryId = spec.id,
                                percent = basePercent,
                                freedBytes = 0L,
                                message = "已拒绝 ${resolved.rejected.size} 个非法路径：${reason.reason}",
                            ),
                        )
                    }
                    val targets = resolved.targets
                    if (targets.isEmpty) {
                        emit(
                            CleanProgress(
                                categoryId = spec.id,
                                percent = (index + 1) / specs.size.toFloat(),
                                freedBytes = 0L,
                                message = "无可清理内容",
                            ),
                        )
                        return@forEachIndexed
                    }
                    val (plan, planError) = scanner.buildDeletePlan(targets)
                    if (plan == null) {
                        recordAudit(spec.id, "（未执行删除）", AuditResult.FAILED, "白名单校验失败：$planError", null)
                        emit(
                            CleanProgress(
                                categoryId = spec.id,
                                percent = (index + 1) / specs.size.toFloat(),
                                freedBytes = 0L,
                                message = "已拒绝：${planError ?: "路径校验失败"}",
                            ),
                        )
                        return@forEachIndexed
                    }
                    val snapshotId = createSnapshot(spec, targets)
                    if (snapshotId == null) {
                        emit(
                            CleanProgress(
                                categoryId = spec.id,
                                percent = (index + 1) / specs.size.toFloat(),
                                freedBytes = 0L,
                                message = "无法写入清理快照，已中止该分类（未删除任何文件）",
                            ),
                        )
                        return@forEachIndexed
                    }
                    val before = scanner.measureBytes(targets)
                    if (before == null) {
                        recordAudit(spec.id, plan.commands.joinToString("\n"), AuditResult.FAILED, "清理前 du 测量失败", snapshotId)
                        emit(
                            CleanProgress(
                                categoryId = spec.id,
                                percent = (index + 1) / specs.size.toFloat(),
                                freedBytes = 0L,
                                message = "清理前 du 测量失败，未执行删除",
                            ),
                        )
                        return@forEachIndexed
                    }
                    emit(
                        CleanProgress(
                            categoryId = spec.id,
                            percent = basePercent + (0.3f / specs.size),
                            freedBytes = 0L,
                            message = "正在删除 ${targets.dirCountText()}（${Formatters.bytes(before)}）…",
                        ),
                    )
                    val results = shell.execMany(plan.commands, root = true, timeoutMs = DELETE_TIMEOUT_MS)
                    val failedIndex = results.indexOfFirst { !it.ok }
                    if (failedIndex >= 0) {
                        val failed = results[failedIndex]
                        val message = "删除命令失败（退出码 ${failed.code}）：" +
                            (firstLine(failed.stdout.ifBlank { failed.stderr }) ?: "无输出")
                        recordAudit(spec.id, plan.commands.joinToString("\n"), AuditResult.FAILED, message, snapshotId)
                        emit(
                            CleanProgress(
                                categoryId = spec.id,
                                percent = (index + 1) / specs.size.toFloat(),
                                freedBytes = 0L,
                                message = message,
                            ),
                        )
                        return@forEachIndexed
                    }
                    val after = scanner.measureBytes(targets)
                    if (after == null) {
                        recordAudit(spec.id, plan.commands.joinToString("\n"), AuditResult.FAILED, "删除后 du 回读失败", snapshotId)
                        emit(
                            CleanProgress(
                                categoryId = spec.id,
                                percent = (index + 1) / specs.size.toFloat(),
                                freedBytes = 0L,
                                message = "删除后 du 回读失败，无法确认释放量",
                            ),
                        )
                        return@forEachIndexed
                    }
                    val freed = (before - after).coerceAtLeast(0L)
                    val discrepancy = discrepancyNote(spec.id, freed)
                    val message = buildString {
                        append("释放 ${Formatters.bytes(freed)}")
                        append("（${Formatters.bytes(before)} → ${Formatters.bytes(after)}）")
                        if (discrepancy != null) append("；$discrepancy")
                    }
                    recordAudit(spec.id, plan.commands.joinToString("\n"), AuditResult.OK, message, snapshotId)
                    emit(
                        CleanProgress(
                            categoryId = spec.id,
                            percent = (index + 1) / specs.size.toFloat(),
                            freedBytes = freed,
                            message = message,
                        ),
                    )
                }
            } finally {
                cache.invalidate()
            }
        }
    }

    private suspend fun createSnapshot(spec: CleanCategorySpec, targets: CleanTargets): Long? {
        val payload = JSONObject().apply {
            put("category", spec.id)
            put("dirs", JSONArray(targets.dirs))
            put("files", JSONArray(targets.files))
            put("beforeBytes", targets.bytes)
            put("fileCount", targets.fileCount)
            put("at", System.currentTimeMillis())
        }.toString()
        return runCatching {
            snapshotRepository.create(KIND_CLEAN, spec.id, payload, SNAPSHOT_TTL_MS)
        }.onFailure { XgLog.e(TAG, "创建清理快照失败：${spec.id}", it) }.getOrNull()
    }

    private suspend fun recordAudit(
        categoryId: String,
        commands: String,
        result: String,
        message: String,
        snapshotId: Long?,
    ) {
        runCatching {
            auditRepository.record(
                category = AuditCategory.SYSTEM,
                action = ACTION_CLEAN,
                target = categoryId,
                commands = commands.take(AUDIT_COMMAND_LIMIT),
                result = result,
                message = message.take(AUDIT_MESSAGE_LIMIT),
                snapshotId = snapshotId,
            )
        }.onFailure { XgLog.e(TAG, "写入清理审计失败：$categoryId", it) }
    }

    
    private fun discrepancyNote(categoryId: String, freed: Long): String? {
        val displayed = lastScanBytes[categoryId] ?: return null
        if (displayed <= 0L) return null
        val diff = kotlin.math.abs(freed - displayed).toDouble() / displayed.toDouble()
        if (diff <= 0.05) return null
        val percent = Formatters.percent((diff * 100).toFloat(), 1)
        return "回读与扫描差异 $percent%（可能有进程同时写入）"
    }

    private fun CleanTargets.dirCountText(): String {
        val parts = mutableListOf<String>()
        if (dirs.isNotEmpty()) parts += "${dirs.size} 个目录"
        if (files.isNotEmpty()) parts += "${files.size} 个文件"
        return parts.joinToString(" / ")
    }

    private fun firstLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200)

    companion object {
        private const val TAG = "CleanController"
        private const val KIND_CLEAN = "clean"
        private const val ACTION_CLEAN = "clean"
        private const val SNAPSHOT_TTL_MS = 7L * 24 * 3600 * 1000
        private const val DELETE_TIMEOUT_MS = 120_000L
        private const val AUDIT_COMMAND_LIMIT = 2000
        private const val AUDIT_MESSAGE_LIMIT = 1000
    }
}
