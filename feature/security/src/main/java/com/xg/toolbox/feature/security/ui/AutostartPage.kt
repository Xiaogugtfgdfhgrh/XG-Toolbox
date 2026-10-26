package com.xg.toolbox.feature.security.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgIconButton
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgProgressBar
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgSwitch
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.component.xgAnimateItem
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.security.AutostartGroup
import com.xg.toolbox.feature.security.R
import com.xg.toolbox.feature.security.SecurityUiState
import com.xg.toolbox.feature.security.domain.BootReceiverInfo

@Composable
fun SecurityAutostartPage(
    state: SecurityUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onExpandAll: () -> Unit,
    onCollapseAll: () -> Unit,
    onReceiverToggle: (BootReceiverInfo, Boolean) -> Unit,
) {
    val autostart = state.autostart
    XgSubPageScaffold(
        title = stringResource(R.string.sec_auto_title),
        meta = if (autostart.totalReceivers > 0) {
            stringResource(
                R.string.sec_auto_meta,
                autostart.groups.size,
                autostart.totalReceivers,
            )
        } else {
            null
        },
        onBack = onBack,
        actions = {
            XgTextAction(
                text = stringResource(R.string.sec_action_refresh),
                onClick = onRefresh,
                enabled = !autostart.loading,
            )
        },
    ) {
        when {
            autostart.loading && !autostart.loaded -> item(key = "auto-loading") {
                AutostartLoadingPanel()
            }

            autostart.error != null && autostart.groups.isEmpty() -> item(key = "auto-error") {
                XgErrorState(
                    code = autostart.error.code,
                    message = autostart.error.message,
                    onRetry = onRefresh,
                )
            }

            !autostart.loaded && autostart.groups.isEmpty() -> item(key = "auto-initial") {
                AutostartLoadingPanel()
            }

            autostart.groups.isEmpty() -> item(key = "auto-empty") {
                XgPanel {
                    XgNote(text = stringResource(R.string.sec_auto_note))
                    Spacer(Modifier.height(XgSpacing.sm))
                    XgEmptyState(
                        title = stringResource(R.string.sec_auto_empty),
                        actionText = stringResource(R.string.sec_action_refresh),
                        onAction = onRefresh,
                    )
                }
            }

            else -> {
                if (autostart.error != null) {
                    item(key = "auto-inline-error") {
                        XgNote(text = autostart.error.message, tone = XgTone.Danger)
                    }
                }
                if (autostart.loading) {
                    item(key = "auto-progress") { XgProgressBar(progress = null) }
                }
                items(
                    items = autostart.groups,
                    key = { group -> group.packageName },
                    contentType = { "autostart-group" },
                ) { group ->
                    AutostartGroupCard(
                        group = group,
                        expanded = group.packageName in autostart.expanded,
                        pending = autostart.pending,
                        rootGranted = state.rootGranted,
                        onToggleGroup = onToggleGroup,
                        onReceiverToggle = onReceiverToggle,
                        modifier = Modifier.animateItem().xgAnimateItem(),
                    )
                }
                item(key = "auto-actions") {
                    val allExpanded = autostart.groups.isNotEmpty() &&
                        autostart.groups.all { it.packageName in autostart.expanded }
                    XgTextAction(
                        text = stringResource(
                            if (allExpanded) {
                                R.string.sec_auto_collapse_all
                            } else {
                                R.string.sec_auto_expand_all
                            },
                        ),
                        onClick = if (allExpanded) onCollapseAll else onExpandAll,
                    )
                }
                item(key = "auto-notes") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        XgNote(text = stringResource(R.string.sec_auto_note))
                        Spacer(Modifier.height(XgSpacing.xs))
                        XgNote(text = stringResource(R.string.sec_auto_vendor_note))
                        if (autostart.vendorManagers.isNotEmpty()) {
                            Spacer(Modifier.height(XgSpacing.xs))
                            XgNote(
                                text = stringResource(
                                    R.string.sec_auto_vendor_detected,
                                    autostart.vendorManagers.joinToString("、") { manager ->
                                        "${manager.label}（${manager.packageName}）"
                                    },
                                ),
                                tone = XgTone.Warn,
                            )
                        }
                        if (!state.rootGranted) {
                            Spacer(Modifier.height(XgSpacing.xs))
                            XgNote(
                                text = stringResource(R.string.sec_auto_need_root),
                                tone = XgTone.Warn,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutostartGroupCard(
    group: AutostartGroup,
    expanded: Boolean,
    pending: Set<String>,
    rootGranted: Boolean,
    onToggleGroup: (String) -> Unit,
    onReceiverToggle: (BootReceiverInfo, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    XgPanel(modifier = modifier) {
        GroupHeaderRow(
            group = group,
            expanded = expanded,
            onToggle = { onToggleGroup(group.packageName) },
        )
        if (expanded) {
            group.receivers.forEach { receiver ->
                XgHairline()
                ReceiverRow(
                    receiver = receiver,
                    pending = receiver.component in pending,
                    rootGranted = rootGranted,
                    onToggle = { enabled -> onReceiverToggle(receiver, enabled) },
                )
            }
        }
    }
}

@Composable
private fun GroupHeaderRow(
    group: AutostartGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalXgColors.current
    val groupDescription = stringResource(
        R.string.sec_auto_group_desc,
        group.label,
        group.receivers.size,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = XgSize.rowMin)
            .padding(vertical = 4.dp)
            .semantics { contentDescription = groupDescription },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.label,
                style = XgType.rowTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = group.packageName,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(XgSpacing.xs))
        XgBadge(
            text = stringResource(R.string.sec_auto_component_count, group.receivers.size),
            tone = XgTone.Muted,
        )
        XgIconButton(
            icon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.sec_auto_action_collapse else R.string.sec_auto_action_expand,
            ),
            onClick = onToggle,
        )
    }
}

@Composable
private fun ReceiverRow(
    receiver: BootReceiverInfo,
    pending: Boolean,
    rootGranted: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LocalXgColors.current
    val name = componentSimpleName(receiver.component)
    val switchDescription = stringResource(R.string.sec_auto_receiver_desc, receiver.component)
    val sourceLabel = stringResource(
        if (receiver.source.contains("pm_dump")) {
            R.string.sec_auto_source_dump
        } else {
            R.string.sec_auto_source_pm
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = XgSize.rowMin)
            .padding(start = XgSpacing.md, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = XgType.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = receiver.packageName + " · " + sourceLabel,
                style = XgType.monoSmall,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(XgSpacing.xs))
        XgBadge(
            text = stringResource(
                if (receiver.enabled) {
                    R.string.sec_auto_state_enabled
                } else {
                    R.string.sec_auto_state_disabled
                },
            ),
            tone = if (receiver.enabled) XgTone.Ok else XgTone.Muted,
        )
        Spacer(Modifier.width(XgSpacing.xs))
        XgSwitch(
            checked = receiver.enabled,
            onCheckedChange = onToggle,
            enabled = rootGranted && !pending,
            contentDescription = switchDescription,
        )
    }
}

private fun componentSimpleName(component: String): String {
    val slash = component.indexOf('/')
    return if (slash >= 0) component.substring(slash + 1) else component
}

@Composable
private fun AutostartLoadingPanel() {
    val colors = LocalXgColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = colors.accent,
                strokeWidth = 3.dp,
            )
            Text(
                text = stringResource(R.string.sec_auto_scanning),
                color = colors.muted,
                style = XgType.body,
            )
        }
    }
}
