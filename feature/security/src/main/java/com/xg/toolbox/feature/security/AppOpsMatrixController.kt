package com.xg.toolbox.feature.security

import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppEntry
import com.xg.toolbox.feature.security.domain.AppOpRow
import com.xg.toolbox.feature.security.domain.AppOpState
import com.xg.toolbox.feature.security.domain.AppOpsController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class AppOpsMatrixController(
    private val scope: CoroutineScope,
    private val store: SecurityStateStore,
    private val appOps: AppOpsController,
) {

    private var loadJob: Job? = null
    private var loadedOnce = false

    
    fun ensureLoaded() {
        if (loadedOnce) return
        if (!store.current.rootGranted) return
        if (store.current.apps.isEmpty()) return
        syncCounts()
        load(reset = true)
    }

    
    fun syncCounts() {
        val state = store.current
        val sample = MatrixAppSelector.systemSample(state.apps)
        val filtered = MatrixAppSelector.filter(
            apps = state.apps,
            filter = state.matrix.filter,
            query = state.matrix.query,
            systemSample = sample,
        )
        store.update {
            it.copy(
                matrix = it.matrix.copy(
                    definitions = if (it.matrix.definitions.isEmpty()) {
                        appOps.definitions()
                    } else {
                        it.matrix.definitions
                    },
                    filteredCount = filtered.size,
                    systemSampleSize = sample.size,
                    systemTotal = it.apps.count { app -> app.isSystem },
                ),
            )
        }
    }

    fun setQuery(query: String) {
        if (store.current.matrix.query == query) return
        store.update { it.copy(matrix = it.matrix.copy(query = query)) }
        syncCounts()
        if (store.current.rootGranted) load(reset = true)
    }

    fun setFilter(filter: MatrixFilter) {
        if (store.current.matrix.filter == filter) return
        store.update { it.copy(matrix = it.matrix.copy(filter = filter)) }
        syncCounts()
        if (store.current.rootGranted) load(reset = true)
    }

    fun retry() {
        loadedOnce = false
        syncCounts()
        load(reset = true, forceRefresh = true)
    }

    fun loadMore() {
        val matrix = store.current.matrix
        if (matrix.loading || matrix.loadingMore) return
        if (matrix.rows.size >= matrix.filteredCount) return
        load(reset = false)
    }

    
    fun cycleCell(packageName: String, op: String) {
        if (!store.current.rootGranted) {
            store.show(SecurityMessage.Res(R.string.sec_need_root))
            return
        }
        val row = store.current.matrix.rows.firstOrNull { it.packageName == packageName } ?: return
        if (row.cells.any { it.op == op && it.pending }) return
        val current = row.cells.firstOrNull { it.op == op }?.state
        val next = when (current) {
            AppOpState.ALLOW -> AppOpState.ASK
            AppOpState.ASK -> AppOpState.DENY
            AppOpState.DENY, null -> AppOpState.ALLOW
        }
        updateRow(packageName) { existing ->
            existing.copy(
                cells = existing.cells.map { cell ->
                    if (cell.op == op) cell.copy(pending = true) else cell
                },
            )
        }
        scope.launch {
            try {
                when (val result = appOps.set(packageName, op, next)) {
                    is XgResult.Ok -> {
                        updateRow(packageName) { existing ->
                            existing.copy(
                                error = null,
                                cells = existing.cells.map { cell ->
                                    if (cell.op == op) {
                                        cell.copy(state = next, pending = false)
                                    } else {
                                        cell
                                    }
                                },
                            )
                        }
                        val opLabel = row.cells.firstOrNull { it.op == op }?.label ?: op
                        store.show(SecurityMessage.AppOpUpdated(row.label, opLabel, next))
                    }

                    is XgResult.Err -> {
                        clearCellPending(packageName, op)
                        store.show(SecurityMessage.Raw(result.message))
                        reloadRow(packageName)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                clearCellPending(packageName, op)
                store.show(SecurityMessage.Raw(t.message ?: "写入 appops 失败"))
                reloadRow(packageName)
            }
        }
    }

    private fun load(reset: Boolean, forceRefresh: Boolean = false) {
        if (!store.current.rootGranted) return
        val filtered = filteredApps()
        if (filtered.isEmpty()) {
            loadJob?.cancel()
            loadedOnce = true
            store.update {
                it.copy(
                    matrix = it.matrix.copy(
                        rows = emptyList(),
                        loading = false,
                        loadingMore = false,
                        error = null,
                        loaded = true,
                        visibleCount = 0,
                    ),
                )
            }
            return
        }
        val requested = if (reset) {
            PAGE_SIZE
        } else {
            store.current.matrix.visibleCount + PAGE_SIZE
        }
        val targetCount = minOf(requested, filtered.size)
        val targetPackages = filtered.take(targetCount).map { it.packageName }
        val existingRows = if (reset) emptyList() else store.current.matrix.rows
        val existingPackages = existingRows.map { it.packageName }.toSet()
        val packagesToLoad = if (reset) {
            targetPackages
        } else {
            targetPackages.filterNot { it in existingPackages }
        }
        loadedOnce = true
        loadJob?.cancel()
        store.update {
            it.copy(
                matrix = it.matrix.copy(
                    definitions = if (it.matrix.definitions.isEmpty()) {
                        appOps.definitions()
                    } else {
                        it.matrix.definitions
                    },
                    loading = reset,
                    loadingMore = !reset,
                    error = null,
                    visibleCount = targetCount,
                ),
            )
        }
        loadJob = scope.launch {
            try {
                val loadedRows = if (packagesToLoad.isEmpty()) {
                    emptyList()
                } else {
                    appOps.load(packagesToLoad, forceRefresh = forceRefresh)
                }
                val order = targetPackages.withIndex().associate { (index, pkg) -> pkg to index }
                val merged = (existingRows + loadedRows.map { toRowUi(it) })
                    .distinctBy { it.packageName }
                    .sortedBy { order[it.packageName] ?: Int.MAX_VALUE }
                store.update {
                    it.copy(
                        matrix = it.matrix.copy(
                            rows = merged,
                            loading = false,
                            loadingMore = false,
                            error = null,
                            loaded = true,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                store.update {
                    it.copy(
                        matrix = it.matrix.copy(
                            loading = false,
                            loadingMore = false,
                            error = UiError.of(t, "读取应用权限失败"),
                        ),
                    )
                }
            }
        }
    }

    
    private fun clearCellPending(packageName: String, op: String) {
        updateRow(packageName) { existing ->
            existing.copy(
                cells = existing.cells.map { cell ->
                    if (cell.op == op) cell.copy(pending = false) else cell
                },
            )
        }
    }

    
    private fun updateRow(packageName: String, transform: (MatrixRowUi) -> MatrixRowUi) {
        store.update { state ->
            state.copy(
                matrix = state.matrix.copy(
                    rows = state.matrix.rows.map { existing ->
                        if (existing.packageName == packageName) transform(existing) else existing
                    },
                ),
            )
        }
    }

    private suspend fun reloadRow(packageName: String) {
        try {
            val fresh = appOps.load(listOf(packageName), forceRefresh = true).firstOrNull() ?: return
            val row = toRowUi(fresh)
            store.update { state ->
                state.copy(
                    matrix = state.matrix.copy(
                        rows = state.matrix.rows.map {
                            if (it.packageName == packageName) row else it
                        },
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }

    private fun toRowUi(row: AppOpRow): MatrixRowUi {
        val app = store.current.apps.firstOrNull { it.packageName == row.packageName }
        val cells = appOps.definitions().map { definition ->
            MatrixCellUi(
                op = definition.op,
                label = definition.label,
                state = row.cells[definition.op],
                pending = false,
            )
        }
        return MatrixRowUi(
            packageName = row.packageName,
            label = row.label.ifBlank { app?.label ?: row.packageName },
            isSystem = app?.isSystem ?: false,
            cells = cells,
            error = row.error,
        )
    }

    private fun filteredApps(): List<AppEntry> {
        val state = store.current
        return MatrixAppSelector.filter(
            apps = state.apps,
            filter = state.matrix.filter,
            query = state.matrix.query,
            systemSample = MatrixAppSelector.systemSample(state.apps),
        )
    }

    private companion object {
        
        const val PAGE_SIZE = 10
    }
}
