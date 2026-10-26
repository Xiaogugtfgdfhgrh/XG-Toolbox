package com.xg.toolbox.feature.system.ui.processes

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.processes.ProcessController
import com.xg.toolbox.feature.system.domain.processes.ProcessGroup
import com.xg.toolbox.feature.system.domain.processes.ProcessItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

data class ProcessesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val processes: List<ProcessItem> = emptyList(),
    
    val rows: List<ProcessRowUi> = emptyList(),
    
    val visibleRows: List<ProcessRowUi> = emptyList(),
    val group: ProcessGroup? = null,
    val selectedPids: Set<Int> = emptySet(),
    val expandedPids: Set<Int> = emptySet(),
    val stopping: Boolean = false,
    val stopProgress: Float = 0f,
    val stopCurrent: String? = null,
    val stopTotal: Int = 0,
    val stopSuccessCount: Int = 0,
    val stopFailedCount: Int = 0,
    val stopFailures: List<String> = emptyList(),
    val errorCode: Int? = null,
    val errorMessage: String? = null,
    val errorDetail: String? = null,
    val rootState: RootState = RootState.Unknown,
) {
    val rootGranted: Boolean get() = rootState is RootState.Granted
    val canStopSelected: Boolean
        get() = !stopping && selectedPids.isNotEmpty() && rootGranted
}

@HiltViewModel
class ProcessesViewModel @Inject constructor(
    private val controller: ProcessController,
    rootStatus: RootStatusRepository,
    private val iconCache: ProcessIconCache,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProcessesUiState())
    val uiState: StateFlow<ProcessesUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var stopJob: Job? = null
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
        loadInternal(forceRefresh = false)
    }

    
    suspend fun loadIcon(packageName: String): ImageBitmap? = iconCache.load(packageName)

    
    fun peekIcon(packageName: String): ImageBitmap? = iconCache.peek(packageName)

    fun load() = loadInternal(forceRefresh = true)

    private fun loadInternal(forceRefresh: Boolean) {
        if (_uiState.value.stopping) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val firstLoad = _uiState.value.processes.isEmpty()
            _uiState.update {
                it.copy(
                    loading = firstLoad,
                    refreshing = !firstLoad,
                    errorCode = null,
                    errorMessage = null,
                    errorDetail = null,
                )
            }
            val result = call { controller.list(forceRefresh = forceRefresh) }
            when (result) {
                is XgResult.Ok -> {
                    val processes = result.data
                    val rows = withContext(Dispatchers.IO) { buildRows(processes) }
                    val pids = processes.map { it.pid }.toHashSet()
                    _uiState.update { current ->
                        current.copy(
                            loading = false,
                            refreshing = false,
                            processes = processes,
                            rows = rows,
                            visibleRows = filterRows(rows, current.group),
                            selectedPids = current.selectedPids.intersect(pids),
                            expandedPids = current.expandedPids.intersect(pids),
                            errorCode = null,
                            errorMessage = null,
                            errorDetail = null,
                        )
                    }
                }

                is XgResult.Err -> _uiState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        errorCode = result.code,
                        errorMessage = result.message,
                        errorDetail = result.detail,
                    )
                }
            }
        }
    }

    fun setGroup(group: ProcessGroup?) {
        if (_uiState.value.stopping) return
        _uiState.update { state ->
            state.copy(
                group = group,
                visibleRows = filterRows(state.rows, group),
                selectedPids = emptySet(),
            )
        }
    }

    fun toggleSelection(pid: Int) {
        val current = _uiState.value
        if (current.stopping) return
        val item = current.processes.firstOrNull { it.pid == pid } ?: return
        if (item.protected) return
        _uiState.update { state ->
            state.copy(
                selectedPids = if (pid in state.selectedPids) {
                    state.selectedPids - pid
                } else {
                    state.selectedPids + pid
                },
            )
        }
    }

    fun toggleExpanded(pid: Int) {
        _uiState.update { state ->
            state.copy(
                expandedPids = if (pid in state.expandedPids) {
                    state.expandedPids - pid
                } else {
                    state.expandedPids + pid
                },
            )
        }
    }

    fun selectAllVisible() {
        if (_uiState.value.stopping) return
        _uiState.update { state ->
            state.copy(
                selectedPids = state.visibleRows
                    .filter { !it.protected }
                    .map { it.pid }
                    .toSet(),
            )
        }
    }

    fun clearSelection() {
        if (_uiState.value.stopping) return
        _uiState.update { it.copy(selectedPids = emptySet()) }
    }

    
    fun stopSelected() {
        val current = _uiState.value
        val targets = current.processes.filter { it.pid in current.selectedPids && !it.protected }
        stop(targets, force = false)
    }

    
    fun stopSingle(item: ProcessItem, force: Boolean) {
        if (item.protected) return
        stop(listOf(item), force = force)
    }

    private fun stop(targets: List<ProcessItem>, force: Boolean) {
        val current = _uiState.value
        if (targets.isEmpty() || current.stopping || !current.rootGranted) return
        stopJob?.cancel()
        stopJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    stopping = true,
                    stopProgress = 0f,
                    stopCurrent = null,
                    stopTotal = targets.size,
                    stopSuccessCount = 0,
                    stopFailedCount = 0,
                    stopFailures = emptyList(),
                )
            }
            var success = 0
            var failed = 0
            val failures = mutableListOf<String>()
            targets.forEachIndexed { index, item ->
                _uiState.update {
                    it.copy(
                        stopCurrent = item.name,
                        stopProgress = index.toFloat() / targets.size.toFloat(),
                    )
                }
                val result = call { controller.stop(item, force) }
                when (result) {
                    is XgResult.Ok -> success += 1
                    is XgResult.Err -> {
                        failed += 1
                        failures += "${item.name} (${item.pid})：${result.message}"
                    }
                }
                _uiState.update {
                    it.copy(
                        stopProgress = (index + 1).toFloat() / targets.size.toFloat(),
                        stopSuccessCount = success,
                        stopFailedCount = failed,
                        stopFailures = failures.toList(),
                    )
                }
            }
            _uiState.update {
                it.copy(
                    stopping = false,
                    stopProgress = 1f,
                    stopCurrent = null,
                    selectedPids = emptySet(),
                )
            }
            load()
        }
    }

    private fun buildRows(processes: List<ProcessItem>): List<ProcessRowUi> =
        processes.map { item -> toRowUi(item) }

    private fun toRowUi(item: ProcessItem): ProcessRowUi {
        val protectedReason = item.protectedReason
        val protectedFallback = context.getString(R.string.sys_core_processes_protected)
        return ProcessRowUi(
            pid = item.pid,
            title = item.name.ifBlank {
                item.cmdline.trim().substringBefore(' ').ifBlank { "PID ${item.pid}" }
            },
            meta = context.getString(
                R.string.sys_core_processes_row_meta,
                item.pid,
                item.uid,
                Formatters.bytes(item.rssKb * 1024L),
                item.stat,
            ),
            group = item.group,
            groupLabel = groupLabel(item.group),
            groupTone = groupTone(item.group),
            protected = item.protected,
            protectedText = if (item.protected) {
                context.getString(
                    R.string.sys_core_processes_protected_reason,
                    protectedReason ?: protectedFallback,
                )
            } else {
                null
            },
            iconPackage = candidatePackage(item),
            item = item,
        )
    }

    private fun filterRows(rows: List<ProcessRowUi>, group: ProcessGroup?): List<ProcessRowUi> =
        if (group == null) rows else rows.filter { it.group == group }

    private fun groupLabel(group: ProcessGroup): String = context.getString(
        when (group) {
            ProcessGroup.FOREGROUND -> R.string.sys_core_processes_group_foreground
            ProcessGroup.VISIBLE -> R.string.sys_core_processes_group_visible
            ProcessGroup.SERVICE -> R.string.sys_core_processes_group_service
            ProcessGroup.CACHED -> R.string.sys_core_processes_group_cached
            ProcessGroup.SYSTEM -> R.string.sys_core_processes_group_system
            ProcessGroup.KERNEL -> R.string.sys_core_processes_group_kernel
        },
    )

    private fun groupTone(group: ProcessGroup): XgTone = when (group) {
        ProcessGroup.FOREGROUND -> XgTone.Accent
        ProcessGroup.VISIBLE -> XgTone.Ok
        ProcessGroup.SERVICE -> XgTone.Warn
        ProcessGroup.CACHED, ProcessGroup.KERNEL -> XgTone.Muted
        ProcessGroup.SYSTEM -> XgTone.Neutral
    }

    
    private fun candidatePackage(item: ProcessItem): String? {
        if (item.group !in APP_GROUPS) return null
        val raw = item.cmdline.trim().ifBlank { item.name.trim() }
        if (raw.isEmpty()) return null
        val token = raw.substringBefore(' ').substringBefore(':')
        if (token.length !in PACKAGE_NAME_MIN..PACKAGE_NAME_MAX) return null
        return token.takeIf { PACKAGE_NAME.matches(it) }
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
        loadJob?.cancel()
        stopJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val PACKAGE_NAME_MIN = 3
        const val PACKAGE_NAME_MAX = 200
        val PACKAGE_NAME = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$")
        val APP_GROUPS = setOf(
            ProcessGroup.FOREGROUND,
            ProcessGroup.VISIBLE,
            ProcessGroup.SERVICE,
            ProcessGroup.CACHED,
        )
    }
}
