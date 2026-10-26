package com.xg.toolbox.feature.apps.domain

import android.util.Base64
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppRepository
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.rootError
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class GlobalIdOutcome(
    val rewrittenEntries: Int,
    val totalEntries: Int,
    val requiresReboot: Boolean,
)

@Singleton
internal class AndroidIdController @Inject constructor(
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val appRepository: AppRepository,
    private val audit: AuditRepository,
    private val snapshots: SnapshotRepository,
) {

    private val editMutex = Mutex()
    private val random = SecureRandom()

    
    suspend fun setGlobal(id16: String): XgResult<GlobalIdOutcome> = withContext(Dispatchers.IO) {
        editMutex.withLock {
            if (!AppShellText.ANDROID_ID.matches(id16)) return@withLock invalidId()
            rootStatus.state.value.rootError()?.let { error ->
                recordAudit(ACTION_GLOBAL, GLOBAL_TARGET, "", AuditResult.FAILED, error.message, null)
                return@withLock error
            }

            val beforeRead = shell.exec(
                "settings get secure android_id",
                root = true,
                timeoutMs = READ_TIMEOUT_MS,
            )
            if (!beforeRead.ok) {
                val error = AppShellText.failure("读取当前 Android ID 失败", beforeRead)
                recordAudit(
                    ACTION_GLOBAL,
                    GLOBAL_TARGET,
                    "settings get secure android_id",
                    AuditResult.FAILED,
                    error.message,
                    null,
                )
                return@withLock error
            }
            val beforeValue = beforeRead.stdout.trim()

            val stamp = stamp()
            val backupPath = "$BACKUP_ROOT/settings_ssaid-$stamp-global.xml"
            val backup = createBackup(backupPath)
            if (backup is XgResult.Err) {
                recordAudit(
                    ACTION_GLOBAL,
                    GLOBAL_TARGET,
                    "<备份 settings_ssaid.xml>",
                    AuditResult.FAILED,
                    backup.message,
                    null,
                )
                return@withLock backup
            }
            var commands = "<备份 settings_ssaid.xml>"

            val workDir = "$WORK_ROOT/$stamp-global"
            val sourceRead = readSource(workDir, requireEntries = false)
            if (sourceRead is XgResult.Err) {
                cleanup(workDir)
                recordAudit(ACTION_GLOBAL, GLOBAL_TARGET, commands, AuditResult.FAILED, sourceRead.message, null)
                return@withLock sourceRead
            }
            val source = sourceRead.getOrNull()
            if (source == null) {
                cleanup(workDir)
                val error = XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "SSAID 配置读取结果为空")
                recordAudit(ACTION_GLOBAL, GLOBAL_TARGET, commands, AuditResult.FAILED, error.message, null)
                return@withLock error
            }

            val rewrite = SsaidXml.rewriteAll(source.xml, id16)
            if (rewrite.totalEntries == 0 && SsaidXml.countEntries(source.xml) > 1) {
                cleanup(workDir)
                val error = XgResult.Err(
                    ErrorCode.ERR_PARSE_FAILED,
                    "SSAID 配置包含 setting 节点但未识别到 SSAID 条目，未写入",
                )
                recordAudit(ACTION_GLOBAL, GLOBAL_TARGET, commands, AuditResult.FAILED, error.message, null)
                return@withLock error
            }
            if (rewrite.unresolvedPackages.isNotEmpty()) {
                cleanup(workDir)
                val error = XgResult.Err(
                    ErrorCode.ERR_PARSE_FAILED,
                    "有 ${rewrite.unresolvedPackages.size} 条 SSAID 记录无法识别，未写入",
                    rewrite.unresolvedPackages.take(MAX_LISTED_PACKAGES).joinToString(" | "),
                )
                recordAudit(ACTION_GLOBAL, GLOBAL_TARGET, commands, AuditResult.FAILED, error.message, null)
                return@withLock error
            }

            val snapshotId = snapshots.create(
                kind = KIND_GLOBAL_ID,
                target = GLOBAL_TARGET,
                payload = JSONObject()
                    .put("backupPath", backupPath)
                    .put("sourcePath", SSAID_SOURCE)
                    .put("format", source.format.name.lowercase(Locale.US))
                    .put("before", beforeValue)
                    .put("totalEntries", rewrite.totalEntries)
                    .put("rewrittenEntries", rewrite.rewrittenEntries)
                    .toString(),
                ttlMs = SNAPSHOT_TTL_MS,
            )

            if (rewrite.totalEntries > 0) {
                commands += "\n<原子替换 settings_ssaid.xml>"
                val write = writeReplacement(source.format, rewrite.xml, workDir, stamp)
                if (write is XgResult.Err) {
                    return@withLock failGlobalAndRollback(
                        commands = commands,
                        snapshotId = snapshotId,
                        backupPath = backupPath,
                        workDir = workDir,
                        beforeId = beforeValue,
                        restoreGlobal = false,
                        error = write,
                    )
                }
            }

            commands += "\nsettings put secure android_id <16hex>\nsettings get secure android_id"
            val put = shell.exec(
                "settings put secure android_id ${AppShellText.quote(id16)}",
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
            val readback = if (put.ok) {
                shell.exec("settings get secure android_id", root = true, timeoutMs = READ_TIMEOUT_MS)
            } else {
                null
            }
            val readValue = readback?.stdout?.trim().orEmpty()
            val consistent = readback?.ok == true &&
                readValue.length == 16 &&
                readValue.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } &&
                readValue.equals(id16, ignoreCase = true)
            val globalError = when {
                !put.ok -> AppShellText.failure("写入 Android ID 失败", put)
                readback == null -> XgResult.Err(ErrorCode.ERR_CMD_FAILED, "未执行 Android ID 回读")
                !readback.ok -> AppShellText.failure("回读 Android ID 失败", readback)
                !consistent -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "Android ID 回读不一致",
                    "回读值长度 ${readValue.length}，与写入值不一致",
                )
                else -> null
            }
            if (globalError != null) {
                return@withLock failGlobalAndRollback(
                    commands = commands,
                    snapshotId = snapshotId,
                    backupPath = backupPath,
                    workDir = workDir,
                    beforeId = beforeValue,
                    restoreGlobal = true,
                    error = globalError,
                )
            }

            val verification = verifyAllSsaid(source.format, id16, rewrite.totalEntries, workDir)
            if (verification is XgResult.Err) {
                return@withLock failGlobalAndRollback(
                    commands = commands,
                    snapshotId = snapshotId,
                    backupPath = backupPath,
                    workDir = workDir,
                    beforeId = beforeValue,
                    restoreGlobal = true,
                    error = verification,
                )
            }

            cleanup(workDir)
            val message = buildString {
                append("备份：").append(backupPath)
                append("；SSAID 条目 ").append(rewrite.totalEntries).append(" 条已确认，")
                append(rewrite.rewrittenEntries).append(" 条发生变更")
                append("；settings_ssaid.xml 与 settings get 双回读一致")
                append("；system_server 缓存需重启后生效")
            }
            recordAudit(ACTION_GLOBAL, GLOBAL_TARGET, commands, AuditResult.OK, message, snapshotId)
            XgResult.Ok(
                GlobalIdOutcome(
                    rewrittenEntries = rewrite.rewrittenEntries,
                    totalEntries = rewrite.totalEntries,
                    requiresReboot = true,
                ),
            )
        }
    }

    
    suspend fun ssaidEntryCount(): XgResult<Int> = withContext(Dispatchers.IO) {
        editMutex.withLock {
            rootStatus.state.value.rootError()?.let { return@withLock it }
            val workDir = "$WORK_ROOT/${stamp()}-count"
            val sourceRead = readSource(workDir, requireWriteTools = false, requireEntries = false)
            if (sourceRead is XgResult.Err) {
                cleanup(workDir)
                return@withLock sourceRead
            }
            val source = sourceRead.getOrNull()
            if (source == null) {
                cleanup(workDir)
                return@withLock XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "SSAID 配置读取结果为空")
            }
            val count = SsaidXml.ssaidEntryCount(source.xml)
            cleanup(workDir)
            XgResult.Ok(count)
        }
    }

    
    suspend fun currentSsaid(pkg: String): XgResult<String?> = withContext(Dispatchers.IO) {
        editMutex.withLock {
            if (!AppShellText.isValidPackage(pkg)) {
                return@withLock XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
            }
            rootStatus.state.value.rootError()?.let { return@withLock it }
            val workDir = "$WORK_ROOT/${stamp()}-current"
            val sourceRead = readSource(workDir, requireWriteTools = false, requireEntries = false)
            if (sourceRead is XgResult.Err) {
                cleanup(workDir)
                return@withLock sourceRead
            }
            val source = sourceRead.getOrNull()
            if (source == null) {
                cleanup(workDir)
                return@withLock XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "SSAID 配置读取结果为空")
            }
            val lookup = SsaidXml.currentSsaid(source.xml, pkg)
            cleanup(workDir)
            val value = lookup.ssaid
            when {
                !lookup.found -> XgResult.Ok<String?>(null)
                value != null -> XgResult.Ok<String?>(value)
                else -> XgResult.Err(
                    ErrorCode.ERR_PARSE_FAILED,
                    lookup.error ?: "无法解析该包 SSAID",
                )
            }
        }
    }

    
    suspend fun resetAppSsaid(pkg: String, clearData: Boolean): XgResult<Unit> =
        withContext(Dispatchers.IO) {
            editMutex.withLock {
                if (!AppShellText.isValidPackage(pkg)) {
                    return@withLock XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
                }
                if (appRepository.isProtected(pkg)) {
                    val error = XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "受保护应用禁止修改 SSAID：$pkg")
                    recordAudit(ACTION_SSAID, pkg, "", AuditResult.FAILED, error.message, null)
                    return@withLock error
                }
                rootStatus.state.value.rootError()?.let { error ->
                    recordAudit(ACTION_SSAID, pkg, "", AuditResult.FAILED, error.message, null)
                    return@withLock error
                }

                val stamp = stamp()
                val workDir = "$WORK_ROOT/$stamp-${safeName(pkg)}"
                val sourceRead = readSource(workDir)
                if (sourceRead is XgResult.Err) {
                    recordAudit(ACTION_SSAID, pkg, "", AuditResult.FAILED, sourceRead.message, null)
                    return@withLock sourceRead
                }
                val source = sourceRead.getOrNull()
                    ?: return@withLock XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "SSAID 配置读取结果为空")
                val newXml = SsaidXml.removePackageEntry(source.xml, pkg)
                if (newXml == null) {
                    cleanup(workDir)
                    val error = XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "未找到该应用的 SSAID 记录，未修改文件",
                        "文件 ${SSAID_SOURCE} 中没有 package=$pkg 或 name=id/$pkg 的 setting 节点",
                    )
                    recordAudit(ACTION_SSAID, pkg, "", AuditResult.FAILED, error.message, null)
                    return@withLock error
                }

                val backupPath = "$BACKUP_ROOT/settings_ssaid-$stamp-${safeName(pkg)}.xml"
                val backup = createBackup(backupPath)
                if (backup is XgResult.Err) {
                    cleanup(workDir)
                    recordAudit(ACTION_SSAID, pkg, "", AuditResult.FAILED, backup.message, null)
                    return@withLock backup
                }
                val snapshotId = snapshots.create(
                    kind = KIND_SSAID,
                    target = pkg,
                    payload = JSONObject()
                        .put("backupPath", backupPath)
                        .put("sourcePath", SSAID_SOURCE)
                        .put("format", source.format.name.lowercase(Locale.US))
                        .put("clearData", clearData)
                        .toString(),
                    ttlMs = SNAPSHOT_TTL_MS,
                )
                var commands = "am force-stop $pkg"

                val forceStop = shell.exec(
                    "am force-stop ${AppShellText.quote(pkg)}",
                    root = true,
                    timeoutMs = COMMAND_TIMEOUT_MS,
                )
                if (!forceStop.ok) {
                    val error = AppShellText.failure("停止应用失败，未修改 SSAID", forceStop)
                    cleanup(workDir)
                    recordAudit(ACTION_SSAID, pkg, commands, AuditResult.FAILED, error.message, snapshotId)
                    return@withLock error
                }

                commands += "\n<原子替换 $SSAID_SOURCE>"
                val write = writeReplacement(source.format, newXml, workDir, stamp)
                if (write is XgResult.Err) {
                    cleanup(workDir)
                    recordAudit(ACTION_SSAID, pkg, commands, AuditResult.FAILED, write.message, snapshotId)
                    return@withLock write
                }

                val verification = verifyRemoved(source.format, pkg, workDir)
                if (verification is XgResult.Err) {
                    return@withLock failAndRollback(
                        pkg = pkg,
                        commands = commands,
                        snapshotId = snapshotId,
                        backupPath = backupPath,
                        workDir = workDir,
                        error = verification,
                    )
                }

                if (clearData) {
                    commands += "\npm clear $pkg"
                    val clear = shell.exec(
                        "pm clear ${AppShellText.quote(pkg)}",
                        root = true,
                        timeoutMs = CLEAR_TIMEOUT_MS,
                    )
                    val cleared = clear.ok && clear.stdout.contains("Success", ignoreCase = true)
                    if (!cleared) {
                        val error = if (clear.ok) {
                            XgResult.Err(
                                ErrorCode.ERR_CMD_FAILED,
                                "pm clear 退出码为 0，但未返回 Success，无法确认数据已清除",
                                AppShellText.detail(clear),
                            )
                        } else {
                            AppShellText.failure("清除应用数据失败", clear)
                        }
                        return@withLock failAndRollback(
                            pkg = pkg,
                            commands = commands,
                            snapshotId = snapshotId,
                            backupPath = backupPath,
                            workDir = workDir,
                            error = error,
                            dataNote = "pm clear 未确认成功（退出码 ${clear.code}），应用数据状态未确认",
                        )
                    }
                    val afterClear = verifyRemoved(source.format, pkg, workDir)
                    if (afterClear is XgResult.Err) {
                        return@withLock failAndRollback(
                            pkg = pkg,
                            commands = commands,
                            snapshotId = snapshotId,
                            backupPath = backupPath,
                            workDir = workDir,
                            error = afterClear,
                            dataCleared = true,
                            dataNote = "应用数据已清除，无法从 SSAID 备份恢复应用数据",
                        )
                    }
                }

                cleanup(workDir)
                val resultMessage = buildString {
                    append("备份：").append(backupPath)
                    append("；settings_ssaid.xml 回读确认该包节点不存在")
                    append("；实际 SSAID 由系统重新生成")
                    if (clearData) {
                        append("；pm clear 成功，应用数据已清除")
                    } else {
                        append("；未清除应用数据，应用可能继续使用旧缓存，需重启后生效")
                    }
                }
                recordAudit(
                    ACTION_SSAID,
                    pkg,
                    commands,
                    AuditResult.OK,
                    resultMessage,
                    snapshotId,
                )
                XgResult.Ok(Unit)
            }
        }

    fun generate(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        val hex = "0123456789abcdef"
        return buildString(16) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xFF
                append(hex[value ushr 4])
                append(hex[value and 0x0F])
            }
        }
    }

    suspend fun restoreLatest(pkg: String): XgResult<Unit> = withContext(Dispatchers.IO) {
        editMutex.withLock {
            if (!AppShellText.isValidPackage(pkg)) {
                return@withLock XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
            }
            rootStatus.state.value.rootError()?.let { return@withLock it }
            val snapshot = snapshots.latest(KIND_SSAID, pkg)
                ?: return@withLock XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "未找到该应用的 SSAID 备份快照")
            if (snapshot.expiresAt < System.currentTimeMillis()) {
                return@withLock XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "SSAID 备份快照已过期")
            }
            val payload = runCatching { JSONObject(snapshot.payload) }.getOrNull()
                ?: return@withLock XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "SSAID 备份快照内容损坏")
            val backupPath = payload.optString("backupPath")
            val sourcePath = payload.optString("sourcePath", SSAID_SOURCE)
            if (!isSafeBackupPath(backupPath) || sourcePath != SSAID_SOURCE) {
                return@withLock XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "SSAID 备份路径非法，拒绝回滚")
            }

            val forceStop = shell.exec(
                "am force-stop ${AppShellText.quote(pkg)}",
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
            val restore = restoreBackup(backupPath)
            if (restore.isOk) {
                val forceStopNote = if (forceStop.ok) {
                    ""
                } else {
                    "；am force-stop 退出码 ${forceStop.code}，应用可能仍持有旧缓存"
                }
                recordAudit(
                    ACTION_UNDO_SSAID,
                    pkg,
                    "am force-stop $pkg\n<恢复 settings_ssaid.xml 备份>",
                    AuditResult.OK,
                    "已从 $backupPath 恢复并回读一致$forceStopNote",
                    snapshot.id,
                )
                XgResult.Ok(Unit)
            } else {
                val error = restore as XgResult.Err
                recordAudit(
                    ACTION_UNDO_SSAID,
                    pkg,
                    "<恢复 settings_ssaid.xml 备份>",
                    AuditResult.FAILED,
                    error.message,
                    snapshot.id,
                )
                error
            }
        }
    }

    private suspend fun readSource(
        workDir: String,
        requireWriteTools: Boolean = true,
        requireEntries: Boolean = true,
    ): XgResult<SourceDocument> {
        val exists = shell.exec(
            "test -f ${AppShellText.quote(SSAID_SOURCE)}",
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        )
        if (exists.code != 0) {
            return XgResult.Err(
                ErrorCode.ERR_BACKUP_FAILED,
                "SSAID 配置文件不存在，无法修改",
                "$SSAID_SOURCE（test 退出码 ${exists.code}）",
            )
        }

        val probe = shell.exec(
            "dd if=${AppShellText.quote(SSAID_SOURCE)} bs=1 count=4 2>/dev/null | od -An -tx1 | tr -d ' \\n'",
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        )
        val magic = probe.stdout.replace(" ", "").replace("\n", "").trim()
        if (magic.isEmpty()) {
            return XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "无法识别 SSAID 配置文件格式", AppShellText.detail(probe))
        }
        val format = if (magic.startsWith(ABX_MAGIC)) SsaidFormat.ABX else SsaidFormat.TEXT

        if (format == SsaidFormat.ABX) {
            val toolCheck = if (requireWriteTools) {
                "command -v abx2xml >/dev/null 2>&1 && command -v xml2abx >/dev/null 2>&1"
            } else {
                "command -v abx2xml >/dev/null 2>&1"
            }
            val tools = shell.exec(
                toolCheck,
                root = true,
                timeoutMs = READ_TIMEOUT_MS,
            )
            if (!tools.ok) {
                return XgResult.Err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    if (requireWriteTools) {
                        "当前 ROM 的 SSAID 文件为 ABX 格式，但缺少 abx2xml/xml2abx"
                    } else {
                        "当前 ROM 的 SSAID 文件为 ABX 格式，但缺少 abx2xml"
                    },
                )
            }
        }

        val mkdir = shell.exec("mkdir -p ${AppShellText.quote(workDir)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        if (!mkdir.ok) {
            cleanup(workDir)
            return XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "无法创建 SSAID 临时目录", AppShellText.detail(mkdir))
        }
        val textPath = "$workDir/ssaid.xml"
        val prepare = if (format == SsaidFormat.ABX) {
            shell.exec(
                "abx2xml ${AppShellText.quote(SSAID_SOURCE)} ${AppShellText.quote(textPath)}",
                root = true,
                timeoutMs = CONVERT_TIMEOUT_MS,
            )
        } else {
            shell.exec(
                "cp -a ${AppShellText.quote(SSAID_SOURCE)} ${AppShellText.quote(textPath)}",
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
        }
        if (!prepare.ok) {
            cleanup(workDir)
            return XgResult.Err(
                ErrorCode.ERR_PARSE_FAILED,
                if (format == SsaidFormat.ABX) "ABX 转 XML 失败" else "读取 SSAID 配置失败",
                AppShellText.detail(prepare),
            )
        }
        val read = shell.exec("cat ${AppShellText.quote(textPath)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        if (!read.ok || read.stdout.isBlank()) {
            cleanup(workDir)
            return XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "SSAID 配置内容为空或读取失败", AppShellText.detail(read))
        }
        if (!read.stdout.contains("<settings")) {
            cleanup(workDir)
            return XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "SSAID 配置缺少 settings 根节点")
        }
        if (requireEntries && SsaidXml.countEntries(read.stdout) == 0) {
            cleanup(workDir)
            return XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "SSAID 配置中未解析到 setting 节点")
        }
        return XgResult.Ok(SourceDocument(format, read.stdout))
    }

    private suspend fun createBackup(backupPath: String): XgResult<Unit> {
        val mkdir = shell.exec(
            "mkdir -p ${AppShellText.quote(BACKUP_ROOT)}",
            root = true,
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        if (!mkdir.ok) {
            return XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "无法创建 SSAID 备份目录", AppShellText.detail(mkdir))
        }
        if (!isLexicalWithin(backupPath, BACKUP_ROOT)) {
            return XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "备份路径非法，已中止")
        }
        val command = "cp -a ${AppShellText.quote(SSAID_SOURCE)} ${AppShellText.quote(backupPath)}" +
            " && test -f ${AppShellText.quote(backupPath)}" +
            " && cmp -s ${AppShellText.quote(SSAID_SOURCE)} ${AppShellText.quote(backupPath)}"
        val result = shell.exec(command, root = true, timeoutMs = 20_000)
        if (!result.ok) {
            shell.exec("rm -f ${AppShellText.quote(backupPath)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
            return XgResult.Err(
                ErrorCode.ERR_BACKUP_FAILED,
                "备份 SSAID 配置文件失败，已中止",
                AppShellText.detail(result),
            )
        }
        if (!isSafeBackupPath(backupPath)) {
            shell.exec("rm -f ${AppShellText.quote(backupPath)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
            return XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "备份文件规范化后越界，已删除")
        }
        return XgResult.Ok(Unit)
    }

    private suspend fun writeReplacement(
        format: SsaidFormat,
        xml: String,
        workDir: String,
        stamp: String,
    ): XgResult<Unit> {
        val textPath = "$workDir/new.xml"
        val encoded = Base64.encodeToString(xml.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val writeText = shell.exec(
            "printf '%s' ${AppShellText.quote(encoded)} | base64 -d > ${AppShellText.quote(textPath)}",
            root = true,
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        if (!writeText.ok) {
            return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "写入 SSAID 临时文本失败", AppShellText.detail(writeText))
        }

        val payloadPath = "$workDir/new.payload"
        val convert = if (format == SsaidFormat.ABX) {
            shell.exec(
                "xml2abx ${AppShellText.quote(textPath)} ${AppShellText.quote(payloadPath)}",
                root = true,
                timeoutMs = CONVERT_TIMEOUT_MS,
            )
        } else {
            shell.exec(
                "cp -a ${AppShellText.quote(textPath)} ${AppShellText.quote(payloadPath)}",
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
        }
        if (!convert.ok) {
            return XgResult.Err(
                ErrorCode.ERR_PARSE_FAILED,
                if (format == SsaidFormat.ABX) "XML 转 ABX 失败" else "复制 SSAID 临时文件失败",
                AppShellText.detail(convert),
            )
        }

        val tempPath = "$SSAID_DIR/.settings_ssaid.xml.xg.tmp.$stamp"
        val replace = shell.exec(
            "cp ${AppShellText.quote(payloadPath)} ${AppShellText.quote(tempPath)}" +
                " && chown system:system ${AppShellText.quote(tempPath)}" +
                " && chmod 0600 ${AppShellText.quote(tempPath)}" +
                " && (restorecon -F ${AppShellText.quote(tempPath)} 2>/dev/null || true)" +
                " && mv -f ${AppShellText.quote(tempPath)} ${AppShellText.quote(SSAID_SOURCE)}",
            root = true,
            timeoutMs = 20_000,
        )
        if (!replace.ok) {
            shell.exec("rm -f ${AppShellText.quote(tempPath)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "原子替换 SSAID 配置失败",
                AppShellText.detail(replace),
            )
        }
        return XgResult.Ok(Unit)
    }

    private suspend fun verifyRemoved(format: SsaidFormat, pkg: String, workDir: String): XgResult<Unit> {
        val readPath = if (format == SsaidFormat.ABX) {
            val readbackPath = "$workDir/readback.xml"
            shell.exec(
                "rm -f ${AppShellText.quote(readbackPath)}",
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
            val convert = shell.exec(
                "abx2xml ${AppShellText.quote(SSAID_SOURCE)} ${AppShellText.quote(readbackPath)}",
                root = true,
                timeoutMs = CONVERT_TIMEOUT_MS,
            )
            if (!convert.ok) {
                return XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "回读时 ABX 转 XML 失败", AppShellText.detail(convert))
            }
            readbackPath
        } else {
            SSAID_SOURCE
        }
        val read = shell.exec("cat ${AppShellText.quote(readPath)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        if (!read.ok || read.stdout.isBlank()) {
            return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "SSAID 回读失败", AppShellText.detail(read))
        }
        if (SsaidXml.hasPackageEntry(read.stdout, pkg)) {
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "SSAID 回读仍存在该应用记录，替换未生效",
            )
        }
        if (!read.stdout.contains("<settings")) {
            return XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "SSAID 回读缺少 settings 根节点，拒绝视为成功")
        }
        return XgResult.Ok(Unit)
    }

    private suspend fun verifyAllSsaid(
        format: SsaidFormat,
        id16: String,
        expectedEntries: Int,
        workDir: String,
    ): XgResult<Unit> {
        val readPath = "$workDir/readback-global.xml"
        shell.exec(
            "rm -f ${AppShellText.quote(readPath)}",
            root = true,
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        val prepare = if (format == SsaidFormat.ABX) {
            shell.exec(
                "abx2xml ${AppShellText.quote(SSAID_SOURCE)} ${AppShellText.quote(readPath)}",
                root = true,
                timeoutMs = CONVERT_TIMEOUT_MS,
            )
        } else {
            shell.exec(
                "cp -a ${AppShellText.quote(SSAID_SOURCE)} ${AppShellText.quote(readPath)}",
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
        }
        if (!prepare.ok) {
            return XgResult.Err(
                ErrorCode.ERR_PARSE_FAILED,
                if (format == SsaidFormat.ABX) "回读时 ABX 转 XML 失败" else "回读 SSAID 配置失败",
                AppShellText.detail(prepare),
            )
        }
        val read = shell.exec("cat ${AppShellText.quote(readPath)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        if (!read.ok || read.stdout.isBlank()) {
            return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "SSAID 回读失败", AppShellText.detail(read))
        }
        if (!read.stdout.contains("<settings")) {
            return XgResult.Err(ErrorCode.ERR_PARSE_FAILED, "SSAID 回读缺少 settings 根节点，拒绝视为成功")
        }
        val verification = SsaidXml.verifyAll(read.stdout, id16)
        if (verification.totalEntries != expectedEntries) {
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "SSAID 回读条目数变化",
                "期望 $expectedEntries，实际 ${verification.totalEntries}",
            )
        }
        if (verification.unresolvedPackages.isNotEmpty()) {
            return XgResult.Err(
                ErrorCode.ERR_PARSE_FAILED,
                "SSAID 回读有 ${verification.unresolvedPackages.size} 条记录无法解析",
                verification.unresolvedPackages.take(MAX_LISTED_PACKAGES).joinToString(" | "),
            )
        }
        if (verification.mismatchedPackages.isNotEmpty()) {
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "SSAID 回读仍有 ${verification.mismatchedPackages.size} 条记录不是目标 ID",
                verification.mismatchedPackages.take(MAX_LISTED_PACKAGES).joinToString(" | "),
            )
        }
        return XgResult.Ok(Unit)
    }

    private suspend fun failGlobalAndRollback(
        commands: String,
        snapshotId: Long,
        backupPath: String,
        workDir: String,
        beforeId: String,
        restoreGlobal: Boolean,
        error: XgResult.Err,
    ): XgResult<GlobalIdOutcome> {
        val fileRollback = restoreBackup(backupPath)
        val globalRollback: XgResult<Unit> = if (restoreGlobal) {
            restoreGlobalId(beforeId)
        } else {
            XgResult.Ok(Unit)
        }
        cleanup(workDir)
        val fileError = (fileRollback as? XgResult.Err)?.message
        val globalError = (globalRollback as? XgResult.Err)?.message
        val message = buildString {
            append(error.message)
            if (fileRollback.isOk) {
                append("；settings_ssaid.xml 已从备份恢复并回读一致")
            } else {
                append("；settings_ssaid.xml 回滚失败：").append(fileError ?: "未知原因")
            }
            if (restoreGlobal) {
                if (globalRollback.isOk) {
                    append("；全局 android_id 已恢复原值并回读一致")
                } else {
                    append("；全局 android_id 回滚失败：").append(globalError ?: "未知原因")
                }
            }
        }
        val fullyRolledBack = fileRollback.isOk && globalRollback.isOk
        val auditCommands = buildString {
            append(commands)
            append("\n<恢复 settings_ssaid.xml 备份>")
            if (restoreGlobal) append("\n<恢复原全局 android_id>")
        }
        recordAudit(
            ACTION_GLOBAL,
            GLOBAL_TARGET,
            auditCommands,
            if (fullyRolledBack) AuditResult.ROLLED_BACK else AuditResult.FAILED,
            message,
            snapshotId,
        )
        val detail = listOfNotNull(error.detail, fileError, globalError)
            .joinToString(" | ")
            .take(MAX_DETAIL_CHARS)
            .ifBlank { null }
        return if (fullyRolledBack) {
            XgResult.Err(error.code, message, detail)
        } else {
            XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, message, detail)
        }
    }

    private suspend fun restoreGlobalId(beforeId: String): XgResult<Unit> {
        val previous = beforeId.trim()
        if (AppShellText.ANDROID_ID.matches(previous.lowercase(Locale.US))) {
            val put = shell.exec(
                "settings put secure android_id ${AppShellText.quote(previous)}",
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
            if (!put.ok) return AppShellText.failure("恢复原 Android ID 失败", put)
            val read = shell.exec("settings get secure android_id", root = true, timeoutMs = READ_TIMEOUT_MS)
            if (!read.ok) return AppShellText.failure("回读原 Android ID 失败", read)
            val restored = read.stdout.trim()
            if (!restored.equals(previous, ignoreCase = true)) {
                return XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "恢复原 Android ID 后回读不一致",
                    "回读值长度 ${restored.length}",
                )
            }
            return XgResult.Ok(Unit)
        }
        if (previous.isEmpty() || previous.equals("null", ignoreCase = true)) {
            val delete = shell.exec(
                "settings delete secure android_id",
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
            if (!delete.ok) return AppShellText.failure("清除 Android ID 失败", delete)
            val read = shell.exec("settings get secure android_id", root = true, timeoutMs = READ_TIMEOUT_MS)
            if (!read.ok) return AppShellText.failure("回读 Android ID 失败", read)
            val restored = read.stdout.trim()
            if (restored.isNotEmpty() && !restored.equals("null", ignoreCase = true)) {
                return XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "清除 Android ID 后回读仍存在值",
                    "回读值长度 ${restored.length}",
                )
            }
            return XgResult.Ok(Unit)
        }
        return XgResult.Err(
            ErrorCode.ERR_BACKUP_FAILED,
            "原 Android ID 格式无法识别，未能恢复全局设置",
        )
    }

    private suspend fun failAndRollback(
        pkg: String,
        commands: String,
        snapshotId: Long,
        backupPath: String,
        workDir: String,
        error: XgResult.Err,
        dataCleared: Boolean = false,
        dataNote: String? = null,
    ): XgResult<Unit> {
        val rollback = restoreBackup(backupPath)
        cleanup(workDir)
        val message = buildString {
            append(error.message)
            if (rollback.isOk) {
                append("；SSAID 配置已从备份恢复并回读一致")
            } else {
                append("；回滚失败：").append((rollback as XgResult.Err).message)
            }
            if (dataNote != null) append("；").append(dataNote)
        }
        val auditResult = if (rollback.isOk && !dataCleared) AuditResult.ROLLED_BACK else AuditResult.FAILED
        recordAudit(ACTION_SSAID, pkg, commands, auditResult, message, snapshotId)
        return if (rollback.isOk) {
            XgResult.Err(error.code, message, error.detail)
        } else {
            XgResult.Err(
                ErrorCode.ERR_BACKUP_FAILED,
                message,
                listOfNotNull(error.detail, (rollback as XgResult.Err).detail).joinToString(" | "),
            )
        }
    }

    private suspend fun restoreBackup(backupPath: String): XgResult<Unit> {
        if (!isSafeBackupPath(backupPath)) {
            return XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "备份路径非法，拒绝回滚")
        }
        val tempPath = "$SSAID_DIR/.settings_ssaid.xml.xg.rollback.${stamp()}"
        val command = "cp ${AppShellText.quote(backupPath)} ${AppShellText.quote(tempPath)}" +
            " && chown system:system ${AppShellText.quote(tempPath)}" +
            " && chmod 0600 ${AppShellText.quote(tempPath)}" +
            " && (restorecon -F ${AppShellText.quote(tempPath)} 2>/dev/null || true)" +
            " && mv -f ${AppShellText.quote(tempPath)} ${AppShellText.quote(SSAID_SOURCE)}"
        val result = shell.exec(command, root = true, timeoutMs = 20_000)
        if (!result.ok) {
            shell.exec("rm -f ${AppShellText.quote(tempPath)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
            return XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "恢复 SSAID 备份失败", AppShellText.detail(result))
        }
        val compare = shell.exec(
            "cmp -s ${AppShellText.quote(backupPath)} ${AppShellText.quote(SSAID_SOURCE)}",
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        )
        if (!compare.ok) {
            shell.exec("rm -f ${AppShellText.quote(tempPath)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
            return XgResult.Err(
                ErrorCode.ERR_BACKUP_FAILED,
                "恢复 SSAID 备份后校验不一致",
                AppShellText.detail(compare),
            )
        }
        return XgResult.Ok(Unit)
    }

    private suspend fun cleanup(workDir: String) {
        if (!isCanonicalWithin(workDir, WORK_ROOT)) return
        shell.exec("rm -rf ${AppShellText.quote(workDir)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
    }

    private suspend fun recordAudit(
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String?,
        snapshotId: Long?,
    ) {
        audit.record(
            category = AuditCategory.APP,
            action = action,
            target = target,
            commands = commands,
            result = result,
            message = message,
            snapshotId = snapshotId,
        )
    }

    private fun invalidId(): XgResult.Err = XgResult.Err(
        ErrorCode.ERR_CMD_FAILED,
        "Android ID 必须为 16 位小写十六进制（0-9a-f）",
    )

    private suspend fun isSafeBackupPath(path: String): Boolean =
        isCanonicalWithin(path, BACKUP_ROOT)

    private suspend fun isCanonicalWithin(path: String, root: String): Boolean {
        if (!isLexicalWithin(path, root)) return false
        val canonical = canonicalPath(path) ?: return false
        return canonical == path && canonical.startsWith("$root/") && canonical != root
    }

    private fun isLexicalWithin(path: String, root: String): Boolean =
        path != root && path.startsWith("$root/") && !path.contains("..")

    private suspend fun canonicalPath(path: String): String? {
        if (!path.startsWith("/") || path.contains('\n') || path.contains('\u0000')) return null
        val result = shell.exec(
            "readlink -f ${AppShellText.quote(path)}",
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        )
        if (!result.ok) return null
        val canonical = result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return canonical.takeIf {
            it.startsWith("/") && !it.contains('\n') && !it.contains('\u0000')
        }
    }

    private fun safeName(pkg: String): String = pkg.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun stamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    private enum class SsaidFormat { ABX, TEXT }

    private data class SourceDocument(val format: SsaidFormat, val xml: String)

    private companion object {
        const val SSAID_SOURCE = "/data/system/users/0/settings_ssaid.xml"
        const val SSAID_DIR = "/data/system/users/0"
        const val BACKUP_ROOT = "/data/local/tmp/xg/backups"
        const val WORK_ROOT = "/data/local/tmp/xg/ssaid-work"
        const val ABX_MAGIC = "41425800"
        const val GLOBAL_TARGET = "android_id"

        const val KIND_GLOBAL_ID = "global_android_id"
        const val KIND_SSAID = "app_ssaid"

        const val ACTION_GLOBAL = "set_global_android_id"
        const val ACTION_SSAID = "set_app_ssaid"
        const val ACTION_UNDO_SSAID = "undo_app_ssaid"

        const val READ_TIMEOUT_MS = 10_000L
        const val COMMAND_TIMEOUT_MS = 15_000L
        const val CONVERT_TIMEOUT_MS = 30_000L
        const val CLEAR_TIMEOUT_MS = 60_000L
        const val SNAPSHOT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_LISTED_PACKAGES = 5
        const val MAX_DETAIL_CHARS = 800
    }
}
