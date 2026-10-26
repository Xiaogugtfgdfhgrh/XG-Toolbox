package com.xg.toolbox.feature.system.ui.optimize

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.shell.DenyReason
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.feature.system.R
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

internal fun normalizePercent(value: Float): Float {
    val normalized = if (value > 1f) value / 100f else value
    return normalized.coerceIn(0f, 1f)
}

internal fun signedBytes(delta: Long): String {
    val prefix = if (delta > 0) "+" else ""
    return prefix + Formatters.formatSignedBytes(delta)
}

@Composable
fun SysCheckBox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true,
    a11yLabel: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(XgRadius.small)
    val interactive = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { value -> onCheckedChange?.invoke(value) },
        )
    } else {
        Modifier
    }
    val semanticsModifier = if (a11yLabel != null) {
        Modifier.semantics { contentDescription = a11yLabel.orEmpty() }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(XgSize.touch)
            .alpha(if (enabled) 1f else 0.38f)
            .then(interactive)
            .then(semanticsModifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(XgSize.iconSmall)
                .clip(shape)
                .background(if (checked) colors.accent else Color.Transparent)
                .border(1.dp, if (checked) colors.accent else colors.lineStrong, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = colors.onAccent,
                    modifier = Modifier.size(XgSize.iconSmall * 0.7f),
                )
            }
        }
    }
}

@Composable
fun SystemRootBadge(
    rootState: RootState,
    modifier: Modifier = Modifier,
) {
    if (rootState is RootState.Granted) return
    val unknownReason = stringResource(R.string.sys_core_root_reason_unknown)
    val reason = when (rootState) {
        is RootState.Unknown -> stringResource(R.string.sys_core_root_checking)
        is RootState.Denied -> stringResource(
            R.string.sys_core_root_denied,
            denyReasonText(rootState.reason),
        )

        is RootState.Unsupported -> stringResource(
            R.string.sys_core_root_unsupported,
            rootState.reason.ifBlank { unknownReason },
        )

        is RootState.Granted -> return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = XgSize.rowMin)
            .padding(vertical = XgSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        XgBadge(
            text = stringResource(R.string.sys_core_root_readonly_badge),
            tone = XgTone.Warn,
        )
        Spacer(Modifier.width(XgSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            XgNote(text = reason, tone = XgTone.Warn)
            Spacer(Modifier.size(2.dp))
            XgNote(text = stringResource(R.string.sys_core_root_actions_disabled))
        }
    }
}

@Composable
private fun denyReasonText(reason: DenyReason): String = when (reason) {
    DenyReason.NO_BINARY -> stringResource(R.string.sys_core_root_reason_no_binary)
    DenyReason.USER_REJECTED -> stringResource(R.string.sys_core_root_reason_user_rejected)
    DenyReason.TIMEOUT -> stringResource(R.string.sys_core_root_reason_timeout)
    DenyReason.POLICY_BLOCKED -> stringResource(R.string.sys_core_root_reason_policy)
    DenyReason.SELINUX_BLOCKED -> stringResource(R.string.sys_core_root_reason_selinux)
}

@Composable
internal fun LoadAfterTransition(onLoad: () -> Unit) {
    val motion = LocalXgMotionScale.current
    val currentLoad by rememberUpdatedState(onLoad)
    LaunchedEffect(motion) {
        if (motion > 0f) {
            delay((PAGE_TRANSITION_MS * motion).roundToInt().toLong())
        }
        currentLoad()
    }
}

private const val PAGE_TRANSITION_MS = 240f
