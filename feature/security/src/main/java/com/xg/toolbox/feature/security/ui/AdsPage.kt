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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgPrimaryButton
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgTaskProgress
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.security.AdActionKind
import com.xg.toolbox.feature.security.AdActionOutcome
import com.xg.toolbox.feature.security.AdHostsUiState
import com.xg.toolbox.feature.security.AdSdkGroup
import com.xg.toolbox.feature.security.AdsUiState
import com.xg.toolbox.feature.security.R
import com.xg.toolbox.feature.security.SecurityUiState
import com.xg.toolbox.feature.security.domain.AdDnsFilterMode
import com.xg.toolbox.feature.security.domain.AdDnsFilterStore
import com.xg.toolbox.feature.security.domain.AdHit
import com.xg.toolbox.feature.security.domain.AdHostsMethod

@Composable
fun SecurityAdsPage(
    state: SecurityUiState,
    onBack: () -> Unit,
    onHostsRefresh: () -> Unit,
    onHostsApply: () -> Unit,
    onHostsDisable: () -> Unit,
    onAdScan: () -> Unit,
    onAdFreeze: (String, String) -> Unit,
    onAdUnfreeze: (String, String) -> Unit,
    onAdUninstall: (String, String) -> Unit,
) {
    val context = LocalContext.current
    val domainOptions = remember { loadDomainOptions(context) }
    var mode by remember { mutableStateOf(AdDnsFilterStore.mode(context)) }
    var selected by remember { mutableStateOf(AdDnsFilterStore.selected(context)) }
    var showPicker by remember { mutableStateOf(false) }

    XgSubPageScaffold(
        title = stringResource(R.string.sec_ads_title),
        meta = stringResource(R.string.sec_ads_meta_empty),
        onBack = onBack,
        actions = {
            XgTextAction(
                text = stringResource(R.string.sec_adh_refresh),
                onClick = onHostsRefresh,
                enabled = !state.adHosts.loading && !state.adHosts.applying,
            )
        },
    ) {
        item(key = "ad-hosts") {
            AdHostsCard(
                state = state.adHosts,
                mode = mode,
                selected = selected,
                onModeChange = {
                    mode = it
                    AdDnsFilterStore.save(context, mode, selected)
                    if (state.adHosts.status?.enabled == true) onHostsApply()
                },
                onPickDomains = { showPicker = true },
                onApply = onHostsApply,
                onDisable = onHostsDisable,
            )
        }
        item(key = "ads-scan") {
            AdScanPanel(
                ads = state.ads,
                rootGranted = state.rootGranted,
                onScan = onAdScan,
            )
        }
        items(
            items = state.ads.groups,
            key = { group -> "ads-sdk-${group.sdk}" },
            contentType = { "ads-sdk-group" },
        ) { group ->
            AdSdkPanel(
                group = group,
                ads = state.ads,
                rootGranted = state.rootGranted,
                onFreeze = onAdFreeze,
                onUnfreeze = onAdUnfreeze,
                onUninstall = onAdUninstall,
            )
        }
    }

    if (showPicker) {
        DomainFilterDialog(
            domains = domainOptions,
            selected = selected,
            onToggle = {
                selected = if (it in selected) selected - it else selected + it
                AdDnsFilterStore.save(context, mode, selected)
            },
            onDismiss = {
                showPicker = false
                if (state.adHosts.status?.enabled == true) onHostsApply()
            },
        )
    }
}

@Composable
private fun AdHostsCard(
    state: AdHostsUiState,
    mode: AdDnsFilterMode,
    selected: Set<String>,
    onModeChange: (AdDnsFilterMode) -> Unit,
    onPickDomains: () -> Unit,
    onApply: () -> Unit,
    onDisable: () -> Unit,
) {
    val colors = LocalXgColors.current
    val hosts = state.status
    val busy = state.loading || state.applying || state.disabling
    XgPanel {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.sec_adh_title),
                style = XgType.cardTitle,
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    state.loading -> stringResource(R.string.sec_adh_checking)
                    state.applying -> stringResource(R.string.sec_adh_applying)
                    state.disabling -> stringResource(R.string.sec_adh_disabling)
                    hosts?.enabled == true -> stringResource(R.string.sec_adh_enabled)
                    else -> stringResource(R.string.sec_adh_disabled)
                },
                style = XgType.note,
                color = if (hosts?.enabled == true) colors.accent else colors.muted,
            )
        }
        Spacer(Modifier.height(XgSpacing.sm))
        HostsInfoRow(
            label = stringResource(R.string.sec_adh_support),
            value = when {
                hosts == null -> stringResource(R.string.sec_adh_unknown)
                hosts.rootGranted -> stringResource(
                    R.string.sec_adh_support_ok,
                    hostsMethodLabel(hosts.method),
                )
                else -> stringResource(R.string.sec_adh_need_root)
            },
        )
        HostsInfoRow(
            label = stringResource(R.string.sec_adh_target),
            value = hosts?.targetPath ?: stringResource(R.string.sec_adh_unknown),
        )
        HostsInfoRow(
            label = stringResource(R.string.sec_adh_domains),
            value = stringResource(R.string.sec_adh_count, hosts?.domainCount ?: 0),
        )
        HostsInfoRow(
            label = stringResource(R.string.sec_adh_applied),
            value = stringResource(R.string.sec_adh_count, hosts?.appliedCount ?: 0),
        )
        hosts?.message?.let { message ->
            Spacer(Modifier.height(XgSpacing.xs))
            Text(text = message, style = XgType.note, color = colors.muted)
        }

        Spacer(Modifier.height(XgSpacing.md))
        Text(
            text = stringResource(R.string.sec_adh_scope),
            style = XgType.note,
            color = colors.faint,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm)) {
            ModeChip(
                text = stringResource(R.string.sec_adh_scope_all),
                selected = mode == AdDnsFilterMode.ALL,
                onClick = { onModeChange(AdDnsFilterMode.ALL) },
            )
            ModeChip(
                text = stringResource(R.string.sec_adh_scope_black),
                selected = mode == AdDnsFilterMode.BLACKLIST,
                onClick = { onModeChange(AdDnsFilterMode.BLACKLIST) },
            )
            ModeChip(
                text = stringResource(R.string.sec_adh_scope_white),
                selected = mode == AdDnsFilterMode.WHITELIST,
                onClick = { onModeChange(AdDnsFilterMode.WHITELIST) },
            )
        }
        if (mode != AdDnsFilterMode.ALL) {
            Spacer(Modifier.height(XgSpacing.xs))
            Text(
                text = stringResource(R.string.sec_adh_selected_count, selected.size),
                style = XgType.note,
                color = colors.muted,
            )
            Spacer(Modifier.height(XgSpacing.xs))
            XgTextAction(
                text = stringResource(R.string.sec_adh_pick),
                onClick = onPickDomains,
            )
            XgNote(text = stringResource(R.string.sec_adh_scope_note))
        }

        Spacer(Modifier.height(XgSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (hosts?.enabled == true) {
                XgTextAction(
                    text = stringResource(R.string.sec_adh_disable),
                    onClick = onDisable,
                    enabled = !busy,
                    tone = XgTone.Danger,
                )
                Spacer(Modifier.width(XgSpacing.sm))
                XgTextAction(
                    text = stringResource(R.string.sec_adh_reapply),
                    onClick = onApply,
                    enabled = !busy,
                )
            } else {
                XgPrimaryButton(
                    text = stringResource(R.string.sec_adh_enable),
                    onClick = onApply,
                    enabled = !busy,
                )
            }
        }
        state.error?.let { error ->
            Spacer(Modifier.height(XgSpacing.sm))
            XgNote(text = error.message, tone = XgTone.Danger)
        }
        Spacer(Modifier.height(XgSpacing.sm))
        XgNote(text = stringResource(R.string.sec_adh_note))
    }
}

@Composable
private fun ModeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(text) })
}

@Composable
private fun AdScanPanel(
    ads: AdsUiState,
    rootGranted: Boolean,
    onScan: () -> Unit,
) {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sec_ads_scan),
            meta = if (ads.scanned) {
                stringResource(R.string.sec_ads_meta, ads.sdkCount, ads.appCount)
            } else {
                null
            },
            trailing = {
                if (ads.scanning) {
                    XgBadge(
                        text = stringResource(R.string.sec_ads_scanning),
                        tone = XgTone.Accent,
                    )
                }
            },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        when {
            ads.scanning -> XgTaskProgress(
                title = stringResource(R.string.sec_ads_scanning),
                progress = if (ads.total > 0) ads.done.toFloat() / ads.total.toFloat() else null,
                meta = stringResource(R.string.sec_ads_progress, ads.done, ads.total),
            )

            !ads.scanned -> {
                XgNote(text = stringResource(R.string.sec_ads_not_scanned))
                Spacer(Modifier.height(XgSpacing.sm))
                XgPrimaryButton(
                    text = stringResource(R.string.sec_ads_not_scanned_action),
                    onClick = onScan,
                )
            }

            else -> {
                if (ads.groups.isEmpty()) {
                    XgNote(text = stringResource(R.string.sec_ads_empty))
                }
                XgTextAction(
                    text = stringResource(
                        if (ads.groups.isEmpty()) R.string.sec_ads_scan else R.string.sec_ads_rescan,
                    ),
                    onClick = onScan,
                )
            }
        }
        ads.error?.let { error ->
            Spacer(Modifier.height(XgSpacing.sm))
            XgNote(text = error.message, tone = XgTone.Danger)
        }
        if (!rootGranted) {
            Spacer(Modifier.height(XgSpacing.sm))
            XgNote(text = stringResource(R.string.sec_ads_need_root), tone = XgTone.Warn)
        }
    }
}

@Composable
private fun AdSdkPanel(
    group: AdSdkGroup,
    ads: AdsUiState,
    rootGranted: Boolean,
    onFreeze: (String, String) -> Unit,
    onUnfreeze: (String, String) -> Unit,
    onUninstall: (String, String) -> Unit,
) {
    XgPanel {
        XgPanelHeader(
            title = group.sdk,
            meta = stringResource(R.string.sec_ads_sdk_apps, group.hits.size),
        )
        group.hits.forEachIndexed { index, hit ->
            if (index > 0) XgHairline()
            AdHitRow(
                hit = hit,
                ads = ads,
                rootGranted = rootGranted,
                onFreeze = onFreeze,
                onUnfreeze = onUnfreeze,
                onUninstall = onUninstall,
            )
        }
    }
}

@Composable
private fun AdHitRow(
    hit: AdHit,
    ads: AdsUiState,
    rootGranted: Boolean,
    onFreeze: (String, String) -> Unit,
    onUnfreeze: (String, String) -> Unit,
    onUninstall: (String, String) -> Unit,
) {
    val actionBusy = ads.freezing != null || ads.unfreezing != null || ads.uninstalling != null
    val frozen = hit.packageName in ads.frozen
    val outcome = ads.outcomes[hit.packageName]
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hit.appLabel,
                    style = XgType.rowTitle,
                    color = LocalXgColors.current.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = hit.packageName,
                    style = XgType.monoSmall,
                    color = LocalXgColors.current.faint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                XgNote(text = stringResource(R.string.sec_ads_evidence, hit.evidence))
            }
            if (frozen) {
                Spacer(Modifier.width(XgSpacing.sm))
                XgBadge(
                    text = stringResource(R.string.sec_ads_frozen_badge),
                    tone = XgTone.Warn,
                )
            }
        }
        Spacer(Modifier.height(XgSpacing.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm)) {
            if (frozen) {
                XgTextAction(
                    text = if (ads.unfreezing == hit.packageName) {
                        stringResource(R.string.sec_ads_unfreezing)
                    } else {
                        stringResource(R.string.sec_ads_unfreeze)
                    },
                    onClick = { onUnfreeze(hit.packageName, hit.appLabel) },
                    enabled = rootGranted && !actionBusy,
                )
            } else {
                XgTextAction(
                    text = if (ads.freezing == hit.packageName) {
                        stringResource(R.string.sec_ads_freezing)
                    } else {
                        stringResource(R.string.sec_ads_freeze)
                    },
                    onClick = { onFreeze(hit.packageName, hit.appLabel) },
                    enabled = rootGranted && !actionBusy,
                )
            }
            XgTextAction(
                text = if (ads.uninstalling == hit.packageName) {
                    stringResource(R.string.sec_ads_uninstalling)
                } else {
                    stringResource(R.string.sec_ads_uninstall)
                },
                onClick = { onUninstall(hit.packageName, hit.appLabel) },
                enabled = rootGranted && !actionBusy,
                tone = XgTone.Danger,
            )
        }
        if (hit.packageName in ads.rescanning) {
            Spacer(Modifier.height(XgSpacing.xs))
            XgNote(text = stringResource(R.string.sec_ads_outcome_rescanning))
        } else {
            AdOutcomeNote(outcome = outcome)
        }
    }
}

@Composable
private fun AdOutcomeNote(outcome: AdActionOutcome?) {
    if (outcome == null) return
    val text = when {
        outcome.error != null -> stringResource(
            R.string.sec_ads_outcome_rescan_failed,
            outcome.error,
        )

        outcome.stillHits == null -> stringResource(R.string.sec_ads_outcome_rescan_failed, "")
        outcome.kind == AdActionKind.FREEZE && outcome.stillHits -> {
            stringResource(R.string.sec_ads_outcome_frozen_hits)
        }

        outcome.kind == AdActionKind.FREEZE -> stringResource(R.string.sec_ads_outcome_frozen_gone)
        outcome.kind == AdActionKind.UNFREEZE && outcome.stillHits -> {
            stringResource(R.string.sec_ads_outcome_unfrozen_hits)
        }

        outcome.kind == AdActionKind.UNFREEZE -> stringResource(R.string.sec_ads_outcome_unfrozen_gone)
        outcome.stillHits -> stringResource(R.string.sec_ads_outcome_uninstalled_hits)
        else -> stringResource(R.string.sec_ads_outcome_uninstalled_gone)
    }
    Spacer(Modifier.height(XgSpacing.xs))
    XgNote(text = text)
}

@Composable
private fun DomainFilterDialog(
    domains: List<String>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sec_adh_pick_title)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                items(domains, key = { it }) { domain ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = domain in selected,
                            onCheckedChange = { onToggle(domain) },
                        )
                        Text(text = domain, style = XgType.note)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sec_adh_pick_done)) }
        },
    )
}

@Composable
private fun HostsInfoRow(label: String, value: String) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = XgType.note,
            color = colors.faint,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(XgSpacing.sm))
        Text(text = value, style = XgType.note, color = colors.ink)
    }
}

private fun loadDomainOptions(context: android.content.Context): List<String> = try {
    context.assets.open("ad_domains.txt").bufferedReader().useLines { lines ->
        lines.map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2 && parts[0] in setOf("0.0.0.0", "127.0.0.1", "::1")) parts[1] else parts[0]
            }
            .filter { it.isNotBlank() && it != "localhost" }
            .distinct()
            .toList()
    }
} catch (_: Throwable) {
    emptyList()
}

@Composable
private fun hostsMethodLabel(method: AdHostsMethod?): String = stringResource(
    when (method) {
        AdHostsMethod.ROOT_BIND -> R.string.sec_adh_method_root
        AdHostsMethod.VPN -> R.string.sec_adh_method_vpn
        AdHostsMethod.IPTABLES -> R.string.sec_adh_method_builtin
        AdHostsMethod.MODULE -> R.string.sec_adh_method_module
        AdHostsMethod.BIND -> R.string.sec_adh_method_bind
        null -> R.string.sec_adh_unknown
    },
)
