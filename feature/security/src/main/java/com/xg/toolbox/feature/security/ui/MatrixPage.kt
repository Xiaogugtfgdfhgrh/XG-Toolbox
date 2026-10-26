package com.xg.toolbox.feature.security.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgChoiceRow
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgProgressBar
import com.xg.toolbox.core.ui.component.XgSearchField
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.security.MatrixFilter
import com.xg.toolbox.feature.security.MatrixRowUi
import com.xg.toolbox.feature.security.MatrixUiState
import com.xg.toolbox.feature.security.R
import com.xg.toolbox.feature.security.UiError
import com.xg.toolbox.feature.security.domain.AppOpDefinition
import com.xg.toolbox.feature.security.domain.AppOpState
import kotlinx.coroutines.delay

private val AppColumnWidth = 112.dp

private val MatrixCellWidth = 56.dp

private val MatrixRowMinHeight = XgSize.rowMin + XgSpacing.md

private val MatrixHeaderMinHeight = XgSize.rowMin

private const val SEARCH_DEBOUNCE_MS = 350L

private const val CONTENT_FRAME_DELAY = 2

@Composable
fun SecurityMatrixPage(
    matrix: MatrixUiState,
    appsLoading: Boolean,
    appsError: UiError?,
    rootError: UiError?,
    rootPending: Boolean,
    rootGranted: Boolean,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onFilter: (MatrixFilter) -> Unit,
    onCellClick: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    onRetryMatrix: () -> Unit,
    onRetryApps: () -> Unit,
    onRetryRoot: () -> Unit,
) {
    var contentReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(CONTENT_FRAME_DELAY) { withFrameNanos { } }
        contentReady = true
    }

    XgSubPageScaffold(
        title = stringResource(R.string.sec_page_matrix),
        meta = stringResource(
            R.string.sec_matrix_meta,
            matrix.filteredCount,
            matrix.definitions.size,
        ),
        onBack = onBack,
        actions = {
            XgTextAction(
                text = stringResource(R.string.sec_action_refresh),
                onClick = if (rootError != null) onRetryRoot else onRetryMatrix,
                enabled = !matrix.loading,
            )
        },
    ) {
        if (rootError == null) {
            item(key = "matrix-controls") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MatrixControls(
                        state = matrix,
                        onSearch = onSearch,
                        onFilter = onFilter,
                    )
                    if (matrix.loading && matrix.rows.isNotEmpty()) {
                        Spacer(Modifier.height(XgSpacing.sm))
                        XgProgressBar(progress = null)
                    }
                }
            }
        }
        when {
            rootError != null -> item(key = "matrix-readonly") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    XgErrorState(
                        code = rootError.code,
                        message = rootError.message,
                        onRetry = onRetryRoot,
                    )
                    Spacer(Modifier.height(XgSpacing.xs))
                    XgNote(text = stringResource(R.string.sec_matrix_readonly_message))
                }
            }

            rootPending -> item(key = "matrix-loading") {
                XgPanel { XgLoadingSkeleton(lines = 5) }
            }

            appsError != null && matrix.rows.isEmpty() -> item(key = "matrix-apps-error") {
                XgErrorState(
                    code = appsError.code,
                    message = appsError.message,
                    onRetry = onRetryApps,
                )
            }

            !contentReady -> item(key = "matrix-deferred") {
                XgPanel { XgLoadingSkeleton(lines = 5) }
            }

            matrix.rows.isEmpty() && (appsLoading || !matrix.loaded) -> item(
                key = "matrix-initial-loading",
            ) {
                XgPanel { XgLoadingSkeleton(lines = 5) }
            }

            else -> item(key = "matrix-content") {
                XgContentTransition(
                    stateKey = "matrix-table",
                    loading = matrix.loading && matrix.rows.isEmpty(),
                    error = if (matrix.rows.isEmpty()) matrix.error?.message else null,
                    onRetry = onRetryMatrix,
                    skeletonLines = 5,
                    initiallyLoaded = matrix.rows.isNotEmpty() || matrix.loaded,
                ) {
                    MatrixContent(
                        matrix = matrix,
                        rootGranted = rootGranted,
                        onCellClick = onCellClick,
                        onLoadMore = onLoadMore,
                        onRetry = onRetryMatrix,
                        onClearSearch = { onSearch("") },
                    )
                }
            }
        }
        if (rootError == null) {
            item(key = "matrix-legend") {
                MatrixLegendBlock(
                    showSampleNote = matrix.filter != MatrixFilter.USER && matrix.systemTotal > 0,
                    sampleSize = matrix.systemSampleSize,
                    systemTotal = matrix.systemTotal,
                )
            }
        }
    }
}

@Composable
private fun MatrixControls(
    state: MatrixUiState,
    onSearch: (String) -> Unit,
    onFilter: (MatrixFilter) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf(state.query) }
    val currentOnSearch by rememberUpdatedState(onSearch)
    LaunchedEffect(input) {
        if (input == state.query) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        currentOnSearch(input)
    }
    LaunchedEffect(state.query) {
        if (state.query != input) input = state.query
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        XgSearchField(
            value = input,
            onValueChange = { input = it },
            placeholder = stringResource(R.string.sec_matrix_search),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        val filterLabels = listOf(
            stringResource(R.string.sec_matrix_filter_user),
            stringResource(R.string.sec_matrix_filter_system),
            stringResource(R.string.sec_matrix_filter_all),
        )
        XgChoiceRow(
            options = filterLabels,
            selectedIndex = state.filter.ordinal,
            onSelect = { index -> onFilter(MatrixFilter.entries[index]) },
        )
    }
}

@Composable
private fun MatrixContent(
    matrix: MatrixUiState,
    rootGranted: Boolean,
    onCellClick: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onClearSearch: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (matrix.rows.isNotEmpty() && matrix.error != null) {
            XgNote(text = matrix.error.message, tone = XgTone.Danger)
            Spacer(Modifier.height(XgSpacing.sm))
        }
        when {
            matrix.rows.isEmpty() && matrix.filteredCount == 0 -> XgEmptyState(
                title = stringResource(R.string.sec_matrix_empty),
                actionText = stringResource(R.string.sec_matrix_empty_action),
                onAction = onClearSearch,
            )

            matrix.rows.isEmpty() -> XgEmptyState(
                title = stringResource(R.string.sec_matrix_empty),
                actionText = stringResource(R.string.sec_retry),
                onAction = onRetry,
            )

            else -> {
                MatrixTablePanel(
                    matrix = matrix,
                    rootGranted = rootGranted,
                    onCellClick = onCellClick,
                )
                Spacer(Modifier.height(XgSpacing.sm))
                MatrixFooter(matrix = matrix, onLoadMore = onLoadMore)
            }
        }
    }
}

@Composable
private fun MatrixTablePanel(
    matrix: MatrixUiState,
    rootGranted: Boolean,
    onCellClick: (String, String) -> Unit,
) {
    val tableWidth = AppColumnWidth + MatrixCellWidth * matrix.definitions.size
    val tableScroll = rememberScrollState()
    XgPanel {
        Column(
            modifier = Modifier
                .horizontalScroll(tableScroll)
                .width(tableWidth),
        ) {
            MatrixHeaderRow(
                definitions = matrix.definitions,
                modifier = Modifier.width(tableWidth),
            )
            XgHairline()
            matrix.rows.forEachIndexed { index, row ->
                if (index > 0) XgHairline()
                MatrixRow(
                    row = row,
                    enabled = rootGranted,
                    onCellClick = onCellClick,
                    modifier = Modifier.width(tableWidth),
                )
            }
        }
        Spacer(Modifier.height(XgSpacing.sm))
        XgHairline()
        Spacer(Modifier.height(XgSpacing.sm))
        XgNote(text = stringResource(R.string.sec_matrix_scroll_hint))
        Spacer(Modifier.height(XgSpacing.xs))
        MatrixLegend()
    }
}

@Composable
private fun MatrixHeaderRow(
    definitions: List<AppOpDefinition>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = modifier.heightIn(min = MatrixHeaderMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.sec_matrix_col_app),
            style = XgType.monoSmall,
            color = colors.faint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(AppColumnWidth).padding(horizontal = 2.dp),
        )
        definitions.forEach { definition ->
            Text(
                text = definition.label,
                style = XgType.monoSmall,
                color = colors.faint,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .width(MatrixCellWidth)
                    .semantics { contentDescription = definition.label },
            )
        }
    }
}

@Composable
private fun MatrixRow(
    row: MatrixRowUi,
    enabled: Boolean,
    onCellClick: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalXgColors.current
    val context = LocalContext.current
    val cellDescriptions = remember(row.cells, row.label) {
        row.cells.map { cell ->
            context.getString(
                R.string.sec_matrix_cell_desc,
                row.label,
                cell.label,
                context.getString(stateLabelRes(cell.state)),
            )
        }
    }
    Row(
        modifier = modifier.heightIn(min = MatrixRowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .width(AppColumnWidth)
                .padding(vertical = 6.dp, horizontal = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.label,
                    style = XgType.rowTitle,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (row.isSystem) {
                    Spacer(Modifier.width(4.dp))
                    XgBadge(
                        text = stringResource(R.string.sec_matrix_system_badge),
                        tone = XgTone.Muted,
                    )
                }
            }
            Text(
                text = row.packageName,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.error != null) {
                Text(
                    text = stringResource(R.string.sec_matrix_row_error, row.error),
                    style = XgType.note,
                    color = colors.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        row.cells.forEachIndexed { index, cell ->
            MatrixCell(
                description = cellDescriptions[index],
                state = cell.state,
                enabled = enabled,
                pending = cell.pending,
                onClick = { onCellClick(row.packageName, cell.op) },
            )
        }
    }
}

@Composable
private fun MatrixCell(
    description: String,
    state: AppOpState?,
    enabled: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalXgColors.current
    Box(
        modifier = Modifier
            .width(MatrixCellWidth)
            .heightIn(min = MatrixRowMinHeight)
            .alpha(if (pending) 0.45f else 1f)
            .clickable(enabled = enabled && !pending, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (pending) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(colors.inset, CircleShape)
                    .border(1.5.dp, colors.accent.copy(alpha = 0.45f), CircleShape),
            )
        } else {
            MatrixDot(state = state, enabled = enabled)
        }
    }
}

@Composable
private fun MatrixDot(state: AppOpState?, enabled: Boolean = true) {
    val colors = LocalXgColors.current
    val fill = when (state) {
        AppOpState.ALLOW -> colors.accent
        AppOpState.ASK -> colors.warn
        AppOpState.DENY -> Color.Transparent
        null -> colors.inset
    }
    val borderColor = when (state) {
        AppOpState.ALLOW -> colors.accent.copy(alpha = 0.35f)
        AppOpState.ASK -> colors.warn.copy(alpha = 0.35f)
        AppOpState.DENY -> colors.lineStrong
        null -> colors.line
    }
    Box(
        modifier = Modifier
            .size(16.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .background(fill, CircleShape)
            .border(width = 1.5.dp, color = borderColor, shape = CircleShape),
    )
}

@Composable
private fun MatrixLegend() {
    val colors = LocalXgColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.md)) {
        listOf(AppOpState.ALLOW, AppOpState.ASK, AppOpState.DENY).forEach { state ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                MatrixDot(state = state)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stateLabel(state),
                    style = XgType.note,
                    color = colors.muted,
                    maxLines = 1,
                )
            }
        }
    }
    Spacer(Modifier.height(XgSpacing.xs))
    XgNote(text = stringResource(R.string.sec_matrix_legend_tip))
}

@Composable
private fun MatrixLegendBlock(
    showSampleNote: Boolean,
    sampleSize: Int,
    systemTotal: Int,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showSampleNote) {
            XgNote(
                text = stringResource(
                    R.string.sec_matrix_system_sample_note,
                    sampleSize,
                    systemTotal,
                ),
            )
            Spacer(Modifier.height(XgSpacing.xs))
        }
        XgNote(text = stringResource(R.string.sec_matrix_ignore_note))
    }
}

@Composable
private fun MatrixFooter(
    matrix: MatrixUiState,
    onLoadMore: () -> Unit,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(
                R.string.sec_matrix_counter,
                matrix.rows.size,
                matrix.filteredCount,
            ),
            style = XgType.pageMeta,
            color = colors.faint,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        if (matrix.rows.size < matrix.filteredCount) {
            XgTextAction(
                text = stringResource(R.string.sec_matrix_load_more),
                onClick = onLoadMore,
                enabled = !matrix.loadingMore,
            )
        }
    }
    if (matrix.loadingMore) {
        Spacer(Modifier.height(XgSpacing.xs))
        XgProgressBar(progress = null)
    }
}

private fun stateLabelRes(state: AppOpState?): Int = when (state) {
    AppOpState.ALLOW -> R.string.sec_state_allow
    AppOpState.ASK -> R.string.sec_state_ask
    AppOpState.DENY -> R.string.sec_state_deny
    null -> R.string.sec_matrix_cell_unknown
}

@Composable
private fun stateLabel(state: AppOpState?): String = stringResource(stateLabelRes(state))
