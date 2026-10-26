package com.xg.toolbox.feature.system.ui.display

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.feature.system.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun DisplayPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: DisplayViewModel = hiltViewModel()
    val data by viewModel.state
        .map { it.data }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = null)
    val phase by viewModel.state
        .map { it.phase }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = DisplayPhase.LOADING)
    val readOnly by viewModel.state
        .map { it.readOnly }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = false)
    val busy by viewModel.state
        .map { it.busy }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = false)
    val notice by viewModel.state
        .map { it.notice }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = null)
    val lastOperation by viewModel.state
        .map { it.lastOperation }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = null)
    val errorMessage by viewModel.state
        .map { it.errorMessage }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = null)
    val currentData = data
    val editable = !readOnly && !busy

    Box(modifier = modifier.fillMaxSize()) {
        XgSubPageScaffold(
            title = stringResource(R.string.sys_tune_display_title),
            meta = stringResource(R.string.sys_tune_display_meta),
            onBack = onBack,
        ) {
            item(key = "display-summary", contentType = "display-summary") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(XgSpacing.md),
                ) {
                    XgContentTransition(
                        stateKey = currentData ?: phase,
                        loading = currentData == null && phase == DisplayPhase.LOADING,
                        error = if (currentData == null && phase == DisplayPhase.ERROR) {
                            errorMessage ?: stringResource(R.string.sys_tune_error_unknown)
                        } else {
                            null
                        },
                        onRetry = viewModel::refresh,
                        initiallyLoaded = currentData != null,
                    ) {
                        if (readOnly) {
                            ReadOnlyBanner()
                        }
                        notice?.let { notice -> DisplayNoticeRow(notice = notice, onDismiss = viewModel::dismissNotice) }
                        if (currentData != null) {
                            DisplaySummaryPanel(data = currentData)
                        } else {
                            XgEmptyState(
                                title = stringResource(R.string.sys_tune_display_empty),
                                actionText = stringResource(R.string.sys_tune_action_redetect),
                                onAction = viewModel::refresh,
                            )
                        }
                    }
                }
            }
            if (currentData != null) {
                item(key = "display-resolution", contentType = "display-panel") {
                    ResolutionCard(
                        data = currentData,
                        editable = editable,
                        onApply = viewModel::applySize,
                        onReset = viewModel::resetSize,
                    )
                }
                item(key = "display-refresh", contentType = "display-panel") {
                    RefreshCard(
                        data = currentData,
                        editable = editable,
                        onApply = viewModel::applyRefresh,
                    )
                }
                if (currentData.touchSampleOptions.isNotEmpty()) {
                    item(key = "display-touch", contentType = "display-panel") {
                        TouchSampleCard(
                            data = currentData,
                            editable = editable,
                            onTouchSample = viewModel::setTouchSampleHz,
                        )
                    }
                }
                item(key = "display-density", contentType = "display-panel") {
                    DensityCard(
                        data = currentData,
                        editable = editable,
                        onApply = viewModel::applyDensity,
                        onReset = viewModel::resetDensity,
                    )
                }
                item(key = "display-dc", contentType = "display-panel") {
                    DcDimmingCard(
                        data = currentData,
                        editable = editable,
                        onDcDimming = viewModel::setDcDimming,
                    )
                }
                if (currentData.colorTemperatureSupported) {
                    item(key = "display-color", contentType = "display-panel") {
                        ColorTemperatureCard(
                            data = currentData,
                            editable = editable,
                            onApply = viewModel::applyColorTemperature,
                        )
                    }
                }
                item(key = "display-night", contentType = "display-panel") {
                    NightModeCard(
                        data = currentData,
                        editable = editable,
                        onNightMode = viewModel::setNightMode,
                    )
                }
                item(key = "display-restore", contentType = "display-action") {
                    RestoreDefaultsPanel(
                        enabled = editable,
                        onRestore = viewModel::restoreDefaults,
                    )
                }
                item(key = "display-readback", contentType = "display-panel") {
                    DisplayReadbackPanel(data = currentData, lastOperation = lastOperation)
                }
            }
        }
    }
}