package com.xg.toolbox.feature.system.ui.flash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.flash.FlashBackup
import com.xg.toolbox.feature.system.domain.flash.FlashController
import com.xg.toolbox.feature.system.domain.flash.FlashEnvironment
import com.xg.toolbox.feature.system.domain.flash.FlashImageInfo
import com.xg.toolbox.feature.system.domain.flash.FlashProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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

enum class FlashPhase {
    LOADING,
    CONTENT,
    EMPTY,
    ERROR,
    READ_ONLY,
}

enum class FlashNoticeKind {
    IMAGE_READY,
    BACKUP_READY,
    SLOT_SWITCHED,
    SLOT_UNSUPPORTED,
    FLASH_DONE,
    FLASH_FAILED,
    ROLLBACK,
    FLASH_ENDED,
    ABORT_REQUESTED,
    BLOCKED,
    BUSY,
    FAILED,
    MISSING_IMAGE,
    NO_PARTITION,
    IMAGE_TOO_LARGE,
}

data class FlashNotice(
    val kind: FlashNoticeKind,
    val detail: String? = null,
)

data class FlashRiskConfirm(
    val partition: String,
    val risk: FlashPartitionRisk,
)

data class FlashLogLine(
    val id: Long,
    val text: String,
)

data class FlashUiState(
    val phase: FlashPhase = FlashPhase.LOADING,
    val environment: FlashEnvironment? = null,
    val readOnly: Boolean = false,
    val busy: Boolean = false,
    val inspecting: Boolean = false,
    val image: FlashImageInfo? = null,
    val targetPartition: String? = null,
    val backup: FlashBackup? = null,
    val partitionUis: List<FlashPartitionUi> = emptyList(),
    val detectedTotal: Int = 0,
    val partitionQuery: String = "",
    val riskFilter: FlashPartitionRisk? = null,
    val slotFilter: FlashSlotFilter = FlashSlotFilter.ALL,
    val writableOnly: Boolean = false,
    val skipBackup: Boolean = false,
    val riskConfirm: FlashRiskConfirm? = null,
    val progress: FlashProgress? = null,
    val logs: List<FlashLogLine> = emptyList(),
    val confirmFlash: Boolean = false,
    val confirmSlot: Boolean = false,
    val awaitingRestart: Boolean = false,
    val notice: FlashNotice? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
)

@HiltViewModel
class FlashViewModel @Inject constructor(
    private val controller: FlashController,
    settings: SettingsStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(FlashUiState())
    val state: StateFlow<FlashUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var flashJob: Job? = null
    private var inspectJob: Job? = null
    private var backupJob: Job? = null
    private var slotJob: Job? = null
    private var nextLogId = 0L

    init {
        viewModelScope.launch {
            settings.readOnly
                .catch { emit(false) }
                .collect { readOnly ->
                    _state.update { current ->
                        val environment = current.environment
                        current.copy(
                            readOnly = readOnly,
                            phase = if (environment == null) {
                                current.phase
                            } else {
                                phaseFor(readOnly, environment)
                            },
                        )
                    }
                }
        }
        loadJob = viewModelScope.launch {
            loadEnvironment(showLoading = true, forceRefresh = false)
        }
    }

    fun refreshEnvironment() {
        if (state.value.busy || isControllerBusy()) {
            val busy = isControllerBusy()
            _state.update {
                val hasEnvironment = it.environment != null
                it.copy(
                    busy = false,
                    phase = if (busy && !hasEnvironment) FlashPhase.ERROR else it.phase,
                    errorCode = if (busy && !hasEnvironment) ErrorCode.ERR_CMD_FAILED else it.errorCode,
                    errorMessage = if (busy && !hasEnvironment) {
                        context.getString(R.string.sys_tune_flash_busy)
                    } else {
                        it.errorMessage
                    },
                    notice = FlashNotice(FlashNoticeKind.BUSY),
                )
            }
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            loadEnvironment(showLoading = true, forceRefresh = true)
        }
    }

    fun selectPartition(partitionName: String) {
        val current = state.value
        if (current.readOnly || current.busy) return
        val environment = current.environment ?: return
        val partition = environment.availableParts.firstOrNull { it.name == partitionName } ?: return
        if (!partition.writable) return
        _state.update {
            it.copy(
                targetPartition = partitionName,
                backup = it.backup?.takeIf { backup -> backup.partition == partitionName },
                skipBackup = false,
                notice = null,
            )
        }
    }

    fun setPartitionQuery(query: String) {
        _state.update { it.copy(partitionQuery = query) }
    }

    fun setRiskFilter(risk: FlashPartitionRisk?) {
        _state.update { it.copy(riskFilter = risk) }
    }

    fun setSlotFilter(slot: FlashSlotFilter) {
        _state.update { it.copy(slotFilter = slot) }
    }

    fun setWritableOnly(writableOnly: Boolean) {
        _state.update { it.copy(writableOnly = writableOnly) }
    }

    
    fun setSkipBackup(skipBackup: Boolean) {
        val current = state.value
        val target = current.targetPartition ?: return
        val partition = current.partitionUis.firstOrNull { it.name == target } ?: return
        if (skipBackup && !partition.canSkipBackup()) return
        _state.update { it.copy(skipBackup = skipBackup) }
    }

    fun inspectImage(uri: String?) {
        if (uri.isNullOrBlank()) return
        val current = state.value
        if (current.busy || current.inspecting) return
        inspectJob?.cancel()
        _state.update { it.copy(inspecting = true, notice = null) }
        inspectJob = viewModelScope.launch {
            when (val result = call { controller.inspectImage(uri) }) {
                is XgResult.Ok -> _state.update {
                    it.copy(
                        image = result.data,
                        inspecting = false,
                        notice = FlashNotice(FlashNoticeKind.IMAGE_READY),
                    )
                }

                is XgResult.Err -> _state.update {
                    it.copy(
                        inspecting = false,
                        image = null,
                        notice = FlashNotice(FlashNoticeKind.FAILED, result.message),
                    )
                }
            }
        }
    }

    fun backupPartition() {
        val current = state.value
        if (current.readOnly) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.BLOCKED)) }
            return
        }
        if (current.busy || isControllerBusy()) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.BUSY)) }
            return
        }
        val target = current.targetPartition ?: run {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.NO_PARTITION)) }
            return
        }
        _state.update { it.copy(busy = true, notice = null) }
        backupJob = viewModelScope.launch {
            when (val result = call { controller.backup(target) }) {
                is XgResult.Ok -> _state.update {
                    it.copy(
                        busy = false,
                        backup = result.data,
                        notice = FlashNotice(FlashNoticeKind.BACKUP_READY),
                    )
                }

                is XgResult.Err -> _state.update {
                    it.copy(
                        busy = false,
                        notice = FlashNotice(FlashNoticeKind.FAILED, result.message),
                    )
                }
            }
        }
    }

    fun requestFlash() {
        val current = state.value
        if (current.readOnly) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.BLOCKED)) }
            return
        }
        if (current.busy || isControllerBusy()) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.BUSY)) }
            return
        }
        if (current.inspecting) return
        if (current.image == null) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.MISSING_IMAGE)) }
            return
        }
        val target = current.targetPartition ?: run {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.NO_PARTITION)) }
            return
        }
        val partition = current.partitionUis.firstOrNull { it.name == target }
        if (partition == null || !partition.writable) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.NO_PARTITION)) }
            return
        }
        val skipBackup = current.skipBackup && partition.canSkipBackup()
        val image = current.image
        if (image != null && image.sizeBytes > partition.sizeBytes) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.IMAGE_TOO_LARGE)) }
            return
        }
        if (partition.risk.canWriteWithExtraConfirm()) {
            _state.update {
                it.copy(
                    skipBackup = skipBackup,
                    riskConfirm = FlashRiskConfirm(partition = target, risk = partition.risk),
                )
            }
            return
        }
        _state.update { it.copy(skipBackup = skipBackup, confirmFlash = true) }
    }

    
    fun confirmRisk() {
        val pending = state.value.riskConfirm ?: return
        if (state.value.partitionUis.firstOrNull { it.name == pending.partition } == null) {
            _state.update { it.copy(riskConfirm = null) }
            return
        }
        _state.update { it.copy(riskConfirm = null, confirmFlash = true) }
    }

    fun cancelRisk() {
        _state.update { it.copy(riskConfirm = null) }
    }

    fun confirmFlash() {
        val current = state.value
        _state.update { it.copy(confirmFlash = false) }
        if (current.readOnly) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.BLOCKED)) }
            return
        }
        val image = current.image ?: return
        val target = current.targetPartition ?: return
        if (isControllerBusy()) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.BUSY)) }
            return
        }
        _state.update {
            it.copy(
                busy = true,
                progress = null,
                logs = emptyList(),
                skipBackup = false,
                notice = null,
                errorCode = null,
                errorMessage = null,
                awaitingRestart = false,
            )
        }
        val targetPartition = current.partitionUis.firstOrNull { partition -> partition.name == target }
        val force = targetPartition?.risk == FlashPartitionRisk.DANGEROUS
        val skipBackup = current.skipBackup && targetPartition?.canSkipBackup() == true
        flashJob = viewModelScope.launch {
            try {
                var lastStage: String? = null
                var lastPercent = -1
                var lastUiAt = 0L
                controller.flash(target, image, force = force, skipBackup = skipBackup).collect { progress ->
                    val percent = progress.percent.coerceIn(0f, 100f).roundToInt()
                    val stageChanged = progress.stage != lastStage
                    val now = System.currentTimeMillis()
                    if (!stageChanged && (percent == lastPercent || now - lastUiAt < PROGRESS_THROTTLE_MS)) {
                        return@collect
                    }
                    lastStage = progress.stage
                    lastPercent = percent
                    lastUiAt = now
                    val logLine = FlashLogLine(
                        id = nextLogId++,
                        text = "${progress.stage} · $percent% · ${progress.message}",
                    )
                    _state.update { state ->
                        state.copy(
                            progress = progress,
                            logs = (state.logs + logLine).takeLast(MAX_FLASH_LOG_LINES),
                            busy = progress.stage != STAGE_DONE && progress.stage != STAGE_FAILED,
                        )
                    }
                }
                val finalProgress = _state.value.progress
                val noticeKind = when (finalProgress?.stage) {
                    STAGE_DONE -> FlashNoticeKind.FLASH_DONE
                    STAGE_FAILED -> FlashNoticeKind.FLASH_FAILED
                    STAGE_ROLLBACK -> FlashNoticeKind.ROLLBACK
                    else -> FlashNoticeKind.FLASH_ENDED
                }
                _state.update {
                    it.copy(
                        busy = false,
                        notice = FlashNotice(
                            kind = noticeKind,
                            detail = if (noticeKind == FlashNoticeKind.FLASH_FAILED ||
                                noticeKind == FlashNoticeKind.ROLLBACK
                            ) {
                                finalProgress?.message
                            } else {
                                null
                            },
                        ),
                        awaitingRestart = noticeKind == FlashNoticeKind.FLASH_DONE,
                    )
                }
                loadEnvironment(showLoading = false, forceRefresh = true)
            } catch (cancelled: CancellationException) {
                _state.update {
                    it.copy(
                        notice = FlashNotice(FlashNoticeKind.ABORT_REQUESTED),
                    )
                }
                throw cancelled
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        busy = false,
                        notice = FlashNotice(
                            kind = FlashNoticeKind.FAILED,
                            detail = throwable.message ?: throwable.javaClass.simpleName,
                        ),
                    )
                }
            } finally {
                flashJob = null
                _state.update { it.copy(busy = isControllerBusy()) }
            }
        }
    }

    fun cancelFlashConfirm() {
        _state.update { it.copy(confirmFlash = false) }
    }

    fun abortFlash() {
        if (flashJob?.isActive != true) return
        if (state.value.progress?.stage == FlashProgress.STAGE_ROLLBACK) return
        _state.update {
            it.copy(notice = FlashNotice(FlashNoticeKind.ABORT_REQUESTED))
        }
        flashJob?.cancel()
    }

    fun requestSlotSwitch() {
        val current = state.value
        val environment = current.environment
        if (environment == null || !environment.bootctl || environment.currentSlot == null) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.SLOT_UNSUPPORTED)) }
            return
        }
        if (current.readOnly) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.BLOCKED)) }
            return
        }
        if (current.busy || isControllerBusy()) {
            _state.update { it.copy(notice = FlashNotice(FlashNoticeKind.BUSY)) }
            return
        }
        _state.update { it.copy(confirmSlot = true) }
    }

    fun cancelSlotConfirm() {
        _state.update { it.copy(confirmSlot = false) }
    }

    fun confirmSlotSwitch() {
        if (state.value.readOnly) {
            _state.update {
                it.copy(
                    confirmSlot = false,
                    notice = FlashNotice(FlashNoticeKind.BLOCKED),
                )
            }
            return
        }
        if (isControllerBusy()) {
            _state.update { it.copy(confirmSlot = false, notice = FlashNotice(FlashNoticeKind.BUSY)) }
            return
        }
        _state.update { it.copy(confirmSlot = false, busy = true, notice = null) }
        slotJob = viewModelScope.launch {
            when (val result = call { controller.switchSlot() }) {
                is XgResult.Ok -> {
                    _state.update {
                        it.copy(
                            busy = false,
                            notice = FlashNotice(FlashNoticeKind.SLOT_SWITCHED, result.data),
                        )
                    }
                    loadEnvironment(showLoading = false, forceRefresh = true)
                }

                is XgResult.Err -> _state.update {
                    it.copy(
                        busy = false,
                        notice = FlashNotice(FlashNoticeKind.FAILED, result.message),
                    )
                }
            }
        }
    }

    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    private suspend fun loadEnvironment(showLoading: Boolean, forceRefresh: Boolean) {
        if (showLoading) {
            _state.update {
                it.copy(
                    phase = FlashPhase.LOADING,
                    errorCode = null,
                    errorMessage = null,
                )
            }
        }
        when (val result = call { controller.environment(forceRefresh = forceRefresh) }) {
            is XgResult.Ok -> _state.update { current ->
                val environment = result.data
                val partitionUis = environment.partitionUis()
                val target = current.targetPartition
                    ?.takeIf { name ->
                        partitionUis.any { it.name == name && it.writable }
                    }
                    ?: defaultPartition(partitionUis)
                val targetPartitionUi = partitionUis.firstOrNull { it.name == target }
                current.copy(
                    phase = phaseFor(current.readOnly, environment),
                    environment = environment,
                    partitionUis = partitionUis,
                    detectedTotal = environment.byNameTotal,
                    targetPartition = target,
                    backup = current.backup?.takeIf { it.partition == target },
                    skipBackup = current.skipBackup && targetPartitionUi?.canSkipBackup() == true,
                    busy = isControllerBusy(),
                    errorCode = null,
                    errorMessage = null,
                )
            }

            is XgResult.Err -> _state.update {
                if (showLoading) {
                    it.copy(
                        phase = FlashPhase.ERROR,
                        errorCode = result.code,
                        errorMessage = result.message,
                    )
                } else {
                    it.copy(
                        notice = it.notice ?: FlashNotice(FlashNoticeKind.FAILED, result.message),
                    )
                }
            }
        }
    }

    private fun defaultPartition(partitions: List<FlashPartitionUi>): String? {
        val writable = partitions.filter { it.writable }
        val currentSlotSafeBoot = writable.firstOrNull {
            it.base == BOOT_PARTITION && it.isCurrentSlot
        }
        val currentSlotSafe = writable.firstOrNull {
            it.isCurrentSlot && it.risk == FlashPartitionRisk.SAFE
        }
        val safeBoot = writable.firstOrNull { it.base == BOOT_PARTITION && it.risk == FlashPartitionRisk.SAFE }
        val safe = writable.firstOrNull { it.risk == FlashPartitionRisk.SAFE }
        return (currentSlotSafeBoot ?: currentSlotSafe ?: safeBoot ?: safe ?: writable.firstOrNull())?.name
    }

    private fun phaseFor(readOnly: Boolean, environment: FlashEnvironment): FlashPhase = when {
        readOnly -> FlashPhase.READ_ONLY
        environment.availableParts.isEmpty() -> FlashPhase.EMPTY
        else -> FlashPhase.CONTENT
    }

    private fun isControllerBusy(): Boolean =
        runCatching { controller.isBusy() }.getOrDefault(false)

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
        inspectJob?.cancel()
        backupJob?.cancel()
        slotJob?.cancel()
        flashJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAX_FLASH_LOG_LINES = 400
        const val PROGRESS_THROTTLE_MS = 120L
        const val BOOT_PARTITION = "boot"
        const val STAGE_DONE = "done"
        const val STAGE_FAILED = "failed"
        const val STAGE_ROLLBACK = "rollback"
    }
}
