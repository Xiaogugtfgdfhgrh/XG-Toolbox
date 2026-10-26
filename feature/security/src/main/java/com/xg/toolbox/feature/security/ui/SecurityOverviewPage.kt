package com.xg.toolbox.feature.security.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xg.toolbox.core.ui.component.XgEntryCard
import com.xg.toolbox.core.ui.component.XgPageHeader
import com.xg.toolbox.core.ui.component.XgPageScaffold
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.component.xgAnimateItem
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.feature.security.R
import com.xg.toolbox.feature.security.SecurityPage
import com.xg.toolbox.feature.security.SecurityUiState

@Composable
fun SecurityOverviewPage(
    state: SecurityUiState,
    onOpenPage: (SecurityPage) -> Unit,
    onRefreshRoot: () -> Unit,
) {
    XgPageScaffold(
        topBar = {
            XgPageHeader(
                title = stringResource(R.string.sec_title),
                meta = stringResource(R.string.sec_meta),
            )
        },
    ) {
        item(key = "security-root") {
            SecurityRootCard(
                root = state.root,
                onRefresh = onRefreshRoot,
                modifier = Modifier.xgAnimateItem(),
            )
        }
        item(key = "security-entries") {
            Column(verticalArrangement = Arrangement.spacedBy(XgSpacing.md)) {
                SecurityEntryCards(state = state, onOpenPage = onOpenPage)
            }
        }
    }
}

@Composable
private fun SecurityEntryCards(
    state: SecurityUiState,
    onOpenPage: (SecurityPage) -> Unit,
) {
    val rootGranted = state.rootGranted
    val matrix = state.matrix
    val a11y = state.a11y
    val autostart = state.autostart
    val ads = state.ads

    SecurityEntryCard(
        page = SecurityPage.MATRIX,
        ordinal = stringResource(R.string.sec_ordinal_matrix),
        title = stringResource(R.string.sec_page_matrix),
        subtitle = stringResource(R.string.sec_overview_matrix_subtitle),
        meta = if (matrix.loaded) {
            stringResource(
                R.string.sec_overview_matrix_meta_loaded,
                matrix.rows.size,
                matrix.filteredCount,
            )
        } else {
            stringResource(R.string.sec_overview_matrix_meta_idle)
        },
        badge = if (rootGranted) {
            stringResource(R.string.sec_badge_available)
        } else {
            stringResource(R.string.sec_badge_need_root)
        },
        badgeTone = if (rootGranted) XgTone.Ok else XgTone.Warn,
        onOpenPage = onOpenPage,
    )

    SecurityEntryCard(
        page = SecurityPage.A11Y,
        ordinal = stringResource(R.string.sec_ordinal_a11y),
        title = stringResource(R.string.sec_a11y_title),
        subtitle = stringResource(R.string.sec_overview_a11y_subtitle),
        meta = when {
            a11y.loaded && a11y.locked -> {
                stringResource(R.string.sec_a11y_meta_locked, a11y.services.size)
            }

            a11y.loaded -> stringResource(R.string.sec_a11y_meta_off)
            else -> stringResource(R.string.sec_overview_a11y_meta_idle)
        },
        badge = when {
            a11y.locked -> stringResource(R.string.sec_badge_locked)
            rootGranted || a11y.hasWritePermission -> stringResource(R.string.sec_badge_available)
            else -> stringResource(R.string.sec_badge_need_root)
        },
        badgeTone = when {
            a11y.locked -> XgTone.Accent
            rootGranted || a11y.hasWritePermission -> XgTone.Ok
            else -> XgTone.Warn
        },
        onOpenPage = onOpenPage,
    )

    SecurityEntryCard(
        page = SecurityPage.AUTOSTART,
        ordinal = stringResource(R.string.sec_ordinal_autostart),
        title = stringResource(R.string.sec_auto_title),
        subtitle = stringResource(R.string.sec_overview_autostart_subtitle),
        meta = if (autostart.loaded) {
            stringResource(
                R.string.sec_auto_meta,
                autostart.groups.size,
                autostart.totalReceivers,
            )
        } else {
            stringResource(R.string.sec_overview_autostart_meta_idle)
        },
        badge = if (rootGranted) {
            stringResource(R.string.sec_badge_available)
        } else {
            stringResource(R.string.sec_badge_readonly)
        },
        badgeTone = if (rootGranted) XgTone.Ok else XgTone.Muted,
        onOpenPage = onOpenPage,
    )

    SecurityEntryCard(
        page = SecurityPage.ADS,
        ordinal = stringResource(R.string.sec_ordinal_ads),
        title = stringResource(R.string.sec_ads_title),
        subtitle = stringResource(R.string.sec_overview_ads_subtitle),
        meta = stringResource(R.string.sec_ads_meta_empty),
        badge = if (rootGranted) {
            stringResource(R.string.sec_badge_available)
        } else {
            stringResource(R.string.sec_badge_readonly)
        },
        badgeTone = if (rootGranted) XgTone.Ok else XgTone.Muted,
        onOpenPage = onOpenPage,
    )
}

@Composable
private fun SecurityEntryCard(
    page: SecurityPage,
    ordinal: String,
    title: String,
    subtitle: String,
    meta: String?,
    badge: String?,
    badgeTone: XgTone,
    onOpenPage: (SecurityPage) -> Unit,
) {
    XgEntryCard(
        ordinal = ordinal,
        title = title,
        subtitle = subtitle,
        meta = meta,
        badge = badge,
        badgeTone = badgeTone,
        onClick = { onOpenPage(page) },
    )
}
