package com.xg.toolbox.feature.apps.domain

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.ShellResult
import com.xg.toolbox.core.shell.rootError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Singleton
internal class AbiInspector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val audit: AuditRepository,
    private val snapshots: SnapshotRepository,
) {

    
    suspend fun status(pkg: String): XgResult<AbiStatus> = withContext(Dispatchers.IO) {
        if (!AppShellText.isValidPackage(pkg)) {
            return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
        }
        try {
            val packageManager = context.packageManager
            val info = packageManager.getPackageInfo(pkg, 0)
            val app = info.applicationInfo
                ?: return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "无法读取应用信息：$pkg")
            val apkPaths = apkPaths(app)
            if (apkPaths.isEmpty()) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "无法读取 APK 路径：$pkg")
            }
            val apkAbis = try {
                scanAbis(apkPaths)
            } catch (t: Throwable) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_PARSE_FAILED,
                    "无法读取 APK 运行库清单",
                    t.message,
                )
            }
            val deviceAbis = deviceAbis()
            val has32 = apkAbis.any { is32Bit(it) && supportsOnDevice(it, deviceAbis) }
            val has64 = apkAbis.any { is64Bit(it) && supportsOnDevice(it, deviceAbis) }
            val currentAbi = currentPrimaryAbi(pkg, app)
            val reason = when {
                apkAbis.isEmpty() -> NO_NATIVE_LIBS_REASON
                !has32 && !has64 -> {
                    "APK 运行库（${apkAbis.joinToString("/")}）与当前设备支持的 ABI 不匹配"
                }
                !has32 -> if (apkAbis.none(::is32Bit)) {
                    "该应用未包含 32 位运行库"
                } else {
                    "当前设备不支持 32 位运行库"
                }
                !has64 -> if (apkAbis.none(::is64Bit)) {
                    "该应用未包含 64 位运行库"
                } else {
                    "当前设备不支持 64 位运行库"
                }
                else -> null
            }
            XgResult.Ok(
                AbiStatus(
                    packageName = pkg,
                    currentAbi = currentAbi,
                    apkAbis = apkAbis,
                    canSwitchTo32 = has32,
                    canSwitchTo64 = has64,
                    reason = reason,
                ),
            )
        } catch (t: PackageManager.NameNotFoundException) {
            XgResult.Err(ErrorCode.ERR_CMD_FAILED, "应用未安装：$pkg", t.message)
        } catch (t: Throwable) {
            XgResult.Err(ErrorCode.ERR_CMD_FAILED, "读取 ABI 状态失败", t.message)
        }
    }

    
    suspend fun switch(pkg: String, targetAbi: String): XgResult<Unit> =
        withContext(Dispatchers.IO) {
            if (!AppShellText.isValidPackage(pkg)) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
            }
            if (targetAbi !in KNOWN_ABIS) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_UNSUPPORTED_ABI,
                    "不支持的 ABI：$targetAbi",
                )
            }
            val statusResult = status(pkg)
            if (statusResult is XgResult.Err) return@withContext statusResult
            val current = statusResult.getOrNull()
                ?: return@withContext XgResult.Err(
                    ErrorCode.ERR_PARSE_FAILED,
                    "ABI 状态读取结果为空",
                )
            if (current.apkAbis.isEmpty()) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_UNSUPPORTED_ABI,
                    NO_NATIVE_LIBS_REASON,
                )
            }
            if (targetAbi !in current.apkAbis) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_UNSUPPORTED_ABI,
                    "该应用未包含 $targetAbi 运行库，无法切换",
                    "APK 实际包含：${current.apkAbis.joinToString(", ")}",
                )
            }
            if (!supportsOnDevice(targetAbi, deviceAbis())) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_UNSUPPORTED_ABI,
                    "当前设备不支持 $targetAbi",
                    "设备支持：${Build.SUPPORTED_ABIS.joinToString(", ")}",
                )
            }
            if (current.currentAbi != null && abiMatches(targetAbi, current.currentAbi)) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "当前已是 $targetAbi，无需切换",
                )
            }
            rootStatus.state.value.rootError()?.let { return@withContext it }

            val installedApp = try {
                context.packageManager.getPackageInfo(pkg, 0).applicationInfo
            } catch (t: PackageManager.NameNotFoundException) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "应用未安装：$pkg",
                    t.message,
                )
            } catch (t: Throwable) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "无法读取已安装应用信息",
                    t.message,
                )
            } ?: return@withContext XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "应用信息不完整：$pkg",
            )
            val sourcePaths = apkPaths(installedApp)
            if (sourcePaths.isEmpty()) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "无法读取 APK 路径：$pkg")
            }
            if (sourcePaths.any { !isSafeApkPath(it) }) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "PackageManager 返回了非法 APK 路径",
                )
            }
            val stageDir = stageDirectory(pkg)
            if (!isSafeStageDirectory(stageDir)) {
                return@withContext XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "ABI 暂存目录非法：$stageDir",
                )
            }

            val beforeAbi = current.currentAbi
            val attemptedCommands = mutableListOf<String>()
            try {
                val stage = stageApks(sourcePaths, stageDir)
                if (stage.paths.isEmpty()) {
                    val snapshotId = createSnapshot(
                        pkg = pkg,
                        beforeAbi = beforeAbi,
                        afterAbi = beforeAbi,
                        targetAbi = targetAbi,
                        stageDir = stageDir,
                        apkCount = 0,
                        commands = emptyList(),
                    )
                    val message = "ABI 切换失败：无法暂存已安装 APK" +
                        snapshotWarning(snapshotId)
                    recordAudit(
                        pkg = pkg,
                        stageDir = stageDir,
                        commands = emptyList(),
                        result = AuditResult.FAILED,
                        message = message,
                        snapshotId = snapshotId,
                    )
                    return@withContext XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        message,
                        stage.error,
                    )
                }

                val staged = stage.paths
                val preSnapshotId = createSnapshot(
                    pkg = pkg,
                    beforeAbi = beforeAbi,
                    afterAbi = beforeAbi,
                    targetAbi = targetAbi,
                    stageDir = stageDir,
                    apkCount = staged.size,
                    commands = emptyList(),
                )
                if (preSnapshotId == null) {
                    val message = "无法创建 ABI 切换快照，已中止安装"
                    recordAudit(
                        pkg = pkg,
                        stageDir = stageDir,
                        commands = emptyList(),
                        result = AuditResult.FAILED,
                        message = message,
                        snapshotId = null,
                    )
                    return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, message)
                }
                val primaryCommand = buildString {
                    append("pm install --abi ")
                    append(AppShellText.quote(targetAbi))
                    append(" -r -d ")
                    append(staged.joinToString(" ") { AppShellText.quote(it) })
                }
                attemptedCommands += primaryCommand
                var installResult = shell.exec(
                    primaryCommand,
                    root = true,
                    timeoutMs = INSTALL_TIMEOUT_MS,
                )
                var accepted = installAccepted(installResult)
                val installOutput = StringBuilder()
                appendOutput(installOutput, "pm install", installResult)

                if (!accepted && staged.size > 1) {
                    val fallbackCommand = buildString {
                        append("pm install-multiple --abi ")
                        append(AppShellText.quote(targetAbi))
                        append(" -r -d ")
                        append(staged.joinToString(" ") { AppShellText.quote(it) })
                    }
                    attemptedCommands += fallbackCommand
                    installResult = shell.exec(
                        fallbackCommand,
                        root = true,
                        timeoutMs = INSTALL_TIMEOUT_MS,
                    )
                    accepted = installAccepted(installResult)
                    appendOutput(installOutput, "pm install-multiple", installResult)
                }
                val output = installOutput.toString()

                if (!accepted) {
                    val snapshotId = createSnapshot(
                        pkg = pkg,
                        beforeAbi = beforeAbi,
                        afterAbi = beforeAbi,
                        targetAbi = targetAbi,
                        stageDir = stageDir,
                        apkCount = staged.size,
                        commands = attemptedCommands,
                    )
                    val failureCode = INSTALL_FAILURE.find(output)?.groupValues?.getOrNull(1)
                    val restricted = isAbiRestriction(output)
                    val code = when {
                        restricted -> ErrorCode.ERR_UNSUPPORTED_DEVICE
                        else -> AppShellText.normalizeCode(installResult.code)
                    }
                    val message = when {
                        failureCode != null -> "ABI 切换安装失败（$failureCode）"
                        restricted -> "当前系统/ROM 不允许切换应用 ABI（pm --abi 不可用）"
                        else -> "ABI 切换安装失败"
                    } + snapshotWarning(snapshotId)
                    recordAudit(
                        pkg = pkg,
                        stageDir = stageDir,
                        commands = attemptedCommands,
                        result = AuditResult.FAILED,
                        message = message,
                        snapshotId = snapshotId,
                    )
                    return@withContext XgResult.Err(
                        code = code,
                        message = message,
                        detail = detailText("安装输出" to output),
                    )
                }

                val readback = readPrimaryCpuAbi(pkg)
                val afterAbi = readback.abi
                val matched = afterAbi != null && abiMatches(targetAbi, afterAbi)
                val snapshotId = createSnapshot(
                    pkg = pkg,
                    beforeAbi = beforeAbi,
                    afterAbi = afterAbi,
                    targetAbi = targetAbi,
                    stageDir = stageDir,
                    apkCount = staged.size,
                    commands = attemptedCommands,
                )
                if (matched && snapshotId == null) {
                    val message = "ABI 已切换，但结果快照写入失败"
                    recordAudit(
                        pkg = pkg,
                        stageDir = stageDir,
                        commands = attemptedCommands,
                        result = AuditResult.FAILED,
                        message = message,
                        snapshotId = null,
                    )
                    XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        message,
                        detailText(
                            "安装输出" to output,
                            "回读命令" to readback.raw,
                            "处理建议" to "ABI 已变更，请勿重复安装；请检查快照数据库后重新读取状态。",
                        ),
                    )
                } else if (matched) {
                    recordAudit(
                        pkg = pkg,
                        stageDir = stageDir,
                        commands = attemptedCommands,
                        result = AuditResult.OK,
                        message = "primaryCpuAbi 回读为 $afterAbi",
                        snapshotId = snapshotId,
                    )
                    XgResult.Ok(Unit)
                } else {
                    val actual = afterAbi ?: "无法读取"
                    val message =
                        "安装命令已返回成功，但 primaryCpuAbi 回读为 $actual，未确认切换成功" +
                            snapshotWarning(snapshotId)
                    recordAudit(
                        pkg = pkg,
                        stageDir = stageDir,
                        commands = attemptedCommands,
                        result = AuditResult.FAILED,
                        message = message,
                        snapshotId = snapshotId,
                    )
                    XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        message,
                        detailText(
                            "安装输出" to output,
                            "回读命令" to readback.raw,
                        ),
                    )
                }
            } finally {
                cleanupStage(stageDir)
            }
        }

    
    @Suppress("UNUSED_PARAMETER")
    suspend fun switch(pkg: String, targetAbi: String, apkPath: String?): XgResult<Unit> =
        switch(pkg, targetAbi)

    private fun apkPaths(app: ApplicationInfo): List<String> = buildList {
        val base = app.publicSourceDir?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: app.sourceDir?.trim().orEmpty()
        if (base.isNotEmpty()) add(base)
        app.splitSourceDirs?.forEach { split ->
            val path = split?.trim().orEmpty()
            if (path.isNotEmpty()) add(path)
        }
    }.distinct()

    
    private fun scanAbis(paths: List<String>): List<String> {
        val found = linkedSetOf<String>()
        paths.forEach { path ->
            val file = File(path)
            if (!file.isFile) throw IllegalArgumentException("APK 不存在：$path")
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (entry.isDirectory || !name.startsWith("lib/")) continue
                    val relative = name.removePrefix("lib/")
                    val slash = relative.indexOf('/')
                    if (slash <= 0 || slash == relative.lastIndex) continue
                    val abi = relative.substring(0, slash)
                    if (ABI_NAME.matches(abi)) found += abi
                }
            }
        }
        return found.sortedWith(
            compareBy(
                { ABI_ORDER.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } },
                { it },
            ),
        )
    }

    private fun is32Bit(abi: String): Boolean = abi in ABI_32

    private fun is64Bit(abi: String): Boolean = abi in ABI_64

    private fun deviceAbis(): Set<String> = buildSet {
        addAll(Build.SUPPORTED_ABIS)
        addAll(Build.SUPPORTED_32_BIT_ABIS)
        addAll(Build.SUPPORTED_64_BIT_ABIS)
    }

    private fun supportsOnDevice(abi: String, deviceAbis: Set<String>): Boolean = when (abi) {
        "armeabi" -> deviceAbis.any { it == "armeabi" || it == "armeabi-v7a" }
        else -> abi in deviceAbis
    }

    
    private fun abiMatches(targetAbi: String, actualAbi: String): Boolean = when (targetAbi) {
        "armeabi" -> actualAbi == "armeabi" || actualAbi == "armeabi-v7a"
        else -> actualAbi == targetAbi
    }

    private suspend fun currentPrimaryAbi(pkg: String, app: ApplicationInfo): String? {
        if (rootStatus.state.value is RootState.Granted) {
            readPrimaryCpuAbi(pkg).abi?.let { return it }
        }
        return abiFromNativeLibraryDir(app)
    }

    private suspend fun readPrimaryCpuAbi(pkg: String): AbiReadback {
        val command =
            "dumpsys package ${AppShellText.quote(pkg)} | grep -m1 primaryCpuAbi"
        val result = shell.exec(command, root = true, timeoutMs = READ_TIMEOUT_MS)
        val line = result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(PRIMARY_ABI_PREFIX) }
        val value = line
            ?.removePrefix(PRIMARY_ABI_PREFIX)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "null" }
        return AbiReadback(value, rawOutput(result))
    }

    private fun abiFromNativeLibraryDir(app: ApplicationInfo): String? {
        val dir = app.nativeLibraryDir ?: return null
        return when (dir.substringAfterLast('/')) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            "riscv64" -> "riscv64"
            else -> null
        }
    }

    private suspend fun stageApks(sourcePaths: List<String>, stageDir: String): StageOutcome {
        val staged = sourcePaths.mapIndexed { index, _ ->
            if (index == 0) "$stageDir/base.apk" else "$stageDir/split_$index.apk"
        }
        val command = buildString {
            append("rm -rf ").append(AppShellText.quote(stageDir))
            append(" && mkdir -p ").append(AppShellText.quote(stageDir))
            sourcePaths.forEachIndexed { index, source ->
                append(" && cp -f ")
                append(AppShellText.quote(source))
                append(' ')
                append(AppShellText.quote(staged[index]))
            }
            staged.forEach { append(" && test -s ").append(AppShellText.quote(it)) }
        }
        val result = shell.exec(command, root = true, timeoutMs = STAGE_TIMEOUT_MS)
        if (!result.ok) {
            return StageOutcome(
                paths = emptyList(),
                error = detailText("暂存输出" to rawOutput(result)),
            )
        }
        return StageOutcome(staged, null)
    }

    private suspend fun cleanupStage(stageDir: String) {
        if (!isSafeStageDirectory(stageDir)) return
        try {
            withContext(NonCancellable) {
                val result = shell.exec(
                    "rm -rf ${AppShellText.quote(stageDir)}",
                    root = true,
                    timeoutMs = READ_TIMEOUT_MS,
                )
                if (!result.ok) {
                    XgLog.w(TAG, "清理 ABI 暂存目录失败（exit=${result.code}）")
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.w(TAG, "清理 ABI 暂存目录异常：${t.javaClass.simpleName}")
        }
    }

    private fun isSafeApkPath(path: String): Boolean {
        if (!path.startsWith('/') || path.contains('\n') || path.contains('\u0000')) return false
        if (!path.endsWith(".apk")) return false
        return path.split('/').none { it == ".." }
    }

    private fun stageDirectory(pkg: String): String {
        val safe = pkg.map { ch ->
            if (ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '-') ch else '_'
        }.joinToString("")
        return "$STAGE_ROOT/$safe"
    }

    private fun isSafeStageDirectory(path: String): Boolean {
        if (!path.startsWith("$STAGE_ROOT/")) return false
        if (path.contains('\n') || path.contains('\u0000') || path.contains("..")) return false
        val name = path.removePrefix("$STAGE_ROOT/")
        return name.isNotEmpty() && name != "." && name != ".."
    }

    private fun installAccepted(result: ShellResult): Boolean {
        val output = rawOutput(result)
        val hasFailure = INSTALL_FAILURE.containsMatchIn(output) ||
            FAILURE_WORD.containsMatchIn(output)
        val hasSuccess = SUCCESS_WORD.containsMatchIn(output)
        return !hasFailure && (hasSuccess || result.ok)
    }

    private fun isAbiRestriction(output: String): Boolean {
        val lower = output.lowercase()
        if (!lower.contains("--abi") && !lower.contains("abi")) return false
        return lower.contains("unknown option") ||
            lower.contains("unrecognized option") ||
            lower.contains("not supported") ||
            lower.contains("unsupported") ||
            lower.contains("permission denial") ||
            lower.contains("securityexception") ||
            lower.contains("install_failed_abi")
    }

    private fun appendOutput(builder: StringBuilder, label: String, result: ShellResult) {
        val output = rawOutput(result).trim()
        builder.append('[').append(label).append(']')
        if (output.isNotEmpty()) builder.append('\n').append(output)
        builder.append("\nexit=").append(result.code).append('\n')
    }

    private fun rawOutput(result: ShellResult): String =
        listOf(result.stdout, result.stderr)
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private fun snapshotWarning(snapshotId: Long?): String =
        if (snapshotId == null) "；结果快照写入失败" else ""

    private fun detailText(vararg sections: Pair<String, String>): String? {
        val text = sections.mapNotNull { (label, body) ->
            body.trim().takeIf { it.isNotEmpty() }?.let { "$label:\n$it" }
        }.joinToString("\n")
        if (text.isBlank()) return null
        return if (text.length <= MAX_DETAIL_CHARS) {
            text
        } else {
            text.take(MAX_DETAIL_CHARS) + "\n…（输出已截断）"
        }
    }

    private suspend fun createSnapshot(
        pkg: String,
        beforeAbi: String?,
        afterAbi: String?,
        targetAbi: String,
        stageDir: String,
        apkCount: Int,
        commands: List<String>,
    ): Long? {
        val safeCommands = commands.filter { isWhitelistedCommand(it, stageDir) }
        val payload = JSONObject()
            .put("beforeAbi", beforeAbi ?: JSONObject.NULL)
            .put("afterAbi", afterAbi ?: JSONObject.NULL)
            .put("targetAbi", targetAbi)
            .put("stageDir", stageDir)
            .put("apkCount", apkCount)
            .put(
                "commands",
                JSONArray().apply { safeCommands.forEach { put(it) } },
            )
            .toString()
        return try {
            snapshots.create(
                kind = KIND_ABI,
                target = pkg,
                payload = payload,
                ttlMs = SNAPSHOT_TTL_MS,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.e(TAG, "ABI 切换快照写入失败", t)
            null
        }
    }

    private suspend fun recordAudit(
        pkg: String,
        stageDir: String,
        commands: List<String>,
        result: String,
        message: String,
        snapshotId: Long?,
    ) {
        val safeCommands = commands
            .filter { isWhitelistedCommand(it, stageDir) }
            .joinToString("; ")
            .take(MAX_AUDIT_COMMANDS)
        audit.record(
            category = AuditCategory.APP,
            action = ACTION_SWITCH_ABI,
            target = pkg,
            commands = safeCommands,
            result = result,
            message = message,
            snapshotId = snapshotId,
        )
    }

    
    private fun isWhitelistedCommand(command: String, stageDir: String): Boolean {
        if (!isSafeStageDirectory(stageDir)) return false
        if (command.contains("..") || command.contains('\n') || command.contains('\u0000')) {
            return false
        }
        if (!command.contains("--abi")) return false
        return command.contains("'$stageDir/")
    }

    private data class AbiReadback(
        val abi: String?,
        val raw: String,
    )

    private data class StageOutcome(
        val paths: List<String>,
        val error: String?,
    )

    private companion object {
        const val TAG = "AbiInspector"
        const val KIND_ABI = "abi_switch"
        const val ACTION_SWITCH_ABI = "switch_abi"
        const val NO_NATIVE_LIBS_REASON = "应用未包含本地库，无需切换 ABI"
        const val STAGE_ROOT = "/data/local/tmp/xg-abi"
        const val READ_TIMEOUT_MS = 10_000L
        const val STAGE_TIMEOUT_MS = 60_000L
        const val INSTALL_TIMEOUT_MS = 60_000L
        const val SNAPSHOT_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val PRIMARY_ABI_PREFIX = "primaryCpuAbi="
        const val MAX_DETAIL_CHARS = 12_000
        const val MAX_AUDIT_COMMANDS = 1_800

        val ABI_NAME = Regex("[A-Za-z0-9_-]+")
        val ABI_ORDER = listOf(
            "arm64-v8a", "armeabi-v7a", "armeabi",
            "x86_64", "x86", "mips64", "mips",
        )
        val ABI_32 = setOf("armeabi-v7a", "armeabi", "x86", "mips")
        val ABI_64 = setOf("arm64-v8a", "x86_64", "mips64")
        val KNOWN_ABIS = ABI_32 + ABI_64
        val INSTALL_FAILURE = Regex("""Failure\s*\[([A-Z0-9_]+)]""")
        val FAILURE_WORD = Regex("""(?i)\bFailure\b""")
        val SUCCESS_WORD = Regex("""\bSuccess\b""")
    }
}
