package com.xg.toolbox.core.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.xg.toolbox.core.ui.theme.LocalXgColors

enum class XgTone {
    Accent,
    Ok,
    Warn,
    Danger,
    Muted,
    Neutral,
}

@Composable
fun xgToneColor(tone: XgTone): Color {
    val colors = LocalXgColors.current
    return when (tone) {
        XgTone.Accent -> colors.accent
        XgTone.Ok -> colors.ok
        XgTone.Warn -> colors.warn
        XgTone.Danger -> colors.danger
        XgTone.Muted -> colors.muted
        XgTone.Neutral -> colors.text
    }
}

@Composable
fun xgToneSoftColor(tone: XgTone): Color {
    val colors = LocalXgColors.current
    return when (tone) {
        XgTone.Accent -> colors.accentSoft
        XgTone.Ok -> colors.ok.copy(alpha = 0.10f)
        XgTone.Warn -> colors.warnSoft
        XgTone.Danger -> colors.dangerSoft
        XgTone.Muted -> Color.Transparent
        XgTone.Neutral -> colors.surface2
    }
}

@Composable
fun xgToneBorderColor(tone: XgTone): Color {
    val colors = LocalXgColors.current
    return when (tone) {
        XgTone.Accent -> colors.accent.copy(alpha = 0.30f)
        XgTone.Ok -> colors.ok.copy(alpha = 0.30f)
        XgTone.Warn -> colors.warn.copy(alpha = 0.30f)
        XgTone.Danger -> colors.danger.copy(alpha = 0.32f)
        XgTone.Muted -> colors.lineStrong
        XgTone.Neutral -> colors.lineStrong
    }
}

@Composable
fun xgToneSolidColor(tone: XgTone): Color = when (tone) {
    XgTone.Danger -> LocalXgColors.current.danger
    XgTone.Ok -> LocalXgColors.current.ok
    XgTone.Warn -> LocalXgColors.current.warn
    else -> LocalXgColors.current.accent
}

@Composable
fun xgToneOnSolidColor(tone: XgTone): Color {
    val colors = LocalXgColors.current
    return when (tone) {
        XgTone.Danger -> colors.onDanger
        XgTone.Ok, XgTone.Warn -> colors.bg
        else -> colors.onAccent
    }
}
