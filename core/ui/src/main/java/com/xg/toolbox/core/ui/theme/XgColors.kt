package com.xg.toolbox.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class XgColors(
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val inset: Color,
    val ink: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val line: Color,
    val lineStrong: Color,
    val accent: Color,
    val accentDeep: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val ok: Color,
    val console: Color,
    val isDark: Boolean,
) {
    
    val onAccent: Color get() = if (isDark) Color(0xFF11161B) else Color(0xFFFFFFFF)

    
    val onDanger: Color get() = if (isDark) Color(0xFF1B1310) else Color(0xFFFFFFFF)
}

object XgThemeDefaults {
    val light: XgColors = XgColors(
        bg = Color(0xFFE9ECEF),
        surface = Color(0xFFFCFCFA),
        surface2 = Color(0xFFF3F5F6),
        inset = Color(0xFFEDEFF1),
        ink = Color(0xFF111418),
        text = Color(0xFF2A3238),
        muted = Color(0xFF4F5A63),
        faint = Color(0xFF5F6A73),
        line = Color(0x1A111418),          // rgba(17,20,24,.10)
        lineStrong = Color(0x30111418),    // rgba(17,20,24,.19)
        accent = Color(0xFF2B4C8C),
        accentDeep = Color(0xFF213C70),
        accentSoft = Color(0x142B4C8C),    // rgba(43,76,140,.08)
        danger = Color(0xFFA6402D),
        dangerSoft = Color(0x12A6402D),    // rgba(166,64,45,.07)
        warn = Color(0xFF7E5416),
        warnSoft = Color(0x1796681E),      // rgba(150,104,30,.09)
        ok = Color(0xFF2C6B4F),
        console = Color(0xFF14181C),
        isDark = false,
    )

    val dark: XgColors = XgColors(
        bg = Color(0xFF14171A),
        surface = Color(0xFF1C2024),
        surface2 = Color(0xFF23282D),
        inset = Color(0xFF111417),
        ink = Color(0xFFEDF0F2),
        text = Color(0xFFC9D0D5),
        muted = Color(0xFF9AA4AC),
        faint = Color(0xFF858F97),        // 暗色辅助文字：对 surface2 对比度 ≥4.5:1
        line = Color(0x1FEDF0F2),          // rgba(237,240,242,.12)
        lineStrong = Color(0x3DEDF0F2),    // rgba(237,240,242,.24)
        accent = Color(0xFF7EA0E0),
        accentDeep = Color(0xFFA8C0F0),
        accentSoft = Color(0x247EA0E0),    // rgba(126,160,224,.14)
        danger = Color(0xFFE0806C),
        dangerSoft = Color(0x1FE0806C),    // rgba(224,128,108,.12)
        warn = Color(0xFFD8AE63),
        warnSoft = Color(0x1FD8AE63),      // rgba(216,174,99,.12)
        ok = Color(0xFF77C6A1),
        console = Color(0xFF0E1114),
        isDark = true,
    )
}

val LocalXgColors: ProvidableCompositionLocal<XgColors> = staticCompositionLocalOf { XgThemeDefaults.light }
