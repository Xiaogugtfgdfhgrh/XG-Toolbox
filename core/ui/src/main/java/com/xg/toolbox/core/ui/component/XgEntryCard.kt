package com.xg.toolbox.core.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.liquid.xgBackdropSurfaceColor
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgTween

@Composable
fun XgEntryCard(
    ordinal: String?,
    title: String,
    subtitle: String,
    meta: String? = null,
    badge: String? = null,
    badgeTone: XgTone = XgTone.Muted,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    val shapes = rememberXgShapes()
    val motion = LocalXgMotionScale.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = xgTween(120, motion, XgEaseOutCubic),
        label = "xgEntryCardScale",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shapes.card)
            .background(xgBackdropSurfaceColor(colors.surface, alphaWhenVisible = 0.68f))
            .border(1.dp, colors.line, shapes.card)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                horizontal = XgSpacing.panelHorizontal,
                vertical = XgSpacing.md,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ordinal != null) {
            Text(
                text = ordinal,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                modifier = Modifier.width(24.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = XgType.cardTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!meta.isNullOrBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = meta,
                        style = XgType.monoSmall,
                        color = colors.faint,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                style = XgType.note,
                color = colors.faint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!badge.isNullOrBlank()) {
            Spacer(Modifier.width(XgSpacing.sm))
            XgBadge(text = badge, tone = badgeTone)
        }
        Spacer(Modifier.width(XgSpacing.xs))
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = colors.faint,
            modifier = Modifier.size(XgSize.iconSmall),
        )
    }
}
