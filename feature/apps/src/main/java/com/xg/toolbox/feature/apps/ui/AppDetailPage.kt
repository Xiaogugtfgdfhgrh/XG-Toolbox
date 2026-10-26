package com.xg.toolbox.feature.apps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgDangerButton
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgKeyValueRow
import com.xg.toolbox.core.ui.component.LocalXgToast
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.feature.apps.ActionKind
import com.xg.toolbox.feature.apps.AppDetailUi
import com.xg.toolbox.feature.apps.AppsUiState
import com.xg.toolbox.feature.apps.AppsViewModel
import com.xg.toolbox.feature.apps.MemoryState
import com.xg.toolbox.feature.apps.R
import com.xg.toolbox.feature.apps.TrafficState

@Composable
fun AppDetailPage(
    state: AppsUiState,
    packageName: String,
    onBack: () -> Unit,
    viewModel: AppsViewModel,
) {
    val context = LocalContext.current
    val toast = LocalXgToast.current
    val loaded = state.detail?.takeIf { it.detail.packageName == packageName }
    val rowLabel = state.rows.firstOrNull { it.packageName == packageName }?.label
    val title = loaded?.detail?.label ?: rowLabel ?: packageName
    val copyMessage = stringResource(R.string.apps_toast_info_copied)
    val copyFailed = stringResource(R.string.apps_toast_copy_failed)
    XgSubPageScaffold(
        title = title,
        meta = packageName,
        onBack = onBack,
        actions = {
            if (loaded != null) {
                XgTextAction(
                    text = stringResource(R.string.apps_action_copy_info),
                    onClick = {
                        val copied = context.copyPlainText(buildCopyText(loaded, state))
                        toast?.show(if (copied) copyMessage else copyFailed)
                    },
                )
            }
        },
    ) {
        val failure = state.actionFailure?.takeIf { it.packageName == packageName }
        if (failure != null) {
            item(key = "detail_failure") {
                AppsErrorNote(failure = failure, onDismiss = viewModel::clearActionFailure)
            }
        }
        val detailError = state.detailError
        when {
            state.detailLoading -> item(key = "detail_loading") { XgLoadingSkeleton(lines = 6) }
            detailError != null -> item(key = "detail_error") {
                XgErrorState(
                    code = detailError.code,
                    message = detailError.message,
                    onRetry = { viewModel.loadDetail(packageName) },
                )
            }

            loaded != null -> {
                item(key = "detail_fields") {
                    XgPanel {
                        DetailFields(loaded = loaded)
                    }
                }
                item(key = "detail_actions") {
                    DetailActions(loaded = loaded, state = state, viewModel = viewModel)
                }
                item(key = "detail_abi") {
                    AbiSection(loaded = loaded, state = state, viewModel = viewModel)
                }
                val usageError = loaded.usageError
                if (usageError != null) {
                    item(key = "detail_usage_error") {
                        XgErrorState(
                            code = usageError.code,
                            message = usageError.message,
                            onRetry = viewModel::retryUsage,
                        )
                    }
                }
            }

            else -> item(key = "detail_empty") { XgLoadingSkeleton(lines = 4) }
        }
    }
}

@Composable
private fun DetailFields(loaded: AppDetailUi) {
    val detail = loaded.detail
    XgKeyValueRow(
        label = stringResource(R.string.apps_field_signature),
        value = detail.signingSha256.takeIf { it.isNotBlank() },
        badge = if (detail.signatureCount > 1) {
            stringResource(R.string.apps_signature_multi, detail.signatureCount)
        } else {
            null
        },
        badgeTone = XgTone.Warn,
    )
    XgKeyValueRow(
        label = stringResource(R.string.apps_field_source_dir),
        value = detail.sourceDir.takeIf { it.isNotBlank() },
    )
    XgKeyValueRow(
        label = stringResource(R.string.apps_field_data_dir),
        value = detail.dataDir.takeIf { it.isNotBlank() },
    )
    XgKeyValueRow(
        label = stringResource(R.string.apps_field_native_lib),
        value = detail.nativeLibraryDir?.takeIf { it.isNotBlank() },
    )
    XgKeyValueRow(
        label = stringResource(R.string.apps_field_version),
        value = buildString {
            append(if (detail.versionName.isBlank()) "—" else detail.versionName)
            append(" (")
            append(detail.versionCode)
            append(')')
        },
    )
    XgKeyValueRow(
        label = stringResource(R.string.apps_field_installed_at),
        value = if (detail.firstInstallTime > 0) Formatters.fullDateTime(detail.firstInstallTime) else null,
    )
    XgKeyValueRow(
        label = stringResource(R.string.apps_field_updated_at),
        value = if (detail.lastUpdateTime > 0) Formatters.fullDateTime(detail.lastUpdateTime) else null,
    )
    XgKeyValueRow(
        label = stringResource(R.string.apps_field_primary_abi),
        value = detail.primaryAbi,
    )
    val memoryRow = memoryRowValues(loaded)
    XgKeyValueRow(
        label = stringResource(R.string.apps_field_memory),
        value = memoryRow.first,
        badge = memoryRow.second,
        badgeTone = XgTone.Muted,
    )
    val trafficRow = trafficRowValues(loaded)
    XgKeyValueRow(
        label = stringResource(R.string.apps_field_traffic),
        value = trafficRow.first,
        badge = trafficRow.second,
        badgeTone = XgTone.Muted,
    )
}

@Composable
private fun memoryRowValues(loaded: AppDetailUi): Pair<String?, String?> = when (val memory = loaded.memory) {
    null -> if (loaded.memoryLoading) {
        stringResource(R.string.apps_value_loading) to null
    } else {
        null to null
    }

    MemoryState.NotRunning ->
        stringResource(R.string.apps_memory_not_running) to stringResource(R.string.apps_badge_not_running)

    MemoryState.Unavailable ->
        null to stringResource(R.string.apps_badge_unavailable)

    is MemoryState.Running -> Formatters.bytes(memory.bytes) to null
}

@Composable
private fun trafficRowValues(loaded: AppDetailUi): Pair<String?, String?> = when (val traffic = loaded.traffic) {
    null -> if (loaded.trafficLoading) {
        stringResource(R.string.apps_value_loading) to null
    } else {
        null to null
    }

    TrafficState.Unauthorized, TrafficState.Unavailable ->
        stringResource(R.string.apps_traffic_unauthorized) to stringResource(R.string.apps_badge_unauthorized)

    is TrafficState.Available -> stringResource(
        R.string.apps_traffic_value,
        Formatters.bytes(traffic.rxBytes),
        Formatters.bytes(traffic.txBytes),
    ) to stringResource(R.string.apps_traffic_window, traffic.windowDays)
}

@Composable
private fun DetailActions(
    loaded: AppDetailUi,
    state: AppsUiState,
    viewModel: AppsViewModel,
) {
    val packageName = loaded.detail.packageName
    val busy = state.inFlight != null
    val inFlightKind = state.inFlight?.takeIf { it.packageName == packageName }?.kind
    val clearing = inFlightKind == ActionKind.CLEAR_CACHE || inFlightKind == ActionKind.CLEAR_DATA
    Column(
        modifier = Modifier.padding(top = XgSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(XgSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm),
        ) {
            XgGhostButton(
                text = stringResource(
                    if (loaded.detail.isFrozen) R.string.apps_action_unfreeze else R.string.apps_action_freeze,
                ),
                onClick = { viewModel.toggleFreeze(packageName) },
                enabled = state.canWrite && !busy,
                modifier = Modifier.weight(1f),
            )
            XgDangerButton(
                text = stringResource(R.string.apps_action_uninstall),
                onClick = { viewModel.requestUninstall(packageName) },
                enabled = state.canWrite && !busy,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm),
        ) {
            XgGhostButton(
                text = stringResource(R.string.apps_action_clear_cache),
                onClick = { viewModel.requestClearCache(packageName) },
                enabled = state.canWrite && !busy,
                modifier = Modifier.weight(1f),
            )
            XgDangerButton(
                text = stringResource(R.string.apps_action_clear_data),
                onClick = { viewModel.requestClearData(packageName) },
                enabled = state.canWrite && !busy,
                modifier = Modifier.weight(1f),
            )
        }
        if (clearing) {
            XgNote(text = stringResource(R.string.apps_clearing), tone = XgTone.Muted)
        }
        if (!state.canWrite) {
            AppsWriteBlockedNote(reason = state.writeBlockedReason)
        }
    }
}

@Composable
private fun AbiSection(
    loaded: AppDetailUi,
    state: AppsUiState,
    viewModel: AppsViewModel,
) {
    val status = loaded.abiStatus
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.apps_abi_section_title),
            meta = status?.currentAbi ?: stringResource(R.string.apps_value_unknown),
        )
        if (loaded.abiLoading) {
            XgLoadingSkeleton(lines = 2)
            return@XgPanel
        }
        val abiError = loaded.abiError
        if (abiError != null) {
            XgErrorState(
                code = abiError.code,
                message = abiError.message,
                onRetry = viewModel::retryAbi,
            )
            return@XgPanel
        }
        if (status == null) {
            XgErrorState(
                code = null,
                message = stringResource(R.string.apps_abi_state_missing),
                onRetry = viewModel::retryAbi,
            )
            return@XgPanel
        }
        XgNote(
            text = stringResource(
                R.string.apps_abi_apk_libs,
                status.apkAbis.joinToString(" / ").ifBlank { "—" },
            ),
        )
        val target64 = status.apkAbis.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
        val target32 = status.apkAbis.firstOrNull { it == "armeabi-v7a" || it == "armeabi" || it == "x86" }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm),
        ) {
            XgGhostButton(
                text = stringResource(R.string.apps_abi_64),
                onClick = { target64?.let { viewModel.requestAbiSwitch(loaded.detail.packageName, it) } },
                enabled = state.canWrite && state.inFlight == null &&
                    status.canSwitchTo64 && target64 != null && target64 != status.currentAbi,
                modifier = Modifier.weight(1f),
            )
            XgGhostButton(
                text = stringResource(R.string.apps_abi_32),
                onClick = { target32?.let { viewModel.requestAbiSwitch(loaded.detail.packageName, it) } },
                enabled = state.canWrite && state.inFlight == null &&
                    status.canSwitchTo32 && target32 != null && target32 != status.currentAbi,
                modifier = Modifier.weight(1f),
            )
        }
        val guidance = when {
            status.apkAbis.isEmpty() -> stringResource(R.string.apps_abi_no_native)
            status.canSwitchTo32 || status.canSwitchTo64 -> stringResource(R.string.apps_abi_switch_available)
            else -> stringResource(R.string.apps_abi_cannot_switch, status.reason.orEmpty())
        }
        XgNote(text = guidance, tone = XgTone.Muted)
        if (state.inFlight?.takeIf { it.packageName == loaded.detail.packageName }?.kind == ActionKind.ABI) {
            XgNote(text = stringResource(R.string.apps_abi_switching), tone = XgTone.Muted)
        }
        XgNote(text = stringResource(R.string.apps_abi_rule), tone = XgTone.Muted)
    }
}

private fun buildCopyText(loaded: AppDetailUi, state: AppsUiState): String {
    val detail = loaded.detail
    val usage = state.detail?.takeIf { it.detail.packageName == detail.packageName } ?: loaded
    return buildString {
        append("package=").append(detail.packageName).append('\n')
        append("label=").append(detail.label).append('\n')
        append("version=").append(detail.versionName).append('(').append(detail.versionCode).append(")\n")
        append("signatureSha256=").append(detail.signingSha256).append('\n')
        append("signatureCount=").append(detail.signatureCount).append('\n')
        append("sourceDir=").append(detail.sourceDir).append('\n')
        append("dataDir=").append(detail.dataDir).append('\n')
        append("nativeLibraryDir=").append(detail.nativeLibraryDir ?: "—").append('\n')
        append("primaryAbi=").append(detail.primaryAbi ?: "—").append('\n')
        append("firstInstall=").append(Formatters.fullDateTime(detail.firstInstallTime)).append('\n')
        append("lastUpdate=").append(Formatters.fullDateTime(detail.lastUpdateTime)).append('\n')
        append("uid=").append(detail.uid).append('\n')
        append("frozen=").append(detail.isFrozen).append('\n')
        append("memory=").append(memoryCopyText(usage.memory)).append('\n')
        append("traffic30d=").append(trafficCopyText(usage.traffic)).append('\n')
    }
}

private fun memoryCopyText(memory: MemoryState?): String = when (memory) {
    null -> "unknown"
    MemoryState.NotRunning -> "not-running"
    MemoryState.Unavailable -> "unavailable"
    is MemoryState.Running -> Formatters.bytes(memory.bytes)
}

private fun trafficCopyText(traffic: TrafficState?): String = when (traffic) {
    null -> "unknown"
    TrafficState.Unauthorized -> "unauthorized"
    TrafficState.Unavailable -> "unavailable"
    is TrafficState.Available -> "rx=${Formatters.bytes(traffic.rxBytes)} tx=${Formatters.bytes(traffic.txBytes)}"
}

