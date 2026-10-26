package com.xg.toolbox.feature.apps.ui

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.component.LocalXgToast
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgContextMenuAction
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgLongPressContextMenu
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPullRefreshBox
import com.xg.toolbox.core.ui.component.XgSearchField
import com.xg.toolbox.core.ui.component.XgStickyHeader
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.liquid.LocalXgBackdropVisible
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.LocalXgMotionScale
import com.xg.toolbox.core.ui.theme.XgEaseOutCubic
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.core.ui.theme.xgTween
import com.xg.toolbox.feature.apps.AppRowUi
import com.xg.toolbox.feature.apps.AppsFilter
import com.xg.toolbox.feature.apps.AppsSort
import com.xg.toolbox.feature.apps.AppsUiState
import com.xg.toolbox.feature.apps.AppsViewModel
import com.xg.toolbox.feature.apps.PendingUndo
import com.xg.toolbox.feature.apps.R
import kotlinx.coroutines.delay

private data class AppContextTarget(
    val packageName: String,
    val label: String,
)

@Composable
fun AppsOverviewPage(
    state: AppsUiState,
    listState: LazyListState = rememberLazyListState(),
    viewModel: AppsViewModel,
    onOpenDetail: (String) -> Unit,
    onOpenModules: () -> Unit,
    onOpenBatch: () -> Unit,
    onOpenAndroidId: () -> Unit,
) {
    val colors = LocalXgColors.current
    val backdropVisible = LocalXgBackdropVisible.current
    val motion = LocalXgMotionScale.current
    val context = LocalContext.current
    val toast = LocalXgToast.current
    var contextTarget by remember { mutableStateOf<AppContextTarget?>(null) }
    var contextVisible by remember { mutableStateOf(false) }
    var pendingDetail by remember { mutableStateOf<String?>(null) }
    val blurRadius by animateDpAsState(
        targetValue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && contextVisible) 16.dp else 0.dp,
        animationSpec = xgTween(220, motion, XgEaseOutCubic),
        label = "appsContextBlur",
    )
    val blurModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurRadius > 0.dp) {
        Modifier.blur(blurRadius, BlurredEdgeTreatment.Unbounded)
    } else {
        Modifier
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(if (backdropVisible) colors.bg.copy(alpha = 0.10f) else colors.bg)
                .then(blurModifier),
        ) {
            XgStickyHeader {
                XgSearchField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    placeholder = stringResource(R.string.apps_search_placeholder),
                )
                AppsToolEntries(
                    onOpenBatch = onOpenBatch,
                    onOpenAndroidId = onOpenAndroidId,
                    onOpenModules = onOpenModules,
                )
                AppsFilterSortBar(
                    filter = state.filter,
                    sort = state.sort,
                    onFilter = viewModel::setFilter,
                    onCycleSort = {
                        viewModel.setSort(
                            when (state.sort) {
                                AppsSort.NAME -> AppsSort.MEMORY
                                AppsSort.MEMORY -> AppsSort.INSTALL_TIME
                                AppsSort.INSTALL_TIME -> AppsSort.NAME
                            },
                        )
                    },
                    onRefresh = viewModel::refresh,
                )
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                XgPullRefreshBox(
                    refreshing = state.refreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    XgContentTransition(
                        stateKey = state.rows,
                        loading = state.loading && state.rows.isEmpty(),
                        error = (state.listError ?: state.refreshError)?.message,
                        onRetry = viewModel::refresh,
                        modifier = Modifier.fillMaxSize(),
                        skeletonLines = 8,
                        initiallyLoaded = state.rows.isNotEmpty(),
                    ) {
                        AppsListBody(
                            state = state,
                            listState = listState,
                            viewModel = viewModel,
                            contextMenuPackage = contextTarget?.packageName,
                            onOpenDetail = onOpenDetail,
                            onLongPress = { row ->
                                pendingDetail = null
                                contextTarget = AppContextTarget(row.packageName, row.label)
                                contextVisible = true
                            },
                        )
                    }
                }
                state.pendingUndo?.let { undo ->
                    PendingUndoBar(
                        undo = undo,
                        onUndo = viewModel::undoUninstall,
                        onExpired = viewModel::clearPendingUndo,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = XgSpacing.screen, vertical = XgSpacing.md)
                            .padding(bottom = XgSpacing.contentBottom),
                    )
                }
            }
        }
    }

    contextTarget?.let { target ->
        val copyMessage = stringResource(R.string.apps_toast_package_copied, target.label)
        val copyFailed = stringResource(R.string.apps_toast_copy_failed)
        XgLongPressContextMenu(
            visible = contextVisible,
            title = target.label,
            subtitle = target.packageName,
            actions = listOf(
                XgContextMenuAction(
                    label = stringResource(R.string.apps_context_menu_details),
                    icon = Icons.Outlined.Info,
                    onClick = { pendingDetail = target.packageName },
                ),
                XgContextMenuAction(
                    label = stringResource(R.string.apps_context_menu_copy_package),
                    icon = Icons.Outlined.ContentCopy,
                    onClick = {
                        val copied = context.copyPlainText(target.packageName)
                        toast?.show(if (copied) copyMessage else copyFailed)
                    },
                ),
            ),
            onDismissRequest = { contextVisible = false },
            onDismissed = {
                val detail = pendingDetail
                contextVisible = false
                contextTarget = null
                pendingDetail = null
                detail?.let(onOpenDetail)
            },
        )
    }
}

@Composable
private fun AppsToolEntries(
    onOpenBatch: () -> Unit,
    onOpenAndroidId: () -> Unit,
    onOpenModules: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = XgSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(XgSpacing.xs),
    ) {
        XgTextAction(text = stringResource(R.string.apps_tools_batch), onClick = onOpenBatch)
        XgTextAction(text = stringResource(R.string.apps_android_id_title), onClick = onOpenAndroidId)
        XgTextAction(text = stringResource(R.string.apps_modules_title), onClick = onOpenModules)
    }
}

@Composable
private fun AppsFilterSortBar(
    filter: AppsFilter,
    sort: AppsSort,
    onFilter: (AppsFilter) -> Unit,
    onCycleSort: () -> Unit,
    onRefresh: () -> Unit,
) {
    val filterLabels = listOf(
        stringResource(R.string.apps_filter_all),
        stringResource(R.string.apps_filter_user),
        stringResource(R.string.apps_filter_system),
        stringResource(R.string.apps_filter_frozen),
    )
    val sortLabel = stringResource(
        R.string.apps_sort_label,
        stringResource(
            when (sort) {
                AppsSort.NAME -> R.string.apps_sort_name
                AppsSort.MEMORY -> R.string.apps_sort_memory
                AppsSort.INSTALL_TIME -> R.string.apps_sort_installed
            },
        ),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = XgSpacing.xs, bottom = XgSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(XgSpacing.xs),
        ) {
            items(items = AppsFilter.entries, key = { it.name }) { entry ->
                AppsToolChip(
                    text = filterLabels[entry.ordinal],
                    selected = filter == entry,
                    onClick = { onFilter(entry) },
                )
            }
            item {
                AppsToolChip(text = sortLabel, selected = false, onClick = onCycleSort)
            }
        }
        Spacer(Modifier.padding(start = XgSpacing.sm))
        XgTextAction(text = stringResource(R.string.apps_action_refresh), onClick = onRefresh, tone = XgTone.Muted)
    }
}

@Composable
private fun AppsListBody(
    state: AppsUiState,
    listState: LazyListState,
    viewModel: AppsViewModel,
    contextMenuPackage: String?,
    onOpenDetail: (String) -> Unit,
    onLongPress: (AppRowUi) -> Unit,
) {
    val rows = state.visibleRows
    val longPressLabel = stringResource(R.string.apps_long_press_menu)
    if (rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.hasNoAppsAtAll) {
                XgEmptyState(
                    title = stringResource(R.string.apps_empty_all),
                    actionText = stringResource(R.string.apps_action_reload),
                    onAction = viewModel::refresh,
                )
            } else {
                XgEmptyState(
                    title = stringResource(R.string.apps_empty_filtered),
                    actionText = stringResource(R.string.apps_action_clear_filter),
                    onAction = viewModel::clearSearchAndFilter,
                )
            }
        }
    } else {
        androidx.compose.foundation.lazy.LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = XgSpacing.screen,
                end = XgSpacing.screen,
                top = XgSpacing.xs,
                bottom = XgSpacing.contentBottom,
            ),
        ) {
            itemsIndexed(
                items = rows,
                key = { _, row -> row.packageName },
                contentType = { _, _ -> "app" },
            ) { index, row ->
                AppRowCard(
                    row = row,
                    position = appRowPosition(index, rows.size),
                    onClick = { onOpenDetail(row.packageName) },
                    viewModel = viewModel,
                    modifier = Modifier.animateItem(),
                    onLongPress = { onLongPress(row) },
                    longPressLabel = longPressLabel,
                    longPressActive = contextMenuPackage == row.packageName,
                )
            }
            item(key = "apps_footer") {
                Box(modifier = Modifier.padding(XgSpacing.md)) {
                    XgNote(
                        text = stringResource(R.string.apps_list_footer, rows.size),
                        tone = XgTone.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingUndoBar(
    undo: PendingUndo,
    onUndo: () -> Unit,
    onExpired: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(undo.packageName, undo.expiresAt) {
        delay((undo.expiresAt - System.currentTimeMillis()).coerceAtLeast(0L))
        onExpired()
    }
    XgPanel(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.apps_undo_title, undo.label),
                    style = XgType.rowTitle,
                    color = LocalXgColors.current.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.apps_undo_window),
                    style = XgType.note,
                    color = LocalXgColors.current.faint,
                )
            }
            XgTextAction(text = stringResource(R.string.apps_action_undo), onClick = onUndo)
        }
    }
}