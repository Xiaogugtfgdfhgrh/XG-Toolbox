package com.xg.toolbox.core.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xg.toolbox.core.ui.R
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgMotionDuration

data class XgContextMenuAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

@Composable
fun XgLongPressContextMenu(
    visible: Boolean,
    title: String,
    subtitle: String?,
    actions: List<XgContextMenuAction>,
    onDismissRequest: () -> Unit,
    onDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = LocalXgMotionScale.current
    val visibility = remember { MutableTransitionState(false) }
    var opened by remember { mutableStateOf(false) }
    val scrimProgress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = contextTween<Float>(180, motion),
        label = "xgContextScrim",
    )

    LaunchedEffect(visible) {
        if (visible) {
            opened = true
            visibility.targetState = true
        } else if (opened) {
            visibility.targetState = false
        }
    }
    LaunchedEffect(visibility.isIdle, visibility.currentState, visible, opened) {
        if (opened && visibility.isIdle && !visibility.currentState && !visible) {
            onDismissed()
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.50f * scrimProgress))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismissRequest,
                    )
                    .clearAndSetSemantics { },
            )
            AnimatedVisibility(
                visibleState = visibility,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(animationSpec = contextTween<Float>(120, motion)) +
                    slideInVertically(
                        animationSpec = if (motion <= 0f) {
                            snap()
                        } else {
                            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
                        },
                    ) { height -> height },
                exit = fadeOut(animationSpec = contextTween<Float>(150, motion)) +
                    slideOutVertically(
                        animationSpec = contextTween<IntOffset>(190, motion),
                    ) { height -> height },
            ) {
                ContextMenuPanel(
                    title = title,
                    subtitle = subtitle,
                    actions = actions,
                    onDismissRequest = onDismissRequest,
                )
            }
        }
    }
}

@Composable
private fun ContextMenuPanel(
    title: String,
    subtitle: String?,
    actions: List<XgContextMenuAction>,
    onDismissRequest: () -> Unit,
) {
    val colors = LocalXgColors.current
    val shape = remember { RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp) }
    val focusRequester = remember { FocusRequester() }
    val panelInteraction = remember { MutableInteractionSource() }
    val menuPaneTitle = stringResource(R.string.xg_context_menu_title, title)

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = 18.dp, shape = shape)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.line, shape)
            .clickable(
                interactionSource = panelInteraction,
                indication = null,
                onClick = {},
            )
            .navigationBarsPadding()
            .padding(horizontal = XgSpacing.xl, vertical = XgSpacing.lg)
            .focusRequester(focusRequester)
            .focusGroup()
            .focusable()
            .semantics {
                paneTitle = menuPaneTitle
                isTraversalGroup = true
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.lineStrong),
        )
        Spacer(Modifier.height(XgSpacing.md))
        Text(
            text = title,
            style = XgType.rowTitle,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!subtitle.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(XgSpacing.sm))
        actions.forEachIndexed { index, action ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.line),
                )
            }
            val foreground = if (action.destructive) colors.danger else colors.ink
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(XgRadius.button))
                    .clickable(role = Role.Button) {
                        onDismissRequest()
                        action.onClick()
                    }
                    .padding(horizontal = XgSpacing.sm, vertical = XgSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm),
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = foreground,
                    modifier = Modifier.size(XgSize.iconSmall),
                )
                Text(
                    text = action.label,
                    style = XgType.rowTitle,
                    color = foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
private fun <T> contextTween(baseMs: Int, scale: Float): FiniteAnimationSpec<T> =
    if (scale <= 0f) snap() else tween(durationMillis = xgMotionDuration(baseMs, scale), easing = XgEaseOutCubic)
