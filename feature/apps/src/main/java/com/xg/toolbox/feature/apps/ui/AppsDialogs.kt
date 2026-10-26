package com.xg.toolbox.feature.apps.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgConfirmDialog
import com.xg.toolbox.feature.apps.AppsDialog
import com.xg.toolbox.feature.apps.AppsUiState
import com.xg.toolbox.feature.apps.AppsViewModel
import com.xg.toolbox.feature.apps.R

@Composable
fun AppsDialogs(
    state: AppsUiState,
    viewModel: AppsViewModel,
) {
    when (val dialog = state.dialog) {
        null -> Unit

        is AppsDialog.UninstallConfirm -> {
            val intro = stringResource(
                R.string.apps_uninstall_body,
                dialog.label,
                dialog.packageName,
                Formatters.bytes(dialog.apkSizeBytes),
            )
            val backupHint = stringResource(
                R.string.apps_uninstall_backup_hint,
                Formatters.bytes(dialog.backupLimitBytes),
            )
            val tooLarge = dialog.apkSizeBytes > dialog.backupLimitBytes
            val body = if (tooLarge) {
                intro + "\n" + backupHint + "\n" + stringResource(R.string.apps_uninstall_too_large)
            } else {
                intro + "\n" + backupHint
            }
            XgConfirmDialog(
                title = stringResource(R.string.apps_uninstall_title),
                body = body,
                confirmText = stringResource(R.string.apps_action_uninstall_confirm),
                danger = true,
                onConfirm = viewModel::confirmDialog,
                onDismiss = viewModel::dismissDialog,
            )
        }

        is AppsDialog.AbiConfirm -> {
            val currentAbi = dialog.currentAbi ?: stringResource(R.string.apps_value_unknown)
            XgConfirmDialog(
                title = stringResource(R.string.apps_abi_confirm_title),
                body = stringResource(
                    R.string.apps_abi_confirm_body,
                    dialog.label,
                    currentAbi,
                    dialog.targetAbi,
                ),
                confirmText = stringResource(R.string.apps_abi_confirm_action),
                onConfirm = viewModel::confirmDialog,
                onDismiss = viewModel::dismissDialog,
            )
        }

        is AppsDialog.ClearCacheConfirm -> XgConfirmDialog(
            title = stringResource(R.string.apps_clear_cache_title),
            body = stringResource(R.string.apps_clear_cache_body, dialog.label),
            confirmText = stringResource(R.string.apps_clear_cache_confirm),
            danger = false,
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )

        is AppsDialog.ClearDataConfirm -> XgConfirmDialog(
            title = stringResource(R.string.apps_clear_data_title),
            body = stringResource(R.string.apps_clear_data_body, dialog.label),
            confirmText = stringResource(R.string.apps_clear_data_confirm),
            danger = true,
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )

        is AppsDialog.ModuleRemoveConfirm -> XgConfirmDialog(
            title = stringResource(R.string.apps_module_remove_title),
            body = stringResource(
                R.string.apps_module_remove_body,
                dialog.module.name,
                dialog.module.version,
                dialog.module.id,
            ),
            confirmText = stringResource(R.string.apps_remove_confirm),
            danger = true,
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )

        is AppsDialog.SsaidConfirm -> XgConfirmDialog(
            title = stringResource(R.string.apps_ssaid_confirm_title),
            body = stringResource(
                R.string.apps_ssaid_confirm_body,
                dialog.label,
                dialog.packageName,
            ),
            confirmText = stringResource(R.string.apps_ssaid_confirm_action),
            danger = true,
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )

        is AppsDialog.GlobalIdConfirm -> XgConfirmDialog(
            title = stringResource(R.string.apps_android_id_global_confirm_title),
            body = stringResource(
                R.string.apps_android_id_global_confirm_body,
                dialog.entryCount,
                dialog.id16,
            ),
            confirmText = stringResource(R.string.apps_android_id_global_confirm_action),
            danger = true,
            onConfirm = viewModel::confirmDialog,
            onDismiss = viewModel::dismissDialog,
        )
    }
    state.rebootPrompt?.let { message ->
        XgConfirmDialog(
            title = stringResource(R.string.apps_reboot_title),
            body = message,
            confirmText = stringResource(R.string.apps_reboot_confirm),
            dismissText = stringResource(R.string.apps_reboot_later),
            danger = false,
            onConfirm = viewModel::rebootNow,
            onDismiss = viewModel::dismissRebootPrompt,
        )
    }
}

