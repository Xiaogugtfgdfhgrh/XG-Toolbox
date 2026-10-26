package com.xg.toolbox.core.ui.component

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.xg.toolbox.core.ui.R
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.LocalXgReduceMotion
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgTween
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun XgCopyButton(
    text: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
    successText: String? = null,
    onCopy: (() -> Boolean)? = null,
) {
    val colors = LocalXgColors.current
    val motion = LocalXgMotionScale.current
    val reduceMotion = LocalXgReduceMotion.current
    val animationsEnabled = motion > 0f && !reduceMotion
    val context = LocalContext.current
    val currentText by rememberUpdatedState(text)
    val currentOnCopy by rememberUpdatedState(onCopy)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val shape = remember { RoundedCornerShape(9.dp) }
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.93f else 1f,
        animationSpec = xgTween(110, if (reduceMotion) 0f else motion, XgEaseOutCubic),
        label = "xgCopyButtonScale",
    )
    val copyLabel = contentDescription ?: stringResource(R.string.xg_action_copy)
    val copiedLabel = successText ?: stringResource(R.string.xg_copied)
    val readyState = stringResource(R.string.xg_copy_state_ready)
    val copiedState = stringResource(R.string.xg_copy_state_copied)
    val unavailableState = stringResource(R.string.xg_copy_state_unavailable)
    val canCopy = enabled && text != null
    var copied by remember(text) { mutableStateOf(false) }
    var feedbackRequest by remember(text) { mutableIntStateOf(0) }

    LaunchedEffect(feedbackRequest) {
        if (feedbackRequest == 0) return@LaunchedEffect
        copied = true
        delay(1000)
        copied = false
    }

    val performCopy: () -> Boolean = copyAction@{
        val value = currentText ?: return@copyAction false
        val callback = currentOnCopy
        if (callback != null) {
            return@copyAction runCatching { callback.invoke() }.getOrDefault(false)
        }
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return@copyAction false
        runCatching {
            manager.setPrimaryClip(ClipData.newPlainText("xg-copy", value))
            manager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString() == value
        }.getOrDefault(false)
    }

    Box(
        modifier = modifier
            .size(XgSize.touch)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.38f
            }
            .clip(shape)
            .then(if (focused) Modifier.border(2.dp, colors.accent, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = canCopy,
                onClick = {
                    if (performCopy()) {
                        feedbackRequest += 1
                    } else {
                        copied = false
                    }
                },
            )
            .semantics {
                this.contentDescription = copyLabel
                stateDescription = when {
                    copied -> copiedState
                    canCopy -> readyState
                    else -> unavailableState
                }
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = copied,
            transitionSpec = {
                if (animationsEnabled) {
                    (fadeIn(tween(140, easing = XgEaseOutCubic)) +
                        scaleIn(initialScale = 0.82f, animationSpec = tween(140, easing = XgEaseOutCubic))) togetherWith
                        (fadeOut(tween(90)) +
                            scaleOut(targetScale = 0.82f, animationSpec = tween(90)))
                } else {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                }
            },
            contentAlignment = Alignment.Center,
            label = "xgCopyButtonIcon",
        ) { success ->
            val flip = remember { Animatable(if (animationsEnabled) 90f else 0f) }
            LaunchedEffect(Unit) {
                if (animationsEnabled) {
                    flip.animateTo(0f, tween(180, easing = XgEaseOutCubic))
                } else {
                    flip.snapTo(0f)
                }
            }
            Icon(
                imageVector = if (success) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                contentDescription = null,
                tint = if (success) colors.ok else colors.muted,
                modifier = Modifier
                    .size(XgSize.iconSmall)
                    .graphicsLayer { rotationY = flip.value },
            )
        }
        if (copied) {
            XgCopyTooltip(text = copiedLabel)
        }
    }
}

@Composable
private fun XgCopyTooltip(text: String) {
    val colors = LocalXgColors.current
    val density = LocalDensity.current
    val gapPx = with(density) { 6.dp.roundToPx() }
    val shape = remember { RoundedCornerShape(7.dp) }
    val positionProvider = remember(gapPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val maxX = max(0, windowSize.width - popupContentSize.width)
                val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                    .coerceIn(0, maxX)
                val above = anchorBounds.top - popupContentSize.height - gapPx
                val y = if (above >= 0) {
                    above
                } else {
                    anchorBounds.bottom + gapPx
                }.coerceIn(0, max(0, windowSize.height - popupContentSize.height))
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(colors.ink)
                .border(1.dp, colors.lineStrong, shape)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                text = text,
                style = XgType.note.copy(fontWeight = FontWeight.SemiBold),
                color = colors.bg,
                maxLines = 1,
            )
        }
    }
}
