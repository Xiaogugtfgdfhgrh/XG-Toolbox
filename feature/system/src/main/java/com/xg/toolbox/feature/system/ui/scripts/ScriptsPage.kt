package com.xg.toolbox.feature.system.ui.scripts

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
import com.xg.toolbox.feature.system.ui.optimize.LoadAfterTransition

@Composable
fun ScriptsPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ScriptsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LoadAfterTransition { viewModel.ensureLoaded() }

    Box(modifier = modifier.fillMaxWidth()) {
        XgSubPageScaffold(
            title = stringResource(R.string.sys_core_scripts_title),
            meta = stringResource(R.string.sys_core_scripts_meta, state.scripts.size),
            onBack = onBack,
            actions = {
                XgTextAction(
                    text = stringResource(R.string.sys_core_action_reload),
                    onClick = viewModel::reload,
                    enabled = !state.loading && state.runningId == null,
                )
            },
        ) {
            scriptsPageItems(
                state = state,
                onRun = viewModel::run,
                onReload = viewModel::reload,
                onClearLog = viewModel::clearLog,
            )
        }
    }
}
