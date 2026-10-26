package com.xg.toolbox.feature.system.ui.perf

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
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
fun PerfPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: PerfViewModel = hiltViewModel()
    val data by viewModel.state
        .map { it.data }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = null)
    val phase by viewModel.state
        .map { it.phase }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = PerfPhase.LOADING)
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
    val confirm by viewModel.state
        .map { it.confirm }
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
            title = stringResource(R.string.sys_tune_perf_title),
            meta = stringResource(R.string.sys_tune_perf_meta),
            onBack = onBack,
        ) {
            item(key = "perf-summary", contentType = "perf-summary") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(XgSpacing.md),
                ) {
                    XgContentTransition(
                        stateKey = currentData ?: phase,
                        loading = currentData == null && phase == PerfPhase.LOADING,
                        error = if (currentData == null && phase == PerfPhase.ERROR) {
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
                        notice?.let { notice -> PerfNoticeRow(notice = notice, onDismiss = viewModel::dismissNotice) }
                        if (currentData != null) {
                            PerfSummaryPanel(data = currentData)
                        } else {
                            XgEmptyState(
                                title = stringResource(R.string.sys_tune_perf_empty),
                                actionText = stringResource(R.string.sys_tune_action_redetect),
                                onAction = viewModel::refresh,
                            )
                        }
                    }
                }
            }
            if (currentData != null) {
                items(
                    items = currentData.controls,
                    key = { control -> control.id },
                    contentType = { "perf-control" },
                ) { control ->
                    PerfControlCard(
                        control = control,
                        readOnly = readOnly,
                        busy = busy,
                        onRequestApply = { choiceId ->
                            viewModel.requestApplyChoice(control.id, choiceId)
                        },
                    )
                }
                item(key = "perf-memory", contentType = "perf-toggle") {
                    MemoryCompressionCard(
                        data = currentData,
                        enabled = editable,
                        onToggle = viewModel::setMemoryCompression,
                    )
                }
                item(key = "perf-thermal", contentType = "perf-toggle") {
                    ThermalGuardCard(
                        data = currentData,
                        enabled = editable,
                        onToggle = viewModel::setThermalGuard,
                    )
                }
                item(key = "perf-restore", contentType = "perf-action") {
                    RestoreDefaultsPanel(
                        enabled = editable,
                        onRestore = viewModel::requestRestoreDefaults,
                    )
                }
            }
        }
    }

    PerfConfirmDialogs(confirm = confirm, viewModel = viewModel)
}