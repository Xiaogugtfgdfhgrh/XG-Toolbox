package com.xg.toolbox.feature.system.ui.optimize

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.feature.system.R

@Composable
fun OptimizePage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: OptimizeViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LoadAfterTransition { viewModel.ensureLoaded() }

    Box(modifier = modifier.fillMaxWidth()) {
        XgSubPageScaffold(
            title = stringResource(R.string.sys_core_optimize_title),
            meta = stringResource(R.string.sys_core_optimize_meta),
            onBack = onBack,
            actions = {
                XgTextAction(
                    text = stringResource(R.string.sys_core_action_rescan),
                    onClick = viewModel::scan,
                    enabled = !state.loading && !state.running,
                )
            },
        ) {
            optimizePageItems(
                state = state,
                onRescan = viewModel::scan,
                onToggle = viewModel::toggleSelection,
                onSelectAll = viewModel::selectAll,
                onClearSelection = viewModel::clearSelection,
                onRun = viewModel::runSelected,
            )
        }
    }
}
