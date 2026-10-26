package com.xg.toolbox.core.device

import com.xg.toolbox.core.common.SingleFlightValueCache
import com.xg.toolbox.core.common.XgTab
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RiskItem(
    val id: String,
    val title: String,
    val note: String,
    val severity: String,
    val actionTab: XgTab? = null,
)

@Singleton
class RiskEvaluator @Inject constructor(
    private val deviceInfoRepository: DeviceInfoRepository,
    private val metricsSampler: MetricsSampler,
) {

    
    private val cache = SingleFlightValueCache<List<RiskItem>>()

    suspend fun evaluate(forceRefresh: Boolean = false): List<RiskItem> = withContext(Dispatchers.IO) {
        cache.get(forceRefresh) {
            val fields = try {
                deviceInfoRepository.collect(forceRefresh = forceRefresh)
                    .flatMap { group -> group.fields }
                    .associateBy { field -> field.id }
            } catch (t: Throwable) {
                null
            }
            if (fields.isNullOrEmpty()) {
                null
            } else {
                val risks = mutableListOf<RiskItem>()
                evaluateSecurity(fields, risks)
                evaluateStorage(risks)
                evaluateTemperature(risks)
                risks.sortBy { severityRank(it.severity) }
                if (risks.isEmpty()) {
                    listOf(
                        RiskItem(
                            id = "risk.none",
                            title = "未发现明显风险",
                            note = "安全状态、存储与温度均未触发已知风险项",
                            severity = SEVERITY_OK,
                            actionTab = XgTab.SECURITY,
                        ),
                    )
                } else {
                    risks
                }
            }
        } ?: listOf(
            RiskItem(
                id = "risk.collectFailed",
                title = "无法读取设备安全状态",
                note = "设备信息采集失败，本次风险提示不完整",
                severity = SEVERITY_WARN,
                actionTab = XgTab.SECURITY,
            ),
        )
    }

    private fun evaluateSecurity(fields: Map<String, DeviceField>, risks: MutableList<RiskItem>) {
        val bootloader = fields[DeviceFieldIds.SECURITY_BOOTLOADER]?.value
        if (bootloader?.contains("已解锁") == true) {
            risks += RiskItem(
                id = "risk.bootloader",
                title = "Bootloader 已解锁",
                note = "设备完整性无法通过官方校验，部分支付与金融应用可能拒绝服务",
                severity = SEVERITY_DANGER,
                actionTab = XgTab.SECURITY,
            )
        }
        val verity = fields[DeviceFieldIds.SECURITY_DM_VERITY]?.value
        if (verity != null && !verity.contains("已启用")) {
            risks += RiskItem(
                id = "risk.dmVerity",
                title = "dm-verity 未启用",
                note = "系统分区校验关闭，被修改的系统文件不会被发现",
                severity = SEVERITY_WARN,
                actionTab = XgTab.SECURITY,
            )
        }
        val selinux = fields[DeviceFieldIds.SECURITY_SELINUX]?.value
        if (selinux != null && !selinux.contains("Enforcing")) {
            risks += RiskItem(
                id = "risk.selinux",
                title = "SELinux 未处于强制模式",
                note = "当前为 $selinux，系统访问控制被放宽",
                severity = SEVERITY_DANGER,
                actionTab = XgTab.SECURITY,
            )
        }
        val adb = fields[DeviceFieldIds.SECURITY_ADB]?.value
        if (adb == "已开启") {
            risks += RiskItem(
                id = "risk.adb",
                title = "ADB 调试已开启",
                note = "连接电脑后可通过 adb 获取调试权限，用完建议关闭",
                severity = SEVERITY_WARN,
                actionTab = XgTab.SECURITY,
            )
        }
        val developerOptions = fields[DeviceFieldIds.SECURITY_DEVELOPER_OPTIONS]?.value
        if (developerOptions == "已开启") {
            risks += RiskItem(
                id = "risk.developerOptions",
                title = "开发者选项已开启",
                note = "开发者选项会开放调试相关能力，日常使用建议关闭",
                severity = SEVERITY_WARN,
                actionTab = XgTab.SECURITY,
            )
        }
        val integrity = fields[DeviceFieldIds.SECURITY_PLAY_INTEGRITY]?.value
        if (integrity != null && integrity.contains("未通过")) {
            risks += RiskItem(
                id = "risk.playIntegrity",
                title = "设备完整性校验可能不通过",
                note = "本地推断：Bootloader / dm-verity / 构建指纹不符合官方状态（未调用 Google API）",
                severity = SEVERITY_WARN,
                actionTab = XgTab.SECURITY,
            )
        }
    }

    
    private fun evaluateStorage(risks: MutableList<RiskItem>) {
        val stat = try {
            android.os.StatFs(android.os.Environment.getDataDirectory().absolutePath)
        } catch (t: Throwable) {
            null
        } ?: return
        val total = stat.totalBytes
        if (total <= 0L) return
        val usedPercent = (total - stat.availableBytes).toDouble() / total * 100.0
        when {
            usedPercent >= STORAGE_DANGER_PERCENT -> risks += RiskItem(
                id = "risk.storage",
                title = "存储空间即将耗尽",
                note = "已用 ${formatPercent(usedPercent.toFloat())}，请尽快清理",
                severity = SEVERITY_DANGER,
                actionTab = XgTab.SYSTEM,
            )
            usedPercent >= STORAGE_WARN_PERCENT -> risks += RiskItem(
                id = "risk.storage",
                title = "存储空间不足",
                note = "已用 ${formatPercent(usedPercent.toFloat())}，建议清理缓存与残留文件",
                severity = SEVERITY_WARN,
                actionTab = XgTab.SYSTEM,
            )
        }
    }

    
    private fun evaluateTemperature(risks: MutableList<RiskItem>) {
        val temperature = metricsSampler.history.value.lastOrNull()?.tempC ?: return
        val text = String.format(java.util.Locale.US, "%.1f", temperature)
        when {
            temperature >= TEMPERATURE_DANGER_C -> risks += RiskItem(
                id = "risk.temperature",
                title = "设备温度过高",
                note = "当前 $text °C，建议停止高负载任务并散热",
                severity = SEVERITY_DANGER,
                actionTab = XgTab.SYSTEM,
            )
            temperature >= TEMPERATURE_WARN_C -> risks += RiskItem(
                id = "risk.temperature",
                title = "设备温度偏高",
                note = "当前 $text °C，长时间高负载可能触发降频",
                severity = SEVERITY_WARN,
                actionTab = XgTab.SYSTEM,
            )
        }
    }

    private fun severityRank(severity: String): Int = when (severity) {
        SEVERITY_DANGER -> 0
        SEVERITY_WARN -> 1
        else -> 2
    }

    private companion object {
        const val SEVERITY_DANGER = "danger"
        const val SEVERITY_WARN = "warn"
        const val SEVERITY_OK = "ok"

        const val STORAGE_WARN_PERCENT = 90.0
        const val STORAGE_DANGER_PERCENT = 95.0
        const val TEMPERATURE_WARN_C = 48f
        const val TEMPERATURE_DANGER_C = 55f
    }
}
