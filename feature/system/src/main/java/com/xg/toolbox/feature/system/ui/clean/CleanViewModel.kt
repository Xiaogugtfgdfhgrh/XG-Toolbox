package com.xg.toolbox.feature.system.ui.clean

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.feature.system.domain.clean.CleanCategory
import com.xg.toolbox.feature.system.domain.clean.CleanController
import com.xg.toolbox.feature.system.domain.clean.CleanScan
import com.xg.toolbox.feature.system.ui.optimize.normalizePercent
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

data class CleanUiState(
    val loading: Boolean = true,
    val scanning: Boolean = false,
    val scan: CleanScan? = null,
    val scanProgress: Float = 0f,
    val selectedIds: Set<String> = emptySet(),
    val expandedIds: Set<String> = emptySet(),
    val cleaning: Boolean = false,
    val cleanProgress: Float = 0f,
    val reportedFreedBytes: Long = 0,
    val cleanMessage: String? = null,
    val duBeforeBytes: Long? = null,
    val duAfterBytes: Long? = null,
    val duVerifiedBytes: Long? = null,
    val duError: String? = null,
    val finished: Boolean = false,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
    val errorDetail: String? = null,
    val rootState: RootState = RootState.Unknown,
) {
    val categories: List<CleanCategory> get() = scan?.categories.orEmpty()
    val rootGranted: Boolean get() = rootState is RootState.Granted
    val selectedBytes: Long
        get() = categories.filter { it.id in selectedIds }.sumOf { it.bytes }
    val canClean: Boolean
        get() = !cleaning && !scanning && selectedIds.isNotEmpty() && rootGranted
}

@HiltViewModel
class CleanViewModel @Inject constructor(
    private val controller: CleanController,
    rootStatus: RootStatusRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CleanUiState())
    val uiState: StateFlow<CleanUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null
    private var cleanJob: Job? = null
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
        if (_uiState.value.cleaning) return
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    scanning = true,
                    scanProgress = 0f,
                    errorCode = null,
                    errorMessage = null,
                    errorDetail = null,
                )
            }
            val result = call {
                controller.scan(forceRefresh = forceRefresh) { progress ->
                    _uiState.update { state ->
                        state.copy(scanProgress = normalizePercent(progress))
                    }
                }
            }
            when (result) {
                is XgResult.Ok -> {
                    val scan = result.data
                    val ids = scan.categories.map { it.id }.toSet()
                    _uiState.update { current ->
                        current.copy(
                            loading = false,
                            scanning = false,
                            scanProgress = 1f,
                            scan = scan,
                            selectedIds = if (current.selectedIds.isEmpty()) {
                                ids
                            } else {
                                current.selectedIds.intersect(ids)
                            },
                            expandedIds = current.expandedIds.intersect(ids),
                            reportedFreedBytes = 0,
                            cleanMessage = null,
                            duBeforeBytes = null,
                            duAfterBytes = null,
                            duVerifiedBytes = null,
                            duError = null,
                            finished = false,
                            errorCode = null,
                            errorMessage = null,
                            errorDetail = null,
                        )
                    }
                }

                is XgResult.Err -> _uiState.update {
                    it.copy(
                        loading = false,
                        scanning = false,
                        errorCode = result.code,
                        errorMessage = result.message,
                        errorDetail = result.detail,
                    )
                }
            }
        }
    }

    fun toggleCategory(id: String) {
        val current = _uiState.value
        if (current.cleaning || current.scanning) return
        if (current.categories.none { it.id == id }) return
        _uiState.update { state ->
            state.copy(
                selectedIds = if (id in state.selectedIds) {
                    state.selectedIds - id
                } else {
                    state.selectedIds + id
                },
            )
        }
    }

    fun toggleExpanded(id: String) {
        _uiState.update { state ->
            state.copy(
                expandedIds = if (id in state.expandedIds) {
                    state.expandedIds - id
                } else {
                    state.expandedIds + id
                },
            )
        }
    }

    fun selectAll() {
        if (_uiState.value.cleaning || _uiState.value.scanning) return
        _uiState.update { state ->
            state.copy(selectedIds = state.categories.map { it.id }.toSet())
        }
    }

    fun clearSelection() {
        if (_uiState.value.cleaning || _uiState.value.scanning) return
        _uiState.update { it.copy(selectedIds = emptySet()) }
    }

    
    fun cleanSelected() {
        val current = _uiState.value
        val before = current.scan ?: return
        if (!current.canClean) return
        val ids = current.selectedIds
        cleanJob?.cancel()
        cleanJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    cleaning = true,
                    cleanProgress = 0f,
                    reportedFreedBytes = 0,
                    cleanMessage = null,
                    duBeforeBytes = null,
                    duAfterBytes = null,
                    duVerifiedBytes = null,
                    duError = null,
                    finished = false,
                    errorCode = null,
                    errorMessage = null,
                    errorDetail = null,
                )
            }
            var failureMessage: String? = null
            var flowErrorMessage: String? = null
            val reportedByCategory = linkedMapOf<String, Long>()
            try {
                withContext(Dispatchers.IO) {
                    controller.clean(ids).collect { progress ->
                        if (progress.categoryId.isBlank()) {
                            flowErrorMessage = progress.message ?: "清理未执行"
                        }
                        if (progress.categoryId.isNotBlank()) {
                            reportedByCategory[progress.categoryId] = progress.freedBytes
                        }
                        _uiState.update { state ->
                            state.copy(
                                cleanProgress = normalizePercent(progress.percent),
                                reportedFreedBytes = reportedByCategory.values.sum(),
                                cleanMessage = progress.message,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                failureMessage = throwable.message ?: throwable.javaClass.simpleName
            }

            val rescan = call { controller.scan(forceRefresh = true) }
            _uiState.update { state ->
                when (rescan) {
                    is XgResult.Ok -> {
                        val after = rescan.data
                        state.copy(
                            scan = after,
                            selectedIds = after.categories.map { it.id }.toSet(),
                            expandedIds = state.expandedIds.intersect(
                                after.categories.map { it.id }.toSet(),
                            ),
                            duBeforeBytes = before.totalBytes,
                            duAfterBytes = after.totalBytes,
                            duVerifiedBytes = (before.totalBytes - after.totalBytes)
                                .coerceAtLeast(0L),
                            duError = null,
                            cleaning = false,
                            cleanProgress = 1f,
                            finished = true,
                            errorCode = if (failureMessage != null || flowErrorMessage != null) {
                                ErrorCode.ERR_CMD_FAILED
                            } else {
                                null
                            },
                            errorMessage = failureMessage ?: flowErrorMessage,
                        )
                    }

                    is XgResult.Err -> state.copy(
                        duBeforeBytes = before.totalBytes,
                        duAfterBytes = null,
                        duVerifiedBytes = null,
                        duError = rescan.message,
                        cleaning = false,
                        cleanProgress = 1f,
                        finished = true,
                        errorCode = if (failureMessage != null || flowErrorMessage != null) {
                            ErrorCode.ERR_CMD_FAILED
                        } else {
                            null
                        },
                        errorMessage = failureMessage ?: flowErrorMessage,
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
        cleanJob?.cancel()
        super.onCleared()
    }
}
