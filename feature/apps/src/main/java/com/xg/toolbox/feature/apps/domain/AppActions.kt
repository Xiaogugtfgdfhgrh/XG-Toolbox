package com.xg.toolbox.feature.apps.domain

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Environment
import android.os.StatFs
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppRepository
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.BatchReceiptRepository
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.rootError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class AppActions @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val appRepository: AppRepository,
    private val audit: AuditRepository,
    private val receipts: BatchReceiptRepository,
    private val snapshots: SnapshotRepository,
    private val androidId: AndroidIdController,
    private val abi: AbiInspector,
    private val appData: AppDataController,
    private val modules: ModuleController,
    private val bootReceivers: BootReceiverController,
) {

    private val _lastReceipt = MutableStateFlow<BatchReceipt?>(null)
    private val receiptCacheLock = Any()
    private val receiptSnapshots = LinkedHashMap<Long, Map<String, Long>>()
    private val undoneSnapshotIds = HashSet<Long>()

    val lastReceipt: StateFlow<BatchReceipt?> = _lastReceipt.asStateFlow()

    
    suspend fun preflight(targets: List<String>, action: BatchAction): XgResult<BatchPreflight> =
        withContext(Dispatchers.IO) {
            val requested = targets.map { it.trim() }.filter { it.isNotEmpty() }
            if (requested.isEmpty()) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "未选择任何目标应用")
            }
            val unique = requested.distinct()
            val issues = mutableListOf<PreflightIssue>()
            if (unique.size != requested.size) {
                issues += PreflightIssue(GLOBAL_ISSUE_TARGET, "重复目标已合并", blocking = false)
            }

            val rootError = rootStatus.state.value.rootError()
            if (rootError != null) {
                issues += PreflightIssue(GLOBAL_ISSUE_TARGET, rootError.message, blocking = true)
            }
            var frozenKnown = false
            val frozen: Set<String> = if (rootStatus.state.value is RootState.Granted) {
                val packages = frozenPackages()
                if (packages == null) {
                    issues += PreflightIssue(
                        GLOBAL_ISSUE_TARGET,
                        "无法读取冻结应用列表，无法判断冻结冲突",
                        blocking = true,
                    )
                    emptySet()
                } else {
                    frozenKnown = true
                    packages
                }
            } else {
                emptySet()
            }

            val resolved = mutableListOf<BatchTarget>()
            val apkPaths = mutableListOf<String>()
            unique.forEach { pkg ->
                if (!AppShellText.isValidPackage(pkg)) {
                    issues += PreflightIssue(pkg, "非法包名", blocking = true)
                    return@forEach
                }
                if (appRepository.isProtected(pkg)) {
                    issues += PreflightIssue(pkg, "受保护应用禁止批量操作", blocking = true)
                    return@forEach
                }
                val info = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
                    ?: run {
                        issues += PreflightIssue(pkg, "应用未安装或无法读取包信息", blocking = true)
                        return@forEach
                    }
                val app = info.applicationInfo ?: run {
                    issues += PreflightIssue(pkg, "应用信息不完整", blocking = true)
                    return@forEach
                }
                val label = runCatching {
                    context.packageManager.getApplicationLabel(app).toString()
                }.getOrDefault(pkg)
                val isSystem = (app.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                when (action) {
                    BatchAction.FREEZE -> {
                        if (frozenKnown && frozen.contains(pkg)) {
                            issues += PreflightIssue(pkg, "已处于冻结状态，将跳过", blocking = false)
                        } else if (isSystem) {
                            issues += PreflightIssue(pkg, "系统应用，冻结可能影响系统功能", blocking = false)
                        }
                    }

                    BatchAction.UNFREEZE -> {
                        if (frozenKnown && !frozen.contains(pkg)) {
                            issues += PreflightIssue(pkg, "当前未冻结，将跳过", blocking = false)
                        }
                    }

                    BatchAction.UNINSTALL -> {
                        if (isSystem) {
                            issues += PreflightIssue(
                                pkg,
                                "系统应用，卸载后仅能通过 install-existing 恢复",
                                blocking = false,
                            )
                        }
                    }

                    BatchAction.DISABLE_AUTOSTART -> {
                        val components = bootReceivers.components(pkg)
                        if (components.isEmpty()) {
                            issues += PreflightIssue(pkg, "未发现开机接收器，将跳过", blocking = false)
                        } else {
                            issues += PreflightIssue(
                                pkg,
                                "将禁用 ${components.size} 个开机接收器",
                                blocking = false,
                            )
                        }
                    }

                    BatchAction.CHANGE_ANDROID_ID -> {
                        issues += PreflightIssue(
                            pkg,
                            "将移除该应用的 SSAID 记录并由系统重新生成；不写指定 ID，且不清理应用数据",
                            blocking = false,
                        )
                    }
                }
                resolved += BatchTarget(pkg, label)
                apkPaths += buildList {
                    add(app.sourceDir)
                    app.splitSourceDirs?.forEach { add(it) }
                }
            }

            val totalApkBytes = measureApkBytes(apkPaths.filter { it.isNotBlank() }.distinct())
            if (totalApkBytes == 0L && apkPaths.isNotEmpty()) {
                issues += PreflightIssue(
                    GLOBAL_ISSUE_TARGET,
                    "无法测量目标 APK 体积，磁盘空间检查已跳过",
                    blocking = false,
                )
            } else if (totalApkBytes > 0) {
                val available = availableBytes()
                val required = (totalApkBytes.toDouble() * DISK_HEADROOM).toLong()
                if (available == null) {
                    issues += PreflightIssue(GLOBAL_ISSUE_TARGET, "无法读取 /data 剩余空间", blocking = true)
                } else if (available < required) {
                    issues += PreflightIssue(
                        GLOBAL_ISSUE_TARGET,
                        "磁盘剩余 ${Formatters.bytes(available)}，低于所需 ${Formatters.bytes(required)}" +
                            "（目标 APK 总量 × 1.2）",
                        blocking = true,
                    )
                }
            }

            XgResult.Ok(
                BatchPreflight(
                    targets = resolved,
                    issues = issues,
                    estimatedMs = estimateMs(action) * resolved.size + ROOT_CHECK_MS,
                    canRun = resolved.isNotEmpty() && issues.none { it.blocking },
                ),
            )
        }

    
    fun runBatch(
        targets: List<String>,
        action: BatchAction,
    ): Flow<BatchProgress> = flow {
        _lastReceipt.value = null
        val requested = targets.map { it.trim() }.filter { it.isNotEmpty() }
        val preflightResult = preflight(requested, action)
        if (preflightResult is XgResult.Err) {
            val results = requested.distinct().map {
                BatchItemResult(it, STATUS_SKIPPED, preflightResult.message)
            }
            val receipt = recordReceipt(action, results)
            if (receipt != null) {
                _lastReceipt.value = receipt
                recordBatchAudit(receipt)
                emit(BatchProgress(action, results.size, results.size, receiptSummary(receipt), results))
            } else {
                emit(
                    BatchProgress(
                        action,
                        results.size,
                        results.size,
                        "预检未通过：${preflightResult.message}；回执写入失败",
                        results,
                    ),
                )
            }
            return@flow
        }
        val preflight = preflightResult.getOrNull()
        if (preflight == null) {
            val results = requested.distinct().map { BatchItemResult(it, STATUS_SKIPPED, "预检结果为空") }
            val receipt = recordReceipt(action, results)
            if (receipt != null) {
                _lastReceipt.value = receipt
                recordBatchAudit(receipt)
                emit(BatchProgress(action, results.size, results.size, receiptSummary(receipt), results))
            } else {
                emit(BatchProgress(action, results.size, results.size, "预检结果为空；回执写入失败", results))
            }
            return@flow
        }
        if (!preflight.canRun) {
            val reason = firstBlockingReason(preflight)
            val results = requested.distinct().map { pkg ->
                BatchItemResult(pkg, STATUS_SKIPPED, reasonFor(pkg, preflight))
            }
            val receipt = recordReceipt(action, results)
            if (receipt != null) {
                _lastReceipt.value = receipt
                recordBatchAudit(receipt)
                emit(BatchProgress(action, results.size, results.size, receiptSummary(receipt), results))
            } else {
                emit(BatchProgress(action, results.size, results.size, "预检未通过：$reason；回执写入失败", results))
            }
            return@flow
        }

        val planned = preflight.targets
        val total = planned.size
        val snapshotIds = mutableMapOf<String, Long>()
        val frozen = if (action == BatchAction.FREEZE || action == BatchAction.UNFREEZE) {
            frozenPackages()?.toMutableSet()
        } else {
            null
        }
        val results = mutableListOf<BatchItemResult>()
        var recorded = false
        try {
            for ((index, target) in planned.withIndex()) {
                val pkg = target.packageName
                val skipReason = when (action) {
                    BatchAction.FREEZE -> if (frozen?.contains(pkg) == true) "已处于冻结状态" else null
                    BatchAction.UNFREEZE -> if (frozen != null && !frozen.contains(pkg)) "当前未冻结" else null
                    else -> null
                }
                val item = when {
                    rootStatus.state.value !is RootState.Granted -> {
                        recordSkippedItem(action, pkg, "Root 权限已失效")
                        BatchItemResult(pkg, STATUS_SKIPPED, "Root 权限已失效，未执行")
                    }

                    appRepository.isProtected(pkg) -> {
                        recordSkippedItem(action, pkg, "受保护应用")
                        BatchItemResult(pkg, STATUS_SKIPPED, "受保护应用，已跳过")
                    }

                    skipReason != null -> {
                        recordSkippedItem(action, pkg, skipReason)
                        BatchItemResult(pkg, STATUS_SKIPPED, skipReason)
                    }

                    action == BatchAction.DISABLE_AUTOSTART && bootReceivers.components(pkg).isEmpty() -> {
                        recordSkippedItem(action, pkg, "未发现开机接收器")
                        BatchItemResult(pkg, STATUS_SKIPPED, "未发现开机接收器")
                    }

                    else -> {
                        val outcome = withTimeoutOrNull(ITEM_TIMEOUT_MS) {
                            executeOne(action, pkg)
                        }
                        if (outcome == null) {
                            recordFailedItem(action, pkg, "单条操作超时（20 秒）")
                            BatchItemResult(pkg, STATUS_FAILED, "单条操作超时（20 秒）")
                        } else if (outcome.isOk) {
                            if (action == BatchAction.FREEZE) frozen?.add(pkg)
                            if (action == BatchAction.UNFREEZE) frozen?.remove(pkg)
                            val snapshotId = snapshotIdFor(action, pkg)
                            if (snapshotId == null) {
                                BatchItemResult(pkg, STATUS_OK, "操作成功，但未找到本批快照，撤销将拒绝执行")
                            } else {
                                snapshotIds[pkg] = snapshotId
                                BatchItemResult(pkg, STATUS_OK, null)
                            }
                        } else {
                            BatchItemResult(pkg, STATUS_FAILED, failureText(outcome as XgResult.Err))
                        }
                    }
                }
                results += item
                emit(BatchProgress(action, index + 1, total, pkg, results.toList()))
            }
            val receipt = withContext(NonCancellable) { recordReceipt(action, results) }
            recorded = true
            if (receipt != null) {
                rememberBatchSnapshots(receipt.id, snapshotIds)
                _lastReceipt.value = receipt
                recordBatchAudit(receipt)
                emit(BatchProgress(action, total, total, receiptSummary(receipt), results.toList()))
            } else {
                emit(BatchProgress(action, total, total, "批量完成，但回执写入失败", results.toList()))
            }
        } finally {
            if (!recorded) {
                runCatching {
                    withContext(NonCancellable) {
                        val resultCopy = results.toList()
                        recordReceipt(action, resultCopy)?.let { receipt ->
                            rememberBatchSnapshots(receipt.id, snapshotIds)
                            _lastReceipt.value = receipt
                            recordBatchAudit(receipt)
                        }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    
    suspend fun undoBatch(receipt: BatchReceipt): XgResult<Unit> = withContext(Dispatchers.IO) {
        val okItems = receipt.items.filter { it.status == STATUS_OK }
        if (okItems.isEmpty()) {
            recordUndoAudit(receipt, restored = 0, alreadyUndone = 0, failures = emptyList())
            return@withContext XgResult.Ok(Unit)
        }
        rootStatus.state.value.rootError()?.let { error ->
            recordUndoAudit(receipt, restored = 0, alreadyUndone = 0, failures = listOf(error.message))
            return@withContext error
        }
        val batchSnapshots = synchronized(receiptCacheLock) { receiptSnapshots[receipt.id] }
        if (batchSnapshots == null) {
            val error = XgResult.Err(
                ErrorCode.ERR_BACKUP_FAILED,
                "缺少本批快照索引，拒绝按最新快照撤销",
                "回执 #${receipt.id} 没有本进程记录的快照映射；未执行任何撤销命令",
            )
            recordUndoAudit(receipt, restored = 0, alreadyUndone = 0, failures = listOf(error.message))
            return@withContext error
        }
        val kind = snapshotKind(receipt.action)
        val failures = mutableListOf<String>()
        var restored = 0
        var alreadyUndone = 0
        for (item in okItems.asReversed()) {
            val pkg = item.packageName
            if (!AppShellText.isValidPackage(pkg)) {
                failures += "$pkg：非法包名"
                continue
            }
            if (appRepository.isProtected(pkg)) {
                failures += "$pkg：受保护应用"
                continue
            }
            val expectedSnapshotId = batchSnapshots[pkg]
            if (expectedSnapshotId == null) {
                failures += "$pkg：本批快照索引缺失"
                continue
            }
            if (synchronized(receiptCacheLock) { expectedSnapshotId in undoneSnapshotIds }) {
                alreadyUndone++
                continue
            }
            val snapshot = try {
                snapshots.get(expectedSnapshotId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                XgLog.w(TAG, "读取本批快照失败：$pkg（${t.message}）")
                null
            }
            if (snapshot == null) {
                failures += "$pkg：本批快照不存在"
                continue
            }
            if (snapshot.kind != kind || snapshot.target != pkg) {
                failures += "$pkg：本批快照类型/目标不匹配"
                continue
            }
            if (snapshot.expiresAt < System.currentTimeMillis()) {
                failures += "$pkg：本批快照已过期"
                continue
            }
            val latest = try {
                snapshots.latest(kind, pkg)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                XgLog.w(TAG, "读取最新快照失败：$pkg（${t.message}）")
                null
            }
            if (latest?.id != expectedSnapshotId) {
                failures += "$pkg：该应用在本批之后又有新快照，拒绝撤销"
                continue
            }
            val result = withTimeoutOrNull(UNDO_ITEM_TIMEOUT_MS) {
                when (receipt.action) {
                    BatchAction.FREEZE -> appRepository.unfreeze(pkg)
                    BatchAction.UNFREEZE -> appRepository.freeze(pkg)
                    BatchAction.UNINSTALL -> appRepository.undoUninstall(pkg)
                    BatchAction.DISABLE_AUTOSTART -> bootReceivers.restoreLatest(pkg)
                    BatchAction.CHANGE_ANDROID_ID -> androidId.restoreLatest(pkg)
                }
            }
            if (result == null) {
                failures += "$pkg：撤销超时"
            } else if (result.isOk) {
                restored++
                synchronized(receiptCacheLock) { undoneSnapshotIds += expectedSnapshotId }
            } else {
                failures += "$pkg：${failureText(result as XgResult.Err)}"
            }
        }
        recordUndoAudit(receipt, restored, alreadyUndone, failures)
        if (failures.isEmpty()) {
            XgResult.Ok(Unit)
        } else {
            XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "批量撤销完成 $restored/${okItems.size}，部分失败",
                failures.joinToString("\n").take(MAX_DETAIL_CHARS),
            )
        }
    }

    
    suspend fun setGlobalAndroidId(id16: String): XgResult<GlobalIdOutcome> = androidId.setGlobal(id16)

    suspend fun ssaidEntryCount(): XgResult<Int> = androidId.ssaidEntryCount()

    suspend fun currentSsaid(pkg: String): XgResult<String?> = androidId.currentSsaid(pkg)

    suspend fun resetAppSsaid(pkg: String, clearData: Boolean): XgResult<Unit> =
        androidId.resetAppSsaid(pkg, clearData)

    
    suspend fun generateAndroidId(): String = androidId.generate()

    
    suspend fun abiStatus(pkg: String): XgResult<AbiStatus> = abi.status(pkg)

    
    suspend fun switchAbi(pkg: String, targetAbi: String): XgResult<Unit> =
        abi.switch(pkg, targetAbi)

    
    @Suppress("UNUSED_PARAMETER")
    suspend fun switchAbi(pkg: String, targetAbi: String, apkPath: String?): XgResult<Unit> =
        switchAbi(pkg, targetAbi)

    
    suspend fun clearAppCache(pkg: String): XgResult<Unit> = appData.clearCache(pkg)

    
    suspend fun clearAppData(pkg: String): XgResult<Unit> = appData.clearData(pkg)

    suspend fun listModules(): XgResult<List<ModuleInfo>> = modules.list()

    suspend fun setModuleEnabled(moduleId: String, enabled: Boolean): XgResult<Unit> =
        modules.setEnabled(moduleId, enabled)

    suspend fun removeModule(moduleId: String): XgResult<Unit> = modules.remove(moduleId)

    suspend fun disableBootReceiver(pkg: String, component: String): XgResult<Unit> =
        bootReceivers.setEnabled(pkg, component, enabled = false)

    suspend fun enableBootReceiver(pkg: String, component: String): XgResult<Unit> =
        bootReceivers.setEnabled(pkg, component, enabled = true)

    private suspend fun executeOne(
        action: BatchAction,
        pkg: String,
    ): XgResult<Unit> = when (action) {
        BatchAction.FREEZE -> appRepository.freeze(pkg)
        BatchAction.UNFREEZE -> appRepository.unfreeze(pkg)
        BatchAction.UNINSTALL -> appRepository.uninstall(pkg).map { Unit }
        BatchAction.DISABLE_AUTOSTART -> {
            val components = bootReceivers.components(pkg)
            if (components.isEmpty()) {
                XgResult.Err(ErrorCode.ERR_CMD_FAILED, "未发现开机接收器")
            } else {
                bootReceivers.disableAll(pkg, components)
            }
        }

        BatchAction.CHANGE_ANDROID_ID -> androidId.resetAppSsaid(pkg, clearData = false)
    }

    private suspend fun recordReceipt(
        action: BatchAction,
        results: List<BatchItemResult>,
    ): BatchReceipt? {
        val total = results.size
        val ok = results.count { it.status == STATUS_OK }
        val failed = results.count { it.status == STATUS_FAILED }
        val skipped = results.count { it.status == STATUS_SKIPPED }
        val body = JSONArray().apply {
            results.forEach { item ->
                put(
                    JSONObject()
                        .put("packageName", item.packageName)
                        .put("status", item.status)
                        .put("message", item.message ?: JSONObject.NULL),
                )
            }
        }.toString()
        return try {
            val id = receipts.record(
                action = "batch_${action.name.lowercase(Locale.US)}",
                total = total,
                ok = ok,
                failed = failed,
                skipped = skipped,
                body = body,
            )
            BatchReceipt(id, action, total, ok, failed, skipped, results.toList())
        } catch (t: Throwable) {
            XgLog.e(TAG, "批量回执写入失败", t)
            null
        }
    }

    private fun rememberBatchSnapshots(receiptId: Long, snapshotIds: Map<String, Long>) {
        synchronized(receiptCacheLock) {
            if (snapshotIds.isEmpty()) {
                receiptSnapshots.remove(receiptId)
                return
            }
            receiptSnapshots[receiptId] = snapshotIds.toMap()
            while (receiptSnapshots.size > MAX_REMEMBERED_RECEIPTS) {
                val eldest = receiptSnapshots.keys.firstOrNull() ?: break
                val removed = receiptSnapshots.remove(eldest).orEmpty()
                removed.values.forEach { undoneSnapshotIds.remove(it) }
            }
        }
    }

    private suspend fun snapshotIdFor(action: BatchAction, pkg: String): Long? =
        try {
            snapshots.latest(snapshotKind(action), pkg)?.id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            XgLog.w(TAG, "读取批量快照索引失败：$pkg（${t.message}）")
            null
        }

    private fun snapshotKind(action: BatchAction): String = when (action) {
        BatchAction.FREEZE, BatchAction.UNFREEZE -> KIND_APP_STATE
        BatchAction.UNINSTALL -> KIND_APP_BACKUP
        BatchAction.DISABLE_AUTOSTART -> KIND_BOOT_RECEIVERS
        BatchAction.CHANGE_ANDROID_ID -> KIND_SSAID
    }

    private suspend fun recordUndoAudit(
        receipt: BatchReceipt,
        restored: Int,
        alreadyUndone: Int,
        failures: List<String>,
    ) {
        val total = receipt.items.count { it.status == STATUS_OK }
        val result = if (failures.isEmpty() && restored + alreadyUndone == total) {
            AuditResult.OK
        } else {
            AuditResult.FAILED
        }
        val message = buildString {
            append("回执 #").append(receipt.id)
            append("（").append(receipt.action.name).append("）：撤销 ")
            append(restored).append('/').append(total)
            append("，已撤销 ").append(alreadyUndone)
            append("，失败 ").append(failures.size)
            if (failures.isNotEmpty()) {
                append("；").append(failures.take(5).joinToString(" | ").take(MAX_UNDO_AUDIT_FAILURES))
            }
        }
        audit.record(
            category = AuditCategory.APP,
            action = ACTION_UNDO_BATCH,
            target = GLOBAL_ISSUE_TARGET,
            commands = "",
            result = result,
            message = message,
            snapshotId = null,
        )
    }

    private suspend fun recordBatchAudit(receipt: BatchReceipt) {
        audit.record(
            category = AuditCategory.APP,
            action = "batch_${receipt.action.name.lowercase(Locale.US)}",
            target = GLOBAL_ISSUE_TARGET,
            commands = "",
            result = if (receipt.failed > 0) AuditResult.FAILED else AuditResult.OK,
            message = "回执 #${receipt.id}：成功 ${receipt.ok} / 失败 ${receipt.failed} / 跳过 ${receipt.skipped}",
            snapshotId = null,
        )
    }

    private suspend fun recordSkippedItem(action: BatchAction, pkg: String, reason: String) {
        audit.record(
            category = AuditCategory.APP,
            action = "batch_${action.name.lowercase(Locale.US)}",
            target = pkg,
            commands = "",
            result = AuditResult.FAILED,
            message = "跳过：$reason",
            snapshotId = null,
        )
    }

    private suspend fun recordFailedItem(action: BatchAction, pkg: String, reason: String) {
        audit.record(
            category = AuditCategory.APP,
            action = "batch_${action.name.lowercase(Locale.US)}",
            target = pkg,
            commands = "",
            result = AuditResult.FAILED,
            message = "失败：$reason",
            snapshotId = null,
        )
    }

    private fun receiptSummary(receipt: BatchReceipt): String =
        "回执 #${receipt.id} 已保存（成功 ${receipt.ok} / 失败 ${receipt.failed} / 跳过 ${receipt.skipped}）"

    private fun firstBlockingReason(preflight: BatchPreflight): String {
        val issue = preflight.issues.firstOrNull { it.blocking }
        return when {
            issue == null -> "没有可执行的目标"
            issue.packageName == GLOBAL_ISSUE_TARGET -> issue.reason
            else -> "${issue.packageName}：${issue.reason}"
        }
    }

    private fun reasonFor(pkg: String, preflight: BatchPreflight): String =
        preflight.issues.firstOrNull { it.packageName == pkg }?.reason ?: firstBlockingReason(preflight)

    private fun failureText(error: XgResult.Err): String {
        val detail = error.detail?.trim().orEmpty()
        return if (detail.isBlank()) error.message else "${error.message}：${detail.take(MAX_ITEM_DETAIL_CHARS)}"
    }

    private suspend fun frozenPackages(): Set<String>? {
        if (rootStatus.state.value !is RootState.Granted) return null
        val result = shell.exec("pm list packages -d --user 0", root = true, timeoutMs = READ_TIMEOUT_MS)
        if (!result.ok) return null
        return result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(PACKAGE_PREFIX) }
            .map { it.removePrefix(PACKAGE_PREFIX) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private suspend fun measureApkBytes(paths: List<String>): Long {
        if (paths.isEmpty()) return 0
        if (rootStatus.state.value !is RootState.Granted) {
            return paths.sumOf { path -> runCatching { File(path).length() }.getOrDefault(0L) }
        }
        val result = shell.exec(
            "stat -c %s " + paths.joinToString(" ") { AppShellText.quote(it) },
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        )
        val sizes = result.stdout.lineSequence()
            .map { it.trim() }
            .mapNotNull { it.toLongOrNull() }
            .toList()
        if (result.ok && sizes.size == paths.size) return sizes.sum()
        return paths.sumOf { path -> runCatching { File(path).length() }.getOrDefault(0L) }
    }

    private fun availableBytes(): Long? = runCatching {
        StatFs(Environment.getDataDirectory().absolutePath).availableBytes
    }.getOrNull()

    private fun estimateMs(action: BatchAction): Long = when (action) {
        BatchAction.FREEZE -> 1_800L
        BatchAction.UNFREEZE -> 1_800L
        BatchAction.UNINSTALL -> 8_000L
        BatchAction.DISABLE_AUTOSTART -> 3_000L
        BatchAction.CHANGE_ANDROID_ID -> 4_500L
    }

    companion object {
        const val GLOBAL_ISSUE_TARGET = "-"

        private const val TAG = "AppActions"
        private const val PACKAGE_PREFIX = "package:"
        private const val STATUS_OK = "ok"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_SKIPPED = "skipped"
        private const val ACTION_UNDO_BATCH = "undo_batch"
        private const val DISK_HEADROOM = 1.2
        private const val ROOT_CHECK_MS = 1_000L
        private const val ITEM_TIMEOUT_MS = 20_000L
        private const val UNDO_ITEM_TIMEOUT_MS = 90_000L
        private const val READ_TIMEOUT_MS = 15_000L
        private const val MAX_DETAIL_CHARS = 2_000
        private const val MAX_ITEM_DETAIL_CHARS = 800
        private const val MAX_UNDO_AUDIT_FAILURES = 600
        private const val MAX_REMEMBERED_RECEIPTS = 32

        private const val KIND_APP_STATE = "app_state"
        private const val KIND_APP_BACKUP = "app_backup"
        private const val KIND_BOOT_RECEIVERS = "app_boot_receivers"
        private const val KIND_SSAID = "app_ssaid"
    }
}
