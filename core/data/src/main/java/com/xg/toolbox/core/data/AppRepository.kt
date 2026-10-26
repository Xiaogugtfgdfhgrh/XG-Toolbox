package com.xg.toolbox.core.data

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import com.xg.toolbox.core.common.ErrorCatalog
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.common.SingleFlightResultCache
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.ShellResult
import com.xg.toolbox.core.shell.rootError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Singleton
class AppRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val audit: AuditRepository,
    private val snapshots: SnapshotRepository,
    private val rootStatus: RootStatusRepository,
) {

    private val selfPackage: String = context.packageName
    private val protectedPackages: Set<String> = setOf(
        selfPackage,
        "android",
        "com.android.systemui",
        "com.android.phone",
        SYSTEM_SERVER,
    )

    private val listCache = SingleFlightResultCache<List<AppEntry>>()

    
    suspend fun list(forceRefresh: Boolean = false): XgResult<List<AppEntry>> =
        listCache.get(forceRefresh) {
            withContext(Dispatchers.IO) {
                try {
                    val packageManager = context.packageManager
                    val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNING_CERTIFICATES
                    val installed = installedPackagesRootFirst(packageManager, flags)
                    val frozen = frozenPackagesFromShell()
                    val entries = installed
                        .mapNotNull { info -> toEntry(packageManager, info, frozen) }
                        .sortedBy { it.packageName.lowercase() }
                    XgResult.Ok(entries)
                } catch (t: Throwable) {
                    XgLog.e(TAG, "应用列表读取失败", t)
                    XgResult.Err(ErrorCode.ERR_CMD_FAILED, "应用列表读取失败", t.message)
                }
            }
        }

    suspend fun invalidateListCache() = listCache.invalidate()

    
    private suspend fun installedPackagesRootFirst(
        packageManager: PackageManager,
        flags: Int,
    ): List<android.content.pm.PackageInfo> {
        if (rootStatus.state.value !is RootState.Granted) {
            return packageManager.getInstalledPackages(flags)
        }
        val result = shell.exec("pm list packages", root = true, timeoutMs = 10_000)
        if (!result.ok) return packageManager.getInstalledPackages(flags)
        val names = result.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (names.isEmpty()) return packageManager.getInstalledPackages(flags)
        return names.mapNotNull { name ->
            runCatching { packageManager.getPackageInfo(name, flags) }.getOrNull()
        }
    }

    suspend fun detail(pkg: String): XgResult<AppDetail> = withContext(Dispatchers.IO) {
        if (!isValidPackageName(pkg)) {
            return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
        }
        try {
            val packageManager = context.packageManager
            val info = packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val app = info.applicationInfo
                ?: return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "应用信息不完整：$pkg")
            val signers = info.signingInfo?.apkContentsSigners
            val signingSha256 = signers?.firstOrNull()
                ?.let { Formatters.sha256Hex(it.toByteArray()) }
                .orEmpty()
            XgResult.Ok(
                AppDetail(
                    packageName = pkg,
                    label = labelOf(packageManager, app),
                    signingSha256 = signingSha256,
                    signatureCount = signers?.size ?: 0,
                    sourceDir = app.sourceDir.orEmpty(),
                    dataDir = app.dataDir.orEmpty(),
                    nativeLibraryDir = app.nativeLibraryDir,
                    primaryAbi = shellPrimaryAbi(pkg) ?: abiFromNativeLibraryDir(app),
                    abis = Build.SUPPORTED_ABIS.toList(),
                    versionName = info.versionName.orEmpty(),
                    versionCode = info.longVersionCode,
                    firstInstallTime = info.firstInstallTime,
                    lastUpdateTime = info.lastUpdateTime,
                    uid = app.uid,
                    isSystem = isSystemApp(app),
                    isFrozen = frozenState(pkg) ?: !app.enabled,
                ),
            )
        } catch (t: PackageManager.NameNotFoundException) {
            XgResult.Err(ErrorCode.ERR_CMD_FAILED, "应用未安装：$pkg", t.message)
        } catch (t: Throwable) {
            XgLog.e(TAG, "读取应用详情失败: $pkg", t)
            XgResult.Err(ErrorCode.ERR_CMD_FAILED, "读取应用详情失败", t.message)
        }
    }

    
    suspend fun appUsage(pkg: String): XgResult<AppUsage> = withContext(Dispatchers.IO) {
        if (!isValidPackageName(pkg)) {
            return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
        }
        val app = try {
            context.packageManager.getApplicationInfo(pkg, 0)
        } catch (t: PackageManager.NameNotFoundException) {
            return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "应用未安装：$pkg", t.message)
        }
        val memory = readProcessMemory(pkg)
        val traffic = queryTraffic(app.uid)
        XgResult.Ok(
            AppUsage(
                memoryBytes = memory.bytes,
                memoryRunning = memory.running,
                trafficRxBytes = traffic.rxBytes,
                trafficTxBytes = traffic.txBytes,
                trafficReason = traffic.reason,
                windowDays = TRAFFIC_WINDOW_DAYS,
            ),
        )
    }

    suspend fun freeze(pkg: String): XgResult<Unit> = withContext(Dispatchers.IO) {
        writeGuard(pkg)?.let { return@withContext blocked(ACTION_FREEZE, pkg, it) }
        val snapshotId = snapshots.create(
            kind = KIND_APP_STATE,
            target = pkg,
            payload = appStatePayload(frozen = false),
            ttlMs = APP_STATE_TTL_MS,
        )
        val command = "pm disable-user --user 0 ${quote(pkg)}"
        val result = shell.exec(command, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        val frozen = if (result.ok) frozenState(pkg) else null
        val success = result.ok && frozen == true
        recordAudit(
            action = ACTION_FREEZE,
            target = pkg,
            commands = command,
            result = if (success) AuditResult.OK else AuditResult.FAILED,
            message = if (success) null else failureMessage(result, frozen),
            snapshotId = snapshotId,
        )
        if (success) {
            invalidateListCache()
            XgResult.Ok(Unit)
        } else {
            commandError("冻结应用失败", result, readbackNote = readbackNote(frozen, expected = true))
        }
    }

    suspend fun unfreeze(pkg: String): XgResult<Unit> = withContext(Dispatchers.IO) {
        writeGuard(pkg)?.let { return@withContext blocked(ACTION_UNFREEZE, pkg, it) }
        val snapshotId = snapshots.create(
            kind = KIND_APP_STATE,
            target = pkg,
            payload = appStatePayload(frozen = true),
            ttlMs = APP_STATE_TTL_MS,
        )
        val command = "pm enable ${quote(pkg)}"
        val result = shell.exec(command, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        val frozen = if (result.ok) frozenState(pkg) else null
        val success = result.ok && frozen == false
        recordAudit(
            action = ACTION_UNFREEZE,
            target = pkg,
            commands = command,
            result = if (success) AuditResult.OK else AuditResult.FAILED,
            message = if (success) null else failureMessage(result, frozen),
            snapshotId = snapshotId,
        )
        if (success) {
            invalidateListCache()
            XgResult.Ok(Unit)
        } else {
            commandError("解冻应用失败", result, readbackNote = readbackNote(frozen, expected = false))
        }
    }

    
    suspend fun uninstall(pkg: String): XgResult<UninstallOutcome> = withContext(Dispatchers.IO) {
        writeGuard(pkg)?.let { return@withContext blocked(ACTION_UNINSTALL, pkg, it) }
        val wasSystem = isSystemPackage(pkg) ?: false

        val pathResult = shell.exec("pm path ${quote(pkg)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        val apkPaths = parseApkPaths(pathResult.stdout)
        if (!pathResult.ok || apkPaths.isEmpty()) {
            recordAudit(ACTION_UNINSTALL, pkg, pathResult.commandLine(), AuditResult.FAILED, "无法获取 APK 路径", null)
            return@withContext XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "卸载失败：无法获取应用安装路径",
                detailOf(pathResult),
            )
        }

        val sizeResult = shell.exec(
            "stat -c %s " + apkPaths.joinToString(" ") { quote(it) },
            root = true,
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        val totalBytes = parseLongLines(sizeResult.stdout).sum()
        if (sizeResult.ok && totalBytes > MAX_BACKUP_BYTES) {
            val message = "应用体积 ${Formatters.bytes(totalBytes)} 超过 200MB 备份上限，已中止卸载"
            recordAudit(ACTION_UNINSTALL, pkg, sizeResult.commandLine(), AuditResult.FAILED, message, null)
            return@withContext XgResult.Err(
                ErrorCode.ERR_BACKUP_FAILED,
                message,
                "卸载操作未提供继续选项，已中止",
            )
        }

        val backupDir = "$BACKUP_ROOT/$pkg"
        val backupCommand = "mkdir -p ${quote(backupDir)} && cp -a " +
            apkPaths.joinToString(" ") { quote(it) } + " " + quote("$backupDir/")
        val backupResult = shell.exec(backupCommand, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        if (!backupResult.ok) {
            recordAudit(ACTION_UNINSTALL, pkg, backupCommand, AuditResult.FAILED, "备份 APK 失败", null)
            return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "备份 APK 失败，已中止卸载", detailOf(backupResult))
        }
        val countResult = shell.exec(
            "ls -1 " + quote("$backupDir/") + "*.apk 2>/dev/null | wc -l",
            root = true,
            timeoutMs = COMMAND_TIMEOUT_MS,
        )
        val copiedCount = parseLeadingInt(countResult.stdout) ?: 0
        if (copiedCount < apkPaths.size) {
            val message = "备份不完整（$copiedCount/${apkPaths.size}），已中止卸载"
            recordAudit(ACTION_UNINSTALL, pkg, backupCommand, AuditResult.FAILED, message, null)
            return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, message, detailOf(countResult))
        }
        val duKb = parseDuKb(
            shell.exec("du -sk ${quote(backupDir)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS).stdout,
        )
        if (duKb != null && duKb * 1024 > MAX_BACKUP_BYTES) {
            removeBackupDir(backupDir)
            val message = "备份体积 ${Formatters.bytes(duKb * 1024)} 超过 200MB 上限，已清理备份并中止卸载"
            recordAudit(ACTION_UNINSTALL, pkg, backupCommand, AuditResult.FAILED, message, null)
            return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, message, null)
        }

        val payload = buildJsonObject {
            put("backupDir", backupDir)
            put("apkCount", copiedCount)
            put("wasSystem", wasSystem)
            putJsonArray("apkPaths") { apkPaths.forEach { add(it) } }
        }.toString()
        val snapshotId = snapshots.create(
            kind = KIND_APP_BACKUP,
            target = pkg,
            payload = payload,
            ttlMs = APP_BACKUP_TTL_MS,
        )

        val uninstallCommand = "pm uninstall --user 0 ${quote(pkg)}"
        val uninstallResult = shell.exec(uninstallCommand, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
        val installed = installedForUser(pkg)
        val success = uninstallResult.ok && installed == false
        recordAudit(
            action = ACTION_UNINSTALL,
            target = pkg,
            commands = uninstallCommand,
            result = if (success) AuditResult.OK else AuditResult.FAILED,
            message = if (success) "备份目录：$backupDir（$copiedCount 个 APK）" else failureMessage(uninstallResult, installed),
            snapshotId = snapshotId,
        )
        if (success) {
            invalidateListCache()
            XgResult.Ok(UninstallOutcome(pkg, backupDir, copiedCount, wasSystem))
        } else {
            commandError("卸载失败", uninstallResult, readbackNote = readbackNote(installed, expected = false))
        }
    }

    
    suspend fun undoUninstall(pkg: String): XgResult<Unit> = withContext(Dispatchers.IO) {
        writeGuard(pkg)?.let { return@withContext blocked(ACTION_UNDO_UNINSTALL, pkg, it) }
        val snapshot = snapshots.latest(KIND_APP_BACKUP, pkg)
            ?: return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "未找到该应用的卸载备份记录")
        if (snapshot.expiresAt < System.currentTimeMillis()) {
            return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "备份已过期（卸载撤销仅 24 小时有效）")
        }
        val payload = runCatching { json.parseToJsonElement(snapshot.payload).jsonObject }.getOrNull()
            ?: return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "备份快照内容损坏，无法撤销")
        val backupDir = payload["backupDir"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "备份快照缺少备份目录")
        if (!isSafeBackupDir(backupDir)) {
            return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, "备份路径非法，拒绝执行撤销")
        }
        val wasSystem = payload["wasSystem"]?.jsonPrimitive?.booleanOrNull ?: false
        val command: String
        if (wasSystem) {
            command = "pm install-existing --user 0 ${quote(pkg)}"
        } else {
            val listResult = shell.exec(
                "ls -1 " + quote("$backupDir/") + "*.apk 2>/dev/null",
                root = true,
                timeoutMs = COMMAND_TIMEOUT_MS,
            )
            val apks = listResult.stdout.lineSequence()
                .map { it.trim() }
                .filter { it.endsWith(".apk") }
                .map { "$backupDir/$it" }
                .toList()
            if (apks.isEmpty()) {
                recordAudit(
                    ACTION_UNDO_UNINSTALL,
                    pkg,
                    listResult.commandLine(),
                    AuditResult.FAILED,
                    "备份文件不存在",
                    snapshot.id,
                )
                return@withContext XgResult.Err(
                    ErrorCode.ERR_BACKUP_FAILED,
                    "备份 APK 已不存在（/data/local/tmp 可能被系统清理）",
                )
            }
            command = "pm install -r --user 0 " + apks.joinToString(" ") { quote(it) }
        }
        val result = shell.exec(command, root = true, timeoutMs = INSTALL_TIMEOUT_MS)
        val installed = installedForUser(pkg)
        val success = result.ok && installed == true
        recordAudit(
            action = ACTION_UNDO_UNINSTALL,
            target = pkg,
            commands = command,
            result = if (success) AuditResult.OK else AuditResult.FAILED,
            message = if (success) "已从 $backupDir 恢复" else failureMessage(result, installed),
            snapshotId = snapshot.id,
        )
        if (success) {
            invalidateListCache()
            XgResult.Ok(Unit)
        } else {
            commandError("撤销卸载失败", result, readbackNote = readbackNote(installed, expected = true))
        }
    }

    
    suspend fun setComponentEnabled(pkg: String, component: String, enabled: Boolean): XgResult<Unit> =
        withContext(Dispatchers.IO) {
            writeGuard(pkg)?.let { return@withContext blocked(ACTION_COMPONENT, pkg, it) }
            if (component.isBlank()) {
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "组件名不能为空")
            }
            val componentPath = normalizeComponent(pkg, component)
            val command = "pm ${if (enabled) "enable" else "disable"} ${quote(componentPath)}"
            val snapshotId = snapshots.create(
                kind = KIND_APP_STATE,
                target = pkg,
                payload = componentPayload(componentPath, currentEnabled = !enabled),
                ttlMs = APP_STATE_TTL_MS,
            )
            val result = shell.exec(command, root = true, timeoutMs = COMMAND_TIMEOUT_MS)
            val disabled = componentDisabled(pkg, componentPath)
            val success = disabled == !enabled
            recordAudit(
                action = ACTION_COMPONENT,
                target = pkg,
                commands = command,
                result = if (success) AuditResult.OK else AuditResult.FAILED,
                message = if (success) null else failureMessage(result, disabled),
                snapshotId = snapshotId,
            )
            if (success) {
                invalidateListCache()
                XgResult.Ok(Unit)
            } else {
                commandError(
                    if (enabled) "启用组件失败" else "禁用组件失败",
                    result,
                    readbackNote = if (disabled == null) "回读失败，无法确认组件状态" else null,
                )
            }
        }

    
    fun isProtected(pkg: String): Boolean = pkg in protectedPackages

    
    private suspend fun readProcessMemory(pkg: String): MemoryUsage {
        val command = "pid=\$(pidof " + quote(pkg) +
            "); if [ -n \"\$pid\" ]; then cat /proc/\$pid/statm; else echo " + MEMORY_NO_PID + "; fi"
        val result = shell.exec(command, root = true, timeoutMs = READ_TIMEOUT_MS)
        if (!result.ok) return MemoryUsage(running = false, bytes = null)
        val text = result.stdout.trim()
        if (text.isEmpty() || text == MEMORY_NO_PID) return MemoryUsage(running = false, bytes = null)
        val residentPages = text.lineSequence()
            .firstOrNull()
            ?.trim()
            ?.split(WHITESPACE)
            ?.getOrNull(1)
            ?.toLongOrNull()
        val bytes = residentPages?.let { pages -> pages * Os.sysconf(OsConstants._SC_PAGESIZE) }
        return MemoryUsage(running = true, bytes = bytes)
    }

    
    private fun queryTraffic(uid: Int): TrafficUsage {
        if (!hasUsageStatsAccess()) return TrafficUsage(null, null, "未授权")
        val manager = context.getSystemService(NetworkStatsManager::class.java)
            ?: return TrafficUsage(null, null, "系统不支持")
        val end = System.currentTimeMillis()
        val start = end - TRAFFIC_WINDOW_DAYS * 24L * 60 * 60 * 1000
        var rx = 0L
        var tx = 0L
        var hasData = false
        var securityBlocked = false
        for (networkType in NETWORK_TYPES) {
            try {
                manager.queryDetailsForUid(networkType, null, start, end, uid).use { stats ->
                    val bucket = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(bucket)
                        rx += bucket.rxBytes
                        tx += bucket.txBytes
                        hasData = true
                    }
                }
            } catch (_: SecurityException) {
                securityBlocked = true
            } catch (t: Throwable) {
                XgLog.e(TAG, "流量查询失败 (networkType=$networkType)", t)
            }
        }
        return when {
            hasData -> TrafficUsage(rx, tx, null)
            securityBlocked -> TrafficUsage(null, null, "未授权")
            else -> TrafficUsage(null, null, "无数据")
        }
    }

    private fun hasUsageStatsAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    
    private suspend fun shellPrimaryAbi(pkg: String): String? {
        if (rootStatus.state.value !is RootState.Granted) return null
        val result = shell.exec(
            "dumpsys package ${quote(pkg)} | grep -m1 primaryCpuAbi",
            root = true,
            timeoutMs = READ_TIMEOUT_MS,
        )
        if (!result.ok) return null
        val line = result.stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith(PRIMARY_ABI_PREFIX) }
            ?: return null
        return line.removePrefix(PRIMARY_ABI_PREFIX).trim().ifBlank { null }
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

    
    private fun writeGuard(pkg: String): XgResult.Err? {
        if (!isValidPackageName(pkg)) {
            return XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
        }
        if (isProtected(pkg)) {
            return XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, "受保护应用禁止操作：$pkg")
        }
        return rootStatus.state.value.rootError()
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

    private suspend fun <T> blocked(action: String, pkg: String, error: XgResult.Err): XgResult<T> {
        recordAudit(action, pkg, "", AuditResult.FAILED, error.message, null)
        return error
    }

    private suspend fun frozenPackagesFromShell(): Set<String>? {
        if (rootStatus.state.value !is RootState.Granted) return null
        val result = shell.exec(FROZEN_LIST_COMMAND, root = true, timeoutMs = READ_TIMEOUT_MS)
        if (!result.ok) return null
        return parsePackageNames(result.stdout)
    }

    
    private suspend fun frozenState(pkg: String): Boolean? {
        if (rootStatus.state.value !is RootState.Granted) return null
        val result = shell.exec(FROZEN_LIST_COMMAND, root = true, timeoutMs = READ_TIMEOUT_MS)
        if (!result.ok) return null
        return parsePackageNames(result.stdout).contains(pkg)
    }

    
    private suspend fun installedForUser(pkg: String): Boolean? {
        val result = shell.exec("pm list packages --user 0", root = true, timeoutMs = READ_TIMEOUT_MS)
        if (!result.ok) return null
        return parsePackageNames(result.stdout).contains(pkg)
    }

    
    private suspend fun componentDisabled(pkg: String, componentPath: String): Boolean? {
        if (rootStatus.state.value !is RootState.Granted) return null
        val result = shell.exec("dumpsys package ${quote(pkg)}", root = true, timeoutMs = READ_TIMEOUT_MS)
        if (!result.ok) return null
        val targetClass = targetClassName(pkg, componentPath)
        val disabled = parseDumpSection(result.stdout, "disabledComponents")
        return disabled.any { entry ->
            entry == targetClass || entry == componentPath || entry == "$pkg/$targetClass"
        }
    }

    private fun parseDumpSection(dump: String, section: String): Set<String> {
        val lines = dump.lines()
        val startIndex = lines.indexOfFirst { it.trim() == "$section:" }
        if (startIndex < 0) return emptySet()
        val baseIndent = lines[startIndex].indexOfFirst { it != ' ' }
        val entries = mutableSetOf<String>()
        for (index in startIndex + 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            val indent = line.indexOfFirst { it != ' ' }
            if (indent <= baseIndent) break
            entries += line.trim()
        }
        return entries
    }

    private fun normalizeComponent(pkg: String, component: String): String = when {
        component.startsWith("$pkg/") -> component
        component.startsWith(".") -> "$pkg/$component"
        else -> "$pkg/$component"
    }

    private fun targetClassName(pkg: String, componentPath: String): String {
        val raw = componentPath.substringAfter('/')
        return when {
            raw.startsWith(".") -> pkg + raw
            raw.contains('.') -> raw
            else -> "$pkg.$raw"
        }
    }

    private fun toEntry(
        packageManager: PackageManager,
        info: PackageInfo,
        frozenPackages: Set<String>?,
    ): AppEntry? {
        val app = info.applicationInfo ?: return null
        val packageName = info.packageName
        val frozen = frozenPackages?.contains(packageName) ?: !app.enabled
        return AppEntry(
            packageName = packageName,
            label = labelOf(packageManager, app),
            versionName = info.versionName.orEmpty(),
            versionCode = info.longVersionCode,
            isSystem = isSystemApp(app),
            isFrozen = frozen,
            installedAt = info.firstInstallTime,
            updatedAt = info.lastUpdateTime,
            apkSizeBytes = apkSizeBytes(app),
            primaryAbi = abiFromNativeLibraryDir(app),
            uid = app.uid,
        )
    }

    private fun labelOf(packageManager: PackageManager, app: ApplicationInfo): String =
        runCatching { packageManager.getApplicationLabel(app).toString() }
            .getOrElse { app.packageName }

    private fun apkSizeBytes(app: ApplicationInfo): Long {
        val paths = buildList {
            add(app.sourceDir)
            app.splitSourceDirs?.forEach { split -> add(split) }
        }
        return paths.sumOf { path -> runCatching { File(path).length() }.getOrDefault(0L) }
    }

    private fun isSystemApp(app: ApplicationInfo): Boolean =
        (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0

    private fun isSystemPackage(pkg: String): Boolean? = try {
        isSystemApp(context.packageManager.getApplicationInfo(pkg, 0))
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun isValidPackageName(pkg: String): Boolean = PACKAGE_NAME.matches(pkg)

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun parsePackageNames(output: String): Set<String> = output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(PACKAGE_PREFIX) }
        .map { it.removePrefix(PACKAGE_PREFIX) }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun parseApkPaths(output: String): List<String> = output.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(PACKAGE_PREFIX) }
        .map { it.removePrefix(PACKAGE_PREFIX) }
        .filter { it.endsWith(".apk") }
        .toList()

    private fun parseLongLines(output: String): List<Long> =
        output.lineSequence().mapNotNull { it.trim().toLongOrNull() }.toList()

    private fun parseLeadingInt(output: String): Int? =
        output.trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()

    private fun parseDuKb(output: String): Long? = output.trim().lineSequence()
        .firstOrNull()
        ?.trim()
        ?.substringBefore('\t')
        ?.trim()
        ?.toLongOrNull()

    private fun detailOf(result: ShellResult): String {
        val raw = result.stderr.ifBlank { result.stdout }.trim()
        if (raw.isEmpty()) return if (result.ok) "" else ErrorCatalog.message(result.code)
        return raw.lineSequence().take(3).joinToString(" | ").take(500)
    }

    private fun failureMessage(result: ShellResult, readback: Boolean?): String {
        val note = when (readback) {
            true -> "回读状态与预期不符"
            false -> "回读显示命令未生效"
            null -> "回读失败"
        }
        val detail = detailOf(result)
        return if (detail.isBlank()) note else "$note：$detail"
    }

    private fun readbackNote(readback: Boolean?, expected: Boolean): String? = when {
        readback == null -> "回读失败，无法确认最终状态"
        readback != expected -> "回读状态与预期不一致"
        else -> null
    }

    private fun commandError(label: String, result: ShellResult, readbackNote: String?): XgResult.Err {
        val code = if (result.ok) ErrorCode.ERR_CMD_FAILED else normalizeFailureCode(result.code)
        val detail = buildString {
            val raw = detailOf(result)
            if (raw.isNotBlank()) append(raw)
            if (!readbackNote.isNullOrBlank()) {
                if (isNotEmpty()) append(" | ")
                append(readbackNote)
            }
        }
        return XgResult.Err(code, "$label：${ErrorCatalog.message(code)}", detail.ifBlank { null })
    }

    private fun normalizeFailureCode(code: Int): Int = when (code) {
        ErrorCode.ERR_SU_MISSING,
        ErrorCode.ERR_SU_DENIED,
        ErrorCode.ERR_SU_TIMEOUT,
        ErrorCode.ERR_CMD_FAILED,
        ErrorCode.ERR_CMD_TIMEOUT,
        ErrorCode.ERR_READONLY_FS,
        ErrorCode.ERR_POLICY_BLOCKED,
        ErrorCode.ERR_SELINUX -> code

        else -> ErrorCode.ERR_CMD_FAILED
    }

    private fun isSafeBackupDir(path: String): Boolean =
        path.startsWith("$BACKUP_ROOT/") && !path.contains("..")

    private suspend fun removeBackupDir(path: String) {
        if (!isSafeBackupDir(path)) return
        shell.exec("rm -rf ${quote(path)}", root = true, timeoutMs = COMMAND_TIMEOUT_MS)
    }

    private fun appStatePayload(frozen: Boolean): String =
        buildJsonObject { put("frozen", frozen) }.toString()

    private fun componentPayload(componentPath: String, currentEnabled: Boolean): String =
        buildJsonObject {
            put("component", componentPath)
            put("enabled", currentEnabled)
        }.toString()

    private fun ShellResult.commandLine(): String = stdout.trim().ifBlank { "exit=$code" }

    private data class MemoryUsage(val running: Boolean, val bytes: Long?)

    private data class TrafficUsage(val rxBytes: Long?, val txBytes: Long?, val reason: String?)

    private companion object {
        const val TAG = "AppRepository"
        const val BACKUP_ROOT = "/data/local/tmp/xg/backups"
        const val SYSTEM_SERVER = "system_server"
        const val PACKAGE_PREFIX = "package:"
        const val FROZEN_LIST_COMMAND = "pm list packages -d --user 0"

        const val KIND_APP_STATE = "app_state"
        const val KIND_APP_BACKUP = "app_backup"

        const val ACTION_FREEZE = "freeze"
        const val ACTION_UNFREEZE = "unfreeze"
        const val ACTION_UNINSTALL = "uninstall"
        const val ACTION_UNDO_UNINSTALL = "undo_uninstall"
        const val ACTION_COMPONENT = "set_component_enabled"

        const val COMMAND_TIMEOUT_MS = 15_000L
        const val READ_TIMEOUT_MS = 10_000L
        const val INSTALL_TIMEOUT_MS = 60_000L

        const val APP_STATE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        const val APP_BACKUP_TTL_MS = 24L * 60 * 60 * 1000
        const val MAX_BACKUP_BYTES = 200L * 1024 * 1024
        const val TRAFFIC_WINDOW_DAYS = 30
        const val MEMORY_NO_PID = "XG_NO_PID"
        const val PRIMARY_ABI_PREFIX = "primaryCpuAbi="

        @Suppress("DEPRECATION")
        val NETWORK_TYPES = intArrayOf(
            ConnectivityManager.TYPE_WIFI,
            ConnectivityManager.TYPE_MOBILE,
        )
        val WHITESPACE = Regex("\\s+")
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")
        val json = Json { ignoreUnknownKeys = true }
    }
}
