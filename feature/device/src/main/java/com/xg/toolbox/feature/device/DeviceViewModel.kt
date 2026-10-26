package com.xg.toolbox.feature.device

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.device.DeviceField
import com.xg.toolbox.core.device.DeviceGroup
import com.xg.toolbox.core.device.DeviceInfoRepository
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class DeviceViewModel @Inject constructor(
    private val deviceInfoRepository: DeviceInfoRepository,
    private val rootStatusRepository: RootStatusRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DeviceUiState())
    val uiState: StateFlow<DeviceUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DeviceEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<DeviceEvent> = _events.asSharedFlow()

    
    private var baseGroups: List<DeviceGroupUi> = emptyList()

    private var rootState: RootState = RootState.Unknown
    private var dataVersionCounter: Int = 0
    private var collectJob: Job? = null
    private var serialEditJob: Job? = null

    
    private var lastRootState: RootState = RootState.Unknown
    private var rootRecollectScheduled: Boolean = false

    init {
        lastRootState = rootStatusRepository.state.value
        observeRootState()
        load(forceRefresh = false)
    }

    
    fun refresh() = load(forceRefresh = true)

    private fun load(forceRefresh: Boolean) {
        if (collectJob?.isActive == true) return
        collectJob = viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    loading = current.groups.isEmpty(),
                    refreshing = current.groups.isNotEmpty(),
                    error = null,
                )
            }
            try {
                val groups = withContext(Dispatchers.IO) { deviceInfoRepository.collect(forceRefresh) }
                val mapped = groups.mapIndexed { index, group -> group.toUi(index + 1) }
                baseGroups = mapped
                dataVersionCounter += 1
                _uiState.update { current ->
                    current.copy(
                        loading = false,
                        refreshing = false,
                        groups = mapped,
                        summary = buildSummary(mapped, rootState),
                        totalFields = mapped.sumOf { it.fields.size },
                        error = null,
                        dataVersion = dataVersionCounter,
                    ).withSearch()
                }
            } catch (throwable: Throwable) {
                val message = throwable.message?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.device_load_failed)
                _uiState.update { current ->
                    current.copy(loading = false, refreshing = false, error = message)
                }
            }
        }
    }

    
    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query).withSearch() }
    }

    fun clearQuery() {
        _uiState.update { it.copy(query = "").withSearch() }
    }

    
    fun copyAllText(
        title: String,
        groupLabel: String,
        nameLabel: String,
        keyLabel: String,
        valueLabel: String,
    ): String = DeviceCopyFormatter.markdown(
        groups = _uiState.value.groups,
        title = title,
        groupLabel = groupLabel,
        nameLabel = nameLabel,
        keyLabel = keyLabel,
        valueLabel = valueLabel,
    )

    
    fun copyGroupText(groupId: String): String {
        val group = _uiState.value.groups.firstOrNull { it.id == groupId } ?: return ""
        return DeviceCopyFormatter.groupText(group)
    }

    
    fun openSerialEdit() {
        val knownSerial = serialFieldValue()
        _uiState.update { current ->
            current.copy(
                serialEdit = SerialEditUi(
                    visible = true,
                    currentSerial = knownSerial,
                    input = knownSerial.orEmpty(),
                    loading = true,
                ),
            )
        }
        serialEditJob?.cancel()
        serialEditJob = viewModelScope.launch {
            val capabilityResult = attempt { deviceInfoRepository.serialEditCapability() }
            val serialResult = attempt { deviceInfoRepository.serialNumber() }
            val capability = when (capabilityResult) {
                is XgResult.Ok -> capabilityResult.data
                else -> null
            }
            val currentSerial = when (serialResult) {
                is XgResult.Ok -> serialResult.data
                else -> null
            }?.takeIf { it.isNotBlank() } ?: knownSerial
            val capabilityError = when {
                capabilityResult == null ->
                    context.getString(R.string.device_serial_edit_capability_failed_generic)
                capabilityResult is XgResult.Err -> capabilityResult.message
                else -> null
            }
            _uiState.update { current ->
                val edit = current.serialEdit
                current.copy(
                    serialEdit = edit.copy(
                        loading = false,
                        currentSerial = currentSerial,
                        input = edit.input.ifBlank { currentSerial.orEmpty() },
                        supported = capability?.supported == true,
                        resetpropPath = capability?.resetpropPath,
                        capabilityReason = capability?.reason,
                        capabilityError = capabilityError,
                    ),
                )
            }
        }
    }

    
    fun dismissSerialEdit() {
        if (_uiState.value.serialEdit.submitting) return
        serialEditJob?.cancel()
        _uiState.update { current -> current.copy(serialEdit = SerialEditUi()) }
    }

    
    fun onSerialInputChange(value: String) {
        if (value.length > SERIAL_MAX_LENGTH * 2) return
        val error = if (value.isBlank()) null else validateSerial(value.trim())
        _uiState.update { current ->
            current.copy(
                serialEdit = current.serialEdit.copy(
                    input = value,
                    inputError = error,
                    submitError = null,
                ),
            )
        }
    }

    
    fun confirmSerialEdit() {
        val edit = _uiState.value.serialEdit
        if (edit.submitting || !edit.supported) return
        val value = edit.input.trim()
        val error = validateSerial(value)
        if (error != null) {
            _uiState.update { it.copy(serialEdit = it.serialEdit.copy(inputError = error)) }
            return
        }
        _uiState.update {
            it.copy(serialEdit = it.serialEdit.copy(submitting = true, inputError = null, submitError = null))
        }
        viewModelScope.launch {
            val result = attempt { deviceInfoRepository.setSerial(value) }
            when (result) {
                is XgResult.Ok -> {
                    val change = result.data
                    _uiState.update { it.copy(serialEdit = SerialEditUi()) }
                    _events.tryEmit(
                        DeviceEvent.SerialChanged(
                            before = change.before,
                            after = change.after,
                            requiresReboot = change.requiresReboot == true,
                        ),
                    )
                    refreshAfterSerialEdit()
                }

                is XgResult.Err -> _uiState.update {
                    it.copy(serialEdit = it.serialEdit.copy(submitting = false, submitError = result.message))
                }

                else -> _uiState.update {
                    it.copy(
                        serialEdit = it.serialEdit.copy(
                            submitting = false,
                            submitError = context.getString(R.string.device_serial_edit_failed_generic),
                        ),
                    )
                }
            }
        }
    }

    private fun serialFieldValue(): String? = _uiState.value.groups
        .flatMap { it.fields }
        .firstOrNull { it.isSerialNumberField }
        ?.value
        ?.takeIf { it.isNotBlank() && it != DASH }

    
    private fun refreshAfterSerialEdit() {
        val active = collectJob
        if (active?.isActive == true) {
            viewModelScope.launch {
                active.join()
                refresh()
            }
        } else {
            refresh()
        }
    }

    
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        null
    }

    private fun validateSerial(raw: String): String? = when {
        raw.isEmpty() -> context.getString(R.string.device_serial_edit_error_empty)
        raw.length < SERIAL_MIN_LENGTH || raw.length > SERIAL_MAX_LENGTH ->
            context.getString(R.string.device_serial_edit_error_invalid)
        !SERIAL_PATTERN.matches(raw) -> context.getString(R.string.device_serial_edit_error_invalid)
        raw == _uiState.value.serialEdit.currentSerial ->
            context.getString(R.string.device_serial_edit_error_same)
        else -> null
    }

    private fun observeRootState() {
        viewModelScope.launch {
            rootStatusRepository.state.collect { state ->
                val becameGranted = state is RootState.Granted && lastRootState !is RootState.Granted
                lastRootState = state
                rootState = state
                _uiState.update { current ->
                    current.copy(summary = current.summary?.copy(rooted = state is RootState.Granted))
                }
                if (becameGranted) scheduleRootRecollect()
            }
        }
    }

    
    private fun scheduleRootRecollect() {
        if (rootRecollectScheduled) return
        rootRecollectScheduled = true
        viewModelScope.launch {
            collectJob?.join()
            rootRecollectScheduled = false
            refresh()
        }
    }

    
    private fun DeviceUiState.withSearch(): DeviceUiState {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            return copy(groups = baseGroups, visibleGroups = baseGroups)
        }
        val searchedGroups = baseGroups.map { group ->
            val hits = group.fields.filter { it.matches(keyword) }
            group.copy(
                preview = if (hits.isEmpty()) group.preview else previewOf(hits),
                matchCount = hits.size,
                matchedFieldIds = hits.mapTo(LinkedHashSet()) { it.id },
            )
        }
        return copy(
            groups = searchedGroups,
            visibleGroups = searchedGroups.filter { it.matchCount > 0 },
        )
    }

    private fun DeviceFieldUi.matches(keyword: String): Boolean =
        name.contains(keyword, ignoreCase = true) ||
            propertyKey.contains(keyword, ignoreCase = true) ||
            (value?.contains(keyword, ignoreCase = true) == true)

    private fun DeviceGroup.toUi(ordinal: Int): DeviceGroupUi {
        val fields = fields.mapIndexed { index, field -> field.toUi(index + 1) }
        return DeviceGroupUi(
            id = id,
            ordinal = ordinal,
            title = title,
            fields = fields,
            preview = previewOf(fields),
        )
    }

    private fun DeviceField.toUi(ordinal: Int): DeviceFieldUi = DeviceFieldUi(
        id = id,
        ordinal = ordinal,
        name = name,
        propertyKey = propertyKey,
        value = value,
        restriction = restriction,
        note = note,
    )

    
    private fun previewOf(fields: List<DeviceFieldUi>): String {
        val safeFields = fields.filterNot { it.isUnmaskedIdentityField }
        val previewFields = if (safeFields.isEmpty()) fields else safeFields
        val withValue = previewFields.filter { !it.value.isNullOrBlank() }
        val picked = (withValue.take(PREVIEW_COUNT) + previewFields.take(PREVIEW_COUNT))
            .distinctBy { it.id }
            .take(PREVIEW_COUNT)
        return picked.joinToString(" · ") { field ->
            "${field.name}：${field.value?.let(::shortenValue) ?: DASH}"
        }
    }

    
    private fun shortenValue(value: String): String {
        val compact = value.trim().replace(Regex("\\s+"), " ")
        if (compact.length <= PREVIEW_VALUE_MAX) return compact
        val headChars = PREVIEW_HEAD_CHARS
        val tailChars = PREVIEW_VALUE_MAX - headChars - 1
        return compact.take(headChars) + "…" + compact.takeLast(tailChars)
    }

    
    private fun buildSummary(groups: List<DeviceGroupUi>, rootState: RootState): DeviceSummaryUi? {
        val fields = groups.flatMap { it.fields }
        if (fields.isEmpty()) return null
        fun valueOf(vararg propertyKeys: String): String? = propertyKeys.firstNotNullOfOrNull { key ->
            fields.firstOrNull { it.propertyKey == key }?.value?.takeIf { it.isNotBlank() }
        }

        val model = valueOf("ro.product.model")
        val marketName = valueOf("ro.product.marketname")
        val deviceName = valueOf("settings.global.device_name")
        val name = marketName ?: model ?: deviceName
            ?: fields.firstOrNull { !it.value.isNullOrBlank() }?.value
        if (name.isNullOrBlank()) return null

        val soc = valueOf("ro.soc.model")
        val abi = valueOf("ro.product.cpu.abilist", "ro.product.cpu.abi")
            ?.substringBefore(',')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val memory = valueOf("/proc/meminfo")
        val storage = valueOf("/data")

        val metaParts = buildList {
            model?.let { add(it) }
            val chip = listOfNotNull(soc, abi).joinToString(" ")
            if (chip.isNotEmpty()) add(chip)
            val capacity = listOfNotNull(memory, storage).joinToString(" / ")
            if (capacity.isNotEmpty()) add(capacity)
        }

        val unlocked = valueOf("ro.boot.verifiedbootstate", "ro.boot.flash.locked")
            ?.let { raw ->
                val lower = raw.lowercase()
                raw.contains("已解锁") || lower.contains("orange") || lower.contains("unlocked")
            } == true

        return DeviceSummaryUi(
            name = name,
            meta = metaParts.distinct().joinToString(" · "),
            unlocked = unlocked,
            rooted = rootState is RootState.Granted,
        )
    }

    private companion object {
        const val PREVIEW_COUNT = 3
        const val PREVIEW_VALUE_MAX = 14
        const val PREVIEW_HEAD_CHARS = 8
        const val SERIAL_MIN_LENGTH = 1
        const val SERIAL_MAX_LENGTH = 32
        const val DASH = "—"

        
        val SERIAL_PATTERN = Regex("^[A-Za-z0-9._-]+$")
    }
}
