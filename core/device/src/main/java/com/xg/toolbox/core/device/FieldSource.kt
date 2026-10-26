package com.xg.toolbox.core.device

import android.content.Context
import com.xg.toolbox.core.shell.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class FieldSource private constructor(
    private val props: Map<String, String>,
    private val shellFacts: Map<String, String>,
    private val rootFacts: Map<String, String>,
    val rootGranted: Boolean,
) {

    
    fun prop(key: String): String? = props[key]?.trim()?.takeIf { it.isNotEmpty() }

    
    fun shellFact(key: String): String? = shellFacts[key]?.trim()?.takeIf { it.isNotEmpty() }

    
    fun rootFact(key: String): String? = rootFacts[key]?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val PROPS_TIMEOUT_MS = 4_000L
        private const val SHELL_TIMEOUT_MS = 6_000L
        private const val ROOT_TIMEOUT_MS = 10_000L

        private const val PROPS_MARKER = "XG_PROPS_BEGIN"

        
        suspend fun load(context: Context, shell: Shell, rootGranted: Boolean): FieldSource =
            withContext(Dispatchers.IO) {
                val shellFacts = mutableMapOf<String, String>()
                val props = mutableMapOf<String, String>()

                val shellResult = shell.exec(SHELL_FACT_SCRIPT, root = false, timeoutMs = SHELL_TIMEOUT_MS)
                if (shellResult.ok) {
                    val parsed = parseFacts(shellResult.stdout)
                    shellFacts.putAll(parsed.facts)
                    props.putAll(parsed.props)
                }

                if (props.isEmpty()) {
                    val propsResult = shell.exec("getprop", root = false, timeoutMs = PROPS_TIMEOUT_MS)
                    if (propsResult.ok) props.putAll(parsePropLines(propsResult.stdout))
                }

                val rootFacts = if (rootGranted) {
                    val rootResult = shell.exec(ROOT_FACT_SCRIPT, root = true, timeoutMs = ROOT_TIMEOUT_MS)
                    if (rootResult.ok) parseFacts(rootResult.stdout).facts else emptyMap()
                } else {
                    emptyMap()
                }

                if (rootGranted && props.isEmpty()) {
                    val rootProps = shell.exec("getprop", root = true, timeoutMs = PROPS_TIMEOUT_MS)
                    if (rootProps.ok) props.putAll(parsePropLines(rootProps.stdout))
                }

                FieldSource(props = props, shellFacts = shellFacts, rootFacts = rootFacts, rootGranted = rootGranted)
            }

        
        private val SHELL_FACT_SCRIPT: String = listOf(
            "echo -n \"XG_WM_SIZE=\"; wm size 2>/dev/null | tr '\\n' '|'; echo",
            "echo -n \"XG_WM_DENSITY=\"; wm density 2>/dev/null | tr '\\n' '|'; echo",
            "echo -n \"XG_SETTINGS_ANDROID_ID=\"; settings get secure android_id 2>/dev/null; echo",
            "echo -n \"XG_ADB_ENABLED=\"; settings get global adb_enabled 2>/dev/null; echo",
            "echo -n \"XG_DEVELOPMENT_SETTINGS=\"; settings get global development_settings_enabled 2>/dev/null; echo",
            "echo -n \"XG_UNAME=\"; uname -r 2>/dev/null; echo",
            "echo -n \"XG_CPUINFO_CORES=\"; grep -c '^processor' /proc/cpuinfo 2>/dev/null; echo",
            "echo -n \"XG_IPV4=\"; ip -4 addr show wlan0 2>/dev/null | grep -o 'inet [0-9.]*' | head -n 1; echo",
            "echo -n \"XG_IPV6=\"; ip -6 addr show wlan0 2>/dev/null | grep -o 'inet6 [0-9a-f:]*' | head -n 1; echo",
            "echo $PROPS_MARKER",
            "getprop",
        ).joinToString("\n")

        
        private val ROOT_FACT_SCRIPT: String = listOf(
            "echo -n \"XG_SERIAL=\"; getprop ro.serialno 2>/dev/null; echo",
            "echo -n \"XG_IMEI=\"; getprop persist.radio.imei 2>/dev/null; echo",
            "echo -n \"XG_CHARGE_FULL_DESIGN=\"; cat /sys/class/power_supply/battery/charge_full_design 2>/dev/null; echo",
            "echo -n \"XG_CHARGE_FULL=\"; cat /sys/class/power_supply/battery/charge_full 2>/dev/null; echo",
            "echo -n \"XG_BATTERY_HEALTH=\"; cat /sys/class/power_supply/battery/health 2>/dev/null; echo",
            "echo -n \"XG_CYCLE_COUNT=\"; cat /sys/class/power_supply/battery/cycle_count 2>/dev/null; echo",
            "echo -n \"XG_WLAN_MAC=\"; cat /sys/class/net/wlan0/address 2>/dev/null; echo",
            "echo -n \"XG_BT_MAC=\"; settings get secure bluetooth_address 2>/dev/null; echo",
            "echo -n \"XG_SELINUX=\"; getenforce 2>/dev/null; echo",
            "echo -n \"XG_SELINUX_ENFORCE=\"; cat /sys/fs/selinux/enforce 2>/dev/null; echo",
            "echo -n \"XG_GPU_MODEL=\"; cat /sys/class/kgsl/kgsl-3d0/gpu_model 2>/dev/null; echo",
            "echo -n \"XG_GPU_GLES=\"; dumpsys SurfaceFlinger 2>/dev/null | grep -m1 'GLES:'; echo",
            "echo -n \"XG_WM_SIZE=\"; wm size 2>/dev/null | tr '\\n' '|'; echo",
            "echo -n \"XG_WM_DENSITY=\"; wm density 2>/dev/null | tr '\\n' '|'; echo",
            "echo -n \"XG_REFRESH_LIST=\"; dumpsys display 2>/dev/null | grep -Eo 'fps=[0-9.]+' | sort -u | tr '\\n' ','; echo",
            "echo -n \"XG_WIFI_SSID=\"; dumpsys wifi 2>/dev/null | grep -m1 -o 'SSID: \"[^\"]*\"'; echo",
            "echo -n \"XG_WEBVIEW=\"; dumpsys webviewupdate 2>/dev/null | grep -m1 'Current WebView package'; echo",
            "echo -n \"XG_MAX_FREQ_KHZ=\"; cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 2>/dev/null | sort -n | tail -n 1; echo",
            "echo -n \"XG_UPTIME=\" ; cat /proc/uptime 2>/dev/null; echo",
            "echo -n \"XG_GSF=\"; grep -h -o 'android_id[^<]*' /data/data/com.google.android.gsf/shared_prefs/*.xml 2>/dev/null | head -n 1; echo",
        ).joinToString("\n")

        
        private data class ParsedFacts(
            val facts: Map<String, String>,
            val props: Map<String, String>,
        )

        private const val FACT_PREFIX = "XG_"

        private fun parseFacts(output: String): ParsedFacts {
            val facts = mutableMapOf<String, String>()
            val props = mutableMapOf<String, String>()
            var inProps = false
            output.lineSequence().forEach { rawLine ->
                val line = rawLine.trimEnd('\r')
                if (!inProps && line.trim() == PROPS_MARKER) {
                    inProps = true
                    return@forEach
                }
                if (inProps) {
                    parsePropLine(line)?.let { (key, value) -> props[key] = value }
                    return@forEach
                }
                if (!line.startsWith(FACT_PREFIX)) return@forEach
                val index = line.indexOf('=')
                if (index <= FACT_PREFIX.length) return@forEach
                val key = line.substring(FACT_PREFIX.length, index).trim()
                facts[key] = line.substring(index + 1).trim()
            }
            return ParsedFacts(facts = facts, props = props)
        }

        private fun parsePropLines(output: String): Map<String, String> {
            val props = mutableMapOf<String, String>()
            output.lineSequence().forEach { line ->
                parsePropLine(line)?.let { (key, value) -> props[key] = value }
            }
            return props
        }

        
        private fun parsePropLine(line: String): Pair<String, String>? {
            val text = line.trim()
            if (!text.startsWith("[") || !text.contains("]: [")) return null
            val keyEnd = text.indexOf("]: [")
            if (keyEnd <= 1) return null
            val key = text.substring(1, keyEnd)
            val value = text.substring(keyEnd + 4).removeSuffix("]")
            return key to value
        }
    }
}
