package com.xg.toolbox.feature.system.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.feature.system.R

@Composable
fun TerminalSection(
    modifier: Modifier = Modifier,
    headerVisible: Boolean = true,
    fillHeight: Boolean = false,
) {
    val viewModel: TerminalViewModel = hiltViewModel()
    TerminalSection(
        viewModel = viewModel,
        modifier = modifier,
        headerVisible = headerVisible,
        fillHeight = fillHeight,
    )
}

@Composable
internal fun TerminalSection(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
    headerVisible: Boolean = true,
    fillHeight: Boolean = false,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val focusRequester = remember { FocusRequester() }
    val consoleListState = rememberLazyListState()
    var selectedLineId by remember { mutableStateOf<Long?>(null) }

    val atBottom by remember(consoleListState) {
        derivedStateOf {
            val info = consoleListState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 3
        }
    }
    LaunchedEffect(state.lines.size) {
        if (state.lines.isNotEmpty() && atBottom) {
            consoleListState.scrollToItem(state.lines.lastIndex)
        }
    }

    val truncationText = stringResource(R.string.sys_tune_term_truncated)
    val statusMeta = when {
        state.phase == TerminalPhase.LOADING -> stringResource(R.string.sys_tune_term_status_starting)
        state.running -> stringResource(R.string.sys_tune_term_status_running)
        else -> stringResource(R.string.sys_tune_term_status_stopped)
    }

    Column(
        modifier = (if (fillHeight) modifier.fillMaxSize() else modifier.fillMaxWidth()),
        verticalArrangement = Arrangement.spacedBy(XgSpacing.md),
    ) {
        if (headerVisible) {
            XgSectionHeader(
                title = stringResource(R.string.sys_tune_term_title),
                meta = statusMeta,
            )
        }

        if (state.lines.isEmpty() && state.phase == TerminalPhase.LOADING) {
            XgLoadingSkeleton(lines = 3)
            XgNote(text = stringResource(R.string.sys_tune_term_starting_note))
        } else if (state.phase == TerminalPhase.ERROR && state.lines.isEmpty()) {
            XgErrorState(
                code = state.errorCode,
                message = state.errorMessage ?: stringResource(R.string.sys_tune_term_start_failed),
                onRetry = viewModel::retrySession,
            )
        } else {
            if (state.readOnly) {
                TerminalReadOnlyBanner()
            }
            TerminalStatusHost(
                viewModel = viewModel,
                state = state,
                onRetry = viewModel::retrySession,
                onInterrupt = viewModel::interrupt,
            )
            TerminalConsolePanel(
                lines = state.lines,
                truncated = state.truncated,
                truncationText = truncationText,
                selectedLineId = selectedLineId,
                listState = consoleListState,
                fillHeight = fillHeight,
                modifier = if (fillHeight) Modifier.weight(1f) else Modifier,
                onSelectLine = { lineId -> selectedLineId = lineId },
                onCopyLine = { line -> clipboard.setText(AnnotatedString(line.text)) },
                onDeleteLine = { line ->
                    if (selectedLineId == line.id) selectedLineId = null
                    viewModel.deleteLine(line.id)
                },
                onCopyAll = {
                    val text = buildString {
                        if (state.truncated) {
                            appendLine(truncationText)
                        }
                        state.lines.forEach { line ->
                            if (line.kind == TerminalLineKind.COMMAND) append("\$ ")
                            appendLine(line.text)
                        }
                    }
                    clipboard.setText(AnnotatedString(text))
                },
                onClearOutput = {
                    selectedLineId = null
                    viewModel.clearOutput()
                },
            )
            QuickCommandsPanel(
                enabled = !state.readOnly,
                onPick = { command ->
                    viewModel.prefill(command)
                    focusRequester.requestFocus()
                },
            )
            TerminalInputPanel(
                state = state,
                focusRequester = focusRequester,
                onInputChange = viewModel::onInputChange,
                onSend = viewModel::send,
                onClearInput = viewModel::clearInput,
                onInterrupt = viewModel::interrupt,
                onHistoryUp = viewModel::historyUp,
                onHistoryDown = viewModel::historyDown,
                onClearOutput = {
                    selectedLineId = null
                    viewModel.clearOutput()
                },
            )
            XgNote(text = stringResource(R.string.sys_tune_term_footer))
        }
    }
}

@Composable
private fun TerminalStatusHost(
    viewModel: TerminalViewModel,
    state: TerminalUiState,
    onRetry: () -> Unit,
    onInterrupt: () -> Unit,
) {
    val metrics by viewModel.metricsState.collectAsStateWithLifecycle()
    TerminalStatusPanel(
        state = state,
        metrics = metrics,
        onRetry = onRetry,
        onInterrupt = onInterrupt,
    )
}