package com.xg.toolbox.feature.system.ui.optimize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.feature.system.domain.optimize.OptimizeCompare
import com.xg.toolbox.feature.system.domain.optimize.OptimizeController
import com.xg.toolbox.feature.system.domain.optimize.OptimizeItem
import com.xg.toolbox.feature.system.domain.optimize.OptimizeProgress
import com.xg.toolbox.feature.system.domain.optimize.OptimizeScan
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OptimizeUiState(
    val loading: Boolean = true,
    val scan: OptimizeScan? = null,
    val selectedIds: Set<String> = emptySet(),
    val running: Boolean = false,
    val progress: Map<String, OptimizeProgress> = emptyMap(),
    val overallProgress: Float = 0f,
    val compare: OptimizeCompare? = null,
    val compareError: String? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
    val errorDetail: String? = null,
    val rootState: RootState = RootState.Unknown,
) {
    val items: List<OptimizeItem> get() = scan?.items.orEmpty()
    val rootGranted: Boolean get() = rootState is RootState.Granted
    val canRun: Boolean get() = !running && selectedIds.isNotEmpty() && rootGranted
}

@HiltViewModel
class OptimizeViewModel @Inject constructor(
    private val controller: OptimizeController,
    rootStatus: RootStatusRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OptimizeUiState())
    val uiState: StateFlow<OptimizeUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var runJob: Job? = null
    private var firstLoadRequested = false

    init {
        viewModelScope.launch {
            rootStatus.state.collect { state ->
                _uiState.update { it.copy(rootState = state) }
            }
        }
    }

    
    fun ensureLoaded() {
        if (firstLoadRequested) return
        firstLoadRequested = true
        scanInternal(forceRefresh = false)
    }

    
    fun scan() = scanInternal(forceRefresh = true)

    private fun scanInternal(forceRefresh: Boolean) {
        if (_uiState.value.running) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    errorCode = null,
                    errorMessage = null,
                    errorDetail = null,
                )
            }
            val result = call { controller.scan(forceRefresh = forceRefresh) }
            when (result) {
                is XgResult.Ok -> {
                    val scan = result.data
                    val availableIds = scan.items.filter { it.available }.map { it.id }.toSet()
                    _uiState.update { current ->
                        val selection = if (current.selectedIds.isEmpty()) {
                            availableIds
                        } else {
                            current.selectedIds.intersect(availableIds)
                        }
                        current.copy(
                            loading = false,
                            scan = scan,
                            selectedIds = selection,
                            progress = emptyMap(),
                            overallProgress = 0f,
                            compare = null,
                            compareError = null,
                            errorCode = null,
                            errorMessage = null,
                            errorDetail = null,
                        )
                    }
                }

                is XgResult.Err -> _uiState.update {
                    it.copy(
                        loading = false,
                        errorCode = result.code,
                        errorMessage = result.message,
                        errorDetail = result.detail,
                    )
                }
            }
        }
    }

    fun toggleSelection(id: String) {
        val current = _uiState.value
        if (current.running) return
        val item = current.items.firstOrNull { it.id == id } ?: return
        if (!item.available) return
        _uiState.update { state ->
            val selected = if (id in state.selectedIds) {
                state.selectedIds - id
            } else {
                state.selectedIds + id
            }
            state.copy(selectedIds = selected)
        }
    }

    fun selectAll() {
        val current = _uiState.value
        if (current.running) return
        _uiState.update { state ->
            state.copy(
                selectedIds = state.items.filter { it.available }.map { it.id }.toSet(),
            )
        }
    }

    fun clearSelection() {
        if (_uiState.value.running) return
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    
    fun runSelected() {
        val current = _uiState.value
        val before = current.scan ?: return
        if (current.running || !current.rootGranted) return
        val ids = current.selectedIds.filter { id ->
            before.items.any { it.id == id && it.available }
        }.toSet()
        if (ids.isEmpty()) return

        runJob?.cancel()
        runJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    running = true,
                    progress = emptyMap(),
                    overallProgress = 0f,
                    compare = null,
                    compareError = null,
                    errorCode = null,
                    errorMessage = null,
                    errorDetail = null,
                )
            }
            val progressById = linkedMapOf<String, OptimizeProgress>()
            var failure: Throwable? = null
            var aborted = false
            var abortedMessage: String? = null
            try {
                withContext(Dispatchers.IO) {
                    controller.run(ids).collect { itemProgress ->
                        if (itemProgress.itemId.isBlank()) {
                            aborted = true
                            abortedMessage = itemProgress.message ?: itemProgress.status
                            _uiState.update {
                                it.copy(
                                    errorCode = ErrorCode.ERR_CMD_FAILED,
                                    errorMessage = abortedMessage,
                                )
                            }
                            return@collect
                        }
                        progressById[itemProgress.itemId] = itemProgress
                        val overall = progressById.values
                            .maxOfOrNull { normalizePercent(it.percent) }
                            ?: 0f
                        _uiState.update {
                            it.copy(
                                progress = progressById.toMap(),
                                overallProgress = overall.coerceIn(0f, 1f),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                failure = throwable
            }

            val compareResult = call { controller.compare(before) }
            val failureMessage = failure?.let { it.message ?: it.javaClass.simpleName }
            _uiState.update { state ->
                when (compareResult) {
                    is XgResult.Ok -> state.copy(
                        running = false,
                        overallProgress = if (aborted) 0f else 1f,
                        compare = compareResult.data,
                        compareError = null,
                        errorCode = if (failureMessage != null || abortedMessage != null) {
                            ErrorCode.ERR_CMD_FAILED
                        } else {
                            null
                        },
                        errorMessage = failureMessage ?: abortedMessage,
                    )

                    is XgResult.Err -> state.copy(
                        running = false,
                        overallProgress = if (aborted) 0f else 1f,
                        compare = null,
                        compareError = compareResult.message,
                        errorCode = if (failureMessage != null || abortedMessage != null) {
                            ErrorCode.ERR_CMD_FAILED
                        } else {
                            null
                        },
                        errorMessage = failureMessage ?: abortedMessage,
                    )
                }
            }
        }
    }

    private suspend fun <T> call(block: suspend () -> XgResult<T>): XgResult<T> = try {
        withContext(Dispatchers.IO) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (throwable: Throwable) {
        XgResult.Err(
            code = ErrorCode.ERR_CMD_FAILED,
            message = throwable.message ?: throwable.javaClass.simpleName,
        )
    }

    override fun onCleared() {
        scanJob?.cancel()
        runJob?.cancel()
        super.onCleared()
    }
}
