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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.xg.toolbox.feature.system.domain.flash.FlashBackup
import com.xg.toolbox.feature.system.domain.flash.FlashImageInfo
import com.xg.toolbox.feature.system.domain.flash.FlashProgress
import com.xg.toolbox.feature.system.domain.flash.PartitionInfo
import kotlin.math.roundToInt

@Composable
internal fun FlashImagePanel(
    readOnly: Boolean,
    busy: Boolean,
    inspecting: Boolean,
    image: FlashImageInfo?,
    target: String?,
    targetInfo: FlashPartitionUi?,
    sizeTooLarge: Boolean,
    canSkipBackup: Boolean,
    skipBackup: Boolean,
    onSkipBackupChange: (Boolean) -> Unit,
    backup: FlashBackup?,
    writableTarget: Boolean,
    onPickImage: () -> Unit,
    onBackup: () -> Unit,
    onWrite: () -> Unit,
) {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_flash_image_panel),
            meta = stringResource(R.string.sys_tune_flash_image_meta),
        )
        Spacer(Modifier.height(XgSpacing.md))
        XgGhostButton(
            text = if (image == null) {
                stringResource(R.string.sys_tune_flash_pick_image)
            } else {
                stringResource(R.string.sys_tune_flash_change_image)
            },
            onClick = onPickImage,
            enabled = !busy && !inspecting,
            modifier = Modifier.fillMaxWidth(),
        )
        if (inspecting) {
            Spacer(Modifier.height(XgSpacing.sm))
            XgTaskProgress(
                title = stringResource(R.string.sys_tune_flash_inspecting),
                progress = null,
            )
        }
        if (image == null) {
            Spacer(Modifier.height(XgSpacing.sm))
            XgNote(text = stringResource(R.string.sys_tune_flash_no_image))
        } else {
            Spacer(Modifier.height(XgSpacing.sm))
            XgKeyValueRow(
                label = stringResource(R.string.sys_tune_flash_image_name),
                value = image.displayName,
                copyable = false,
            )
            XgKeyValueRow(
                label = stringResource(R.string.sys_tune_flash_image_size),
                value = Formatters.bytes(image.sizeBytes),
                copyable = false,
            )
            XgKeyValueRow(
                label = stringResource(R.string.sys_tune_flash_image_sha),
                value = image.sha256,
                copyable = true,
            )
        }
        XgHairline()
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_target),
            value = target ?: stringResource(R.string.sys_tune_value_none),
            copyable = false,
        )
        targetInfo?.let { info ->
            XgKeyValueRow(
                label = stringResource(R.string.sys_tune_flash_target_slot),
                value = when {
                    info.slot == null -> stringResource(R.string.sys_tune_flash_slot_none)
                    info.isCurrentSlot -> stringResource(R.string.sys_tune_flash_current_slot_value, info.slot)
                    else -> info.slot
                },
                copyable = false,
            )
            XgKeyValueRow(
                label = stringResource(R.string.sys_tune_flash_target_risk),
                value = when (info.risk) {
                    FlashPartitionRisk.SAFE -> stringResource(R.string.sys_tune_flash_risk_safe)
                    FlashPartitionRisk.CAUTION -> stringResource(R.string.sys_tune_flash_risk_caution)
                    FlashPartitionRisk.DANGEROUS -> stringResource(R.string.sys_tune_flash_risk_dangerous)
                },
                copyable = false,
            )
            XgKeyValueRow(
                label = stringResource(R.string.sys_tune_flash_part_size),
                value = Formatters.bytes(info.sizeBytes),
                copyable = false,
            )
        }
        targetInfo?.let { info ->
            when (info.risk) {
                FlashPartitionRisk.CAUTION -> XgNote(
                    text = stringResource(R.string.sys_tune_flash_risk_notice_caution),
                    tone = XgTone.Warn,
                )

                FlashPartitionRisk.DANGEROUS -> XgNote(
                    text = stringResource(R.string.sys_tune_flash_risk_notice_dangerous),
                    tone = XgTone.Danger,
                )

                FlashPartitionRisk.SAFE -> Unit
            }
            if (info.sizeBytes > SKIP_BACKUP_MIN_BYTES) {
                XgNote(
                    text = stringResource(R.string.sys_tune_flash_backup_hint_large),
                    tone = XgTone.Warn,
                )
            }
        }
        if (sizeTooLarge && image != null && targetInfo != null) {
            XgNote(
                text = stringResource(
                    R.string.sys_tune_flash_size_too_large,
                    Formatters.bytes(image.sizeBytes),
                    Formatters.bytes(targetInfo.sizeBytes),
                ),
                tone = XgTone.Danger,
            )
        }
        XgKeyValueRow(
            label = stringResource(R.string.sys_tune_flash_backup_result),
            value = backup?.path ?: stringResource(R.string.sys_tune_flash_backup_none),
            copyable = backup != null,
        )
        if (backup != null) {
            XgKeyValueRow(
                label = stringResource(R.string.sys_tune_flash_backup_size),
                value = Formatters.bytes(backup.bytes),
                copyable = false,
            )
            if (!backup.sha256.isNullOrBlank()) {
                XgKeyValueRow(
                    label = stringResource(R.string.sys_tune_flash_backup_sha),
                    value = backup.sha256,
                    copyable = true,
                )
            }
        }
        Spacer(Modifier.height(XgSpacing.sm))
        XgGhostButton(
            text = stringResource(R.string.sys_tune_flash_backup),
            onClick = onBackup,
            enabled = !readOnly && !busy && target != null,
            modifier = Modifier.fillMaxWidth(),
        )
        if (canSkipBackup) {
            Spacer(Modifier.height(XgSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = skipBackup,
                    onCheckedChange = onSkipBackupChange,
                    enabled = !readOnly && !busy,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.sys_tune_flash_skip_backup_option),
                    style = XgType.body,
                    color = LocalXgColors.current.ink,
                )
            }
            if (skipBackup) {
                XgNote(
                    text = stringResource(R.string.sys_tune_flash_skip_backup_warning),
                    tone = XgTone.Danger,
                )
            }
        }
        Spacer(Modifier.height(XgSpacing.sm))
        XgDangerButton(
            text = stringResource(
                R.string.sys_tune_flash_write,
                target ?: stringResource(R.string.sys_tune_value_none),
            ),
            onClick = onWrite,
            enabled = !readOnly &&
                !busy &&
                !inspecting &&
                image != null &&
                target != null &&
                writableTarget &&
                !sizeTooLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(XgSpacing.xs))
        if (!skipBackup) {
            XgNote(text = stringResource(R.string.sys_tune_flash_auto_backup))
        }
        if (target?.lowercase()?.contains("vbmeta") == true) {
            XgNote(
                text = stringResource(R.string.sys_tune_flash_vbmeta_warning),
                tone = XgTone.Danger,
            )
        }
        if (!writableTarget) {
            XgNote(
                text = stringResource(R.string.sys_tune_flash_no_target),
                tone = XgTone.Warn,
            )
        }
        if (readOnly) {
            XgNote(
                text = stringResource(R.string.sys_tune_flash_blocked),
                tone = XgTone.Warn,
            )
        }
    }
}

@Composable
internal fun FlashProgressPanel(
    progress: FlashProgress?,
    logs: List<FlashLogLine>,
    busy: Boolean,
    onAbort: () -> Unit,
) {
    if (progress == null && logs.isEmpty()) return
    val stage = progress?.stage
    val stageLabel = stageLabel(stage)
    val percent = progress?.let {
        stringResource(
            R.string.sys_tune_flash_percent,
            it.percent.coerceIn(0f, 100f).roundToInt(),
        )
    }
    val progressFraction = when {
        progress != null -> (progress.percent / 100f).coerceIn(0f, 1f)
        busy -> null
        else -> 0f
    }
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_flash_progress),
            meta = stringResource(R.string.sys_tune_flash_progress_meta),
            trailing = {
                XgBadge(text = stageLabel, tone = stageTone(stage))
            },
        )
        Spacer(Modifier.height(XgSpacing.md))
        XgTaskProgress(
            title = stageLabel,
            progress = progressFraction,
            meta = percent,
        )
        if (busy && stage != FlashProgress.STAGE_ROLLBACK) {
            Spacer(Modifier.height(XgSpacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                XgDangerButton(
                    text = stringResource(R.string.sys_tune_flash_abort),
                    onClick = onAbort,
                )
            }
        }
        Spacer(Modifier.height(XgSpacing.sm))
        Text(
            text = stringResource(R.string.sys_tune_flash_verify),
            style = XgType.fieldLabel,
            color = LocalXgColors.current.muted,
        )
        Spacer(Modifier.height(XgSpacing.xs))
        XgNote(
            text = if (progress == null) {
                stringResource(R.string.sys_tune_flash_verify_none)
            } else {
                stringResource(
                    R.string.sys_tune_flash_verify_line,
                    stageLabel,
                    progress.message,
                )
            },
            tone = stageTone(stage),
        )
        progress?.errorCode?.let { errorCode ->
            XgNote(
                text = stringResource(R.string.sys_tune_flash_error_code, errorCode),
                tone = XgTone.Danger,
            )
        }
        Spacer(Modifier.height(XgSpacing.md))
        Text(
            text = stringResource(R.string.sys_tune_flash_log),
            style = XgType.fieldLabel,
            color = LocalXgColors.current.muted,
        )
        Spacer(Modifier.height(XgSpacing.sm))
        FlashLogConsole(lines = logs)
    }
}

@Composable
internal fun FlashLogConsole(lines: List<FlashLogLine>) {
    val colors = LocalXgColors.current
    val textColor = consoleTextColor()
    val listState = rememberLazyListState()
    val atBottom by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && atBottom) {
            listState.scrollToItem(lines.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp, max = 220.dp)
            .clip(RoundedCornerShape(XgRadius.small))
            .background(colors.console)
            .padding(XgSpacing.md),
        verticalArrangement = Arrangement.spacedBy(XgSpacing.xs),
    ) {
        if (lines.isEmpty()) {
            item(key = "flash-log-empty") {
                Text(
                    text = stringResource(R.string.sys_tune_flash_log_empty),
                    style = XgType.terminal,
                    color = textColor.copy(alpha = 0.62f),
                )
            }
        } else {
            items(lines, key = { it.id }, contentType = { "flash-log" }) { line ->
                Text(
                    text = line.text,
                    style = XgType.terminal,
                    color = textColor,
                )
            }
        }
    }
}

@Composable
internal fun FlashBrickRecoveryPanel() {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_flash_brick),
            meta = stringResource(R.string.sys_tune_flash_brick_meta),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        listOf(
            R.string.sys_tune_flash_brick_step1,
            R.string.sys_tune_flash_brick_step2,
            R.string.sys_tune_flash_brick_step3,
            R.string.sys_tune_flash_brick_step4,
            R.string.sys_tune_flash_brick_step5,
        ).forEach { step ->
            XgNote(text = stringResource(step), tone = XgTone.Neutral)
        }
        Spacer(Modifier.height(XgSpacing.xs))
        XgNote(
            text = stringResource(R.string.sys_tune_flash_brick_note),
            tone = XgTone.Warn,
        )
    }
}

@Composable
internal fun stageLabel(stage: String?): String = when (stage) {
    "validate" -> stringResource(R.string.sys_tune_flash_stage_validate)
    "backup" -> stringResource(R.string.sys_tune_flash_stage_backup)
    "staging" -> stringResource(R.string.sys_tune_flash_stage_staging)
    "writing" -> stringResource(R.string.sys_tune_flash_stage_writing)
    "verify" -> stringResource(R.string.sys_tune_flash_stage_verify)
    "rollback" -> stringResource(R.string.sys_tune_flash_stage_rollback)
    "done" -> stringResource(R.string.sys_tune_flash_stage_done)
    "failed" -> stringResource(R.string.sys_tune_flash_stage_failed)
    else -> stringResource(R.string.sys_tune_flash_stage_unknown)
}

internal fun stageTone(stage: String?): XgTone = when (stage) {
    "done" -> XgTone.Ok
    "failed", "rollback" -> XgTone.Danger
    "validate", "verify" -> XgTone.Accent
    else -> XgTone.Muted
}

@Composable
internal fun consoleTextColor(): Color {
    val colors = LocalXgColors.current
    return if (colors.isDark) colors.ink else colors.bg
}

internal fun isSlotA(slot: String?): Boolean = when (slot?.lowercase()) {
    "_a", "a", "0" -> true
    else -> false
}

internal fun isSlotB(slot: String?): Boolean = when (slot?.lowercase()) {
    "_b", "b", "1" -> true
    else -> false
}

@Composable
internal fun slotSwitchLabel(current: String?): String {
    val target = when (current?.lowercase()) {
        "_a", "a", "0" -> stringResource(R.string.sys_tune_flash_slot_b)
        "_b", "b", "1" -> stringResource(R.string.sys_tune_flash_slot_a)
        else -> null
    }
    return if (target == null) {
        stringResource(R.string.sys_tune_flash_slot_switch)
    } else {
        stringResource(R.string.sys_tune_flash_slot_switch_target, target)
    }
}

@Composable
internal fun slotSwitchTargetText(current: String?): String = when (current?.lowercase()) {
    "_a", "a", "0" -> stringResource(R.string.sys_tune_flash_slot_b)
    "_b", "b", "1" -> stringResource(R.string.sys_tune_flash_slot_a)
    else -> stringResource(R.string.sys_tune_value_none)
}