package com.xg.toolbox.feature.system.ui.display

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.feature.system.domain.display.DisplayController
import com.xg.toolbox.feature.system.domain.display.DisplayState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal const val COLOR_TEMP_MIN_K = 4000
internal const val COLOR_TEMP_MAX_K = 8000
internal const val COLOR_TEMP_DEFAULT_K = 6500

enum class DisplayPhase {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR,
    READ_ONLY,
}

enum class DisplayOp {
    SIZE,
    SIZE_RESET,
    DENSITY,
    DENSITY_RESET,
    REFRESH,
    TOUCH,
    DC,
    COLOR,
    NIGHT,
    RESTORE,
}

enum class DisplayNoticeKind {
    APPLIED,
    READBACK_FAILED,
    FAILED,
    BLOCKED,
}

data class DisplayNotice(
    val kind: DisplayNoticeKind,
    val detail: String? = null,
)

data class DisplayUiState(
    val phase: DisplayPhase = DisplayPhase.LOADING,
    val data: DisplayState? = null,
    val readOnly: Boolean = false,
    val busy: Boolean = false,
    val selectedHz: Float? = null,
    val pendingSize: String? = null,
    val densityDraft: Int? = null,
    val colorTempDraft: Int? = null,
    val lastOperation: DisplayOp? = null,
    val notice: DisplayNotice? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class DisplayViewModel @Inject constructor(
    private val controller: DisplayController,
    settings: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(DisplayUiState())
    val state: StateFlow<DisplayUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var writeJob: Job? = null

    init {
        viewModelScope.launch {
            settings.readOnly
                .catch { emit(false) }
                .collect { readOnly ->
                    _state.update { current ->
                        val data = current.data
                        current.copy(
                            readOnly = readOnly,
                            phase = if (data == null) {
                                current.phase
                            } else {
                                phaseFor(readOnly, data)
                            },
                        )
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
            _state.update { it.copy(phase = DisplayPhase.LOADING, errorCode = null, errorMessage = null) }
            when (val result = call { controller.readState(forceRefresh = forceRefresh) }) {
                is XgResult.Ok -> _state.update { current ->
                    val data = result.data
                    current.copy(
                        phase = phaseFor(current.readOnly, data),
                        data = data,
                        selectedHz = normalizeHz(data, previous = null),
                        pendingSize = null,
                        densityDraft = data.density,
                        colorTempDraft = data.colorTemperature ?: COLOR_TEMP_DEFAULT_K,
                        errorCode = null,
                        errorMessage = null,
                    )
                }

                is XgResult.Err -> _state.update {
                    it.copy(
                        phase = DisplayPhase.ERROR,
                        errorCode = result.code,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    fun selectHz(hz: Float) {
        val current = state.value
        if (current.readOnly || current.busy) return
        val data = current.data ?: return
        val rounded = hz.roundToInt()
        if (data.refreshOptions.none { it == rounded }) return
        _state.update { it.copy(selectedHz = rounded.toFloat(), notice = null) }
    }

    fun selectSize(size: String) {
        if (state.value.readOnly || state.value.busy) return
        if (parseResolution(size) == null) return
        _state.update { it.copy(pendingSize = size, notice = null) }
    }

    fun updateDensity(value: Int) {
        val current = state.value
        if (current.readOnly || current.busy) return
        val range = current.data?.densityRange ?: return
        if (range.isEmpty()) return
        _state.update {
            it.copy(
                densityDraft = value.coerceIn(range.first, range.last),
                notice = null,
            )
        }
    }

    fun updateColorTemperature(value: Int) {
        val current = state.value
        if (current.readOnly || current.busy) return
        if (current.data?.colorTemperatureSupported != true) return
        _state.update {
            it.copy(
                colorTempDraft = value.coerceIn(COLOR_TEMP_MIN_K, COLOR_TEMP_MAX_K),
                notice = null,
            )
        }
    }

    fun applySize() {
        val size = state.value.pendingSize ?: return
        val (width, height) = parseResolution(size) ?: return
        runWrite(DisplayOp.SIZE) { controller.setSize(width, height) }
    }

    fun resetSize() {
        runWrite(DisplayOp.SIZE_RESET) { controller.resetSize() }
    }

    fun applyDensity() {
        val current = state.value
        val data = current.data ?: return
        val dpi = current.densityDraft ?: data.density
        val range = data.densityRange
        if (!range.isEmpty() && dpi !in range) return
        runWrite(DisplayOp.DENSITY) { controller.setDensity(dpi) }
    }

    fun resetDensity() {
        runWrite(DisplayOp.DENSITY_RESET) { controller.resetDensity() }
    }

    fun applyRefresh() {
        val current = state.value
        val data = current.data ?: return
        val hz = current.selectedHz ?: return
        runWrite(DisplayOp.REFRESH) { controller.setRefreshHz(hz, data.forceHighRefresh) }
    }

    fun toggleForceHighRefresh(enabled: Boolean) {
        val current = state.value
        val hz = current.selectedHz ?: return
        runWrite(DisplayOp.REFRESH) { controller.setRefreshHz(hz, enabled) }
    }

    fun setTouchSampleHz(hz: Int?) {
        val data = state.value.data ?: return
        if (hz != null && data.touchSampleOptions.none { it == hz }) return
        runWrite(DisplayOp.TOUCH) { controller.setTouchSampleHz(hz) }
    }

    fun setDcDimming(enabled: Boolean) {
        if (state.value.data?.dcSupported != true) return
        runWrite(DisplayOp.DC) { controller.setDcDimming(enabled) }
    }

    fun applyColorTemperature() {
        val current = state.value
        if (current.data?.colorTemperatureSupported != true) return
        val kelvin = (current.colorTempDraft ?: COLOR_TEMP_DEFAULT_K)
            .coerceIn(COLOR_TEMP_MIN_K, COLOR_TEMP_MAX_K)
        runWrite(DisplayOp.COLOR) { controller.setColorTemperature(kelvin) }
    }

    fun setNightMode(mode: String) {
        if (mode !in NIGHT_MODES) return
        runWrite(DisplayOp.NIGHT) { controller.setNightMode(mode) }
    }

    fun restoreDefaults() {
        runWrite(DisplayOp.RESTORE) restore@{
            val sizeResult = controller.resetSize()
            if (sizeResult is XgResult.Err) {
                return@restore sizeResult
            }
            controller.resetDensity()
        }
    }

    fun applySize(size: String) {
        val current = state.value
        if (current.readOnly || current.busy || current.data == null) return
        val (width, height) = parseResolution(size) ?: return
        runWrite(DisplayOp.SIZE) { controller.setSize(width, height) }
    }

    fun applyDensity(dpi: Int) {
        val current = state.value
        val range = current.data?.densityRange ?: return
        if (current.readOnly || current.busy) return
        val value = if (range.isEmpty()) dpi else dpi.coerceIn(range.first, range.last)
        runWrite(DisplayOp.DENSITY) { controller.setDensity(value) }
    }

    fun applyColorTemperature(kelvin: Int) {
        val current = state.value
        if (current.readOnly || current.busy) return
        if (current.data?.colorTemperatureSupported != true) return
        val value = kelvin.coerceIn(COLOR_TEMP_MIN_K, COLOR_TEMP_MAX_K)
        runWrite(DisplayOp.COLOR) { controller.setColorTemperature(value) }
    }

    fun applyRefresh(hz: Float, force: Boolean) {
        val current = state.value
        val data = current.data ?: return
        if (current.readOnly || current.busy) return
        val rounded = hz.roundToInt()
        if (data.refreshOptions.none { it == rounded }) return
        runWrite(DisplayOp.REFRESH) { controller.setRefreshHz(rounded.toFloat(), force) }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private fun runWrite(
        operation: DisplayOp,
        block: suspend () -> XgResult<Unit>,
    ) {
        val current = state.value
        if (current.readOnly) {
            _state.update { it.copy(notice = DisplayNotice(DisplayNoticeKind.BLOCKED)) }
            return
        }
        if (current.busy) return
        _state.update { it.copy(busy = true, notice = null) }
        writeJob = viewModelScope.launch {
            when (val result = call(block)) {
                is XgResult.Ok -> reloadAfterWrite(operation)
                is XgResult.Err -> _state.update {
                    it.copy(
                        busy = false,
                        notice = DisplayNotice(DisplayNoticeKind.FAILED, result.message),
                        errorCode = result.code,
                        errorMessage = result.message,
                    )
                }
            }
        }
    }

    private suspend fun reloadAfterWrite(operation: DisplayOp) {
        when (val result = call { controller.readState(forceRefresh = true) }) {
            is XgResult.Ok -> _state.update { current ->
                val data = result.data
                current.copy(
                    phase = phaseFor(current.readOnly, data),
                    data = data,
                    selectedHz = normalizeHz(data, current.selectedHz),
                    pendingSize = null,
                    densityDraft = data.density,
                    colorTempDraft = data.colorTemperature ?: COLOR_TEMP_DEFAULT_K,
                    busy = false,
                    lastOperation = operation,
                    notice = DisplayNotice(DisplayNoticeKind.APPLIED),
                    errorCode = null,
                    errorMessage = null,
                )
            }

            is XgResult.Err -> _state.update {
                it.copy(
                    busy = false,
                    notice = DisplayNotice(DisplayNoticeKind.READBACK_FAILED, result.message),
                    errorCode = result.code,
                    errorMessage = result.message,
                )
            }
        }
    }

    private fun normalizeHz(data: DisplayState, previous: Float?): Float? {
        val options = data.refreshOptions.sorted()
        if (options.isEmpty()) return null
        val previousRounded = previous?.roundToInt()
        if (previousRounded != null && options.contains(previousRounded)) {
            return previousRounded.toFloat()
        }
        val peakRounded = data.currentPeakHz?.roundToInt()
        if (peakRounded != null && options.contains(peakRounded)) {
            return peakRounded.toFloat()
        }
        return options.last().toFloat()
    }

    private fun phaseFor(readOnly: Boolean, data: DisplayState): DisplayPhase = when {
        readOnly -> DisplayPhase.READ_ONLY
        isEmpty(data) -> DisplayPhase.EMPTY
        else -> DisplayPhase.CONTENT
    }

    private fun isEmpty(data: DisplayState): Boolean =
        data.size.isBlank() &&
            data.physicalSize.isBlank() &&
            data.refreshOptions.isEmpty()

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

    private companion object {
        val NIGHT_MODES = setOf("yes", "no", "auto")
    }
}

internal fun parseResolution(value: String): Pair<Int, Int>? {
    val parts = value.lowercase().trim().split('x')
    if (parts.size != 2) return null
    val width = parts[0].trim().toIntOrNull() ?: return null
    val height = parts[1].trim().toIntOrNull() ?: return null
    if (width <= 0 || height <= 0) return null
    return width to height
}