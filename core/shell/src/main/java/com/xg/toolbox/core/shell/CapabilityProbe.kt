package com.xg.toolbox.core.shell

import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CapabilityReport(
    val capabilities: Capabilities,
    val evidence: List<String>,
)

@Singleton
class CapabilityProbe @Inject constructor(private val shell: Shell) {

    
    suspend fun probe(): CapabilityReport = withContext(Dispatchers.IO) {
        val evidence = mutableListOf<String>()
        val facts = mutableMapOf<String, String>()

        val systemProbe = shell.exec(SYSTEM_PROBE_SCRIPT, root = true, timeoutMs = PROBE_TIMEOUT_MS)
        if (systemProbe.ok) {
            facts.putAll(parseFacts(systemProbe.stdout))
            evidence += "能力探测 → ${summarizeFacts(facts)}"
        } else {
            evidence += "能力探测命令失败（code=${systemProbe.code}）：${firstLine(systemProbe.stdout.ifBlank { systemProbe.stderr })}"
        }

        val refreshRates = parseRefreshRates(shell.exec(DISPLAY_PROBE_COMMAND, root = true, timeoutMs = PROBE_TIMEOUT_MS))
        val maxRefreshHz = refreshRates.maxOrNull()?.roundToInt() ?: 0
        evidence += if (refreshRates.isEmpty()) {
            "dumpsys display 未解析到 fps 档位，最大刷新率记为 0（未知）"
        } else {
            "dumpsys display → ${refreshRates.joinToString("/")} Hz，最大 $maxRefreshHz Hz"
        }

        val androidSdk = if (Build.VERSION.SDK_INT > 0) Build.VERSION.SDK_INT else 0
        evidence += "Android SDK → $androidSdk（Build.VERSION.SDK_INT）"

        val hasGpuNodes = facts["kgsl"] == "1" || (facts["devfreq"]?.toIntOrNull() ?: 0) > 0
        val hasFastbootd = facts["fastbootd"].orEmpty().let { it == "1" || it.equals("true", ignoreCase = true) }
        val hasBootctl = facts["bootctl"].orEmpty().isNotBlank()
        val hasInitBoot = (facts["initboot"]?.toIntOrNull() ?: 0) > 0
        val hasTouchRateNode = facts["touchrate"].orEmpty().isNotBlank()
        val hasEdlReboot = supportsEdlReboot(facts)

        if (!hasTouchRateNode) {
            evidence += "未发现触控采样率节点（厂商私有），显示页将隐藏该档"
        }
        if (hasEdlReboot) {
            evidence += "EDL 可用性按高通平台属性启发式判断（未执行 reboot edl，属于能力提示而非验证）"
        }

        val capabilities = Capabilities(
            canWriteBlockDevice = facts["blockwrite"] == "1",
            isAbDevice = facts["slot"].orEmpty().isNotBlank(),
            hasBootctl = hasBootctl,
            hasFastbootd = hasFastbootd,
            hasInitBoot = hasInitBoot,
            hasZram = facts["zram"] == "1",
            hasKernelNodes = facts["cpufreq"] == "1",
            hasGpuNodes = hasGpuNodes,
            hasTouchRateNode = hasTouchRateNode,
            hasEdlReboot = hasEdlReboot,
            maxRefreshHz = maxRefreshHz,
            androidSdk = androidSdk,
        )
        CapabilityReport(capabilities, evidence)
    }

    private fun parseFacts(output: String): Map<String, String> {
        val facts = mutableMapOf<String, String>()
        output.lineSequence().forEach { line ->
            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            facts[line.substring(0, index).trim()] = line.substring(index + 1).trim()
        }
        return facts
    }

    private fun parseRefreshRates(result: ShellResult): List<Float> {
        if (!result.ok) return emptyList()
        return FPS_REGEX.findAll(result.stdout)
            .mapNotNull { it.groupValues.getOrNull(1)?.toFloatOrNull() }
            .filter { it > 0f }
            .distinct()
            .sorted()
            .toList()
    }

    
    private fun supportsEdlReboot(facts: Map<String, String>): Boolean {
        val text = listOf("hardware", "boothardware", "platform")
            .joinToString(" ") { facts[it].orEmpty() }
            .lowercase()
        if (text.contains("qcom")) return true
        return QUALCOMM_CODENAMES.any { text.contains(it) }
    }

    private fun summarizeFacts(facts: Map<String, String>): String = FACT_KEYS.joinToString(", ") { key ->
        "$key=${facts[key].orEmpty().ifBlank { "-" }}"
    }

    private fun firstLine(text: String): String =
        text.trim().lineSequence().firstOrNull().orEmpty().take(120)

    private companion object {
        const val PROBE_TIMEOUT_MS = 10_000L

        val FPS_REGEX = Regex("fps=([0-9]+(?:\\.[0-9]+)?)")

        val FACT_KEYS = listOf(
            "slot", "bootctl", "fastbootd", "initboot", "zram", "cpufreq",
            "kgsl", "devfreq", "blockwrite", "touchrate",
        )

        val QUALCOMM_CODENAMES = listOf(
            "msm", "sdm", "sm8", "sm7", "sm6", "pineapple", "kalama", "taro", "lahaina",
            "cape", "crow", "blair", "bengal", "holi", "parrot", "volcano", "kona", "waipio",
        )

        val SYSTEM_PROBE_SCRIPT: String = """
            echo "slot=$(getprop ro.boot.slot_suffix 2>/dev/null)"
            echo "bootctl=$(command -v bootctl 2>/dev/null)"
            echo "fastbootd=$(getprop ro.fastbootd.available 2>/dev/null)"
            echo "initboot=$(ls /dev/block/by-name/ 2>/dev/null | grep -c init_boot)"
            echo "zram=$(test -d /sys/block/zram0 && echo 1 || echo 0)"
            echo "cpufreq=$(test -r /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_frequencies && echo 1 || echo 0)"
            echo "kgsl=$(test -d /sys/class/kgsl/kgsl-3d0 && echo 1 || echo 0)"
            echo "devfreq=$(ls /sys/class/devfreq 2>/dev/null | wc -l)"
            echo "blockwrite=$(test -w /dev/block/by-name && echo 1 || echo 0)"
            echo "hardware=$(getprop ro.hardware 2>/dev/null)"
            echo "boothardware=$(getprop ro.boot.hardware 2>/dev/null)"
            echo "platform=$(getprop ro.board.platform 2>/dev/null)"
            echo "touchrate=$(ls /proc/touchpanel/sampling_rate /sys/class/touch/touch_dev/sampling_rate /sys/class/touchscreen/*/sampling_rate /sys/devices/virtual/touchscreen/*/sampling_rate /sys/kernel/touchpanel/sampling_rate 2>/dev/null | head -n 1)"
        """.trimIndent()

        const val DISPLAY_PROBE_COMMAND = "dumpsys display | grep -Eo 'fps=[0-9.]+' | sort -u"
    }
}
