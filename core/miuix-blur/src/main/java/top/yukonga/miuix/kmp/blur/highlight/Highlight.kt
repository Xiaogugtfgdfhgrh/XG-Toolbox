// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur.highlight

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.RuntimeShaderCache

@Immutable
data class LightPosition(val x: Float, val y: Float, val z: Float)

@Immutable
data class LightSource(
    val position: LightPosition,
    val color: Color = Color.White,
    val intensity: Float = 1f,
)

@Immutable
data class Highlight(
    val width: Dp = 1.dp,
    val alpha: Float = 1f,
    val style: HighlightStyle = BloomStroke(),
)

@Immutable
interface HighlightStyle {
    val color: Color
    val blendMode: BlendMode
    fun DrawScope.createShader(
        shape: Shape,
        strokeWidthPx: Float,
        highlightAlpha: Float,
        runtimeShaderCache: RuntimeShaderCache,
    ): Shader?
}
