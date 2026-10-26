package com.xg.toolbox.feature.security.settings

import android.content.res.Configuration
import android.os.SystemClock
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.xg.toolbox.core.clone.ClonePhase
import com.xg.toolbox.core.ui.component.XgConfirmDialog
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgIconButton
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPrimaryButton
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgSwitch
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.liquid.xgPageSurfaceColor
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.security.R
import java.io.File
import java.util.concurrent.atomic.AtomicLong

private const val CLICK_DEBOUNCE_MS = 600L

@Composable
private fun rememberClickGate(cooldownMs: Long = CLICK_DEBOUNCE_MS): () -> Boolean {
    val lastClickAt = remember { AtomicLong(0L) }
    return remember(cooldownMs) {
        fun allowed(): Boolean {
            val now = SystemClock.elapsedRealtime()
            while (true) {
                val previous = lastClickAt.get()
                if (now - previous < cooldownMs) return false
                if (lastClickAt.compareAndSet(previous, now)) return true
            }
        }
        { allowed() }
    }
}

@Composable
fun SettingsRoute() {
    val viewModel: SettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backgroundPickerGate = rememberClickGate()
    val backgroundPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importBackground(uri)
    }

    SettingsScreen(
        state = state,
        onBackgroundToggle = viewModel::onCustomBackgroundEnabledChange,
        onPickBackground = {
            if (backgroundPickerGate()) {
                runCatching { backgroundPicker.launch(arrayOf("image/*")) }
            }
        },
        onRefreshRoot = viewModel::refreshRootStatus,
        onAutostartToggle = viewModel::onAutostartEnabledChange,
        onKeepAliveToggle = viewModel::onRootKeepAliveEnabledChange,
        onProvisionPrivileges = viewModel::provisionPrivileges,
        onSuInputChange = viewModel::onSuInputChanged,
        onTestAndSaveSu = viewModel::testAndSaveSuPath,
        onRestoreAutoSu = viewModel::restoreAutoSuPath,
        onRequestHideSelf = viewModel::requestHideSelf,
        onConfirmHideSelf = viewModel::confirmHideSelf,
        onDismissHideSelf = viewModel::dismissHideConfirm,
    )
}

@Composable
private fun SettingsScreen(
    state: SettingsUiState,
    onBackgroundToggle: (Boolean) -> Unit,
    onPickBackground: () -> Unit,
    onRefreshRoot: () -> Unit,
    onAutostartToggle: (Boolean) -> Unit,
    onKeepAliveToggle: (Boolean) -> Unit,
    onProvisionPrivileges: () -> Unit,
    onSuInputChange: (String) -> Unit,
    onTestAndSaveSu: () -> Unit,
    onRestoreAutoSu: () -> Unit,
    onRequestHideSelf: () -> Unit,
    onConfirmHideSelf: () -> Unit,
    onDismissHideSelf: () -> Unit,
) {
    var showBackgroundPreview by rememberSaveable { mutableStateOf(false) }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val suTestGate = rememberClickGate()
    val suRestoreGate = rememberClickGate()
    val hideConfirmGate = rememberClickGate()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(xgPageSurfaceColor())
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = XgSpacing.screen,
            end = XgSpacing.screen,
            top = XgSpacing.sm,
            bottom = XgSpacing.contentBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(XgSpacing.md),
    ) {
        item(key = "root") {
            SettingsSection(title = stringResource(R.string.settings_root_section)) {
                SettingsValueRow(
                    label = stringResource(R.string.settings_root_status),
                    value = when {
                        state.rootStatusRefreshing -> stringResource(R.string.settings_root_checking)
                        state.rootStatus.rootGranted -> stringResource(R.string.settings_root_granted)
                        else -> stringResource(R.string.settings_root_denied)
                    },
                    valueTone = if (state.rootStatus.rootGranted) XgTone.Ok else XgTone.Muted,
                    action = stringResource(R.string.settings_root_retry),
                    actionEnabled = !state.privilegeBusy && !state.rootStatusRefreshing,
                    onAction = onRefreshRoot,
                )
                XgHairline()
                SettingsSwitchRow(
                    label = stringResource(R.string.settings_autostart),
                    checked = state.autostartEnabled,
                    enabled = true,
                    onCheckedChange = onAutostartToggle,
                )
                state.autostartMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = XgType.note,
                        color = LocalXgColors.current.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                XgHairline()
                SettingsSwitchRow(
                    label = stringResource(R.string.settings_keepalive),
                    checked = state.rootKeepAliveEnabled,
                    enabled = !state.keepAliveBusy,
                    onCheckedChange = onKeepAliveToggle,
                )
                state.keepAliveMessage?.takeIf { it.isNotBlank() }?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = message,
                        style = XgType.note,
                        color = LocalXgColors.current.muted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                XgHairline()
                SettingsActionRow(
                    label = stringResource(R.string.settings_provision),
                    action = stringResource(R.string.settings_provision_action),
                    enabled = !state.privilegeBusy,
                    tone = XgTone.Accent,
                    onClick = onProvisionPrivileges,
                )
                state.privilegeOutput?.takeIf { it.isNotBlank() }?.let { output ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = output,
                        style = XgType.note,
                        color = if (state.privilegeOutputIsError) {
                            LocalXgColors.current.danger
                        } else {
                            LocalXgColors.current.muted
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item(key = "su") {
            SettingsSection(title = stringResource(R.string.settings_su_section)) {
                SettingsValueRow(
                    label = stringResource(R.string.settings_su_current),
                    value = state.suEffectivePath.ifBlank { stringResource(R.string.settings_su_auto) },
                )
                XgHairline()
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    OutlinedTextField(
                        value = state.suInput,
                        onValueChange = onSuInputChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.settings_su_input)) },
                        placeholder = { Text(state.suEffectivePath.ifBlank { "/system/bin/su" }) },
                        textStyle = XgType.monoSmall.copy(fontSize = 13.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        XgPrimaryButton(
                            text = if (state.suBusy) {
                                stringResource(R.string.settings_su_testing)
                            } else {
                                stringResource(R.string.settings_su_test)
                            },
                            onClick = { if (suTestGate()) onTestAndSaveSu() },
                            enabled = !state.suBusy,
                            modifier = Modifier.weight(1f),
                        )
                        XgGhostButton(
                            text = stringResource(R.string.settings_su_restore),
                            onClick = { if (suRestoreGate()) onRestoreAutoSu() },
                            enabled = !state.suBusy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!state.suMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = state.suMessage,
                            style = XgType.note,
                            color = if (state.suMessageIsError) {
                                LocalXgColors.current.danger
                            } else {
                                LocalXgColors.current.muted
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        item(key = "hide") {
            SettingsSection(title = stringResource(R.string.settings_hide_section)) {
                SettingsActionRow(
                    label = stringResource(R.string.settings_hide_self),
                    action = stringResource(R.string.settings_hide_action),
                    enabled = !state.hideRunning,
                    tone = XgTone.Danger,
                    onClick = onRequestHideSelf,
                )
                if (state.hideRunning) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalXgColors.current.accent,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = clonePhaseText(state.cloneProgress?.phase),
                            style = XgType.note,
                            color = LocalXgColors.current.muted,
                        )
                    }
                }
                if (!state.hideError.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_hide_failed, state.hideError),
                        style = XgType.note,
                        color = LocalXgColors.current.danger,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!state.hideResultPackage.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_hide_new_package, state.hideResultPackage),
                        style = XgType.monoSmall,
                        color = LocalXgColors.current.ok,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        item(key = "appearance") {
            SettingsSection(title = stringResource(R.string.settings_appearance_section)) {
                SettingsSwitchRow(
                    label = stringResource(R.string.settings_background_toggle),
                    checked = state.customBackgroundEnabled,
                    enabled = !state.backgroundImporting,
                    leadingContent = {
                        XgIconButton(
                            icon = Icons.Outlined.Visibility,
                            contentDescription = stringResource(R.string.settings_background_view),
                            onClick = { showBackgroundPreview = true },
                        )
                    },
                    onCheckedChange = { enabled ->
                        onBackgroundToggle(enabled)
                        if (enabled && state.backgroundFile == null) onPickBackground()
                    },
                )
                Spacer(Modifier.height(10.dp))
                XgGhostButton(
                    text = if (state.backgroundFile == null) {
                        stringResource(R.string.settings_background_pick)
                    } else {
                        stringResource(R.string.settings_background_change)
                    },
                    onClick = onPickBackground,
                    enabled = !state.backgroundImporting,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.backgroundMissing) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_background_missing),
                        style = XgType.note,
                        color = LocalXgColors.current.danger,
                    )
                }
                if (state.backgroundImporting) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_background_importing),
                        style = XgType.note,
                        color = LocalXgColors.current.muted,
                    )
                } else if (!state.backgroundMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.backgroundMessage,
                        style = XgType.note,
                        color = if (state.backgroundMessageIsError) {
                            LocalXgColors.current.danger
                        } else {
                            LocalXgColors.current.muted
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    if (showBackgroundPreview) {
        BackgroundPreviewDialog(
            backgroundFile = state.backgroundFile,
            useCustom = state.customBackgroundEnabled && state.backgroundFile != null,
            isLandscape = isLandscape,
            onDismiss = { showBackgroundPreview = false },
        )
    }

    if (state.hideConfirmVisible) {
        XgConfirmDialog(
            title = stringResource(R.string.settings_hide_confirm_title),
            body = stringResource(R.string.settings_hide_confirm_body),
            confirmText = stringResource(R.string.settings_hide_confirm_action),
            dismissText = stringResource(R.string.settings_cancel),
            danger = true,
            onConfirm = { if (hideConfirmGate()) onConfirmHideSelf() },
            onDismiss = onDismissHideSelf,
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        XgSectionHeader(title = title)
        Spacer(Modifier.height(8.dp))
        XgPanel {
            content()
        }
    }
}

@Composable
private fun SettingsValueRow(
    label: String,
    value: String,
    valueTone: XgTone = XgTone.Muted,
    action: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    val colors = LocalXgColors.current
    val allowAction = rememberClickGate()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = XgType.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = XgType.monoSmall.copy(fontSize = 12.sp),
            color = when (valueTone) {
                XgTone.Ok -> colors.ok
                XgTone.Danger -> colors.danger
                XgTone.Warn -> colors.warn
                else -> colors.muted
            },
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = action,
                style = XgType.chip,
                color = if (actionEnabled) colors.accent else colors.faint,
                modifier = Modifier
                    .clickable(
                        enabled = actionEnabled,
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClick = { if (allowAction()) onAction?.invoke() },
                    )
                    .padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    leadingContent: (@Composable () -> Unit)? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalXgColors.current
    val allowToggle = rememberClickGate()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = XgType.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = if (enabled) colors.ink else colors.muted,
            modifier = Modifier.weight(1f),
        )
        if (leadingContent != null) {
            leadingContent()
            Spacer(Modifier.width(4.dp))
        }
        XgSwitch(
            checked = checked,
            onCheckedChange = { value -> if (allowToggle()) onCheckedChange(value) },
            enabled = enabled,
        )
    }
}

@Composable
private fun BackgroundPreviewDialog(
    backgroundFile: File?,
    useCustom: Boolean,
    isLandscape: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalXgColors.current
    var customFailed by remember(backgroundFile, useCustom) { mutableStateOf(false) }
    var builtInFailed by remember(isLandscape) { mutableStateOf(false) }
    val builtInUri = remember(context, isLandscape) {
        val direction = if (isLandscape) "landscape" else "portrait"
        "android.resource://${context.packageName}/drawable/xg_builtin_${direction}_background"
    }
    val customModel = backgroundFile?.takeIf { useCustom && !customFailed }
    val model: Any? = customModel ?: builtInUri.takeUnless { builtInFailed }
    val title = stringResource(R.string.settings_background_view_title)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .padding(16.dp),
            ) {
                Text(
                    text = title,
                    style = XgType.cardTitle,
                    color = colors.ink,
                )
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isLandscape) 160.dp else 260.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    if (model != null) {
                        AsyncImage(
                            model = model,
                            contentDescription = stringResource(R.string.settings_background_view_image),
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.Center,
                            modifier = Modifier.fillMaxSize(),
                            onError = {
                                if (customModel != null) {
                                    customFailed = true
                                } else {
                                    builtInFailed = true
                                }
                            },
                        )
                    }
                    if (builtInFailed || model == null) {
                        Text(
                            text = stringResource(R.string.settings_background_view_unavailable),
                            style = XgType.note,
                            color = colors.muted,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                XgGhostButton(
                    text = stringResource(R.string.settings_background_view_close),
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SettingsActionRow(
    label: String,
    action: String,
    enabled: Boolean,
    tone: XgTone,
    onClick: () -> Unit,
) {
    val colors = LocalXgColors.current
    val allowClick = rememberClickGate()
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = XgType.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = action,
            style = XgType.chip,
            color = when (tone) {
                XgTone.Danger -> colors.danger
                XgTone.Warn -> colors.warn
                XgTone.Ok -> colors.ok
                else -> colors.accent
            },
            modifier = Modifier
                .clickable(
                    enabled = enabled,
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClick = { if (allowClick()) onClick() },
                )
                .padding(horizontal = 4.dp, vertical = 10.dp),
        )
    }
}

private fun clonePhaseText(phase: ClonePhase?): String = when (phase) {
    ClonePhase.PREPARING -> "准备中"
    ClonePhase.PATCHING -> "生成新包"
    ClonePhase.SIGNING -> "签名中"
    ClonePhase.INSTALLING -> "安装中"
    ClonePhase.MIGRATING -> "迁移数据"
    ClonePhase.DONE -> "已完成"
    ClonePhase.FAILED -> "失败"
    else -> "处理中"
}
