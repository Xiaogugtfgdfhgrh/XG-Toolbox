package com.xg.toolbox.feature.system.ui.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.XgSubPage
import com.xg.toolbox.feature.system.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun TerminalPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: TerminalViewModel = hiltViewModel()
    val running by viewModel.state
        .map { it.running }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = false)
    val phase by viewModel.state
        .map { it.phase }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = TerminalPhase.LOADING)
    val meta = when {
        phase == TerminalPhase.LOADING -> stringResource(R.string.sys_tune_term_status_starting)
        running -> stringResource(R.string.sys_tune_term_status_running)
        else -> stringResource(R.string.sys_tune_term_status_stopped)
    }

    Box(modifier = modifier.fillMaxSize()) {
        XgSubPage(
            title = stringResource(R.string.sys_tune_term_title),
            meta = meta,
            onBack = onBack,
        ) {
            TerminalSection(
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                headerVisible = false,
                fillHeight = true,
            )
        }
    }
}