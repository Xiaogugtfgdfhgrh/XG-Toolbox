package com.xg.toolbox.core.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgConsoleColors
import com.xg.toolbox.core.ui.theme.XgType

enum class XgTerminalKind {
    Command,
    Output,
    Ok,
    Warn,
    Error,
}

data class XgTerminalLine(
    val text: String,
    val kind: XgTerminalKind = XgTerminalKind.Output,
)

@Composable
fun XgTerminalPanel(
    lines: List<XgTerminalLine>,
    modifier: Modifier = Modifier,
    maxLines: Int = 300,
    emptyText: String = "",
) {
    val colors = LocalXgColors.current
    val scrollState = rememberScrollState()
    val visible = lines.takeLast(maxLines.coerceAtLeast(1))
    LaunchedEffect(visible.size) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.console)
            .verticalScroll(scrollState)
            .padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (visible.isEmpty()) {
            Text(
                text = emptyText,
                style = XgType.terminal,
                color = XgConsoleColors.dim,
            )
        } else {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    visible.forEach { line ->
                        val color = when (line.kind) {
                            XgTerminalKind.Command -> XgConsoleColors.command
                            XgTerminalKind.Ok -> XgConsoleColors.ok
                            XgTerminalKind.Warn -> XgConsoleColors.warn
                            XgTerminalKind.Error -> XgConsoleColors.danger
                            XgTerminalKind.Output -> XgConsoleColors.text
                        }
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (line.kind == XgTerminalKind.Command) "$ ${line.text}" else line.text,
                                style = XgType.terminal,
                                color = color,
                            )
                        }
                    }
                }
            }
        }
    }
}
