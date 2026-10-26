package com.xg.toolbox.feature.device

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.LocalXgToast
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgEntryCard
import com.xg.toolbox.core.ui.component.XgIconButton
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPageDirection
import com.xg.toolbox.core.ui.component.XgPageScaffold
import com.xg.toolbox.core.ui.component.XgPageTransition
import com.xg.toolbox.core.ui.component.XgPullRefreshBox
import com.xg.toolbox.core.ui.component.XgSearchField
import com.xg.toolbox.core.ui.component.XgStatusChip
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgToastHost
import com.xg.toolbox.core.ui.component.XgToastState
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.XgSpacing

@Composable
fun DeviceRoute(viewModel: DeviceViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val appToast = LocalXgToast.current
    val fallbackToast = remember { if (appToast == null) XgToastState() else null }
    val toast: XgToastState? = appToast ?: fallbackToast
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }

    var selectedGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var transitionDirection by remember { mutableStateOf(XgPageDirection.Forward) }

    LaunchedEffect(state.dataVersion) {
        val current = selectedGroupId
        if (current != null && state.groups.isNotEmpty() && state.groups.none { it.id == current }) {
            transitionDirection = XgPageDirection.Backward
            selectedGroupId = null
        }
    }
    BackHandler(enabled = selectedGroupId != null) {
        transitionDirection = XgPageDirection.Backward
        selectedGroupId = null
    }

    val copyGroupDone = stringResource(R.string.device_copy_group_done)
    val copyAllDone = stringResource(R.string.device_copy_all_done)
    val defaultNote = stringResource(R.string.device_note_default)
    val copyTitle = stringResource(R.string.device_copy_section_title)
    val copyGroupLabel = stringResource(R.string.device_copy_table_group)
    val copyNameLabel = stringResource(R.string.device_copy_table_name)
    val copyKeyLabel = stringResource(R.string.device_copy_table_key)
    val copyValueLabel = stringResource(R.string.device_copy_table_value)

    val copyText: (String, String?) -> Boolean = { text, message ->
        if (text.isEmpty()) {
            false
        } else {
            runCatching {
                val manager = clipboard ?: return@runCatching false
                manager.setPrimaryClip(ClipData.newPlainText("xg-device", text))
                val copied = manager.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.text
                    ?.toString() == text
                if (copied && message != null) {
                    toast?.show(message)
                }
                copied
            }.getOrDefault(false)
        }
    }

    LaunchedEffect(viewModel, toast) {
        viewModel.events.collect { event ->
            when (event) {
                is DeviceEvent.SerialChanged -> {
                    val base = context.getString(
                        R.string.device_serial_edit_success,
                        event.before?.takeIf { it.isNotBlank() } ?: "—",
                        event.after?.takeIf { it.isNotBlank() } ?: "—",
                    )
                    val reboot = if (event.requiresReboot) {
                        context.getString(R.string.device_serial_edit_success_reboot)
                    } else {
                        ""
                    }
                    toast?.show(base + reboot)
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        XgPullRefreshBox(
            refreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            XgPageTransition(
                target = selectedGroupId,
                modifier = Modifier.fillMaxSize(),
                direction = transitionDirection,
            ) { target ->
                val group = target?.let { id -> state.groups.firstOrNull { it.id == id } }
                if (group == null) {
                    DeviceOverviewPage(
                        state = state,
                        onQueryChange = viewModel::onQueryChange,
                        onClearQuery = viewModel::clearQuery,
                        onOpenGroup = { groupId ->
                            transitionDirection = XgPageDirection.Forward
                            selectedGroupId = groupId
                        },
                        onRefresh = viewModel::refresh,
                        onCopyAll = {
                            copyText(
                                viewModel.copyAllText(
                                    copyTitle,
                                    copyGroupLabel,
                                    copyNameLabel,
                                    copyKeyLabel,
                                    copyValueLabel,
                                ),
                                copyAllDone,
                            )
                        },
                    )
                } else {
                    DeviceGroupPage(
                        group = group,
                        state = state,
                        onBack = {
                            transitionDirection = XgPageDirection.Backward
                            selectedGroupId = null
                        },
                        onRefresh = viewModel::refresh,
                        onCopyGroup = {
                            copyText(viewModel.copyGroupText(group.id), copyGroupDone)
                        },
                        onCopyField = { field ->
                            field.value?.let { value -> copyText(value, null) } ?: false
                        },
                        onShowFieldNote = { field ->
                            toast?.show(field.note ?: defaultNote)
                        },
                        onEditSerial = if (group.fields.any { it.isSerialNumberField }) {
                            viewModel::openSerialEdit
                        } else {
                            null
                        },
                    )
                }
            }
        }
        if (fallbackToast != null) {
            XgToastHost(state = fallbackToast)
        }
    }

    if (state.serialEdit.visible) {
        SerialEditDialog(
            ui = state.serialEdit,
            onInputChange = viewModel::onSerialInputChange,
            onConfirm = viewModel::confirmSerialEdit,
            onDismiss = viewModel::dismissSerialEdit,
        )
    }
}

@Composable
private fun DeviceOverviewPage(
    state: DeviceUiState,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onRefresh: () -> Unit,
    onCopyAll: () -> Unit,
) {
    XgPageScaffold(
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    XgStatusChip(
                        text = stringResource(R.string.device_readonly),
                        tone = XgTone.Muted,
                    )
                    Spacer(Modifier.weight(1f))
                    XgTextAction(
                        text = stringResource(R.string.device_copy_all),
                        onClick = onCopyAll,
                        enabled = state.hasData,
                    )
                    Spacer(Modifier.width(XgSpacing.xs))
                    XgIconButton(
                        icon = Icons.Outlined.Refresh,
                        contentDescription = stringResource(R.string.device_refresh),
                        onClick = onRefresh,
                    )
                }
                Spacer(Modifier.height(XgSpacing.xs))
                XgSearchField(
                    value = state.query,
                    onValueChange = onQueryChange,
                    placeholder = stringResource(R.string.device_search_placeholder),
                )
            }
        },
    ) {
        when {
            !state.hasData -> {
                item(key = "device-overview-state") {
                    XgContentTransition(
                        stateKey = state.dataVersion,
                        loading = state.loading,
                        error = state.error,
                        onRetry = onRefresh,
                        skeletonLines = 6,
                    ) {
                        XgEmptyState(title = stringResource(R.string.device_empty_data))
                    }
                }
            }

            else -> {
                item(key = "device-overview-summary") {
                    XgContentTransition(
                        stateKey = state.dataVersion,
                        loading = false,
                        error = state.error,
                        onRetry = onRefresh,
                    ) {
                        DeviceSummaryCard(state.summary)
                    }
                }
                if (state.isSearching) {
                    item(key = "device-overview-search-meta") {
                        XgNote(
                            text = stringResource(R.string.device_search_result, state.matchTotal),
                        )
                    }
                }
                if (state.visibleGroups.isEmpty()) {
                    item(key = "device-overview-empty") {
                        XgEmptyState(
                            title = stringResource(R.string.device_empty_title),
                            actionText = stringResource(R.string.device_clear_search),
                            onAction = onClearQuery,
                        )
                    }
                    item(key = "device-overview-empty-hint") {
                        XgNote(text = stringResource(R.string.device_empty_hint))
                    }
                } else {
                    items(
                        count = state.visibleGroups.size,
                        key = { index -> state.visibleGroups[index].id },
                        contentType = { "device.group" },
                    ) { index ->
                        val group = state.visibleGroups[index]
                        XgContentTransition(
                            stateKey = state.dataVersion,
                            loading = false,
                            error = null,
                            onRetry = onRefresh,
                            modifier = Modifier.animateItem(),
                        ) {
                            XgEntryCard(
                                ordinal = group.ordinal.toString().padStart(2, '0'),
                                title = group.title,
                                subtitle = group.preview,
                                meta = if (state.isSearching) {
                                    stringResource(R.string.device_group_match, group.matchCount)
                                } else {
                                    stringResource(R.string.device_group_count, group.fields.size)
                                },
                                onClick = { onOpenGroup(group.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceGroupPage(
    group: DeviceGroupUi,
    state: DeviceUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCopyGroup: () -> Unit,
    onCopyField: (DeviceFieldUi) -> Boolean,
    onShowFieldNote: (DeviceFieldUi) -> Unit,
    onEditSerial: (() -> Unit)?,
) {
    XgSubPageScaffold(
        title = group.title,
        meta = stringResource(R.string.device_detail_meta, group.fields.size),
        onBack = onBack,
        actions = {
            XgTextAction(
                text = stringResource(R.string.device_copy_group),
                onClick = onCopyGroup,
            )
            XgIconButton(
                icon = Icons.Outlined.Refresh,
                contentDescription = stringResource(R.string.device_refresh),
                onClick = onRefresh,
            )
        },
    ) {
        if (state.isSearching) {
            item(key = "device-detail-search-note") {
                XgNote(
                    text = stringResource(R.string.device_detail_search_hint, group.matchCount),
                )
            }
        }
        item(key = "device-detail-fields-${group.id}") {
            XgContentTransition(
                stateKey = state.dataVersion,
                loading = false,
                error = state.error,
                onRetry = onRefresh,
                skeletonLines = 4,
            ) {
                DeviceFieldsPanel(
                    group = group,
                    onCopyField = onCopyField,
                    onShowFieldNote = onShowFieldNote,
                    onEditSerial = onEditSerial,
                )
            }
        }
    }
}
