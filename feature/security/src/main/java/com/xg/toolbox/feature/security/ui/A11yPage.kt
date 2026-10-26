package com.xg.toolbox.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgLabel
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgProgressBar
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgSwitch
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.component.XgToggleRow
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.security.A11yServiceUi
import com.xg.toolbox.feature.security.A11yUiState
import com.xg.toolbox.feature.security.R
import com.xg.toolbox.feature.security.SecurityUiState

@Composable
fun SecurityA11yPage(
    state: SecurityUiState,
    onBack: () -> Unit,
    onLockChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onToggleService: (String, Boolean) -> Unit,
    onAddAll: () -> Unit,
) {
    val a11y = state.a11y
    XgSubPageScaffold(
        title = stringResource(R.string.sec_a11y_title),
        meta = if (a11y.locked) {
            stringResource(R.string.sec_a11y_meta_locked, a11y.services.size)
        } else {
            stringResource(R.string.sec_a11y_meta_off)
        },
        onBack = onBack,
        actions = {
            XgTextAction(
                text = stringResource(R.string.sec_action_refresh),
                onClick = onRefresh,
                enabled = !a11y.applying,
            )
        },
    ) {
        item(key = "a11y-content") {
            XgContentTransition(
                stateKey = Triple(a11y.loaded, a11y.locked, a11y.services.size),
                loading = a11y.loading && !a11y.loaded,
                error = if (!a11y.loaded) a11y.error?.message else null,
                onRetry = onRefresh,
                skeletonLines = 4,
            ) {
                A11yBody(
                    a11y = a11y,
                    rootGranted = state.rootGranted,
                    onLockChange = onLockChange,
                    onRefresh = onRefresh,
                    onToggleService = onToggleService,
                    onAddAll = onAddAll,
                )
            }
        }
        if (a11y.loading && a11y.loaded) {
            item(key = "a11y-progress") { XgProgressBar(progress = null) }
        }
        if (a11y.error != null && a11y.loaded) {
            item(key = "a11y-inline-error") {
                XgNote(text = a11y.error.message, tone = XgTone.Danger)
            }
        }
    }
}

@Composable
private fun A11yBody(
    a11y: A11yUiState,
    rootGranted: Boolean,
    onLockChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onToggleService: (String, Boolean) -> Unit,
    onAddAll: () -> Unit,
) {
    val colors = LocalXgColors.current
    val canEdit = !a11y.applying && (rootGranted || a11y.hasWritePermission)
    XgPanel {
        XgToggleRow(
            name = stringResource(R.string.sec_a11y_lock_name),
            note = if (a11y.locked) {
                stringResource(R.string.sec_a11y_lock_note_on, a11y.services.size)
            } else {
                stringResource(R.string.sec_a11y_lock_note_off)
            },
            checked = a11y.locked,
            onCheckedChange = onLockChange,
            enabled = canEdit,
        )
        if (!a11y.hasWritePermission) {
            Spacer(Modifier.height(XgSpacing.xs))
            XgNote(
                text = stringResource(
                    if (rootGranted) {
                        R.string.sec_a11y_permission_missing
                    } else {
                        R.string.sec_a11y_need_root
                    },
                ),
                tone = XgTone.Warn,
            )
        }
        Spacer(Modifier.height(XgSpacing.sm))
        XgHairline()
        Spacer(Modifier.height(XgSpacing.sm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            XgLabel(stringResource(R.string.sec_a11y_services_title))
            Spacer(Modifier.weight(1f))
            Text(
                text = a11y.services.size.toString(),
                style = XgType.pageMeta,
                color = colors.faint,
                maxLines = 1,
            )
        }
        Spacer(Modifier.height(XgSpacing.xs))
        if (a11y.services.isEmpty()) {
            XgEmptyState(title = stringResource(R.string.sec_a11y_empty))
        } else {
            a11y.services.forEach { service ->
                A11yServiceRow(
                    service = service,
                    whitelisted = service.service in a11y.draftWhitelist,
                    lockedBadge = a11y.locked && service.service in a11y.whitelist,
                    enabled = canEdit,
                    onToggle = { include -> onToggleService(service.service, include) },
                )
            }
        }
        Spacer(Modifier.height(XgSpacing.sm))
        Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm)) {
            XgTextAction(
                text = stringResource(R.string.sec_a11y_add_all),
                onClick = onAddAll,
                enabled = canEdit && a11y.services.isNotEmpty(),
            )
            XgTextAction(
                text = stringResource(R.string.sec_a11y_refresh),
                onClick = onRefresh,
                enabled = !a11y.applying,
            )
        }
        Spacer(Modifier.height(XgSpacing.sm))
        XgHairline()
        Spacer(Modifier.height(XgSpacing.sm))
        if (!a11y.locked) {
            XgNote(text = stringResource(R.string.sec_a11y_draft_note))
            Spacer(Modifier.height(XgSpacing.xs))
        } else if (!a11y.monitorStartRequested) {
            XgNote(text = stringResource(R.string.sec_a11y_monitor_start_failed), tone = XgTone.Warn)
            Spacer(Modifier.height(XgSpacing.xs))
        }
        XgNote(text = stringResource(R.string.sec_a11y_history_note))
        Spacer(Modifier.height(XgSpacing.xs))
        XgNote(text = stringResource(R.string.sec_a11y_monitor_note))
    }
}

@Composable
private fun A11yServiceRow(
    service: A11yServiceUi,
    whitelisted: Boolean,
    lockedBadge: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = LocalXgColors.current
    val toggleDescription = stringResource(
        R.string.sec_a11y_service_toggle_desc,
        service.service,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = XgSize.rowMin)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = service.packageName,
                style = XgType.monoKey,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (service.className.isNotBlank()) {
                Text(
                    text = service.className,
                    style = XgType.monoSmall,
                    color = colors.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (lockedBadge) {
            Spacer(Modifier.width(XgSpacing.xs))
            XgBadge(text = stringResource(R.string.sec_a11y_whitelist_badge), tone = XgTone.Accent)
        }
        Spacer(Modifier.width(XgSpacing.sm))
        XgSwitch(
            checked = whitelisted,
            onCheckedChange = onToggle,
            enabled = enabled,
            contentDescription = toggleDescription,
        )
    }
}
