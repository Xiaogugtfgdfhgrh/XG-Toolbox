package com.xg.toolbox.feature.apps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgIconButton
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgSwitch
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.apps.AppsUiState
import com.xg.toolbox.feature.apps.AppsViewModel
import com.xg.toolbox.feature.apps.ModulesUiState
import com.xg.toolbox.feature.apps.R
import com.xg.toolbox.feature.apps.domain.ModuleInfo

@Composable
fun ModulesPage(
    state: AppsUiState,
    onBack: () -> Unit,
    viewModel: AppsViewModel,
) {
    XgSubPageScaffold(
        title = stringResource(R.string.apps_modules_title),
        meta = stringResource(R.string.apps_modules_meta, state.modules.modules.size),
        onBack = onBack,
        actions = {
            XgTextAction(
                text = stringResource(R.string.apps_action_refresh),
                onClick = { viewModel.loadModules(force = true) },
            )
        },
    ) {
        item(key = "modules_content") {
            val modules = state.modules
            XgContentTransition(
                stateKey = modules.modules,
                loading = modules.loading && modules.modules.isEmpty(),
                error = modules.error?.message,
                onRetry = { viewModel.loadModules(force = true) },
                modifier = Modifier.fillMaxWidth(),
                skeletonLines = 3,
            ) {
                ModulesContent(
                    modules = modules,
                    canWrite = state.canWrite,
                    writeBlockedReason = state.writeBlockedReason,
                    onToggle = viewModel::setModuleEnabled,
                    onRemove = viewModel::requestRemoveModule,
                    onRetry = { viewModel.loadModules(force = true) },
                )
            }
        }
        if (state.modules.refreshing) {
            item(key = "modules_refreshing") {
                XgNote(text = stringResource(R.string.apps_value_loading), tone = XgTone.Muted)
            }
        }
    }
}

@Composable
private fun ModulesContent(
    modules: ModulesUiState,
    canWrite: Boolean,
    writeBlockedReason: String?,
    onToggle: (ModuleInfo, Boolean) -> Unit,
    onRemove: (ModuleInfo) -> Unit,
    onRetry: () -> Unit,
) {
    Column {
    if (modules.modules.isEmpty()) {
        XgEmptyState(
            title = stringResource(R.string.apps_modules_empty),
            actionText = stringResource(R.string.apps_action_reload),
            onAction = onRetry,
        )
        AppsWriteBlockedNote(reason = writeBlockedReason)
        return@Column
    }
    XgPanel {
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            modules.modules.forEachIndexed { index, module ->
                ModuleRow(
                    module = module,
                    canWrite = canWrite,
                    busy = modules.changingId == module.id || modules.removingId == module.id,
                    onToggle = { enabled -> onToggle(module, enabled) },
                    onRemove = { onRemove(module) },
                )
                if (index != modules.modules.lastIndex) {
                    XgHairline()
                }
            }
        }
    }
    modules.lastActionError?.let { message ->
        XgNote(text = message, tone = XgTone.Danger)
    }
    AppsWriteBlockedNote(reason = writeBlockedReason)
    }
}

@Composable
private fun ModuleRow(
    module: ModuleInfo,
    canWrite: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalXgColors.current
    val markShape = RoundedCornerShape(XgRadius.small)
    val switchDescription = stringResource(R.string.apps_module_switch_desc, module.name)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = XgSize.rowMin).padding(vertical = XgSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(XgSize.markBox)
                .clip(markShape)
                .background(colors.inset)
                .border(1.dp, colors.line, markShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = moduleNameShort(module.name),
                style = XgType.monoSmall,
                color = colors.muted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(XgSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = module.name,
                    style = XgType.rowTitle,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (module.pendingRemove) {
                    Spacer(Modifier.width(XgSpacing.xs))
                    XgBadge(text = stringResource(R.string.apps_module_pending_remove), tone = XgTone.Warn)
                }
            }
            Text(
                text = module.id,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.apps_module_meta, module.version, module.author),
                style = XgType.note,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (module.description.isNotBlank()) {
                Text(
                    text = module.description,
                    style = XgType.note,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(XgSpacing.sm))
        Column(horizontalAlignment = Alignment.End) {
            XgBadge(
                text = stringResource(
                    if (module.enabled) R.string.apps_module_enabled else R.string.apps_module_disabled,
                ),
                tone = if (module.enabled) XgTone.Ok else XgTone.Muted,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                XgSwitch(
                    checked = module.enabled,
                    onCheckedChange = onToggle,
                    enabled = canWrite && !busy,
                    contentDescription = switchDescription,
                )
                XgIconButton(
                    icon = Icons.Outlined.DeleteOutline,
                    contentDescription = stringResource(R.string.apps_module_remove, module.name),
                    onClick = onRemove,
                    enabled = canWrite && !busy,
                )
            }
        }
    }
}

private fun moduleNameShort(name: String): String = name
    .filterNot { it.isWhitespace() }
    .take(2)
    .uppercase()

