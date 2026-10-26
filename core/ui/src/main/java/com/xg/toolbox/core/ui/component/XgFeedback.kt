package com.xg.toolbox.core.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xg.toolbox.core.common.ErrorCatalog
import com.xg.toolbox.core.ui.liquid.xgBackdropSurfaceColor
import com.xg.toolbox.core.ui.liquid.xgLiquidGlassSurface
import com.xg.toolbox.core.ui.R
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.LocalXgReduceMotion
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType

@Composable
fun XgProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    val motion = LocalXgMotionScale.current
    val track = RoundedCornerShape(2.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(track)
            .background(colors.line),
    ) {
        if (progress == null) {
            if (motion <= 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.32f)
                        .height(4.dp)
                        .background(colors.accent),
                )
            } else {
                val transition = rememberInfiniteTransition(label = "xgProgressIndeterminate")
                val fraction by transition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "xgProgressFraction",
                )
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val segment = size.width * 0.32f
                    val x = (size.width + segment) * fraction - segment
                    drawRoundRect(
                        color = colors.accent,
                        topLeft = Offset(x, 0f),
                        size = Size(segment, size.height),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .background(colors.accent),
            )
        }
    }
}

@Composable
fun XgTaskProgress(
    title: String,
    progress: Float?,
    meta: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(11.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(xgBackdropSurfaceColor(colors.surface, alphaWhenVisible = 0.68f))
            .drawBehind {
                drawRoundRect(
                    color = colors.line,
                    topLeft = Offset(0.5f, 0.5f),
                    size = Size(size.width - 1f, size.height - 1f),
                    cornerRadius = CornerRadius(11.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .padding(XgSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = XgType.rowTitle,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(XgSpacing.sm))
            val trailing = meta ?: progress?.let { "${(it.coerceIn(0f, 1f) * 100).toInt()}%" }
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = XgType.pageMeta,
                    color = colors.faint,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        XgProgressBar(progress = progress)
    }
}

private data class ConfirmDialogSpec(
    val title: String,
    val body: String,
    val confirmText: String,
    val dismissText: String,
    val danger: Boolean,
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
fun XgConfirmDialog(
    title: String,
    body: String,
    confirmText: String = "确认",
    dismissText: String = "取消",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val spec by rememberUpdatedState(
        ConfirmDialogSpec(title, body, confirmText, dismissText, danger, onConfirm, onDismiss),
    )
    val host = LocalXgWindowOverlayHost.current
    if (host != null) {
        DisposableEffect(host) {
            val request = host.show { backdrop ->
                val current = spec
                XgGlassDialog(
                    backdrop = backdrop,
                    onDismissRequest = current.onDismiss,
                    title = current.title,
                    shape = RoundedCornerShape(18.dp),
                    maxWidth = 330.dp,
                ) {
                    ConfirmDialogBody(current)
                }
            }
            onDispose { host.dismiss(request) }
        }
    } else {
        Dialog(
            onDismissRequest = spec.onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 330.dp)
                        .fillMaxWidth()
                        .xgLiquidGlassSurface(
                            shape = RoundedCornerShape(18.dp),
                            surfaceColor = LocalXgColors.current.surface,
                            opaqueSurfaceColor = LocalXgColors.current.surface,
                            accentColor = LocalXgColors.current.accent,
                            borderColor = LocalXgColors.current.line,
                            enabled = true,
                            elevation = 14.dp,
                        )
                        .padding(20.dp),
                ) {
                    ConfirmDialogBody(spec)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ConfirmDialogBody(spec: ConfirmDialogSpec) {
    val colors = LocalXgColors.current
    Text(
        text = spec.title,
        style = XgType.dialogTitle,
        color = colors.ink,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(7.dp))
    Text(
        text = spec.body,
        style = XgType.body,
        color = colors.muted,
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        XgGhostButton(
            text = spec.dismissText,
            onClick = spec.onDismiss,
            modifier = Modifier.weight(1f),
        )
        if (spec.danger) {
            XgDangerFilledButton(
                text = spec.confirmText,
                onClick = spec.onConfirm,
                modifier = Modifier.weight(1f),
            )
        } else {
            XgPrimaryButton(
                text = spec.confirmText,
                onClick = spec.onConfirm,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun XgEmptyState(
    title: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalXgColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface.copy(alpha = 0.55f))
            .drawBehind {
                val strokeWidth = 1.dp.toPx()
                drawRoundRect(
                    color = colors.lineStrong,
                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(
                        width = strokeWidth,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                            0f,
                        ),
                    ),
                )
            }
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = XgType.body.copy(fontWeight = FontWeight.SemiBold),
            color = colors.muted,
            textAlign = TextAlign.Center,
        )
        if (actionText != null && onAction != null) {
            XgGhostButton(text = actionText, onClick = onAction)
        }
    }
}

@Composable
fun XgLoadingSkeleton(lines: Int = 3, label: String? = null) {
    val colors = LocalXgColors.current
    val motion = LocalXgMotionScale.current
    val reduceMotion = LocalXgReduceMotion.current
    val shimmerEnabled = motion > 0f && !reduceMotion
    val count = lines.coerceAtLeast(1)
    val shimmer: State<Float> = if (!shimmerEnabled) {
        remember { mutableStateOf(0.38f) }
    } else {
        val transition = rememberInfiniteTransition(label = "xgSkeleton")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1250, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "xgSkeletonShimmer",
        )
    }
    val text = label ?: stringResource(R.string.xg_loading)
    val highlight = if (colors.isDark) {
        lerp(colors.surface2, colors.ink, 0.10f)
    } else {
        lerp(colors.surface2, Color.White, 0.85f)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = text },
        verticalArrangement = Arrangement.spacedBy(XgSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = colors.accent,
                strokeWidth = 2.dp,
            )
            Text(
                text = text,
                style = XgType.note,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        repeat(count) { index ->
            val fraction = when {
                index == count - 1 -> 0.62f
                index == 0 -> 0.94f
                else -> 1f
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .drawBehind {
                        val band = size.width * 0.44f
                        val centerX = -band + (size.width + band * 2f) * shimmer.value
                        drawRoundRect(
                            brush = Brush.linearGradient(
                                colors = listOf(colors.surface2, highlight, colors.surface2),
                                start = Offset(centerX - band, 0f),
                                end = Offset(centerX + band, size.height),
                            ),
                            size = size,
                            cornerRadius = CornerRadius(7.dp.toPx()),
                        )
                    },
            )
        }
    }
}

@Composable
fun XgErrorState(
    code: Int?,
    message: String,
    onRetry: (() -> Unit)? = null,
) {
    val colors = LocalXgColors.current
    XgPanel {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = colors.danger,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = code?.let { ErrorCatalog.title(it) } ?: stringResource(R.string.xg_error_title),
                style = XgType.body.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                textAlign = TextAlign.Center,
            )
            if (code != null) {
                Text(
                    text = stringResource(R.string.xg_error_code, code),
                    style = XgType.pageMeta,
                    color = colors.faint,
                )
            }
            Text(
                text = message,
                style = XgType.note,
                color = colors.muted,
                textAlign = TextAlign.Center,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(2.dp))
                XgGhostButton(text = stringResource(R.string.xg_retry), onClick = onRetry)
            }
        }
    }
}
