package com.xg.toolbox.feature.system.ui.optimize

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgKeyValueRow
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgNum
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgPrimaryButton
import com.xg.toolbox.core.ui.component.XgProgressBar
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgTaskProgress
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.optimize.OptimizeItem
import com.xg.toolbox.feature.system.domain.optimize.OptimizeProgress
import com.xg.toolbox.feature.system.domain.optimize.OptimizeScan
import kotlin.math.roundToInt

private const val STATUS_RUNNING = "running"
private const val STATUS_OK = "ok"
private const val STATUS_FAILED = "failed"
private const val STATUS_SKIPPED = "skipped"

internal fun LazyListScope.optimizePageItems(
    state: OptimizeUiState,
    onRescan: () -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRun: () -> Unit,
) {
    val firstLoading = state.loading && state.scan == null
    item(key = "optimize-root") {
        SystemRootBadge(rootState = state.rootState)
    }
    item(key = "optimize-summary") {
        XgContentTransition(
            stateKey = state.scan ?: 0,
            loading = firstLoading,
            error = state.errorMessage.takeIf { state.scan == null },
            onRetry = onRescan,
            skeletonLines = 3,
            initiallyLoaded = state.scan != null,
        ) {
            state.scan?.let { scan -> OptimizeSummaryCard(scan = scan) }
        }
    }
    item(key = "optimize-items") {
        XgContentTransition(
            stateKey = state.items,
            loading = firstLoading,
            error = null,
            onRetry = onRescan,
            skeletonLines = 4,
            initiallyLoaded = state.scan != null,
        ) {
            when {
                state.scan == null -> Unit
                state.items.isEmpty() -> XgEmptyState(
                    title = stringResource(R.string.sys_core_optimize_empty),
                    actionText = stringResource(R.string.sys_core_action_rescan),
                    onAction = onRescan,
                )

                else -> OptimizeItemsCard(
                    state = state,
                    onToggle = onToggle,
                    onSelectAll = onSelectAll,
                    onClearSelection = onClearSelection,
                    onRun = onRun,
                )
            }
        }
    }
    if (state.scan == null && !firstLoading && state.errorMessage == null) {
        item(key = "optimize-empty") {
            XgEmptyState(
                title = stringResource(R.string.sys_core_optimize_empty),
                actionText = stringResource(R.string.sys_core_action_rescan),
                onAction = onRescan,
            )
        }
    }
    if (state.compare != null || state.compareError != null) {
        item(key = "optimize-compare") {
            XgContentTransition(
                stateKey = state.compare ?: state.compareError ?: 0,
                loading = false,
                error = null,
                onRetry = null,
                initiallyLoaded = true,
            ) {
                OptimizeComparePanel(state = state)
            }
        }
    }
}

@Composable
private fun OptimizeSummaryCard(scan: OptimizeScan) {
    XgPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OptimizeStat(
                label = stringResource(R.string.sys_core_optimize_stat_items),
                value = stringResource(
                    R.string.sys_core_optimize_count,
                    scan.items.count { it.available },
                ),
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(XgSize.riskMark)
                    .background(LocalXgColors.current.line),
            )
            OptimizeStat(
                label = stringResource(R.string.sys_core_optimize_stat_reclaim),
                value = Formatters.bytes(scan.reclaimBytes),
                modifier = Modifier.weight(1f).padding(start = XgSpacing.md),
            )
        }
        Spacer(Modifier.height(4.dp))
        XgNote(
            text = stringResource(
                R.string.sys_core_optimize_stat_mem,
            ) + " · " + Formatters.bytes(scan.availableMemBytes),
        )
    }
}

@Composable
private fun OptimizeItemsCard(
    state: OptimizeUiState,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRun: () -> Unit,
) {
    val failedProgress = state.progress.values.filter { it.status == STATUS_FAILED }
    val finishedCount = state.progress.values.count { it.status != STATUS_RUNNING }
    val selectedCount = state.selectedIds.size

    XgPanel {

        state.items.forEachIndexed { index, item ->
            OptimizeItemRow(
                item = item,
                selected = item.id in state.selectedIds,
                interactionEnabled = !state.running,
                progress = state.progress[item.id],
                onToggle = { onToggle(item.id) },
            )
            if (index < state.items.lastIndex) {
                XgHairline()
            }
        }

        Spacer(Modifier.height(XgSpacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XgNote(
                text = stringResource(R.string.sys_core_optimize_selected, selectedCount),
            )
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.sys_core_action_select_all),
                onClick = onSelectAll,
                enabled = !state.running,
            )
            Spacer(Modifier.width(XgSpacing.xs))
            XgTextAction(
                text = stringResource(R.string.sys_core_action_clear_selection),
                onClick = onClearSelection,
                enabled = !state.running && selectedCount > 0,
                tone = XgTone.Muted,
            )
        }
        Spacer(Modifier.height(XgSpacing.sm))
        XgPrimaryButton(
            text = if (state.running) {
                stringResource(R.string.sys_core_optimize_running)
            } else {
                stringResource(R.string.sys_core_optimize_run)
            },
            onClick = onRun,
            enabled = state.canRun,
            modifier = Modifier.fillMaxWidth(),
        )

        if (state.running || state.progress.isNotEmpty()) {
            Spacer(Modifier.height(XgSpacing.md))
            XgTaskProgress(
                title = stringResource(R.string.sys_core_optimize_progress),
                progress = state.overallProgress,
                meta = stringResource(
                    R.string.sys_core_optimize_progress_meta,
                    (state.overallProgress * 100f).roundToInt(),
                    finishedCount,
                    selectedCount,
                ),
            )
        }

        if (failedProgress.isNotEmpty()) {
            val fallback = stringResource(R.string.sys_core_optimize_status_failed)
            Spacer(Modifier.height(XgSpacing.sm))
            failedProgress.forEach { progress ->
                val name = state.items.firstOrNull { it.id == progress.itemId }?.name
                    ?: progress.itemId
                XgNote(
                    text = "$name：${progress.message ?: fallback}",
                    tone = XgTone.Danger,
                )
            }
        }

        if (state.scan != null && state.errorMessage != null) {
            Spacer(Modifier.height(XgSpacing.xs))
            XgNote(text = state.errorMessage.orEmpty(), tone = XgTone.Danger)
        }
    }
}

@Composable
private fun OptimizeStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        XgNote(text = label)
        Spacer(Modifier.height(2.dp))
        XgNum(text = value, style = XgType.monoValueMedium)
    }
}

@Composable
private fun OptimizeItemRow(
    item: OptimizeItem,
    selected: Boolean,
    interactionEnabled: Boolean,
    progress: OptimizeProgress?,
    onToggle: () -> Unit,
) {
    val colors = LocalXgColors.current
    val selectable = item.available && interactionEnabled
    val rowModifier = if (selectable) {
        Modifier.toggleable(
            value = selected,
            role = Role.Checkbox,
            onValueChange = { onToggle() },
        )
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(rowModifier)
            .heightIn(min = XgSize.rowMin)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SysCheckBox(
            checked = selected,
            onCheckedChange = null,
            enabled = selectable,
        )
        Spacer(Modifier.width(XgSpacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    style = XgType.rowTitle,
                    color = if (item.available) colors.ink else colors.muted,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                if (!item.available) {
                    XgBadge(
                        text = stringResource(R.string.sys_core_optimize_unavailable),
                        tone = XgTone.Muted,
                    )
                } else if (progress != null) {
                    XgBadge(
                        text = optimizeStatusLabel(progress.status),
                        tone = optimizeStatusTone(progress.status),
                    )
                } else {
                    Text(
                        text = item.benefit,
                        style = XgType.rowNote,
                        color = colors.muted,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.description,
                style = XgType.rowNote,
                color = colors.faint,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.sys_core_optimize_risk, item.risk),
                style = XgType.rowNote,
                color = colors.faint,
            )
            Text(
                text = stringResource(R.string.sys_core_optimize_rollback, item.rollback),
                style = XgType.rowNote,
                color = colors.faint,
            )
            if (!item.available && !item.unavailableReason.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                XgNote(
                    text = item.unavailableReason.orEmpty(),
                    tone = XgTone.Warn,
                )
            }
            val failedProgress = progress?.takeIf { it.status == STATUS_FAILED }
            if (failedProgress != null && !failedProgress.message.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                XgNote(text = failedProgress.message.orEmpty(), tone = XgTone.Danger)
            }
            val runningProgress = progress?.takeIf { it.status == STATUS_RUNNING }
            if (runningProgress != null) {
                Spacer(Modifier.height(5.dp))
                XgProgressBar(
                    progress = normalizePercent(runningProgress.percent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun OptimizeComparePanel(state: OptimizeUiState) {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_core_optimize_compare_title),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        val compare = state.compare
        if (compare != null) {
            XgKeyValueRow(
                label = stringResource(R.string.sys_core_optimize_compare_mem),
                value = compareValue(compare.beforeMemBytes, compare.afterMemBytes),
                copyable = false,
            )
            XgHairline()
            XgKeyValueRow(
                label = stringResource(R.string.sys_core_optimize_compare_used),
                value = compareValue(compare.beforeUsedBytes, compare.afterUsedBytes),
                copyable = false,
            )
            XgHairline()
            XgKeyValueRow(
                label = stringResource(R.string.sys_core_optimize_compare_disabled),
                value = "${compare.beforeDisabledBloatCount} → ${compare.afterDisabledBloatCount}",
                copyable = false,
            )
        }
        if (state.compareError != null) {
            Spacer(Modifier.height(XgSpacing.xs))
            XgNote(
                text = stringResource(
                    R.string.sys_core_optimize_compare_failed,
                    state.compareError,
                ),
                tone = XgTone.Danger,
            )
        }
    }
}

private fun compareValue(before: Long, after: Long): String {
    val delta = signedBytes(after - before)
    return "${Formatters.bytes(before)} → ${Formatters.bytes(after)} · $delta"
}

@Composable
private fun optimizeStatusLabel(status: String): String = when (status) {
    STATUS_RUNNING -> stringResource(R.string.sys_core_optimize_status_running)
    STATUS_OK -> stringResource(R.string.sys_core_optimize_status_ok)
    STATUS_FAILED -> stringResource(R.string.sys_core_optimize_status_failed)
    STATUS_SKIPPED -> stringResource(R.string.sys_core_optimize_status_skipped)
    else -> status
}

private fun optimizeStatusTone(status: String): XgTone = when (status) {
    STATUS_RUNNING -> XgTone.Accent
    STATUS_OK -> XgTone.Ok
    STATUS_FAILED -> XgTone.Danger
    STATUS_SKIPPED -> XgTone.Muted
    else -> XgTone.Neutral
}
