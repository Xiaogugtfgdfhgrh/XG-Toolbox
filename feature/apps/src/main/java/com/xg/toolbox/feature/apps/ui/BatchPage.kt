package com.xg.toolbox.feature.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPrimaryButton
import com.xg.toolbox.core.ui.component.XgSearchField
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgTaskProgress
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.apps.AppRowUi
import com.xg.toolbox.feature.apps.AppsUiState
import com.xg.toolbox.feature.apps.AppsViewModel
import com.xg.toolbox.feature.apps.BatchStep
import com.xg.toolbox.feature.apps.BatchUiState
import com.xg.toolbox.feature.apps.R
import com.xg.toolbox.feature.apps.domain.AppActions
import com.xg.toolbox.feature.apps.domain.BatchAction
import com.xg.toolbox.feature.apps.domain.BatchItemResult
import com.xg.toolbox.feature.apps.domain.PreflightIssue
import kotlinx.coroutines.delay

@Composable
fun BatchPage(
    state: AppsUiState,
    onBack: () -> Unit,
    onClose: () -> Unit,
    viewModel: AppsViewModel,
) {
    val batch = state.batch
    var expandedPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var copyFeedback by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    LaunchedEffect(copyFeedback) {
        if (copyFeedback != 0) {
            delay(1000)
            copyFeedback = 0
        }
    }
    XgSubPageScaffold(
        title = stringResource(R.string.apps_batch_title),
        meta = stringResource(R.string.apps_batch_meta_selected, batch.selected.size),
        onBack = onBack,
        actions = {
            XgTextAction(
                text = stringResource(R.string.apps_batch_close),
                onClick = onClose,
                enabled = !batch.executing,
            )
        },
    ) {
        item(key = "batch_steps") {
            BatchStepIndicator(step = batch.step)
        }
        when (batch.step) {
            BatchStep.SELECT -> {
                batchSelectItems(
                    batch = batch,
                    canWrite = state.canWrite,
                    writeBlockedReason = state.writeBlockedReason,
                    onQuery = viewModel::setBatchQuery,
                    onSelect = viewModel::toggleBatchSelect,
                    onSelectAll = viewModel::selectAllBatchVisible,
                    onAction = viewModel::setBatchAction,
                    onNext = viewModel::goToPreflight,
                )
            }

            BatchStep.PREFLIGHT -> {
                item(key = "batch_preflight") {
                    BatchPreflightContent(batch = batch, onRetry = viewModel::runPreflight)
                }
                item(key = "batch_preflight_actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm),
                    ) {
                        XgGhostButton(
                            text = stringResource(R.string.apps_batch_prev),
                            onClick = viewModel::backToBatchSelect,
                            modifier = Modifier.weight(1f),
                        )
                        XgPrimaryButton(
                            text = stringResource(R.string.apps_batch_start),
                            onClick = viewModel::startBatch,
                            enabled = batch.canStart,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            BatchStep.EXECUTING -> {
                item(key = "batch_executing") {
                    BatchExecutingContent(batch = batch)
                }
                items(
                    items = batch.progress?.results.orEmpty(),
                    key = { item -> "exec_${item.packageName}" },
                    contentType = { "batch_result" },
                ) { item ->
                    BatchResultRow(
                        item = item,
                        labels = state.rows,
                        expanded = item.packageName in expandedPackages,
                        onToggle = {
                            expandedPackages = if (item.packageName in expandedPackages) {
                                expandedPackages - item.packageName
                            } else {
                                expandedPackages + item.packageName
                            }
                        },
                    )
                }
            }

            BatchStep.RECEIPT -> {
                val items = batch.receipt?.items ?: batch.progress?.results.orEmpty()
                item(key = "batch_receipt_header") {
                    BatchReceiptHeader(batch = batch, items = items)
                }
                items(
                    items = items,
                    key = { item -> "receipt_${item.packageName}" },
                    contentType = { "batch_result" },
                ) { item ->
                    BatchResultRow(
                        item = item,
                        labels = state.rows,
                        expanded = item.packageName in expandedPackages,
                        onToggle = {
                            expandedPackages = if (item.packageName in expandedPackages) {
                                expandedPackages - item.packageName
                            } else {
                                expandedPackages + item.packageName
                            }
                        },
                    )
                }
                item(key = "batch_receipt_footer") {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            XgTextAction(
                                text = stringResource(R.string.apps_batch_copy_results),
                                onClick = {
                                    copyFeedback = if (
                                        context.copyPlainText(buildBatchResultText(batch.action, items))
                                    ) {
                                        1
                                    } else {
                                        -1
                                    }
                                },
                            )
                            Spacer(Modifier.weight(1f))
                            when (copyFeedback) {
                                1 -> XgNote(
                                    text = stringResource(R.string.apps_batch_copied),
                                    tone = XgTone.Ok,
                                )

                                -1 -> XgNote(
                                    text = stringResource(R.string.apps_batch_copy_failed),
                                    tone = XgTone.Danger,
                                )
                            }
                        }
                        BatchUndoBar(batch = batch, onUndo = viewModel::undoBatch)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.batchSelectItems(
    batch: BatchUiState,
    canWrite: Boolean,
    writeBlockedReason: String?,
    onQuery: (String) -> Unit,
    onSelect: (String) -> Unit,
    onSelectAll: () -> Unit,
    onAction: (BatchAction) -> Unit,
    onNext: () -> Unit,
) {
    item(key = "batch_query") {
        Column {
            XgSearchField(
                value = batch.query,
                onValueChange = onQuery,
                placeholder = stringResource(R.string.apps_search_placeholder),
            )
            XgSpacingSm()
            BatchActionSelector(action = batch.action, onAction = onAction)
            if (batch.action == BatchAction.CHANGE_ANDROID_ID) {
                XgSpacingSm()
                XgNote(text = stringResource(R.string.apps_batch_android_id_note))
            }
            AppsWriteBlockedNote(reason = writeBlockedReason)
        }
    }
    item(key = "batch_select_toolbar") {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = XgSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XgTextAction(
                text = stringResource(R.string.apps_action_select_all),
                onClick = onSelectAll,
            )
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.apps_batch_selected, batch.selected.size),
                onClick = onNext,
                enabled = batch.selected.isNotEmpty() && canWrite,
            )
        }
    }
    if (batch.visible.isEmpty()) {
        item(key = "batch_empty") {
            XgEmptyState(title = stringResource(R.string.apps_empty_filtered))
        }
    } else {
        items(
            items = batch.visible,
            key = { row: AppRowUi -> "pick_${row.packageName}" },
            contentType = { "batch_pick" },
        ) { row ->
            BatchPickRow(
                row = row,
                selected = row.packageName in batch.selected,
                enabled = canWrite,
                onClick = { onSelect(row.packageName) },
            )
        }
    }
    item(key = "batch_next") {
        XgPrimaryButton(
            text = stringResource(R.string.apps_batch_to_preflight),
            onClick = onNext,
            enabled = batch.selected.isNotEmpty() && canWrite,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BatchStepIndicator(step: BatchStep) {
    val labels = listOf(
        stringResource(R.string.apps_batch_step_select),
        stringResource(R.string.apps_batch_step_preflight),
        stringResource(R.string.apps_batch_step_execute),
        stringResource(R.string.apps_batch_step_receipt),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = XgSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(XgSpacing.xs),
    ) {
        labels.forEachIndexed { index, label ->
            val active = index == step.ordinal
            XgBadge(
                text = "${index + 1} · $label",
                tone = if (active) XgTone.Accent else XgTone.Muted,
            )
        }
    }
}

@Composable
private fun BatchActionSelector(action: BatchAction, onAction: (BatchAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(XgSpacing.xs)) {
        Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.xs)) {
            listOf(BatchAction.FREEZE, BatchAction.UNFREEZE, BatchAction.UNINSTALL).forEach { item ->
                AppsToolChip(
                    text = stringResource(batchActionLabelRes(item)),
                    selected = action == item,
                    onClick = { onAction(item) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.xs)) {
            listOf(BatchAction.DISABLE_AUTOSTART, BatchAction.CHANGE_ANDROID_ID).forEach { item ->
                AppsToolChip(
                    text = stringResource(batchActionLabelRes(item)),
                    selected = action == item,
                    onClick = { onAction(item) },
                )
            }
        }
    }
}

@Composable
private fun BatchPickRow(
    row: AppRowUi,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(XgRadius.card)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = XgSize.rowMin + XgSpacing.sm)
            .padding(horizontal = XgSpacing.md, vertical = XgSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = stringResource(
                if (selected) R.string.apps_select_unselect else R.string.apps_select_select,
                row.label,
            ),
            tint = if (selected) colors.accent else colors.faint,
            modifier = Modifier.size(XgSize.iconSmall),
        )
        Spacer(Modifier.width(XgSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.label,
                style = XgType.rowTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.packageName,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AbiBadge(label = row.abiLabel)
    }
}

@Composable
private fun BatchPreflightContent(batch: BatchUiState, onRetry: () -> Unit) {
    val preflight = batch.preflight
    val error = batch.preflightError
    Column {
    when {
        batch.preflightLoading -> XgLoadingSkeleton(lines = 3)
        error != null -> XgErrorState(code = error.code, message = error.message, onRetry = onRetry)
        preflight == null -> XgNote(text = stringResource(R.string.apps_batch_preflight_waiting))
        else -> {
            Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm)) {
                XgBadge(
                    text = stringResource(R.string.apps_batch_targets, preflight.targets.size),
                    tone = XgTone.Accent,
                )
                XgBadge(
                    text = stringResource(R.string.apps_batch_estimate, Formatters.duration(preflight.estimatedMs)),
                    tone = XgTone.Muted,
                )
            }
            val blocking = preflight.issues.count { it.blocking }
            if (preflight.issues.isEmpty()) {
                XgNote(text = stringResource(R.string.apps_batch_preflight_ok), tone = XgTone.Ok)
            } else {
                XgPanel {
                    preflight.issues.forEachIndexed { index, issue ->
                        PreflightIssueLine(issue = issue)
                        if (index != preflight.issues.lastIndex) XgHairline()
                    }
                }
            }
            if (!preflight.canRun || blocking > 0) {
                XgNote(text = stringResource(R.string.apps_batch_blocked, blocking), tone = XgTone.Danger)
            }
            XgTextAction(text = stringResource(R.string.apps_batch_recheck), onClick = onRetry)
        }
    }
    }
}

@Composable
private fun PreflightIssueLine(issue: PreflightIssue) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = XgSize.touch).padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        XgBadge(
            text = stringResource(
                if (issue.blocking) R.string.apps_batch_issue_blocking else R.string.apps_batch_issue_warn,
            ),
            tone = if (issue.blocking) XgTone.Danger else XgTone.Warn,
        )
        Column(modifier = Modifier.weight(1f).padding(start = XgSpacing.sm)) {
            val target = if (issue.packageName == AppActions.GLOBAL_ISSUE_TARGET) {
                stringResource(R.string.apps_batch_global_issue)
            } else {
                issue.packageName
            }
            XgMono(text = target, style = XgType.monoSmall, color = colors.muted)
            Text(text = issue.reason, style = XgType.note, color = colors.faint)
        }
    }
}

@Composable
private fun BatchExecutingContent(batch: BatchUiState) {
    val progress = batch.progress
    Column {
    XgTaskProgress(
        title = stringResource(R.string.apps_batch_running_title),
        progress = progress?.let { if (it.total <= 0) null else it.done.toFloat() / it.total.toFloat() },
        meta = "${progress?.done ?: 0}/${progress?.total ?: batch.selected.size}",
    )
    progress?.current?.let { current ->
        XgNote(text = stringResource(R.string.apps_batch_current, current))
    }
    if (progress == null) {
        XgNote(text = stringResource(R.string.apps_batch_starting))
    }
    }
}

@Composable
private fun BatchReceiptHeader(batch: BatchUiState, items: List<BatchItemResult>) {
    val receipt = batch.receipt
    val ok = receipt?.ok ?: items.count { it.status == STATUS_OK }
    val failed = receipt?.failed ?: items.count { it.status == STATUS_FAILED }
    val skipped = receipt?.skipped ?: items.count { it.status == STATUS_SKIPPED }
    Column {
        batch.receiptError?.let { error -> XgNote(text = error, tone = XgTone.Danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm)) {
            XgBadge(text = stringResource(R.string.apps_batch_ok_count, ok), tone = XgTone.Ok)
            XgBadge(text = stringResource(R.string.apps_batch_failed_count, failed), tone = XgTone.Danger)
            XgBadge(text = stringResource(R.string.apps_batch_skipped_count, skipped), tone = XgTone.Muted)
        }
    }
}

@Composable
private fun BatchResultRow(
    item: BatchItemResult,
    labels: List<AppRowUi>,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalXgColors.current
    val label = labels.firstOrNull { it.packageName == item.packageName }?.label
    val hasMessage = !item.message.isNullOrBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = hasMessage, onClick = onToggle)
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label ?: item.packageName,
                    style = XgType.rowTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.packageName,
                    style = XgType.monoSmall,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BatchItemBadge(status = item.status)
        }
        if (hasMessage && expanded) {
            XgMono(
                text = item.message.orEmpty(),
                style = XgType.monoSmall,
                color = if (item.status == STATUS_FAILED) colors.danger else colors.muted,
            )
        }
        if (hasMessage) {
            XgTextAction(
                text = stringResource(
                    if (expanded) R.string.apps_batch_collapse_stderr else R.string.apps_batch_expand_stderr,
                ),
                onClick = onToggle,
                tone = XgTone.Muted,
            )
        }
    }
}

@Composable
private fun BatchUndoBar(batch: BatchUiState, onUndo: () -> Unit) {
    val receipt = batch.receipt
    if (receipt == null || receipt.ok <= 0) return
    val undoResult = batch.undoResult
    val undoError = batch.undoError
    when {
        undoResult != null -> XgNote(text = undoResult, tone = XgTone.Ok)
        undoError != null -> XgNote(text = undoError, tone = XgTone.Danger)
        else -> XgTextAction(
            text = if (batch.undoRunning) {
                stringResource(R.string.apps_batch_undo_running)
            } else {
                stringResource(R.string.apps_batch_undo)
            },
            onClick = onUndo,
            enabled = !batch.undoRunning,
            tone = XgTone.Danger,
        )
    }
}

private fun batchActionLabelRes(action: BatchAction): Int = when (action) {
    BatchAction.FREEZE -> R.string.apps_batch_action_freeze
    BatchAction.UNFREEZE -> R.string.apps_batch_action_unfreeze
    BatchAction.UNINSTALL -> R.string.apps_batch_action_uninstall
    BatchAction.DISABLE_AUTOSTART -> R.string.apps_batch_action_autostart
    BatchAction.CHANGE_ANDROID_ID -> R.string.apps_batch_action_android_id
}

private fun buildBatchResultText(action: BatchAction, items: List<BatchItemResult>): String = buildString {
    append("action=").append(action.name).append('\n')
    items.forEach { item ->
        append(item.packageName).append('\t').append(item.status)
        item.message?.let { message -> append('\t').append(message) }
        append('\n')
    }
}

@Composable
private fun XgSpacingSm() {
    Spacer(Modifier.heightIn(min = 8.dp))
}

private const val STATUS_OK = "ok"
private const val STATUS_FAILED = "failed"
private const val STATUS_SKIPPED = "skipped"

