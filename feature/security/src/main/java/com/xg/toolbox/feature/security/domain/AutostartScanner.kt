package com.xg.toolbox.feature.security.domain

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.SingleFlightResultCache
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.rootError
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class AutostartScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shell: Shell,
    private val audit: AuditRepository,
    private val rootStatus: RootStatusRepository,
    private val snapshots: SnapshotRepository,
) {

    private val cache = SingleFlightResultCache<List<BootReceiverInfo>>()

    private val protectedPackages: Set<String> = setOf(
        context.packageName,
        "android",
        "com.android.systemui",
        "com.android.phone",
    )

    
    fun detectVendorAutostartManagers(): List<VendorAutostartManager> {
        val packageManager = context.packageManager
        val result = mutableListOf<VendorAutostartManager>()
        for (packageName in VENDOR_AUTOSTART_PACKAGES) {
            val appInfo = try {
                packageManager.getApplicationInfo(packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                continue
            } catch (_: Throwable) {
                continue
            }
            val label = runCatching {
                packageManager.getApplicationLabel(appInfo).toString()
            }.getOrDefault(packageName)
            result += VendorAutostartManager(packageName, label)
        }
        return result
    }

    
    suspend fun list(forceRefresh: Boolean = false): XgResult<List<BootReceiverInfo>> =
        cache.get(forceRefresh) {
            withContext(Dispatchers.IO) {
                try {
                    val packageManager = context.packageManager
                    val packages = packageManager.getInstalledPackages(PACKAGE_FLAGS)
                    val packageByName = packages.associateBy { it.packageName }
                    val resolved = packageManager.queryBroadcastReceivers(
                        Intent(Intent.ACTION_BOOT_COMPLETED),
                        QUERY_FLAGS,
                    )
                    val rows = linkedMapOf<String, BootReceiverInfo>()

                    for (resolveInfo in resolved) {
                        val activity = resolveInfo.activityInfo ?: continue
                        val packageName = activity.packageName ?: continue
                        val packageInfo = packageByName[packageName] ?: continue
                        val app = packageInfo.applicationInfo ?: continue
                        val receiverNames = packageInfo.receivers?.mapNotNull { it.name }?.toSet().orEmpty()
                        if (receiverNames.isNotEmpty() && activity.name !in receiverNames) {
                            continue
                        }
                        val componentPath = normalizeComponent(packageName, activity.name) ?: continue
                        rows[componentPath] = BootReceiverInfo(
                            packageName = packageName,
                            label = labelOf(packageManager, app, packageName),
                            component = componentPath,
                            enabled = isEffectivelyEnabled(
                                packageManager = packageManager,
                                packageName = packageName,
                                className = activity.name,
                                appEnabled = app.enabled,
                                manifestEnabled = activity.enabled,
                            ),
                            source = SOURCE_PACKAGE_MANAGER,
                        )
                    }

                    supplementWithDump(packageManager, rows)
                    XgResult.Ok(rows.values.sortedWith(compareBy({ it.packageName }, { it.component })))
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    XgLog.e(TAG, "读取自启接收器失败", t)
                    XgResult.Err(
                        ErrorCode.ERR_CMD_FAILED,
                        "读取自启接收器失败",
                        t.message,
                    )
                }
            }
        }
    
    suspend fun setEnabled(pkg: String, component: String, enabled: Boolean): XgResult<Unit> =
        withContext(Dispatchers.IO) {
            if (!isValidPackageName(pkg)) {
                recordAudit("", pkg, "", AuditResult.FAILED, "非法包名：$pkg")
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, "非法包名：$pkg")
            }
            if (pkg in protectedPackages) {
                val message = "受保护应用禁止操作：$pkg"
                recordAudit("", pkg, "", AuditResult.FAILED, message)
                return@withContext XgResult.Err(ErrorCode.ERR_POLICY_BLOCKED, message)
            }
            val fullComponent = normalizeComponent(pkg, component)
            if (fullComponent == null) {
                val message = "组件标识非法：$component"
                recordAudit("", pkg, "", AuditResult.FAILED, message)
                return@withContext XgResult.Err(ErrorCode.ERR_PARSE_FAILED, message, component)
            }
            val className = componentClass(fullComponent)
            if (className == null) {
                val message = "组件类名非法：$component"
                recordAudit("", fullComponent, "", AuditResult.FAILED, message)
                return@withContext XgResult.Err(ErrorCode.ERR_PARSE_FAILED, message, component)
            }
            rootStatus.state.value.rootError()?.let { error ->
                recordAudit("", fullComponent, "", AuditResult.FAILED, error.message)
                return@withContext error
            }

            val packageManager = context.packageManager
            val componentName = ComponentName(pkg, className)
            try {
                packageManager.getReceiverInfo(componentName, PackageManager.MATCH_DISABLED_COMPONENTS)
            } catch (t: Throwable) {
                val message = "未找到接收器组件：$fullComponent"
                recordAudit("", fullComponent, "", AuditResult.FAILED, message)
                return@withContext XgResult.Err(ErrorCode.ERR_CMD_FAILED, message, t.message)
            }

            val originalEnabled = componentEnabled(packageManager, componentName)
            if (originalEnabled == null) {
                val message = "读取组件原状态失败，未执行写入：$fullComponent"
                recordAudit("", fullComponent, "", AuditResult.FAILED, message)
                return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, message)
            }
            val snapshotId = try {
                snapshots.create(
                    kind = SECURITY_SNAPSHOT_KIND,
                    target = fullComponent,
                    payload = buildString {
                        append('{')
                        append("\"component\":").append(jsonString(fullComponent)).append(',')
                        append("\"enabled\":").append(originalEnabled)
                        append('}')
                    },
                    ttlMs = SECURITY_SNAPSHOT_TTL_MS,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.e(TAG, "创建组件状态快照失败: $fullComponent", t)
                val message = "创建快照失败，未执行写入"
                recordAudit(
                    "",
                    fullComponent,
                    "",
                    AuditResult.FAILED,
                    "$message：${t.message.orEmpty()}".take(500),
                )
                return@withContext XgResult.Err(ErrorCode.ERR_BACKUP_FAILED, message, t.message)
            }

            val action = if (enabled) "enable" else "disable"
            val command = "pm $action --user 0 ${shellQuote(fullComponent)}"
            val result = try {
                shell.exec(command, root = true, timeoutMs = WRITE_TIMEOUT_MS)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.w(TAG, "组件启停命令异常: ${t.javaClass.simpleName}")
                null
            }
            val readback = componentEnabled(packageManager, componentName)
            val success = result?.ok == true && readback == enabled
            cache.invalidate()

            if (success) {
                recordAudit(
                    action = ACTION_SET_RECEIVER,
                    target = fullComponent,
                    commands = command,
                    result = AuditResult.OK,
                    message = if (enabled) "已启用开机接收器" else "已禁用开机接收器",
                    snapshotId = snapshotId,
                )
                return@withContext XgResult.Ok(Unit)
            }

            val error = when {
                result == null -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "组件启停命令未能执行",
                    "shell 调用异常",
                )

                !result.ok -> XgResult.Err(
                    shellFailureCode(result),
                    "组件启停失败：${shellFailureMessage(result)}",
                    shellDetail(result),
                )

                readback == null -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "组件状态回读失败",
                    "无法读取 $fullComponent 的启用状态",
                )

                else -> XgResult.Err(
                    ErrorCode.ERR_CMD_FAILED,
                    "组件状态回读与预期不符",
                    "期望 ${if (enabled) "启用" else "禁用"}，实际 ${if (readback == true) "启用" else "禁用"}",
                )
            }
            recordAudit(
                action = ACTION_SET_RECEIVER,
                target = fullComponent,
                commands = command,
                result = AuditResult.FAILED,
                message = "${error.message}：${error.detail.orEmpty()}".take(500),
                snapshotId = snapshotId,
            )
            error
        }

    private suspend fun supplementWithDump(
        packageManager: PackageManager,
        rows: MutableMap<String, BootReceiverInfo>,
    ) {
        if (rows.isEmpty() || !hasRoot()) return
        val byPackage = rows.values.groupBy { it.packageName }
        val targets = byPackage.keys
            .sortedWith(compareBy({ isSystemPackage(packageManager, it) }, { it }))
            .take(MAX_ROOT_DUMPS)
        val startedAt = SystemClock.elapsedRealtime()
        var dumped = 0

        for (packageName in targets) {
            if (SystemClock.elapsedRealtime() - startedAt > ROOT_DUMP_BUDGET_MS) {
                XgLog.w(TAG, "pm dump 补充达到时间预算，已跳过剩余 ${targets.size - dumped} 个包")
                break
            }
            val result = try {
                shell.exec(
                    "pm dump ${shellQuote(packageName)}",
                    root = true,
                    timeoutMs = ROOT_DUMP_TIMEOUT_MS,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.w(TAG, "pm dump 异常: $packageName: ${t.javaClass.simpleName}")
                continue
            }
            if (!result.ok) {
                XgLog.w(TAG, "pm dump 失败: $packageName: ${shellFailureMessage(result)}")
                continue
            }
            dumped += 1

            val disabledClasses = parseDumpSection(result.stdout, "disabledComponents")
            val enabledClasses = parseDumpSection(result.stdout, "enabledComponents")
            val dumpedComponents = parseBootComponents(result.stdout, packageName)

            for ((key, row) in rows.entries.toList()) {
                if (row.packageName != packageName) continue
                val className = componentClass(row.component) ?: continue
                val enabled = when {
                    listed(disabledClasses, className, row.component, packageName) -> false
                    listed(enabledClasses, className, row.component, packageName) -> true
                    else -> row.enabled
                }
                rows[key] = row.copy(enabled = enabled, source = SOURCE_PACKAGE_MANAGER_DUMP)
            }

            for (component in dumpedComponents) {
                if (rows.containsKey(component)) continue
                val className = componentClass(component) ?: continue
                val app = runCatching {
                    packageManager.getApplicationInfo(packageName, 0)
                }.getOrNull() ?: continue
                rows[component] = BootReceiverInfo(
                    packageName = packageName,
                    label = labelOf(packageManager, app, packageName),
                    component = component,
                    enabled = !listed(disabledClasses, className, component, packageName),
                    source = SOURCE_DUMP,
                )
            }
        }

        if (dumped < byPackage.size) {
            XgLog.w(
                TAG,
                "pm dump 补充覆盖 $dumped/${byPackage.size} 个包" +
                    "（上限 $MAX_ROOT_DUMPS，预算 ${ROOT_DUMP_BUDGET_MS}ms）",
            )
        }
    }

    private suspend fun hasRoot(): Boolean {
        when (rootStatus.state.value) {
            is RootState.Granted -> return true
            is RootState.Unknown -> Unit
            else -> return false
        }
        val result = try {
            shell.exec("id -u", root = true, timeoutMs = ROOT_PROBE_TIMEOUT_MS)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.w(TAG, "Root 探测异常: ${t.javaClass.simpleName}")
            return false
        }
        return result.ok && result.stdout.trim() == "0"
    }

    private fun parseDumpSection(dump: String, section: String): Set<String> {
        val lines = dump.lines()
        val start = lines.indexOfFirst { it.trim() == "$section:" }
        if (start < 0) return emptySet()
        val baseIndent = indentOf(lines[start])
        val entries = linkedSetOf<String>()
        for (index in start + 1 until lines.size) {
            val line = lines[index]
            if (line.isBlank()) continue
            if (indentOf(line) <= baseIndent) break
            entries += line.trim()
        }
        return entries
    }

    private fun parseBootComponents(dump: String, packageName: String): Set<String> {
        val result = linkedSetOf<String>()
        val lines = dump.lines()
        var inReceiverTable = false
        var inBootAction = false
        var bootIndent = -1

        for (line in lines) {
            val text = line.trim()
            if (!inReceiverTable) {
                if (text == "Receiver Resolver Table:") inReceiverTable = true
                continue
            }
            if (text == "Service Resolver Table:" || text == "Provider Resolver Table:") break
            if (text == BOOT_ACTION_HEADER) {
                inBootAction = true
                bootIndent = indentOf(line)
                continue
            }
            if (!inBootAction) continue
            if (text.isBlank()) continue
            if (indentOf(line) <= bootIndent && text.endsWith(":")) {
                inBootAction = false
                continue
            }
            val match = COMPONENT_ENTRY.find(line) ?: continue
            val component = normalizeComponent(packageName, match.groupValues[1]) ?: continue
            result += component
        }
        return result
    }

    private fun indentOf(line: String): Int {
        val index = line.indexOfFirst { it != ' ' }
        return if (index < 0) line.length else index
    }

    private fun listed(
        entries: Set<String>,
        className: String,
        component: String,
        packageName: String,
    ): Boolean = entries.contains(className) ||
        entries.contains(component) ||
        entries.contains("$packageName/$className")

    private fun isEffectivelyEnabled(
        packageManager: PackageManager,
        packageName: String,
        className: String,
        appEnabled: Boolean,
        manifestEnabled: Boolean,
    ): Boolean {
        if (!appEnabled) return false
        return when (
            packageManager.getComponentEnabledSetting(ComponentName(packageName, className))
        ) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false

            else -> manifestEnabled
        }
    }

    
    private fun componentEnabled(
        packageManager: PackageManager,
        componentName: ComponentName,
    ): Boolean? = try {
        val receiver = packageManager.getReceiverInfo(
            componentName,
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        when (packageManager.getComponentEnabledSetting(componentName)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false

            else -> receiver.enabled
        }
    } catch (t: Throwable) {
        XgLog.w(TAG, "组件状态读取失败: ${componentName.flattenToShortString()}: ${t.javaClass.simpleName}")
        null
    }

    private fun isSystemPackage(packageManager: PackageManager, packageName: String): Boolean =
        runCatching {
            val info = packageManager.getApplicationInfo(packageName, 0)
            (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        }.getOrDefault(false)

    private fun labelOf(
        packageManager: PackageManager,
        app: ApplicationInfo,
        packageName: String,
    ): String = runCatching {
        packageManager.getApplicationLabel(app).toString()
    }.getOrDefault(packageName)

    private suspend fun recordAudit(
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String?,
        snapshotId: Long? = null,
    ) {
        try {
            audit.record(
                category = AuditCategory.SECURITY,
                action = if (action.isBlank()) ACTION_SET_RECEIVER else action,
                target = target,
                commands = commands,
                result = result,
                message = message,
                snapshotId = snapshotId,
            )
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.w(TAG, "写入审计失败: ${t.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "AutostartScanner"
        const val SOURCE_PACKAGE_MANAGER = "package_manager"
        const val SOURCE_PACKAGE_MANAGER_DUMP = "package_manager+pm_dump"
        const val SOURCE_DUMP = "pm_dump"
        const val ACTION_SET_RECEIVER = "set_boot_receiver"
        const val BOOT_ACTION_HEADER = "android.intent.action.BOOT_COMPLETED:"

        const val MAX_ROOT_DUMPS = 24

        
        private val VENDOR_AUTOSTART_PACKAGES = listOf(
            "com.miui.securitycenter",
            "com.oplus.safecenter",
            "com.coloros.safecenter",
            "com.vivo.permissionmanager",
            "com.huawei.systemmanager",
            "com.samsung.android.lool",
        )
        const val ROOT_DUMP_BUDGET_MS = 15_000L
        const val ROOT_DUMP_TIMEOUT_MS = 8_000L
        const val ROOT_PROBE_TIMEOUT_MS = 3_000L
        const val WRITE_TIMEOUT_MS = 15_000L

        val PACKAGE_FLAGS = PackageManager.GET_RECEIVERS or PackageManager.MATCH_DISABLED_COMPONENTS
        val QUERY_FLAGS = PackageManager.MATCH_ALL or
            PackageManager.MATCH_DISABLED_COMPONENTS or
            PackageManager.MATCH_DIRECT_BOOT_AWARE or
            PackageManager.MATCH_DIRECT_BOOT_UNAWARE

        val COMPONENT_ENTRY = Regex(
            "^\\s*(?:[0-9a-fA-F]+\\s+)?([A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+)\\b",
        )
    }
}
