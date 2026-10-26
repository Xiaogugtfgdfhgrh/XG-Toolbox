package com.xg.toolbox.feature.system.ui.terminal

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.core.device.MetricsSampler
import com.xg.toolbox.core.device.MetricsSnapshot
import com.xg.toolbox.core.shell.ShellLine
import com.xg.toolbox.core.shell.TerminalSession
import com.xg.toolbox.core.shell.TerminalText
import com.xg.toolbox.feature.system.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class TerminalPhase {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR,
    READ_ONLY,
}

enum class TerminalLineKind {
    OUTPUT,
    ERROR,
    COMMAND,
    SYSTEM,
    SUCCESS,
}

data class TerminalLineUi(
    val id: Long,
    val text: String,
    val kind: TerminalLineKind,
)

data class TerminalUiState(
    val phase: TerminalPhase = TerminalPhase.LOADING,
    val lines: List<TerminalLineUi> = emptyList(),
    val input: String = "",
    val history: List<String> = emptyList(),
    val historyIndex: Int? = null,
    val historyDraft: String = "",
    val running: Boolean = false,
    val readOnly: Boolean = false,
    val waitingTooLong: Boolean = false,
    val truncated: Boolean = false,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class TerminalViewModel @Inject constructor(
    private val session: TerminalSession,
    private val settings: SettingsStore,
    private val metrics: MetricsSampler,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    
    private val _metrics = MutableStateFlow<MetricsSnapshot?>(null)
    val metricsState: StateFlow<MetricsSnapshot?> = _metrics.asStateFlow()

    
    private val outputQueue = Channel<ShellLine>(capacity = Channel.UNLIMITED)

    private var nextLineId = 0L
    private var waitingSince: Long? = null

    init {
        viewModelScope.launch {
            launch {
                settings.readOnly
                    .catch { emit(false) }
                    .collect { readOnly ->
                        _state.update { current ->
                            current.copy(
                                readOnly = readOnly,
                                phase = phaseFor(current, readOnly),
                            )
                        }
                    }
            }
            launch {
                settings.termHistory
                    .catch { emit(emptyList()) }
                    .collect { history ->
                        _state.update {
                            it.copy(history = history, historyIndex = null, historyDraft = "")
                        }
                    }
            }
            launch {
                session.lines.collect { line -> outputQueue.send(line) }
            }
            launch {
                val batch = ArrayList<ShellLine>(64)
                while (isActive) {
                    val first = outputQueue.receive()
                    batch.clear()
                    batch.add(first)
                    delay(OUTPUT_BATCH_MS)
                    while (true) {
                        val next = outputQueue.tryReceive().getOrNull() ?: break
                        batch.add(next)
                    }
                    appendOutputBatch(batch)
                }
            }
            launch {
                session.running.collect { running ->
                    if (!running) {
                        waitingSince = null
                    }
                    _state.update { current ->
                        current.copy(
                            running = running,
                            waitingTooLong = if (running) current.waitingTooLong else false,
                            phase = when {
                                current.readOnly -> TerminalPhase.READ_ONLY
                                running && current.lines.isEmpty() -> TerminalPhase.EMPTY
                                running -> TerminalPhase.CONTENT
                                current.phase == TerminalPhase.ERROR -> TerminalPhase.ERROR
                                current.lines.isEmpty() -> TerminalPhase.EMPTY
                                else -> TerminalPhase.CONTENT
                            },
                        )
                    }
                }
            }
            launch {
                metrics.stream()
                    .catch { }
                    .collect { snapshot ->
                        _metrics.value = snapshot
                    }
            }
            launch {
                while (isActive) {
                    delay(WAITING_TICK_MS)
                    updateWaiting()
                }
            }
            startSession()
        }
    }

    fun onInputChange(value: String) {
        _state.update {
            it.copy(input = value, historyIndex = null, historyDraft = "")
        }
    }

    fun prefill(command: String) {
        _state.update {
            it.copy(input = command, historyIndex = null, historyDraft = "")
        }
    }

    fun send() {
        val current = state.value
        if (current.readOnly) {
            appendLocal(context.getString(R.string.sys_tune_term_readonly_blocked), TerminalLineKind.SYSTEM)
            return
        }
        val command = current.input.trim()
        if (command.isEmpty()) return
        if (!current.running) {
            appendLocal(context.getString(R.string.sys_tune_term_not_running), TerminalLineKind.ERROR)
            return
        }
        appendLocal(command, TerminalLineKind.COMMAND)
        waitingSince = System.currentTimeMillis()
        _state.update {
            it.copy(input = "", historyIndex = null, historyDraft = "", waitingTooLong = false)
        }
        viewModelScope.launch {
            session.send(command)
            try {
                settings.appendTermHistory(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
        }
    }

    fun interrupt() {
        if (!state.value.running) return
        session.interrupt()
        waitingSince = null
        _state.update { it.copy(waitingTooLong = false) }
        appendLocal(context.getString(R.string.sys_tune_term_interrupt_sent), TerminalLineKind.SUCCESS)
    }

    fun clearInput() {
        _state.update { it.copy(input = "", historyIndex = null, historyDraft = "") }
    }

    fun clearOutput() {
        waitingSince = null
        _state.update {
            it.copy(
                lines = emptyList(),
                truncated = false,
                waitingTooLong = false,
                phase = if (it.readOnly) TerminalPhase.READ_ONLY else TerminalPhase.EMPTY,
            )
        }
    }

    fun deleteLine(lineId: Long) {
        _state.update { current ->
            val remaining = current.lines.filterNot { it.id == lineId }
            current.copy(
                lines = remaining,
                phase = when {
                    current.readOnly -> TerminalPhase.READ_ONLY
                    remaining.isEmpty() -> TerminalPhase.EMPTY
                    else -> TerminalPhase.CONTENT
                },
            )
        }
    }

    fun historyUp() {
        val current = state.value
        if (current.history.isEmpty()) return
        val index = current.historyIndex?.let { (it - 1).coerceAtLeast(0) }
            ?: current.history.lastIndex
        val draft = if (current.historyIndex == null) current.input else current.historyDraft
        _state.update {
            it.copy(
                input = current.history[index],
                historyIndex = index,
                historyDraft = draft,
            )
        }
    }

    fun historyDown() {
        val current = state.value
        val index = current.historyIndex ?: return
        val next = index + 1
        if (next > current.history.lastIndex) {
            _state.update {
                it.copy(input = it.historyDraft, historyIndex = null, historyDraft = "")
            }
        } else {
            _state.update {
                it.copy(input = it.history[next], historyIndex = next)
            }
        }
    }

    fun retrySession() {
        viewModelScope.launch {
            if (state.value.running) {
                session.close()
                delay(RECONNECT_DELAY_MS)
            }
            startSession()
        }
    }

    private suspend fun startSession() {
        _state.update {
            it.copy(
                phase = TerminalPhase.LOADING,
                errorCode = null,
                errorMessage = null,
            )
        }
        try {
            session.start()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (throwable: Throwable) {
            _state.update {
                it.copy(
                    phase = TerminalPhase.ERROR,
                    errorCode = ErrorCode.ERR_CMD_FAILED,
                    errorMessage = throwable.message ?: throwable.javaClass.simpleName,
                )
            }
            return
        }
        _state.update { current ->
            when {
                current.readOnly -> current.copy(phase = TerminalPhase.READ_ONLY)
                session.running.value -> current.copy(
                    phase = if (current.lines.isEmpty()) {
                        TerminalPhase.EMPTY
                    } else {
                        TerminalPhase.CONTENT
                    },
                )

                else -> current.copy(
                    phase = TerminalPhase.ERROR,
                    errorCode = ErrorCode.ERR_CMD_FAILED,
                    errorMessage = current.lines.lastOrNull { it.kind == TerminalLineKind.ERROR }?.text
                        ?: context.getString(R.string.sys_tune_term_start_failed),
                )
            }
        }
    }

    
    private fun appendOutputBatch(batch: List<ShellLine>) {
        if (batch.isEmpty()) return
        waitingSince = null
        val converted = ArrayList<TerminalLineUi>(batch.size)
        batch.forEach { line ->
            val cleaned = TerminalText.clean(line.text)
            converted.add(
                TerminalLineUi(
                    id = nextLineId++,
                    text = cleaned,
                    kind = kindOf(cleaned, line.isErr),
                ),
            )
        }
        _state.update { current ->
            val merged = ArrayList<TerminalLineUi>(current.lines.size + converted.size)
            merged.addAll(current.lines)
            merged.addAll(converted)
            val overflow = merged.size > MAX_LINES
            val lines = if (overflow) {
                ArrayList<TerminalLineUi>(MAX_LINES).apply {
                    addAll(merged.subList(merged.size - MAX_LINES, merged.size))
                }
            } else {
                merged
            }
            current.copy(
                lines = lines,
                truncated = current.truncated || overflow,
                phase = when {
                    current.readOnly -> TerminalPhase.READ_ONLY
                    current.phase == TerminalPhase.ERROR && !current.running -> TerminalPhase.ERROR
                    else -> TerminalPhase.CONTENT
                },
            )
        }
    }

    private fun appendLocal(
        text: String,
        kind: TerminalLineKind,
        clearWaiting: Boolean = false,
    ) {
        if (clearWaiting) {
            waitingSince = null
        }
        val line = TerminalLineUi(
            id = nextLineId++,
            text = text,
            kind = kind,
        )
        _state.update { current ->
            val merged = ArrayList<TerminalLineUi>(current.lines.size + 1)
            merged.addAll(current.lines)
            merged.add(line)
            val overflow = merged.size > MAX_LINES
            val lines = if (overflow) {
                ArrayList<TerminalLineUi>(MAX_LINES).apply {
                    addAll(merged.subList(merged.size - MAX_LINES, merged.size))
                }
            } else {
                merged
            }
            current.copy(
                lines = lines,
                truncated = current.truncated || overflow,
                waitingTooLong = if (clearWaiting) false else current.waitingTooLong,
                phase = when {
                    current.readOnly -> TerminalPhase.READ_ONLY
                    current.phase == TerminalPhase.ERROR && !current.running -> TerminalPhase.ERROR
                    else -> TerminalPhase.CONTENT
                },
            )
        }
    }

    private fun kindOf(cleaned: String, isErr: Boolean): TerminalLineKind = when {
        isErr -> TerminalLineKind.ERROR
        cleaned.startsWith("[会话") -> TerminalLineKind.SYSTEM
        cleaned.startsWith("— Root 终端已连接") ||
            cleaned.startsWith("— 未获得 Root") -> TerminalLineKind.SUCCESS
        else -> TerminalLineKind.OUTPUT
    }

    private fun updateWaiting() {
        val since = waitingSince ?: return
        val current = state.value
        val shouldWait = current.running && System.currentTimeMillis() - since >= WAITING_TIMEOUT_MS
        if (shouldWait != current.waitingTooLong) {
            _state.update { it.copy(waitingTooLong = shouldWait) }
        }
    }

    private fun phaseFor(current: TerminalUiState, readOnly: Boolean): TerminalPhase = when {
        readOnly -> TerminalPhase.READ_ONLY
        current.phase == TerminalPhase.LOADING -> TerminalPhase.LOADING
        current.phase == TerminalPhase.ERROR -> TerminalPhase.ERROR
        current.lines.isEmpty() -> TerminalPhase.EMPTY
        else -> TerminalPhase.CONTENT
    }

    override fun onCleared() {
        session.close()
        outputQueue.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_LINES = 1000
        const val OUTPUT_BATCH_MS = 24L
        const val WAITING_TIMEOUT_MS = 8_000L
        const val WAITING_TICK_MS = 1_000L
        const val RECONNECT_DELAY_MS = 300L
    }
}