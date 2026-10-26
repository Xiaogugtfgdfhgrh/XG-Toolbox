package com.xg.toolbox.feature.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AuditRepository
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.core.device.DeviceField
import com.xg.toolbox.core.device.DeviceGroup
import com.xg.toolbox.core.device.DeviceInfoRepository
import com.xg.toolbox.core.device.MetricsSampler
import com.xg.toolbox.core.device.RebootController
import com.xg.toolbox.core.device.RebootTargetInfo
import com.xg.toolbox.core.device.RiskEvaluator
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val deviceInfoRepository: DeviceInfoRepository,
    private val metricsSampler: MetricsSampler,
    private val riskEvaluator: RiskEvaluator,
    private val rebootController: RebootController,
    private val auditRepository: AuditRepository,
    private val settingsStore: SettingsStore,
    private val rootStatusRepository: RootStatusRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _metricsUi = MutableStateFlow(EmptyHomeMetricsUi)

    
    val metricsUi: StateFlow<HomeMetricsUi> = _metricsUi.asStateFlow()

    private val metricsMapper = HomeMetricsMapper(context)

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    private var deviceJob: Job? = null
    private var riskJob: Job? = null
    private var recentJob: Job? = null
    private var dataVersionCounter: Int = 0

    
    private var lastRootState: RootState = RootState.Unknown
    private var rootRecollectScheduled: Boolean = false

    init {
        lastRootState = rootStatusRepository.state.value
        observeMetrics()
        observeRecent()
        observeReadOnly()
        observeRoot()
        loadDeviceInfo()
        loadRisks()
        refreshRebootTargets()
    }

    
    fun retry() {
        loadDeviceInfo(forceRefresh = true)
        loadRisks(forceRefresh = true)
        refreshRebootTargets()
    }

    
    fun requestReboot(target: RebootTargetInfo) {
        if (!target.available) return
        if (_uiState.value.rebooting) return
        _uiState.update { it.copy(pendingReboot = target) }
    }

    fun dismissReboot() {
        _uiState.update { it.copy(pendingReboot = null) }
    }

    
    fun confirmReboot() {
        val target = _uiState.value.pendingReboot ?: return
        if (_uiState.value.rebooting) return
        viewModelScope.launch {
            _uiState.update { it.copy(rebooting = true) }
            try {
                when (val result = rebootController.reboot(target.id)) {
                    is XgResult.Ok -> _events.tryEmit(HomeEvent.RebootSent)
                    is XgResult.Err -> _events.tryEmit(HomeEvent.Error(result.message))
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _events.tryEmit(HomeEvent.Error(t.message?.takeIf { it.isNotBlank() } ?: "重启命令执行失败"))
            } finally {
                _uiState.update { it.copy(rebooting = false, pendingReboot = null) }
            }
        }
    }

    
    fun toggleRecentExpanded(auditId: Long) {
        _uiState.update { current ->
            current.copy(
                expandedAuditId = if (current.expandedAuditId == auditId) null else auditId,
            )
        }
    }

    private fun observeMetrics() {
        viewModelScope.launch {
            metricsSampler.history
                .map { history -> metricsMapper.map(history) }
                .flowOn(Dispatchers.Default)
                .collect { ui -> _metricsUi.value = ui }
        }
        viewModelScope.launch {
            runCatching { metricsSampler.stream().collect { } }
        }
    }

    private fun observeRecent() {
        recentJob?.cancel()
        recentJob = viewModelScope.launch {
            try {
                auditRepository.observeRecent(limit = 3).collect { recent ->
                    _uiState.update { it.copy(recent = recent, recentLoaded = true, recentError = null) }
                }
            } catch (throwable: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        recentLoaded = true,
                        recentError = throwable.message?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.home_recent_load_failed),
                    )
                }
            }
        }
    }

    private fun observeReadOnly() {
        viewModelScope.launch {
            settingsStore.readOnly.collect { readOnly ->
                _uiState.update { it.copy(readOnly = readOnly) }
            }
        }
    }

    private fun observeRoot() {
        viewModelScope.launch {
            combine(
                rootStatusRepository.state,
                rootStatusRepository.rootInfo,
            ) { state, info -> state to info }.collect { (state, info) ->
                val becameGranted = state is RootState.Granted && lastRootState !is RootState.Granted
                lastRootState = state
                _uiState.update { current ->
                    current.copy(
                        rootState = state,
                        rootInfo = info,
                        summary = current.summary?.copy(rooted = state is RootState.Granted),
                    )
                }
                refreshRebootTargets()
                if (becameGranted) scheduleRootRecollect()
            }
        }
    }

    
    private fun scheduleRootRecollect() {
        if (rootRecollectScheduled) return
        rootRecollectScheduled = true
        viewModelScope.launch {
            deviceJob?.join()
            riskJob?.join()
            rootRecollectScheduled = false
            loadDeviceInfo(forceRefresh = true)
            loadRisks(forceRefresh = true)
        }
    }

    private fun loadDeviceInfo(forceRefresh: Boolean = false) {
        if (deviceJob?.isActive == true) return
        deviceJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    loading = current.summary == null,
                    error = null,
                )
            }
            try {
                val groups = withContext(Dispatchers.IO) { deviceInfoRepository.collect(forceRefresh) }
                val fields = groups.flatMap { group: DeviceGroup -> group.fields }
                dataVersionCounter += 1
                _uiState.update { current ->
                    current.copy(
                        loading = false,
                        error = null,
                        fields = fields,
                        summary = buildSummary(fields, current.rootState),
                        dataVersion = dataVersionCounter,
                    )
                }
            } catch (throwable: Throwable) {
                val message = throwable.message?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.home_load_failed)
                _uiState.update { current -> current.copy(loading = false, error = message) }
            }
        }
    }

    private fun loadRisks(forceRefresh: Boolean = false) {
        if (riskJob?.isActive == true) return
        riskJob = viewModelScope.launch {
            try {
                val risks = withContext(Dispatchers.IO) {
                    riskEvaluator.evaluate(forceRefresh = forceRefresh)
                }.take(MAX_RISKS)
                _uiState.update { it.copy(risks = risks, riskLoaded = true, riskError = null) }
            } catch (throwable: Throwable) {
                _uiState.update { current ->
                    current.copy(
                        riskLoaded = true,
                        riskError = throwable.message?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.home_risk_failed_generic),
                    )
                }
            }
        }
    }

    private fun refreshRebootTargets() {
        val targets = runCatching { rebootController.targets() }.getOrDefault(emptyList())
        _uiState.update { it.copy(rebootTargets = targets) }
    }

    
    private fun buildSummary(fields: List<DeviceField>, rootState: RootState): HomeSummaryUi? {
        if (fields.isEmpty()) return null
        fun valueOf(vararg propertyKeys: String): String? = propertyKeys.firstNotNullOfOrNull { key ->
            fields.firstOrNull { it.propertyKey == key }?.value?.takeIf { it.isNotBlank() }
        }

        val model = valueOf("ro.product.model")
        val marketName = valueOf("ro.product.marketname")
        val deviceName = valueOf("settings.global.device_name")
        val name = marketName ?: model ?: deviceName
            ?: fields.firstOrNull { !it.value.isNullOrBlank() }?.value
            ?: return null

        val device = valueOf("ro.product.device")
        val version = valueOf("ro.build.version.incremental")
        val android = valueOf("ro.build.version.release")
        val metaParts = buildList {
            model?.let { add(it) }
            device?.let { add(it) }
            version?.let { add(it) }
            android?.let { add(context.getString(R.string.home_android_prefix, it)) }
        }

        val unlocked = valueOf("ro.boot.verifiedbootstate", "ro.boot.flash.locked")
            ?.let { raw ->
                val lower = raw.lowercase()
                raw.contains("已解锁") || lower.contains("orange") || lower.contains("unlocked")
            } == true

        return HomeSummaryUi(
            name = name,
            meta = metaParts.distinct().joinToString(" · "),
            unlocked = unlocked,
            rooted = rootState is RootState.Granted,
        )
    }

    private companion object {
        const val MAX_RISKS = 3
    }
}
