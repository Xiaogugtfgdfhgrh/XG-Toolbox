package com.xg.toolbox.feature.system.ui.scripts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgConsoleColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.scripts.ScriptInfo
import com.xg.toolbox.feature.system.domain.scripts.ScriptLine
import com.xg.toolbox.feature.system.domain.scripts.ScriptResult
import com.xg.toolbox.feature.system.ui.optimize.SystemRootBadge

private const val LOG_VISIBLE_LINES = 80

internal fun LazyListScope.scriptsPageItems(
    state: ScriptsUiState,
    onRun: (String) -> Unit,
    onReload: () -> Unit,
    onClearLog: () -> Unit,
) {
    item(key = "scripts-root") {
        SystemRootBadge(rootState = state.rootState)
    }
    if (state.scripts.isNotEmpty() && state.loadErrorMessage != null) {
        item(key = "scripts-load-error") {
            XgNote(text = state.loadErrorMessage.orEmpty(), tone = XgTone.Danger)
        }
    }
    if (state.installErrorMessage != null && state.scripts.isNotEmpty()) {
        item(key = "scripts-install-error") {
            XgNote(
                text = stringResource(
                    R.string.sys_core_scripts_install_failed,
                    state.installErrorMessage.orEmpty(),
                ),
                tone = XgTone.Danger,
            )
        }
    }
    item(key = "scripts-list") {
        XgContentTransition(
            stateKey = state.scripts,
            loading = state.loading && state.scripts.isEmpty(),
            error = (state.loadErrorMessage ?: state.installErrorMessage)
                .takeIf { state.scripts.isEmpty() },
            onRetry = onReload,
            skeletonLines = 3,
            initiallyLoaded = state.scripts.isNotEmpty(),
        ) {
            when {
                state.scripts.isEmpty() -> XgEmptyState(
                    title = stringResource(R.string.sys_core_scripts_empty),
                    actionText = stringResource(R.string.sys_core_action_retry),
                    onAction = onReload,
                )

                else -> XgPanel {
                    state.scripts.forEachIndexed { index, script ->
                        ScriptRow(
                            script = script,
                            lastResult = state.lastResults[script.id],
                            running = state.runningId == script.id,
                            enabled = state.canRun,
                            onRun = { onRun(script.id) },
                        )
                        if (index < state.scripts.lastIndex) {
                            XgHairline()
                        }
                    }
                }
            }
        }
    }
    item(key = "scripts-log") {
        ScriptLogPanel(state = state, onClear = onClearLog)
    }
}

@Composable
private fun ScriptRow(
    script: ScriptInfo,
    lastResult: ScriptResult?,
    running: Boolean,
    enabled: Boolean,
    onRun: () -> Unit,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = script.name,
                style = XgType.rowTitle,
                color = colors.ink,
                maxLines = 2,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = script.description,
                style = XgType.rowNote,
                color = colors.faint,
            )
            Spacer(Modifier.height(2.dp))
            XgNote(
                text = stringResource(R.string.sys_core_scripts_estimate, script.estimate),
            )
            if (lastResult != null) {
                Spacer(Modifier.height(2.dp))
                XgNote(
                    text = stringResource(
                        R.string.sys_core_scripts_last_result,
                        lastResultText(lastResult),
                    ),
                    tone = if (lastResult.exitCode == 0) XgTone.Ok else XgTone.Danger,
                )
            }
        }
        Spacer(Modifier.width(XgSpacing.sm))
        if (running) {
            XgBadge(
                text = stringResource(R.string.sys_core_scripts_running),
                tone = XgTone.Accent,
            )
        } else {
            XgGhostButton(
                text = stringResource(R.string.sys_core_scripts_run),
                onClick = onRun,
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun lastResultText(result: ScriptResult): String {
    val freed = result.freedBytes
    return if (result.exitCode == 0 && freed != null) {
        stringResource(
            R.string.sys_core_scripts_result_ok,
            result.exitCode,
            Formatters.bytes(freed),
        )
    } else {
        stringResource(
            R.string.sys_core_scripts_result_exit,
            result.exitCode,
            result.message,
        )
    }
}

@Composable
private fun ScriptLogPanel(
    state: ScriptsUiState,
    onClear: () -> Unit,
) {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_core_scripts_log_title),
            meta = stringResource(R.string.sys_core_scripts_log_meta),
            trailing = {
                XgTextAction(
                    text = stringResource(R.string.sys_core_action_clear),
                    onClick = onClear,
                    enabled = state.lines.isNotEmpty() && state.runningId == null,
                )
            },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .clip(RoundedCornerShape(XgRadius.small))
                .background(LocalXgColors.current.console)
                .padding(horizontal = XgSpacing.sm, vertical = XgSpacing.sm),
        ) {
            if (state.lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.sys_core_scripts_log_empty),
                    style = XgType.terminal,
                    color = XgConsoleColors.dim,
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    state.lines.takeLast(LOG_VISIBLE_LINES).forEach { line ->
                        Text(
                            text = line.text,
                            style = XgType.terminal,
                            color = logLineColor(line),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(XgSpacing.sm))
        ScriptResultLine(state = state)
    }
}

@Composable
private fun ScriptResultLine(state: ScriptsUiState) {
    val result = state.runResult
    val freed = result?.freedBytes ?: state.doneBytes
    when {
        result != null && result.exitCode == 0 -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XgBadge(
                    text = stringResource(R.string.sys_core_optimize_status_ok),
                    tone = XgTone.Ok,
                )
                Spacer(Modifier.width(XgSpacing.sm))
                XgNote(
                    text = if (freed != null) {
                        stringResource(
                            R.string.sys_core_scripts_result_ok,
                            result.exitCode,
                            Formatters.bytes(freed),
                        )
                    } else {
                        stringResource(
                            R.string.sys_core_scripts_result_exit,
                            result.exitCode,
                            result.message,
                        )
                    },
                    tone = XgTone.Ok,
                )
            }
        }

        result != null -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XgBadge(
                    text = stringResource(R.string.sys_core_optimize_status_failed),
                    tone = XgTone.Danger,
                )
                Spacer(Modifier.width(XgSpacing.sm))
                XgNote(
                    text = stringResource(
                        R.string.sys_core_scripts_result_exit,
                        result.exitCode,
                        result.message,
                    ),
                    tone = XgTone.Danger,
                )
            }
        }

        freed != null -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XgBadge(
                    text = stringResource(R.string.sys_core_scripts_marker),
                    tone = XgTone.Ok,
                )
                Spacer(Modifier.width(XgSpacing.sm))
                XgNote(
                    text = stringResource(
                        R.string.sys_core_scripts_result_done,
                        Formatters.bytes(freed),
                    ),
                    tone = XgTone.Ok,
                )
            }
        }

        state.runErrorMessage != null -> {
            XgNote(
                text = stringResource(
                    R.string.sys_core_scripts_run_failed,
                    state.runErrorMessage.orEmpty(),
                ),
                tone = XgTone.Danger,
            )
        }

        state.lines.isNotEmpty() && state.runningId == null -> {
            XgNote(
                text = stringResource(R.string.sys_core_scripts_done_missing),
                tone = XgTone.Warn,
            )
        }
    }

    if (state.doneLine != null) {
        Spacer(Modifier.height(2.dp))
        XgMono(
            text = state.doneLine.orEmpty(),
            style = XgType.monoSmall,
            color = LocalXgColors.current.muted,
        )
    }
}

private fun logLineColor(line: ScriptLine) = when {
    line.failed -> XgConsoleColors.danger
    line.text.contains("XG_DONE") -> XgConsoleColors.ok
    else -> XgConsoleColors.text
}
