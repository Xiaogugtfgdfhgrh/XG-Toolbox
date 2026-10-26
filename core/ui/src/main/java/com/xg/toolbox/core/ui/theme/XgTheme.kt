package com.xg.toolbox.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

@Composable
fun XgTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) XgThemeDefaults.dark else XgThemeDefaults.light
    val systemScale = rememberSystemAnimatorScale()
    val reduceMotion = LocalXgReduceMotion.current
    val motionScale = if (reduceMotion) 0f else systemScale

    CompositionLocalProvider(
        LocalXgColors provides colors,
        LocalXgMotionScale provides motionScale,
    ) {
        MaterialTheme(colorScheme = colors.toMaterialColorScheme()) {
            CompositionLocalProvider(LocalContentColor provides colors.ink) {
                content()
            }
        }
    }
}

fun XgColors.toMaterialColorScheme(): androidx.compose.material3.ColorScheme = if (isDark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = ok,
        onSecondary = bg,
        tertiary = warn,
        onTertiary = bg,
        background = bg,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surface2,
        onSurfaceVariant = muted,
        outline = lineStrong,
        outlineVariant = line,
        error = danger,
        onError = onDanger,
        scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = ok,
        onSecondary = bg,
        tertiary = warn,
        onTertiary = bg,
        background = bg,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surface2,
        onSurfaceVariant = muted,
        outline = lineStrong,
        outlineVariant = line,
        error = danger,
        onError = onDanger,
        scrim = Color.Black,
    )
}
