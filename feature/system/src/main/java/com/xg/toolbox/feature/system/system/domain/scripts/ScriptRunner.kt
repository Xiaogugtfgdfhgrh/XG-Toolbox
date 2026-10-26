package com.xg.toolbox.feature.system.domain.scripts

import android.content.Context
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.SingleFlightValueCache
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
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.json.JSONArray

@Singleton
class ScriptRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val auditRepository: AuditRepository,
    private val snapshotRepository: SnapshotRepository,
) {

    private val prefs by lazy {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private val scriptsCache = SingleFlightValueCache<List<ScriptInfo>>()

    
    suspend fun scripts(forceRefresh: Boolean = false): List<ScriptInfo> =
        scriptsCache.get(forceRefresh) {
            runCatching {
                parseManifest(readAssetText(MANIFEST_ASSET))
            }.onFailure { XgLog.e(TAG, "读取内置脚本清单失败", it) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        } ?: emptyList()

    
    suspend fun ensureInstalled(): XgResult<Unit> = withContext(Dispatchers.IO) {
        rootStatus.state.value.rootError()?.let { return@withContext it }
        val infos = scripts()
        if (infos.isEmpty()) {
            return@withContext XgResult.Err(
                code = ErrorCode.ERR_CMD_FAILED,
                message = "内置脚本清单为空或无法解析（assets/scripts/manifest.json）",
            )
        }
        val prepare = shell.exec("mkdir -p $INSTALL_DIR && chmod 700 $INSTALL_DIR", root = true, timeoutMs = 10_000)
        if (!prepare.ok) {
            return@withContext XgResult.Err(
                code = ErrorCode.ERR_CMD_FAILED,
                message = "无法创建脚本目录 $INSTALL_DIR",
                detail = firstLine(prepare.stdout.ifBlank { prepare.stderr }),
            )
        }
        val assets = mutableMapOf<String, ByteArray>()
        for (info in infos) {
            val bytes = runCatching { readAssetBytes("$SCRIPT_ASSET_DIR/${info.id}.sh") }.getOrNull()
            if (bytes == null || bytes.isEmpty()) {
                return@withContext XgResult.Err(
                    code = ErrorCode.ERR_CMD_FAILED,
                    message = "内置脚本缺失：${info.id}.sh",
                )
            }
            assets[info.id] = bytes
        }
        val expectedHashes = assets.mapValues { (_, bytes) -> sha256Hex(bytes) }
        val currentHashes = readInstalledHashes(infos.map { it.id })
        val toWrite = infos.filter { currentHashes[it.id] != expectedHashes[it.id] }
        for (info in toWrite) {
            val bytes = assets.getValue(info.id)
            val base64 = Base64.getEncoder().encodeToString(bytes)
            val path = "$INSTALL_DIR/${info.id}.sh"
            val write = shell.exec(
                "printf %s '$base64' | base64 -d > $path && chmod 700 $path",
                root = true,
                timeoutMs = 20_000,
            )
            if (!write.ok) {
                return@withContext XgResult.Err(
                    code = ErrorCode.ERR_CMD_FAILED,
                    message = "写入脚本失败：${info.id}.sh",
                    detail = firstLine(write.stdout.ifBlank { write.stderr }),
                )
            }
        }
        val readbackHashes = readInstalledHashes(infos.map { it.id })
        val hashMismatch = infos.filter { readbackHashes[it.id] != expectedHashes[it.id] }
        if (hashMismatch.isNotEmpty()) {
            return@withContext XgResult.Err(
                code = ErrorCode.ERR_CMD_FAILED,
                message = "脚本安装校验失败（sha256 不一致）：${hashMismatch.joinToString(", ") { it.id }}",
            )
        }
        val perms = readInstalledPerms(infos.map { it.id })
        val badPerm = infos.filter { perms[it.id] != EXPECTED_MODE }
        if (badPerm.isNotEmpty()) {
            return@withContext XgResult.Err(
                code = ErrorCode.ERR_CMD_FAILED,
                message = "脚本权限校验失败（期望 $EXPECTED_MODE）：${badPerm.joinToString(", ") { it.id }}",
            )
        }
        XgResult.Ok(Unit)
    }

    
    fun run(scriptId: String): Flow<ScriptLine> = flow {
        val info = scripts().firstOrNull { it.id == scriptId }
        if (info == null) {
            emit(ScriptLine("未知脚本：$scriptId", failed = true))
            persistResult(scriptId, ScriptResult(exitCode = -1, freedBytes = null, message = "未知脚本：$scriptId"))
            return@flow
        }
        when (val installed = ensureInstalled()) {
            is XgResult.Err -> {
                emit(ScriptLine("脚本安装/校验失败：${installed.message}", failed = true))
                installed.detail?.let { emit(ScriptLine(it, failed = true)) }
                persistResult(scriptId, ScriptResult(exitCode = -1, freedBytes = null, message = "安装失败：${installed.message}"))
                recordAudit(scriptId, "（未执行）", AuditResult.FAILED, "脚本安装失败：${installed.message}", null)
                return@flow
            }

            is XgResult.Ok -> Unit
        }
        val path = "$INSTALL_DIR/$scriptId.sh"
        emit(ScriptLine("$ sh $path", failed = false))
        val snapshotId = createSnapshot(scriptId)
        if (snapshotId == null) {
            emit(ScriptLine("无法写入执行快照，已中止（未运行脚本）", failed = true))
            persistResult(scriptId, ScriptResult(exitCode = -1, freedBytes = null, message = "无法写入执行快照"))
            return@flow
        }
        var exitCode = EXIT_UNKNOWN
        var freedBytes: Long? = null
        val stderrLines = mutableListOf<String>()
        var persisted = false
        try {
            shell.execStream(
                "sh -c 'sh $path; rc=\$?; echo $MARK_EXIT:\$rc'",
                root = true,
                timeoutMs = EXEC_TIMEOUT_MS,
            ).collect { line ->
                val text = line.text.trimEnd('\r')
                if (line.isErr) {
                    if (text.isNotBlank()) stderrLines += text
                    emit(ScriptLine(text, failed = true))
                    return@collect
                }
                when {
                    text.startsWith(MARK_DONE) -> {
                        freedBytes = text.removePrefix(MARK_DONE).trim().toLongOrNull()
                        emit(ScriptLine(text, failed = false))
                    }

                    text.startsWith(MARK_EXIT) -> {
                        exitCode = text.removePrefix(MARK_EXIT).trim().toIntOrNull() ?: EXIT_UNKNOWN
                    }

                    else -> emit(ScriptLine(text, failed = false))
                }
            }
            val result = buildResult(info, exitCode, freedBytes, stderrLines)
            emit(ScriptLine(result.second, failed = result.first))
            persistResult(scriptId, result.third)
            persisted = true
            recordAudit(
                scriptId = scriptId,
                commands = "sh $path",
                result = if (result.third.exitCode == 0 && result.third.freedBytes != null) {
                    AuditResult.OK
                } else {
                    AuditResult.FAILED
                },
                message = result.third.message,
                snapshotId = snapshotId,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            XgLog.e(TAG, "脚本执行异常：$scriptId", t)
            emit(ScriptLine("执行异常：${t.message ?: t.javaClass.simpleName}", failed = true))
            persistResult(scriptId, ScriptResult(exitCode, freedBytes, "执行异常：${t.message ?: t.javaClass.simpleName}"))
            persisted = true
        } finally {
            if (!persisted) {
                withContext(NonCancellable) {
                    persistResult(
                        scriptId,
                        ScriptResult(
                            exitCode = exitCode,
                            freedBytes = freedBytes,
                            message = "执行被中断（退出码 ${if (exitCode == EXIT_UNKNOWN) "未知" else exitCode})",
                        ),
                    )
                    recordAudit(scriptId, "sh $path", AuditResult.FAILED, "执行被中断", snapshotId)
                }
            }
        }
    }

    
    suspend fun lastResult(scriptId: String): ScriptResult? = withContext(Dispatchers.IO) {
        if (!prefs.contains(key(scriptId, KEY_EXIT))) return@withContext null
        val exitCode = prefs.getInt(key(scriptId, KEY_EXIT), EXIT_UNKNOWN)
        val freed = if (prefs.contains(key(scriptId, KEY_FREED))) {
            prefs.getLong(key(scriptId, KEY_FREED), 0L)
        } else {
            null
        }
        val message = prefs.getString(key(scriptId, KEY_MESSAGE), "").orEmpty()
        ScriptResult(exitCode = exitCode, freedBytes = freed, message = message)
    }

    
    private fun buildResult(
        info: ScriptInfo,
        exitCode: Int,
        freedBytes: Long?,
        stderrLines: List<String>,
    ): Triple<Boolean, String, ScriptResult> {
        val stderrText = stderrLines.lastOrNull { it.isNotBlank() }?.take(160)
        return when {
            exitCode == 0 && freedBytes != null -> {
                val message = "完成 · 释放 ${Formatters.bytes(freedBytes)} · 退出码 0"
                Triple(false, message, ScriptResult(0, freedBytes, message))
            }

            exitCode == 0 && freedBytes == null -> {
                val message = "退出码 0，但脚本未输出 XG_DONE 标记，释放量未知（不采用估算值）"
                Triple(true, message, ScriptResult(0, null, message))
            }

            exitCode == EXIT_UNKNOWN && freedBytes != null -> {
                if (stderrLines.any { it.isNotBlank() }) {
                    val message = "未取到退出码且存在错误输出" + (stderrText?.let { "：$it" } ?: "")
                    Triple(true, message, ScriptResult(EXIT_UNKNOWN, freedBytes, message))
                } else {
                    val message = "完成 · 释放 ${Formatters.bytes(freedBytes)}"
                    Triple(false, message, ScriptResult(0, freedBytes, message))
                }
            }

            exitCode == EXIT_UNKNOWN -> {
                val message = "未取到退出码（脚本可能被中断）" + (stderrText?.let { "：$it" } ?: "")
                Triple(true, message, ScriptResult(EXIT_UNKNOWN, freedBytes, message))
            }

            freedBytes != null -> {
                val message = "${info.name} 部分失败 · 退出码 $exitCode · 已释放 ${Formatters.bytes(freedBytes)}" +
                    (stderrText?.let { "：$it" } ?: "")
                Triple(true, message, ScriptResult(exitCode, freedBytes, message))
            }

            else -> {
                val message = "${info.name} 失败 · 退出码 $exitCode" + (stderrText?.let { "：$it" } ?: "")
                Triple(true, message, ScriptResult(exitCode, null, message))
            }
        }
    }

    private suspend fun persistResult(scriptId: String, result: ScriptResult) = withContext(Dispatchers.IO) {
        runCatching {
            prefs.edit()
                .putInt(key(scriptId, KEY_EXIT), result.exitCode)
                .apply {
                    val freed = result.freedBytes
                    if (freed == null) remove(key(scriptId, KEY_FREED)) else putLong(key(scriptId, KEY_FREED), freed)
                }
                .putString(key(scriptId, KEY_MESSAGE), result.message)
                .putLong(key(scriptId, KEY_TS), System.currentTimeMillis())
                .commit()
        }.onFailure { XgLog.e(TAG, "持久化脚本结果失败：$scriptId", it) }
        Unit
    }

    private suspend fun recordAudit(
        scriptId: String,
        commands: String,
        result: String,
        message: String,
        snapshotId: Long?,
    ) {
        runCatching {
            auditRepository.record(
                category = AuditCategory.SYSTEM,
                action = ACTION_RUN_SCRIPT,
                target = scriptId,
                commands = commands.take(AUDIT_COMMAND_LIMIT),
                result = result,
                message = message.take(AUDIT_MESSAGE_LIMIT),
                snapshotId = snapshotId,
            )
        }.onFailure { XgLog.e(TAG, "写入脚本审计失败：$scriptId", it) }
    }

    private suspend fun createSnapshot(scriptId: String): Long? = runCatching {
        snapshotRepository.create(
            KIND_SCRIPT_RUN,
            scriptId,
            """{"script":"$scriptId","at":${System.currentTimeMillis()}}""",
            SNAPSHOT_TTL_MS,
        )
    }.onFailure { XgLog.e(TAG, "创建脚本执行快照失败：$scriptId", it) }.getOrNull()

    private suspend fun readInstalledHashes(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val paths = ids.joinToString(" ") { "$INSTALL_DIR/$it.sh" }
        val result = shell.exec("sha256sum $paths 2>/dev/null", root = true, timeoutMs = 20_000)
        if (result.stdout.isBlank()) return emptyMap()
        val hashes = mutableMapOf<String, String>()
        result.stdout.lineSequence().forEach { line ->
            val tokens = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (tokens.size >= 2) {
                val id = tokens[1].substringAfterLast('/').removeSuffix(".sh")
                hashes[id] = tokens[0]
            }
        }
        return hashes
    }

    private suspend fun readInstalledPerms(ids: List<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val paths = ids.joinToString(" ") { "$INSTALL_DIR/$it.sh" }
        val result = shell.exec("stat -c %a:%n $paths 2>/dev/null", root = true, timeoutMs = 20_000)
        if (result.stdout.isBlank()) return emptyMap()
        val perms = mutableMapOf<String, String>()
        result.stdout.lineSequence().forEach { line ->
            val trimmed = line.trim()
            val separator = trimmed.indexOf(':')
            if (separator <= 0) return@forEach
            val mode = trimmed.substring(0, separator)
            val id = trimmed.substringAfterLast('/').removeSuffix(".sh")
            perms[id] = mode
        }
        return perms
    }

    private fun readAssetBytes(path: String): ByteArray =
        context.assets.open(path).use { it.readBytes() }

    private fun readAssetText(path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun parseManifest(json: String): List<ScriptInfo> {
        val array = JSONArray(json)
        val list = mutableListOf<ScriptInfo>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (!SCRIPT_ID.matches(id)) {
                XgLog.w(TAG, "忽略非法的脚本 id：$id")
                continue
            }
            val name = item.optString("name").trim().ifEmpty { id }
            val desc = item.optString("desc").trim()
            val estimate = item.optString("est").trim().ifEmpty { "—" }
            list += ScriptInfo(id = id, name = name, description = desc, estimate = estimate)
        }
        return list
    }

    private fun sha256Hex(bytes: ByteArray): String =
        Formatters.sha256Hex(bytes)

    private fun key(scriptId: String, field: String): String = "script_${scriptId}_$field"

    private fun firstLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200)

    companion object {
        private const val TAG = "ScriptRunner"
        private const val MANIFEST_ASSET = "scripts/manifest.json"
        private const val SCRIPT_ASSET_DIR = "scripts"
        private const val INSTALL_DIR = "/data/local/tmp/xg/scripts"
        private const val EXPECTED_MODE = "700"
        private const val MARK_DONE = "XG_DONE"
        private const val MARK_EXIT = "XG_EXIT"
        private const val EXIT_UNKNOWN = -1
        private const val EXEC_TIMEOUT_MS = 180_000L
        private const val KIND_SCRIPT_RUN = "script_run"
        private const val ACTION_RUN_SCRIPT = "run_script"
        private const val SNAPSHOT_TTL_MS = 24L * 3600 * 1000
        private const val AUDIT_COMMAND_LIMIT = 2000
        private const val AUDIT_MESSAGE_LIMIT = 1000
        private const val PREF_NAME = "xg_system_scripts"
        private const val KEY_EXIT = "exit"
        private const val KEY_FREED = "freed"
        private const val KEY_MESSAGE = "message"
        private const val KEY_TS = "ts"
        private val WHITESPACE = Regex("\\s+")
        private val SCRIPT_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}
