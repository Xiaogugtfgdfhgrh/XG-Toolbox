package com.xg.toolbox.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.core.shell.RootInfo
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RootGateUiState(
    val rootState: RootState = RootState.Unknown,
    val rootInfo: RootInfo? = null,
    val evidence: List<String> = emptyList(),
    val settled: Boolean = false,
    val gateDismissed: Boolean = false,
    val probing: Boolean = false,
) {
    
    val readOnly: Boolean get() = settled && rootState !is RootState.Granted

    
    val showGate: Boolean get() =
        settled && !gateDismissed && (rootState is RootState.Denied || rootState is RootState.Unknown)
}

@HiltViewModel
class RootGateViewModel @Inject constructor(
    repository: RootStatusRepository,
    private val coordinator: RootProbeCoordinator,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private data class ProbeSnapshot(
        val state: RootState,
        val info: RootInfo?,
        val evidence: List<String>,
        val settled: Boolean,
    )

    private val dismissed = MutableStateFlow(false)
    private val probing = MutableStateFlow(false)

    private val snapshot = combine(
        repository.state,
        repository.rootInfo,
        repository.evidence,
        coordinator.settled,
    ) { state, info, evidence, settled ->
        ProbeSnapshot(state = state, info = info, evidence = evidence, settled = settled)
    }

    val uiState: StateFlow<RootGateUiState> = combine(snapshot, dismissed, probing) { probe, isDismissed, isProbing ->
        RootGateUiState(
            rootState = probe.state,
            rootInfo = probe.info,
            evidence = probe.evidence,
            settled = probe.settled,
            gateDismissed = isDismissed,
            probing = isProbing,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RootGateUiState(),
    )

    init {
        viewModelScope.launch {
            combine(snapshot, settingsStore.readOnly) { probe, persisted ->
                if (!probe.settled) {
                    null
                } else {
                    (probe.state !is RootState.Granted) to persisted
                }
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (derivedReadOnly, persistedReadOnly) ->
                    if (derivedReadOnly == persistedReadOnly) return@collect
                    try {
                        settingsStore.setReadOnly(derivedReadOnly)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        XgLog.e(TAG, "同步只读门禁失败", t)
                    }
                }
        }
        viewModelScope.launch { coordinator.ensureProbed() }
    }
    

    fun dismissGate() {
        dismissed.value = true
    }

    
    fun retryProbe() {
        if (probing.value) return
        probing.value = true
        viewModelScope.launch {
            try {
                coordinator.reprobe()
            } finally {
                probing.value = false
            }
        }
    }

    private companion object {
        const val TAG = "RootGateViewModel"
    }
}
