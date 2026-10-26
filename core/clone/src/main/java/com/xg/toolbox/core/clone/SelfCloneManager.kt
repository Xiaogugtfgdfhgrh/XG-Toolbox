package com.xg.toolbox.core.clone

import android.content.Context
import android.content.pm.PackageManager
import android.util.Base64
import com.xg.toolbox.core.clone.internal.ApkClonePatcher
import com.xg.toolbox.core.clone.internal.ApkCloneSigner
import com.xg.toolbox.core.clone.internal.CloneMigrationScript
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.ShellResult
import com.xg.toolbox.core.shell.SuPathStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

enum class ClonePhase { IDLE, PREPARING, PATCHING, SIGNING, INSTALLING, MIGRATING, DONE, FAILED }

data class CloneProgress(
    val phase: ClonePhase,
    val message: String,
    val newPackage: String? = null,
)

/**
 * Self-hiding clone orchestration.
 *
 * The current process cannot observe the final uninstall of itself. The method
 * therefore returns Ok only after the detached root script has installed the
 * patched APK, the package manager has been queried from this process, and the
 * script has received the commit marker. Data copy, new-app launch and old-app
 * uninstall continue in that detached script. DONE in [progress] means
 * "committed and scheduled", not "uninstall already observed".
 */
@Singleton
class SelfCloneManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val suPathStore: SuPathStore,
) {
    private val operationMutex = Mutex()
    @Volatile
    private var migrationProcess: Process? = null
    private val random = SecureRandom()
    private val _progress = MutableStateFlow(
        CloneProgress(ClonePhase.IDLE, "尚未开始隐藏自身")
    )

    val progress: StateFlow<CloneProgress> = _progress.asStateFlow()

    suspend fun hideSelf(): XgResult<String> {
        if (!operationMutex.tryLock()) {
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "隐藏自身正在进行中",
                "SelfCloneManager.hideSelf() 已被其他调用占用",
            )
        }
        try {
            return withContext(Dispatchers.IO + NonCancellable) {
                runHideSelf()
            }
        } finally {
            operationMutex.unlock()
        }
    }

    private suspend fun runHideSelf(): XgResult<String> {
        var workDir: File? = null
        var newPackage: String? = null
        var remoteApk: String? = null
        var remoteScript: String? = null
        var installedMarker: String? = null
        var goMarker: String? = null
        var cancelMarker: String? = null
        var statusFile: String? = null
        var scriptLaunched = false
        var triggered = false

        try {
            update(ClonePhase.PREPARING, "检查 Root 权限和安装包…")
            val root = shell.exec("id -u", root = true, timeoutMs = 15_000)
            if (!root.ok) {
                fail(
                    root.code.takeIf { it != 0 } ?: ErrorCode.ERR_CMD_FAILED,
                    "无法获取 Root 权限",
                    root.stdout.ifBlank { root.stderr },
                )
            }
            val uid = root.stdout.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.matches(Regex("[-0-9]+")) }
            if (uid != "0") {
                fail(
                    ErrorCode.ERR_SU_DENIED,
                    "Root 授权失败",
                    "id -u 返回 ${root.stdout.trim().take(120)}",
                )
            }

            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS,
            )
            val appInfo = packageInfo.applicationInfo
                ?: fail(ErrorCode.ERR_CMD_FAILED, "无法读取当前应用信息")
            val splitDirs = appInfo.splitSourceDirs
                ?.filterNotNull()
                ?.filter { it.isNotBlank() }
                .orEmpty()
            val splitNames = packageInfo.splitNames
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (splitDirs.isNotEmpty() || splitNames.isNotEmpty()) {
                fail(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    "当前是多 APK 拆分安装，暂不支持自克隆",
                    "splitSourceDirs=$splitDirs splitNames=$splitNames",
                )
            }

            val oldPackage = packageInfo.packageName
            if (oldPackage != context.packageName) {
                fail(ErrorCode.ERR_CMD_FAILED, "当前包名信息不一致")
            }
            val sourceApk = File(appInfo.sourceDir)
            if (!sourceApk.isFile || sourceApk.length() <= 0L) {
                fail(ErrorCode.ERR_CMD_FAILED, "无法读取已安装基础 APK")
            }
            val requiredSpace = sourceApk.length() * 4L + 64L * 1024L * 1024L
            val usable = context.cacheDir.usableSpace
            if (usable > 0L && usable < requiredSpace) {
                fail(
                    ErrorCode.ERR_NOT_ENOUGH_SPACE,
                    "缓存空间不足，至少需要 ${requiredSpace / 1024L / 1024L} MB",
                    "usableSpace=$usable required=$requiredSpace",
                )
            }

            newPackage = chooseUnusedPackage(oldPackage)
            update(ClonePhase.PREPARING, "复制基础 APK 并生成临时工作目录…", newPackage)
            val token = randomToken(8)
            val dir = File(context.cacheDir, "xg_self_clone_$token").apply {
                if (!mkdirs() && !isDirectory) {
                    throw IOException("无法创建缓存工作目录: $absolutePath")
                }
            }
            workDir = dir
            val baseCopy = File(dir, "base.apk")
            copyFile(sourceApk, baseCopy)
            if (baseCopy.length() != sourceApk.length()) {
                fail(ErrorCode.ERR_HASH_MISMATCH, "APK 复制长度校验失败")
            }

            update(
                ClonePhase.PATCHING,
                "仅原位替换 AndroidManifest.xml 中的包名（UTF-8/UTF-16LE）…",
                newPackage,
            )
            val unsignedApk = File(dir, "patched-unsigned.apk")
            val patchResult = ApkClonePatcher.patch(
                baseCopy,
                unsignedApk,
                oldPackage,
                newPackage,
            )
            if (patchResult.criticalEntries.none { it.name == "AndroidManifest.xml" && it.patched }) {
                fail(ErrorCode.ERR_PARSE_FAILED, "AndroidManifest.xml 未实际改写")
            }
            // DEX and resources.arsc stay byte-identical by design. Device
            // testing showed DEX string-pool edits violate string_ids ordering.
            baseCopy.delete()
            if (!unsignedApk.isFile || unsignedApk.length() <= 0L) {
                fail(ErrorCode.ERR_CMD_FAILED, "重打包 APK 为空")
            }

            update(ClonePhase.SIGNING, "生成随机 RSA-2048 密钥与自签名证书，v1/v2/v3 签名…", newPackage)
            val signedApk = File(dir, "clone-signed.apk")
            val signature = ApkCloneSigner.sign(
                unsignedApk,
                signedApk,
                appInfo.minSdkVersion.takeIf { it > 0 } ?: 30,
            )
            if (!signature.verifiedV2 && !signature.verifiedV3) {
                fail(ErrorCode.ERR_CMD_FAILED, "新 APK 未通过 v2/v3 签名校验")
            }
            unsignedApk.delete()

            val archive = packageManager.getPackageArchiveInfo(
                signedApk.absolutePath,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_PROVIDERS,
            ) ?: fail(ErrorCode.ERR_PARSE_FAILED, "无法解析已签名克隆 APK")
            if (archive.packageName != newPackage) {
                fail(
                    ErrorCode.ERR_PARSE_FAILED,
                    "克隆 APK 包名校验失败",
                    "expected=$newPackage actual=${archive.packageName}",
                )
            }
            val mainActivity = archive.activities?.firstOrNull { it.name == MAIN_ACTIVITY }
                ?: fail(ErrorCode.ERR_PARSE_FAILED, "克隆 APK 缺少 $MAIN_ACTIVITY 组件")
            if (archive.applicationInfo?.className != APP_CLASS) {
                fail(
                    ErrorCode.ERR_PARSE_FAILED,
                    "克隆 APK 的 Application 类异常",
                    "expected=$APP_CLASS actual=${archive.applicationInfo?.className}",
                )
            }
            val badAuthorities = archive.providers.orEmpty()
                .flatMap { provider ->
                    provider.authority?.split(';').orEmpty()
                }
                .filter { it.contains(oldPackage) }
            if (badAuthorities.isNotEmpty()) {
                fail(
                    ErrorCode.ERR_CMD_FAILED,
                    "克隆 APK 仍包含旧包名 authority，已中止",
                    "authorities=$badAuthorities",
                )
            }
            // Keep this local check explicit: the new manifest package is the
            // clone package while Java/Kotlin class descriptors stay unchanged.
            if (mainActivity.name != MAIN_ACTIVITY) {
                fail(ErrorCode.ERR_PARSE_FAILED, "MainActivity 解析异常")
            }

            update(ClonePhase.INSTALLING, "上传克隆 APK 并等待 root 安装…", newPackage)
            remoteApk = "/data/local/tmp/xg_clone_$token.apk"
            remoteScript = "/data/local/tmp/xg_clone_$token.sh"
            installedMarker = "/data/local/tmp/xg_clone_$token.installed"
            goMarker = "/data/local/tmp/xg_clone_$token.go"
            cancelMarker = "/data/local/tmp/xg_clone_$token.cancel"
            statusFile = "/data/local/tmp/xg_clone_$token.status"
            val remoteLog = "/data/local/tmp/xg_clone_$token.log"

            val copied = shell.exec(
                "cat ${q(signedApk.absolutePath)} > ${q(remoteApk)} && chmod 0644 ${q(remoteApk)}",
                root = true,
                timeoutMs = 180_000,
            )
            if (!copied.ok) {
                fail(
                    ErrorCode.ERR_CMD_FAILED,
                    "上传克隆 APK 失败",
                    copied.stdout.ifBlank { copied.stderr },
                )
            }
            val remoteBytes = shell.exec(
                "wc -c < ${q(remoteApk)}",
                root = true,
                timeoutMs = 15_000,
            )
            if (remoteBytes.stdout.trim().toLongOrNull() != signedApk.length()) {
                fail(
                    ErrorCode.ERR_HASH_MISMATCH,
                    "设备端 APK 长度校验失败",
                    "local=${signedApk.length()} remote=${remoteBytes.stdout.trim()}",
                )
            }

            val script = CloneMigrationScript.build(
                oldPackage,
                newPackage,
                MAIN_ACTIVITY,
                remoteApk!!,
                remoteScript!!,
                installedMarker!!,
                goMarker!!,
                cancelMarker!!,
                statusFile!!,
                remoteLog,
            )
            val scriptBytes = script.toByteArray(Charsets.UTF_8)
            val encodedScript = Base64.encodeToString(scriptBytes, Base64.NO_WRAP)
            val written = shell.exec(
                "printf '%s' ${q(encodedScript)} | base64 -d > ${q(remoteScript)} && chmod 0700 ${q(remoteScript)}",
                root = true,
                timeoutMs = 20_000,
            )
            if (!written.ok) {
                fail(
                    ErrorCode.ERR_CMD_FAILED,
                    "写入设备迁移脚本失败",
                    written.stdout.ifBlank { written.stderr },
                )
            }
            val scriptBytesOnDevice = shell.exec(
                "wc -c < ${q(remoteScript)}",
                root = true,
                timeoutMs = 15_000,
            ).stdout.trim().toLongOrNull()
            if (scriptBytesOnDevice != scriptBytes.size.toLong()) {
                fail(
                    ErrorCode.ERR_HASH_MISMATCH,
                    "设备迁移脚本长度校验失败",
                    "local=${scriptBytes.size} remote=${scriptBytesOnDevice ?: "null"}",
                )
            }

            if (!launchMigrationProcess(remoteScript!!)) {
                fail(
                    ErrorCode.ERR_CMD_FAILED,
                    "启动设备迁移脚本失败",
                    "root shell did not stay alive",
                )
            }
            scriptLaunched = true

            val pollCommand = buildString {
                append("i=0; while [ \"\$i\" -lt 120 ]; do ")
                append("if [ -f ${q(installedMarker!!)} ]; then echo XG_CLONE_INSTALLED; exit 0; fi; ")
                append("if [ -f ${q(statusFile!!)} ]; then cat ${q(statusFile!!)}; exit 20; fi; ")
                append("i=\$((i + 1)); sleep 1; done; ")
                append("echo XG_CLONE_INSTALL_TIMEOUT; exit 21")
            }
            val poll = shell.exec(pollCommand, root = true, timeoutMs = 135_000)
            if (!poll.ok || !poll.stdout.contains("XG_CLONE_INSTALLED")) {
                fail(
                    ErrorCode.ERR_CMD_FAILED,
                    "新包安装或 pm path 校验失败",
                    poll.stdout.ifBlank { poll.stderr },
                )
            }

            val path = shell.exec(
                "pm path --user 0 ${q(newPackage)}",
                root = true,
                timeoutMs = 15_000,
            )
            if (!path.ok || !path.stdout.lineSequence().any { it.startsWith("package:") }) {
                fail(
                    ErrorCode.ERR_CMD_FAILED,
                    "当前进程无法确认新包已安装",
                    path.stdout.ifBlank { path.stderr },
                )
            }

            val installedInfo = runCatching {
                packageManager.getPackageInfo(newPackage, 0)
            }.getOrNull()
            if (installedInfo != null) {
                if (installedInfo.packageName != newPackage) {
                    fail(ErrorCode.ERR_CMD_FAILED, "已安装包名校验失败")
                }
                val installedCert = installedInfo.signingInfo
                    ?.apkContentsSigners
                    ?.firstOrNull()
                    ?.toByteArray()
                if (installedCert != null) {
                    val installedFingerprint = sha256Hex(installedCert)
                    if (installedFingerprint != signature.certificateSha256) {
                        fail(
                            ErrorCode.ERR_HASH_MISMATCH,
                            "新包签名证书指纹不匹配",
                            "expected=${signature.certificateSha256} actual=$installedFingerprint",
                        )
                    }
                }
            }

            update(
                ClonePhase.MIGRATING,
                "新包已安装并校验，提交后台迁移与旧包卸载…",
                newPackage,
            )
            if (goMarker == null) {
                fail(ErrorCode.ERR_CMD_FAILED, "内部错误：提交标记路径为空")
            }
            val committed = shell.exec(
                "echo go > ${q(goMarker)}",
                root = true,
                timeoutMs = 10_000,
            )
            if (!committed.ok) {
                fail(
                    ErrorCode.ERR_CMD_FAILED,
                    "提交后台迁移失败",
                    committed.stdout.ifBlank { committed.stderr },
                )
            }
            triggered = true
            update(
                ClonePhase.DONE,
                "新包 $newPackage 已安装并通过签名/对齐校验。root 后台任务将在数秒内复制数据、启动新包并卸载旧包；旧进程即将停止。请以新包内的迁移标记为最终结果。",
                newPackage,
            )
            return XgResult.Ok(newPackage)
        } catch (t: Throwable) {
            if (!triggered) {
                cleanupRemoteBeforeCommit(
                    shell = shell,
                    scriptLaunched = scriptLaunched,
                    remoteApk = remoteApk,
                    remoteScript = remoteScript,
                    installedMarker = installedMarker,
                    goMarker = goMarker,
                    cancelMarker = cancelMarker,
                    statusFile = statusFile,
                )
            }
            XgLog.e(TAG, "hideSelf failed", t)
            val failure = t as? CloneFailure
            val code = failure?.code ?: ErrorCode.ERR_CMD_FAILED
            val summary = failure?.summary ?: "隐藏自身失败"
            val detail = failure?.detail ?: cloneThrowableDetail(t)
            val display = if (detail.isBlank()) summary else "$summary：${detail.lineSequence().first().take(180)}"
            _progress.value = CloneProgress(ClonePhase.FAILED, display, newPackage)
            return XgResult.Err(code, display, detail)
        } finally {
            workDir?.deleteRecursively()
        }
    }

    private suspend fun chooseUnusedPackage(oldPackage: String): String {
        val segments = oldPackage.split('.')
        if (segments.size < 2 || segments.any { it.isEmpty() }) {
            throw IOException("当前包名不是合法的分段包名: $oldPackage")
        }
        val listed = shell.exec("pm list packages", root = true, timeoutMs = 20_000)
        if (!listed.ok) {
            throw IOException("pm list packages 失败: ${listed.stdout.ifBlank { listed.stderr }}")
        }
        val installed = listed.stdout.lineSequence()
            .map { it.trim().removePrefix("package:").trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        repeat(256) {
            val candidate = segments.joinToString(".") { segment ->
                buildString(segment.length) {
                    append(randomFrom(LOWER))
                    repeat(segment.length - 1) {
                        append(randomFrom(ALNUM))
                    }
                }
            }
            if (candidate != oldPackage && candidate !in installed) {
                return candidate
            }
        }
        throw IOException("连续生成 256 个候选包名均已被占用")
    }

    private suspend fun launchMigrationProcess(scriptPath: String): Boolean {
        val command = ". ${q(scriptPath)}"
        val su = suPathStore.effectivePath.ifBlank { "su" }
        val candidates = listOf(
            listOf(su, "-M", "-Z", "u:r:ksu:s0", "-c", command),
            listOf(su, "-Z", "u:r:ksu:s0", "-c", command),
            listOf(su, "-c", command),
        )
        val log = File(context.cacheDir, "clone-migration.log")
        for (args in candidates) {
            try {
                val process = ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                    .start()
                kotlinx.coroutines.delay(700)
                if (process.isAlive) {
                    migrationProcess = process
                    return true
                }
                process.destroy()
            } catch (_: Throwable) {
            }
        }
        return false
    }

    private fun update(phase: ClonePhase, message: String, newPackage: String? = null) {
        _progress.value = CloneProgress(phase, message, newPackage)
    }

    private fun fail(code: Int, summary: String, detail: String? = null): Nothing {
        throw CloneFailure(code, summary, detail)
    }

    private fun randomFrom(chars: CharArray): Char = chars[random.nextInt(chars.size)]

    private fun randomToken(bytes: Int): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        return buildString(data.size * 2) {
            data.forEach { append(String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF)) }
        }
    }

    private fun copyFile(source: File, destination: File) {
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read > 0) output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
        } catch (t: Throwable) {
            throw IOException("复制 APK 失败: ${t.message}", t)
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { append(String.format(Locale.ROOT, "%02x", it.toInt() and 0xFF)) }
        }
    }

    private suspend fun cleanupRemoteBeforeCommit(
        shell: Shell,
        scriptLaunched: Boolean,
        remoteApk: String?,
        remoteScript: String?,
        installedMarker: String?,
        goMarker: String?,
        cancelMarker: String?,
        statusFile: String?,
    ) {
        // The detached script owns cleanup after launch: the cancel marker makes
        // it uninstall the not-yet-committed new package and remove temp files.
        if (scriptLaunched) {
            if (cancelMarker != null) {
                runCatching { shell.exec("echo cancel > ${q(cancelMarker)}", root = true, timeoutMs = 10_000) }
            }
            return
        }
        val paths = listOfNotNull(remoteApk, remoteScript, installedMarker, goMarker, cancelMarker, statusFile)
        if (paths.isEmpty()) return
        runCatching {
            shell.exec(
                "rm -f ${paths.joinToString(" ") { q(it) }}",
                root = true,
                timeoutMs = 20_000,
            )
        }
    }

    private fun q(value: String): String = CloneMigrationScript.shellQuote(value)

    private class CloneFailure(
        val code: Int,
        val summary: String,
        val detail: String?,
    ) : Exception(summary)

    private companion object {
        val LOWER = "abcdefghijklmnopqrstuvwxyz".toCharArray()
        val ALNUM = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray()
        const val MAIN_ACTIVITY = "com.xg.toolbox.MainActivity"
        const val APP_CLASS = "com.xg.toolbox.XgApp"
        const val TAG = "SelfCloneManager"
    }
}






internal fun cloneThrowableDetail(t: Throwable): String = buildString {
    var current: Throwable? = t
    var depth = 0
    while (current != null && depth < 8) {
        if (depth > 0) append("\ncause: ")
        append(current.javaClass.name)
        current.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
        current = current.cause
        depth++
    }
}

