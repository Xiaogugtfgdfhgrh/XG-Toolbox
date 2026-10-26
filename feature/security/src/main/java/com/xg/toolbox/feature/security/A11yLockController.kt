package com.xg.toolbox.feature.security

import android.content.Context
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.feature.security.domain.AccessibilityGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class A11yLockController(
    private val scope: CoroutineScope,
    private val store: SecurityStateStore,
    private val guard: AccessibilityGuard,
    private val context: Context,
) {

    
    suspend fun load(forceRefresh: Boolean = false) {
        store.update { it.copy(a11y = it.a11y.copy(loading = !it.a11y.loaded, error = null)) }
        try {
            val accessibility = guard.read(forceRefresh = forceRefresh)
            val hasPermission = try {
                guard.checkPermission()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }
            val services = accessibility.enabledServices.map { parseService(it) }
            val monitorRequested = if (accessibility.whitelist.isNotEmpty()) {
                A11yMonitor.start(context) || A11yMonitor.running
            } else {
                A11yMonitor.stop(context)
                false
            }
            store.update {
                it.copy(
                    a11y = it.a11y.copy(
                        loading = false,
                        error = null,
                        services = services,
                        locked = accessibility.locked,
                        whitelist = accessibility.whitelist,
                        draftWhitelist = accessibility.whitelist,
                        hasWritePermission = hasPermission,
                        loaded = true,
                        monitorStartRequested = monitorRequested,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            store.update {
                it.copy(
                    a11y = it.a11y.copy(
                        loading = false,
                        error = UiError.of(t, "读取无障碍状态失败"),
                        loaded = true,
                    ),
                )
            }
        }
    }

    
    fun requestLock(enable: Boolean) {
        val a11y = store.current.a11y
        if (a11y.applying) return
        if (!store.current.rootGranted && !a11y.hasWritePermission) {
            store.show(SecurityMessage.Res(R.string.sec_a11y_need_root))
            return
        }
        if (!enable) {
            store.update { it.copy(dialog = SecurityDialog.ConfirmA11yUnlock) }
            return
        }
        val target = if (a11y.draftWhitelist.isNotEmpty()) {
            a11y.draftWhitelist
        } else {
            a11y.services.map { it.service }.toSet()
        }
        if (target.isEmpty()) {
            store.show(SecurityMessage.Res(R.string.sec_a11y_toast_no_services))
            return
        }
        apply(target)
    }

    
    fun confirmUnlock() {
        store.update { it.copy(dialog = null) }
        apply(emptySet())
    }

    
    fun toggleService(service: String, include: Boolean) {
        val a11y = store.current.a11y
        if (a11y.applying) return
        val next = if (include) a11y.draftWhitelist + service else a11y.draftWhitelist - service
        if (!a11y.locked) {
            store.update { it.copy(a11y = it.a11y.copy(draftWhitelist = next)) }
            return
        }
        if (next.isEmpty()) {
            store.show(SecurityMessage.Res(R.string.sec_a11y_toast_no_services))
            return
        }
        store.update { it.copy(a11y = it.a11y.copy(draftWhitelist = next)) }
        apply(next)
    }

    
    fun addAllServices() {
        val a11y = store.current.a11y
        if (a11y.applying) return
        val all = a11y.services.map { it.service }.toSet()
        if (all.isEmpty()) {
            store.show(SecurityMessage.Res(R.string.sec_a11y_toast_no_services))
            return
        }
        store.update { it.copy(a11y = it.a11y.copy(draftWhitelist = all)) }
        if (a11y.locked) apply(all)
    }

    private fun apply(whitelist: Set<String>) {
        store.update { it.copy(a11y = it.a11y.copy(applying = true)) }
        val serviceCount = store.current.a11y.services.size
        scope.launch {
            try {
                when (val result = guard.applyWhitelist(whitelist)) {
                    is XgResult.Ok -> {
                        store.update { it.copy(a11y = it.a11y.copy(applying = false)) }
                        load(forceRefresh = true)
                        if (whitelist.isEmpty()) {
                            A11yMonitor.stop(context)
                            store.show(SecurityMessage.Res(R.string.sec_a11y_toast_unlocked))
                        } else if (whitelist.size == serviceCount) {
                            store.show(
                                SecurityMessage.Res(
                                    R.string.sec_a11y_toast_locked,
                                    listOf(result.data.size),
                                ),
                            )
                        } else {
                            store.show(
                                SecurityMessage.Res(R.string.sec_a11y_toast_whitelist_updated),
                            )
                        }
                    }

                    is XgResult.Err -> {
                        store.update { it.copy(a11y = it.a11y.copy(applying = false)) }
                        store.show(SecurityMessage.Raw(result.message))
                        load(forceRefresh = true)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                store.update { it.copy(a11y = it.a11y.copy(applying = false)) }
                store.show(SecurityMessage.Raw(t.message ?: "写入无障碍白名单失败"))
                load(forceRefresh = true)
            }
        }
    }

    
    private fun parseService(service: String): A11yServiceUi {
        val separator = service.indexOf('/')
        return if (separator < 0) {
            A11yServiceUi(service = service, packageName = service, className = "")
        } else {
            A11yServiceUi(
                service = service,
                packageName = service.substring(0, separator),
                className = service.substring(separator + 1),
            )
        }
    }
}
