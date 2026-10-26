package com.xg.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.liquid.xgBackdropSurfaceColor
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgType

@Composable
fun XgMetricCard(
    name: String,
    hint: String,
    value: String,
    unit: String,
    points: List<Float>,
    tone: XgTone = XgTone.Accent,
    modifier: Modifier = Modifier,
) {
    MetricCardFrame(
        name = name,
        hint = hint,
        value = value,
        unit = unit,
        tone = tone,
        modifier = modifier,
    ) { color, sparklineModifier ->
        Sparkline(
            count = points.size,
            pointAt = { index -> points[index] },
            color = color,
            modifier = sparklineModifier,
            revision = points.hashCode(),
        )
    }
}

@Composable
fun XgMetricCard(
    name: String,
    hint: String,
    value: String,
    unit: String,
    points: FloatArray,
    pointsCount: Int = points.size,
    pointsRevision: Int = 0,
    tone: XgTone = XgTone.Accent,
    modifier: Modifier = Modifier,
) {
    val count = pointsCount.coerceIn(0, points.size)
    MetricCardFrame(
        name = name,
        hint = hint,
        value = value,
        unit = unit,
        tone = tone,
        modifier = modifier,
    ) { color, sparklineModifier ->
        Sparkline(
            count = count,
            pointAt = { index -> points[index] },
            color = color,
            modifier = sparklineModifier,
            revision = pointsRevision,
        )
    }
}

@Composable
private fun MetricCardFrame(
    name: String,
    hint: String,
    value: String,
    unit: String,
    tone: XgTone,
    modifier: Modifier,
    sparkline: @Composable (Color, Modifier) -> Unit,
) {
    val colors = LocalXgColors.current
    val shape = remember { RoundedCornerShape(12.dp) }
    val lineColor = when (tone) {
        XgTone.Accent -> colors.accent
        XgTone.Ok -> colors.ok
        XgTone.Warn -> colors.warn
        XgTone.Danger -> colors.danger
        XgTone.Muted -> colors.faint
        XgTone.Neutral -> colors.muted
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(xgBackdropSurfaceColor(colors.surface, alphaWhenVisible = 0.68f))
            .border(1.dp, colors.line, shape)
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = name,
                style = XgType.metricName,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = hint,
                style = XgType.metricNow,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = XgType.monoValueLarge,
                color = colors.ink,
                maxLines = 1,
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = unit,
                style = XgType.metricUnit,
                color = colors.faint,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        sparkline(lineColor, Modifier.fillMaxWidth().height(26.dp))
    }
}

@Composable
private fun Sparkline(
    count: Int,
    pointAt: (Int) -> Float,
    color: Color,
    modifier: Modifier = Modifier,
    revision: Int = 0,
) {
    val colors = LocalXgColors.current
    val revisionState = rememberUpdatedState(revision)
    Spacer(
        modifier = modifier.drawWithCache {
            @Suppress("UNUSED_VARIABLE")
            val cacheRevision = revisionState.value
            if (count < 2) {
                val centerY = size.height / 2f
                val lineColor = colors.lineStrong
                onDrawBehind {
                    drawLine(
                        color = lineColor,
                        start = Offset(0f, centerY),
                        end = Offset(size.width, centerY),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            } else {
                var minValue = Float.MAX_VALUE
                var maxValue = -Float.MAX_VALUE
                for (index in 0 until count) {
                    val sample = pointAt(index)
                    if (sample < minValue) minValue = sample
                    if (sample > maxValue) maxValue = sample
                }
                val range = (maxValue - minValue).takeIf { it > 0.0001f } ?: 1f
                val inset = 3.dp.toPx()
                val usableHeight = (size.height - inset * 2f).coerceAtLeast(1f)
                val stepX = size.width / (count - 1).toFloat()
                val path = Path()
                var lastX = 0f
                var lastY = 0f
                for (index in 0 until count) {
                    val normalized = ((pointAt(index) - minValue) / range).coerceIn(0f, 1f)
                    val x = stepX * index
                    val y = size.height - inset - normalized * usableHeight
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    lastX = x
                    lastY = y
                }
                val stroke = Stroke(
                    width = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                val radius = 2.5f.dp.toPx()
                val center = Offset(lastX, lastY)
                onDrawBehind {
                    drawPath(path = path, color = color, style = stroke)
                    drawCircle(color = color, radius = radius, center = center)
                }
            }
        },
    )
}
