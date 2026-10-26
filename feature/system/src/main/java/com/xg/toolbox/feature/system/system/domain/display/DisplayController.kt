package com.xg.toolbox.feature.system.domain.display

import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.SingleFlightResultCache
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AuditCategory
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.data.SnapshotRepository
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.shell.Shell
import com.xg.toolbox.core.shell.ShellResult
import com.xg.toolbox.feature.system.domain.perf.ShellIo
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class DisplayController @Inject constructor(
    private val shell: Shell,
    private val rootStatus: RootStatusRepository,
    private val auditRepository: AuditRepository,
    private val snapshotRepository: SnapshotRepository,
) {

    private val probe = DisplayProbe(shell)
    private val actionMutex = Mutex()
    private val cache = SingleFlightResultCache<DisplayState>()

    
    suspend fun readState(forceRefresh: Boolean = false): XgResult<DisplayState> =
        cache.get(forceRefresh) {
            withContext(Dispatchers.IO) {
                ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
                val snapshot = probe.probe()
                    ?: return@withContext ShellIo.err(
                        ErrorCode.ERR_PARSE_FAILED,
                        "无法解析 wm size / wm density 输出",
                    )
                XgResult.Ok(
                    DisplayState(
                        size = snapshot.size,
                        physicalSize = snapshot.physicalSize,
                        density = snapshot.density,
                        refreshOptions = snapshot.refreshOptionsHz,
                        currentPeakHz = snapshot.peakHz,
                        currentMinHz = snapshot.minHz,
                        forceHighRefresh = snapshot.forceHighRefresh,
                        touchSampleOptions = snapshot.touchSampleOptions,
                        touchSampleHz = snapshot.touchSampleHz,
                        dcSupported = snapshot.dcSource != null,
                        dcEnabled = snapshot.dcEnabled,
                        colorTemperatureSupported = snapshot.colorTemperatureSource != null,
                        colorTemperature = snapshot.colorTemperature,
                        nightMode = snapshot.nightMode,
                        densityRange = DENSITY_RANGE,
                    ),
                )
            }
        }

    
    suspend fun setSize(width: Int, height: Int): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            val snapshot = probe.probe()
                ?: return@withLock ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法解析当前分辨率")
            validateSize(snapshot, width, height)?.let {
                return@withLock ShellIo.err(ErrorCode.ERR_CMD_FAILED, it)
            }
            val snapshotId = snapshotRepository.create(
                KIND_DISPLAY_STATE,
                TARGET_SIZE,
                json("size" to snapshot.size, "overrideSize" to snapshot.overrideSize),
                SNAPSHOT_TTL_MS,
            )
            val command = "wm size ${width}x$height"
            val result = shell.exec(command, root = true, timeoutMs = 20_000)
            if (!result.ok) {
                return@withLock fail(
                    action = "display_set_size",
                    target = "${width}x$height",
                    commands = command,
                    snapshotId = snapshotId,
                    message = "分辨率写入命令失败",
                    error = ShellIo.errFromShell(result, "修改分辨率失败"),
                )
            }
            val readback = readWmSize()
            val actual = readback?.overrideSize
            val expected = "${width}x$height"
            if (actual != expected) {
                return@withLock fail(
                    action = "display_set_size",
                    target = expected,
                    commands = command,
                    snapshotId = snapshotId,
                    message = "回读 override=$actual，期望 $expected",
                    error = ShellIo.err(ErrorCode.ERR_CMD_FAILED, "分辨率回读不一致（实际 ${actual ?: "无 override"}）"),
                )
            }
            recordAudit(
                action = "display_set_size",
                target = expected,
                commands = command,
                result = AuditResult.OK,
                message = "分辨率已设为 $expected（可用 resetSize 恢复）",
                snapshotId = snapshotId,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        }
    }

    
    suspend fun resetSize(): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            val snapshot = probe.probe()
                ?: return@withLock ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法读取当前分辨率")
            val snapshotId = snapshotRepository.create(
                KIND_DISPLAY_STATE,
                TARGET_SIZE,
                json(
                    "physicalSize" to snapshot.physicalSize,
                    "overrideSize" to snapshot.overrideSize,
                ),
                SNAPSHOT_TTL_MS,
            )
            val command = "wm size reset"
            val result = shell.exec(command, root = true, timeoutMs = 20_000)
            if (!result.ok) {
                return@withLock fail(
                    action = "display_reset_size",
                    target = "wm size",
                    commands = command,
                    snapshotId = snapshotId,
                    message = "恢复分辨率失败",
                    error = ShellIo.errFromShell(result, "恢复分辨率失败"),
                )
            }
            val readback = readWmSize()
            if (readback?.overrideSize != null) {
                return@withLock fail(
                    action = "display_reset_size",
                    target = "wm size",
                    commands = command,
                    snapshotId = snapshotId,
                    message = "回读仍存在 override=${readback.overrideSize}",
                    error = ShellIo.err(ErrorCode.ERR_CMD_FAILED, "恢复分辨率后仍有 override（${readback.overrideSize}）"),
                )
            }
            recordAudit(
                action = "display_reset_size",
                target = "wm size",
                commands = command,
                result = AuditResult.OK,
                message = "已恢复面板物理分辨率",
                snapshotId = snapshotId,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        }
    }

    
    suspend fun setDensity(dpi: Int): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            if (dpi !in DENSITY_RANGE) {
                return@withLock ShellIo.err(
                    ErrorCode.ERR_CMD_FAILED,
                    "密度必须在 ${DENSITY_RANGE.first}–${DENSITY_RANGE.last} dpi 之间（当前输入 $dpi）",
                )
            }
            val snapshot = probe.probe()
                ?: return@withLock ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法解析当前密度")
            val snapshotId = snapshotRepository.create(
                KIND_DISPLAY_STATE,
                TARGET_DENSITY,
                json("density" to snapshot.density, "overrideDensity" to snapshot.overrideDensity),
                SNAPSHOT_TTL_MS,
            )
            val command = "wm density $dpi"
            val result = shell.exec(command, root = true, timeoutMs = 20_000)
            if (!result.ok) {
                return@withLock fail(
                    action = "display_set_density",
                    target = dpi.toString(),
                    commands = command,
                    snapshotId = snapshotId,
                    message = "密度写入命令失败",
                    error = ShellIo.errFromShell(result, "修改密度失败"),
                )
            }
            val readback = readWmDensity()?.overrideDensity
            if (readback != dpi) {
                return@withLock fail(
                    action = "display_set_density",
                    target = dpi.toString(),
                    commands = command,
                    snapshotId = snapshotId,
                    message = "回读 override=$readback，期望 $dpi",
                    error = ShellIo.err(ErrorCode.ERR_CMD_FAILED, "密度回读不一致（实际 ${readback ?: "无 override"}）"),
                )
            }
            recordAudit(
                action = "display_set_density",
                target = dpi.toString(),
                commands = command,
                result = AuditResult.OK,
                message = "显示密度已设为 $dpi dpi（可用 resetDensity 恢复）",
                snapshotId = snapshotId,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        }
    }

    
    suspend fun resetDensity(): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            val snapshot = probe.probe()
                ?: return@withLock ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法读取当前密度")
            val snapshotId = snapshotRepository.create(
                KIND_DISPLAY_STATE,
                TARGET_DENSITY,
                json(
                    "physicalDensity" to snapshot.physicalDensity,
                    "overrideDensity" to snapshot.overrideDensity,
                ),
                SNAPSHOT_TTL_MS,
            )
            val command = "wm density reset"
            val result = shell.exec(command, root = true, timeoutMs = 20_000)
            if (!result.ok) {
                return@withLock fail(
                    action = "display_reset_density",
                    target = "wm density",
                    commands = command,
                    snapshotId = snapshotId,
                    message = "恢复密度失败",
                    error = ShellIo.errFromShell(result, "恢复密度失败"),
                )
            }
            val readback = readWmDensity()?.overrideDensity
            if (readback != null) {
                return@withLock fail(
                    action = "display_reset_density",
                    target = "wm density",
                    commands = command,
                    snapshotId = snapshotId,
                    message = "回读仍存在 override=$readback",
                    error = ShellIo.err(ErrorCode.ERR_CMD_FAILED, "恢复密度后仍有 override（$readback）"),
                )
            }
            recordAudit(
                action = "display_reset_density",
                target = "wm density",
                commands = command,
                result = AuditResult.OK,
                message = "已恢复面板物理密度",
                snapshotId = snapshotId,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        }
    }

    
    suspend fun setRefreshHz(hz: Float, force: Boolean): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            val snapshot = probe.probe()
                ?: return@withLock ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法读取当前刷新率设置")
            val snapshotId = snapshotRepository.create(
                KIND_DISPLAY_STATE,
                TARGET_REFRESH,
                json(
                    "peak" to snapshot.peakHz,
                    "min" to snapshot.minHz,
                    "user" to snapshot.userHz,
                ),
                SNAPSHOT_TTL_MS,
            )
            if (hz <= 0f) {
                return@withLock restoreAdaptiveRefresh(snapshotId)
            }
            val supported = snapshot.refreshOptionsHz
            if (supported.isEmpty()) {
                return@withLock ShellIo.err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    "dumpsys display 未解析到任何刷新率档位，无法设置",
                )
            }
            val target = supported.firstOrNull { abs(it - hz) < 0.6f }
                ?: return@withLock ShellIo.err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    if (abs(hz - 185f) < 0.6f) {
                        "面板不支持 185Hz（实际支持：${supported.joinToString("/")} Hz）"
                    } else {
                        "面板不支持 ${formatHz(hz)}Hz（实际支持：${supported.joinToString("/")} Hz）"
                    },
                )
            val commands = mutableListOf(
                "settings put system peak_refresh_rate $target",
                "settings put system user_refresh_rate $target",
            )
            if (force) {
                commands += "settings put system min_refresh_rate $target"
            } else {
                commands += "settings delete system min_refresh_rate"
            }
            for (command in commands) {
                val result = shell.exec(command, root = true, timeoutMs = 8_000)
                if (!result.ok) {
                    return@withLock fail(
                        action = "display_set_refresh",
                        target = "$target Hz",
                        commands = commands.joinToString("\n"),
                        snapshotId = snapshotId,
                        message = "刷新率写入命令失败：$command",
                        error = ShellIo.errFromShell(result, "刷新率写入失败"),
                    )
                }
            }
            val readback = readRefreshSettings()
            val peakOk = readback.peak != null && abs(readback.peak - target) < 0.6f
            val minOk = if (force) {
                readback.min != null && abs(readback.min - target) < 0.6f
            } else {
                readback.min == null
            }
            if (!peakOk || !minOk) {
                return@withLock fail(
                    action = "display_set_refresh",
                    target = "$target Hz",
                    commands = commands.joinToString("\n"),
                    snapshotId = snapshotId,
                    message = "回读 peak=${readback.peak} min=${readback.min}，期望 ${if (force) "peak=min=$target" else "peak=$target、min=空"}",
                    error = ShellIo.err(
                        ErrorCode.ERR_CMD_FAILED,
                        "刷新率回读不一致（peak=${readback.peak ?: "空"}，min=${readback.min ?: "空"}）",
                    ),
                )
            }
            val userNote = if (readback.user == null || abs(readback.user - target) >= 0.6f) {
                "；user_refresh_rate 未生效（厂商可能忽略该键）"
            } else {
                ""
            }
            recordAudit(
                action = "display_set_refresh",
                target = "$target Hz",
                commands = commands.joinToString("\n"),
                result = AuditResult.OK,
                message = "已设置刷新率上限 $target Hz${if (force) "（强制高刷）" else ""}$userNote",
                snapshotId = snapshotId,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        }
    }

    
    suspend fun setTouchSampleHz(hz: Int?): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            val snapshot = probe.probe()
                ?: return@withLock ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法读取显示状态")
            val node = snapshot.touchNode
                ?: return@withLock ShellIo.err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    "当前设备未提供触控采样率节点（厂商私有节点，不支持强制采样率）",
                )
            if (hz == null) {
                val origin = snapshotRepository.latest(KIND_DISPLAY_STATE, TARGET_TOUCH)
                val originId = origin?.id
                val originalValue = origin?.let { runCatching { JSONObject(it.payload).optInt("value", -1) }.getOrNull() }
                    ?.takeIf { it > 0 }
                    ?: return@withLock ShellIo.err(
                        ErrorCode.ERR_CMD_FAILED,
                        "没有可恢复的原始触控采样率记录",
                    )
                return@withLock writeTouchSample(node, originalValue, originId)
            }
            if (snapshot.touchSampleOptions.isNotEmpty() && hz !in snapshot.touchSampleOptions) {
                return@withLock ShellIo.err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    "采样率 $hz Hz 不在节点可用档位：${snapshot.touchSampleOptions.joinToString("/")}",
                )
            }
            if (snapshot.touchSampleOptions.isEmpty() && hz != snapshot.touchSampleHz) {
                return@withLock ShellIo.err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    "节点未提供可写档位列表，仅支持写回当前值（${snapshot.touchSampleHz ?: "未知"}）",
                )
            }
            val snapshotId = snapshotRepository.create(
                KIND_DISPLAY_STATE,
                TARGET_TOUCH,
                json("node" to node, "value" to snapshot.touchSampleHz),
                SNAPSHOT_TTL_MS,
            )
            writeTouchSample(node, hz, snapshotId)
        }
    }

    
    suspend fun setDcDimming(enabled: Boolean): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            val snapshot = probe.probe()
                ?: return@withLock ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法读取显示状态")
            val source = snapshot.dcSource
                ?: return@withLock ShellIo.err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    "未检测到 DC 调光开关（厂商节点与系统键均不存在），不支持该功能",
                )
            val target = if (enabled) "1" else "0"
            val snapshotId = snapshotRepository.create(
                KIND_DISPLAY_STATE,
                TARGET_DC,
                json("enabled" to snapshot.dcEnabled),
                SNAPSHOT_TTL_MS,
            )
            val command = commandFor(source, target)
            val result = writeSwitch(source, target)
            if (!result.ok) {
                return@withLock fail(
                    action = "display_set_dc",
                    target = if (enabled) "on" else "off",
                    commands = command,
                    snapshotId = snapshotId,
                    message = "DC 调光写入失败",
                    error = ShellIo.errFromShell(result, "DC 调光写入失败"),
                )
            }
            val readback = readSwitch(source)
            if (readback != target) {
                return@withLock fail(
                    action = "display_set_dc",
                    target = if (enabled) "on" else "off",
                    commands = command,
                    snapshotId = snapshotId,
                    message = "回读 $readback，期望 $target",
                    error = ShellIo.err(ErrorCode.ERR_CMD_FAILED, "DC 调光回读不一致（实际 ${readback ?: "读取失败"}）"),
                )
            }
            recordAudit(
                action = "display_set_dc",
                target = if (enabled) "on" else "off",
                commands = command,
                result = AuditResult.OK,
                message = if (enabled) "已开启 DC 调光" else "已关闭 DC 调光",
                snapshotId = snapshotId,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        }
    }

    
    suspend fun setColorTemperature(kelvin: Int): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        actionMutex.withLock {
            if (kelvin !in COLOR_TEMPERATURE_RANGE) {
                return@withLock ShellIo.err(
                    ErrorCode.ERR_CMD_FAILED,
                    "色温必须在 ${COLOR_TEMPERATURE_RANGE.first}–${COLOR_TEMPERATURE_RANGE.last}K 之间（当前输入 $kelvin）",
                )
            }
            val snapshot = probe.probe()
                ?: return@withLock ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法读取显示状态")
            val source = snapshot.colorTemperatureSource
                ?: return@withLock ShellIo.err(
                    ErrorCode.ERR_UNSUPPORTED_DEVICE,
                    "未检测到色温节点/系统键，该机型不支持色温调节",
                )
            val snapshotId = snapshotRepository.create(
                KIND_DISPLAY_STATE,
                TARGET_COLOR_TEMPERATURE,
                json("kelvin" to snapshot.colorTemperature),
                SNAPSHOT_TTL_MS,
            )
            val command = commandFor(source, kelvin.toString())
            val result = writeSwitch(source, kelvin.toString())
            if (!result.ok) {
                return@withLock fail(
                    action = "display_set_color_temperature",
                    target = kelvin.toString(),
                    commands = command,
                    snapshotId = snapshotId,
                    message = "色温写入失败",
                    error = ShellIo.errFromShell(result, "色温写入失败"),
                )
            }
            val readback = readSwitch(source)?.trim()?.toIntOrNull()
            if (readback != kelvin) {
                return@withLock fail(
                    action = "display_set_color_temperature",
                    target = kelvin.toString(),
                    commands = command,
                    snapshotId = snapshotId,
                    message = "回读 $readback，期望 $kelvin",
                    error = ShellIo.err(ErrorCode.ERR_CMD_FAILED, "色温回读不一致（实际 ${readback ?: "读取失败"}）"),
                )
            }
            recordAudit(
                action = "display_set_color_temperature",
                target = kelvin.toString(),
                commands = command,
                result = AuditResult.OK,
                message = "色温已设为 ${kelvin}K",
                snapshotId = snapshotId,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        }
    }

    
    suspend fun setNightMode(mode: String): XgResult<Unit> = withContext(Dispatchers.IO) {
        ShellIo.requireRoot(rootStatus.state.value)?.let { return@withContext it }
        val normalized = mode.trim().lowercase()
        if (normalized !in NIGHT_MODES) {
            return@withContext ShellIo.err(ErrorCode.ERR_CMD_FAILED, "未知的深色模式：$mode（仅支持 yes/no/auto）")
        }
        actionMutex.withLock {
            val snapshot = probe.probe()
                ?: return@withLock ShellIo.err(ErrorCode.ERR_PARSE_FAILED, "无法读取当前深色模式")
            val snapshotId = snapshotRepository.create(
                KIND_DISPLAY_STATE,
                TARGET_NIGHT_MODE,
                json("nightMode" to snapshot.nightMode),
                SNAPSHOT_TTL_MS,
            )
            val command = "cmd uimode night $normalized"
            val result = shell.exec(command, root = true, timeoutMs = 10_000)
            if (!result.ok) {
                return@withLock fail(
                    action = "display_set_night_mode",
                    target = normalized,
                    commands = command,
                    snapshotId = snapshotId,
                    message = "深色模式写入失败",
                    error = ShellIo.errFromShell(result, "深色模式写入失败"),
                )
            }
            val readbackResult = shell.exec("cmd uimode night", root = true, timeoutMs = 8_000)
            val readback = parseNightMode(readbackResult.stdout)
            if (readback != normalized) {
                return@withLock fail(
                    action = "display_set_night_mode",
                    target = normalized,
                    commands = command,
                    snapshotId = snapshotId,
                    message = "回读 ${readback ?: "无法解析"}，期望 $normalized",
                    error = ShellIo.err(
                        if (readback == null) ErrorCode.ERR_PARSE_FAILED else ErrorCode.ERR_CMD_FAILED,
                        "深色模式回读不一致（实际 ${readback ?: "无法解析命令输出"}）",
                    ),
                )
            }
            recordAudit(
                action = "display_set_night_mode",
                target = normalized,
                commands = command,
                result = AuditResult.OK,
                message = "深色模式已切换为 $normalized",
                snapshotId = snapshotId,
            )
            cache.invalidate()
            XgResult.Ok(Unit)
        }
    }

    private suspend fun restoreAdaptiveRefresh(snapshotId: Long): XgResult<Unit> {
        val commands = listOf(
            "settings delete system peak_refresh_rate",
            "settings delete system min_refresh_rate",
            "settings delete system user_refresh_rate",
        )
        for (command in commands) {
            val result = shell.exec(command, root = true, timeoutMs = 8_000)
            if (!result.ok) {
                return fail(
                    action = "display_reset_refresh",
                    target = "refresh",
                    commands = commands.joinToString("\n"),
                    snapshotId = snapshotId,
                    message = "恢复自适应刷新率失败：$command",
                    error = ShellIo.errFromShell(result, "恢复自适应刷新率失败"),
                )
            }
        }
        val readback = readRefreshSettings()
        if (readback.peak != null || readback.min != null) {
            return fail(
                action = "display_reset_refresh",
                target = "refresh",
                commands = commands.joinToString("\n"),
                snapshotId = snapshotId,
                message = "删除后仍读到 peak=${readback.peak} min=${readback.min}",
                error = ShellIo.err(ErrorCode.ERR_CMD_FAILED, "恢复自适应刷新率后仍存在刷新率锁定"),
            )
        }
        recordAudit(
            action = "display_reset_refresh",
            target = "refresh",
            commands = commands.joinToString("\n"),
            result = AuditResult.OK,
            message = "已恢复系统自适应刷新率",
            snapshotId = snapshotId,
        )
        cache.invalidate()
        return XgResult.Ok(Unit)
    }

    private suspend fun writeTouchSample(node: String, hz: Int, snapshotId: Long?): XgResult<Unit> {
        val command = "echo $hz > $node"
        val result = ShellIo.writeFile(shell, node, hz.toString())
        if (!result.ok) {
            return fail(
                action = "display_set_touch_sample",
                target = hz.toString(),
                commands = command,
                snapshotId = snapshotId,
                message = "触控采样率写入失败",
                error = ShellIo.errFromShell(result, "触控采样率写入失败"),
            )
        }
        val readback = ShellIo.readFile(shell, node)?.trim()?.toIntOrNull()
        if (readback != hz) {
            return fail(
                action = "display_set_touch_sample",
                target = hz.toString(),
                commands = command,
                snapshotId = snapshotId,
                message = "回读 $readback，期望 $hz",
                error = ShellIo.err(ErrorCode.ERR_CMD_FAILED, "触控采样率回读不一致（实际 ${readback ?: "读取失败"}）"),
            )
        }
        recordAudit(
            action = "display_set_touch_sample",
            target = hz.toString(),
            commands = command,
            result = AuditResult.OK,
            message = "触控采样率已设为 $hz Hz",
            snapshotId = snapshotId,
        )
        cache.invalidate()
        return XgResult.Ok(Unit)
    }

    
    private fun validateSize(snapshot: DisplaySnapshot, width: Int, height: Int): String? {
        val physical = parseDimension(snapshot.physicalSize)
            ?: return "无法解析物理分辨率 ${snapshot.physicalSize}"
        if (width <= 0 || height <= 0) return "分辨率必须为正数"
        if (width % 2 != 0 || height % 2 != 0) return "分辨率宽高必须为偶数（面板对齐要求）"
        if (width >= height) return "分辨率需按竖屏（宽 < 高）填写，当前 ${width}x$height"
        val currentOverride = snapshot.overrideSize?.let { parseDimension(it) }
        val allowed = (buildAllowedSizes(physical) + listOfNotNull(currentOverride)).distinct()
        if (allowed.none { it.first == width && it.second == height }) {
            val candidates = allowed.joinToString(" / ") { "${it.first}x${it.second}" }
            return "仅允许面板原生或常用档（$candidates），拒绝非常用分辨率 ${width}x$height"
        }
        val physicalRatio = physical.first.toDouble() / physical.second
        val ratio = width.toDouble() / height
        if (abs(ratio - physicalRatio) / physicalRatio > MAX_ASPECT_DRIFT) {
            return "纵横比与面板不一致（面板 ${formatRatio(physicalRatio)}，输入 ${formatRatio(ratio)}），已拒绝以防显示异常"
        }
        return null
    }

    
    private fun buildAllowedSizes(physical: Pair<Int, Int>): List<Pair<Int, Int>> {
        val sizes = mutableListOf(physical)
        ALLOWED_SIZE_SCALES.forEach { scale ->
            val width = evenDimension((physical.first * scale).toInt())
            val height = evenDimension((physical.second * scale).toInt())
            if (width > 0 && height > 0 && width < height) sizes += width to height
        }
        return sizes.distinct()
    }

    private fun evenDimension(value: Int): Int {
        val even = if (value % 2 == 0) value else value - 1
        return if (even > 0) even else 2
    }

    private suspend fun readWmSize(): WmSizeReadback? {
        val result = shell.exec("wm size", root = true, timeoutMs = 10_000)
        if (!result.ok) return null
        val physical = Regex("Physical size:\\s*(\\d+x\\d+)").find(result.stdout)?.groupValues?.get(1)
            ?: return null
        val override = Regex("Override size:\\s*(\\d+x\\d+)").find(result.stdout)?.groupValues?.get(1)
        return WmSizeReadback(physical = physical, overrideSize = override)
    }

    private suspend fun readWmDensity(): WmDensityReadback? {
        val result = shell.exec("wm density", root = true, timeoutMs = 10_000)
        if (!result.ok) return null
        val physical = Regex("Physical density:\\s*(\\d+)").find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val override = Regex("Override density:\\s*(\\d+)").find(result.stdout)?.groupValues?.get(1)?.toIntOrNull()
        return WmDensityReadback(physical = physical, overrideDensity = override)
    }

    private suspend fun readRefreshSettings(): RefreshReadback {
        val result = shell.exec(
            "settings get system peak_refresh_rate; settings get system min_refresh_rate; settings get system user_refresh_rate",
            root = true,
            timeoutMs = 10_000,
        )
        val lines = result.stdout.lineSequence().map { it.trim() }.toList()
        return RefreshReadback(
            peak = lines.getOrNull(0)?.toFloatOrNull(),
            min = lines.getOrNull(1)?.toFloatOrNull(),
            user = lines.getOrNull(2)?.toFloatOrNull(),
        )
    }

    private suspend fun writeSwitch(source: VendorSwitch, value: String): ShellResult = when (source) {
        is VendorSwitch.SettingsKey -> ShellIo.settingsPut(shell, source.namespace, source.key, value)
        is VendorSwitch.NodePath -> ShellIo.writeFile(shell, source.path, value)
    }

    private suspend fun readSwitch(source: VendorSwitch): String? = when (source) {
        is VendorSwitch.SettingsKey -> ShellIo.settingsGet(shell, source.namespace, source.key)
        is VendorSwitch.NodePath -> ShellIo.readFile(shell, source.path)
    }

    private fun commandFor(source: VendorSwitch, value: String): String = when (source) {
        is VendorSwitch.SettingsKey -> "settings put ${source.namespace} ${source.key} $value"
        is VendorSwitch.NodePath -> "echo $value > ${source.path}"
    }

    private fun parseNightMode(output: String): String? {
        val raw = output.lineSequence()
            .firstOrNull { it.contains("Night mode", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?: return null
        return when {
            raw.startsWith("yes") -> "yes"
            raw.startsWith("no") -> "no"
            raw.startsWith("auto") -> "auto"
            else -> null
        }
    }

    private fun parseDimension(value: String): Pair<Int, Int>? {
        val match = Regex("(\\d+)x(\\d+)").find(value) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        return width to height
    }

    private fun formatRatio(ratio: Double): String = String.format(Locale.US, "%.4f", ratio)

    private fun formatHz(hz: Float): String =
        if (abs(hz - hz.toInt()) < 0.05f) hz.toInt().toString() else String.format(Locale.US, "%.1f", hz)

    private fun json(vararg pairs: Pair<String, Any?>): String = JSONObject().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> put(key, JSONObject.NULL)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Float -> put(key, value.toDouble())
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }.toString()

    private suspend fun fail(
        action: String,
        target: String,
        commands: String,
        snapshotId: Long?,
        message: String,
        error: XgResult.Err,
    ): XgResult.Err {
        cache.invalidate()
        recordAudit(action, target, commands, AuditResult.FAILED, message, snapshotId)
        return error
    }

    private suspend fun recordAudit(
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String? = null,
        snapshotId: Long? = null,
    ) {
        runCatching {
            auditRepository.record(
                category = AuditCategory.SYSTEM,
                action = action,
                target = target,
                commands = commands.take(MAX_AUDIT_COMMANDS),
                result = result,
                message = message,
                snapshotId = snapshotId,
            )
        }.onFailure { XgLog.w(TAG, "审计写入失败：${it.javaClass.simpleName}") }
    }

    private data class WmSizeReadback(val physical: String, val overrideSize: String?)

    private data class WmDensityReadback(val physical: Int, val overrideDensity: Int?)

    private data class RefreshReadback(val peak: Float?, val min: Float?, val user: Float?)

    private companion object {
        const val TAG = "DisplayController"
        const val KIND_DISPLAY_STATE = "display_state"
        const val TARGET_SIZE = "size"
        const val TARGET_DENSITY = "density"
        const val TARGET_REFRESH = "refresh"
        const val TARGET_TOUCH = "touch_sample"
        const val TARGET_DC = "dc"
        const val TARGET_COLOR_TEMPERATURE = "color_temperature"
        const val TARGET_NIGHT_MODE = "night_mode"
        const val SNAPSHOT_TTL_MS = 7L * 24 * 3600 * 1000
        const val MAX_AUDIT_COMMANDS = 4_000
        const val MAX_ASPECT_DRIFT = 0.02

        
        val ALLOWED_SIZE_SCALES = listOf(1.0, 0.85, 0.75)

        val DENSITY_RANGE = 320..640
        val COLOR_TEMPERATURE_RANGE = 4000..8000
        val NIGHT_MODES = setOf("yes", "no", "auto")
    }
}
