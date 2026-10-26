package com.xg.toolbox.feature.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.common.XgTab
import com.xg.toolbox.core.data.AuditEntity
import com.xg.toolbox.core.data.AuditResult
import com.xg.toolbox.core.device.DeviceField
import com.xg.toolbox.core.device.FieldRestriction
import com.xg.toolbox.core.shell.RootInfo
import com.xg.toolbox.core.shell.RootScheme
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.ui.component.LocalXgToast
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgConfirmDialog
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgKeyValueRow
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPageScaffold
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgRiskRow
import com.xg.toolbox.core.ui.component.XgRunRow
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import kotlinx.coroutines.flow.StateFlow

@Composable
fun HomeRoute(
    onOpenTab: (XgTab) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val toast = LocalXgToast.current
    val rebootSentText = stringResource(R.string.home_reboot_sent)
    LaunchedEffect(viewModel, toast) {
        viewModel.events.collect { event ->
            when (event) {
                HomeEvent.RebootSent -> toast?.show(rebootSentText)
                is HomeEvent.Error -> toast?.show(event.message)
            }
        }
    }
    HomeScreen(
        state = state,
        metrics = viewModel.metricsUi,
        onOpenTab = onOpenTab,
        onConfirmReboot = viewModel::confirmReboot,
        onDismissReboot = viewModel::dismissReboot,
        onToggleRecent = viewModel::toggleRecentExpanded,
        onRetry = viewModel::retry,
    )
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    metrics: StateFlow<HomeMetricsUi>,
    onOpenTab: (XgTab) -> Unit,
    onConfirmReboot: () -> Unit,
    onDismissReboot: () -> Unit,
    onToggleRecent: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        resumed = lifecycleOwner.lifecycle.currentState == Lifecycle.State.RESUMED
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val interactionActive = listState.isScrollInProgress || !resumed
    val displayedMetrics = remember(metrics) { mutableStateOf(metrics.value) }
    LaunchedEffect(metrics, interactionActive) {
        if (interactionActive) return@LaunchedEffect
        metrics.collect { latest -> displayedMetrics.value = latest }
    }

    XgPageScaffold(
        listState = listState,
        topBar = null,
    ) {
        item(key = "home-brand") {
            BrandSection()
        }
        item(key = "home-summary") {
            SummarySection(state = state, onRetry = onRetry)
        }
        item(key = "home-metrics") {
            MetricsSection(metrics = displayedMetrics)
        }
        item(key = "home-risks") {
            RiskSection(state = state, onOpenTab = onOpenTab, onRetry = onRetry)
        }
        item(key = "home-status") {
            StatusSection(state = state)
        }
        item(key = "home-recent") {
            RecentSection(state = state, onToggleRecent = onToggleRecent, onRetry = onRetry)
        }
    }

    state.pendingReboot?.let { target ->
        XgConfirmDialog(
            title = stringResource(R.string.home_reboot_confirm_title, target.name),
            body = target.note,
            confirmText = stringResource(R.string.home_reboot_confirm),
            dismissText = stringResource(R.string.home_reboot_cancel),
            danger = target.dangerous,
            onConfirm = onConfirmReboot,
            onDismiss = onDismissReboot,
        )
    }
}

@Composable
private fun BrandSection() {
    val colors = LocalXgColors.current
    XgPanel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.home_brand_name),
                        style = XgType.cardTitle,
                        color = colors.ink,
                    )
                    Spacer(Modifier.width(XgSpacing.sm))
                    XgBadge(
                        text = stringResource(R.string.home_brand_version),
                        tone = XgTone.Accent,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_brand_author),
                    style = XgType.note,
                    color = colors.muted,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.home_brand_channel_label),
                    style = XgType.label,
                    color = colors.muted,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.home_brand_channel),
                    style = XgType.monoValue,
                    color = colors.accent,
                )
            }
        }
    }
}

@Composable
private fun SummarySection(state: HomeUiState, onRetry: () -> Unit) {
    XgContentTransition(
        stateKey = state.dataVersion,
        loading = state.loading && state.summary == null,
        error = state.error,
        onRetry = onRetry,
        skeletonLines = 2,
        initiallyLoaded = state.summary != null,
    ) {
        if (state.summary == null) {
            XgPanel {
                XgEmptyState(title = stringResource(R.string.home_empty_device))
            }
        } else {
            HomeSummaryCard(summary = state.summary)
        }
    }
}

@Composable
private fun MetricsSection(metrics: State<HomeMetricsUi>) {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.home_metrics_title),
            meta = stringResource(R.string.home_metrics_meta),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        HomeMetricsGrid(metrics = metrics)
    }
}

@Composable
private fun RiskSection(
    state: HomeUiState,
    onOpenTab: (XgTab) -> Unit,
    onRetry: () -> Unit,
) {
    Column {
        XgSectionHeader(
            title = stringResource(R.string.home_risk_title),
            meta = if (state.riskLoaded && state.risks.isNotEmpty()) {
                stringResource(R.string.home_risk_count, state.risks.size)
            } else {
                null
            },
        )
        Spacer(Modifier.height(XgSpacing.xs))
        XgPanel {
            XgContentTransition(
                stateKey = state.risks,
                loading = !state.riskLoaded,
                error = state.riskError,
                onRetry = onRetry,
                skeletonLines = 3,
                initiallyLoaded = state.riskLoaded,
            ) {
                if (state.risks.isEmpty()) {
                    XgNote(text = stringResource(R.string.home_risk_empty))
                } else {
                    state.risks.forEachIndexed { index, risk ->
                        if (index > 0) XgHairline()
                        XgRiskRow(
                            level = risk.severity.toTone(),
                            title = risk.title,
                            note = risk.note,
                            actionText = stringResource(R.string.home_risk_action),
                            onAction = { onOpenTab(risk.actionTab ?: XgTab.DEVICE) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSection(state: HomeUiState) {
    val rows = buildStatusRows(state)
    Column {
        XgSectionHeader(title = stringResource(R.string.home_status_title))
        Spacer(Modifier.height(XgSpacing.xs))
        XgPanel {
            XgContentTransition(
                stateKey = state.dataVersion,
                loading = state.loading && state.fields.isEmpty(),
                error = null,
                onRetry = null,
                skeletonLines = 4,
                initiallyLoaded = state.fields.isNotEmpty(),
            ) {
                if (state.fields.isEmpty()) {
                    XgEmptyState(title = stringResource(R.string.home_empty_device))
                } else {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) XgHairline()
                        XgKeyValueRow(
                            label = row.label,
                            value = row.value,
                            propertyKey = row.propertyKey,
                            badge = row.restriction?.badgeText(),
                            badgeTone = row.restriction?.badgeTone() ?: XgTone.Muted,
                            copyable = false,
                            index = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSection(
    state: HomeUiState,
    onToggleRecent: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    Column {
        XgSectionHeader(title = stringResource(R.string.home_recent_title))
        Spacer(Modifier.height(XgSpacing.xs))
        XgPanel {
            XgContentTransition(
                stateKey = state.recent,
                loading = !state.recentLoaded,
                error = state.recentError,
                onRetry = onRetry,
                skeletonLines = 3,
                initiallyLoaded = state.recentLoaded,
            ) {
                if (state.recent.isEmpty()) {
                    XgEmptyState(title = stringResource(R.string.home_recent_empty))
                } else {
                    state.recent.forEachIndexed { index, audit ->
                        if (index > 0) XgHairline()
                        XgRunRow(
                            title = audit.action.ifBlank { audit.target },
                            meta = recentMeta(audit),
                            result = auditResultText(audit.result),
                            resultTone = auditResultTone(audit.result),
                            onClick = { onToggleRecent(audit.id) },
                        )
                        if (state.expandedAuditId == audit.id) {
                            AuditDetail(audit = audit)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditDetail(audit: AuditEntity) {
    val noCommands = stringResource(R.string.home_recent_no_commands)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = XgSpacing.sm),
    ) {
        XgNote(text = stringResource(R.string.home_recent_commands))
        XgMono(
            text = audit.commands.ifBlank { noCommands },
            style = XgType.monoSmall,
            color = LocalXgColors.current.muted,
        )
        if (!audit.message.isNullOrBlank()) {
            Spacer(Modifier.height(XgSpacing.xs))
            XgNote(text = stringResource(R.string.home_recent_message))
            XgNote(text = audit.message.orEmpty())
        }
    }
}

private fun recentMeta(audit: AuditEntity): String {
    val time = Formatters.relative(audit.ts)
    return if (audit.target.isBlank()) time else "$time · ${audit.target}"
}

@Composable
private fun auditResultText(result: String): String = when (result) {
    AuditResult.OK -> stringResource(R.string.home_recent_result_ok)
    AuditResult.FAILED -> stringResource(R.string.home_recent_result_failed)
    AuditResult.ROLLED_BACK -> stringResource(R.string.home_recent_result_rolled_back)
    else -> result
}

private fun auditResultTone(result: String): XgTone = when (result) {
    AuditResult.OK -> XgTone.Ok
    AuditResult.FAILED -> XgTone.Danger
    AuditResult.ROLLED_BACK -> XgTone.Warn
    else -> XgTone.Muted
}

private fun String.toTone(): XgTone = when (lowercase()) {
    "danger" -> XgTone.Danger
    "warn" -> XgTone.Warn
    "ok" -> XgTone.Ok
    else -> XgTone.Muted
}

private data class StatusRow(
    val label: String,
    val value: String?,
    val propertyKey: String?,
    val restriction: FieldRestriction?,
)

@Composable
private fun buildStatusRows(state: HomeUiState): List<StatusRow> {
    val fields = state.fields
    val versionField = fields.findStatusField("ro.build.version.incremental", "系统版本")
    val patchField = fields.findStatusField("ro.build.version.security_patch", "安全补丁")
    val selinuxField = fields.findStatusField("getenforce", "SELinux")
    return listOf(
        StatusRow(
            label = stringResource(R.string.home_status_version),
            value = versionField?.value,
            propertyKey = versionField?.propertyKey,
            restriction = versionField?.restriction,
        ),
        StatusRow(
            label = stringResource(R.string.home_status_patch),
            value = patchField?.value,
            propertyKey = patchField?.propertyKey,
            restriction = patchField?.restriction,
        ),
        StatusRow(
            label = stringResource(R.string.home_status_selinux),
            value = selinuxField?.value,
            propertyKey = selinuxField?.propertyKey,
            restriction = selinuxField?.restriction,
        ),
        StatusRow(
            label = stringResource(R.string.home_status_root),
            value = rootStateText(state.rootState, state.rootInfo),
            propertyKey = null,
            restriction = null,
        ),
    )
}

@Composable
private fun rootStateText(rootState: RootState, rootInfo: RootInfo?): String = when (rootState) {
    is RootState.Granted -> {
        val scheme = when (rootInfo?.scheme) {
            RootScheme.KERNELSU -> "KernelSU"
            RootScheme.MAGISK -> "Magisk"
            RootScheme.APATCH -> "APatch"
            RootScheme.OTHER -> "su"
            RootScheme.NONE, null -> null
        }
        val version = rootInfo?.version?.takeIf { it.isNotBlank() }
        val label = listOfNotNull(scheme, version).joinToString(" ")
        if (label.isBlank()) {
            stringResource(R.string.home_status_root_plain)
        } else {
            stringResource(R.string.home_status_root_granted, label)
        }
    }

    is RootState.Unknown -> stringResource(R.string.home_status_root_unknown)
    is RootState.Denied -> stringResource(R.string.home_status_root_denied)
    is RootState.Unsupported -> stringResource(R.string.home_status_root_unsupported)
}

private fun List<DeviceField>.findStatusField(propertyKey: String, name: String): DeviceField? =
    firstOrNull { it.propertyKey == propertyKey }
        ?: firstOrNull { it.name == name }

@Composable
private fun FieldRestriction.badgeText(): String? = when (this) {
    FieldRestriction.NONE -> null
    FieldRestriction.UNAVAILABLE -> stringResource(R.string.home_status_unavailable)
    FieldRestriction.MASKED -> stringResource(R.string.home_status_masked)
    FieldRestriction.NEEDS_ROOT -> stringResource(R.string.home_status_needs_root)
    FieldRestriction.LIMITED -> stringResource(R.string.home_status_limited)
}

private fun FieldRestriction.badgeTone(): XgTone = when (this) {
    FieldRestriction.NONE -> XgTone.Muted
    FieldRestriction.UNAVAILABLE, FieldRestriction.MASKED -> XgTone.Muted
    FieldRestriction.NEEDS_ROOT, FieldRestriction.LIMITED -> XgTone.Warn
}
