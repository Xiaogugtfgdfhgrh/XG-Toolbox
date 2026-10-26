// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur.highlight

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import top.yukonga.miuix.kmp.blur.RuntimeShaderCache
import top.yukonga.miuix.kmp.blur.internal.BLOOM_STROKE_SHADER_DUAL
import top.yukonga.miuix.kmp.blur.internal.BLOOM_STROKE_SHADER_SINGLE
import top.yukonga.miuix.kmp.shader.RuntimeShader
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported
import kotlin.math.floor
import kotlin.math.sqrt

private const val LIGHT_REF_X = 0.5f
private const val LIGHT_REF_Y = 0.7f

@Immutable
data class BloomStroke(
    override val color: Color = Color.White.copy(alpha = 0.05f),
    override val blendMode: BlendMode = BlendMode.Plus,
    val innerBlurRadius: Dp = 2.8.dp,
    val primaryLight: LightSource = LightSource(LightPosition(0.5f, 0.5f, -0.5f), intensity = 0.4f),
    val secondaryLight: LightSource = LightSource(LightPosition(0.5f, 0.8f, -0.5f), intensity = 0.25f),
    val dualPeak: Boolean = false,
) : HighlightStyle {
    override fun DrawScope.createShader(
        shape: Shape,
        strokeWidthPx: Float,
        highlightAlpha: Float,
        runtimeShaderCache: RuntimeShaderCache,
    ): Shader? {
        if (!isRuntimeShaderSupported()) return null
        val shaderKey = if (dualPeak) "BloomStrokeDual" else "BloomStrokeSingle"
        val shaderSource = if (dualPeak) BLOOM_STROKE_SHADER_DUAL else BLOOM_STROKE_SHADER_SINGLE
        val shader = runtimeShaderCache.obtainRuntimeShader(shaderKey, shaderSource)
        val halfW = size.width * 0.5f
        val halfH = size.height * 0.5f
        shader.setFloatUniform("halfView", halfW, halfH)
        shader.setFloatUniform("halfViewFloor", floor(halfW), floor(halfH))
        setCornerRadiiUniform(shader, shape)
        shader.setFloatUniform("strokeWidth", strokeWidthPx)
        val innerR = innerBlurRadius.toPx()
        shader.setFloatUniform("innerBlurRadius", innerR)
        shader.setFloatUniform("innerBlurRadiusSq", innerR * innerR)
        shader.setFloatUniform("highlightAlpha", highlightAlpha)
        shader.setColorUniform("strokeColor", color.copy(alpha = 1f))
        shader.setFloatUniform("strokeAlphaMul", color.alpha)
        applyLightUniforms(shader, "1", primaryLight, includeAxis = !dualPeak)
        applyLightUniforms(shader, "2", secondaryLight, includeAxis = !dualPeak)
        return shader.shader
    }
}

internal fun applyLightUniforms(shader: RuntimeShader, suffix: String, light: LightSource, includeAxis: Boolean = true) {
    val dx = light.position.x - LIGHT_REF_X
    val dy = light.position.y - LIGHT_REF_Y
    val dz = light.position.z
    val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
    val nx = dx / len
    val ny = dy / len
    shader.setFloatUniform("lightDir$suffix", nx, ny, dz / len)
    shader.setColorUniform("lightColor$suffix", light.color.copy(alpha = 1f))
    shader.setFloatUniform("lightIntensity$suffix", light.color.alpha * light.intensity)
    if (includeAxis) {
        val xyLen = sqrt(nx * nx + ny * ny)
        if (xyLen > 1e-3f) shader.setFloatUniform("axis$suffix", nx / xyLen, ny / xyLen)
        else shader.setFloatUniform("axis$suffix", 0f, if (suffix == "1") -1f else 1f)
    }
}

internal fun DrawScope.setCornerRadiiUniform(shader: RuntimeShader, shape: Shape) {
    val maxRadius = size.minDimension / 2f
    val cornerShape = shape as? CornerBasedShape
    if (cornerShape == null) {
        shader.setFloatUniform("cornerRadii", maxRadius, maxRadius, maxRadius, maxRadius)
        return
    }
    val isLtr = layoutDirection == LayoutDirection.Ltr
    val topLeft = if (isLtr) cornerShape.topStart.toPx(size, this) else cornerShape.topEnd.toPx(size, this)
    val topRight = if (isLtr) cornerShape.topEnd.toPx(size, this) else cornerShape.topStart.toPx(size, this)
    val bottomRight = if (isLtr) cornerShape.bottomEnd.toPx(size, this) else cornerShape.bottomStart.toPx(size, this)
    val bottomLeft = if (isLtr) cornerShape.bottomStart.toPx(size, this) else cornerShape.bottomEnd.toPx(size, this)
    shader.setFloatUniform(
        "cornerRadii",
        topLeft.fastCoerceAtMost(maxRadius),
        topRight.fastCoerceAtMost(maxRadius),
        bottomLeft.fastCoerceAtMost(maxRadius),
        bottomRight.fastCoerceAtMost(maxRadius),
    )
}
