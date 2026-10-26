package com.xg.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.R
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgTween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun XgKeyValueRow(
    label: String,
    value: String?,
    propertyKey: String? = null,
    badge: String? = null,
    badgeTone: XgTone = XgTone.Muted,
    copyable: Boolean = true,
    onCopy: (() -> Boolean)? = null,
    onBadgeClick: (() -> Unit)? = null,
    index: String? = null,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = XgSize.rowMin).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (index != null) {
            Text(
                text = index,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                modifier = Modifier.width(20.dp).padding(top = 2.dp),
            )
        }
        Text(
            text = label,
            style = XgType.fieldLabel,
            color = colors.muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(112.dp).padding(top = 1.dp),
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = XgSpacing.sm),
            horizontalAlignment = Alignment.End,
        ) {
            if (!propertyKey.isNullOrBlank()) {
                Text(
                    text = propertyKey,
                    style = XgType.monoSmall,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value ?: "—",
                    style = XgType.kvValue,
                    color = if (value == null) colors.faint else colors.ink,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .heightIn(min = 28.dp)
                            .then(
                                if (onBadgeClick != null) {
                                    Modifier.clickable(role = Role.Button, onClick = onBadgeClick)
                                } else {
                                    Modifier
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        XgBadge(text = badge, tone = badgeTone)
                    }
                }
                if (copyable) {
                    Spacer(Modifier.width(2.dp))
                    XgCopyButton(
                        text = value,
                        contentDescription = stringResource(R.string.xg_copy_content_description, label),
                        onCopy = onCopy,
                    )
                }
            }
        }
    }
}

@Composable
fun XgToggleRow(
    name: String,
    note: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    count: String? = null,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = XgSize.rowMin).padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = XgType.rowTitle,
                color = if (enabled) colors.ink else colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                style = XgType.rowNote,
                color = colors.faint,
            )
        }
        if (!count.isNullOrBlank()) {
            Spacer(Modifier.width(XgSpacing.sm))
            Text(
                text = count,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(XgSpacing.xs))
        XgSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = name },
        )
    }
}

@Composable
fun XgRiskRow(
    level: XgTone,
    title: String,
    note: String,
    actionText: String,
    onAction: () -> Unit,
) {
    val colors = LocalXgColors.current
    val markTone = when (level) {
        XgTone.Danger -> XgTone.Danger
        XgTone.Warn -> XgTone.Warn
        else -> XgTone.Accent
    }
    val markShape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = XgSize.rowMin).padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(XgSize.riskMark)
                .clip(markShape)
                .background(xgToneSoftColor(markTone))
                .border(1.dp, xgToneBorderColor(markTone), markShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (markTone == XgTone.Accent) Icons.Outlined.Info else Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = xgToneColor(markTone),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = XgType.rowTitle,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = note,
                style = XgType.rowNote,
                color = colors.faint,
            )
        }
        Spacer(Modifier.width(XgSpacing.sm))
        XgTextAction(text = actionText, onClick = onAction)
    }
}

@Composable
fun XgRunRow(
    title: String,
    meta: String,
    result: String,
    resultTone: XgTone = XgTone.Ok,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalXgColors.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalXgMotionScale.current
    val scale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.99f else 1f,
        animationSpec = xgTween(120, motion, XgEaseOutCubic),
        label = "xgRunRowScale",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = XgSize.rowMin)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = androidx.compose.foundation.LocalIndication.current,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            )
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = XgType.rowTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = meta,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(XgSpacing.sm))
        XgBadge(text = result, tone = resultTone)
    }
}

@Composable
fun XgDividerRow(
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    if (label.isNullOrBlank()) {
        XgHairline()
        return
    }
    val colors = LocalXgColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = XgSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        XgLabel(label)
        Spacer(Modifier.width(XgSpacing.sm))
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = 1.dp)
                .height(1.dp)
                .background(colors.line),
        )
    }
}
