package com.xg.toolbox.feature.apps.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.R as XgUiR
import com.xg.toolbox.core.ui.component.XgCopyButton
import com.xg.toolbox.core.ui.component.XgDangerButton
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgFieldLabel
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgPrimaryButton
import com.xg.toolbox.core.ui.component.XgSearchField
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.apps.AndroidIdUiState
import com.xg.toolbox.feature.apps.AppRowUi
import com.xg.toolbox.feature.apps.AppsUiState
import com.xg.toolbox.feature.apps.AppsViewModel
import com.xg.toolbox.feature.apps.R

@Composable
fun AndroidIdPage(
    state: AppsUiState,
    onBack: () -> Unit,
    viewModel: AppsViewModel,
) {
    val android = state.androidId
    XgSubPageScaffold(
        title = stringResource(R.string.apps_android_id_title),
        meta = stringResource(R.string.apps_android_id_meta),
        onBack = onBack,
    ) {
        item(key = "android_id_intro") {
            Column {
                XgNote(text = stringResource(R.string.apps_android_id_intro), tone = XgTone.Muted)
                AppsWriteBlockedNote(reason = state.writeBlockedReason)
            }
        }
        item(key = "android_id_global") {
            GlobalIdPanel(
                android = android,
                canWrite = state.canWrite,
                onInput = viewModel::setGlobalAndroidIdInput,
                onGenerate = viewModel::generateGlobalAndroidId,
                onApply = viewModel::requestGlobalAndroidId,
                onRetryEntries = viewModel::loadGlobalEntryCount,
            )
        }
        item(key = "android_id_apps_header") {
            Column {
                XgFieldLabel(text = stringResource(R.string.apps_ssaid_apps_title))
                XgSearchField(
                    value = android.query,
                    onValueChange = viewModel::setAndroidIdQuery,
                    placeholder = stringResource(R.string.apps_search_placeholder),
                )
            }
        }
        if (android.targetPackage != null) {
            item(key = "android_id_target") {
                SsaidTargetPanel(
                    android = android,
                    canWrite = state.canWrite,
                    onApply = viewModel::requestSsaidApply,
                    onChangeTarget = viewModel::clearSsaidTarget,
                )
            }
        }
        val appsError = android.appsError
        when {
            android.appsLoading -> item(key = "android_id_apps_loading") {
                XgLoadingSkeleton(lines = 4)
            }

            appsError != null -> item(key = "android_id_apps_error") {
                XgErrorState(
                    code = appsError.code,
                    message = appsError.message,
                    onRetry = viewModel::loadAndroidIdApps,
                )
            }

            android.userApps.isNotEmpty() && android.visibleApps.isEmpty() -> item(key = "android_id_apps_nomatch") {
                XgEmptyState(
                    title = stringResource(R.string.apps_empty_filtered),
                    actionText = stringResource(R.string.apps_action_clear_filter),
                    onAction = { viewModel.setAndroidIdQuery("") },
                )
            }

            android.visibleApps.isEmpty() -> item(key = "android_id_apps_empty") {
                XgEmptyState(
                    title = stringResource(R.string.apps_ssaid_apps_empty),
                    actionText = stringResource(R.string.apps_action_reload),
                    onAction = viewModel::loadAndroidIdApps,
                )
            }

            else -> items(
                items = android.visibleApps,
                key = { app: AppRowUi -> "ssaid_${app.packageName}" },
                contentType = { "ssaid_app" },
            ) { app ->
                SsaidAppRow(
                    app = app,
                    selected = app.packageName == android.targetPackage,
                    onClick = { viewModel.selectAndroidIdTarget(app.packageName) },
                    viewModel = viewModel,
                )
            }
        }
        item(key = "android_id_capability") {
            Column {
                XgHairline()
                XgNote(text = stringResource(R.string.apps_ssaid_capability_process), tone = XgTone.Muted)
                XgNote(text = stringResource(R.string.apps_ssaid_capability_limit), tone = XgTone.Warn)
            }
        }
    }
}

@Composable
private fun GlobalIdPanel(
    android: AndroidIdUiState,
    canWrite: Boolean,
    onInput: (String) -> Unit,
    onGenerate: () -> Unit,
    onApply: () -> Unit,
    onRetryEntries: () -> Unit,
) {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.apps_android_id_global),
            trailing = {
                android.globalOutcome?.let { outcome ->
                    XgBadge(
                        text = stringResource(R.string.apps_android_id_written),
                        tone = XgTone.Ok,
                    )
                    XgBadge(
                        text = "${outcome.rewrittenEntries}/${outcome.totalEntries}",
                        tone = XgTone.Ok,
                    )
                }
            },
        )
        XgNote(text = stringResource(R.string.apps_android_id_global_scope), tone = XgTone.Muted)
        val entriesError = android.globalEntryCountError
        val entryCount = android.globalEntryCount
        when {
            android.globalEntryCountLoading -> Text(
                text = stringResource(R.string.apps_android_id_entries_loading),
                style = XgType.note,
                color = LocalXgColors.current.faint,
            )

            entriesError != null -> XgErrorState(
                code = entriesError.code,
                message = entriesError.message,
                onRetry = onRetryEntries,
            )

            entryCount != null -> XgNote(
                text = stringResource(R.string.apps_android_id_entries, entryCount),
                tone = XgTone.Warn,
            )
        }
        AppsHexField(
            value = android.globalInput,
            onValueChange = onInput,
            placeholder = stringResource(R.string.apps_android_id_placeholder),
            enabled = canWrite && !android.globalApplying,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm),
        ) {
            XgTextAction(
                text = stringResource(R.string.apps_android_id_generate),
                onClick = onGenerate,
                enabled = canWrite && !android.globalApplying,
            )
            Spacer(Modifier.weight(1f))
            XgPrimaryButton(
                text = stringResource(
                    if (android.globalApplying) R.string.apps_android_id_writing
                    else R.string.apps_android_id_write_verify,
                ),
                onClick = onApply,
                enabled = canWrite && Formatters.isValidAndroidId(android.globalInput) && !android.globalApplying,
            )
        }
        android.globalError?.let { error ->
            XgNote(text = error, tone = XgTone.Danger)
        }
        android.globalOutcome?.let { outcome ->
            XgNote(
                text = stringResource(
                    R.string.apps_android_id_global_outcome,
                    outcome.rewrittenEntries,
                    outcome.totalEntries,
                ),
                tone = XgTone.Ok,
            )
            if (outcome.requiresReboot) {
                XgNote(text = stringResource(R.string.apps_android_id_global_reboot), tone = XgTone.Warn)
            }
        }
        XgNote(text = stringResource(R.string.apps_android_id_global_restart), tone = XgTone.Muted)
        XgNote(text = stringResource(R.string.apps_android_id_regex), tone = XgTone.Muted)
    }
}

@Composable
private fun SsaidTargetPanel(
    android: AndroidIdUiState,
    canWrite: Boolean,
    onApply: () -> Unit,
    onChangeTarget: () -> Unit,
) {
    val colors = LocalXgColors.current
    val packageName = android.targetPackage ?: return
    val currentSsaidError = android.currentSsaidError
    val currentSsaidValue = android.currentSsaid
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.apps_android_id_ssaid),
            meta = android.targetLabel ?: packageName,
            trailing = {
                if (android.ssaidApplied) {
                    XgBadge(text = stringResource(R.string.apps_ssaid_reset_badge), tone = XgTone.Ok)
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            XgMono(text = packageName, style = XgType.monoSmall, color = colors.muted)
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.apps_ssaid_change_target),
                onClick = onChangeTarget,
                tone = XgTone.Muted,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.apps_ssaid_current_label),
                style = XgType.label,
                color = colors.muted,
                modifier = Modifier.width(96.dp),
            )
            when {
                android.currentSsaidLoading -> Text(
                    text = stringResource(R.string.apps_value_loading),
                    style = XgType.note,
                    color = colors.faint,
                )

                currentSsaidError != null -> Text(
                    text = stringResource(
                        R.string.apps_ssaid_current_error,
                        currentSsaidError.message,
                    ),
                    style = XgType.note,
                    color = colors.danger,
                )

                currentSsaidValue != null -> Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        XgMono(
                            text = currentSsaidValue,
                            style = XgType.monoSmall,
                            color = colors.ink,
                        )
                    }
                    XgCopyButton(
                        text = currentSsaidValue,
                        contentDescription = stringResource(
                            XgUiR.string.xg_copy_content_description,
                            stringResource(R.string.apps_ssaid_current_label),
                        ),
                    )
                }

                else -> Text(
                    text = stringResource(R.string.apps_ssaid_current_none),
                    style = XgType.note,
                    color = colors.faint,
                )
            }
        }
        Spacer(Modifier.height(XgSpacing.sm))
        XgDangerButton(
            text = stringResource(R.string.apps_ssaid_reset),
            onClick = onApply,
            enabled = canWrite && !android.ssaidApplying,
            modifier = Modifier.fillMaxWidth(),
        )
        android.ssaidError?.let { error ->
            XgNote(text = error, tone = XgTone.Danger)
        }
        if (android.ssaidApplied) {
            XgNote(text = stringResource(R.string.apps_ssaid_ok), tone = XgTone.Ok)
        }
    }
}

@Composable
private fun SsaidAppRow(
    app: AppRowUi,
    selected: Boolean,
    onClick: () -> Unit,
    viewModel: AppsViewModel,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(vertical = XgSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            packageName = app.packageName,
            versionCode = app.versionCode,
            label = app.label,
            viewModel = viewModel,
            size = XgSize.avatar,
        )
        Spacer(Modifier.width(XgSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.label,
                style = XgType.rowTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(XgSpacing.sm))
        if (selected) {
            XgBadge(text = stringResource(R.string.apps_ssaid_selected), tone = XgTone.Accent)
        } else {
            XgTextAction(
                text = stringResource(R.string.apps_ssaid_select),
                onClick = onClick,
                tone = XgTone.Accent,
            )
        }
    }
}
