package com.xg.toolbox.core.rootops

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process

import com.xg.toolbox.core.common.ErrorCatalog
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.BehaviorPreferences
import com.xg.toolbox.core.data.AutostartOriginals
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.ShellResult
import com.xg.toolbox.core.shell.SuPathStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Root-backed boot persistence and guard management.
 *
 * Nothing here copies the APK into /system, edits verified boot, writes /data/adb modules,
 * or installs a Magisk/KernelSU module. Every claimed state comes from a command exit
 * code/readback or from Android's own package, app-op and battery APIs.
 */
@Singleton
class RootOpsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val suPathStore: SuPathStore,
    private val behaviorPreferences: BehaviorPreferences,
    private val recoveryJournal: StartupRecoveryJournal,
) {

    private val packageName: String = context.packageName
    private val bootReceiverComponent = ComponentName(context, BootReceiver::class.java)
    private val unlockReceiverComponent = ComponentName(context, UserUnlockReceiver::class.java)
    private val guardServiceComponent = ComponentName(context, RootGuardService::class.java)
    private val targetUserId: Int = Process.myUid() / 100_000

    private val scriptFile = File(context.filesDir, "root_guard.sh")
    private val markerFile = File(context.filesDir, "root_guard.enabled")
    private val pidFile = File(context.filesDir, "root_guard.pid")
    private val guardLogFile = File(context.filesDir, "root_guard.log")
    private val guardToken = "xg-root-guard-v1:$packageName"

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val actionMutex = Mutex()
    private val keepAliveStartRequested = AtomicBoolean(false)
    private var lastProvisioningReport: String = ""

    private val _status = MutableStateFlow(RootOpsStatus(targetUserId = targetUserId))
    val status: StateFlow<RootOpsStatus> = _status.asStateFlow()

    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        actionMutex.withLock {
            try {
                refreshStatusLocked()
            } catch (t: Throwable) {
                XgLog.e(TAG, "refresh root ops status failed", t)
                _status.value = _status.value.copy(
                    keepAliveRunning = false,
                    message = "状态读取失败：${t.message.orEmpty().take(120)}",
                )
            }
        }
    }

    suspend fun setAutostartEnabled(enabled: Boolean): XgResult<String> = withContext(Dispatchers.IO + NonCancellable) {
        actionMutex.withLock {
            if (enabled) enableAutostartLocked() else disableAutostartLocked()
        }
    }

    suspend fun setRootKeepAliveEnabled(enabled: Boolean): XgResult<Unit> = withContext(Dispatchers.IO + NonCancellable) {
        actionMutex.withLock {
            if (enabled) {
                val root = queryRootStateLocked()
                if (!root.rootGranted) {
                    applyStatusLocked(root)
                    return@withLock rootError(root)
                }

                val write = runCatching { behaviorPreferences.setRootKeepAliveEnabled(true) }
                if (write.isFailure) {
                    refreshStatusLocked()
                    return@withLock XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "保存保活开关失败",
                        write.exceptionOrNull()?.message,
                    )
                }

                val marker = runCatching { markerFile.writeText("1", Charsets.UTF_8) }
                if (marker.isFailure) {
                    rollbackKeepAliveLocked()
                    refreshStatusLocked()
                    return@withLock XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "写入保活标记失败",
                        marker.exceptionOrNull()?.message,
                    )
                }

                val serviceError = runCatching { RootGuardService.start(context) }.exceptionOrNull()
                if (serviceError != null) {
                    rollbackKeepAliveLocked()
                    refreshStatusLocked()
                    return@withLock XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "启动前台守护服务失败",
                        serviceError.message,
                    )
                }

                val serviceVisible = awaitGuardServiceRunning(SERVICE_START_TIMEOUT_MS)
                if (!serviceVisible) {
                    rollbackKeepAliveLocked()
                    refreshStatusLocked()
                    return@withLock XgResult.Err(
                        ErrorCode.ERR_POLICY_BLOCKED,
                        "RootGuardService 未在前台运行",
                        "前台服务启动后 dumpsys 未观察到服务；可能被 ROM 或后台启动策略拦截",
                    )
                }

                val guardStarted = ensureRootGuardProcessLocked()
                refreshStatusLocked()
                if (!guardStarted || !_status.value.keepAliveRunning) {
                    rollbackKeepAliveLocked()
                    refreshStatusLocked()
                    return@withLock XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "Root 守护进程启动失败",
                        "已写入脚本但未能通过 PID/命令行存活校验；请检查 su 授权和 SELinux",
                    )
                }
                XgResult.Ok(Unit)
            } else {
                val write = runCatching { behaviorPreferences.setRootKeepAliveEnabled(false) }
                if (write.isFailure) {
                    return@withLock XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "保存保活开关失败",
                        write.exceptionOrNull()?.message,
                    )
                }

                markerFile.delete()
                val stopped = stopRootGuardProcessLocked()
                if (!readAutostartPreference()) stopGuardService()
                refreshStatusLocked()
                if (!stopped) {
                    XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "Root 守护进程停止结果无法验证",
                        "已移除保活标记，但 root 命令未能确认守护 PID 已退出；最迟下一轮 30 秒检测会因标记缺失退出",
                    )
                } else {
                    XgResult.Ok(Unit)
                }
            }
        }
    }

    suspend fun provisionPrivileges(): XgResult<String> = withContext(Dispatchers.IO + NonCancellable) {
        actionMutex.withLock {
            val root = queryRootStateLocked()
            if (!root.rootGranted) {
                applyStatusLocked(root)
                return@withLock rootError(root)
            }
            val originals = when (val prepared = prepareOriginalStateLocked()) {
                is XgResult.Ok -> prepared.data
                is XgResult.Err -> return@withLock prepared
            }
            val steps = applyAutostartPrivilegesLocked(enable = true, originals = originals)
            val primaryReport = buildProvisioningReport(steps)
            if (hasBlockingFailure(steps)) {
                val rollback = applyAutostartPrivilegesLocked(enable = false, originals = originals)
                val rollbackOk = !hasBlockingFailure(rollback)
                if (rollbackOk) clearOriginalStateLocked()
                lastProvisioningReport = primaryReport + buildRollbackReport(rollback)
                refreshStatusLocked()
                return@withLock XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "Root 提权配置未全部通过回读，已尝试恢复原状态",
                    lastProvisioningReport,
                )
            }
            lastProvisioningReport = primaryReport
            refreshStatusLocked()
            XgResult.Ok(lastProvisioningReport)
        }
    }

    suspend fun restoreAutostartPrivileges(): XgResult<String> = withContext(Dispatchers.IO) {
        actionMutex.withLock {
            if (!readAutostartPreference()) {
                return@withLock XgResult.Ok("开机自启动未开启，跳过 Root 授权恢复")
            }
            val root = queryRootStateLocked()
            if (!root.rootGranted) {
                applyStatusLocked(root)
                return@withLock rootError(root)
            }
            val originals = when (val prepared = prepareOriginalStateLocked()) {
                is XgResult.Ok -> prepared.data
                is XgResult.Err -> return@withLock prepared
            }
            val steps = applyAutostartPrivilegesLocked(enable = true, originals = originals)
            lastProvisioningReport = buildProvisioningReport(steps)
            refreshStatusLocked()
            if (hasBlockingFailure(steps)) {
                XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "开机自启动权限恢复失败",
                    lastProvisioningReport,
                )
            } else {
                XgResult.Ok(conciseSummary(steps, enabled = true))
            }
        }
    }
    suspend fun testCustomSuPath(path: String): XgResult<String> = withContext(Dispatchers.IO) {
        val candidate = path.trim()
        if (candidate.isEmpty() || !File(candidate).isAbsolute ||
            candidate.contains('\u0000') || candidate.contains('\n') || candidate.contains('\r')
        ) {
            return@withContext XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "su 路径必须是绝对路径",
                "拒绝空路径、相对路径或包含控制字符的路径",
            )
        }

        actionMutex.withLock {
            val quoted = shellQuote(candidate)
            val version = shell.exec("$quoted -v", root = false, timeoutMs = 10_000)
            val id = shell.exec("$quoted -c id", root = false, timeoutMs = 15_000)
            val idText = id.stdout.ifBlank { id.stderr }
            val uid = UID_REGEX.find(idText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val detail = "版本检查: ${commandDetail(version)}; 授权检查: ${commandDetail(id)}"
            if (uid == 0) {
                suPathStore.setCustomPath(candidate)
                refreshStatusLocked()
                XgResult.Ok(
                    buildString {
                        appendLine("自定义 su 验证通过")
                        appendLine("路径: $candidate")
                        appendLine("版本检查: ${commandDetail(version)}")
                        appendLine("授权检查: uid=0，已持久化")
                        append("后续 RootShell 每次启动 root 命令都会优先使用该绝对路径，失败时才回退。")
                    },
                )
            } else {
                val missing = version.code == ErrorCode.ERR_SU_MISSING ||
                    version.code == 126 || version.code == 127 ||
                    id.code == ErrorCode.ERR_SU_MISSING ||
                    id.code == 126 || id.code == 127 ||
                    (version.stdout + version.stderr + idText).contains("not found", ignoreCase = true) ||
                    (version.stdout + version.stderr + idText).contains("inaccessible", ignoreCase = true)
                val code = when {
                    missing -> ErrorCode.ERR_SU_MISSING
                    id.code == ErrorCode.ERR_SU_TIMEOUT || version.code == ErrorCode.ERR_SU_TIMEOUT ->
                        ErrorCode.ERR_SU_TIMEOUT
                    else -> ErrorCode.ERR_SU_DENIED
                }
                XgResult.Err(
                    code = code,
                    message = "自定义 su 验证失败，未保存",
                    detail = detail,
                )
            }
        }
    }

    /**
     * Non-blocking startup hook for XgApp. It only acts when the user's keep-alive preference is
     * true; DataStore reads and su commands run in the manager's IO scope.
     */
    fun startKeepAliveIfEnabled() {
        if (!keepAliveStartRequested.compareAndSet(false, true)) return
        managerScope.launch {
            try {
                delay(6_000)
                val autostart = readAutostartPreference()
                val keepAlive = readKeepAlivePreference()
                if (!autostart && !keepAlive) return@launch
                if (autostart) {
                    val result = restoreAutostartPrivileges()
                    when (result) {
                        is XgResult.Ok -> recoveryJournal.record("app-start-privileges", true, result.data)
                        is XgResult.Err -> recoveryJournal.record(
                            "app-start-privileges",
                            false,
                            result.detail ?: result.message,
                        )
                    }
                }
                if (!keepAlive) return@launch
                actionMutex.withLock {
                    val root = queryRootStateLocked()
                    if (!root.rootGranted) {
                        applyStatusLocked(root)
                        return@withLock
                    }
                    markerFile.writeText("1", Charsets.UTF_8)
                    val serviceError = runCatching { RootGuardService.start(context) }.exceptionOrNull()
                    if (serviceError != null) {
                        XgLog.e(TAG, "startKeepAliveIfEnabled: foreground service start failed", serviceError)
                    }
                    val serviceVisible = awaitGuardServiceRunning(SERVICE_START_TIMEOUT_MS)
                    val guardStarted = ensureRootGuardProcessLocked()
                    if (!serviceVisible) {
                        XgLog.w(TAG, "startKeepAliveIfEnabled: foreground service not observed")
                    }
                    if (!guardStarted) {
                        XgLog.w(TAG, "startKeepAliveIfEnabled: detached root guard not observed")
                    }
                    applyStatusLocked(queryRootStateLocked())
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.e(TAG, "startKeepAliveIfEnabled failed", t)
                runCatching {
                    recoveryJournal.record(
                        "app-start-keepalive",
                        false,
                        "${t.javaClass.simpleName}: ${t.message.orEmpty().take(180)}",
                    )
                }
            }
        }
    }

    internal suspend fun onGuardServiceStarted() = withContext(Dispatchers.IO) {
        actionMutex.withLock {
            syncGuardStateLocked(
                autostart = readAutostartPreference(),
                keepAlive = readKeepAlivePreference(),
            )
        }
    }

    internal suspend fun onGuardServicePreferencesChanged(
        autostart: Boolean,
        keepAlive: Boolean,
    ) = withContext(Dispatchers.IO) {
        actionMutex.withLock {
            syncGuardStateLocked(autostart, keepAlive)
        }
    }

    private suspend fun enableAutostartLocked(): XgResult<String> {
        val root = queryRootStateLocked()
        if (!root.rootGranted) {
            applyStatusLocked(root)
            return rootError(root)
        }

        val originals = when (val prepared = prepareOriginalStateLocked()) {
            is XgResult.Ok -> prepared.data
            is XgResult.Err -> {
                refreshStatusLocked()
                return prepared
            }
        }
        val prefWrite = runCatching { behaviorPreferences.setAutostartEnabled(true) }
        if (prefWrite.isFailure) {
            refreshStatusLocked()
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "保存自启动开关失败，未修改系统组件",
                prefWrite.exceptionOrNull()?.message,
            )
        }
        val steps = applyAutostartPrivilegesLocked(enable = true, originals = originals)
        val primaryReport = buildProvisioningReport(steps)
        if (hasBlockingFailure(steps)) {
            val (rollback, rollbackOk) = rollbackAutostartPrivilegesLocked(originals)
            setAutostartPreferenceQuietly(false)
            lastProvisioningReport = primaryReport + buildRollbackReport(rollback)
            refreshStatusLocked()
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                if (rollbackOk) {
                    "开机自启动 Root 授权失败，已恢复原始状态并回滚本地开关"
                } else {
                    "开机自启动 Root 授权失败，本地开关已回滚但原始状态恢复未全部通过"
                },
                lastProvisioningReport,
            )
        }

        val serviceError = runCatching { RootGuardService.start(context) }.exceptionOrNull()
        if (serviceError != null) {
            setAutostartPreferenceQuietly(false)
            val (rollback, rollbackOk) = rollbackAutostartPrivilegesLocked(originals)
            lastProvisioningReport = primaryReport + buildRollbackReport(rollback)
            refreshStatusLocked()
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                if (rollbackOk) {
                    "开机自启动服务启动失败，已恢复原始状态"
                } else {
                    "开机自启动服务启动失败，原始状态恢复未全部通过"
                },
                lastProvisioningReport + "\n" + serviceError.message.orEmpty(),
            )
        }
        val serviceVisible = awaitGuardServiceRunning(SERVICE_START_TIMEOUT_MS)
        if (!serviceVisible) {
            setAutostartPreferenceQuietly(false)
            stopGuardService()
            val (rollback, rollbackOk) = rollbackAutostartPrivilegesLocked(originals)
            lastProvisioningReport = primaryReport + buildRollbackReport(rollback)
            refreshStatusLocked()
            return XgResult.Err(
                ErrorCode.ERR_POLICY_BLOCKED,
                if (rollbackOk) {
                    "开机自启动服务未被系统确认运行，已恢复原始状态"
                } else {
                    "开机自启动服务未被系统确认运行，原始状态恢复未全部通过"
                },
                lastProvisioningReport,
            )
        }

        lastProvisioningReport = primaryReport
        refreshStatusLocked()
        return XgResult.Ok(conciseSummary(steps, enabled = true))
    }

    private suspend fun disableAutostartLocked(): XgResult<String> {
        val root = queryRootStateLocked()
        if (!root.rootGranted) {
            applyStatusLocked(root)
            return rootError(root)
        }
        val originals = behaviorPreferences.autostartOriginals()
            ?: return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "缺少自启动原始状态快照，未执行移除",
                "为避免盲改系统状态，请先重新开启一次自启动以生成原状态快照",
            )

        val prefWrite = runCatching { behaviorPreferences.setAutostartEnabled(false) }
        if (prefWrite.isFailure) {
            refreshStatusLocked()
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "保存关闭状态失败，未修改系统权限",
                prefWrite.exceptionOrNull()?.message,
            )
        }
        val steps = applyAutostartPrivilegesLocked(enable = false, originals = originals)
        val primaryReport = buildProvisioningReport(steps)
        if (hasBlockingFailure(steps)) {
            setAutostartPreferenceQuietly(true)
            val restore = applyAutostartPrivilegesLocked(enable = true, originals = originals)
            lastProvisioningReport = primaryReport + buildRollbackReport(restore)
            refreshStatusLocked()
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "关闭开机自启动未全部通过回读，已恢复自启动授权，本地开关保持开启",
                lastProvisioningReport,
            )
        }

        if (!readKeepAlivePreference()) {
            stopRootGuardProcessLocked()
            stopGuardService()
        }
        val snapshotCleared = clearOriginalStateLocked()
        lastProvisioningReport = primaryReport
        refreshStatusLocked()
        val summary = conciseSummary(steps, enabled = false)
        return XgResult.Ok(
            if (snapshotCleared) summary else "$summary；原始状态快照清理失败，已记录日志",
        )
    }
    private suspend fun applyAutostartPrivilegesLocked(
        enable: Boolean,
        originals: AutostartOriginals,
    ): List<Step> {
        val steps = mutableListOf<Step>()
        if (enable) {
            steps += setReceiverEnabled(
                bootReceiverComponent,
                enabled = true,
                action = Intent.ACTION_BOOT_COMPLETED,
                label = "BootReceiver",
            )
            steps += setReceiverEnabled(
                unlockReceiverComponent,
                enabled = true,
                action = Intent.ACTION_USER_UNLOCKED,
                label = "UserUnlockReceiver",
            )
            steps += setAppOpToAllow(APP_OP_RUN_IN_BACKGROUND)
            steps += setAppOpToAllow(APP_OP_RUN_ANY_IN_BACKGROUND)
            steps += setBatteryWhitelist(enabled = true)
            steps += setStandbyActive(originals.standbyBucket)
            steps += setOplusStartup(enabled = true)
        } else {
            steps += setOplusStartup(enabled = originals.oplusStartupEnabled ?: false)
            steps += restoreAppOp(APP_OP_RUN_IN_BACKGROUND, originals.appOpRunInBackground)
            steps += restoreAppOp(APP_OP_RUN_ANY_IN_BACKGROUND, originals.appOpRunAnyInBackground)
            steps += setBatteryWhitelist(
                enabled = originals.deviceIdleWhitelisted,
                restore = true,
            )
            steps += restoreStandbyBucket(originals.standbyBucket)
            steps += setReceiverEnabled(
                bootReceiverComponent,
                enabled = true,
                action = Intent.ACTION_BOOT_COMPLETED,
                label = "BootReceiver:keep-enabled",
            )
            steps += setReceiverEnabled(
                unlockReceiverComponent,
                enabled = true,
                action = Intent.ACTION_USER_UNLOCKED,
                label = "UserUnlockReceiver:keep-enabled",
            )
        }
        return steps
    }

    private suspend fun prepareOriginalStateLocked(): XgResult<AutostartOriginals> {
        behaviorPreferences.autostartOriginals()?.let { return XgResult.Ok(it) }

        val bootEnabled = isBootReceiverEnabled()
        val unlockEnabled = isComponentEnabled(unlockReceiverComponent, isService = false)
        val deviceIdle = readBatteryWhitelistState()
            ?: return XgResult.Err(
                ErrorCode.ERR_PARSE_FAILED,
                "无法读取原始电池白名单状态，未修改任何系统权限",
                "cmd deviceidle whitelist 与 dumpsys 均未返回可解析结果",
            )
        val runInBackground = when (val captured = captureAppOpOriginal(APP_OP_RUN_IN_BACKGROUND)) {
            is XgResult.Ok -> captured.data
            is XgResult.Err -> return captured
        }
        val runAnyInBackground = when (val captured = captureAppOpOriginal(APP_OP_RUN_ANY_IN_BACKGROUND)) {
            is XgResult.Ok -> captured.data
            is XgResult.Err -> return captured
        }
        val standby = readStandbyBucket()
        val oplusSupported = targetUserId == 0 && isOplusDevice()
        val oplusBefore = if (oplusSupported) {
            readOplusStartupState() ?: return XgResult.Err(
                ErrorCode.ERR_PARSE_FAILED,
                "无法读取 OPlus 启动库原始状态，未修改任何系统权限",
                "OplusStartupDbHelper status 未返回 DB_ON/XML_ON",
            )
        } else {
            null
        }
        val originals = AutostartOriginals(
            appOpRunInBackground = runInBackground,
            appOpRunAnyInBackground = runAnyInBackground,
            deviceIdleWhitelisted = deviceIdle,
            bootReceiverEnabled = bootEnabled,
            unlockReceiverEnabled = unlockEnabled,
            standbyBucket = standby,
            oplusSupported = oplusSupported,
            oplusStartupEnabled = oplusBefore,
        )
        val saved = runCatching { behaviorPreferences.saveAutostartOriginals(originals) }
        if (saved.isFailure) {
            return XgResult.Err(
                ErrorCode.ERR_CMD_FAILED,
                "保存自启动原始状态失败，未修改任何系统权限",
                saved.exceptionOrNull()?.message,
            )
        }
        return XgResult.Ok(originals)
    }

    private suspend fun captureAppOpOriginal(op: String): XgResult<String?> {
        val probe = runRoot(
            "appops get --user $targetUserId ${shellQuote(packageName)} $op",
            READBACK_TIMEOUT_MS,
        )
        val text = probe.stdout.ifBlank { probe.stderr }.trim()
        if (!probe.ok || looksUnsupported(text)) {
            return XgResult.Err(
                ErrorCode.ERR_UNSUPPORTED_DEVICE,
                "无法探测 $op 原始状态，未修改任何系统权限",
                "code=${probe.code} ${text.take(180)}",
            )
        }
        val mode = parseAppOpMode(text, op)
        if (mode == null && !text.contains("No operations", ignoreCase = true)) {
            return XgResult.Err(
                ErrorCode.ERR_PARSE_FAILED,
                "无法解析 $op 原始状态，未修改任何系统权限",
                text.take(180),
            )
        }
        return XgResult.Ok(mode ?: "default")
    }

    private suspend fun rollbackAutostartPrivilegesLocked(
        originals: AutostartOriginals,
    ): Pair<List<Step>, Boolean> {
        val latest = runCatching { behaviorPreferences.autostartOriginals() }.getOrNull() ?: originals
        val steps = applyAutostartPrivilegesLocked(enable = false, originals = latest)
        val ok = !hasBlockingFailure(steps)
        if (ok) clearOriginalStateLocked()
        return steps to ok
    }
    private suspend fun setAutostartPreferenceQuietly(value: Boolean) {
        runCatching { behaviorPreferences.setAutostartEnabled(value) }
            .onFailure { XgLog.e(TAG, "set autostart preference failed: $value", it) }
    }

    private suspend fun clearOriginalStateLocked(): Boolean = try {
        behaviorPreferences.clearAutostartOriginals()
        true
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        XgLog.e(TAG, "clear autostart originals failed", t)
        recoveryJournal.record("autostart-originals-clear", false, t.message ?: "clear failed")
        false
    }
    private suspend fun setReceiverEnabled(
        component: ComponentName,
        enabled: Boolean,
        action: String,
        label: String,
    ): Step {
        val quoted = shellQuote(component.flattenToString())
        val verb = if (enabled) "enable" else "disable-user"
        val localBefore = isComponentEnabled(component, isService = false)
        val before = readReceiverStateViaQuery(component, action)
        if (shouldSkipComponentMutation(before.first, localBefore, enabled)) {
            return Step(
                name = label,
                status = StepStatus.OK,
                code = 0,
                readback = "already=${if (enabled) "enabled" else "disabled"}; " +
                    "local=$localBefore; query_state=${before.first}; " +
                    "query=${before.second.take(180).ifBlank { "empty" }}",
                detail = "skip pm $verb (state already ${if (enabled) "enabled" else "disabled"})",
                blocking = true,
            )
        }
        val command = "pm $verb --user $targetUserId $quoted"
        val result = runRoot(command, CMD_TIMEOUT_MS)
        val localEnabled = awaitComponentEnabled(component, enabled)
        val after = readReceiverStateViaQuery(component, action)
        val stateText = after.second.take(180)
        val actual = after.first ?: localEnabled
        val ok = result.ok && actual == enabled
        return Step(
            name = label,
            status = if (ok) StepStatus.OK else StepStatus.FAILED,
            code = result.code,
            readback = "local=${if (localEnabled) "enabled" else "disabled"}; " +
                "query=${actual}; output=${stateText.ifBlank { "empty" }}",
            detail = command,
            blocking = true,
        )
    }

    private suspend fun readReceiverStateViaQuery(
        component: ComponentName,
        action: String,
    ): Pair<Boolean?, String> {
        val result = runRoot(
            "cmd package query-receivers --brief --components -a ${shellQuote(action)}",
            READBACK_TIMEOUT_MS,
        )
        val text = result.stdout.ifBlank { result.stderr }.trim()
        if (!result.ok) return null to text
        val shortName = component.className.removePrefix(component.packageName + ".")
        val candidates = setOf(
            component.flattenToString(),
            "${component.packageName}/${component.className}",
            "${component.packageName}/.$shortName",
        )
        val present = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { line -> candidates.any { candidate -> line == candidate } }
        return present to text
    }

    private suspend fun setAppOpToAllow(op: String): Step =
        writeAppOp(op = op, target = "allow", expected = "allow", restore = false)

    private suspend fun restoreAppOp(op: String, original: String?): Step =
        writeAppOp(op = op, target = original ?: "default", expected = original, restore = true)

    private suspend fun writeAppOp(
        op: String,
        target: String,
        expected: String?,
        restore: Boolean,
    ): Step {
        val name = "appops:$op" + if (restore) ":restore" else ""
        val probe = runRoot(
            "appops get --user $targetUserId ${shellQuote(packageName)} $op",
            READBACK_TIMEOUT_MS,
        )
        val probeText = probe.stdout.ifBlank { probe.stderr }.trim()
        if (!probe.ok || looksUnsupported(probeText)) {
            return Step(
                name = name,
                status = StepStatus.SKIPPED,
                code = probe.code,
                readback = "probe=${probeText.take(160).ifBlank { "no-output" }}",
                detail = "设备未暴露该 AppOp；不能把未探测到的 op 当作成功",
                blocking = true,
            )
        }
        val set = runRoot(
            "appops set --user $targetUserId ${shellQuote(packageName)} $op $target",
            CMD_TIMEOUT_MS,
        )
        val read = runRoot(
            "appops get --user $targetUserId ${shellQuote(packageName)} $op",
            READBACK_TIMEOUT_MS,
        )
        val mode = parseAppOpMode(read.stdout.ifBlank { read.stderr }, op)
        val expectedMatched = if (expected == null || expected == "default") {
            mode == null || mode == "default"
        } else {
            mode == expected
        }
        val ok = set.ok && read.ok && expectedMatched
        return Step(
            name = name,
            status = if (ok) StepStatus.OK else StepStatus.FAILED,
            code = set.code,
            readback = "target=$target; get_code=${read.code}; mode=${mode ?: "unset"}; " +
                "expected=${expected ?: "unset"}",
            detail = commandDetail(read),
            blocking = true,
        )
    }

    private suspend fun setBatteryWhitelist(enabled: Boolean, restore: Boolean = false): Step {
        val name = if (restore) "deviceidle:restore" else "deviceidle"
        val sign = if (enabled) "+" else "-"
        val quotedPkg = shellQuote(packageName)
        val withUser = runRoot("cmd deviceidle whitelist --user $targetUserId $sign$quotedPkg", CMD_TIMEOUT_MS)
        var listed = readWhitelistState()
        var fallbackCode: Int? = null
        if (!withUser.ok || listed != enabled) {
            val fallback = runRoot("cmd deviceidle whitelist $sign$quotedPkg", CMD_TIMEOUT_MS)
            fallbackCode = fallback.code
            listed = readWhitelistState()
        }
        val settledListed = awaitWhitelistState(enabled) ?: listed
        val power = awaitBatteryWhitelist(enabled)
        val ok = settledListed == enabled && power
        return Step(
            name = name,
            status = if (ok) StepStatus.OK else StepStatus.FAILED,
            code = withUser.code,
            readback = "list=${settledListed?.let { if (it) "present" else "absent" } ?: "read-failed"}; " +
                "PowerManager=${power}; fallback_code=${fallbackCode ?: "none"}",
            detail = firstLine(withUser.stdout.ifBlank { withUser.stderr }),
            blocking = true,
        )
    }

    private suspend fun setStandbyActive(original: String?): Step {
        val current = readStandbyBucket()
        if (current == null) {
            return Step(
                name = "standby-bucket",
                status = StepStatus.SKIPPED,
                code = null,
                readback = "am get-standby-bucket 不可用或输出无法解析",
                blocking = false,
            )
        }
        if (current == "active" || current == "exempted") {
            return Step(
                name = "standby-bucket",
                status = StepStatus.OK,
                code = 0,
                readback = "before=$current; after=$current（已无需提桶）",
                blocking = true,
            )
        }
        if (original == null) {
            runCatching { behaviorPreferences.setStandbyBucketBefore(current) }
        }
        val set = runRoot(
            "am set-standby-bucket --user $targetUserId ${shellQuote(packageName)} active",
            CMD_TIMEOUT_MS,
        )
        val after = readStandbyBucket()
        val ok = set.ok && after == "active"
        return Step(
            name = "standby-bucket",
            status = if (ok) StepStatus.OK else StepStatus.FAILED,
            code = set.code,
            readback = "before=$current; after=${after ?: "parse-failed"}; target=active",
            detail = firstLine(set.stdout.ifBlank { set.stderr }),
            blocking = true,
        )
    }

    private suspend fun restoreStandbyBucket(original: String?): Step {
        val current = readStandbyBucket()
        if (current == null) {
            return Step(
                name = "standby-bucket:restore",
                status = StepStatus.SKIPPED,
                code = null,
                readback = "am get-standby-bucket 不可用或输出无法解析",
                detail = if (original == null) {
                    "未记录可恢复 bucket，本次未修改 standby"
                } else {
                    "原始 bucket=$original 已记录，但当前无法回读"
                },
                blocking = original != null,
            )
        }
        if (original == null) {
            val ok = current != "active"
            return Step(
                name = "standby-bucket:restore",
                status = if (ok) StepStatus.OK else StepStatus.FAILED,
                code = 0,
                readback = "before=$current; original=unset",
                detail = if (ok) "原始 bucket 未记录，当前不是 active" else "原始 bucket 未记录且当前为 active",
                blocking = true,
            )
        }
        if (current == original) {
            return Step(
                name = "standby-bucket:restore",
                status = StepStatus.OK,
                code = 0,
                readback = "before=$current; after=$current; original=$original",
                blocking = true,
            )
        }
        if (original == "exempted") {
            return Step(
                name = "standby-bucket:restore",
                status = StepStatus.FAILED,
                code = null,
                readback = "before=$current; original=exempted",
                detail = "am 不支持把 bucket 设置回 exempted，无法诚实恢复原值",
                blocking = true,
            )
        }
        val set = runRoot(
            "am set-standby-bucket --user $targetUserId ${shellQuote(packageName)} $original",
            CMD_TIMEOUT_MS,
        )
        val after = readStandbyBucket()
        val ok = set.ok && after == original
        return Step(
            name = "standby-bucket:restore",
            status = if (ok) StepStatus.OK else StepStatus.FAILED,
            code = set.code,
            readback = "before=$current; after=${after ?: "parse-failed"}; target=$original",
            detail = firstLine(set.stdout.ifBlank { set.stderr }),
            blocking = true,
        )
    }
    private suspend fun setOplusStartup(enabled: Boolean): Step {
        if (targetUserId != 0) {
            return Step(
                name = "oplus-startup",
                status = StepStatus.SKIPPED,
                code = null,
                readback = "当前用户=$targetUserId；OPlus 启动库仅对主用户 0 执行，避免多用户误写",
                blocking = false,
            )
        }
        if (!isOplusDevice()) {
            return Step(
                name = "oplus-startup",
                status = StepStatus.SKIPPED,
                code = null,
                readback = "非 OPlus/OPPO/OnePlus/realme 设备",
                blocking = false,
            )
        }
        val set = runOplusHelper(if (enabled) "on" else "off")
        if (!set.ok) {
            return Step(
                name = "oplus-startup",
                status = StepStatus.FAILED,
                code = set.code,
                readback = "set=${commandDetail(set)}",
                blocking = true,
            )
        }
        val read = runOplusHelper("status")
        val text = read.stdout.ifBlank { read.stderr }
        val dbOn = OPLUS_DB_ON_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val xmlOn = OPLUS_XML_ON_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val expected = if (enabled) 1 else 0
        val ok = read.ok && dbOn == expected && xmlOn == expected
        return Step(
            name = "oplus-startup",
            status = if (ok) StepStatus.OK else StepStatus.FAILED,
            code = set.code,
            readback = "status_code=${read.code}; db=${dbOn ?: "unknown"}; xml=${xmlOn ?: "unknown"}; " +
                "expected=$expected",
            detail = firstLine(text),
            blocking = true,
        )
    }

    private fun buildRollbackReport(steps: List<Step>): String =
        "\n恢复原状态报告:\n" + buildProvisioningReport(steps)

    private fun hasBlockingFailure(steps: List<Step>): Boolean =
        steps.any { it.status == StepStatus.FAILED || (it.status == StepStatus.SKIPPED && it.blocking) }

    private fun buildProvisioningReport(steps: List<Step>): String = buildString {
        appendLine("Root 自启动授权报告")
        appendLine("目标用户=$targetUserId（当前应用用户；主用户通常为 0）")
        appendLine("包名=$packageName")
        steps.forEach { step ->
            appendLine(formatStep(step))
        }
        append("所有命令均通过 core/shell 的 root Shell 执行；readback 未通过时不会标记为成功。")
    }

    private fun conciseSummary(steps: List<Step>, enabled: Boolean): String {
        val action = if (enabled) "已开启" else "已关闭"
        val details = steps.joinToString("，") { step ->
            "${step.name}=${when (step.status) {
                StepStatus.OK -> "OK"
                StepStatus.SKIPPED -> "N/A"
                StepStatus.FAILED -> "FAIL"
            }}"
        }
        return "开机自启动$action（用户=$targetUserId）：$details"
    }

    private suspend fun syncGuardStateLocked(autostart: Boolean, keepAlive: Boolean) {
        if (!autostart && !keepAlive) {
            stopRootGuardProcessLocked()
            stopGuardService()
            updateLocalStatusLocked()
            return
        }

        if (!keepAlive) {
            if (markerFile.exists() || pidFile.exists()) stopRootGuardProcessLocked()
            updateLocalStatusLocked()
            return
        }

        val root = queryRootStateLocked()
        if (root.rootGranted) {
            runCatching { markerFile.writeText("1", Charsets.UTF_8) }
            ensureRootGuardProcessLocked()
            applyStatusLocked(queryRootStateLocked())
        } else {
            applyStatusLocked(root)
        }
    }

    private suspend fun refreshStatusLocked() {
        applyStatusLocked(queryRootStateLocked())
    }

    private suspend fun applyStatusLocked(query: RootQuery) {
        val bootEnabled = isBootReceiverEnabled()
        val unlockEnabled = isComponentEnabled(unlockReceiverComponent, isService = false)
        val batteryWhitelisted = isBatteryWhitelisted()
        var backgroundAllowed = false
        var anyBackgroundAllowed = false
        var standbyActive = false
        var oplusSynced: Boolean? = null
        if (query.rootGranted) {
            backgroundAllowed = isAppOpAllowed(APP_OP_RUN_IN_BACKGROUND)
            anyBackgroundAllowed = isAppOpAllowed(APP_OP_RUN_ANY_IN_BACKGROUND)
            standbyActive = readStandbyBucket().let { it == "active" || it == "exempted" }
            if (isOplusDevice()) oplusSynced = readOplusStartupState()
        }
        val keepAlive = query.rootGranted && query.keepAlive
        _status.value = RootOpsStatus(
            rootGranted = query.rootGranted,
            bootReceiverEnabled = bootEnabled,
            userUnlockReceiverEnabled = unlockEnabled,
            batteryWhitelisted = batteryWhitelisted,
            backgroundAppOpsAllowed = backgroundAllowed,
            anyBackgroundAppOpsAllowed = anyBackgroundAllowed,
            standbyBucketActive = standbyActive,
            oplusStartupSynced = oplusSynced,
            targetUserId = targetUserId,
            keepAliveRunning = keepAlive,
            provisioningReport = lastProvisioningReport,
            message = buildStatusMessage(
                rootGranted = query.rootGranted,
                bootEnabled = bootEnabled,
                unlockEnabled = unlockEnabled,
                batteryWhitelisted = batteryWhitelisted,
                backgroundAllowed = backgroundAllowed,
                anyBackgroundAllowed = anyBackgroundAllowed,
                standbyActive = standbyActive,
                oplusSynced = oplusSynced,
                keepAlive = keepAlive,
                rootDetail = query.output,
            ),
        )
    }

    private fun updateLocalStatusLocked() {
        val current = _status.value
        val bootEnabled = isBootReceiverEnabled()
        val unlockEnabled = isComponentEnabled(unlockReceiverComponent, isService = false)
        val batteryWhitelisted = isBatteryWhitelisted()
        _status.value = current.copy(
            bootReceiverEnabled = bootEnabled,
            userUnlockReceiverEnabled = unlockEnabled,
            batteryWhitelisted = batteryWhitelisted,
            keepAliveRunning = false,
            message = buildStatusMessage(
                rootGranted = current.rootGranted,
                bootEnabled = bootEnabled,
                unlockEnabled = unlockEnabled,
                batteryWhitelisted = batteryWhitelisted,
                backgroundAllowed = current.backgroundAppOpsAllowed,
                anyBackgroundAllowed = current.anyBackgroundAppOpsAllowed,
                standbyActive = current.standbyBucketActive,
                oplusSynced = current.oplusStartupSynced,
                keepAlive = false,
                rootDetail = current.message,
            ),
        )
    }

    private fun buildStatusMessage(
        rootGranted: Boolean,
        bootEnabled: Boolean,
        unlockEnabled: Boolean,
        batteryWhitelisted: Boolean,
        backgroundAllowed: Boolean,
        anyBackgroundAllowed: Boolean,
        standbyActive: Boolean,
        oplusSynced: Boolean?,
        keepAlive: Boolean,
        rootDetail: String,
    ): String {
        val parts = mutableListOf<String>()
        if (rootGranted) {
            parts += "Root 已授权（su 首选 ${suPathStore.effectivePath}）"
        } else {
            parts += "Root 未授权或 su 不可用（su 首选 ${suPathStore.effectivePath}）"
            rootDetail.lineSequence().firstOrNull { it.isNotBlank() }?.let {
                parts += it.take(120)
            }
        }
        parts += "目标用户=$targetUserId"
        parts += "BootReceiver${if (bootEnabled) "已启用" else "未启用"}"
        parts += "UserUnlockReceiver${if (unlockEnabled) "已启用" else "未启用"}"
        parts += "RUN_IN_BACKGROUND${if (backgroundAllowed) "=allow" else "未验证"}"
        parts += "RUN_ANY_IN_BACKGROUND${if (anyBackgroundAllowed) "=allow" else "未验证"}"
        parts += "电池优化白名单${if (batteryWhitelisted) "已加入" else "未加入"}"
        parts += "Standby${if (standbyActive) "=active" else "非 active"}"
        if (oplusSynced != null) {
            parts += "OPlus 启动库${if (oplusSynced) "已同步" else "未同步"}"
        }
        parts += "Root 守护${if (keepAlive) "运行中" else "未运行"}"
        parts += "重启自启仍取决于 ROM 是否放行 BOOT_COMPLETED 和后台前台服务；未主动拉起 Activity"
        return parts.joinToString("；")
    }

    private suspend fun queryRootStateLocked(): RootQuery {
        val pidQuoted = shellQuote(pidFile.absolutePath)
        val markerQuoted = shellQuote(markerFile.absolutePath)
        val tokenQuoted = shellQuote(guardToken)
        val command = """
            printf 'uid=%s\n' "@D@(id -u 2>/dev/null)"
            pid="@D@(cat $pidQuoted 2>/dev/null)"
            if [ -f $markerQuoted ] && [ -n "@D@pid" ] && kill -0 "@D@pid" 2>/dev/null && tr '\000' '\n' < "/proc/@D@pid/cmdline" 2>/dev/null | grep -F -q $tokenQuoted; then
              echo "keepalive=1"
            else
              echo "keepalive=0"
            fi
        """.trimIndent().replace("@D@", "$").replace("@PID@", "$$")

        val result = try {
            shell.exec(command, root = true, timeoutMs = ROOT_TIMEOUT_MS)
        } catch (t: Throwable) {
            ShellResult(ErrorCode.ERR_CMD_FAILED, "", t.message.orEmpty(), 0L)
        }
        val output = (result.stdout + "\n" + result.stderr).trim()
        val uid = UID_REGEX.find(output)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val keepAlive = output.lineSequence().any { it.trim() == "keepalive=1" }
        return RootQuery(
            rootGranted = uid == 0,
            keepAlive = keepAlive,
            code = result.code,
            output = output,
        )
    }

    private suspend fun isAppOpAllowed(op: String): Boolean {
        val result = runRoot(
            "appops get --user $targetUserId ${shellQuote(packageName)} $op",
            READBACK_TIMEOUT_MS,
        )
        return result.ok && parseAppOpMode(result.stdout.ifBlank { result.stderr }, op) == "allow"
    }

    private suspend fun readStandbyBucket(): String? {
        val result = runRoot(
            "am get-standby-bucket --user $targetUserId ${shellQuote(packageName)}",
            READBACK_TIMEOUT_MS,
        )
        if (!result.ok) return null
        val text = result.stdout.ifBlank { result.stderr }
        val token = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        STANDBY_BUCKET_NUMBERS[token]?.let { return it }
        return STANDBY_BUCKETS.firstOrNull { bucket ->
            Regex("(?i)(?<![A-Za-z_])${Regex.escape(bucket)}(?![A-Za-z_])").containsMatchIn(text)
        }
    }
    private suspend fun readWhitelistState(): Boolean? {
        val dump = runRoot("dumpsys deviceidle whitelist", READBACK_TIMEOUT_MS)
        val result = if (dump.ok) {
            dump
        } else {
            runRoot("cmd deviceidle whitelist --user $targetUserId", READBACK_TIMEOUT_MS)
        }
        if (!result.ok) return null
        val quoted = Regex.escape(packageName)
        return Regex("(?m)(^|[\\s,:])$quoted($|[\\s,:])").containsMatchIn(result.stdout)
    }
    private suspend fun awaitComponentEnabled(component: ComponentName, expected: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + COMPONENT_READBACK_TIMEOUT_MS
        do {
            if (isComponentEnabled(component, isService = false) == expected) return true
            delay(100)
        } while (System.currentTimeMillis() < deadline)
        return isComponentEnabled(component, isService = false) == expected
    }

    private suspend fun awaitWhitelistState(expected: Boolean): Boolean? {
        val deadline = System.currentTimeMillis() + BATTERY_READBACK_TIMEOUT_MS
        var last: Boolean? = null
        do {
            last = readWhitelistState()
            if (last == expected) return last
            delay(100)
        } while (System.currentTimeMillis() < deadline)
        return readWhitelistState() ?: last
    }

    private suspend fun awaitBatteryWhitelist(expected: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + BATTERY_READBACK_TIMEOUT_MS
        do {
            if (isBatteryWhitelisted() == expected) return true
            delay(100)
        } while (System.currentTimeMillis() < deadline)
        return isBatteryWhitelisted() == expected
    }
    private fun parseAppOpMode(text: String, op: String): String? {
        val named = Regex("(?im)(?:^|\\s)${Regex.escape(op)}\\s*:\\s*(?:mode=)?([a-z_]+)")
            .find(text)?.groupValues?.getOrNull(1)
        if (!named.isNullOrBlank()) return named.lowercase()
        if (text.contains("No operations", ignoreCase = true)) return null
        return Regex("(?im)\\bmode=([a-z_]+)").find(text)?.groupValues?.getOrNull(1)?.lowercase()
    }

    private fun looksUnsupported(text: String): Boolean {
        val lower = text.lowercase()
        return lower.isBlank() ||
            lower.contains("unknown") ||
            lower.contains("unsupported") ||
            lower.contains("no such") ||
            lower.contains("not found") ||
            lower.contains("invalid") ||
            lower.contains("error:")
    }

    private suspend fun awaitGuardServiceRunning(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        do {
            val result = runRoot(
                "dumpsys activity services ${shellQuote(packageName)} | grep -F -q RootGuardService",
                SERVICE_START_TIMEOUT_MS,
            )
            if (result.ok) return true
            delay(150)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private suspend fun ensureRootGuardProcessLocked(): Boolean {
        if (isRootGuardProcessRunningLocked()) return true
        return try {
            scriptFile.writeText(buildGuardScript(), Charsets.UTF_8)
            if (!markerFile.exists()) markerFile.writeText("1", Charsets.UTF_8)
            val started = startGuardProcessLocked()
            if (!started) {
                XgLog.w(TAG, "root guard process did not stay alive")
                return false
            }
            delay(500)
            isRootGuardProcessRunningLocked()
        } catch (t: Throwable) {
            XgLog.e(TAG, "ensure root guard failed", t)
            false
        }
    }

    private suspend fun startGuardProcessLocked(): Boolean {
        val command = "( /system/bin/sh ${shellQuote(scriptFile.absolutePath)} ${shellQuote(guardToken)} " +
            "< /dev/null >> ${shellQuote(guardLogFile.absolutePath)} 2>&1 & )"
        val result = runRoot(command, CMD_TIMEOUT_MS)
        if (!result.ok) {
            XgLog.w(TAG, "root guard launch command failed: ${commandDetail(result)}")
            return false
        }
        delay(700)
        return isRootGuardProcessRunningLocked()
    }

    private suspend fun isRootGuardProcessRunningLocked(): Boolean {
        val pidQuoted = shellQuote(pidFile.absolutePath)
        val tokenQuoted = shellQuote(guardToken)
        val command = """
            pid="@D@(cat $pidQuoted 2>/dev/null)"
            if [ -n "@D@pid" ] && kill -0 "@D@pid" 2>/dev/null && tr '\000' '\n' < "/proc/@D@pid/cmdline" 2>/dev/null | grep -F -q $tokenQuoted; then
              echo RUNNING
            else
              echo STOPPED
            fi
        """.trimIndent().replace("@D@", "$").replace("@PID@", "$$")
        val result = runRoot(command, READBACK_TIMEOUT_MS)
        return result.ok && result.stdout.lineSequence().any { it.trim() == "RUNNING" }
    }

    private suspend fun stopRootGuardProcessLocked(): Boolean {
        markerFile.delete()
        val result = runRoot(buildGuardStopCommand(), CMD_TIMEOUT_MS)
        if (!result.ok) XgLog.w(TAG, "root guard stop command failed: ${commandDetail(result)}")
        delay(350)
        return !isRootGuardProcessRunningLocked()
    }

    private suspend fun rollbackKeepAliveLocked() {
        runCatching { behaviorPreferences.setRootKeepAliveEnabled(false) }
        markerFile.delete()
        stopRootGuardProcessLocked()
        if (!readAutostartPreference()) stopGuardService()
    }

    private fun buildGuardScript(): String {
        val markerQuoted = shellQuote(markerFile.absolutePath)
        val pidQuoted = shellQuote(pidFile.absolutePath)
        val packageQuoted = shellQuote(packageName)
        val serviceQuoted = shellQuote(guardServiceComponent.flattenToString())
        val logQuoted = shellQuote(guardLogFile.absolutePath)

        val script = """
            #!/system/bin/sh
            MARKER=$markerQuoted
            PID_FILE=$pidQuoted
            LOG=$logQuoted
            TOKEN=@D@(printf '%s%s' 'xg-root-guard-v1:' $packageQuoted)
            PKG=$packageQuoted
            SERVICE=$serviceQuoted
            AM=/system/bin/am
            DUMPSYS=/system/bin/dumpsys

            write_pid() {
              printf '%s\n' "@PID@" > "@D@PID_FILE" 2>/dev/null
              chmod 0644 "@D@PID_FILE" 2>/dev/null
            }

            cleanup() {
              current="@D@(cat "@D@PID_FILE" 2>/dev/null)"
              if [ "@D@current" = "@PID@" ]; then
                rm -f "@D@PID_FILE" 2>/dev/null
              fi
              exit 0
            }
            trap cleanup EXIT INT TERM
            trap '' HUP

            # A second script must never coexist. The token is a dedicated argv element.
            for proc in /proc/[0-9]*; do
              guard_pid="@D@{proc##*/}"
              [ "@D@guard_pid" = "@PID@" ] && continue
              if tr '\000' '\n' < "@D@proc/cmdline" 2>/dev/null | grep -F -q "@D@TOKEN"; then
                kill "@D@guard_pid" 2>/dev/null
              fi
            done
            write_pid

            pid_of_app() {
              app_pid="@D@(pidof "@D@PKG" 2>/dev/null | awk '{print @D@1; exit}')"
              if [ -z "@D@app_pid" ]; then
                app_pid="@D@(ps -A 2>/dev/null | awk -v pkg="@D@PKG" '@D@NF == pkg {print @D@2; exit}')"
              fi
              printf '%s' "@D@app_pid"
            }

            start_service() {
              "@D@AM" start-foreground-service -n "@D@SERVICE" >>"@D@LOG" 2>&1
              rc="@D@?"
              if [ "@D@rc" -ne 0 ]; then
                "@D@AM" startservice -n "@D@SERVICE" >>"@D@LOG" 2>&1
                rc="@D@?"
              fi
              printf 'service_start rc=%s at %s\n' "@D@rc" "@D@(date +%s)" >>"@D@LOG"
              return "@D@rc"
            }

            tick=0
            while :; do
              [ -f "@D@MARKER" ] || exit 0
              app_pid="@D@(pid_of_app)"
              if [ -z "@D@app_pid" ]; then
                start_service
              elif [ @D@((tick % 3)) -eq 0 ]; then
                if ! "@D@DUMPSYS" activity services "@D@PKG" 2>/dev/null | grep -F -q "RootGuardService"; then
                  start_service
                fi
              fi
              tick=@D@((tick + 1))
              sleep 30
            done
        """.trimIndent()
        return script.replace("@D@", "$").replace("@PID@", "$$")
    }

    private fun buildGuardStopCommand(): String =
        buildGuardStopCommandFor(pidFile.absolutePath, packageName)

    private suspend fun runOplusHelper(action: String): ShellResult {
        if (!packageName.matches(PACKAGE_REGEX)) {
            return ShellResult(ErrorCode.ERR_CMD_FAILED, "", "非法包名", 0L)
        }
        val apk = context.packageCodePath
        val helper = OplusStartupDbHelper::class.java.name
        val inner = "CLASSPATH=$apk app_process /system/bin $helper $packageName $action"
        return runRoot("su -M 1000 -c ${shellQuote(inner)}", OPLUS_TIMEOUT_MS)
    }

    private suspend fun readOplusStartupState(): Boolean? {
        if (targetUserId != 0) return null
        if (!isOplusDevice()) return null
        val result = runOplusHelper("status")
        if (!result.ok) return null
        val text = result.stdout.ifBlank { result.stderr }
        val dbOn = OPLUS_DB_ON_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val xmlOn = OPLUS_XML_ON_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
        return dbOn == 1 && xmlOn == 1
    }

    private fun isOplusDevice(): Boolean {
        val maker = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return OPLUS_MARKERS.any { maker.contains(it) }
    }

    private suspend fun runRoot(command: String, timeoutMs: Long): ShellResult = try {
        shell.exec(command, root = true, timeoutMs = timeoutMs)
    } catch (t: Throwable) {
        ShellResult(
            code = ErrorCode.ERR_CMD_FAILED,
            stdout = "",
            stderr = t.message.orEmpty(),
            durationMs = 0L,
        )
    }

    private fun isBootReceiverEnabled(): Boolean = isComponentEnabled(bootReceiverComponent, isService = false)

    private fun isComponentEnabled(component: ComponentName, isService: Boolean): Boolean {
        val pm = context.packageManager
        return try {
            val appEnabled = pm.getApplicationInfo(packageName, 0).enabled
            if (!appEnabled) return false
            val setting = pm.getComponentEnabledSetting(component)
            if (setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                setting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
            ) {
                return false
            }
            if (setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return true

            val infoEnabled = if (isService) {
                pm.getServiceInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
            } else {
                pm.getReceiverInfo(component, PackageManager.MATCH_DISABLED_COMPONENTS).enabled
            }
            infoEnabled
        } catch (_: Throwable) {
            false
        }
    }

    private fun readBatteryWhitelistState(): Boolean? = try {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName)
    } catch (_: Throwable) {
        null
    }

    private fun isBatteryWhitelisted(): Boolean = readBatteryWhitelistState() ?: false

    private suspend fun readAutostartPreference(): Boolean =
        withTimeoutOrNull(PREF_TIMEOUT_MS) {
            behaviorPreferences.autostartEnabled.first()
        } ?: false

    private suspend fun readKeepAlivePreference(): Boolean =
        withTimeoutOrNull(PREF_TIMEOUT_MS) {
            behaviorPreferences.rootKeepAliveEnabled.first()
        } ?: false

    private fun stopGuardService() {
        runCatching { context.stopService(Intent(context, RootGuardService::class.java)) }
    }

    private fun rootError(query: RootQuery): XgResult.Err {
        val code = when (query.code) {
            ErrorCode.ERR_SU_TIMEOUT -> ErrorCode.ERR_SU_TIMEOUT
            ErrorCode.ERR_SU_MISSING, 126, 127 -> ErrorCode.ERR_SU_MISSING
            ErrorCode.ERR_POLICY_BLOCKED -> ErrorCode.ERR_POLICY_BLOCKED
            ErrorCode.ERR_SELINUX -> ErrorCode.ERR_SELINUX
            else -> ErrorCode.ERR_SU_DENIED
        }
        val detail = query.output.lineSequence().firstOrNull { it.isNotBlank() }?.take(160)
        return XgResult.Err(code, ErrorCatalog.message(code), detail)
    }

    private fun commandDetail(result: ShellResult): String {
        val text = firstLine(result.stdout.ifBlank { result.stderr })
        return "code=${result.code}${if (text.isBlank()) "" else " $text"}"
    }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(180).orEmpty()

    private fun formatStep(step: Step): String {
        val status = when (step.status) {
            StepStatus.OK -> "OK"
            StepStatus.SKIPPED -> if (step.blocking) "UNSUPPORTED-BLOCKING" else "SKIPPED"
            StepStatus.FAILED -> "FAILED"
        }
        return "[$status] ${step.name} " +
            "code=${step.code ?: "local"} readback=${step.readback} " +
            "detail=${step.detail.ifBlank { "-" }}"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private data class RootQuery(
        val rootGranted: Boolean,
        val keepAlive: Boolean,
        val code: Int,
        val output: String,
    )

    private enum class StepStatus { OK, SKIPPED, FAILED }

    private data class Step(
        val name: String,
        val status: StepStatus,
        val code: Int?,
        val readback: String,
        val detail: String = "",
        val blocking: Boolean = true,
    )

    private companion object {
        const val TAG = "RootOpsManager"
        const val ROOT_TIMEOUT_MS = 8_000L
        const val CMD_TIMEOUT_MS = 12_000L
        const val READBACK_TIMEOUT_MS = 8_000L
        const val OPLUS_TIMEOUT_MS = 20_000L
        const val SERVICE_START_TIMEOUT_MS = 3_500L
        const val PREF_TIMEOUT_MS = 3_000L
        const val COMPONENT_READBACK_TIMEOUT_MS = 1_500L
        const val BATTERY_READBACK_TIMEOUT_MS = 1_800L

        const val APP_OP_RUN_IN_BACKGROUND = "RUN_IN_BACKGROUND"
        const val APP_OP_RUN_ANY_IN_BACKGROUND = "RUN_ANY_IN_BACKGROUND"

        val UID_REGEX = Regex("uid=(\\d+)")
        val PACKAGE_REGEX = Regex("[A-Za-z0-9._]+")
        val OPLUS_MARKERS = listOf("oppo", "oneplus", "realme", "oplus")
        val STANDBY_BUCKETS = listOf("exempted", "active", "working_set", "frequent", "rare", "restricted", "never")
        val STANDBY_BUCKET_NUMBERS = mapOf(
            "5" to "exempted",
            "10" to "active",
            "20" to "working_set",
            "30" to "frequent",
            "40" to "rare",
            "45" to "restricted",
            "50" to "never",
        )
        val OPLUS_DB_ON_REGEX = Regex("DB_ON=(\\d+)")
        val OPLUS_XML_ON_REGEX = Regex("XML_ON=(\\d+)")
    }
}


internal fun buildGuardStopCommandFor(pidPath: String, packageName: String): String {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    val pidQuoted = quote(pidPath)
    val pkgQuoted = quote(packageName)
    val command = """
        XG_TOKEN=@D@(printf '%s%s' 'xg-root-guard-v1:' $pkgQuoted)
        if [ -s $pidQuoted ]; then
          old_pid="@D@(cat $pidQuoted 2>/dev/null)"
          if [ -n "@D@old_pid" ] && kill -0 "@D@old_pid" 2>/dev/null && tr '\000' '\n' < "/proc/@D@old_pid/cmdline" 2>/dev/null | grep -F -q "${'$'}XG_TOKEN"; then
            kill "@D@old_pid" 2>/dev/null
          fi
        fi
        for proc in /proc/[0-9]*; do
          guard_pid="@D@{proc##*/}"
          [ "@D@guard_pid" = "@PID@" ] && continue
          if tr '\000' '\n' < "@D@proc/cmdline" 2>/dev/null | grep -F -q "${'$'}XG_TOKEN"; then
            kill "@D@guard_pid" 2>/dev/null
          fi
        done
        sleep 0.5
        for proc in /proc/[0-9]*; do
          guard_pid="@D@{proc##*/}"
          [ "@D@guard_pid" = "@PID@" ] && continue
          if tr '\000' '\n' < "@D@proc/cmdline" 2>/dev/null | grep -F -q "${'$'}XG_TOKEN"; then
            kill -9 "@D@guard_pid" 2>/dev/null
          fi
        done
        rm -f $pidQuoted
        echo STOPPED
    """.trimIndent()
    return command.replace("@D@", "$").replace("@PID@", "$$")
}

internal fun shouldSkipComponentMutation(
    queryState: Boolean?,
    localState: Boolean,
    target: Boolean,
): Boolean {
    val actual = when {
        localState -> true
        queryState != null -> queryState
        else -> false
    }
    return actual == target
}
