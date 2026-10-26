package com.xg.toolbox.feature.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.common.ErrorCatalog
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.apps.ActionFailure
import com.xg.toolbox.feature.apps.R

@Composable
fun AppsHexField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(XgRadius.small)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = XgType.monoValue.copy(color = if (enabled) colors.ink else colors.muted),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = XgSize.touch)
            .clip(shape)
            .background(colors.inset)
            .border(1.dp, colors.lineStrong, shape)
            .padding(horizontal = XgSpacing.md, vertical = XgSpacing.sm),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = XgType.monoValue,
                        color = colors.faint,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
fun AppsErrorNote(
    failure: ActionFailure,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(XgRadius.small)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.dangerSoft)
            .border(1.dp, colors.danger.copy(alpha = 0.30f), shape)
            .padding(XgSpacing.sm),
        verticalArrangement = Arrangement.spacedBy(XgSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XgBadge(text = ErrorCatalog.title(failure.code), tone = XgTone.Danger)
            Text(
                text = stringResource(R.string.apps_error_code, failure.code),
                style = XgType.monoSmall,
                color = colors.faint,
                modifier = Modifier.padding(start = XgSpacing.sm),
            )
        }
        Text(text = failure.message, style = XgType.body, color = colors.danger)
        val detail = failure.detail
        if (detail != null && detail.isNotBlank()) {
            XgMono(text = detail, style = XgType.monoSmall, color = colors.muted)
        }
        XgTextAction(
            text = stringResource(R.string.apps_error_dismiss),
            onClick = onDismiss,
            tone = XgTone.Muted,
        )
    }
}

@Composable
fun BatchItemBadge(status: String) {
    when (status) {
        STATUS_OK -> XgBadge(text = stringResource(R.string.apps_batch_status_ok), tone = XgTone.Ok)
        STATUS_FAILED -> XgBadge(text = stringResource(R.string.apps_batch_status_failed), tone = XgTone.Danger)
        else -> XgBadge(text = stringResource(R.string.apps_batch_status_skipped), tone = XgTone.Muted)
    }
}

@Composable
fun AppsWriteBlockedNote(reason: String?) {
    if (reason == null) return
    val text = if (reason == "readonly") {
        stringResource(R.string.apps_readonly_note)
    } else {
        stringResource(R.string.apps_need_root_note)
    }
    XgNote(text = text, tone = XgTone.Warn)
}

@Composable
fun AppsToolChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(XgRadius.chip)
    Box(
        modifier = Modifier
            .heightIn(min = XgSize.touch)
            .clip(shape)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(1.dp, if (selected) colors.accent else colors.lineStrong, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = XgSpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = XgType.chip,
            color = if (selected) colors.accent else colors.muted,
            maxLines = 1,
        )
    }
}

private const val STATUS_OK = "ok"
private const val STATUS_FAILED = "failed"

