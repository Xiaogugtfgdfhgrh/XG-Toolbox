package com.xg.toolbox.feature.device

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xg.toolbox.core.device.FieldRestriction
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgDangerButton
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgIconButton
import com.xg.toolbox.core.ui.component.XgKeyValueRow
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType

@Composable
internal fun restrictionBadgeText(field: DeviceFieldUi): String? =
    if (field.restriction == FieldRestriction.MASKED && field.isUnmaskedIdentityField) {
        null
    } else {
        restrictionBadgeText(field.restriction)
    }

@Composable
internal fun restrictionBadgeText(restriction: FieldRestriction): String? = when (restriction) {
    FieldRestriction.NONE -> null
    FieldRestriction.UNAVAILABLE -> stringResource(R.string.device_badge_unavailable)
    FieldRestriction.MASKED -> stringResource(R.string.device_badge_masked)
    FieldRestriction.NEEDS_ROOT -> stringResource(R.string.device_badge_needs_root)
    FieldRestriction.LIMITED -> stringResource(R.string.device_badge_limited)
}

internal fun restrictionBadgeTone(restriction: FieldRestriction): XgTone = when (restriction) {
    FieldRestriction.NONE -> XgTone.Muted
    FieldRestriction.UNAVAILABLE, FieldRestriction.MASKED -> XgTone.Muted
    FieldRestriction.NEEDS_ROOT, FieldRestriction.LIMITED -> XgTone.Warn
}

@Composable
internal fun DeviceSummaryCard(summary: DeviceSummaryUi?) {
    val colors = LocalXgColors.current
    XgPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(XgSize.avatar)
                    .clip(RoundedCornerShape(XgRadius.small))
                    .background(colors.inset)
                    .border(1.dp, colors.lineStrong, RoundedCornerShape(XgRadius.small)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Smartphone,
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(XgSize.iconSmall),
                )
            }
            Spacer(Modifier.width(XgSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.device_summary_unknown),
                    style = XgType.cardTitle,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!summary?.meta.isNullOrBlank()) {
                    Spacer(Modifier.size(XgSpacing.xs))
                    XgMono(
                        text = summary?.meta.orEmpty(),
                        style = XgType.monoSmall,
                        color = colors.faint,
                    )
                }
            }
            Spacer(Modifier.width(XgSpacing.sm))
            when {
                summary?.unlocked == true -> XgBadge(
                    text = stringResource(R.string.device_summary_unlocked),
                    tone = XgTone.Warn,
                )
                summary?.rooted == true -> XgBadge(
                    text = stringResource(R.string.device_summary_rooted),
                    tone = XgTone.Accent,
                )
                else -> Unit
            }
        }
    }
}

@Composable
internal fun DeviceFieldsPanel(
    group: DeviceGroupUi,
    onCopyField: (DeviceFieldUi) -> Boolean,
    onShowFieldNote: (DeviceFieldUi) -> Unit,
    onEditSerial: (() -> Unit)? = null,
) {
    XgPanel {
        group.fields.forEachIndexed { index, field ->
            if (index > 0) {
                XgHairline()
            }
            if (field.isSerialNumberField && onEditSerial != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        DeviceFieldRow(
                            field = field,
                            highlighted = field.id in group.matchedFieldIds,
                            onCopy = { onCopyField(field) },
                            onShowNote = { onShowFieldNote(field) },
                        )
                    }
                    XgIconButton(
                        icon = Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.device_serial_edit),
                        onClick = onEditSerial,
                    )
                }
            } else {
                DeviceFieldRow(
                    field = field,
                    highlighted = field.id in group.matchedFieldIds,
                    onCopy = { onCopyField(field) },
                    onShowNote = { onShowFieldNote(field) },
                )
            }
        }
    }
}

@Composable
private fun DeviceFieldRow(
    field: DeviceFieldUi,
    highlighted: Boolean,
    onCopy: () -> Boolean,
    onShowNote: () -> Unit,
) {
    val colors = LocalXgColors.current
    val badgeText = restrictionBadgeText(field)
    val badgeTone = restrictionBadgeTone(field.restriction)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (highlighted) {
                    Modifier.background(
                        colors.accentSoft,
                        RoundedCornerShape(XgRadius.small),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        XgKeyValueRow(
            label = field.name,
            value = field.value,
            propertyKey = field.propertyKey.takeIf { it.isNotBlank() },
            badge = badgeText,
            badgeTone = badgeTone,
            copyable = true,
            onCopy = onCopy,
            onBadgeClick = if (badgeText != null) onShowNote else null,
            index = field.ordinal.toString().padStart(2, '0'),
        )
    }
}

@Composable
internal fun SerialEditDialog(
    ui: SerialEditUi,
    onInputChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!ui.visible) return
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(18.dp)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.86f).dp
    Dialog(
        onDismissRequest = { if (!ui.submitting) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .heightIn(max = maxHeight)
                    .clip(shape)
                    .background(colors.surface)
                    .border(1.dp, colors.line, shape)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.device_serial_edit_title),
                    style = XgType.dialogTitle,
                    color = colors.ink,
                )
                Spacer(Modifier.height(XgSpacing.sm))
                Text(
                    text = stringResource(R.string.device_serial_edit_current),
                    style = XgType.fieldLabel,
                    color = colors.muted,
                )
                Spacer(Modifier.height(XgSpacing.xs))
                XgMono(
                    text = ui.currentSerial?.takeIf { it.isNotBlank() } ?: "—",
                    style = XgType.monoValue,
                    color = colors.ink,
                )
                Spacer(Modifier.height(XgSpacing.sm))
                XgNote(
                    text = capabilityText(ui),
                    tone = when {
                        ui.loading || ui.capabilityError != null -> XgTone.Muted
                        ui.supported -> XgTone.Ok
                        else -> XgTone.Warn
                    },
                )
                Spacer(Modifier.height(XgSpacing.sm))
                XgHairline()
                Spacer(Modifier.height(XgSpacing.sm))
                Text(
                    text = stringResource(R.string.device_serial_edit_new_value),
                    style = XgType.fieldLabel,
                    color = colors.muted,
                )
                Spacer(Modifier.height(XgSpacing.xs))
                SerialInputField(
                    value = ui.input,
                    onValueChange = onInputChange,
                    enabled = !ui.submitting && !ui.loading,
                    isError = ui.inputError != null,
                    placeholder = stringResource(R.string.device_serial_edit_placeholder),
                )
                if (ui.inputError != null) {
                    Spacer(Modifier.height(XgSpacing.xs))
                    XgNote(text = ui.inputError, tone = XgTone.Danger)
                }
                Spacer(Modifier.height(XgSpacing.sm))
                XgHairline()
                Spacer(Modifier.height(XgSpacing.sm))
                Text(
                    text = stringResource(R.string.device_serial_edit_risk_title),
                    style = XgType.fieldLabel,
                    color = colors.muted,
                )
                Spacer(Modifier.height(XgSpacing.xs))
                XgNote(
                    text = stringResource(R.string.device_serial_edit_risk_root),
                    tone = XgTone.Warn,
                )
                XgNote(
                    text = stringResource(R.string.device_serial_edit_risk_reboot),
                    tone = XgTone.Warn,
                )
                XgNote(
                    text = stringResource(R.string.device_serial_edit_risk_account),
                    tone = XgTone.Warn,
                )
                if (ui.submitError != null) {
                    Spacer(Modifier.height(XgSpacing.sm))
                    XgNote(
                        text = stringResource(R.string.device_serial_edit_failed, ui.submitError),
                        tone = XgTone.Danger,
                    )
                }
                Spacer(Modifier.height(XgSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    XgGhostButton(
                        text = stringResource(R.string.device_serial_edit_cancel),
                        onClick = onDismiss,
                        enabled = !ui.submitting,
                    )
                    Spacer(Modifier.width(XgSpacing.sm))
                    XgDangerButton(
                        text = if (ui.submitting) {
                            stringResource(R.string.device_serial_edit_submitting)
                        } else {
                            stringResource(R.string.device_serial_edit_confirm)
                        },
                        onClick = onConfirm,
                        enabled = !ui.submitting && !ui.loading && ui.supported && ui.input.isNotBlank(),
                    )
                }
            }
        }
    }
}

@Composable
private fun capabilityText(ui: SerialEditUi): String = when {
    ui.loading -> stringResource(R.string.device_serial_edit_capability_checking)
    ui.capabilityError != null -> stringResource(
        R.string.device_serial_edit_capability_failed,
        ui.capabilityError,
    )
    ui.supported -> ui.resetpropPath
        ?.takeIf { it.isNotBlank() }
        ?.let { path -> stringResource(R.string.device_serial_edit_capability_supported_with_path, path) }
        ?: stringResource(R.string.device_serial_edit_capability_supported)
    else -> stringResource(
        R.string.device_serial_edit_capability_unsupported,
        ui.capabilityReason?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.device_serial_edit_capability_unsupported_generic),
    )
}

@Composable
private fun SerialInputField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isError: Boolean,
    placeholder: String,
) {
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(XgRadius.small)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = XgType.monoValue.copy(color = colors.ink),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.inset)
            .border(1.dp, if (isError) colors.danger else colors.lineStrong, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = XgType.monoValue,
                        color = colors.faint,
                    )
                }
                innerTextField()
            }
        },
    )
}
