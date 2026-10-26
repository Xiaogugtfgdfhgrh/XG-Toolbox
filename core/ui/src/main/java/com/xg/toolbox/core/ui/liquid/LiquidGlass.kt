package com.xg.toolbox.core.ui.liquid

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.theme.LocalXgColors
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.LightSource
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported

val LocalXgLiquidGlassEnabled: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { true }

val LocalXgBackdropVisible: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

val LocalXgBackdrop: ProvidableCompositionLocal<Backdrop?> =
    staticCompositionLocalOf { null }

fun isXgGlassSupported(): Boolean = isRenderEffectSupported()

@Composable
fun xgPageSurfaceColor(alpha: Float = 0.08f): Color {
    val colors = LocalXgColors.current
    return if (LocalXgBackdropVisible.current) {
        colors.bg.copy(alpha = alpha.coerceIn(0f, 0.22f))
    } else {
        colors.bg
    }
}

@Composable
fun xgBackdropSurfaceColor(opaque: Color, alphaWhenVisible: Float = 0.68f): Color =
    if (LocalXgBackdropVisible.current) opaque.copy(alpha = alphaWhenVisible.coerceIn(0f, 1f)) else opaque

private val xgGlassHighlight = Highlight(
    width = 1.dp,
    alpha = 0.78f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.dp,
        primaryLight = LightSource(LightPosition(0.5f, -0.3f, -0.05f), intensity = 1f),
        secondaryLight = LightSource(LightPosition(0.5f, 0.8f, -0.5f), intensity = 0.4f),
        dualPeak = true,
    ),
)

@Composable
fun Modifier.xgLiquidGlassSurface(
    shape: Shape,
    surfaceColor: Color,
    accentColor: Color,
    borderColor: Color,
    opaqueSurfaceColor: Color = surfaceColor.copy(alpha = 1f),
    enabled: Boolean = LocalXgLiquidGlassEnabled.current,
    elevation: Dp = 8.dp,
): Modifier {
    val colors = LocalXgColors.current
    val shadowColor = if (colors.isDark) Color.Black.copy(alpha = 0.38f) else colors.ink.copy(alpha = 0.18f)
    val backdrop = LocalXgBackdrop.current
    val glass = enabled && backdrop != null && isXgGlassSupported()
    if (!glass) {
        return this
            .shadow(elevation, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
            .clip(shape)
            .background(opaqueSurfaceColor, shape)
            .border(1.dp, borderColor, shape)
    }
    return this
        .shadow(elevation, shape, clip = false, ambientColor = shadowColor, spotColor = shadowColor)
        .drawBackdrop(
            backdrop = backdrop!!,
            shape = { shape },
            effects = {
                vibrancy()
                blur(16.dp.toPx(), 16.dp.toPx())
                lens(refractionHeight = 24.dp.toPx(), refractionAmount = 24.dp.toPx())
            },
            highlight = { xgGlassHighlight },
            onDrawSurface = { drawRect(surfaceColor) },
        )
        .border(1.dp, borderColor.copy(alpha = 0.55f), shape)
}
