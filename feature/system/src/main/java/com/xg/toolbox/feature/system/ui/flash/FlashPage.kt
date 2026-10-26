package com.xg.toolbox.feature.system.ui.flash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.flash.FlashBackup
import com.xg.toolbox.feature.system.domain.flash.FlashEnvironment
import com.xg.toolbox.feature.system.domain.flash.FlashImageInfo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Composable
fun FlashPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: FlashViewModel = hiltViewModel()
    val ui by viewModel.state
        .map { state ->
            FlashPageUi(
                phase = state.phase,
                environment = state.environment,
                readOnly = state.readOnly,
                busy = state.busy,
                inspecting = state.inspecting,
                image = state.image,
                target = state.targetPartition,
                backup = state.backup,
                notice = state.notice,
                awaitingRestart = state.awaitingRestart,
                confirmFlash = state.confirmFlash,
                confirmSlot = state.confirmSlot,
                errorMessage = state.errorMessage,
                partitionUis = state.partitionUis,
                detectedTotal = state.detectedTotal,
                partitionQuery = state.partitionQuery,
                riskFilter = state.riskFilter,
                slotFilter = state.slotFilter,
                writableOnly = state.writableOnly,
                skipBackup = state.skipBackup,
                riskConfirm = state.riskConfirm,
            )
        }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = FlashPageUi())
    val pickImage = rememberFlashImagePicker(viewModel)
    val environment = ui.environment
    val currentSlot = environment?.currentSlot
    val filteredPartitions = remember(
        ui.partitionUis,
        ui.partitionQuery,
        ui.riskFilter,
        ui.slotFilter,
        ui.writableOnly,
        currentSlot,
    ) {
        ui.partitionUis.filterPartitions(
            query = ui.partitionQuery,
            risk = ui.riskFilter,
            slot = ui.slotFilter,
            currentSlot = currentSlot,
            writableOnly = ui.writableOnly,
        )
    }
    val groupedPartitions = remember(filteredPartitions, currentSlot) {
        filteredPartitions.groupBy { partition -> partition.group(currentSlot) }
    }
    val targetInfo = ui.partitionUis.firstOrNull { partition -> partition.name == ui.target }
    val sizeTooLarge = ui.image != null && targetInfo != null && ui.image!!.sizeBytes > targetInfo.sizeBytes
    val canSkipBackup = targetInfo?.canSkipBackup() == true

    Box(modifier = modifier.fillMaxSize()) {
        XgSubPageScaffold(
            title = stringResource(R.string.sys_tune_flash_title),
            meta = stringResource(R.string.sys_tune_flash_meta),
            onBack = onBack,
        ) {
            if (ui.readOnly || ui.busy || ui.notice != null || ui.awaitingRestart) {
                item(key = "flash-banners", contentType = "flash-banner") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(XgSpacing.sm),
                    ) {
                        if (ui.readOnly) {
                            FlashReadOnlyBanner()
                        }
                        if (ui.busy) {
                            FlashBusyRow()
                        }
                        ui.notice?.let { notice ->
                            FlashNoticeRow(notice = notice, onDismiss = viewModel::dismissNotice)
                        }
                        if (ui.awaitingRestart) {
                            XgNote(
                                text = stringResource(R.string.sys_tune_flash_restart_needed),
                                tone = XgTone.Warn,
                            )
                        }
                    }
                }
            }
            if (environment == null && ui.phase == FlashPhase.LOADING) {
                item(key = "flash-detecting", contentType = "flash-loading") {
                    FlashDetectingPanel()
                }
            } else {
                item(key = "flash-environment", contentType = "flash-panel") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        XgContentTransition(
                            stateKey = environment ?: ui.phase,
                            loading = environment == null && ui.phase == FlashPhase.LOADING,
                            error = if (environment == null && ui.phase == FlashPhase.ERROR) {
                                ui.errorMessage ?: stringResource(R.string.sys_tune_error_unknown)
                            } else {
                                null
                            },
                            onRetry = viewModel::refreshEnvironment,
                            initiallyLoaded = environment != null,
                        ) {
                            if (environment != null) {
                                FlashEnvironmentPanel(
                                    environment = environment,
                                    onRedetect = viewModel::refreshEnvironment,
                                    redetectEnabled = !ui.busy,
                                )
                            } else {
                                XgEmptyState(
                                    title = stringResource(R.string.sys_tune_flash_no_parts),
                                    actionText = stringResource(R.string.sys_tune_action_redetect),
                                    onAction = viewModel::refreshEnvironment,
                                )
                            }
                        }
                    }
                }
            }
            if (environment != null) {
                item(key = "flash-slot", contentType = "flash-panel") {
                    FlashSlotPanel(
                        environment = environment,
                        editable = ui.editable,
                        onSwitchSlot = viewModel::requestSlotSwitch,
                    )
                }
                item(key = "flash-parts-toolbar", contentType = "flash-toolbar") {
                    FlashPartitionToolbar(
                        detectedTotal = ui.detectedTotal,
                        availableCount = ui.partitionUis.size,
                        resultCount = filteredPartitions.size,
                        currentSlot = currentSlot,
                        query = ui.partitionQuery,
                        onQueryChange = viewModel::setPartitionQuery,
                        riskFilter = ui.riskFilter,
                        onRiskFilter = viewModel::setRiskFilter,
                        slotFilter = ui.slotFilter,
                        onSlotFilter = viewModel::setSlotFilter,
                        writableOnly = ui.writableOnly,
                        onWritableOnly = viewModel::setWritableOnly,
                    )
                }
                if (filteredPartitions.isEmpty()) {
                    item(key = "flash-parts-empty", contentType = "flash-empty") {
                        XgNote(
                            text = stringResource(R.string.sys_tune_flash_no_matching_parts),
                            tone = XgTone.Muted,
                        )
                    }
                } else {
                    FlashPartitionGroup.entries.forEach { group ->
                        val parts = groupedPartitions[group].orEmpty()
                        if (parts.isEmpty()) return@forEach
                        item(key = "flash-group-${group.name}", contentType = "flash-group") {
                            FlashPartitionGroupHeader(group = group, count = parts.size)
                        }
                        items(
                            items = parts,
                            key = { partition -> partition.name },
                            contentType = { "flash-partition" },
                        ) { partition ->
                            FlashPartitionUiCard(
                                partition = partition,
                                selected = partition.name == ui.target,
                                editable = ui.editable,
                                onClick = { viewModel.selectPartition(partition.name) },
                            )
                        }
                    }
                }
                item(key = "flash-image", contentType = "flash-panel") {
                    FlashImagePanel(
                        readOnly = ui.readOnly,
                        busy = ui.busy,
                        inspecting = ui.inspecting,
                        image = ui.image,
                        target = ui.target,
                        targetInfo = targetInfo,
                        sizeTooLarge = sizeTooLarge,
                        canSkipBackup = canSkipBackup,
                        skipBackup = ui.skipBackup && canSkipBackup,
                        onSkipBackupChange = viewModel::setSkipBackup,
                        backup = ui.backup,
                        writableTarget = ui.writableTarget,
                        onPickImage = pickImage,
                        onBackup = viewModel::backupPartition,
                        onWrite = viewModel::requestFlash,
                    )
                }
                item(key = "flash-progress", contentType = "flash-progress") {
                    FlashProgressCard(viewModel = viewModel)
                }
                item(key = "flash-brick", contentType = "flash-panel") {
                    FlashBrickRecoveryPanel()
                }
            }
        }
    }

    FlashConfirmDialogs(
        confirmFlash = ui.confirmFlash,
        image = ui.image,
        targetPartition = ui.target,
        backup = ui.backup,
        skipBackup = ui.skipBackup && canSkipBackup,
        confirmSlot = ui.confirmSlot,
        currentSlot = currentSlot,
        viewModel = viewModel,
    )
    FlashRiskDialogs(
        riskConfirm = ui.riskConfirm,
        skipBackup = ui.skipBackup && canSkipBackup,
        onConfirm = viewModel::confirmRisk,
        onDismiss = viewModel::cancelRisk,
    )
}

@Composable
private fun FlashProgressCard(viewModel: FlashViewModel) {
    val ui by viewModel.state
        .map { state -> FlashProgressUi(state.progress, state.logs, state.busy) }
        .distinctUntilChanged()
        .collectAsStateWithLifecycle(initialValue = FlashProgressUi(null, emptyList(), false))
    FlashProgressPanel(
        progress = ui.progress,
        logs = ui.logs,
        busy = ui.busy,
        onAbort = viewModel::abortFlash,
    )
}

private data class FlashPageUi(
    val phase: FlashPhase = FlashPhase.LOADING,
    val environment: FlashEnvironment? = null,
    val readOnly: Boolean = false,
    val busy: Boolean = false,
    val inspecting: Boolean = false,
    val image: FlashImageInfo? = null,
    val target: String? = null,
    val backup: FlashBackup? = null,
    val notice: FlashNotice? = null,
    val awaitingRestart: Boolean = false,
    val confirmFlash: Boolean = false,
    val confirmSlot: Boolean = false,
    val errorMessage: String? = null,
    val partitionUis: List<FlashPartitionUi> = emptyList(),
    val detectedTotal: Int = 0,
    val partitionQuery: String = "",
    val riskFilter: FlashPartitionRisk? = null,
    val slotFilter: FlashSlotFilter = FlashSlotFilter.ALL,
    val writableOnly: Boolean = false,
    val skipBackup: Boolean = false,
    val riskConfirm: FlashRiskConfirm? = null,
) {
    val writableTarget: Boolean
        get() = partitionUis.any { partition -> partition.name == target && partition.writable }

    val editable: Boolean
        get() = !readOnly && !busy
}

private data class FlashProgressUi(
    val progress: com.xg.toolbox.feature.system.domain.flash.FlashProgress?,
    val logs: List<FlashLogLine>,
    val busy: Boolean,
)

@Composable
private fun FlashDetectingPanel() {
    val colors = com.xg.toolbox.core.ui.theme.LocalXgColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = colors.accent,
                strokeWidth = 3.dp,
            )
            Text(
                text = stringResource(R.string.sys_tune_flash_detecting),
                color = colors.muted,
                style = com.xg.toolbox.core.ui.theme.XgType.body,
            )
        }
    }
}
