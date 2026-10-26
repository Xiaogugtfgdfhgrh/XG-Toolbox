package com.xg.toolbox.feature.system.ui.scripts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.feature.system.domain.scripts.ScriptInfo
import com.xg.toolbox.feature.system.domain.scripts.ScriptLine
import com.xg.toolbox.feature.system.domain.scripts.ScriptResult
import com.xg.toolbox.feature.system.domain.scripts.ScriptRunner
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

private const val MAX_LOG_LINES = 400
private val DONE_MARKER = Regex("""XG_DONE\s+(\d+)""")

data class ScriptsUiState(
    val loading: Boolean = true,
    val scripts: List<ScriptInfo> = emptyList(),
    val loadErrorCode: Int? = null,
    val loadErrorMessage: String? = null,
    val installing: Boolean = false,
    val installErrorCode: Int? = null,
    val installErrorMessage: String? = null,
    val runningId: String? = null,
    val lines: List<ScriptLine> = emptyList(),
    val lastResults: Map<String, ScriptResult> = emptyMap(),
    val runResult: ScriptResult? = null,
    val doneBytes: Long? = null,
    val doneLine: String? = null,
    val runErrorMessage: String? = null,
    val rootState: RootState = RootState.Unknown,
) {
    val rootGranted: Boolean get() = rootState is RootState.Granted
    val canRun: Boolean get() = !loading && !installing && runningId == null && rootGranted
}

@HiltViewModel
class ScriptsViewModel @Inject constructor(
    private val runner: ScriptRunner,
    rootStatus: RootStatusRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScriptsUiState())
    val uiState: StateFlow<ScriptsUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
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
        load(forceRefresh = false)
    }

    fun reload() = load(forceRefresh = true)

    private fun load(forceRefresh: Boolean) {
        if (_uiState.value.runningId != null) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    loading = true,
                    loadErrorCode = null,
                    loadErrorMessage = null,
                    installErrorCode = null,
                    installErrorMessage = null,
                )
            }
            val scriptsResult = withContext(Dispatchers.IO) {
                runCatching { runner.scripts(forceRefresh = forceRefresh) }
            }
            val scripts = scriptsResult.getOrNull().orEmpty()
            val results = withContext(Dispatchers.IO) {
                val collected = mutableMapOf<String, ScriptResult>()
                for (script in scripts) {
                    runCatching { runner.lastResult(script.id) }
                        .getOrNull()
                        ?.let { result -> collected[script.id] = result }
                }
                collected
            }
            val failure = scriptsResult.exceptionOrNull()
            _uiState.update {
                it.copy(
                    loading = false,
                    scripts = scripts,
                    lastResults = results,
                    loadErrorCode = if (failure != null) ErrorCode.ERR_CMD_FAILED else null,
                    loadErrorMessage = failure?.message?.takeIf { message -> message.isNotBlank() }
                        ?: if (failure != null) "读取脚本清单失败" else null,
                )
            }
            if (failure == null) install()
        }
    }

    
    fun install() {
        if (_uiState.value.installing) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    installing = true,
                    installErrorCode = null,
                    installErrorMessage = null,
                )
            }
            val result = try {
                withContext(Dispatchers.IO) { runner.ensureInstalled() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                XgResult.Err(
                    code = ErrorCode.ERR_CMD_FAILED,
                    message = throwable.message ?: throwable.javaClass.simpleName,
                )
            }
            when (result) {
                is XgResult.Ok -> _uiState.update { it.copy(installing = false) }
                is XgResult.Err -> _uiState.update {
                    it.copy(
                        installing = false,
                        installErrorCode = result.code,
                        installErrorMessage = result.message,
                    )
                }
            }
        }
    }

    
    fun run(scriptId: String) {
        val current = _uiState.value
        if (current.runningId != null || !current.rootGranted) return
        if (current.scripts.none { it.id == scriptId }) return
        runJob?.cancel()
        runJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    runningId = scriptId,
                    lines = emptyList(),
                    runResult = null,
                    doneBytes = null,
                    doneLine = null,
                    runErrorMessage = null,
                )
            }
            val buffer = mutableListOf<ScriptLine>()
            try {
                withContext(Dispatchers.IO) {
                    runner.run(scriptId).collect { line ->
                        buffer += line
                        while (buffer.size > MAX_LOG_LINES) {
                            buffer.removeAt(0)
                        }
                        val marker = parseDone(buffer)
                        _uiState.update {
                            it.copy(
                                lines = buffer.toList(),
                                doneBytes = marker?.second,
                                doneLine = marker?.first,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                val message = throwable.message ?: throwable.javaClass.simpleName
                buffer += ScriptLine(text = "脚本中断：$message", failed = true)
                _uiState.update {
                    it.copy(
                        lines = buffer.toList(),
                        runErrorMessage = message,
                    )
                }
            }

            val lastResult = withContext(Dispatchers.IO) {
                runCatching { runner.lastResult(scriptId) }.getOrNull()
            }
            val marker = parseDone(buffer)
            _uiState.update { state ->
                state.copy(
                    runningId = null,
                    runResult = lastResult,
                    doneBytes = marker?.second ?: state.doneBytes,
                    doneLine = marker?.first ?: state.doneLine,
                    lastResults = if (lastResult != null) {
                        state.lastResults + (scriptId to lastResult)
                    } else {
                        state.lastResults
                    },
                )
            }
        }
    }

    
    fun clearLog() {
        if (_uiState.value.runningId != null) return
        _uiState.update {
            it.copy(
                lines = emptyList(),
                runResult = null,
                doneBytes = null,
                doneLine = null,
                runErrorMessage = null,
            )
        }
    }

    private fun parseDone(lines: List<ScriptLine>): Pair<String, Long>? {
        for (line in lines.asReversed()) {
            val match = DONE_MARKER.find(line.text) ?: continue
            val bytes = match.groupValues.getOrNull(1)?.toLongOrNull() ?: continue
            return line.text to bytes
        }
        return null
    }

    override fun onCleared() {
        loadJob?.cancel()
        runJob?.cancel()
        super.onCleared()
    }
}
