package com.xg.toolbox.feature.apps

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppRepository
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.core.device.RebootController
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.feature.apps.domain.AppActions
import com.xg.toolbox.feature.apps.domain.BatchAction
import com.xg.toolbox.feature.apps.domain.BatchProgress
import com.xg.toolbox.feature.apps.domain.ModuleInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppsEvent {
    data object BackToOverview : AppsEvent
}

@HiltViewModel
class AppsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appRepository: AppRepository,
    private val appActions: AppActions,
    private val rootStatus: RootStatusRepository,
    private val rebootController: RebootController,
    private val settingsStore: SettingsStore,
    private val iconCache: AppIconCache,
) : ViewModel() {

    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toasts: SharedFlow<String> = _toasts.asSharedFlow()

    private val _events = MutableSharedFlow<AppsEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<AppsEvent> = _events.asSharedFlow()

    private val memoryCache = ConcurrentHashMap<String, Long>()

    private var searchJob: Job? = null
    private var batchSearchJob: Job? = null

    init {
        viewModelScope.launch {
            rootStatus.state.collect { root ->
                _state.update { it.copy(rootGranted = root is RootState.Granted) }
            }
        }
        viewModelScope.launch {
            settingsStore.readOnly.collect { readOnly ->
                _state.update { it.copy(readOnly = readOnly) }
            }
        }
        loadApps(forceRefresh = false)
    }

    fun refresh() = loadApps(forceRefresh = true)

    private fun loadApps(forceRefresh: Boolean) {
        viewModelScope.launch {
            val hasRows = _state.value.rows.isNotEmpty()
            _state.update {
                if (hasRows) {
                    it.copy(refreshing = true, refreshError = null)
                } else {
                    it.copy(loading = true, listError = null)
                }
            }
            when (val result = appRepository.list(forceRefresh)) {
                is XgResult.Ok -> {
                    val entries = result.data
                    val installedPackages = entries.mapTo(HashSet(entries.size)) { it.packageName }
                    memoryCache.keys.retainAll(installedPackages)
                    val snapshot = _state.value
                    val (rows, visible) = withContext(Dispatchers.Default) {
                        val mapped = entries.map { entry -> entry.toRowUi(memoryCache[entry.packageName]) }
                        mapped to computeVisible(mapped, snapshot.query, snapshot.filter, snapshot.sort)
                    }
                    _state.update {
                        it.copy(
                            rows = rows,
                            visibleRows = visible,
                            frozenCount = rows.count { row -> row.frozen },
                            loading = false,
                            refreshing = false,
                            listError = null,
                            refreshError = null,
                        )
                    }
                    if (_state.value.batch.step == BatchStep.SELECT) {
                        refreshBatchVisibleAsync()
                    }
                }

                is XgResult.Err -> {
                    _state.update { current ->
                        if (current.rows.isEmpty()) {
                            current.copy(loading = false, listError = result)
                        } else {
                            current.copy(refreshing = false, refreshError = result)
                        }
                    }
                }
            }
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            applyVisibleFilter()
        }
    }

    fun setFilter(filter: AppsFilter) {
        searchJob?.cancel()
        _state.update { it.copy(filter = filter) }
        viewModelScope.launch { applyVisibleFilter() }
    }

    fun setSort(sort: AppsSort) {
        searchJob?.cancel()
        _state.update { it.copy(sort = sort) }
        viewModelScope.launch { applyVisibleFilter() }
    }

    fun clearSearchAndFilter() {
        searchJob?.cancel()
        _state.update { it.copy(query = "", filter = AppsFilter.ALL, sort = AppsSort.NAME) }
        viewModelScope.launch { applyVisibleFilter() }
    }

    private suspend fun applyVisibleFilter() {
        val snapshot = _state.value
        val visible = withContext(Dispatchers.Default) {
            computeVisible(snapshot.rows, snapshot.query, snapshot.filter, snapshot.sort)
        }
        _state.update { current ->
            if (current.query == snapshot.query && current.filter == snapshot.filter && current.sort == snapshot.sort) {
                current.copy(visibleRows = visible, frozenCount = snapshot.rows.count { it.frozen })
            } else {
                current
            }
        }
    }

    fun peekIcon(packageName: String, versionCode: Long): ImageBitmap? =
        iconCache.peek(packageName, versionCode)

    suspend fun loadIcon(packageName: String, versionCode: Long): ImageBitmap? =
        iconCache.load(packageName, versionCode)

    private fun rememberMemory(packageName: String, memoryBytes: Long?) {
        if (memoryBytes != null) memoryCache[packageName] = memoryBytes else memoryCache.remove(packageName)
        viewModelScope.launch {
            val snapshot = _state.value
            val (rows, visible) = withContext(Dispatchers.Default) {
                val mapped = snapshot.rows.map { row ->
                    if (row.packageName == packageName) row.copy(memoryBytes = memoryBytes) else row
                }
                mapped to computeVisible(mapped, snapshot.query, snapshot.filter, snapshot.sort)
            }
            _state.update { it.copy(rows = rows, visibleRows = visible) }
        }
    }

    fun loadDetail(packageName: String) {
        _state.update {
            it.copy(
                detailPackage = packageName,
                detail = null,
                detailLoading = true,
                detailError = null,
                actionFailure = null,
            )
        }
        viewModelScope.launch {
            val result = appRepository.detail(packageName)
            if (_state.value.detailPackage != packageName) return@launch
            when (result) {
                is XgResult.Err -> {
                    _state.update { it.copy(detailLoading = false, detailError = result, detail = null) }
                    return@launch
                }

                is XgResult.Ok -> {
                    _state.update {
                        it.copy(
                            detailLoading = false,
                            detailError = null,
                            detail = AppDetailUi(
                                detail = result.data,
                                memory = null,
                                memoryLoading = true,
                                traffic = null,
                                trafficLoading = true,
                                usageError = null,
                                abiStatus = null,
                                abiLoading = true,
                                abiError = null,
                            ),
                        )
                    }
                }
            }
            loadAbi(packageName)
            loadUsage(packageName)
        }
    }

    fun retryAbi() {
        val packageName = _state.value.detailPackage ?: return
        _state.update { current ->
            val detail = current.detail ?: return@update current
            current.copy(detail = detail.copy(abiLoading = true, abiError = null))
        }
        viewModelScope.launch { loadAbi(packageName) }
    }

    fun retryUsage() {
        val packageName = _state.value.detailPackage ?: return
        _state.update { current ->
            val detail = current.detail ?: return@update current
            current.copy(
                detail = detail.copy(
                    memory = null,
                    memoryLoading = true,
                    traffic = null,
                    trafficLoading = true,
                    usageError = null,
                ),
            )
        }
        viewModelScope.launch { loadUsage(packageName) }
    }

    private suspend fun loadAbi(packageName: String) {
        val result = appActions.abiStatus(packageName)
        if (_state.value.detailPackage != packageName) return
        _state.update { current ->
            val detail = current.detail ?: return@update current
            current.copy(
                detail = when (result) {
                    is XgResult.Ok -> detail.copy(abiStatus = result.data, abiLoading = false, abiError = null)
                    is XgResult.Err -> detail.copy(abiLoading = false, abiError = result)
                },
            )
        }
    }

    private suspend fun loadUsage(packageName: String) {
        val usageResult = appRepository.appUsage(packageName)
        if (_state.value.detailPackage != packageName) return
        when (usageResult) {
            is XgResult.Ok -> {
                val usage = usageResult.data
                rememberMemory(packageName, usage.memoryBytes)
                _state.update { current ->
                    val detail = current.detail ?: return@update current
                    current.copy(
                        detail = detail.copy(
                            memory = usage.toMemoryState(),
                            memoryLoading = false,
                            traffic = usage.toTrafficState(),
                            trafficLoading = false,
                            usageError = null,
                        ),
                    )
                }
            }

            is XgResult.Err -> {
                _state.update { current ->
                    val detail = current.detail ?: return@update current
                    current.copy(
                        detail = detail.copy(
                            memory = MemoryState.Unavailable,
                            memoryLoading = false,
                            traffic = TrafficState.Unavailable,
                            trafficLoading = false,
                            usageError = usageResult,
                        ),
                    )
                }
            }
        }
    }

    fun toggleFreeze(packageName: String) {
        if (!guardWrite()) return
        if (_state.value.inFlight != null) return
        val currentRow = _state.value.rows.firstOrNull { it.packageName == packageName }
        val currentFrozen = _state.value.detail?.detail?.takeIf { it.packageName == packageName }?.isFrozen
            ?: currentRow?.frozen
            ?: return
        val freeze = !currentFrozen
        viewModelScope.launch {
            val kind = if (freeze) ActionKind.FREEZE else ActionKind.UNFREEZE
            _state.update { it.copy(inFlight = InFlightAction(packageName, kind), actionFailure = null) }
            val result = if (freeze) appRepository.freeze(packageName) else appRepository.unfreeze(packageName)
            _state.update { it.copy(inFlight = null) }
            when (result) {
                is XgResult.Ok -> {
                    _state.update { current ->
                        val rows = current.rows.map { row ->
                            if (row.packageName == packageName) row.copy(frozen = freeze) else row
                        }
                        val detail = current.detail?.let { loaded ->
                            if (loaded.detail.packageName == packageName) {
                                loaded.copy(detail = loaded.detail.copy(isFrozen = freeze))
                            } else {
                                loaded
                            }
                        }
                        current.copy(rows = rows, detail = detail)
                    }
                    applyVisibleFilter()
                    emit(
                        context.getString(
                            if (freeze) R.string.apps_toast_frozen else R.string.apps_toast_unfrozen,
                            currentRow?.label ?: packageName,
                        ),
                    )
                }

                is XgResult.Err -> {
                    setFailure(packageName, result)
                    emit(result.message)
                }
            }
        }
    }

    fun requestUninstall(packageName: String) {
        if (!guardWrite()) return
        val row = _state.value.rows.firstOrNull { it.packageName == packageName } ?: return
        _state.update {
            it.copy(
                dialog = AppsDialog.UninstallConfirm(
                    packageName = packageName,
                    label = row.label,
                    apkSizeBytes = row.apkSizeBytes,
                    backupLimitBytes = MAX_BACKUP_BYTES,
                ),
            )
        }
    }

    private fun uninstall(packageName: String, label: String) {
        if (_state.value.inFlight != null) return
        viewModelScope.launch {
            _state.update {
                it.copy(inFlight = InFlightAction(packageName, ActionKind.UNINSTALL), actionFailure = null)
            }
            val result = appRepository.uninstall(packageName)
            _state.update { it.copy(inFlight = null) }
            when (result) {
                is XgResult.Ok -> {
                    _state.update { current ->
                        val rows = current.rows.filterNot { it.packageName == packageName }
                        current.copy(
                            rows = rows,
                            detailPackage = if (current.detailPackage == packageName) null else current.detailPackage,
                            detail = if (current.detail?.detail?.packageName == packageName) null else current.detail,
                            pendingUndo = PendingUndo(
                                packageName = packageName,
                                label = label,
                                outcome = result.data,
                                expiresAt = System.currentTimeMillis() + UNDO_WINDOW_MS,
                            ),
                        )
                    }
                    applyVisibleFilter()
                    emit(context.getString(R.string.apps_toast_uninstalled, label))
                    _events.tryEmit(AppsEvent.BackToOverview)
                }

                is XgResult.Err -> {
                    setFailure(packageName, result)
                    emit(result.message)
                }
            }
        }
    }

    fun undoUninstall() {
        val undo = _state.value.pendingUndo ?: return
        if (_state.value.inFlight != null) return
        _state.update { it.copy(pendingUndo = null) }
        viewModelScope.launch {
            _state.update { it.copy(inFlight = InFlightAction(undo.packageName, ActionKind.UNDO_UNINSTALL)) }
            val result = appRepository.undoUninstall(undo.packageName)
            _state.update { it.copy(inFlight = null) }
            when (result) {
                is XgResult.Ok -> {
                    emit(context.getString(R.string.apps_toast_undo_ok, undo.label))
                    refresh()
                }

                is XgResult.Err -> {
                    setFailure(undo.packageName, result)
                    emit(context.getString(R.string.apps_toast_undo_failed, result.message))
                }
            }
        }
    }

    fun clearPendingUndo() = _state.update { it.copy(pendingUndo = null) }

    
    fun requestAbiSwitch(packageName: String, targetAbi: String) {
        if (!guardWrite()) return
        val loaded = _state.value.detail
        val status = loaded?.abiStatus
        if (loaded == null || loaded.detail.packageName != packageName || status == null) {
            emit(context.getString(R.string.apps_abi_state_missing))
            return
        }
        if (status.apkAbis.isEmpty()) {
            emit(context.getString(R.string.apps_abi_no_native))
            return
        }
        if (status.currentAbi == targetAbi) {
            emit(context.getString(R.string.apps_abi_same))
            return
        }
        if (status.apkAbis.none { it == targetAbi }) {
            setFailure(
                packageName,
                XgResult.Err(
                    ErrorCode.ERR_UNSUPPORTED_ABI,
                    context.getString(R.string.apps_abi_unsupported, targetAbi),
                    status.reason,
                ),
            )
            emit(context.getString(R.string.apps_abi_unsupported, targetAbi))
            return
        }
        _state.update {
            it.copy(
                dialog = AppsDialog.AbiConfirm(
                    packageName = packageName,
                    label = loaded.detail.label,
                    currentAbi = status.currentAbi,
                    targetAbi = targetAbi,
                ),
            )
        }
    }

    private fun performAbiSwitch(packageName: String, label: String, targetAbi: String) {
        if (_state.value.inFlight != null) return
        viewModelScope.launch {
            _state.update { it.copy(inFlight = InFlightAction(packageName, ActionKind.ABI), actionFailure = null) }
            try {
                when (val result = appActions.switchAbi(packageName, targetAbi)) {
                    is XgResult.Ok -> {
                        emit(context.getString(R.string.apps_toast_abi_switch_ok, label, targetAbi))
                        refreshDetailAfterAbi(packageName)
                    }

                    is XgResult.Err -> {
                        setFailure(packageName, result)
                        emit(result.message)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                emit("ABI 切换失败：${t.message ?: "未知错误"}")
            } finally {
                _state.update { it.copy(inFlight = null) }
            }
        }
    }

    private suspend fun refreshDetailAfterAbi(packageName: String) {
        if (_state.value.detailPackage != packageName) return
        when (val result = appRepository.detail(packageName)) {
            is XgResult.Ok -> _state.update { current ->
                val detail = current.detail ?: return@update current
                if (detail.detail.packageName != packageName) {
                    current
                } else {
                    current.copy(detail = detail.copy(detail = result.data))
                }
            }

            is XgResult.Err -> Unit
        }
        loadAbi(packageName)
    }

    fun requestClearCache(packageName: String) {
        if (!guardWrite()) return
        val label = labelOf(packageName) ?: return
        _state.update { it.copy(dialog = AppsDialog.ClearCacheConfirm(packageName, label)) }
    }

    fun requestClearData(packageName: String) {
        if (!guardWrite()) return
        val label = labelOf(packageName) ?: return
        _state.update { it.copy(dialog = AppsDialog.ClearDataConfirm(packageName, label)) }
    }

    private fun labelOf(packageName: String): String? =
        _state.value.detail?.takeIf { it.detail.packageName == packageName }?.detail?.label
            ?: _state.value.rows.firstOrNull { it.packageName == packageName }?.label

    private fun clearCache(packageName: String) {
        if (_state.value.inFlight != null) return
        viewModelScope.launch {
            _state.update { it.copy(inFlight = InFlightAction(packageName, ActionKind.CLEAR_CACHE), actionFailure = null) }
            try {
                when (val result = appActions.clearAppCache(packageName)) {
                    is XgResult.Ok -> {
                        emit(context.getString(R.string.apps_toast_clear_cache_ok, labelOf(packageName) ?: packageName))
                        if (_state.value.detailPackage == packageName) loadUsage(packageName)
                    }

                    is XgResult.Err -> {
                        setFailure(packageName, result)
                        emit(result.message)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                emit("清除缓存失败：${t.message ?: "未知错误"}")
            } finally {
                _state.update { it.copy(inFlight = null) }
            }
        }
    }

    private fun clearData(packageName: String) {
        if (_state.value.inFlight != null) return
        viewModelScope.launch {
            _state.update { it.copy(inFlight = InFlightAction(packageName, ActionKind.CLEAR_DATA), actionFailure = null) }
            try {
                when (val result = appActions.clearAppData(packageName)) {
                    is XgResult.Ok -> {
                        emit(context.getString(R.string.apps_toast_clear_data_ok, labelOf(packageName) ?: packageName))
                        if (_state.value.detailPackage == packageName) loadUsage(packageName)
                    }

                    is XgResult.Err -> {
                        setFailure(packageName, result)
                        emit(result.message)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                emit("清除数据失败：${t.message ?: "未知错误"}")
            } finally {
                _state.update { it.copy(inFlight = null) }
            }
        }
    }

    fun openBatch() {
        _state.update { current ->
            val next = current.copy(
                batch = current.batch.copy(
                    step = BatchStep.SELECT,
                    selected = emptySet(),
                    query = "",
                    preflight = null,
                    preflightLoading = false,
                    preflightError = null,
                    progress = null,
                    executing = false,
                    receipt = null,
                    receiptError = null,
                    undoRunning = false,
                    undoResult = null,
                    undoError = null,
                ),
            )
            next.copy(batch = next.batch.copy(visible = next.rows))
        }
        viewModelScope.launch {
            val snapshot = _state.value
            val visible = withContext(Dispatchers.Default) { computeBatchVisible(snapshot.batch, snapshot.rows) }
            _state.update { it.copy(batch = it.batch.copy(visible = visible)) }
        }
    }

    fun resetBatch() {
        _state.update { it.copy(batch = BatchUiState()) }
    }

    fun setBatchAction(action: BatchAction) {
        _state.update { it.copy(batch = it.batch.copy(action = action, preflight = null, preflightError = null)) }
    }

    fun setBatchQuery(value: String) {
        _state.update { it.copy(batch = it.batch.copy(query = value)) }
        batchSearchJob?.cancel()
        batchSearchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val snapshot = _state.value
            val visible = withContext(Dispatchers.Default) { computeBatchVisible(snapshot.batch, snapshot.rows) }
            _state.update { current ->
                if (current.batch.query == snapshot.batch.query) {
                    current.copy(batch = current.batch.copy(visible = visible))
                } else {
                    current
                }
            }
        }
    }

    fun toggleBatchSelect(packageName: String) {
        _state.update { current ->
            val batch = current.batch
            val selected = if (packageName in batch.selected) {
                batch.selected - packageName
            } else {
                batch.selected + packageName
            }
            current.copy(batch = batch.copy(selected = selected))
        }
    }

    fun selectAllBatchVisible() {
        _state.update { current ->
            val batch = current.batch
            val visiblePackages = batch.visible.map { it.packageName }.toSet()
            val allSelected = visiblePackages.isNotEmpty() && visiblePackages.all { it in batch.selected }
            current.copy(
                batch = batch.copy(
                    selected = if (allSelected) batch.selected - visiblePackages else batch.selected + visiblePackages,
                ),
            )
        }
    }

    fun goToPreflight() {
        val current = _state.value
        val batch = current.batch
        if (batch.selected.isEmpty()) {
            emit(context.getString(R.string.apps_toast_select_first))
            return
        }
        _state.update { it.copy(batch = it.batch.copy(step = BatchStep.PREFLIGHT)) }
        runPreflight()
    }

    fun backToBatchSelect() {
        _state.update { it.copy(batch = it.batch.copy(step = BatchStep.SELECT)) }
    }

    fun runPreflight() {
        val current = _state.value
        val batch = current.batch
        val targets = batch.selected.toList().sorted()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(batch = it.batch.copy(preflightLoading = true, preflightError = null)) }
            val result = appActions.preflight(targets, batch.action)
            _state.update { state ->
                when (result) {
                    is XgResult.Ok -> state.copy(
                        batch = state.batch.copy(preflight = result.data, preflightLoading = false),
                    )

                    is XgResult.Err -> state.copy(
                        batch = state.batch.copy(preflightLoading = false, preflightError = result),
                    )
                }
            }
        }
    }

    fun startBatch() {
        val current = _state.value
        val batch = current.batch
        if (batch.executing || !batch.canStart) return
        val targets = batch.selected.toList().sorted()
        val action = batch.action
        viewModelScope.launch {
            _state.update {
                it.copy(
                    batch = it.batch.copy(
                        step = BatchStep.EXECUTING,
                        executing = true,
                        progress = null,
                        receipt = null,
                        receiptError = null,
                        undoResult = null,
                        undoError = null,
                    ),
                )
            }
            var last: BatchProgress? = null
            val failure = try {
                appActions.runBatch(targets, action).collect { progress ->
                    last = progress
                    _state.update { state -> state.copy(batch = state.batch.copy(progress = progress)) }
                }
                null
            } catch (t: Throwable) {
                t
            }
            val progress = last
            val results = progress?.results.orEmpty()
            val success = results.count { it.status == STATUS_OK }
            val failed = results.count { it.status == STATUS_FAILED }
            val explicitSkipped = results.count { it.status == STATUS_SKIPPED }
            val missing = (targets.size - results.size).coerceAtLeast(0)
            val skipped = explicitSkipped + missing
            val domainReceipt = appActions.lastReceipt.value?.takeIf { it.action == action }
            val receiptError = when {
                failure != null -> context.getString(R.string.apps_batch_exec_failed, failure.message.orEmpty())
                domainReceipt == null -> context.getString(R.string.apps_batch_receipt_unavailable)
                else -> null
            }
            _state.update {
                it.copy(
                    batch = it.batch.copy(
                        step = BatchStep.RECEIPT,
                        executing = false,
                        receipt = domainReceipt,
                        receiptError = receiptError,
                        progress = progress ?: it.batch.progress?.copy(
                            done = targets.size,
                            total = targets.size,
                            current = null,
                            results = results,
                        ),
                    ),
                )
            }
            emit(context.getString(R.string.apps_toast_batch_done, success, failed, skipped))
            refresh()
        }
    }

    fun undoBatch() {
        val batch = _state.value.batch
        val receipt = batch.receipt ?: return
        if (batch.undoRunning || batch.undoResult != null) return
        viewModelScope.launch {
            _state.update { it.copy(batch = it.batch.copy(undoRunning = true, undoError = null)) }
            try {
                val result = appActions.undoBatch(receipt)
                _state.update { state ->
                    when (result) {
                        is XgResult.Ok -> state.copy(
                            batch = state.batch.copy(
                                undoRunning = false,
                                undoResult = context.getString(R.string.apps_batch_undo_ok),
                            ),
                        )

                        is XgResult.Err -> state.copy(
                            batch = state.batch.copy(undoRunning = false, undoError = result.message),
                        )
                    }
                }
                if (result is XgResult.Ok) refresh()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { state ->
                    state.copy(
                        batch = state.batch.copy(
                            undoRunning = false,
                            undoError = t.message?.takeIf { it.isNotBlank() } ?: "撤销执行失败",
                        ),
                    )
                }
            }
        }
    }

    private fun refreshBatchVisibleAsync() {
        viewModelScope.launch {
            val snapshot = _state.value
            val visible = withContext(Dispatchers.Default) { computeBatchVisible(snapshot.batch, snapshot.rows) }
            _state.update { it.copy(batch = it.batch.copy(visible = visible)) }
        }
    }

    fun loadModules(force: Boolean = false) {
        val modules = _state.value.modules
        if (!force && !modules.loading && modules.modules.isNotEmpty()) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    modules = it.modules.copy(
                        loading = it.modules.modules.isEmpty(),
                        refreshing = it.modules.modules.isNotEmpty(),
                        error = null,
                    ),
                )
            }
            val result = appActions.listModules()
            _state.update { current ->
                current.copy(
                    modules = when (result) {
                        is XgResult.Ok -> current.modules.copy(
                            loading = false,
                            refreshing = false,
                            error = null,
                            modules = result.data,
                        )

                        is XgResult.Err -> current.modules.copy(
                            loading = false,
                            refreshing = false,
                            error = result,
                        )
                    },
                )
            }
        }
    }

    fun setModuleEnabled(module: ModuleInfo, enabled: Boolean) {
        if (!guardWrite()) return
        if (_state.value.modules.changingId != null || _state.value.modules.removingId != null) return
        viewModelScope.launch {
            _state.update {
                it.copy(modules = it.modules.copy(changingId = module.id, lastActionError = null))
            }
            val result = appActions.setModuleEnabled(module.id, enabled)
            _state.update { it.copy(modules = it.modules.copy(changingId = null)) }
            when (result) {
                is XgResult.Ok -> {
                    emit(
                        context.getString(
                            if (enabled) R.string.apps_toast_module_enabled else R.string.apps_toast_module_disabled,
                            module.name,
                        ),
                    )
                    _state.update { it.copy(rebootPrompt = context.getString(R.string.apps_reboot_module)) }
                    _state.update { current ->
                        current.copy(
                            modules = current.modules.copy(
                                modules = current.modules.modules.map {
                                    if (it.id == module.id) it.copy(enabled = enabled) else it
                                },
                            ),
                        )
                    }
                }

                is XgResult.Err -> {
                    _state.update { it.copy(modules = it.modules.copy(lastActionError = result.message)) }
                    emit(result.message)
                }
            }
        }
    }

    fun requestRemoveModule(module: ModuleInfo) {
        if (!guardWrite()) return
        _state.update { it.copy(dialog = AppsDialog.ModuleRemoveConfirm(module)) }
    }

    private fun removeModule(module: ModuleInfo) {
        if (_state.value.modules.removingId != null) return
        viewModelScope.launch {
            _state.update {
                it.copy(modules = it.modules.copy(removingId = module.id, lastActionError = null))
            }
            val result = appActions.removeModule(module.id)
            _state.update { it.copy(modules = it.modules.copy(removingId = null)) }
            when (result) {
                is XgResult.Ok -> {
                    emit(context.getString(R.string.apps_toast_module_removed, module.name))
                    _state.update { it.copy(rebootPrompt = context.getString(R.string.apps_reboot_module)) }
                    _state.update { current ->
                        current.copy(
                            modules = current.modules.copy(
                                modules = current.modules.modules.filterNot { it.id == module.id },
                            ),
                        )
                    }
                }

                is XgResult.Err -> {
                    _state.update { it.copy(modules = it.modules.copy(lastActionError = result.message)) }
                    emit(result.message)
                }
            }
        }
    }

    fun openAndroidIdPage() {
        _state.update {
            it.copy(
                androidId = AndroidIdUiState(
                    userApps = it.androidId.userApps,
                    visibleApps = it.androidId.visibleApps,
                    appsLoading = true,
                ),
            )
        }
        loadAndroidIdApps()
        loadGlobalEntryCount()
    }

    fun loadAndroidIdApps() {
        viewModelScope.launch {
            _state.update { it.copy(androidId = it.androidId.copy(appsLoading = true, appsError = null)) }
            val existing = _state.value.rows
            if (existing.isNotEmpty()) {
                val apps = withContext(Dispatchers.Default) { existing.filter { !it.isSystem } }
                _state.update { current ->
                    current.copy(
                        androidId = current.androidId.copy(
                            userApps = apps,
                            visibleApps = filterAndroidIdApps(apps, current.androidId.query),
                            appsLoading = false,
                            appsError = null,
                        ),
                    )
                }
                return@launch
            }
            when (val result = appRepository.list(forceRefresh = true)) {
                is XgResult.Ok -> {
                    val entries = result.data
                    val apps = withContext(Dispatchers.Default) {
                        entries.filter { !it.isSystem }.map { it.toRowUi() }
                    }
                    _state.update { current ->
                        current.copy(
                            androidId = current.androidId.copy(
                                userApps = apps,
                                visibleApps = filterAndroidIdApps(apps, current.androidId.query),
                                appsLoading = false,
                                appsError = null,
                            ),
                        )
                    }
                }

                is XgResult.Err -> _state.update {
                    it.copy(androidId = it.androidId.copy(appsLoading = false, appsError = result))
                }
            }
        }
    }

    fun loadGlobalEntryCount() {
        viewModelScope.launch {
            _state.update {
                it.copy(androidId = it.androidId.copy(globalEntryCountLoading = true, globalEntryCountError = null))
            }
            when (val result = appActions.ssaidEntryCount()) {
                is XgResult.Ok -> {
                    val count = result.data
                    _state.update { current ->
                        current.copy(
                            androidId = current.androidId.copy(
                                globalEntryCount = count,
                                globalEntryCountLoading = false,
                                globalEntryCountError = null,
                            ),
                        )
                    }
                }

                is XgResult.Err -> _state.update { current ->
                    current.copy(
                        androidId = current.androidId.copy(
                            globalEntryCountLoading = false,
                            globalEntryCountError = result,
                        ),
                    )
                }
            }
        }
    }

    fun setAndroidIdQuery(value: String) {
        _state.update { current ->
            current.copy(
                androidId = current.androidId.copy(
                    query = value,
                    visibleApps = filterAndroidIdApps(current.androidId.userApps, value),
                ),
            )
        }
    }

    private fun filterAndroidIdApps(apps: List<AppRowUi>, query: String): List<AppRowUi> {
        val keyword = query.trim().lowercase(Locale.US)
        if (keyword.isEmpty()) return apps
        return apps.filter { it.labelLower.contains(keyword) || it.packageLower.contains(keyword) }
    }

    fun selectAndroidIdTarget(packageName: String) {
        val app = _state.value.androidId.userApps.firstOrNull { it.packageName == packageName }
        _state.update { current ->
            current.copy(
                androidId = current.androidId.copy(
                    targetPackage = packageName,
                    targetLabel = app?.label ?: packageName,
                    currentSsaid = null,
                    currentSsaidLoading = true,
                    currentSsaidError = null,
                    ssaidError = null,
                    ssaidApplied = false,
                ),
            )
        }
        viewModelScope.launch {
            when (val result = appActions.currentSsaid(packageName)) {
                is XgResult.Ok -> {
                    val ssaid = result.data
                    _state.update { current ->
                        if (current.androidId.targetPackage != packageName) return@update current
                        current.copy(
                            androidId = current.androidId.copy(
                                currentSsaid = ssaid,
                                currentSsaidLoading = false,
                                currentSsaidError = null,
                            ),
                        )
                    }
                }

                is XgResult.Err -> _state.update { current ->
                    if (current.androidId.targetPackage != packageName) return@update current
                    current.copy(
                        androidId = current.androidId.copy(
                            currentSsaidLoading = false,
                            currentSsaidError = result,
                        ),
                    )
                }
            }
        }
    }

    fun clearSsaidTarget() {
        _state.update { current ->
            current.copy(
                androidId = current.androidId.copy(
                    targetPackage = null,
                    targetLabel = null,
                    currentSsaid = null,
                    currentSsaidLoading = false,
                    currentSsaidError = null,
                    ssaidApplied = false,
                ),
            )
        }
    }

    fun setGlobalAndroidIdInput(value: String) {
        val normalized = value.trim().lowercase(Locale.US).filter { it in HEX_CHARS }
        _state.update {
            it.copy(
                androidId = it.androidId.copy(
                    globalInput = normalized,
                    globalError = null,
                    globalOutcome = null,
                ),
            )
        }
    }

    fun generateGlobalAndroidId() {
        viewModelScope.launch {
            val id = appActions.generateAndroidId()
            _state.update {
                it.copy(
                    androidId = it.androidId.copy(
                        globalInput = id,
                        globalError = null,
                        globalOutcome = null,
                    ),
                )
            }
        }
    }

    fun requestGlobalAndroidId() {
        val android = _state.value.androidId
        if (!Formatters.isValidAndroidId(android.globalInput)) {
            _state.update {
                it.copy(androidId = it.androidId.copy(globalError = context.getString(R.string.apps_android_id_invalid)))
            }
            return
        }
        val count = android.globalEntryCount
        if (count == null) {
            _state.update {
                it.copy(androidId = it.androidId.copy(globalError = context.getString(R.string.apps_android_id_entries_loading)))
            }
            if (!android.globalEntryCountLoading) loadGlobalEntryCount()
            return
        }
        _state.update { it.copy(dialog = AppsDialog.GlobalIdConfirm(id16 = android.globalInput, entryCount = count)) }
    }

    private fun applyGlobalAndroidId(id16: String) {
        if (_state.value.androidId.globalApplying) return
        viewModelScope.launch {
            _state.update {
                it.copy(androidId = it.androidId.copy(globalApplying = true, globalError = null, globalOutcome = null))
            }
            when (val result = appActions.setGlobalAndroidId(id16)) {
                is XgResult.Ok -> {
                    val outcome = result.data
                    _state.update { current ->
                        current.copy(
                            androidId = current.androidId.copy(
                                globalApplying = false,
                                globalError = null,
                                globalEntryCount = outcome.totalEntries,
                                globalOutcome = GlobalIdResult(
                                    rewrittenEntries = outcome.rewrittenEntries,
                                    totalEntries = outcome.totalEntries,
                                    requiresReboot = outcome.requiresReboot,
                                ),
                            ),
                        )
                    }
                    emit(
                        context.getString(
                            R.string.apps_toast_android_id_ok,
                            outcome.rewrittenEntries,
                            outcome.totalEntries,
                        ),
                    )
                    if (outcome.requiresReboot) {
                        _state.update { it.copy(rebootPrompt = context.getString(R.string.apps_android_id_global_reboot)) }
                    }
                }

                is XgResult.Err -> {
                    _state.update { current ->
                        current.copy(
                            androidId = current.androidId.copy(globalApplying = false, globalError = result.message),
                        )
                    }
                    emit(result.message)
                }
            }
        }
    }

    fun requestSsaidApply() {
        val android = _state.value.androidId
        val packageName = android.targetPackage ?: run {
            _state.update {
                it.copy(androidId = it.androidId.copy(ssaidError = context.getString(R.string.apps_ssaid_no_target)))
            }
            return
        }
        _state.update {
            it.copy(
                dialog = AppsDialog.SsaidConfirm(
                    packageName = packageName,
                    label = android.targetLabel ?: packageName,
                ),
            )
        }
    }

    private fun applySsaid(packageName: String) {
        viewModelScope.launch {
            _state.update {
                it.copy(androidId = it.androidId.copy(ssaidApplying = true, ssaidError = null, ssaidApplied = false))
            }
            when (val result = appActions.resetAppSsaid(packageName, clearData = true)) {
                is XgResult.Ok -> {
                    _state.update { current ->
                        current.copy(
                            androidId = current.androidId.copy(
                                ssaidApplying = false,
                                ssaidError = null,
                                ssaidApplied = true,
                                currentSsaid = null,
                            ),
                        )
                    }
                    emit(context.getString(R.string.apps_toast_ssaid_ok, packageName))
                    _state.update { it.copy(rebootPrompt = context.getString(R.string.apps_reboot_ssaid)) }
                }

                is XgResult.Err -> {
                    _state.update { current ->
                        current.copy(androidId = current.androidId.copy(ssaidApplying = false, ssaidError = result.message))
                    }
                    emit(result.message)
                }
            }
        }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    fun confirmDialog() {
        val dialog = _state.value.dialog ?: return
        _state.update { it.copy(dialog = null) }
        when (dialog) {
            is AppsDialog.UninstallConfirm -> uninstall(dialog.packageName, dialog.label)
            is AppsDialog.AbiConfirm -> performAbiSwitch(dialog.packageName, dialog.label, dialog.targetAbi)
            is AppsDialog.ClearCacheConfirm -> clearCache(dialog.packageName)
            is AppsDialog.ClearDataConfirm -> clearData(dialog.packageName)
            is AppsDialog.ModuleRemoveConfirm -> removeModule(dialog.module)
            is AppsDialog.SsaidConfirm -> applySsaid(dialog.packageName)
            is AppsDialog.GlobalIdConfirm -> applyGlobalAndroidId(dialog.id16)
        }
    }

    fun clearActionFailure() = _state.update { it.copy(actionFailure = null) }

    fun dismissRebootPrompt() = _state.update { it.copy(rebootPrompt = null) }

    fun rebootNow() {
        _state.update { it.copy(rebootPrompt = null) }
        viewModelScope.launch {
            when (val result = rebootController.reboot(REBOOT_SYSTEM)) {
                is XgResult.Ok -> emit(context.getString(R.string.apps_toast_reboot_sent))
                is XgResult.Err -> emit(context.getString(R.string.apps_toast_reboot_failed, result.message))
            }
        }
    }

    private fun computeVisible(
        rows: List<AppRowUi>,
        query: String,
        filter: AppsFilter,
        sort: AppsSort,
    ): List<AppRowUi> {
        val keyword = query.trim().lowercase(Locale.US)
        val filtered = rows.asSequence().filter { row ->
            val matchesFilter = when (filter) {
                AppsFilter.ALL -> true
                AppsFilter.USER -> !row.isSystem
                AppsFilter.SYSTEM -> row.isSystem
                AppsFilter.FROZEN -> row.frozen
            }
            if (!matchesFilter) {
                false
            } else if (keyword.isEmpty()) {
                true
            } else {
                row.labelLower.contains(keyword) ||
                    row.packageLower.contains(keyword)
            }
        }
        return when (sort) {
            AppsSort.NAME -> filtered.sortedBy { it.labelLower }.toList()
            AppsSort.INSTALL_TIME -> filtered.sortedByDescending { it.installedAt }.toList()
            AppsSort.MEMORY -> filtered
                .sortedWith(
                    compareBy<AppRowUi> { it.memoryBytes ?: Long.MAX_VALUE }
                        .thenByDescending { it.apkSizeBytes },
                )
                .toList()
        }
    }

    private fun computeBatchVisible(batch: BatchUiState, rows: List<AppRowUi>): List<AppRowUi> {
        val keyword = batch.query.trim().lowercase(Locale.US)
        return rows.filter { row ->
            keyword.isEmpty() ||
                row.labelLower.contains(keyword) ||
                row.packageLower.contains(keyword)
        }.sortedBy { it.labelLower }
    }

    private fun guardWrite(): Boolean {
        val current = _state.value
        if (current.canWrite) return true
        emit(
            context.getString(
                if (!current.rootGranted) R.string.apps_toast_need_root else R.string.apps_toast_readonly,
            ),
        )
        return false
    }

    private fun setFailure(packageName: String, error: XgResult.Err) {
        _state.update {
            it.copy(
                actionFailure = ActionFailure(
                    packageName = packageName,
                    code = error.code,
                    message = error.message,
                    detail = error.detail?.lineSequence()?.firstOrNull()?.trim(),
                ),
            )
        }
    }

    private fun emit(message: String) {
        _toasts.tryEmit(message)
    }

    private companion object {
        const val MAX_BACKUP_BYTES = 200L * 1024 * 1024
        const val UNDO_WINDOW_MS = 5_000L
        const val REBOOT_SYSTEM = "system"
        const val SEARCH_DEBOUNCE_MS = 120L
        const val STATUS_OK = "ok"
        const val STATUS_FAILED = "failed"
        const val STATUS_SKIPPED = "skipped"
        val HEX_CHARS: Set<Char> = (('0'..'9') + ('a'..'f')).toSet()
    }
}

