package com.xg.toolbox.feature.system.ui.perf

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xg.toolbox.core.ui.component.XgConfirmDialog
import com.xg.toolbox.feature.system.R

@Composable
internal fun PerfConfirmDialogs(confirm: PerfConfirm?, viewModel: PerfViewModel) {
    when (confirm) {
        is PerfConfirm.Apply -> XgConfirmDialog(
            title = stringResource(R.string.sys_tune_perf_confirm_apply_title),
            body = stringResource(
                R.string.sys_tune_perf_confirm_apply_body,
                confirm.controlName,
                confirm.fromLabel,
                confirm.toLabel,
            ),
            confirmText = stringResource(R.string.sys_tune_action_apply),
            dismissText = stringResource(R.string.sys_tune_action_cancel),
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissConfirm,
        )

        PerfConfirm.Restore -> XgConfirmDialog(
            title = stringResource(R.string.sys_tune_perf_confirm_restore_title),
            body = stringResource(R.string.sys_tune_perf_confirm_restore_body),
            confirmText = stringResource(R.string.sys_tune_action_restore_defaults),
            dismissText = stringResource(R.string.sys_tune_action_cancel),
            onConfirm = viewModel::confirm,
            onDismiss = viewModel::dismissConfirm,
        )

        null -> Unit
    }
}
