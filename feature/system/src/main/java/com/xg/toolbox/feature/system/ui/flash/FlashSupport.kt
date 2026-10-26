package com.xg.toolbox.feature.system.ui.flash

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgConfirmDialog
import com.xg.toolbox.core.ui.component.XgDangerButton
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.system.domain.flash.FlashBackup
import com.xg.toolbox.feature.system.domain.flash.FlashImageInfo
import com.xg.toolbox.feature.system.R

@Composable
internal fun rememberFlashImagePicker(viewModel: FlashViewModel): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.inspectImage(uri.toString())
        }
    }
    return { launcher.launch(arrayOf("*/*")) }
}

@Composable
internal fun FlashConfirmDialogs(
    confirmFlash: Boolean,
    image: FlashImageInfo?,
    targetPartition: String?,
    backup: FlashBackup?,
    skipBackup: Boolean,
    confirmSlot: Boolean,
    currentSlot: String?,
    viewModel: FlashViewModel,
) {
    if (confirmFlash && image != null) {
        val target = targetPartition.orEmpty()
        val backupText = if (skipBackup) {
            stringResource(R.string.sys_tune_flash_confirm_skip_backup)
        } else {
            backup?.path ?: stringResource(R.string.sys_tune_flash_confirm_no_backup)
        }
        XgConfirmDialog(
            title = stringResource(R.string.sys_tune_flash_confirm_title),
            body = stringResource(
                R.string.sys_tune_flash_confirm_body,
                target,
                image.displayName,
                Formatters.bytes(image.sizeBytes),
                image.sha256,
                backupText,
            ),
            confirmText = stringResource(R.string.sys_tune_flash_write, target),
            dismissText = stringResource(R.string.sys_tune_action_cancel),
            danger = true,
            onConfirm = viewModel::confirmFlash,
            onDismiss = viewModel::cancelFlashConfirm,
        )
    }

    if (confirmSlot) {
        val targetSlot = slotSwitchTargetText(currentSlot)
        XgConfirmDialog(
            title = stringResource(R.string.sys_tune_flash_slot_confirm_title),
            body = stringResource(R.string.sys_tune_flash_slot_confirm_body, targetSlot),
            confirmText = stringResource(R.string.sys_tune_flash_slot_switch),
            dismissText = stringResource(R.string.sys_tune_action_cancel),
            onConfirm = viewModel::confirmSlotSwitch,
            onDismiss = viewModel::cancelSlotConfirm,
        )
    }
}

@Composable
internal fun FlashRiskDialogs(
    riskConfirm: FlashRiskConfirm?,
    skipBackup: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val confirm = riskConfirm ?: return
    val skipWarning = if (skipBackup) {
        "\n" + stringResource(R.string.sys_tune_flash_skip_backup_warning)
    } else {
        ""
    }
    when (confirm.risk) {
        FlashPartitionRisk.SAFE -> Unit
        FlashPartitionRisk.CAUTION -> XgConfirmDialog(
            title = stringResource(R.string.sys_tune_flash_risk_confirm_title),
            body = stringResource(R.string.sys_tune_flash_risk_caution_body, confirm.partition) + skipWarning,
            confirmText = stringResource(R.string.sys_tune_flash_risk_continue),
            dismissText = stringResource(R.string.sys_tune_action_cancel),
            danger = true,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )

        FlashPartitionRisk.DANGEROUS -> FlashDangerousRiskDialog(
            partition = confirm.partition,
            skipBackup = skipBackup,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
        )
    }
}

@Composable
private fun FlashDangerousRiskDialog(
    partition: String,
    skipBackup: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalXgColors.current
    var acknowledged by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            XgPanel(modifier = Modifier.fillMaxWidth().widthIn(max = 330.dp)) {
                Text(
                    text = stringResource(R.string.sys_tune_flash_risk_confirm_title),
                    style = XgType.dialogTitle,
                    color = colors.danger,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = stringResource(R.string.sys_tune_flash_risk_dangerous_body, partition),
                    style = XgType.body,
                    color = colors.muted,
                )
                if (skipBackup) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        text = stringResource(R.string.sys_tune_flash_skip_backup_warning),
                        style = XgType.note,
                        color = colors.danger,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.sys_tune_flash_risk_dangerous_check),
                        style = XgType.note,
                        color = colors.ink,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    XgGhostButton(
                        text = stringResource(R.string.sys_tune_action_cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    XgDangerButton(
                        text = stringResource(R.string.sys_tune_flash_risk_continue),
                        onClick = onConfirm,
                        enabled = acknowledged,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}