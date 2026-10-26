package com.xg.toolbox.core.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt

val XgEase: Easing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1f)

val XgEaseOutCubic: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

val XgEaseInCubic: Easing = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)

val XgEaseInOutCubic: Easing = CubicBezierEasing(0.645f, 0.045f, 0.355f, 1f)

val LocalXgMotionScale: ProvidableCompositionLocal<Float> = staticCompositionLocalOf { 1f }

val LocalXgReduceMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

@Composable
fun rememberSystemAnimatorScale(): Float {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching {
            Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f).coerceAtLeast(0f)
    }
}

fun xgMotionDuration(baseMs: Int, scale: Float): Int =
    if (scale <= 0f) 0 else (baseMs * scale).roundToInt().coerceAtLeast(1)

fun <T> xgTween(
    baseMs: Int,
    scale: Float,
    easing: Easing = XgEase,
): AnimationSpec<T> =
    if (scale <= 0f) snap() else tween(durationMillis = xgMotionDuration(baseMs, scale), easing = easing)

fun <T> xgSpring(scale: Float): AnimationSpec<T> =
    if (scale <= 0f) snap() else spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

