package com.xg.toolbox.feature.system.domain.display

import com.xg.toolbox.core.shell.Shell

internal sealed interface VendorSwitch {
    data class SettingsKey(val namespace: String, val key: String, val raw: String) : VendorSwitch
    data class NodePath(val path: String, val raw: String) : VendorSwitch
}

internal data class DisplaySnapshot(
    val physicalSize: String,
    val overrideSize: String?,
    val physicalDensity: Int,
    val overrideDensity: Int?,
    val refreshOptionsHz: List<Int>,
    val peakHz: Float?,
    val minHz: Float?,
    val userHz: Float?,
    val nightMode: String,
    val supportedColorModes: List<Int>,
    val vendorColorMode: String?,
    val dcSource: VendorSwitch?,
    val dcEnabled: Boolean,
    val colorTemperatureSource: VendorSwitch?,
    val colorTemperature: Int?,
    val touchNode: String?,
    val touchSampleHz: Int?,
    val touchSampleOptions: List<Int>,
) {
    
    val size: String get() = overrideSize ?: physicalSize

    
    val density: Int get() = overrideDensity ?: physicalDensity

    val forceHighRefresh: Boolean
        get() = peakHz != null && minHz != null && kotlin.math.abs(peakHz - minHz) < 0.6f
}

internal class DisplayProbe(private val shell: Shell) {

    suspend fun probe(): DisplaySnapshot? {
        val result = shell.exec(PROBE_SCRIPT, root = true, timeoutMs = PROBE_TIMEOUT_MS)
        if (!result.ok) return null
        val output = result.stdout.substringBefore(SECTION_END)
        val sections = splitSections(output)

        val wm = sections[SECTION_WM].orEmpty()
        val physicalSize = Regex("Physical size:\\s*(\\d+x\\d+)").find(wm)?.groupValues?.get(1)
            ?: return null
        val overrideSize = Regex("Override size:\\s*(\\d+x\\d+)").find(wm)?.groupValues?.get(1)
        val physicalDensity = Regex("Physical density:\\s*(\\d+)").find(wm)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        val overrideDensity = Regex("Override density:\\s*(\\d+)").find(wm)?.groupValues?.get(1)?.toIntOrNull()

        val refreshOptions = sections[SECTION_FPS].orEmpty().lineSequence()
            .mapNotNull { FPS_REGEX.find(it)?.groupValues?.getOrNull(1)?.toFloatOrNull() }
            .map { it.toInt() }
            .filter { it > 0 }
            .distinct()
            .sorted()
            .toList()

        val refreshSection = sections[SECTION_REFRESH].orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val peakHz = refreshSection.getOrNull(0)?.toFloatOrNull()
        val minHz = refreshSection.getOrNull(1)?.toFloatOrNull()
        val userHz = refreshSection.getOrNull(2)?.toFloatOrNull()

        val nightMode = sections[SECTION_NIGHT].orEmpty().lineSequence()
            .firstOrNull { it.contains("Night mode", ignoreCase = true) }
            ?.substringAfter(':', "")
            ?.trim()
            ?.lowercase()
            ?.takeIf { it == "yes" || it == "no" || it == "auto" }
            ?: "unknown"

        val colorModeSection = sections[SECTION_COLOR_MODE].orEmpty()
        val supportedColorModes = Regex("supportedColorModes\\s*\\[([0-9,\\s]*)]")
            .find(colorModeSection)
            ?.groupValues
            ?.getOrNull(1)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
        val vendorColorMode = colorModeSection.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.matches(Regex("\\d+")) }

        val dcSection = sections[SECTION_DC].orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val dcSource = parseSwitch(dcSection, "secure", "dc_dimming_enable")
        val dcEnabled = dcSource?.isEnabled() ?: false

        val tempSection = sections[SECTION_TEMPERATURE].orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val colorTemperatureSource = parseSwitch(tempSection, "system", "screen_color_temperature")
        val colorTemperature = colorTemperatureSource?.numericValue()

        val touchSection = sections[SECTION_TOUCH].orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        val touchNode = touchSection.firstOrNull { !it.contains("_options") }?.substringBefore(':')
        val touchSampleHz = touchSection.firstOrNull { !it.contains("_options") }
            ?.substringAfter(':', "")
            ?.trim()
            ?.toIntOrNull()
        val touchSampleOptions = touchSection.filter { it.contains("_options") }
            .flatMap { line ->
                line.substringAfter(':', "").split(WHITESPACE)
                    .mapNotNull { it.toIntOrNull() }
            }
            .distinct()
            .sorted()

        val snapshot = DisplaySnapshot(
            physicalSize = physicalSize,
            overrideSize = overrideSize,
            physicalDensity = physicalDensity,
            overrideDensity = overrideDensity,
            refreshOptionsHz = refreshOptions,
            peakHz = peakHz,
            minHz = minHz,
            userHz = userHz,
            nightMode = nightMode,
            supportedColorModes = supportedColorModes,
            vendorColorMode = vendorColorMode,
            dcSource = dcSource,
            dcEnabled = dcEnabled,
            colorTemperatureSource = colorTemperatureSource,
            colorTemperature = colorTemperature,
            touchNode = touchNode,
            touchSampleHz = touchSampleHz,
            touchSampleOptions = touchSampleOptions,
        )
        if (snapshot.refreshOptionsHz.isEmpty() && snapshot.physicalDensity <= 0) return null
        return snapshot
    }

    
    private fun parseSwitch(lines: List<String>, settingsNamespace: String, settingsKey: String): VendorSwitch? {
        lines.forEach { line ->
            when {
                line.startsWith("/sys/") -> {
                    val index = line.indexOf(':')
                    if (index <= 0) return@forEach
                    val path = line.substring(0, index)
                    val raw = line.substring(index + 1).trim()
                    if (raw.matches(Regex("[0-9]{1,6}"))) return VendorSwitch.NodePath(path, raw)
                }
                line.matches(Regex("[0-9]{1,6}")) -> {
                    return VendorSwitch.SettingsKey(settingsNamespace, settingsKey, line)
                }
            }
        }
        return null
    }

    private fun VendorSwitch.isEnabled(): Boolean = when (this) {
        is VendorSwitch.SettingsKey -> raw == "1"
        is VendorSwitch.NodePath -> raw == "1"
    }

    private fun VendorSwitch.numericValue(): Int? = when (this) {
        is VendorSwitch.SettingsKey -> raw.toIntOrNull()
        is VendorSwitch.NodePath -> raw.toIntOrNull()
    }

    private fun splitSections(output: String): Map<String, String> {
        val sections = mutableMapOf<String, String>()
        var current = ""
        val buffer = StringBuilder()
        fun flush() {
            if (current.isNotEmpty()) sections[current] = buffer.toString()
            buffer.clear()
        }
        output.lineSequence().forEach { line ->
            val marker = line.trim()
            if (marker.startsWith("@@") && marker.length > 2) {
                flush()
                current = marker.removePrefix("@@").lowercase()
            } else {
                buffer.appendLine(line)
            }
        }
        flush()
        return sections
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 20_000L
        const val SECTION_WM = "wm"
        const val SECTION_FPS = "fps"
        const val SECTION_REFRESH = "refresh"
        const val SECTION_NIGHT = "night"
        const val SECTION_COLOR_MODE = "colormode"
        const val SECTION_TEMPERATURE = "temperature"
        const val SECTION_DC = "dc"
        const val SECTION_TOUCH = "touch"
        const val SECTION_END = "@@END"

        val FPS_REGEX = Regex("fps=([0-9]+(?:\\.[0-9]+)?)")
        val WHITESPACE = Regex("\\s+")

        
        val PROBE_SCRIPT: String = """
            echo '@@WM'
            wm size
            wm density
            echo '@@FPS'
            dumpsys display | grep -Eo 'fps=[0-9.]+' | sort -u
            echo '@@REFRESH'
            settings get system peak_refresh_rate
            settings get system min_refresh_rate
            settings get system user_refresh_rate
            echo '@@NIGHT'
            cmd uimode night
            echo '@@COLORMODE'
            dumpsys display | grep -o 'supportedColorModes \[[0-9, ]*\]' | head -n 1
            settings get system display_color_mode
            echo '@@TEMPERATURE'
            settings get system screen_color_temperature
            grep -H '' /sys/class/backlight/panel0-backlight/color_temperature /sys/kernel/oplus_display/color_temperature 2>/dev/null
            echo '@@DC'
            settings get secure dc_dimming_enable
            grep -H '' /sys/class/backlight/panel0-backlight/dc_dimming /sys/kernel/oplus_display/dc_dimming /sys/class/drm/card0-DSI-1/dc_dimming 2>/dev/null
            echo '@@TOUCH'
            grep -H '' /proc/touchpanel/sampling_rate /sys/class/touch/touch_dev/sampling_rate /sys/class/touchscreen/*/sampling_rate /sys/devices/virtual/touchscreen/*/sampling_rate /proc/touchpanel/touch_report_rate 2>/dev/null
            grep -H '' /sys/class/touch/touch_dev/sampling_rate_options /sys/class/touchscreen/*/sampling_rate_options /proc/touchpanel/sampling_rate_options 2>/dev/null
            echo '@@END'
        """.trimIndent()
    }
}
