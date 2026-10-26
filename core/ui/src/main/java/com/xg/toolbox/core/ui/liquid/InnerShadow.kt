// Adapted from Kyant0/AndroidLiquidGlass and compose-miuix-ui (Apache-2.0).

package com.xg.toolbox.core.ui.liquid

import android.graphics.BlurMaskFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

@Immutable
data class InnerShadow(
    val radius: Dp = 24.dp,
    val offset: DpOffset = DpOffset(0.dp, radius),
    val color: Color = Color.Black.copy(alpha = 0.15f),
    val alpha: Float = 1f,
    val blendMode: BlendMode = BlendMode.SrcOver,
) {
    companion object {
        @Stable
        val Default: InnerShadow = InnerShadow()
    }
}

fun Modifier.innerShadow(shape: Shape, shadow: () -> InnerShadow?): Modifier = drawWithContent {
    drawContent()
    val current = shadow() ?: return@drawWithContent
    if (current.alpha <= 0f || current.radius.value < 0f) return@drawWithContent
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = when (outline) {
        is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
        is Outline.Generic -> outline.path
    }
    val radiusPx = current.radius.toPx()
    val offsetX = current.offset.x.toPx()
    val offsetY = current.offset.y.toPx()
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        val layerPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            alpha = (current.alpha * 255f).toInt().coerceIn(0, 255)
        }
        val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = current.color.toArgb()
        }
        val clearPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            if (radiusPx > 0f) maskFilter = BlurMaskFilter(radiusPx, BlurMaskFilter.Blur.NORMAL)
        }
        native.save()
        native.clipPath(path.asAndroidPath())
        native.saveLayer(0f, 0f, size.width, size.height, layerPaint)
        native.drawPath(path.asAndroidPath(), shadowPaint)
        native.translate(offsetX, offsetY)
        native.drawPath(path.asAndroidPath(), clearPaint)
        native.translate(-offsetX, -offsetY)
        native.restore()
        native.restore()
    }
}
