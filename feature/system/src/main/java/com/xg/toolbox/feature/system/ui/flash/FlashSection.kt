package com.xg.toolbox.feature.system.ui.flash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.feature.system.R

@Composable
fun FlashSection(modifier: Modifier = Modifier, headerVisible: Boolean = true) {
    val viewModel: FlashViewModel = hiltViewModel()
    FlashSection(viewModel = viewModel, modifier = modifier, headerVisible = headerVisible)
}

@Composable
internal fun FlashSection(
    viewModel: FlashViewModel,
    modifier: Modifier = Modifier,
    headerVisible: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pickImage = rememberFlashImagePicker(viewModel)
    val environment = state.environment

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(XgSpacing.md),
    ) {
        if (headerVisible) {
            XgSectionHeader(
                title = stringResource(R.string.sys_tune_flash_title),
                meta = stringResource(R.string.sys_tune_flash_meta),
            )
        }

        if (environment != null) {
            if (state.readOnly) {
                FlashReadOnlyBanner()
            }
            if (state.busy) {
                FlashBusyRow()
            }
            state.notice?.let { notice ->
                FlashNoticeRow(notice = notice, onDismiss = viewModel::dismissNotice)
            }
            if (state.awaitingRestart) {
                XgNote(
                    text = stringResource(R.string.sys_tune_flash_restart_needed),
                    tone = XgTone.Warn,
                )
            }
            FlashEnvironmentPanel(
                environment = environment,
                onRedetect = viewModel::refreshEnvironment,
                redetectEnabled = !state.busy,
            )
            FlashSlotPanel(
                environment = environment,
                editable = !state.readOnly && !state.busy,
                onSwitchSlot = viewModel::requestSlotSwitch,
            )
            FlashPartsPanel(
                environment = environment,
                targetPartition = state.targetPartition,
                editable = !state.readOnly && !state.busy,
                onSelectPartition = viewModel::selectPartition,
            )
            val targetInfo = environment.partitionUis()
                .firstOrNull { partition -> partition.name == state.targetPartition }
            FlashImagePanel(
                readOnly = state.readOnly,
                busy = state.busy,
                inspecting = state.inspecting,
                image = state.image,
                target = state.targetPartition,
                targetInfo = targetInfo,
                sizeTooLarge = state.image != null &&
                    targetInfo != null &&
                    state.image!!.sizeBytes > targetInfo.sizeBytes,
                canSkipBackup = targetInfo?.canSkipBackup() == true,
                skipBackup = state.skipBackup && targetInfo?.canSkipBackup() == true,
                onSkipBackupChange = viewModel::setSkipBackup,
                backup = state.backup,
                writableTarget = environment.availableParts
                    .any { it.name == state.targetPartition && it.writable },
                onPickImage = pickImage,
                onBackup = viewModel::backupPartition,
                onWrite = viewModel::requestFlash,
            )
            FlashProgressPanel(
                progress = state.progress,
                logs = state.logs,
                busy = state.busy,
                onAbort = viewModel::abortFlash,
            )
            FlashBrickRecoveryPanel()
        } else {
            when (state.phase) {
                FlashPhase.ERROR -> XgErrorState(
                    code = state.errorCode,
                    message = state.errorMessage ?: stringResource(R.string.sys_tune_error_unknown),
                    onRetry = viewModel::refreshEnvironment,
                )

                FlashPhase.EMPTY -> XgEmptyState(
                    title = stringResource(R.string.sys_tune_flash_no_parts),
                    actionText = stringResource(R.string.sys_tune_action_redetect),
                    onAction = viewModel::refreshEnvironment,
                )

                else -> XgLoadingSkeleton(lines = 3)
            }
        }
    }

    FlashConfirmDialogs(
        confirmFlash = state.confirmFlash,
        image = state.image,
        targetPartition = state.targetPartition,
        backup = state.backup,
        skipBackup = state.skipBackup,
        confirmSlot = state.confirmSlot,
        currentSlot = state.environment?.currentSlot,
        viewModel = viewModel,
    )
    FlashRiskDialogs(
        riskConfirm = state.riskConfirm,
        skipBackup = state.skipBackup,
        onConfirm = viewModel::confirmRisk,
        onDismiss = viewModel::cancelRisk,
    )
}