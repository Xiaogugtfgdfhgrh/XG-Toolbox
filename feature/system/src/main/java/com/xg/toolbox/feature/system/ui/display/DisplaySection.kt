package com.xg.toolbox.feature.system.ui.display

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
fun DisplaySection(modifier: Modifier = Modifier, headerVisible: Boolean = true) {
    val viewModel: DisplayViewModel = hiltViewModel()
    DisplaySection(viewModel = viewModel, modifier = modifier, headerVisible = headerVisible)
}

@Composable
internal fun DisplaySection(
    viewModel: DisplayViewModel,
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
                title = stringResource(R.string.sys_tune_display_title),
                meta = stringResource(R.string.sys_tune_display_meta),
            )
        }

        if (data != null) {
            if (state.readOnly) {
                ReadOnlyBanner()
            }
            state.notice?.let { notice ->
                DisplayNoticeRow(notice = notice, onDismiss = viewModel::dismissNotice)
            }
            DisplaySummaryPanel(data = data)
            DisplayControlsPanel(
                data = data,
                state = state,
                onSelectHz = viewModel::selectHz,
                onSelectSize = viewModel::selectSize,
                onUpdateDensity = viewModel::updateDensity,
                onUpdateColorTemperature = viewModel::updateColorTemperature,
                onApplySize = viewModel::applySize,
                onResetSize = viewModel::resetSize,
                onApplyDensity = viewModel::applyDensity,
                onResetDensity = viewModel::resetDensity,
                onApplyRefresh = viewModel::applyRefresh,
                onToggleForceHighRefresh = viewModel::toggleForceHighRefresh,
                onTouchSample = viewModel::setTouchSampleHz,
                onDcDimming = viewModel::setDcDimming,
                onApplyColorTemperature = viewModel::applyColorTemperature,
                onNightMode = viewModel::setNightMode,
            )
            RestoreDefaultsPanel(
                enabled = !state.readOnly && !state.busy,
                onRestore = viewModel::restoreDefaults,
            )
            DisplayReadbackPanel(data = data, lastOperation = state.lastOperation)
        } else {
            when (state.phase) {
                DisplayPhase.ERROR -> XgErrorState(
                    code = state.errorCode,
                    message = state.errorMessage ?: stringResource(R.string.sys_tune_error_unknown),
                    onRetry = viewModel::refresh,
                )

                DisplayPhase.EMPTY -> XgEmptyState(
                    title = stringResource(R.string.sys_tune_display_empty),
                    actionText = stringResource(R.string.sys_tune_action_redetect),
                    onAction = viewModel::refresh,
                )

                else -> XgLoadingSkeleton(lines = 3)
            }
        }
    }
}