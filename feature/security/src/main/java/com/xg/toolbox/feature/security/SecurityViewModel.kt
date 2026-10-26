package com.xg.toolbox.feature.security

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppEntry
import com.xg.toolbox.core.data.AppRepository
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.feature.security.domain.AccessibilityGuard
import com.xg.toolbox.feature.security.domain.AdHostsBlocker
import com.xg.toolbox.feature.security.domain.AdScanner
import com.xg.toolbox.feature.security.domain.AppOpsController
import com.xg.toolbox.feature.security.domain.AutostartScanner
import com.xg.toolbox.feature.security.domain.BootReceiverInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SecurityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootStatus: RootStatusRepository,
    private val appRepository: AppRepository,
    appOpsController: AppOpsController,
    accessibilityGuard: AccessibilityGuard,
    autostartScanner: AutostartScanner,
    adScanner: AdScanner,
    adHostsBlocker: AdHostsBlocker,
) : ViewModel() {

    private val store = SecurityStateStore()

    private val matrix = AppOpsMatrixController(viewModelScope, store, appOpsController)
    private val a11y = A11yLockController(viewModelScope, store, accessibilityGuard, context)
    private val autostart = AutostartController(viewModelScope, store, autostartScanner)
    private val ads = AdScanController(viewModelScope, store, adScanner, appRepository)
    private val adHosts = AdHostsController(viewModelScope, store, adHostsBlocker)

    
    val uiState: StateFlow<SecurityUiState> = store.uiState

    
    val messages: SharedFlow<SecurityMessage> = store.messages

    
    private var currentPage: SecurityPage = SecurityPage.OVERVIEW

    init {
        observeRootStatus()
        if (rootStatus.state.value is RootState.Unknown) {
            refreshRoot()
        }
    }

    
    fun onPageOpened(page: SecurityPage) {
        val changed = currentPage != page
        currentPage = page
        if (changed) loadForPage(page)
    }

    private fun loadForPage(page: SecurityPage) {
        when (page) {
            SecurityPage.OVERVIEW -> Unit
            SecurityPage.MATRIX -> viewModelScope.launch { ensureMatrixData() }
            SecurityPage.A11Y -> if (!store.current.a11y.loaded) viewModelScope.launch { a11y.load() }
            SecurityPage.AUTOSTART -> autostart.ensureLoaded()
            SecurityPage.ADS -> adHosts.ensureLoaded()
        }
    }

    private suspend fun ensureMatrixData() {
        if (store.current.apps.isEmpty()) {
            loadAppList()
        } else {
            matrix.syncCounts()
            matrix.ensureLoaded()
        }
    }

    private fun observeRootStatus() {
        viewModelScope.launch {
            combine(
                rootStatus.state,
                rootStatus.rootInfo,
                rootStatus.evidence,
                rootStatus.refreshing,
            ) { state, info, evidence, refreshing ->
                RootCardState(
                    state = state,
                    info = info,
                    evidence = evidence,
                    refreshing = refreshing,
                )
            }.collect { rootCard ->
                val wasGranted = store.current.rootGranted
                store.update { it.copy(root = rootCard) }
                val isGranted = rootCard.state is RootState.Granted
                if (isGranted && !wasGranted) {
                    loadForPage(currentPage)
                }
                if (!isGranted && wasGranted) {
                    store.update { state ->
                        state.copy(
                            matrix = state.matrix.copy(loading = false, loadingMore = false),
                        )
                    }
                }
            }
        }
    }

    
    fun refreshRoot() {
        viewModelScope.launch {
            try {
                rootStatus.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                store.show(SecurityMessage.Raw(t.message ?: "重新检测失败"))
            }
        }
    }

    
    fun retryApps() {
        viewModelScope.launch { loadAppList(forceRefresh = true) }
    }

    private suspend fun loadAppList(forceRefresh: Boolean = false) {
        store.update { it.copy(appsLoading = true, appsError = null) }
        try {
            when (val result = appRepository.list(forceRefresh = forceRefresh)) {
                is XgResult.Ok -> {
                    val sorted = result.data.sortedWith(
                        compareBy<AppEntry>({ it.isSystem }, { it.label.lowercase() }),
                    )
                    store.update {
                        it.copy(apps = sorted, appsLoading = false, appsError = null)
                    }
                    matrix.syncCounts()
                    matrix.ensureLoaded()
                }

                is XgResult.Err -> {
                    store.update { it.copy(appsLoading = false, appsError = UiError.of(result)) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            store.update {
                it.copy(appsLoading = false, appsError = UiError.of(t, "读取应用清单失败"))
            }
        }
    }

    fun setMatrixQuery(query: String) = matrix.setQuery(query)

    fun setMatrixFilter(filter: MatrixFilter) = matrix.setFilter(filter)

    fun retryMatrix() = matrix.retry()

    fun loadMoreMatrix() = matrix.loadMore()

    fun cycleCell(packageName: String, op: String) = matrix.cycleCell(packageName, op)

    fun refreshA11y() {
        viewModelScope.launch { a11y.load(forceRefresh = true) }
    }

    fun requestA11yLock(enable: Boolean) = a11y.requestLock(enable)

    fun confirmA11yUnlock() = a11y.confirmUnlock()

    fun toggleA11yService(service: String, include: Boolean) =
        a11y.toggleService(service, include)

    fun addAllA11yServices() = a11y.addAllServices()

    fun refreshAutostart() = autostart.refresh()

    fun toggleAutostartGroup(packageName: String) = autostart.toggleGroup(packageName)

    fun expandAllAutostart() = autostart.expandAll()

    fun collapseAllAutostart() = autostart.collapseAll()

    fun setReceiverEnabled(receiver: BootReceiverInfo, enabled: Boolean) =
        autostart.setEnabled(receiver, enabled)

    fun scanAds() = ads.scan()

    fun requestAdFreeze(packageName: String, label: String) =
        ads.requestFreeze(packageName, label)

    fun confirmAdFreeze() = ads.confirmFreeze()

    fun requestAdUnfreeze(packageName: String, label: String) =
        ads.requestUnfreeze(packageName, label)

    fun confirmAdUnfreeze() = ads.confirmUnfreeze()

    fun requestAdUninstall(packageName: String, label: String) =
        ads.requestUninstall(packageName, label)

    fun confirmAdUninstall() = ads.confirmUninstall()

    fun refreshAdHosts() = adHosts.refresh()

    fun applyAdHosts() = adHosts.apply()

    fun requestDisableAdHosts() = adHosts.requestDisable()

    fun confirmDisableAdHosts() = adHosts.confirmDisable()

    fun dismissDialog() {
        store.update { it.copy(dialog = null) }
    }
}
