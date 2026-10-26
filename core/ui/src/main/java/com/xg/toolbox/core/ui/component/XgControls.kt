package com.xg.toolbox.core.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgEase
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgTween

@Composable
internal fun XgButtonFrame(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(XgRadius.button),
    containerColor: Color,
    pressedContainerColor: Color? = null,
    contentColor: Color,
    borderColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = XgSpacing.lg),
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalXgColors.current
    val motion = LocalXgMotionScale.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = xgTween(120, motion, XgEaseOutCubic),
        label = "xgButtonScale",
    )
    val background by animateColorAsState(
        targetValue = if (pressed && enabled && pressedContainerColor != null) {
            pressedContainerColor
        } else {
            containerColor
        },
        animationSpec = xgTween(120, motion, XgEase),
        label = "xgButtonBackground",
    )
    val alpha = if (enabled) 1f else 0.42f
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier = modifier
                .heightIn(min = XgSize.touch)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .clip(shape)
                .background(background)
                .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
                .then(if (focused) Modifier.border(2.dp, colors.accent, shape) else Modifier)
                .clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(contentPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
fun XgPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    XgButtonFrame(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        containerColor = colors.accent,
        pressedContainerColor = colors.accentDeep,
        contentColor = colors.onAccent,
    ) {
        Text(
            text = text,
            style = XgType.chip,
            color = colors.onAccent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun XgGhostButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    XgButtonFrame(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        containerColor = Color.Transparent,
        pressedContainerColor = colors.surface2,
        contentColor = colors.ink,
        borderColor = colors.lineStrong,
    ) {
        Text(
            text = text,
            style = XgType.chip,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun XgDangerButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    XgButtonFrame(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        containerColor = Color.Transparent,
        pressedContainerColor = colors.dangerSoft,
        contentColor = colors.danger,
        borderColor = colors.danger.copy(alpha = 0.38f),
    ) {
        Text(
            text = text,
            style = XgType.chip,
            color = colors.danger,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun XgTextAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tone: XgTone = XgTone.Accent,
) {
    val colors = LocalXgColors.current
    val foreground = xgToneColor(tone)
    val border = when (tone) {
        XgTone.Danger -> colors.danger.copy(alpha = 0.30f)
        else -> colors.line
    }
    XgButtonFrame(
        onClick = onClick,
        enabled = enabled,
        containerColor = colors.surface,
        pressedContainerColor = colors.surface2,
        contentColor = foreground,
        borderColor = border,
        contentPadding = PaddingValues(horizontal = 10.dp),
    ) {
        Text(
            text = text,
            style = XgType.chip,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun XgDangerFilledButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    XgButtonFrame(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        containerColor = colors.danger,
        pressedContainerColor = colors.danger.copy(alpha = 0.86f),
        contentColor = colors.onDanger,
    ) {
        Text(
            text = text,
            style = XgType.chip,
            color = colors.onDanger,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun XgIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalXgColors.current
    val motion = LocalXgMotionScale.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = xgTween(120, motion, XgEaseOutCubic),
        label = "xgIconButtonScale",
    )
    val shape = remember { RoundedCornerShape(9.dp) }
    Box(
        modifier = Modifier
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
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.muted,
            modifier = Modifier.size(XgSize.iconSmall),
        )
    }
}

@Composable
fun XgSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val colors = LocalXgColors.current
    val motion = LocalXgMotionScale.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.lineStrong,
        animationSpec = xgTween(180, motion, XgEaseOutCubic),
        label = "xgSwitchTrack",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) XgSize.switchW - XgSize.switchKnob - 2.dp else 2.dp,
        animationSpec = xgTween(180, motion, XgEaseOutCubic),
        label = "xgSwitchKnob",
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = xgTween(120, motion, XgEaseOutCubic),
        label = "xgSwitchScale",
    )
    Box(
        modifier = modifier
            .size(XgSize.touch)
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .semantics {
                if (!contentDescription.isNullOrBlank()) {
                    this.contentDescription = contentDescription
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = XgSize.switchW, height = XgSize.switchH)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    alpha = if (enabled) 1f else 0.38f
                }
                .clip(CircleShape)
                .background(trackColor)
                .then(if (focused) Modifier.border(2.dp, colors.accent, CircleShape) else Modifier),
        ) {
            Box(
                modifier = Modifier
                    .offset(x = knobOffset, y = 2.dp)
                    .size(XgSize.switchKnob)
                    .shadow(elevation = 1.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }
}

@Composable
fun XgChoiceRow(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalXgColors.current
    val motion = LocalXgMotionScale.current
    val optionShape = remember { RoundedCornerShape(9.dp) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = XgSize.touch),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (pressed && enabled) 0.97f else 1f,
                animationSpec = xgTween(120, motion, XgEaseOutCubic),
                label = "xgChoiceScale",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = XgSize.touch)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .semantics { this.selected = selected },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
                        .clip(optionShape)
                        .background(if (selected) colors.accentSoft else Color.Transparent)
                        .border(1.dp, if (selected) colors.accent else colors.lineStrong, optionShape)
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option,
                        style = XgType.choice,
                        color = if (selected) colors.accent else colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
