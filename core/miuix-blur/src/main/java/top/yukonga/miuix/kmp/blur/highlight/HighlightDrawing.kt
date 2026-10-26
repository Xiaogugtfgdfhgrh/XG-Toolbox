// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.blur.highlight

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.util.fastCoerceAtMost
import top.yukonga.miuix.kmp.blur.RuntimeShaderCache

internal fun DrawScope.drawHighlight(
    highlight: Highlight,
    shape: Shape,
    runtimeShaderCache: RuntimeShaderCache,
    paint: Paint,
) {
    if (highlight.width.value <= 0f || highlight.alpha <= 0f) return
    if (size.width <= 0f || size.height <= 0f) return
    val strokeWidthPx = highlight.width.toPx().fastCoerceAtMost(size.minDimension / 2f)
    val style = highlight.style
    val shader = with(style) {
        createShader(shape, strokeWidthPx, highlight.alpha, runtimeShaderCache)
    }
    if (shader != null) {
        paint.shader = shader
        paint.blendMode = style.blendMode
        drawIntoCanvas { canvas ->
            canvas.drawRect(0f, 0f, size.width, size.height, paint)
        }
        paint.shader = null
        return
    }

    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(
        outline = outline,
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.72f * highlight.alpha),
            0.45f to style.color.copy(alpha = 0.14f * highlight.alpha),
            1f to Color.White.copy(alpha = 0.42f * highlight.alpha),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        ),
        style = Stroke(width = strokeWidthPx),
        blendMode = style.blendMode,
    )
}


