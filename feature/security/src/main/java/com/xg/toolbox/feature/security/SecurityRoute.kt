package com.xg.toolbox.feature.security

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.rootError
import com.xg.toolbox.core.ui.component.LocalXgToast
import com.xg.toolbox.core.ui.component.XgConfirmDialog
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgSubPageScaffold
import com.xg.toolbox.core.ui.component.XgToastHost
import com.xg.toolbox.core.ui.component.XgToastState
import com.xg.toolbox.feature.security.domain.AppOpState
import com.xg.toolbox.feature.security.settings.SettingsRoute
import com.xg.toolbox.feature.security.ui.SecurityA11yPage
import com.xg.toolbox.feature.security.ui.SecurityAdsPage
import com.xg.toolbox.feature.security.ui.SecurityAutostartPage
import com.xg.toolbox.feature.security.ui.SecurityMatrixPage

/**
 * The old SECURITY tab now contains Settings only.
 *
 * The four former Security overview capabilities still live in this feature and are exposed
 * through [SecurityCapabilityScreen] for the System tab (see SystemScreen).
 */
@Composable
fun SecurityRoute() {
    SettingsRoute()
}

/** Stable ids used by SystemOverview and SystemRoute for the four migrated capabilities. */
object SecurityCapabilityIds {
    const val MATRIX = "security_matrix"
    const val A11Y = "security_a11y"
    const val AUTOSTART = "security_autostart"
    const val ADS = "security_ads"

    val all: List<String> = listOf(MATRIX, A11Y, AUTOSTART, ADS)

    fun isCapability(id: String?): Boolean = id != null && id in all

    fun pageFor(id: String): SecurityPage? = when (id) {
        MATRIX -> SecurityPage.MATRIX
        A11Y -> SecurityPage.A11Y
        AUTOSTART -> SecurityPage.AUTOSTART
        ADS -> SecurityPage.ADS
        else -> null
    }
}

/**
 * Hosts one existing Security capability page inside the System tab.
 * The Security feature keeps its own ViewModel, controllers, dialogs and loading behavior.
 */
@Composable
fun SecurityCapabilityScreen(
    sectionId: String,
    onBack: () -> Unit,
) {
    val page = SecurityCapabilityIds.pageFor(sectionId)
    if (page == null) {
        XgSubPageScaffold(title = "安全能力", onBack = onBack) {
            item(key = "unknown-security-capability") {
                XgEmptyState(title = "未知安全能力入口")
            }
        }
        return
    }

    val viewModel: SecurityViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val globalToast = LocalXgToast.current
    val localToast = remember { XgToastState() }
    val toast = globalToast ?: localToast

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> toast.show(message.resolve(context)) }
    }
    LaunchedEffect(page) {
        viewModel.onPageOpened(page)
    }
    BackHandler(onBack = onBack)

    val rootError = state.root.state.rootError()?.let { error ->
        UiError(error.code, error.message, error.detail)
    }
    val rootPending = state.root.state is RootState.Unknown

    val onMatrixSearch: (String) -> Unit = remember(viewModel) { viewModel::setMatrixQuery }
    val onMatrixFilter: (MatrixFilter) -> Unit = remember(viewModel) { viewModel::setMatrixFilter }
    val onMatrixCellClick: (String, String) -> Unit = remember(viewModel) { viewModel::cycleCell }
    val onMatrixLoadMore: () -> Unit = remember(viewModel) { viewModel::loadMoreMatrix }
    val onMatrixRetry: () -> Unit = remember(viewModel) { viewModel::retryMatrix }
    val onAppsRetry: () -> Unit = remember(viewModel) { viewModel::retryApps }
    val onRootRetry: () -> Unit = remember(viewModel) { viewModel::refreshRoot }

    Box(modifier = Modifier.fillMaxSize()) {
        when (page) {
            SecurityPage.MATRIX -> SecurityMatrixPage(
                matrix = state.matrix,
                appsLoading = state.appsLoading,
                appsError = state.appsError,
                rootError = rootError,
                rootPending = rootPending,
                rootGranted = state.rootGranted,
                onBack = onBack,
                onSearch = onMatrixSearch,
                onFilter = onMatrixFilter,
                onCellClick = onMatrixCellClick,
                onLoadMore = onMatrixLoadMore,
                onRetryMatrix = onMatrixRetry,
                onRetryApps = onAppsRetry,
                onRetryRoot = onRootRetry,
            )

            SecurityPage.A11Y -> SecurityA11yPage(
                state = state,
                onBack = onBack,
                onLockChange = viewModel::requestA11yLock,
                onRefresh = viewModel::refreshA11y,
                onToggleService = viewModel::toggleA11yService,
                onAddAll = viewModel::addAllA11yServices,
            )

            SecurityPage.AUTOSTART -> SecurityAutostartPage(
                state = state,
                onBack = onBack,
                onRefresh = viewModel::refreshAutostart,
                onToggleGroup = viewModel::toggleAutostartGroup,
                onExpandAll = viewModel::expandAllAutostart,
                onCollapseAll = viewModel::collapseAllAutostart,
                onReceiverToggle = viewModel::setReceiverEnabled,
            )

            SecurityPage.ADS -> SecurityAdsPage(
                state = state,
                onBack = onBack,
                onHostsRefresh = viewModel::refreshAdHosts,
                onHostsApply = viewModel::applyAdHosts,
                onHostsDisable = viewModel::requestDisableAdHosts,
                onAdScan = viewModel::scanAds,
                onAdFreeze = viewModel::requestAdFreeze,
                onAdUnfreeze = viewModel::requestAdUnfreeze,
                onAdUninstall = viewModel::requestAdUninstall,
            )

            SecurityPage.OVERVIEW -> Unit
        }
        if (globalToast == null) {
            XgToastHost(state = localToast)
        }
    }

    when (val dialog = state.dialog) {
        SecurityDialog.ConfirmA11yUnlock -> XgConfirmDialog(
            title = stringResource(R.string.sec_a11y_unlock_confirm_title),
            body = stringResource(R.string.sec_a11y_unlock_confirm_body),
            confirmText = stringResource(R.string.sec_a11y_confirm_ok),
            dismissText = stringResource(R.string.sec_a11y_confirm_cancel),
            danger = true,
            onConfirm = viewModel::confirmA11yUnlock,
            onDismiss = viewModel::dismissDialog,
        )

        is SecurityDialog.ConfirmAdFreeze -> XgConfirmDialog(
            title = stringResource(R.string.sec_ads_freeze_confirm_title, dialog.label),
            body = stringResource(R.string.sec_ads_freeze_confirm_body),
            confirmText = stringResource(R.string.sec_ads_freeze_confirm_ok),
            dismissText = stringResource(R.string.sec_ads_freeze_confirm_cancel),
            danger = false,
            onConfirm = viewModel::confirmAdFreeze,
            onDismiss = viewModel::dismissDialog,
        )

        is SecurityDialog.ConfirmAdUnfreeze -> XgConfirmDialog(
            title = stringResource(R.string.sec_ads_unfreeze_confirm_title, dialog.label),
            body = stringResource(R.string.sec_ads_unfreeze_confirm_body),
            confirmText = stringResource(R.string.sec_ads_unfreeze_confirm_ok),
            dismissText = stringResource(R.string.sec_ads_confirm_cancel),
            danger = false,
            onConfirm = viewModel::confirmAdUnfreeze,
            onDismiss = viewModel::dismissDialog,
        )

        is SecurityDialog.ConfirmAdUninstall -> XgConfirmDialog(
            title = stringResource(R.string.sec_ads_uninstall_confirm_title, dialog.label),
            body = stringResource(R.string.sec_ads_uninstall_confirm_body),
            confirmText = stringResource(R.string.sec_ads_uninstall_confirm_ok),
            dismissText = stringResource(R.string.sec_ads_confirm_cancel),
            danger = true,
            onConfirm = viewModel::confirmAdUninstall,
            onDismiss = viewModel::dismissDialog,
        )

        SecurityDialog.ConfirmAdHostsDisable -> XgConfirmDialog(
            title = stringResource(R.string.sec_adh_confirm_title),
            body = stringResource(R.string.sec_adh_confirm_body),
            confirmText = stringResource(R.string.sec_adh_confirm_ok),
            dismissText = stringResource(R.string.sec_adh_confirm_cancel),
            danger = true,
            onConfirm = viewModel::confirmDisableAdHosts,
            onDismiss = viewModel::dismissDialog,
        )

        null -> Unit
    }
}

private fun SecurityMessage.resolve(context: Context): String = when (this) {
    is SecurityMessage.Raw -> text
    is SecurityMessage.Res -> if (args.isEmpty()) {
        context.getString(resId)
    } else {
        context.getString(resId, *args.toTypedArray())
    }

    is SecurityMessage.AppOpUpdated -> context.getString(
        R.string.sec_toast_appop_set,
        appLabel,
        opLabel,
        context.getString(stateResId(state)),
    )
}

private fun stateResId(state: AppOpState): Int = when (state) {
    AppOpState.ALLOW -> R.string.sec_state_allow
    AppOpState.ASK -> R.string.sec_state_ask
    AppOpState.DENY -> R.string.sec_state_deny
}
