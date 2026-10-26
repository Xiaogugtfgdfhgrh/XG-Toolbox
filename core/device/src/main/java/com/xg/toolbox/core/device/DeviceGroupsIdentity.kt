package com.xg.toolbox.core.device

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.xg.toolbox.core.common.Formatters
import java.util.Locale

internal fun buildIdentityGroup(context: Context, source: FieldSource): DeviceGroup = DeviceGroup(
    id = DeviceGroupIds.IDENTITY,
    title = "身份",
    fields = listOf(
        makeField(
            id = DeviceFieldIds.IDENTITY_NAME,
            groupId = DeviceGroupIds.IDENTITY,
            name = "设备名称",
            propertyKey = "settings.global.device_name",
            unavailableNote = "系统未设置设备名称",
        ) {
            FieldValue(
                firstNonBlank(
                    readGlobalSetting(context, "device_name"),
                    readSecureSetting(context, "bluetooth_name"),
                ),
            )
        },
        makeField(
            id = DeviceFieldIds.IDENTITY_MODEL,
            groupId = DeviceGroupIds.IDENTITY,
            name = "设备型号",
            propertyKey = "ro.product.model",
            unavailableNote = "系统未提供设备型号",
        ) {
            FieldValue(firstNonBlank(Build.MODEL, source.prop("ro.product.model")))
        },
        makeField(
            id = DeviceFieldIds.IDENTITY_MARKET_NAME,
            groupId = DeviceGroupIds.IDENTITY,
            name = "市场名称",
            propertyKey = "ro.product.marketname",
            unavailableNote = "厂商未提供市场名称",
        ) {
            val marketName = source.prop("ro.product.marketname")
            if (marketName != null) {
                FieldValue(marketName)
            } else {
                val model = firstNonBlank(Build.MODEL, source.prop("ro.product.model"))
                if (model == null) {
                    FieldValue(value = null)
                } else {
                    FieldValue(
                        value = model,
                        restriction = FieldRestriction.LIMITED,
                        note = "厂商未提供市场名称，回退显示设备型号",
                    )
                }
            }
        },
        makeField(
            id = DeviceFieldIds.IDENTITY_BRAND,
            groupId = DeviceGroupIds.IDENTITY,
            name = "品牌",
            propertyKey = "ro.product.brand",
            unavailableNote = "系统未提供品牌",
        ) {
            FieldValue(firstNonBlank(Build.BRAND, source.prop("ro.product.brand")))
        },
        makeField(
            id = DeviceFieldIds.IDENTITY_MANUFACTURER,
            groupId = DeviceGroupIds.IDENTITY,
            name = "制造商",
            propertyKey = "ro.product.manufacturer",
            unavailableNote = "系统未提供制造商",
        ) {
            FieldValue(firstNonBlank(Build.MANUFACTURER, source.prop("ro.product.manufacturer")))
        },
        makeField(
            id = DeviceFieldIds.IDENTITY_DEVICE,
            groupId = DeviceGroupIds.IDENTITY,
            name = "产品代号",
            propertyKey = "ro.product.device",
            unavailableNote = "系统未提供产品代号",
        ) {
            FieldValue(firstNonBlank(Build.DEVICE, source.prop("ro.product.device")))
        },
        makeField(
            id = DeviceFieldIds.IDENTITY_SERIAL,
            groupId = DeviceGroupIds.IDENTITY,
            name = "设备序列号",
            propertyKey = "ro.serialno",
            unavailableNote = "Android 10+ 非特权应用无法读取序列号",
        ) {
            val apiSerial = try {
                Build.getSerial()?.takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
            } catch (t: Throwable) {
                null
            }
            val rootSerial = source.rootFact("SERIAL")
                ?.takeIf { !it.equals("unknown", ignoreCase = true) }
            val raw = firstNonBlank(rootSerial, apiSerial)
            when {
                raw == null && !source.rootGranted -> FieldValue(
                    value = null,
                    restriction = FieldRestriction.NEEDS_ROOT,
                    note = "读取序列号需要 Root：Android 10+ 非特权应用不可读",
                )
                raw == null -> FieldValue(
                    value = null,
                    restriction = FieldRestriction.UNAVAILABLE,
                    note = "未从系统属性 ro.serialno 读到序列号",
                )
                else -> FieldValue(
                    value = raw,
                    restriction = FieldRestriction.NONE,
                    note = if (rootSerial != null) {
                        "Root 读取原始值；可在设备页修改"
                    } else {
                        "系统 API 读取原始值；可在设备页修改"
                    },
                )
            }
        },
        makeField(
            id = DeviceFieldIds.IDENTITY_LOCALE,
            groupId = DeviceGroupIds.IDENTITY,
            name = "系统语言",
            propertyKey = "persist.sys.locale",
            unavailableNote = "系统未提供语言设置",
        ) {
            FieldValue(firstNonBlank(Locale.getDefault().toLanguageTag(), source.prop("persist.sys.locale")))
        },
    ),
)

internal fun buildSystemGroup(context: Context, source: FieldSource): DeviceGroup = DeviceGroup(
    id = DeviceGroupIds.SYSTEM,
    title = "系统",
    fields = listOf(
        makeField(
            id = DeviceFieldIds.SYSTEM_VERSION,
            groupId = DeviceGroupIds.SYSTEM,
            name = "系统版本",
            propertyKey = "ro.build.version.incremental",
            unavailableNote = "系统未提供版本号",
        ) {
            FieldValue(firstNonBlank(Build.DISPLAY, source.prop("ro.build.version.incremental")))
        },
        makeField(
            id = DeviceFieldIds.SYSTEM_ANDROID_VERSION,
            groupId = DeviceGroupIds.SYSTEM,
            name = "Android 版本",
            propertyKey = "ro.build.version.release",
            unavailableNote = "系统未提供 Android 版本",
        ) {
            FieldValue(firstNonBlank(Build.VERSION.RELEASE, source.prop("ro.build.version.release")))
        },
        makeField(
            id = DeviceFieldIds.SYSTEM_API_LEVEL,
            groupId = DeviceGroupIds.SYSTEM,
            name = "API 级别",
            propertyKey = "ro.build.version.sdk",
            unavailableNote = "系统未提供 API 级别",
        ) {
            FieldValue(Build.VERSION.SDK_INT.takeIf { it > 0 }?.toString())
        },
        makeField(
            id = DeviceFieldIds.SYSTEM_SECURITY_PATCH,
            groupId = DeviceGroupIds.SYSTEM,
            name = "安全补丁",
            propertyKey = "ro.build.version.security_patch",
            unavailableNote = "部分 ROM 未提供安全补丁日期",
        ) {
            FieldValue(firstNonBlank(Build.VERSION.SECURITY_PATCH, source.prop("ro.build.version.security_patch")))
        },
        makeField(
            id = DeviceFieldIds.SYSTEM_BUILD_TYPE,
            groupId = DeviceGroupIds.SYSTEM,
            name = "构建类型",
            propertyKey = "ro.build.type",
            unavailableNote = "系统未提供构建类型",
        ) {
            FieldValue(firstNonBlank(Build.TYPE, source.prop("ro.build.type")))
        },
        makeField(
            id = DeviceFieldIds.SYSTEM_BUILD_TAGS,
            groupId = DeviceGroupIds.SYSTEM,
            name = "构建标签",
            propertyKey = "ro.build.tags",
            unavailableNote = "系统未提供构建标签",
        ) {
            FieldValue(firstNonBlank(Build.TAGS, source.prop("ro.build.tags")))
        },
        makeField(
            id = DeviceFieldIds.SYSTEM_FINGERPRINT,
            groupId = DeviceGroupIds.SYSTEM,
            name = "系统指纹",
            propertyKey = "ro.build.fingerprint",
            unavailableNote = "系统未提供系统指纹",
        ) {
            FieldValue(firstNonBlank(Build.FINGERPRINT, source.prop("ro.build.fingerprint")))
        },
        makeField(
            id = DeviceFieldIds.SYSTEM_KERNEL,
            groupId = DeviceGroupIds.SYSTEM,
            name = "内核版本",
            propertyKey = "uname -r",
            unavailableNote = "系统未提供内核版本",
        ) {
            FieldValue(
                firstNonBlank(
                    System.getProperty("os.version"),
                    source.shellFact("UNAME"),
                    source.rootFact("UNAME"),
                ),
            )
        },
        makeField(
            id = DeviceFieldIds.SYSTEM_ARCH,
            groupId = DeviceGroupIds.SYSTEM,
            name = "内核架构",
            propertyKey = "ro.product.cpu.abi",
            unavailableNote = "系统未提供 CPU 架构",
        ) {
            FieldValue(firstNonBlank(Build.SUPPORTED_ABIS.firstOrNull(), source.prop("ro.product.cpu.abi")))
        },
        makeField(
            id = DeviceFieldIds.SYSTEM_UPTIME,
            groupId = DeviceGroupIds.SYSTEM,
            name = "运行时长",
            propertyKey = "/proc/uptime",
            unavailableNote = "无法读取系统运行时长",
        ) {
            val elapsedMs = SystemClock.elapsedRealtime().takeIf { it > 0 }
            val fallbackSeconds = source.rootFact("UPTIME")
                ?.substringBefore(' ')
                ?.toDoubleOrNull()
                ?.times(1000)
                ?.toLong()
            val totalMs = elapsedMs ?: fallbackSeconds
            FieldValue(totalMs?.let { Formatters.uptime(it) })
        },
    ),
)

internal fun buildIdentifierGroup(context: Context, source: FieldSource): DeviceGroup = DeviceGroup(
    id = DeviceGroupIds.IDENTIFIERS,
    title = "标识",
    fields = listOf(
        makeField(
            id = DeviceFieldIds.IDENTIFIERS_ANDROID_ID,
            groupId = DeviceGroupIds.IDENTIFIERS,
            name = "Android ID",
            propertyKey = "settings.secure.android_id",
            unavailableNote = "系统未提供 Android ID",
        ) {
            FieldValue(
                firstNonBlank(
                    readSecureSetting(context, "android_id"),
                    source.shellFact("SETTINGS_ANDROID_ID"),
                ),
            )
        },
        makeField(
            id = DeviceFieldIds.IDENTIFIERS_OAID,
            groupId = DeviceGroupIds.IDENTIFIERS,
            name = "OAID",
            propertyKey = "oaid.client",
            unavailableNote = "未接入 MSA OAID SDK，无法读取系统广告标识",
        ) {
            FieldValue(
                value = null,
                restriction = FieldRestriction.UNAVAILABLE,
                note = "未接入 MSA OAID SDK（com.bun.miitmdid），按要求不以演示值代替",
            )
        },
        makeField(
            id = DeviceFieldIds.IDENTIFIERS_INSTALL_ID,
            groupId = DeviceGroupIds.IDENTIFIERS,
            name = "安装 ID",
            propertyKey = "firebase.installations",
            unavailableNote = "未接入 Firebase Installations SDK，安装 ID 未生成",
        ) {
            FieldValue(
                value = null,
                restriction = FieldRestriction.UNAVAILABLE,
                note = "未接入 Firebase Installations SDK，本应用也没有自建安装 ID",
            )
        },
        makeField(
            id = DeviceFieldIds.IDENTIFIERS_ADVERTISING_ID,
            groupId = DeviceGroupIds.IDENTIFIERS,
            name = "广告 ID",
            propertyKey = "gaid.client",
            unavailableNote = "未接入 Google Play 广告 ID 客户端",
        ) {
            FieldValue(
                value = null,
                restriction = FieldRestriction.UNAVAILABLE,
                note = "未接入 Google Play 广告 ID 客户端；即使接入，用户也可随时重置该 ID",
            )
        },
        makeField(
            id = DeviceFieldIds.IDENTIFIERS_APP_SET_ID,
            groupId = DeviceGroupIds.IDENTIFIERS,
            name = "App Set ID",
            propertyKey = "appset.client",
            unavailableNote = "未接入 Google Play App Set ID",
        ) {
            FieldValue(
                value = null,
                restriction = FieldRestriction.UNAVAILABLE,
                note = "需 Google Play 服务 App Set ID API，本应用未接入",
            )
        },
        makeField(
            id = DeviceFieldIds.IDENTIFIERS_GSF_ID,
            groupId = DeviceGroupIds.IDENTIFIERS,
            name = "GSF ID",
            propertyKey = "gsf.android_id",
            unavailableNote = "本机未提供 Google 服务框架 ID",
        ) {
            val fromApi = readGsfAndroidId(context)
            val fromRoot = extractHex16(source.rootFact("GSF"))
            val value = firstNonBlank(fromApi, fromRoot)
            FieldValue(
                value = value,
                restriction = if (value == null) FieldRestriction.UNAVAILABLE else FieldRestriction.NONE,
                note = if (value == null) "仅 Google 服务设备可读：GServices 提供者不可用或未登录" else null,
            )
        },
        makeField(
            id = DeviceFieldIds.IDENTIFIERS_DRM_DEVICE_ID,
            groupId = DeviceGroupIds.IDENTIFIERS,
            name = "DRM 设备 ID",
            propertyKey = "widevine.device_id",
            unavailableNote = "MediaDrm 未提供 Widevine 设备 ID",
        ) {
            val hash = readWidevineDeviceIdHash()
            FieldValue(
                value = hash?.let { "Widevine · $it" },
                restriction = if (hash == null) FieldRestriction.UNAVAILABLE else FieldRestriction.NONE,
                note = if (hash == null) {
                    "MediaDrm 未能读取 deviceUniqueId：可能未安装 Widevine 或该属性不受支持"
                } else {
                    null
                },
            )
        },
    ),
)

private fun readGsfAndroidId(context: Context): String? {
    return try {
        val cursor = context.contentResolver.query(
            Uri.parse("content://com.google.android.gsf.gservices"),
            null,
            null,
            arrayOf("android_id"),
            null,
        )
        cursor?.use {
            if (!it.moveToFirst() || it.columnCount < 2) {
                null
            } else {
                it.getString(1)?.takeIf { value -> Regex("^[0-9a-fA-F]{16}$").matches(value) }
            }
        }
    } catch (t: Throwable) {
        null
    }
}

private fun extractHex16(raw: String?): String? {
    val text = raw?.trim() ?: return null
    return Regex("[0-9a-fA-F]{16}").find(text)?.value
}
