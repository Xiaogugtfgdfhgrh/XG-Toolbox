package com.xg.toolbox.feature.system.ui.perf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.feature.system.R

@Composable
fun PerfSection(modifier: Modifier = Modifier, headerVisible: Boolean = true) {
    val viewModel: PerfViewModel = hiltViewModel()
    PerfSection(viewModel = viewModel, modifier = modifier, headerVisible = headerVisible)
}

@Composable
internal fun PerfSection(
    viewModel: PerfViewModel,
    modifier: Modifier = Modifier,
    headerVisible: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val data = state.data

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(XgSpacing.md),
    ) {
        if (headerVisible) {
            XgSectionHeader(
                title = stringResource(R.string.sys_tune_perf_title),
                meta = stringResource(R.string.sys_tune_perf_meta),
            )
        }

        if (data != null) {
            if (state.readOnly) {
                ReadOnlyBanner()
            }
            state.notice?.let { notice ->
                PerfNoticeRow(notice = notice, onDismiss = viewModel::dismissNotice)
            }
            PerfSummaryPanel(data = data)
            PerfControlsPanel(
                data = data,
                state = state,
                onSelectChoice = viewModel::selectChoice,
                onApply = viewModel::requestApply,
                onMemoryCompression = viewModel::setMemoryCompression,
                onThermalGuard = viewModel::setThermalGuard,
            )
            RestoreDefaultsPanel(
                enabled = !state.readOnly && !state.busy,
                onRestore = viewModel::requestRestoreDefaults,
            )
        } else {
            when (state.phase) {
                PerfPhase.ERROR -> XgErrorState(
                    code = state.errorCode,
                    message = state.errorMessage ?: stringResource(R.string.sys_tune_error_unknown),
                    onRetry = viewModel::refresh,
                )

                PerfPhase.EMPTY -> XgEmptyState(
                    title = stringResource(R.string.sys_tune_perf_empty),
                    actionText = stringResource(R.string.sys_tune_action_redetect),
                    onAction = viewModel::refresh,
                )

                else -> XgLoadingSkeleton(lines = 3)
            }
        }
    }

    PerfConfirmDialogs(confirm = state.confirm, viewModel = viewModel)
}