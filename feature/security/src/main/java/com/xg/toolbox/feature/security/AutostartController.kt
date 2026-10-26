package com.xg.toolbox.feature.security

import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.feature.security.domain.AutostartScanner
import com.xg.toolbox.feature.security.domain.BootReceiverInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AutostartController(
    private val scope: CoroutineScope,
    private val store: SecurityStateStore,
    private val scanner: AutostartScanner,
) {

    private var loadedOnce = false

    
    private var loadedWithRoot = false

    
    fun ensureLoaded() {
        val rootGranted = store.current.rootGranted
        if (loadedOnce && (loadedWithRoot || !rootGranted)) return
        scope.launch { load(forceRefresh = false) }
    }

    fun refresh() {
        scope.launch { load(forceRefresh = true) }
    }

    suspend fun load(forceRefresh: Boolean = false) {
        store.update { it.copy(autostart = it.autostart.copy(loading = true, error = null)) }
        try {
            val vendorManagers = runCatching {
                scanner.detectVendorAutostartManagers()
            }.getOrDefault(emptyList())
            when (val result = scanner.list(forceRefresh = forceRefresh)) {
                is XgResult.Ok -> {
                    val groups = result.data
                        .groupBy { it.packageName }
                        .map { (packageName, receivers) ->
                            AutostartGroup(
                                packageName = packageName,
                                label = receivers.firstOrNull()?.label
                                    ?.takeIf { it.isNotBlank() } ?: packageName,
                                receivers = receivers.sortedBy { it.component },
                            )
                        }
                        .sortedBy { it.label.lowercase() }
                    loadedOnce = true
                    loadedWithRoot = store.current.rootGranted
                    store.update {
                        it.copy(
                            autostart = it.autostart.copy(
                                loading = false,
                                error = null,
                                groups = groups,
                                totalReceivers = result.data.size,
                                vendorManagers = vendorManagers,
                                loaded = true,
                            ),
                        )
                    }
                }

                is XgResult.Err -> {
                    store.update {
                        it.copy(
                            autostart = it.autostart.copy(
                                loading = false,
                                error = UiError.of(result),
                                loaded = true,
                            ),
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            store.update {
                it.copy(
                    autostart = it.autostart.copy(
                        loading = false,
                        error = UiError.of(t, "读取开机接收器失败"),
                        loaded = true,
                    ),
                )
            }
        }
    }

    fun toggleGroup(packageName: String) {
        store.update { state ->
            val expanded = state.autostart.expanded
            state.copy(
                autostart = state.autostart.copy(
                    expanded = if (packageName in expanded) {
                        expanded - packageName
                    } else {
                        expanded + packageName
                    },
                ),
            )
        }
    }

    fun expandAll() {
        store.update { state ->
            state.copy(
                autostart = state.autostart.copy(
                    expanded = state.autostart.groups.map { it.packageName }.toSet(),
                ),
            )
        }
    }

    fun collapseAll() {
        store.update { it.copy(autostart = it.autostart.copy(expanded = emptySet())) }
    }

    
    fun setEnabled(receiver: BootReceiverInfo, enabled: Boolean) {
        if (!store.current.rootGranted) {
            store.show(SecurityMessage.Res(R.string.sec_auto_need_root))
            return
        }
        val key = receiver.component
        if (key in store.current.autostart.pending) return
        store.update {
            it.copy(autostart = it.autostart.copy(pending = it.autostart.pending + key))
        }
        scope.launch {
            try {
                val result = scanner.setEnabled(receiver.packageName, receiver.component, enabled)
                when (result) {
                    is XgResult.Ok -> {
                        store.update { state ->
                            state.copy(
                                autostart = state.autostart.copy(
                                    pending = state.autostart.pending - key,
                                    groups = state.autostart.groups.map { group ->
                                        group.copy(
                                            receivers = group.receivers.map { existing ->
                                                if (existing.component == key) {
                                                    existing.copy(enabled = enabled)
                                                } else {
                                                    existing
                                                }
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                        store.show(
                            SecurityMessage.Res(
                                if (enabled) {
                                    R.string.sec_auto_toast_enabled
                                } else {
                                    R.string.sec_auto_toast_disabled
                                },
                                listOf(componentSimpleName(receiver.component)),
                            ),
                        )
                    }

                    is XgResult.Err -> {
                        clearPending(key)
                        store.show(SecurityMessage.Raw(result.message))
                        load(forceRefresh = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                clearPending(key)
                store.show(SecurityMessage.Raw(t.message ?: "写入开机接收器状态失败"))
                load(forceRefresh = true)
            }
        }
    }

    private fun clearPending(key: String) {
        store.update {
            it.copy(autostart = it.autostart.copy(pending = it.autostart.pending - key))
        }
    }

    private fun componentSimpleName(component: String): String {
        val slash = component.indexOf('/')
        return if (slash >= 0) component.substring(slash + 1) else component
    }
}
