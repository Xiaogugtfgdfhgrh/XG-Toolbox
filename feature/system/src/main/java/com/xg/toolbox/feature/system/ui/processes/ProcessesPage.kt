package com.xg.toolbox.feature.system.ui.processes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.XgConfirmDialog
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.processes.ProcessGroup
import com.xg.toolbox.feature.system.ui.optimize.LoadAfterTransition

@Composable
fun ProcessesPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProcessesViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var pendingStop by remember { mutableStateOf<StopRequest?>(null) }

    LoadAfterTransition { viewModel.ensureLoaded() }

    Box(modifier = modifier.fillMaxWidth()) {
        XgSubPageScaffold(
            title = stringResource(R.string.sys_core_processes_title),
            meta = stringResource(
                R.string.sys_core_processes_meta,
                state.processes.size,
                ProcessGroup.values().size,
            ),
            onBack = onBack,
            actions = {
                XgTextAction(
                    text = stringResource(R.string.sys_core_action_reload),
                    onClick = viewModel::load,
                    enabled = !state.stopping && !state.refreshing,
                )
            },
        ) {
            processesPageItems(
                state = state,
                viewModel = viewModel,
                onLoad = viewModel::load,
                onSetGroup = viewModel::setGroup,
                onSelectAll = viewModel::selectAllVisible,
                onToggleSelection = viewModel::toggleSelection,
                onToggleExpanded = viewModel::toggleExpanded,
                onRequestStop = { items, force -> pendingStop = StopRequest(items, force) },
            )
        }
    }

    val request = pendingStop
    if (request != null) {
        val targetText = if (request.items.size == 1) {
            request.items.first().name
        } else {
            stringResource(R.string.sys_core_processes_confirm_many, request.items.size)
        }
        XgConfirmDialog(
            title = stringResource(R.string.sys_core_processes_confirm_title),
            body = stringResource(
                if (request.force) {
                    R.string.sys_core_processes_confirm_force_body
                } else {
                    R.string.sys_core_processes_confirm_body
                },
                targetText,
            ),
            confirmText = stringResource(
                if (request.force) {
                    R.string.sys_core_processes_confirm_force_action
                } else {
                    R.string.sys_core_processes_confirm_action
                },
            ),
            dismissText = stringResource(R.string.sys_core_processes_cancel),
            danger = true,
            onConfirm = {
                pendingStop = null
                if (request.items.size == 1) {
                    viewModel.stopSingle(request.items.first(), request.force)
                } else {
                    viewModel.stopSelected()
                }
            },
            onDismiss = { pendingStop = null },
        )
    }
}
