package com.xg.toolbox.feature.system.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.device.MetricsSnapshot
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgIconButton
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgPrimaryButton
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgStatusChip
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.system.R

@Composable
internal fun TerminalReadOnlyBanner() {
    val colors = LocalXgColors.current
    XgPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XgStatusChip(
                text = stringResource(R.string.sys_tune_term_readonly),
                tone = XgTone.Warn,
            )
            Spacer(Modifier.width(XgSpacing.sm))
            Text(
                text = stringResource(R.string.sys_tune_term_readonly_note),
                style = XgType.note,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun TerminalStatusPanel(
    state: TerminalUiState,
    metrics: MetricsSnapshot?,
    onRetry: () -> Unit,
    onInterrupt: () -> Unit,
) {
    XgPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XgStatusChip(
                text = if (state.running) {
                    stringResource(R.string.sys_tune_term_status_running)
                } else {
                    stringResource(R.string.sys_tune_term_status_stopped)
                },
                tone = if (state.running) XgTone.Ok else XgTone.Muted,
            )
            if (state.readOnly) {
                Spacer(Modifier.width(XgSpacing.sm))
                XgBadge(
                    text = stringResource(R.string.sys_tune_term_readonly),
                    tone = XgTone.Warn,
                )
            }
            Spacer(Modifier.weight(1f))
            if (!state.running) {
                XgTextAction(
                    text = stringResource(R.string.sys_tune_term_retry),
                    onClick = onRetry,
                )
            }
        }
        Spacer(Modifier.height(XgSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.lg)) {
            TerminalMetric(
                label = stringResource(R.string.sys_tune_term_metric_cpu),
                value = metrics?.cpuPercent?.let { value ->
                    "${Formatters.percent(value)}%"
                } ?: stringResource(R.string.sys_tune_term_metric_unknown),
                modifier = Modifier.weight(1f),
            )
            TerminalMetric(
                label = stringResource(R.string.sys_tune_term_metric_mem),
                value = memoryText(metrics),
                modifier = Modifier.weight(1f),
            )
            TerminalMetric(
                label = stringResource(R.string.sys_tune_term_metric_temp),
                value = metrics?.tempC?.let { value ->
                    "${Formatters.percent(value, 1)} °C"
                } ?: stringResource(R.string.sys_tune_term_metric_unknown),
                modifier = Modifier.weight(1f),
            )
        }
        if (state.waitingTooLong) {
            Spacer(Modifier.height(XgSpacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                XgStatusChip(
                    text = stringResource(R.string.sys_tune_term_waiting),
                    tone = XgTone.Warn,
                )
                Spacer(Modifier.width(XgSpacing.sm))
                Text(
                    text = stringResource(R.string.sys_tune_term_waiting_note),
                    style = XgType.note,
                    color = LocalXgColors.current.muted,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(XgSpacing.xs))
                XgTextAction(
                    text = stringResource(R.string.sys_tune_term_interrupt),
                    onClick = onInterrupt,
                )
            }
        }
    }
}

@Composable
internal fun TerminalMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    Column(modifier = modifier) {
        Text(
            text = label,
            style = XgType.metricName,
            color = colors.faint,
            maxLines = 1,
        )
        Spacer(Modifier.height(XgSpacing.xs))
        XgMono(
            text = value,
            style = XgType.monoSmall,
            color = colors.ink,
        )
    }
}

@Composable
internal fun memoryText(snapshot: MetricsSnapshot?): String {
    if (snapshot == null || snapshot.memTotalBytes <= 0L) {
        return stringResource(R.string.sys_tune_term_metric_unknown)
    }
    return "${Formatters.bytes(snapshot.memUsedBytes)} / ${Formatters.bytes(snapshot.memTotalBytes)}"
}

@Composable
internal fun TerminalConsolePanel(
    lines: List<TerminalLineUi>,
    truncated: Boolean,
    truncationText: String,
    selectedLineId: Long?,
    listState: LazyListState,
    fillHeight: Boolean,
    modifier: Modifier = Modifier,
    onSelectLine: (Long?) -> Unit,
    onCopyLine: (TerminalLineUi) -> Unit,
    onDeleteLine: (TerminalLineUi) -> Unit,
    onCopyAll: () -> Unit,
    onClearOutput: () -> Unit,
) {
    val colors = LocalXgColors.current
    XgPanel(modifier = modifier) {
        val consoleHeight = if (fillHeight) {
            Modifier.weight(1f)
        } else {
            Modifier.heightIn(min = 220.dp, max = 360.dp)
        }
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_term_output),
            meta = stringResource(R.string.sys_tune_term_lines, lines.size),
            trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.xs)) {
                    XgTextAction(
                        text = stringResource(R.string.sys_tune_term_copy_all),
                        onClick = onCopyAll,
                        enabled = lines.isNotEmpty(),
                    )
                    XgTextAction(
                        text = stringResource(R.string.sys_tune_term_clear_output),
                        onClick = onClearOutput,
                        enabled = lines.isNotEmpty(),
                        tone = XgTone.Muted,
                    )
                }
            },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .then(consoleHeight)
                .clip(RoundedCornerShape(XgRadius.small))
                .background(colors.console)
                .padding(XgSpacing.md),
            verticalArrangement = Arrangement.spacedBy(XgSpacing.xs),
        ) {
            if (truncated) {
                item(key = "term-truncated") {
                    Text(
                        text = truncationText,
                        style = XgType.terminal,
                        color = terminalConsoleText().copy(alpha = 0.58f),
                    )
                }
            }
            if (lines.isEmpty()) {
                item(key = "term-empty") {
                    Text(
                        text = stringResource(R.string.sys_tune_term_empty),
                        style = XgType.terminal,
                        color = terminalConsoleText().copy(alpha = 0.58f),
                    )
                }
            } else {
                items(lines, key = { it.id }, contentType = { "terminal-line" }) { line ->
                    TerminalConsoleLine(
                        line = line,
                        selected = line.id == selectedLineId,
                        onSelect = { onSelectLine(if (line.id == selectedLineId) null else line.id) },
                        onCopy = { onCopyLine(line) },
                        onDelete = { onDeleteLine(line) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun TerminalConsoleLine(
    line: TerminalLineUi,
    selected: Boolean,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalXgColors.current
    val foreground = terminalLineColor(line.kind)
    val prefix = when (line.kind) {
        TerminalLineKind.COMMAND -> "\$"
        TerminalLineKind.ERROR -> "!"
        TerminalLineKind.SYSTEM -> "·"
        TerminalLineKind.SUCCESS -> "·"
        TerminalLineKind.OUTPUT -> " "
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.surface2.copy(alpha = 0.24f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onSelect),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = prefix,
                style = XgType.terminal,
                color = foreground.copy(alpha = 0.72f),
                modifier = Modifier.padding(end = XgSpacing.xs),
            )
            Text(
                text = line.text,
                style = if (line.kind == TerminalLineKind.COMMAND) {
                    XgType.terminal.copy(fontWeight = FontWeight.SemiBold)
                } else {
                    XgType.terminal
                },
                color = foreground,
                modifier = Modifier.weight(1f),
            )
        }
        if (selected) {
            Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.xs)) {
                XgTextAction(
                    text = stringResource(R.string.sys_tune_term_copy_line),
                    onClick = onCopy,
                    tone = XgTone.Muted,
                )
                XgTextAction(
                    text = stringResource(R.string.sys_tune_term_delete_line),
                    onClick = onDelete,
                    tone = XgTone.Danger,
                )
            }
        }
        Spacer(Modifier.height(XgSpacing.xs))
    }
}

@Composable
internal fun terminalLineColor(kind: TerminalLineKind): Color = when (kind) {
    TerminalLineKind.ERROR -> LocalXgColors.current.danger
    TerminalLineKind.SUCCESS -> LocalXgColors.current.ok
    TerminalLineKind.COMMAND -> terminalConsoleText()
    TerminalLineKind.SYSTEM -> terminalConsoleText().copy(alpha = 0.72f)
    TerminalLineKind.OUTPUT -> terminalConsoleText()
}

@Composable
internal fun terminalConsoleText(): Color {
    val colors = LocalXgColors.current
    return if (colors.isDark) colors.ink else colors.bg
}

@Composable
internal fun QuickCommandsPanel(
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    XgPanel {
        XgPanelHeader(title = stringResource(R.string.sys_tune_term_quick))
        Spacer(Modifier.height(XgSpacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm),
        ) {
            QUICK_COMMANDS.forEach { quick ->
                XgTextAction(
                    text = stringResource(quick.labelRes),
                    onClick = { onPick(quick.command) },
                    enabled = enabled,
                )
            }
        }
    }
}

@Composable
internal fun TerminalInputPanel(
    state: TerminalUiState,
    focusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onClearInput: () -> Unit,
    onInterrupt: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    onClearOutput: () -> Unit,
) {
    val colors = LocalXgColors.current
    val foreground = terminalConsoleText()
    val shape = RoundedCornerShape(XgRadius.small)
    val placeholder = if (state.readOnly) {
        stringResource(R.string.sys_tune_term_input_readonly)
    } else {
        stringResource(R.string.sys_tune_term_input_placeholder)
    }
    XgPanel {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = XgSize.touch)
                .clip(shape)
                .background(colors.console)
                .border(1.dp, colors.lineStrong, shape)
                .padding(horizontal = XgSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\$",
                style = XgType.terminal,
                color = foreground.copy(alpha = 0.72f),
            )
            Spacer(Modifier.width(XgSpacing.xs))
            BasicTextField(
                value = state.input,
                onValueChange = onInputChange,
                enabled = !state.readOnly,
                singleLine = true,
                textStyle = XgType.terminal.copy(color = foreground),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Send,
                    keyboardType = KeyboardType.Ascii,
                ),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when {
                            event.key == Key.DirectionUp -> {
                                onHistoryUp()
                                true
                            }

                            event.key == Key.DirectionDown -> {
                                onHistoryDown()
                                true
                            }

                            event.key == Key.Escape -> {
                                onClearInput()
                                true
                            }

                            event.isCtrlPressed && event.key == Key.L -> {
                                onClearOutput()
                                true
                            }

                            else -> false
                        }
                    }
                    .padding(vertical = XgSpacing.sm),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (state.input.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = XgType.terminal,
                                color = foreground.copy(alpha = 0.48f),
                            )
                        }
                        inner()
                    }
                },
            )
            XgIconButton(
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.sys_tune_term_clear_input),
                onClick = onClearInput,
                enabled = !state.readOnly && state.input.isNotEmpty(),
            )
        }
        Spacer(Modifier.height(XgSpacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XgIconButton(
                icon = Icons.Outlined.KeyboardArrowUp,
                contentDescription = stringResource(R.string.sys_tune_term_history_up),
                onClick = {
                    onHistoryUp()
                    focusRequester.requestFocus()
                },
                enabled = !state.readOnly && state.history.isNotEmpty(),
            )
            XgIconButton(
                icon = Icons.Outlined.KeyboardArrowDown,
                contentDescription = stringResource(R.string.sys_tune_term_history_down),
                onClick = {
                    onHistoryDown()
                    focusRequester.requestFocus()
                },
                enabled = !state.readOnly && state.historyIndex != null,
            )
            Spacer(Modifier.weight(1f))
            XgGhostButton(
                text = stringResource(R.string.sys_tune_term_interrupt),
                onClick = onInterrupt,
                enabled = state.running,
            )
            Spacer(Modifier.width(XgSpacing.sm))
            XgPrimaryButton(
                text = stringResource(R.string.sys_tune_term_send),
                onClick = onSend,
                enabled = !state.readOnly && state.input.isNotBlank() && state.running,
            )
        }
        if (!state.running) {
            Spacer(Modifier.height(XgSpacing.xs))
            XgNote(
                text = stringResource(R.string.sys_tune_term_not_running),
                tone = XgTone.Warn,
            )
        }
        if (state.readOnly) {
            Spacer(Modifier.height(XgSpacing.xs))
            XgNote(
                text = stringResource(R.string.sys_tune_term_readonly_blocked),
                tone = XgTone.Warn,
            )
        }
    }
}

internal data class QuickCommand(
    val labelRes: Int,
    val command: String,
)

internal val QUICK_COMMANDS = listOf(
    QuickCommand(R.string.sys_tune_term_quick_su, "su"),
    QuickCommand(R.string.sys_tune_term_quick_uid, "id"),
    QuickCommand(R.string.sys_tune_term_quick_model, "getprop ro.product.model"),
    QuickCommand(R.string.sys_tune_term_quick_storage, "df -h /data"),
    QuickCommand(R.string.sys_tune_term_quick_battery, "dumpsys battery"),
    QuickCommand(R.string.sys_tune_term_quick_packages, "pm list packages -3"),
    QuickCommand(R.string.sys_tune_term_quick_process, "ps -A | head"),
)