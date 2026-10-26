package com.xg.toolbox.feature.system.ui.perf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.feature.system.domain.perf.PerfControl
import com.xg.toolbox.feature.system.domain.perf.PerfController
import com.xg.toolbox.feature.system.domain.perf.PerfState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PerfPhase {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR,
    READ_ONLY,
}

enum class PerfNoticeKind {
    APPLIED,
    RESTORED,
    UPDATED,
    FAILED,
    BLOCKED,
    READBACK_FAILED,
}

data class PerfNotice(
    val kind: PerfNoticeKind,
    val detail: String? = null,
)

sealed interface PerfConfirm {
    data class Apply(
        val controlId: String,
        val choiceId: String,
        val controlName: String,
        val fromLabel: String,
        val toLabel: String,
    ) : PerfConfirm

    data object Restore : PerfConfirm
}

data class PerfUiState(
    val phase: PerfPhase = PerfPhase.LOADING,
    val data: PerfState? = null,
    val readOnly: Boolean = false,
    val busy: Boolean = false,
    val pendingChoices: Map<String, String> = emptyMap(),
    val confirm: PerfConfirm? = null,
    val notice: PerfNotice? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class PerfViewModel @Inject constructor(
    private val controller: PerfController,
    settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(PerfUiState())
    val state: StateFlow<PerfUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var writeJob: Job? = null

    init {
        viewModelScope.launch {
            settings.readOnly
                .catch { emit(false) }
                .collect { readOnly ->
                    _state.update { current ->
                        val data = current.data
                        val phase = when {
                            current.phase == PerfPhase.ERROR -> PerfPhase.ERROR
                            data == null -> current.phase
                            readOnly -> PerfPhase.READ_ONLY
                            data.controls.isEmpty() -> PerfPhase.EMPTY
                            else -> PerfPhase.CONTENT
                        }
                        current.copy(readOnly = readOnly, phase = phase)
                    }
                }
        }
        load(forceRefresh = false)
    }

    fun refresh() = load(forceRefresh = true)

    private fun load(forceRefresh: Boolean) {
        if (state.value.busy) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(phase = PerfPhase.LOADING, errorCode = null, errorMessage = null) }
            when (val result = call { controller.readState(forceRefresh = forceRefresh) }) {
                is XgResult.Ok -> _state.update { current ->
                    val data = result.data
                    current.copy(
                        phase = phaseFor(current.readOnly, data),
                        data = data,
                        pendingChoices = emptyMap(),
                        errorCode = null,
                        errorMessage = null,
                    )
                }

                is XgResult.Err -> _state.update {
                    it.copy(
                        phase = PerfPhase.ERROR,
                        errorCode = result.code,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    fun selectChoice(controlId: String, choiceId: String) {
        val current = state.value
        if (current.readOnly || current.busy) return
        val control = current.data?.controls?.firstOrNull { it.id == controlId } ?: return
        if (!control.available) return
        if (control.choices.none { it.id == choiceId }) return
        _state.update {
            it.copy(
                pendingChoices = it.pendingChoices + (controlId to choiceId),
                notice = null,
            )
        }
    }

    fun requestApply(controlId: String) {
        val current = state.value
        if (current.readOnly || current.busy) return
        val control = current.data?.controls?.firstOrNull { it.id == controlId } ?: return
        if (!control.available) return
        val choiceId = current.pendingChoices[controlId] ?: return
        val existingChoiceId = currentChoiceId(control)
        if (choiceId == existingChoiceId) return
        val target = control.choices.firstOrNull { it.id == choiceId } ?: return
        val fromLabel = existingChoiceId
            ?.let { id -> control.choices.firstOrNull { it.id == id }?.label }
            ?: control.current
        _state.update {
            it.copy(
                confirm = PerfConfirm.Apply(
                    controlId = controlId,
                    choiceId = choiceId,
                    controlName = control.name,
                    fromLabel = fromLabel,
                    toLabel = target.label,
                ),
            )
        }
    }

    
    fun requestApplyChoice(controlId: String, choiceId: String) {
        val current = state.value
        if (current.readOnly || current.busy) return
        val control = current.data?.controls?.firstOrNull { it.id == controlId } ?: return
        if (!control.available) return
        if (control.choices.none { it.id == choiceId }) return
        val existingChoiceId = currentChoiceId(control)
        if (choiceId == existingChoiceId) return
        val target = control.choices.firstOrNull { it.id == choiceId } ?: return
        val fromLabel = existingChoiceId
            ?.let { id -> control.choices.firstOrNull { it.id == id }?.label }
            ?: control.current
        _state.update {
            it.copy(
                confirm = PerfConfirm.Apply(
                    controlId = controlId,
                    choiceId = choiceId,
                    controlName = control.name,
                    fromLabel = fromLabel,
                    toLabel = target.label,
                ),
            )
        }
    }

    fun requestRestoreDefaults() {
        val current = state.value
        if (current.readOnly || current.busy) return
        _state.update { it.copy(confirm = PerfConfirm.Restore) }
    }

    fun confirm() {
        val confirm = state.value.confirm ?: return
        _state.update { it.copy(confirm = null) }
        when (confirm) {
            is PerfConfirm.Apply -> runWrite(PerfNoticeKind.APPLIED) {
                controller.apply(confirm.controlId, confirm.choiceId)
            }

            PerfConfirm.Restore -> runWrite(PerfNoticeKind.RESTORED) {
                controller.restoreDefaults()
            }
        }
    }

    fun dismissConfirm() {
        _state.update { it.copy(confirm = null) }
    }

    fun setMemoryCompression(enabled: Boolean) {
        runWrite(PerfNoticeKind.UPDATED) { controller.setMemoryCompression(enabled) }
    }

    fun setThermalGuard(enabled: Boolean) {
        runWrite(PerfNoticeKind.UPDATED) { controller.setThermalGuard(enabled) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun runWrite(
        successKind: PerfNoticeKind,
        block: suspend () -> XgResult<Unit>,
    ) {
        val current = state.value
        if (current.readOnly) {
            _state.update { it.copy(notice = PerfNotice(PerfNoticeKind.BLOCKED)) }
            return
        }
        if (current.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        writeJob = viewModelScope.launch {
            when (val result = call(block)) {
                is XgResult.Ok -> reloadAfterWrite(successKind)
                is XgResult.Err -> _state.update {
                    it.copy(busy = false, notice = PerfNotice(PerfNoticeKind.FAILED, result.message))
                }
            }
        }
    }

    private suspend fun reloadAfterWrite(successKind: PerfNoticeKind) {
        when (val result = call { controller.readState(forceRefresh = true) }) {
            is XgResult.Ok -> _state.update { current ->
                current.copy(
                    phase = phaseFor(current.readOnly, result.data),
                    data = result.data,
                    busy = false,
                    pendingChoices = emptyMap(),
                    notice = PerfNotice(successKind),
                    errorCode = null,
                    errorMessage = null,
                )
            }

            is XgResult.Err -> _state.update {
                it.copy(
                    busy = false,
                    notice = PerfNotice(PerfNoticeKind.READBACK_FAILED, result.message),
                )
            }
        }
    }

    private fun phaseFor(readOnly: Boolean, data: PerfState): PerfPhase = when {
        readOnly -> PerfPhase.READ_ONLY
        data.controls.isEmpty() -> PerfPhase.EMPTY
        else -> PerfPhase.CONTENT
    }

    
    private fun currentChoiceId(control: PerfControl): String? =
        control.choices.firstOrNull { it.label == control.current }?.id

    private suspend fun <T> call(block: suspend () -> XgResult<T>): XgResult<T> = try {
        block()
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
        writeJob?.cancel()
        super.onCleared()
    }
}