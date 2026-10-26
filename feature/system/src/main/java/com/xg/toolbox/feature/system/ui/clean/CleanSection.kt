package com.xg.toolbox.feature.system.ui.clean

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgIconButton
import com.xg.toolbox.core.ui.component.XgKeyValueRow
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgNum
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgPrimaryButton
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgTaskProgress
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.clean.CleanCategory
import com.xg.toolbox.feature.system.ui.optimize.SysCheckBox
import com.xg.toolbox.feature.system.ui.optimize.SystemRootBadge
import kotlin.math.roundToInt

private const val SAMPLE_PATH_LIMIT = 20

internal fun LazyListScope.cleanPageItems(
    state: CleanUiState,
    onScan: () -> Unit,
    onToggle: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onClean: () -> Unit,
) {
    item(key = "clean-root") {
        SystemRootBadge(rootState = state.rootState)
    }
    item(key = "clean-scan") {
        CleanScanPanel(state = state, onScan = onScan)
    }
    item(key = "clean-results") {
        XgContentTransition(
            stateKey = state.scan ?: 0,
            loading = state.loading && state.scan == null,
            error = state.errorMessage.takeIf { state.scan == null },
            onRetry = onScan,
            skeletonLines = 4,
            initiallyLoaded = state.scan != null,
        ) {
            when {
                state.scan == null -> Unit
                state.categories.isEmpty() -> XgEmptyState(
                    title = stringResource(R.string.sys_core_clean_empty),
                    actionText = stringResource(R.string.sys_core_action_rescan),
                    onAction = onScan,
                )

                else -> CleanResultsPanel(
                    state = state,
                    onToggle = onToggle,
                    onToggleExpanded = onToggleExpanded,
                    onSelectAll = onSelectAll,
                    onClearSelection = onClearSelection,
                    onClean = onClean,
                )
            }
        }
    }
}

@Composable
private fun CleanScanPanel(
    state: CleanUiState,
    onScan: () -> Unit,
) {
    val scan = state.scan
    XgPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (scan == null) {
                        stringResource(R.string.sys_core_clean_not_scanned)
                    } else {
                        stringResource(R.string.sys_core_clean_scanned)
                    },
                    style = XgType.cardTitle,
                    color = LocalXgColors.current.ink,
                )
                Spacer(Modifier.height(2.dp))
                XgNote(text = stringResource(R.string.sys_core_clean_hint))
                if (scan != null) {
                    Spacer(Modifier.height(2.dp))
                    XgNote(
                        text = stringResource(
                            R.string.sys_core_clean_total,
                            Formatters.bytes(scan.totalBytes),
                        ),
                    )
                }
            }
            Spacer(Modifier.width(XgSpacing.sm))
            XgPrimaryButton(
                text = when {
                    state.scanning -> stringResource(R.string.sys_core_clean_scanning)
                    scan == null -> stringResource(R.string.sys_core_action_scan)
                    else -> stringResource(R.string.sys_core_action_rescan)
                },
                onClick = onScan,
                enabled = !state.scanning && !state.cleaning,
            )
        }
        if (state.scanning) {
            Spacer(Modifier.height(XgSpacing.md))
            XgTaskProgress(
                title = stringResource(R.string.sys_core_clean_scan_title),
                progress = state.scanProgress,
                meta = stringResource(
                    R.string.sys_core_percent,
                    (state.scanProgress * 100f).roundToInt(),
                ),
            )
        } else if (state.scan == null && state.loading) {
            Spacer(Modifier.height(XgSpacing.md))
            XgLoadingSkeleton(lines = 2)
        }
    }
}

@Composable
private fun CleanResultsPanel(
    state: CleanUiState,
    onToggle: (String) -> Unit,
    onToggleExpanded: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onClean: () -> Unit,
) {
    val allSelected = state.categories.isNotEmpty() &&
        state.categories.all { it.id in state.selectedIds }
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_core_clean_results),
            meta = stringResource(
                R.string.sys_core_clean_results_meta,
                state.selectedIds.size,
                Formatters.bytes(state.scan?.totalBytes ?: 0L),
            ),
            trailing = {
                XgTextAction(
                    text = if (allSelected) {
                        stringResource(R.string.sys_core_action_clear_selection)
                    } else {
                        stringResource(R.string.sys_core_action_select_all)
                    },
                    onClick = if (allSelected) onClearSelection else onSelectAll,
                    enabled = !state.cleaning && !state.scanning,
                )
            },
        )
        Spacer(Modifier.height(XgSpacing.xs))
        XgHairline()

        state.categories.forEachIndexed { index, category ->
            CleanCategoryRow(
                category = category,
                selected = category.id in state.selectedIds,
                expanded = category.id in state.expandedIds,
                enabled = !state.cleaning && !state.scanning,
                onToggle = { onToggle(category.id) },
                onToggleExpanded = { onToggleExpanded(category.id) },
            )
            if (index < state.categories.lastIndex) {
                XgHairline()
            }
        }

        Spacer(Modifier.height(XgSpacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                XgNote(
                    text = stringResource(
                        R.string.sys_core_clean_selected_total,
                        state.selectedIds.size,
                        Formatters.bytes(state.selectedBytes),
                    ),
                )
            }
            Spacer(Modifier.width(XgSpacing.sm))
            XgPrimaryButton(
                text = if (state.cleaning) {
                    stringResource(R.string.sys_core_clean_running)
                } else {
                    stringResource(R.string.sys_core_clean_run)
                },
                onClick = onClean,
                enabled = state.canClean,
            )
        }

        if (state.cleaning) {
            Spacer(Modifier.height(XgSpacing.md))
            XgTaskProgress(
                title = stringResource(R.string.sys_core_clean_progress),
                progress = state.cleanProgress,
                meta = stringResource(
                    R.string.sys_core_percent,
                    (state.cleanProgress * 100f).roundToInt(),
                ),
            )
            Spacer(Modifier.height(XgSpacing.xs))
            XgNote(
                text = stringResource(
                    R.string.sys_core_clean_reported_total,
                ) + " · " + Formatters.bytes(state.reportedFreedBytes),
            )
        }

        if (state.finished) {
            Spacer(Modifier.height(XgSpacing.sm))
            XgHairline()
            Spacer(Modifier.height(XgSpacing.xs))
            CleanReport(state = state)
        }
    }
}

@Composable
private fun CleanCategoryRow(
    category: CleanCategory,
    selected: Boolean,
    expanded: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onToggleExpanded: () -> Unit,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = selected,
                enabled = enabled,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .heightIn(min = XgSize.rowMin)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        SysCheckBox(
            checked = selected,
            onCheckedChange = null,
            enabled = enabled,
        )
        Spacer(Modifier.width(XgSpacing.xs))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = category.name,
                    style = XgType.rowTitle,
                    color = colors.ink,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(XgSpacing.sm))
                XgNum(text = Formatters.bytes(category.bytes), style = XgType.monoValue)
                XgIconButton(
                    icon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) {
                        stringResource(R.string.sys_core_clean_collapse_paths)
                    } else {
                        stringResource(R.string.sys_core_clean_expand_paths, SAMPLE_PATH_LIMIT)
                    },
                    onClick = onToggleExpanded,
                )
            }
            XgMono(
                text = category.path,
                style = XgType.monoSmall,
                color = colors.faint,
            )
            Spacer(Modifier.height(2.dp))
            XgNote(
                text = stringResource(R.string.sys_core_clean_files, category.fileCount),
            )
            if (expanded) {
                Spacer(Modifier.height(XgSpacing.sm))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(XgRadius.small))
                        .background(colors.inset)
                        .padding(horizontal = XgSpacing.sm, vertical = 6.dp),
                ) {
                    val samples = category.samplePaths.take(SAMPLE_PATH_LIMIT)
                    if (samples.isEmpty()) {
                        XgNote(text = stringResource(R.string.sys_core_clean_no_samples))
                    } else {
                        samples.forEach { path ->
                            XgMono(
                                text = path,
                                style = XgType.monoSmall,
                                color = colors.muted,
                            )
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                    if (category.fileCount > SAMPLE_PATH_LIMIT) {
                        Spacer(Modifier.height(2.dp))
                        XgNote(
                            text = stringResource(
                                R.string.sys_core_clean_more_paths,
                                SAMPLE_PATH_LIMIT,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanReport(state: CleanUiState) {
    val verified = state.duVerifiedBytes
    XgKeyValueRow(
        label = if (verified != null) {
            stringResource(R.string.sys_core_clean_verified_total)
        } else {
            stringResource(R.string.sys_core_clean_reported_total)
        },
        value = Formatters.bytes(verified ?: state.reportedFreedBytes),
        copyable = false,
    )
    val before = state.duBeforeBytes
    val after = state.duAfterBytes
    if (before != null && after != null) {
        XgNote(
            text = stringResource(
                R.string.sys_core_clean_du_line,
                Formatters.bytes(before),
                Formatters.bytes(after),
            ),
        )
    }
    if (verified != null) {
        XgNote(
            text = stringResource(
                R.string.sys_core_clean_du_diff,
                Formatters.bytes(verified),
            ),
        )
    }
    if (state.duError != null) {
        XgNote(
            text = stringResource(R.string.sys_core_clean_du_failed, state.duError.orEmpty()),
            tone = XgTone.Danger,
        )
    }
    if (!state.cleanMessage.isNullOrBlank()) {
        Spacer(Modifier.height(2.dp))
        XgMono(
            text = state.cleanMessage.orEmpty(),
            style = XgType.monoSmall,
            color = LocalXgColors.current.faint,
        )
    }
    if (state.errorMessage != null) {
        Spacer(Modifier.height(2.dp))
        XgNote(text = state.errorMessage.orEmpty(), tone = XgTone.Danger)
    }
}
