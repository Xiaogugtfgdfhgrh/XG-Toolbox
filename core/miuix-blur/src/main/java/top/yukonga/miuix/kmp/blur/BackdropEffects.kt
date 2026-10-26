// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur

import android.annotation.SuppressLint
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import top.yukonga.miuix.kmp.blur.internal.COLOR_CONTROLS_SHADER
import top.yukonga.miuix.kmp.blur.internal.chain
import top.yukonga.miuix.kmp.blur.internal.runtimeShaderEffect as createRuntimeShaderEffect
import top.yukonga.miuix.kmp.shader.RuntimeShader
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported

@SuppressLint("NewApi")
fun BackdropEffectScope.blur(radiusX: Float, radiusY: Float = radiusX) {
    val scope = impl
    if (!isRenderEffectSupported()) return
    if (radiusX <= 0f && radiusY <= 0f) return
    val sigmaX = radiusX * 0.45f
    val sigmaY = radiusY * 0.45f
    val sigmaSq = maxOf(sigmaX * sigmaX, sigmaY * sigmaY)
    val sf = when {
        sigmaSq >= 90.25f -> 4
        sigmaSq >= 12.6f -> 2
        else -> 1
    }
    val kernelPadding = 13f * sf
    if (kernelPadding > padding) padding = kernelPadding
    if (sf > downscaleFactor) downscaleFactor = sf

    val sizeW = size.width
    val sizeH = size.height
    val effect: android.graphics.RenderEffect = if (scope.cachedBlurResult != null &&
        scope.cachedBlurRadiusX == radiusX && scope.cachedBlurRadiusY == radiusY &&
        scope.cachedBlurSizeW == sizeW && scope.cachedBlurSizeH == sizeH &&
        scope.cachedBlurDownscale == downscaleFactor
    ) {
        scope.cachedBlurResult!!
    } else {
        android.graphics.RenderEffect.createBlurEffect(
            sigmaX / downscaleFactor,
            sigmaY / downscaleFactor,
            android.graphics.Shader.TileMode.CLAMP,
        ).also {
            scope.cachedBlurRadiusX = radiusX
            scope.cachedBlurRadiusY = radiusY
            scope.cachedBlurSizeW = sizeW
            scope.cachedBlurSizeH = sizeH
            scope.cachedBlurDownscale = downscaleFactor
            scope.cachedBlurResult = it
        }
    }
    renderEffect = renderEffect?.chain(effect) ?: effect
}

@SuppressLint("NewApi")
fun BackdropEffectScope.colorControls(
    brightness: Float = 0f,
    contrast: Float = 1f,
    saturation: Float = 1f,
) {
    if (brightness == 0f && contrast == 1f && saturation == 1f) return
    val scope = impl
    if (isRuntimeShaderSupported()) {
        val effect: android.graphics.RenderEffect = if (scope.cachedColorResult != null &&
            scope.cachedColorBrightness == brightness &&
            scope.cachedColorContrast == contrast &&
            scope.cachedColorSaturation == saturation
        ) {
            scope.cachedColorResult!!
        } else {
            val shader = obtainRuntimeShader("ColorControls", COLOR_CONTROLS_SHADER).apply {
                setFloatUniform("in_brightness", brightness)
                setFloatUniform("in_contrast", contrast)
                setFloatUniform("in_saturation", saturation)
            }
            createRuntimeShaderEffect(shader, "child").also {
                scope.cachedColorBrightness = brightness
                scope.cachedColorContrast = contrast
                scope.cachedColorSaturation = saturation
                scope.cachedColorResult = it
            }
        }
        renderEffect = renderEffect?.chain(effect) ?: effect
        return
    }

    val invS = 1f - saturation
    val lr = 0.213f * invS
    val lg = 0.715f * invS
    val lb = 0.072f * invS
    val c = contrast
    val scale = 1f + brightness
    val translate = (1f - c) * 0.5f
    val matrix = floatArrayOf(
        (lr + saturation) * c * scale, lg * c * scale, lb * c * scale, 0f, translate,
        lr * c * scale, (lg + saturation) * c * scale, lb * c * scale, 0f, translate,
        lr * c * scale, lg * c * scale, (lb + saturation) * c * scale, 0f, translate,
        0f, 0f, 0f, 1f, 0f,
    )
    colorFilter = ColorFilter.colorMatrix(ColorMatrix(matrix))
}

@SuppressLint("NewApi")
fun BackdropEffectScope.runtimeShaderEffect(
    key: String,
    shaderString: String,
    uniformShaderName: String,
    block: RuntimeShader.() -> Unit,
) {
    if (!isRuntimeShaderSupported()) return
    val effect = createRuntimeShaderEffect(
        runtimeShader = obtainRuntimeShader(key, shaderString).apply(block),
        uniformShaderName = uniformShaderName,
    )
    renderEffect = renderEffect?.chain(effect) ?: effect
}
