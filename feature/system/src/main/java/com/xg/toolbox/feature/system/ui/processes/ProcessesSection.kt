package com.xg.toolbox.feature.system.ui.processes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xg.toolbox.core.ui.component.XgAnchor
import com.xg.toolbox.core.ui.component.XgAnchorRail
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgDangerButton
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgTaskProgress
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.processes.ProcessGroup
import com.xg.toolbox.feature.system.domain.processes.ProcessItem
import com.xg.toolbox.feature.system.ui.optimize.SystemRootBadge

internal data class StopRequest(
    val items: List<ProcessItem>,
    val force: Boolean,
)

internal fun LazyListScope.processesPageItems(
    state: ProcessesUiState,
    viewModel: ProcessesViewModel,
    onLoad: () -> Unit,
    onSetGroup: (ProcessGroup?) -> Unit,
    onSelectAll: () -> Unit,
    onToggleSelection: (Int) -> Unit,
    onToggleExpanded: (Int) -> Unit,
    onRequestStop: (List<ProcessItem>, Boolean) -> Unit,
) {
    item(key = "processes-root") {
        SystemRootBadge(rootState = state.rootState)
    }
    if (state.processes.isEmpty()) {
        item(key = "processes-empty") {
            XgContentTransition(
                stateKey = state.loading,
                loading = state.loading && state.errorMessage == null,
                error = state.errorMessage,
                onRetry = onLoad,
                skeletonLines = 4,
                initiallyLoaded = !state.loading && state.errorMessage == null,
            ) {
                XgEmptyState(
                    title = stringResource(R.string.sys_core_processes_empty),
                    actionText = stringResource(R.string.sys_core_action_reload),
                    onAction = onLoad,
                )
            }
        }
        return
    }

    item(key = "processes-controls") {
        XgPanel {
            XgNote(text = stringResource(R.string.sys_core_processes_note))
            if (state.stopping || state.stopTotal > 0) {
                Spacer(Modifier.height(XgSpacing.sm))
                XgTaskProgress(
                    title = stringResource(R.string.sys_core_processes_stop_progress),
                    progress = state.stopProgress,
                    meta = stringResource(
                        R.string.sys_core_processes_stop_summary,
                        state.stopSuccessCount,
                        state.stopFailedCount,
                    ),
                )
                val current = state.stopCurrent
                if (current != null) {
                    Spacer(Modifier.height(XgSpacing.xs))
                    XgNote(
                        text = stringResource(R.string.sys_core_processes_stopping, current),
                    )
                }
            }
            Spacer(Modifier.height(XgSpacing.sm))
            ProcessGroupFilter(
                selectedGroup = state.group,
                onSelect = onSetGroup,
            )
            Spacer(Modifier.height(XgSpacing.xs))
            ProcessBatchBar(
                selectedCount = state.selectedPids.size,
                canSelectAll = !state.stopping,
                canStop = state.canStopSelected,
                onSelectAll = onSelectAll,
                onStop = {
                    val targets = state.processes.filter {
                        it.pid in state.selectedPids && !it.protected
                    }
                    if (targets.isNotEmpty()) {
                        onRequestStop(targets, false)
                    }
                },
            )
        }
    }

    val filtered = state.visibleRows
    if (filtered.isEmpty()) {
        item(key = "processes-empty-group") {
            XgEmptyState(
                title = stringResource(R.string.sys_core_processes_empty_group),
                actionText = stringResource(R.string.sys_core_processes_show_all_groups),
                onAction = { onSetGroup(null) },
            )
        }
    } else {
        itemsIndexed(
            items = filtered,
            key = { _, row -> row.pid },
            contentType = { _, row -> row.group.name },
        ) { index, row ->
            Column(modifier = Modifier.fillMaxWidth()) {
                ProcessRow(
                    row = row,
                    selected = row.pid in state.selectedPids,
                    expanded = row.pid in state.expandedPids,
                    interactionEnabled = !state.stopping && state.rootGranted,
                    viewModel = viewModel,
                    onToggleSelection = { onToggleSelection(row.pid) },
                    onToggleExpanded = { onToggleExpanded(row.pid) },
                    onStop = { force -> onRequestStop(listOf(row.item), force) },
                )
                if (index < filtered.lastIndex) {
                    XgHairline()
                }
            }
        }
    }

    if (state.errorMessage != null) {
        item(key = "processes-error") {
            XgNote(text = state.errorMessage.orEmpty(), tone = XgTone.Danger)
        }
    }
    if (!state.stopping && state.stopTotal > 0) {
        item(key = "processes-stop-summary") {
            Column(modifier = Modifier.fillMaxWidth()) {
                XgNote(
                    text = stringResource(
                        R.string.sys_core_processes_stop_summary,
                        state.stopSuccessCount,
                        state.stopFailedCount,
                    ),
                    tone = if (state.stopFailedCount > 0) XgTone.Danger else XgTone.Ok,
                )
                state.stopFailures.forEach { failure ->
                    XgNote(text = failure, tone = XgTone.Danger)
                }
            }
        }
    }
}

@Composable
private fun ProcessGroupFilter(
    selectedGroup: ProcessGroup?,
    onSelect: (ProcessGroup?) -> Unit,
) {
    val anchors = listOf(
        XgAnchor("all", stringResource(R.string.sys_core_processes_group_all)),
        XgAnchor(
            ProcessGroup.FOREGROUND.name,
            stringResource(R.string.sys_core_processes_group_foreground),
        ),
        XgAnchor(
            ProcessGroup.VISIBLE.name,
            stringResource(R.string.sys_core_processes_group_visible),
        ),
        XgAnchor(
            ProcessGroup.SERVICE.name,
            stringResource(R.string.sys_core_processes_group_service),
        ),
        XgAnchor(
            ProcessGroup.CACHED.name,
            stringResource(R.string.sys_core_processes_group_cached),
        ),
        XgAnchor(
            ProcessGroup.SYSTEM.name,
            stringResource(R.string.sys_core_processes_group_system),
        ),
        XgAnchor(
            ProcessGroup.KERNEL.name,
            stringResource(R.string.sys_core_processes_group_kernel),
        ),
    )
    XgAnchorRail(
        anchors = anchors,
        selectedId = selectedGroup?.name ?: "all",
        onSelect = { id ->
            onSelect(
                if (id == "all") {
                    null
                } else {
                    ProcessGroup.values().firstOrNull { it.name == id }
                },
            )
        },
    )
}

@Composable
private fun ProcessBatchBar(
    selectedCount: Int,
    canSelectAll: Boolean,
    canStop: Boolean,
    onSelectAll: () -> Unit,
    onStop: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XgNote(
                text = stringResource(R.string.sys_core_processes_selected, selectedCount),
            )
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.sys_core_action_select_all),
                onClick = onSelectAll,
                enabled = canSelectAll,
            )
        }
        Spacer(Modifier.height(XgSpacing.xs))
        XgDangerButton(
            text = stringResource(R.string.sys_core_processes_stop_selected),
            onClick = onStop,
            enabled = canStop,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
