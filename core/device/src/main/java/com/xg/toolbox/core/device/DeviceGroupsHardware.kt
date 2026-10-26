package com.xg.toolbox.core.device

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.telephony.TelephonyManager
import android.view.Display
import android.view.WindowManager
import com.xg.toolbox.core.common.Formatters
import java.util.Locale
import kotlin.math.roundToInt

internal fun buildHardwareGroup(context: Context, source: FieldSource): DeviceGroup = DeviceGroup(
    id = DeviceGroupIds.HARDWARE,
    title = "硬件",
    fields = listOf(
        makeField(
            id = DeviceFieldIds.HARDWARE_SOC,
            groupId = DeviceGroupIds.HARDWARE,
            name = "SoC 型号",
            propertyKey = "ro.soc.model",
            unavailableNote = "系统未提供 SoC 型号（Build.SOC_MODEL / ro.soc.model / ro.board.platform 均为空）",
        ) {
            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            } else {
                null
            }
            val manufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
            } else {
                null
            }
            val resolved = firstNonBlank(socModel, source.prop("ro.soc.model"), source.prop("ro.board.platform"))
            when {
                resolved == null -> FieldValue(value = null)
                manufacturer != null -> FieldValue("$resolved（$manufacturer）")
                else -> FieldValue(resolved)
            }
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_CPU_ABI,
            groupId = DeviceGroupIds.HARDWARE,
            name = "CPU 架构",
            propertyKey = "ro.product.cpu.abilist",
            unavailableNote = "系统未提供 CPU 架构列表",
        ) {
            val abis = Build.SUPPORTED_ABIS.joinToString(", ").takeIf { it.isNotBlank() }
            FieldValue(firstNonBlank(abis, source.prop("ro.product.cpu.abilist")))
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_CPU_CORES,
            groupId = DeviceGroupIds.HARDWARE,
            name = "CPU 核心",
            propertyKey = "/proc/cpuinfo",
            unavailableNote = "无法读取 CPU 核心数量",
        ) {
            val cores = Runtime.getRuntime().availableProcessors().takeIf { it > 0 }
                ?: source.shellFact("CPUINFO_CORES")?.toIntOrNull()?.takeIf { it > 0 }
            if (cores == null) {
                FieldValue(value = null)
            } else {
                val maxFreqKhz = source.rootFact("MAX_FREQ_KHZ")?.toLongOrNull()
                val maxFreqText = maxFreqKhz?.let { "最高 ${Formatters.mhz(it)}" }
                FieldValue(listOfNotNull("$cores 核", maxFreqText).joinToString(" · "))
            }
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_GPU,
            groupId = DeviceGroupIds.HARDWARE,
            name = "GPU",
            propertyKey = "ro.hardware.egl",
            unavailableNote = "未读取到 GPU 名称（SurfaceFlinger / kgsl / 驱动属性均不可用）",
        ) {
            val fromSurfaceFlinger = parseGlesName(source.rootFact("GPU_GLES"))
            val fromKgsl = formatGpuModel(source.rootFact("GPU_MODEL"))
            val fromProps = listOfNotNull(
                source.prop("ro.hardware.egl"),
                source.prop("ro.board.platform"),
            ).joinToString(" · ").takeIf { it.isNotEmpty() }
            when {
                fromSurfaceFlinger != null -> FieldValue(
                    value = fromSurfaceFlinger,
                    restriction = FieldRestriction.NONE,
                )
                fromKgsl != null -> FieldValue(
                    value = fromKgsl,
                    restriction = FieldRestriction.NONE,
                )
                fromProps != null -> FieldValue(
                    value = fromProps,
                    restriction = FieldRestriction.LIMITED,
                    note = "从 GPU 驱动属性推断，未读取到完整渲染器名称",
                )
                else -> FieldValue(value = null)
            }
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_MEMORY,
            groupId = DeviceGroupIds.HARDWARE,
            name = "内存",
            propertyKey = "/proc/meminfo",
            unavailableNote = "无法读取物理内存容量",
        ) {
            val totalBytes = readActivityManagerTotalMem(context) ?: readMemTotalBytes()
            FieldValue(totalBytes?.takeIf { it > 0 }?.let { Formatters.bytes(it) })
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_STORAGE,
            groupId = DeviceGroupIds.HARDWARE,
            name = "存储",
            propertyKey = "/data",
            unavailableNote = "无法读取 /data 分区容量",
        ) {
            val totalBytes = readDataTotalBytes()
            FieldValue(totalBytes?.takeIf { it > 0 }?.let { Formatters.bytes(it) })
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_RESOLUTION,
            groupId = DeviceGroupIds.HARDWARE,
            name = "屏幕分辨率",
            propertyKey = "wm size",
            unavailableNote = "系统未提供屏幕分辨率",
        ) {
            val fromApi = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
                    bounds?.takeIf { it.width() > 0 && it.height() > 0 }
                        ?.let { "${it.width()} × ${it.height()}" }
                } else {
                    null
                }
            } catch (t: Throwable) {
                null
            }
            FieldValue(
                firstNonBlank(
                    fromApi,
                    parseWmSize(source.rootFact("WM_SIZE")),
                    parseWmSize(source.shellFact("WM_SIZE")),
                ),
            )
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_DENSITY,
            groupId = DeviceGroupIds.HARDWARE,
            name = "屏幕密度",
            propertyKey = "wm density",
            unavailableNote = "系统未提供屏幕密度",
        ) {
            val dpi = context.resources.displayMetrics.densityDpi.takeIf { it > 0 }
            FieldValue(
                firstNonBlank(
                    dpi?.let { "$it dpi" },
                    parseWmDensity(source.rootFact("WM_DENSITY")),
                    parseWmDensity(source.shellFact("WM_DENSITY")),
                ),
            )
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_REFRESH_RATE,
            groupId = DeviceGroupIds.HARDWARE,
            name = "刷新率",
            propertyKey = "dumpsys display",
            unavailableNote = "系统未提供刷新率",
        ) {
            val currentHz = try {
                context.getSystemService(DisplayManager::class.java)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
                    ?.refreshRate
                    ?.takeIf { it > 0f }
            } catch (t: Throwable) {
                null
            }
            val maxHz = parseMaxRefreshHz(source.rootFact("REFRESH_LIST"))
            val value = when {
                currentHz != null -> String.format(Locale.US, "%.1f Hz", currentHz)
                maxHz != null -> "${maxHz.roundToInt()} Hz"
                else -> null
            }
            FieldValue(value)
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_BATTERY_CAPACITY,
            groupId = DeviceGroupIds.HARDWARE,
            name = "电池容量",
            propertyKey = "sysfs.charge_full_design",
            unavailableNote = "厂商未暴露电池设计容量节点（charge_full_design / charge_full）",
        ) {
            val microAmpHours = firstNonBlank(
                source.rootFact("CHARGE_FULL_DESIGN"),
                source.rootFact("CHARGE_FULL"),
            )?.toLongOrNull()
            when {
                microAmpHours != null && microAmpHours > 0 -> FieldValue(formatMilliampHours(microAmpHours))
                !source.rootGranted -> FieldValue(
                    value = null,
                    restriction = FieldRestriction.NEEDS_ROOT,
                    note = "电池设计容量位于 sysfs 节点，需要 Root 读取",
                )
                else -> FieldValue(value = null)
            }
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_BATTERY_HEALTH,
            groupId = DeviceGroupIds.HARDWARE,
            name = "电池健康",
            propertyKey = "sysfs.charge_full",
            unavailableNote = "系统未提供电池健康信息",
        ) {
            val health = mapBatteryHealthCode(readBatteryHealthCode(context))
                ?: mapBatteryHealthText(source.rootFact("BATTERY_HEALTH"))
            val cycles = source.rootFact("CYCLE_COUNT")?.toIntOrNull()?.takeIf { it >= 0 }
            val parts = listOfNotNull(health, cycles?.let { "循环 $it 次" })
            when {
                parts.isNotEmpty() -> FieldValue(parts.joinToString(" · "))
                !source.rootGranted -> FieldValue(
                    value = null,
                    restriction = FieldRestriction.NEEDS_ROOT,
                    note = "电池健康与循环次数位于 sysfs 节点，需要 Root 读取",
                )
                else -> FieldValue(value = null)
            }
        },
        makeField(
            id = DeviceFieldIds.HARDWARE_SENSOR_COUNT,
            groupId = DeviceGroupIds.HARDWARE,
            name = "传感器数量",
            propertyKey = "SensorManager.getSensorList(TYPE_ALL)",
            unavailableNote = "系统未提供传感器列表",
        ) {
            val count = try {
                context.getSystemService(SensorManager::class.java)
                    ?.getSensorList(Sensor.TYPE_ALL)
                    ?.size
            } catch (t: Throwable) {
                null
            }
            FieldValue(count?.takeIf { it > 0 }?.let { "$it 个" })
        },
    ),
)

internal fun buildNetworkGroup(context: Context, source: FieldSource): DeviceGroup = DeviceGroup(
    id = DeviceGroupIds.NETWORK,
    title = "网络",
    fields = listOf(
        makeField(
            id = DeviceFieldIds.NETWORK_WIFI_SSID,
            groupId = DeviceGroupIds.NETWORK,
            name = "Wi-Fi 名称",
            propertyKey = "wifi.ssid",
            unavailableNote = "系统未提供 Wi-Fi 名称",
        ) {
            val fromApi = readWifiSsid(context)
            val fromRoot = parseWifiSsidLine(source.rootFact("WIFI_SSID"))
            when {
                fromApi != null -> FieldValue(fromApi)
                fromRoot != null -> FieldValue(
                    value = fromRoot,
                    restriction = FieldRestriction.LIMITED,
                    note = "Android 12+ 读取 Wi-Fi 名称需要位置权限或 Root；此处经 Root 读取 dumpsys wifi",
                )
                !source.rootGranted -> FieldValue(
                    value = null,
                    restriction = FieldRestriction.LIMITED,
                    note = "Android 12+ 读取 Wi-Fi 名称需要位置权限或 Root，当前两者都不可用",
                )
                else -> FieldValue(value = null)
            }
        },
        makeField(
            id = DeviceFieldIds.NETWORK_IPV4,
            groupId = DeviceGroupIds.NETWORK,
            name = "IPv4 地址",
            propertyKey = "wifi.ipv4",
            unavailableNote = "当前网络未分配 IPv4 地址",
        ) {
            val (apiIpv4, _) = readIpAddresses(context)
            val shellIpv4 = source.shellFact("IPV4")?.substringAfter("inet ")?.trim()
                ?: source.rootFact("IPV4")?.substringAfter("inet ")?.trim()
            FieldValue(firstNonBlank(apiIpv4, shellIpv4))
        },
        makeField(
            id = DeviceFieldIds.NETWORK_IPV6,
            groupId = DeviceGroupIds.NETWORK,
            name = "IPv6 地址",
            propertyKey = "wifi.ipv6",
            unavailableNote = "当前网络未分配全局 IPv6 地址",
        ) {
            val (_, apiIpv6) = readIpAddresses(context)
            val shellIpv6 = source.shellFact("IPV6")?.substringAfter("inet6 ")?.trim()
                ?: source.rootFact("IPV6")?.substringAfter("inet6 ")?.trim()
            FieldValue(firstNonBlank(apiIpv6, shellIpv6))
        },
        makeField(
            id = DeviceFieldIds.NETWORK_WIFI_MAC,
            groupId = DeviceGroupIds.NETWORK,
            name = "Wi-Fi MAC",
            propertyKey = "wifi.mac",
            unavailableNote = "Android 10+ 需位置权限或 Root 才能读取接口 MAC",
        ) {
            val fromApi = readWifiMac(context)
            val fromSysfs = source.rootFact("WLAN_MAC")?.takeIf { isUsableMac(it) }
            val value = firstNonBlank(fromApi, fromSysfs)
            when {
                value != null -> FieldValue(
                    value = value,
                    restriction = FieldRestriction.LIMITED,
                    note = "Android 10+ 默认随机化 Wi-Fi MAC；此处为当前 wlan0 接口地址",
                )
                !source.rootGranted -> FieldValue(
                    value = null,
                    restriction = FieldRestriction.NEEDS_ROOT,
                    note = "读取接口 MAC 需要 Root（/sys/class/net/wlan0/address）",
                )
                else -> FieldValue(value = null)
            }
        },
        makeField(
            id = DeviceFieldIds.NETWORK_BT_MAC,
            groupId = DeviceGroupIds.NETWORK,
            name = "蓝牙 MAC",
            propertyKey = "bluetooth.mac",
            unavailableNote = "系统仅返回占位地址（02:00:00:00:00:00）或不可读",
        ) {
            val fromApi = readBluetoothMac(context)
            val fromSettings = readSecureSetting(context, "bluetooth_address")?.takeIf { isUsableMac(it) }
            val fromRoot = source.rootFact("BT_MAC")?.takeIf { isUsableMac(it) }
            FieldValue(firstNonBlank(fromApi, fromSettings, fromRoot))
        },
        makeField(
            id = DeviceFieldIds.NETWORK_MOBILE_TYPE,
            groupId = DeviceGroupIds.NETWORK,
            name = "移动网络",
            propertyKey = "telephony.network",
            unavailableNote = "系统未提供移动网络类型（可能需要 READ_PHONE_STATE 或 Root）",
        ) {
            val simState = readSimStateCode(context)
            val apiType = readDataNetworkType(context)
            val propType = parseNetworkTypeProp(source.prop("gsm.network.type"))
            val value = when {
                apiType != null -> mapDataNetworkType(apiType)
                propType != null -> propType
                simState == TelephonyManager.SIM_STATE_ABSENT -> "未插卡"
                else -> null
            }
            when {
                value != null -> FieldValue(value)
                !source.rootGranted -> FieldValue(
                    value = null,
                    restriction = FieldRestriction.LIMITED,
                    note = "读取移动网络类型需要 READ_PHONE_STATE 运行时权限或 Root",
                )
                else -> FieldValue(value = null)
            }
        },
        makeField(
            id = DeviceFieldIds.NETWORK_SIM_STATE,
            groupId = DeviceGroupIds.NETWORK,
            name = "SIM 状态",
            propertyKey = "telephony.sim",
            unavailableNote = "系统未提供 SIM 状态（可能需要 READ_PHONE_STATE 或 Root）",
        ) {
            val simState = readSimStateCode(context) ?: parseSimStateProp(source.prop("gsm.sim.state"))
            if (simState == null) {
                FieldValue(value = null)
            } else {
                val operatorName = readSimOperatorName(context)
                val base = mapSimState(simState)
                FieldValue(if (operatorName != null) "$base · $operatorName" else base)
            }
        },
        makeField(
            id = DeviceFieldIds.NETWORK_BASEBAND,
            groupId = DeviceGroupIds.NETWORK,
            name = "基带版本",
            propertyKey = "gsm.version.baseband",
            unavailableNote = "系统未提供基带版本",
        ) {
            FieldValue(firstNonBlank(Build.getRadioVersion(), source.prop("gsm.version.baseband")))
        },
        makeField(
            id = DeviceFieldIds.NETWORK_IMEI,
            groupId = DeviceGroupIds.NETWORK,
            name = "IMEI",
            propertyKey = "telephony.imei",
            unavailableNote = "受系统限制：Android 10+ 普通应用不可读取 IMEI",
        ) {
            val apiImei = readImei(context)
            val rootImei = source.rootFact("IMEI")?.takeIf(::validImei)
            val raw = firstNonBlank(rootImei, apiImei)
            when {
                raw != null -> FieldValue(
                    value = raw,
                    restriction = FieldRestriction.NONE,
                    note = if (rootImei != null) "Root 读取原始值" else "系统 API 读取原始值",
                )
                !source.rootGranted -> FieldValue(
                    value = null,
                    restriction = FieldRestriction.NEEDS_ROOT,
                    note = "受系统限制（Android 10+）：普通应用不可读 IMEI，Root 属性 persist.radio.imei 也未读取",
                )
                else -> FieldValue(
                    value = null,
                    restriction = FieldRestriction.UNAVAILABLE,
                    note = "受系统限制（Android 10+）：Root 节点 persist.radio.imei 也无有效值",
                )
            }
        },
    ),
)

internal fun buildSecurityGroup(context: Context, source: FieldSource): DeviceGroup = DeviceGroup(
    id = DeviceGroupIds.SECURITY,
    title = "权限与安全",
    fields = listOf(
        makeField(
            id = DeviceFieldIds.SECURITY_SELINUX,
            groupId = DeviceGroupIds.SECURITY,
            name = "SELinux",
            propertyKey = "getenforce",
            unavailableNote = "未读取到 SELinux 状态",
        ) {
            val fromRoot = mapSelinux(firstNonBlank(source.rootFact("SELINUX"), source.rootFact("SELINUX_ENFORCE")))
            val fromSysfs = mapSelinux(readTextFile("/sys/fs/selinux/enforce"))
            val value = fromRoot ?: fromSysfs
            when {
                value != null -> FieldValue(value)
                !source.rootGranted -> FieldValue(
                    value = null,
                    restriction = FieldRestriction.NEEDS_ROOT,
                    note = "读取 SELinux 状态需要 Root（getenforce）",
                )
                else -> FieldValue(value = null)
            }
        },
        makeField(
            id = DeviceFieldIds.SECURITY_BOOTLOADER,
            groupId = DeviceGroupIds.SECURITY,
            name = "Bootloader",
            propertyKey = "ro.boot.verifiedbootstate",
            unavailableNote = "厂商未暴露 Bootloader 状态属性（verifiedbootstate / flash.locked）",
        ) {
            val bootState = source.prop("ro.boot.verifiedbootstate")?.lowercase(Locale.US)
            val flashLocked = source.prop("ro.boot.flash.locked")
            val unlocked = bootState == "orange" || bootState == "red" || flashLocked == "0"
            val value = when {
                bootState != null -> (if (unlocked) "已解锁" else "已锁定") + " · $bootState"
                flashLocked == "1" -> "已锁定 · flash.locked=1"
                flashLocked == "0" -> "已解锁 · flash.locked=0"
                else -> null
            }
            FieldValue(value)
        },
        makeField(
            id = DeviceFieldIds.SECURITY_DM_VERITY,
            groupId = DeviceGroupIds.SECURITY,
            name = "dm-verity",
            propertyKey = "ro.boot.veritymode",
            unavailableNote = "厂商未暴露 dm-verity 状态属性（ro.boot.veritymode）",
        ) {
            val raw = firstNonBlank(
                source.prop("ro.boot.veritymode"),
                source.prop("ro.boot.veritymode.managed"),
            )
            val value = when (raw?.lowercase(Locale.US)) {
                "enforcing" -> "enforcing（已启用）"
                "permissive" -> "permissive（宽容）"
                "disabled" -> "disabled（已关闭）"
                else -> raw
            }
            FieldValue(value)
        },
        makeField(
            id = DeviceFieldIds.SECURITY_AVB,
            groupId = DeviceGroupIds.SECURITY,
            name = "AVB 状态",
            propertyKey = "ro.boot.vbmeta.device_state",
            unavailableNote = "该设备未提供 vbmeta.device_state（老设备常见）",
        ) {
            val raw = source.prop("ro.boot.vbmeta.device_state")?.lowercase(Locale.US)
            val value = when (raw) {
                "locked" -> "locked（已锁定）"
                "unlocked" -> "unlocked（已解锁）"
                else -> raw
            }
            FieldValue(value)
        },
        makeField(
            id = DeviceFieldIds.SECURITY_PLAY_INTEGRITY,
            groupId = DeviceGroupIds.SECURITY,
            name = "Play Integrity",
            propertyKey = "local.inference",
            unavailableNote = "本地状态不足以推断设备完整性",
        ) {
            FieldValue(
                value = inferPlayIntegrity(source),
                restriction = FieldRestriction.LIMITED,
                note = "基于 Bootloader / dm-verity / 构建指纹的本地推断，未调用 Google Play Integrity API",
            )
        },
        makeField(
            id = DeviceFieldIds.SECURITY_ADB,
            groupId = DeviceGroupIds.SECURITY,
            name = "ADB 调试",
            propertyKey = "settings.global.adb_enabled",
            unavailableNote = "系统未提供 adb_enabled 开关状态",
        ) {
            val raw = firstNonBlank(readGlobalSetting(context, "adb_enabled"), source.shellFact("ADB_ENABLED"))
            FieldValue(mapToggleState(raw))
        },
        makeField(
            id = DeviceFieldIds.SECURITY_DEVELOPER_OPTIONS,
            groupId = DeviceGroupIds.SECURITY,
            name = "开发者选项",
            propertyKey = "settings.global.development_settings",
            unavailableNote = "系统未提供开发者选项开关状态",
        ) {
            val raw = firstNonBlank(
                readGlobalSetting(context, "development_settings_enabled"),
                source.shellFact("DEVELOPMENT_SETTINGS"),
            )
            FieldValue(mapToggleState(raw))
        },
        makeField(
            id = DeviceFieldIds.SECURITY_WEBVIEW,
            groupId = DeviceGroupIds.SECURITY,
            name = "系统 WebView",
            propertyKey = "webview.version",
            unavailableNote = "系统未提供 WebView 包信息",
        ) {
            FieldValue(firstNonBlank(readWebViewPackage(), parseWebViewVersion(source.rootFact("WEBVIEW"))))
        },
    ),
)
