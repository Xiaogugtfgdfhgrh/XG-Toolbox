package com.xg.toolbox.feature.system.ui.flash

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgConfirmDialog
import com.xg.toolbox.core.ui.component.XgDangerButton
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgKeyValueRow
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgStatusChip
import com.xg.toolbox.core.ui.component.XgTaskProgress
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.flash.FlashEnvironment
import com.xg.toolbox.feature.system.domain.flash.FlashProgress
import com.xg.toolbox.feature.system.domain.flash.PartitionInfo
import kotlin.math.roundToInt

@Composable
internal fun FlashReadOnlyBanner() {
    val colors = LocalXgColors.current
    XgPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XgStatusChip(
                text = stringResource(R.string.sys_tune_status_readonly),
                tone = XgTone.Warn,
            )
            Spacer(Modifier.width(XgSpacing.sm))
            Text(
                text = stringResource(R.string.sys_tune_flash_readonly_note),
                style = XgType.note,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun FlashBusyRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        XgStatusChip(
            text = stringResource(R.string.sys_tune_status_busy),
            tone = XgTone.Accent,
        )
        Spacer(Modifier.width(XgSpacing.sm))
        XgNote(text = stringResource(R.string.sys_tune_flash_busy_note))
    }
}

@Composable
internal fun FlashNoticeRow(
    notice: FlashNotice,
    onDismiss: () -> Unit,
) {
    val colors = LocalXgColors.current
    val label = when (notice.kind) {
        FlashNoticeKind.IMAGE_READY -> stringResource(R.string.sys_tune_flash_status_image_ready)
        FlashNoticeKind.BACKUP_READY -> stringResource(R.string.sys_tune_flash_status_backup_ready)
        FlashNoticeKind.SLOT_SWITCHED -> stringResource(R.string.sys_tune_flash_status_slot_switched)
        FlashNoticeKind.SLOT_UNSUPPORTED -> stringResource(R.string.sys_tune_flash_status_unsupported)
        FlashNoticeKind.FLASH_DONE -> stringResource(R.string.sys_tune_flash_status_done)
        FlashNoticeKind.FLASH_FAILED -> stringResource(R.string.sys_tune_flash_status_failed)
        FlashNoticeKind.ROLLBACK -> stringResource(R.string.sys_tune_flash_status_rollback)
        FlashNoticeKind.FLASH_ENDED -> stringResource(R.string.sys_tune_flash_status_ended)
        FlashNoticeKind.ABORT_REQUESTED -> stringResource(R.string.sys_tune_flash_status_abort_requested)
        FlashNoticeKind.BLOCKED -> stringResource(R.string.sys_tune_flash_status_blocked)
        FlashNoticeKind.BUSY -> stringResource(R.string.sys_tune_flash_status_busy)
        FlashNoticeKind.FAILED -> stringResource(R.string.sys_tune_flash_status_failed)
        FlashNoticeKind.MISSING_IMAGE -> stringResource(R.string.sys_tune_flash_status_missing_image)
        FlashNoticeKind.NO_PARTITION -> stringResource(R.string.sys_tune_flash_status_no_partition)
        FlashNoticeKind.IMAGE_TOO_LARGE -> stringResource(R.string.sys_tune_flash_status_image_too_large)
    }
    val description = when (notice.kind) {
        FlashNoticeKind.BACKUP_READY -> stringResource(R.string.sys_tune_flash_backup_ok)
        FlashNoticeKind.SLOT_UNSUPPORTED -> stringResource(R.string.sys_tune_flash_slot_unsupported)
        FlashNoticeKind.FLASH_DONE -> stringResource(R.string.sys_tune_flash_done)
        FlashNoticeKind.FLASH_FAILED -> stringResource(R.string.sys_tune_flash_failed)
        FlashNoticeKind.ROLLBACK -> stringResource(R.string.sys_tune_flash_rollback)
        FlashNoticeKind.FLASH_ENDED -> stringResource(R.string.sys_tune_flash_ended)
        FlashNoticeKind.ABORT_REQUESTED -> stringResource(R.string.sys_tune_flash_abort_requested)
        FlashNoticeKind.BLOCKED -> stringResource(R.string.sys_tune_flash_blocked)
        FlashNoticeKind.BUSY -> stringResource(R.string.sys_tune_flash_busy)
        FlashNoticeKind.MISSING_IMAGE -> stringResource(R.string.sys_tune_flash_missing_image)
        FlashNoticeKind.NO_PARTITION -> stringResource(R.string.sys_tune_flash_no_target)
        FlashNoticeKind.IMAGE_TOO_LARGE -> stringResource(R.string.sys_tune_flash_image_too_large)
        FlashNoticeKind.SLOT_SWITCHED,
        FlashNoticeKind.IMAGE_READY,
        FlashNoticeKind.FAILED -> null
    }
    val tone = when (notice.kind) {
        FlashNoticeKind.IMAGE_READY,
        FlashNoticeKind.BACKUP_READY,
        FlashNoticeKind.SLOT_SWITCHED,
        FlashNoticeKind.FLASH_DONE -> XgTone.Ok

        FlashNoticeKind.SLOT_UNSUPPORTED,
        FlashNoticeKind.FLASH_ENDED,
        FlashNoticeKind.ABORT_REQUESTED,
        FlashNoticeKind.BUSY,
        FlashNoticeKind.MISSING_IMAGE,
        FlashNoticeKind.NO_PARTITION,
        FlashNoticeKind.IMAGE_TOO_LARGE -> XgTone.Warn

        FlashNoticeKind.FLASH_FAILED,
        FlashNoticeKind.ROLLBACK,
        FlashNoticeKind.BLOCKED,
        FlashNoticeKind.FAILED -> XgTone.Danger
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XgStatusChip(text = label, tone = tone)
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.sys_tune_action_clear),
                onClick = onDismiss,
                tone = XgTone.Muted,
            )
        }
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(XgSpacing.xs))
            Text(
                text = description,
                style = XgType.note,
                color = colors.muted,
            )
        }
        if (!notice.detail.isNullOrBlank()) {
            Spacer(Modifier.height(XgSpacing.xs))
            Text(
                text = notice.detail.orEmpty(),
                style = XgType.note,
                color = colors.faint,
            )
        }
    }
}

@Composable
internal fun FlashEnvironmentPanel(
    environment: FlashEnvironment,
    onRedetect: () -> Unit,
    redetectEnabled: Boolean,
) {
    val scheme = when {
        environment.scheme.isBlank() -> stringResource(R.string.sys_tune_value_none)
        environment.version.isBlank() -> environment.scheme
        else -> stringResource(
            R.string.sys_tune_flash_scheme_value,
            environment.scheme,
            environment.version,
        )
    }
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_flash_env),
            meta = stringResource(R.string.sys_tune_flash_env_meta),
            trailing = {
                XgStatusChip(
                    text = environment.scheme.ifBlank {
                        stringResource(R.string.sys_tune_value_none)
                    },
                    tone = XgTone.Accent,
                )
            },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_scheme),
            value = scheme,
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_evidence),
            value = environment.evidence.joinToString("\n").ifBlank {
                stringResource(R.string.sys_tune_flash_evidence_empty)
            },
            copyable = environment.evidence.isNotEmpty(),
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_kernel),
            value = environment.kernelVersion,
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_boot_mode),
            value = environment.bootMode,
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_slot_suffix),
            value = environment.slotSuffix.ifBlank {
                stringResource(R.string.sys_tune_value_none)
            },
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_current_slot),
            value = environment.currentSlot ?: stringResource(R.string.sys_tune_value_none),
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_bootctl),
            value = if (environment.bootctl) {
                stringResource(R.string.sys_tune_flash_bootctl_yes)
            } else {
                stringResource(R.string.sys_tune_flash_bootctl_no)
            },
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_parts),
            value = stringResource(R.string.sys_tune_flash_parts_meta, environment.availableParts.size),
            copyable = false,
        )
        Spacer(Modifier.height(XgSpacing.xs))
        XgNote(text = stringResource(R.string.sys_tune_flash_scheme_note))
        XgNote(text = stringResource(R.string.sys_tune_flash_root_required))
        Spacer(Modifier.height(XgSpacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.sys_tune_action_redetect),
                onClick = onRedetect,
                enabled = redetectEnabled,
            )
        }
    }
}

@Composable
internal fun FlashSlotPanel(
    environment: FlashEnvironment,
    editable: Boolean,
    onSwitchSlot: () -> Unit,
) {
    val current = environment.currentSlot
    val activeA = isSlotA(current)
    val activeB = isSlotB(current)
    val canSwitch = environment.bootctl && current != null && editable
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_flash_slot_panel),
            meta = stringResource(R.string.sys_tune_flash_slot_meta),
        )
        Spacer(Modifier.height(XgSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm)) {
            SlotCard(
                name = stringResource(R.string.sys_tune_flash_slot_a),
                active = activeA,
                modifier = Modifier.weight(1f),
            )
            SlotCard(
                name = stringResource(R.string.sys_tune_flash_slot_b),
                active = activeB,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(XgSpacing.sm))
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_current_slot),
            value = current ?: stringResource(R.string.sys_tune_value_none),
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_bootctl),
            value = if (environment.bootctl) {
                stringResource(R.string.sys_tune_flash_bootctl_yes)
            } else {
                stringResource(R.string.sys_tune_flash_bootctl_no)
            },
            copyable = false,
        )
        Spacer(Modifier.height(XgSpacing.sm))
        XgGhostButton(
            text = slotSwitchLabel(current),
            onClick = onSwitchSlot,
            enabled = canSwitch,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(XgSpacing.xs))
        XgNote(text = stringResource(R.string.sys_tune_flash_slot_hint))
        if (!environment.bootctl || current == null) {
            XgNote(
                text = stringResource(R.string.sys_tune_flash_slot_unsupported),
                tone = XgTone.Warn,
            )
        }
    }
}

@Composable
internal fun SlotCard(
    name: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(XgRadius.small)
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (active) colors.accentSoft else colors.surface2)
            .border(1.dp, if (active) colors.accent else colors.line, shape)
            .padding(XgSpacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = XgType.rowTitle,
                color = colors.ink,
            )
            Spacer(Modifier.weight(1f))
            XgBadge(
                text = if (active) {
                    stringResource(R.string.sys_tune_flash_slot_active)
                } else {
                    stringResource(R.string.sys_tune_flash_slot_standby)
                },
                tone = if (active) XgTone.Ok else XgTone.Muted,
            )
        }
        Spacer(Modifier.height(XgSpacing.sm))
        Text(
            text = stringResource(R.string.sys_tune_flash_slot_managed),
            style = XgType.note,
            color = colors.faint,
        )
    }
}

@Composable
internal fun FlashPartsPanel(
    environment: FlashEnvironment,
    targetPartition: String?,
    editable: Boolean,
    onSelectPartition: (String) -> Unit,
) {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_flash_parts_panel),
            meta = stringResource(R.string.sys_tune_flash_parts_meta, environment.availableParts.size),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        environment.availableParts.forEachIndexed { index, partition ->
            if (index > 0) {
                XgHairline()
            }
            PartitionRow(
                partition = partition,
                isSelected = partition.name == targetPartition,
                editable = editable,
                onClick = { onSelectPartition(partition.name) },
            )
        }
        if (environment.availableParts.isEmpty()) {
            XgNote(text = stringResource(R.string.sys_tune_flash_no_parts))
        }
        Spacer(Modifier.height(XgSpacing.xs))
        XgNote(text = stringResource(R.string.sys_tune_flash_write_hint))
    }
}

@Composable
internal fun FlashPartsHeader(environment: FlashEnvironment) {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_flash_parts_panel),
            meta = stringResource(R.string.sys_tune_flash_parts_meta, environment.availableParts.size),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        XgNote(text = stringResource(R.string.sys_tune_flash_write_hint))
        if (environment.availableParts.isEmpty()) {
            Spacer(Modifier.height(XgSpacing.xs))
            XgNote(text = stringResource(R.string.sys_tune_flash_no_parts))
        }
    }
}

@Composable
internal fun FlashPartitionCard(
    partition: PartitionInfo,
    isSelected: Boolean,
    editable: Boolean,
    onClick: () -> Unit,
) {
    XgPanel {
        PartitionRow(
            partition = partition,
            isSelected = isSelected,
            editable = editable,
            onClick = onClick,
        )
    }
}

@Composable
internal fun PartitionRow(
    partition: PartitionInfo,
    isSelected: Boolean,
    editable: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = XgSize.rowMin)
            .clickable(
                enabled = editable && partition.writable,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { selected = isSelected }
            .padding(vertical = XgSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = partition.name,
                    style = XgType.rowTitle,
                    color = if (partition.writable) colors.ink else colors.muted,
                )
                if (isSelected) {
                    Spacer(Modifier.width(XgSpacing.sm))
                    XgBadge(
                        text = stringResource(R.string.sys_tune_flash_part_selected),
                        tone = XgTone.Accent,
                    )
                }
            }
            Spacer(Modifier.height(XgSpacing.xs))
            XgMono(
                text = "${partition.base} · ${Formatters.bytes(partition.sizeBytes)}",
                style = XgType.monoSmall,
                color = colors.faint,
            )
            Text(
                text = partition.path,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(XgSpacing.sm))
        XgBadge(
            text = if (partition.writable) {
                stringResource(R.string.sys_tune_flash_part_writable)
            } else {
                stringResource(R.string.sys_tune_flash_part_readonly)
            },
            tone = if (partition.writable) XgTone.Ok else XgTone.Muted,
        )
    }
}