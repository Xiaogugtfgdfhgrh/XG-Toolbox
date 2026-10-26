package com.xg.toolbox.feature.apps.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgTween
import com.xg.toolbox.feature.apps.AbiLabel
import com.xg.toolbox.feature.apps.AppRowUi
import com.xg.toolbox.feature.apps.AppsViewModel
import com.xg.toolbox.feature.apps.R
import kotlinx.coroutines.delay

enum class AppRowPosition { SINGLE, FIRST, MIDDLE, LAST }

fun appRowPosition(index: Int, count: Int): AppRowPosition = when {
    count <= 1 -> AppRowPosition.SINGLE
    index == 0 -> AppRowPosition.FIRST
    index == count - 1 -> AppRowPosition.LAST
    else -> AppRowPosition.MIDDLE
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRowCard(
    row: AppRowUi,
    position: AppRowPosition,
    onClick: () -> Unit,
    viewModel: AppsViewModel,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    longPressLabel: String? = null,
    longPressActive: Boolean = false,
) {
    val colors = LocalXgColors.current
    val motion = LocalXgMotionScale.current
    var lifted by remember { mutableStateOf(false) }
    LaunchedEffect(longPressActive, motion) {
        if (longPressActive) {
            if (motion > 0f) delay(90)
            lifted = true
        } else {
            lifted = false
        }
    }
    val scale by animateFloatAsState(
        targetValue = when {
            !longPressActive -> 1f
            !lifted -> 0.96f
            else -> 0.985f
        },
        animationSpec = when {
            motion <= 0f -> snap()
            !lifted -> xgTween(90, motion, XgEaseOutCubic)
            else -> spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "appRowLongPressScale",
    )
    val lift by animateDpAsState(
        targetValue = if (longPressActive && lifted) (-6).dp else 0.dp,
        animationSpec = if (motion <= 0f) {
            snap()
        } else {
            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
        },
        label = "appRowLongPressLift",
    )
    val full = RoundedCornerShape(XgRadius.card)
    val top = RoundedCornerShape(topStart = XgRadius.card, topEnd = XgRadius.card)
    val bottom = RoundedCornerShape(bottomStart = XgRadius.card, bottomEnd = XgRadius.card)
    val shape = when (position) {
        AppRowPosition.SINGLE -> full
        AppRowPosition.FIRST -> top
        AppRowPosition.MIDDLE -> RoundedCornerShape(0.dp)
        AppRowPosition.LAST -> bottom
    }
    val clickModifier = if (onLongPress != null) {
        Modifier.combinedClickable(
            role = Role.Button,
            onLongClickLabel = longPressLabel,
            onLongClick = onLongPress,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = lift.toPx()
                    shadowElevation = if (longPressActive && lifted) 12.dp.toPx() else 0f
                    this.shape = shape
                    clip = false
                }
                .clip(shape)
                .background(colors.surface)
                .then(clickModifier)
                .heightIn(min = XgSize.rowMin + XgSpacing.sm)
                .padding(horizontal = XgSpacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                packageName = row.packageName,
                versionCode = row.versionCode,
                label = row.label,
                viewModel = viewModel,
            )
            Spacer(Modifier.width(XgSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    style = XgType.rowTitle,
                    color = if (row.frozen) colors.muted else colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.heightIn(min = 2.dp))
                Text(
                    text = row.packageName,
                    style = XgType.monoSmall,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(XgSpacing.sm))
            Column(horizontalAlignment = Alignment.End) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.frozen) {
                        XgBadge(text = stringResource(R.string.apps_badge_frozen), tone = XgTone.Warn)
                    }
                    AbiBadge(label = row.abiLabel)
                }
                Text(
                    text = row.sizeText,
                    style = XgType.monoSmall,
                    color = colors.faint,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = colors.faint,
                modifier = Modifier.size(18.dp),
            )
        }
        if (position != AppRowPosition.LAST && position != AppRowPosition.SINGLE) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = XgSpacing.md)
                    .heightIn(min = 1.dp)
                    .background(colors.line),
            )
        }
    }
}

@Composable
fun AbiBadge(label: AbiLabel) {
    val text = when (label) {
        AbiLabel.Bit64 -> stringResource(R.string.apps_abi_64)
        AbiLabel.Bit32 -> stringResource(R.string.apps_abi_32)
        AbiLabel.Unknown -> stringResource(R.string.apps_value_unknown)
        is AbiLabel.Raw -> label.value
    }
    XgBadge(text = text, tone = XgTone.Muted)
}

