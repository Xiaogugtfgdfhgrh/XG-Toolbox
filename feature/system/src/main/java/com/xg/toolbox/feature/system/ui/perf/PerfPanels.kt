package com.xg.toolbox.feature.system.ui.perf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgChoiceRow
import com.xg.toolbox.core.ui.component.XgConfirmDialog
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgStatusChip
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgToggleRow
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.component.xgToneColor
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.perf.PerfControl
import com.xg.toolbox.feature.system.domain.perf.PerfState

@Composable
internal fun ReadOnlyBanner() {
    XgPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XgStatusChip(
                text = stringResource(R.string.sys_tune_status_readonly),
                tone = XgTone.Warn,
            )
            Spacer(Modifier.width(XgSpacing.sm))
            Text(
                text = stringResource(R.string.sys_tune_readonly_note),
                style = XgType.note,
                color = LocalXgColors.current.muted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun PerfNoticeRow(
    notice: PerfNotice,
    onDismiss: () -> Unit,
) {
    val colors = LocalXgColors.current
    val label = when (notice.kind) {
        PerfNoticeKind.APPLIED -> stringResource(R.string.sys_tune_perf_notice_applied)
        PerfNoticeKind.RESTORED -> stringResource(R.string.sys_tune_perf_notice_restored)
        PerfNoticeKind.UPDATED -> stringResource(R.string.sys_tune_perf_notice_updated)
        PerfNoticeKind.FAILED -> stringResource(R.string.sys_tune_status_failed)
        PerfNoticeKind.BLOCKED -> stringResource(R.string.sys_tune_perf_notice_blocked)
        PerfNoticeKind.READBACK_FAILED -> stringResource(R.string.sys_tune_perf_notice_readback_failed)
    }
    val tone = when (notice.kind) {
        PerfNoticeKind.APPLIED, PerfNoticeKind.RESTORED -> XgTone.Ok
        PerfNoticeKind.UPDATED -> XgTone.Accent
        PerfNoticeKind.FAILED, PerfNoticeKind.BLOCKED, PerfNoticeKind.READBACK_FAILED -> XgTone.Danger
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
        if (!notice.detail.isNullOrBlank()) {
            Spacer(Modifier.height(XgSpacing.xs))
            Text(
                text = notice.detail.orEmpty(),
                style = XgType.note,
                color = colors.muted,
            )
        }
    }
}

@Composable
internal fun PerfSummaryPanel(data: PerfState) {
    val entries = data.summary.entries.map { entry -> entry.key to entry.value }

    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_perf_summary),
            meta = stringResource(R.string.sys_tune_perf_summary_meta),
            trailing = {
                XgBadge(
                    text = stringResource(R.string.sys_tune_perf_controls_meta, data.controls.size),
                    tone = XgTone.Accent,
                )
            },
        )
        Spacer(Modifier.height(XgSpacing.md))
        if (entries.isEmpty()) {
            XgNote(text = stringResource(R.string.sys_tune_perf_summary_empty), tone = XgTone.Muted)
        } else {
            SummaryGrid(entries)
        }
    }
}

@Composable
internal fun SummaryGrid(entries: List<Pair<String, String>>) {
    val colors = LocalXgColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(XgSpacing.md),
    ) {
        entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(XgSpacing.lg),
            ) {
                row.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = XgType.metricName,
                            color = colors.faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(XgSpacing.xs))
                        XgMono(text = value, style = XgType.monoValueMedium, color = colors.ink)
                    }
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun PerfControlsPanel(
    data: PerfState,
    state: PerfUiState,
    onSelectChoice: (String, String) -> Unit,
    onApply: (String) -> Unit,
    onMemoryCompression: (Boolean) -> Unit,
    onThermalGuard: (Boolean) -> Unit,
) {
    val memoryLabel = stringResource(R.string.sys_tune_perf_memory)
    val thermalKey = stringResource(R.string.sys_tune_perf_thermal_key)
    val unavailable = stringResource(R.string.sys_tune_value_unavailable)
    val memoryState = data.summary.entries.firstOrNull { it.key == memoryLabel }?.value
    val thermalState = data.summary.entries.firstOrNull { it.key.contains(thermalKey) }?.value
    val memoryAvailable = memoryState?.contains(unavailable) != true
    val thermalAvailable = thermalState?.contains(unavailable) != true
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_perf_controls),
            meta = stringResource(R.string.sys_tune_perf_controls_meta, data.controls.size),
            trailing = {
                if (state.busy) {
                    XgStatusChip(
                        text = stringResource(R.string.sys_tune_status_busy),
                        tone = XgTone.Accent,
                    )
                }
            },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        data.controls.forEachIndexed { index, control ->
            if (index > 0) {
                XgHairline()
            }
            PerfControlBlock(
                control = control,
                pendingChoiceId = state.pendingChoices[control.id],
                readOnly = state.readOnly,
                busy = state.busy,
                onSelectChoice = { choiceId -> onSelectChoice(control.id, choiceId) },
                onApply = { onApply(control.id) },
            )
        }
        if (data.controls.isEmpty()) {
            XgNote(text = stringResource(R.string.sys_tune_perf_empty))
        }
        XgHairline()
        XgToggleRow(
            name = memoryLabel,
            note = if (memoryAvailable) {
                stringResource(R.string.sys_tune_perf_memory_note)
            } else {
                stringResource(R.string.sys_tune_perf_memory_unavailable)
            },
            checked = data.memoryCompression,
            onCheckedChange = onMemoryCompression,
            enabled = !state.readOnly && !state.busy && memoryAvailable,
        )
        XgHairline()
        XgToggleRow(
            name = stringResource(R.string.sys_tune_perf_thermal),
            note = if (thermalAvailable) {
                stringResource(R.string.sys_tune_perf_thermal_note)
            } else {
                stringResource(R.string.sys_tune_perf_thermal_unavailable)
            },
            checked = data.thermalGuard,
            onCheckedChange = onThermalGuard,
            enabled = !state.readOnly && !state.busy && thermalAvailable,
        )
        if (state.readOnly) {
            Spacer(Modifier.height(XgSpacing.sm))
            XgNote(text = stringResource(R.string.sys_tune_perf_notice_blocked), tone = XgTone.Warn)
        }
    }
}

@Composable
internal fun PerfControlBlock(
    control: PerfControl,
    pendingChoiceId: String?,
    readOnly: Boolean,
    busy: Boolean,
    onSelectChoice: (String) -> Unit,
    onApply: () -> Unit,
) {
    val colors = LocalXgColors.current
    val currentChoiceId = control.choices.firstOrNull { it.label == control.current }?.id
    val currentLabel = currentChoiceId
        ?.let { id -> control.choices.firstOrNull { it.id == id }?.label }
        ?: control.current
    val pendingLabel = pendingChoiceId
        ?.let { id -> control.choices.firstOrNull { it.id == id }?.label }
    val selectedId = pendingChoiceId ?: currentChoiceId
    val selectedIndex = control.choices.indexOfFirst { it.id == selectedId }
    val canEdit = control.available && !readOnly && !busy
    val canApply = canEdit && pendingChoiceId != null && pendingChoiceId != currentChoiceId

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = XgSpacing.sm)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = control.name,
                    style = XgType.rowTitle,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(XgSpacing.xs))
                Text(
                    text = control.description,
                    style = XgType.rowNote,
                    color = colors.faint,
                )
            }
            Spacer(Modifier.width(XgSpacing.sm))
            Text(
                text = stringResource(R.string.sys_tune_perf_current, currentLabel),
                style = XgType.monoSmall,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(XgSpacing.sm))
        XgChoiceRow(
            options = control.choices.map { it.label },
            selectedIndex = selectedIndex,
            onSelect = { index ->
                control.choices.getOrNull(index)?.let { choice -> onSelectChoice(choice.id) }
            },
            enabled = canEdit,
        )
        if (!control.available) {
            Spacer(Modifier.height(XgSpacing.xs))
            XgNote(
                text = control.unavailableReason ?: stringResource(R.string.sys_tune_perf_disabled),
                tone = XgTone.Warn,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pendingLabel != null && pendingChoiceId != currentChoiceId) {
                XgMono(
                    text = stringResource(R.string.sys_tune_perf_pending, pendingLabel),
                    style = XgType.monoSmall,
                    color = xgToneColor(XgTone.Accent),
                )
            }
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.sys_tune_action_apply),
                onClick = onApply,
                enabled = canApply,
            )
        }
    }
}

@Composable
internal fun RestoreDefaultsPanel(
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_perf_restore_title),
            meta = stringResource(R.string.sys_tune_perf_restore_note),
        )
        Spacer(Modifier.height(XgSpacing.md))
        XgGhostButton(
            text = stringResource(R.string.sys_tune_action_restore_defaults),
            onClick = onRestore,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}