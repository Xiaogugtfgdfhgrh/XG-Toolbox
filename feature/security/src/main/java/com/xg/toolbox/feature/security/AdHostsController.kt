package com.xg.toolbox.feature.security

import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.feature.security.domain.AdHostsBlocker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AdHostsController(
    private val scope: CoroutineScope,
    private val store: SecurityStateStore,
    private val blocker: AdHostsBlocker,
) {

    
    fun ensureLoaded() {
        val state = store.current.adHosts
        if (state.loaded || state.loading) return
        load(forceRefresh = false)
    }

    fun refresh() = load(forceRefresh = true)

    private fun load(forceRefresh: Boolean) {
        if (store.current.adHosts.loading) return
        store.update { it.copy(adHosts = it.adHosts.copy(loading = true, error = null)) }
        scope.launch {
            try {
                val status = blocker.status(forceRefresh = forceRefresh)
                store.update {
                    it.copy(
                        adHosts = it.adHosts.copy(
                            loading = false,
                            loaded = true,
                            status = status,
                            error = null,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                store.update {
                    it.copy(
                        adHosts = it.adHosts.copy(
                            loading = false,
                            error = UiError.of(t, "读取域名拦截状态失败"),
                        ),
                    )
                }
            }
        }
    }

    fun apply() {
        if (busy()) return
        store.update { it.copy(adHosts = it.adHosts.copy(applying = true, error = null)) }
        scope.launch {
            try {
                when (val result = blocker.apply()) {
                    is XgResult.Ok -> {
                        store.update {
                            it.copy(
                                adHosts = it.adHosts.copy(
                                    applying = false,
                                    loaded = true,
                                    status = result.data,
                                    error = null,
                                ),
                            )
                        }
                        store.show(
                            SecurityMessage.Res(
                                R.string.sec_adh_toast_enabled,
                                listOf(result.data.appliedCount),
                            ),
                        )
                    }

                    is XgResult.Err -> {
                        store.update { it.copy(adHosts = it.adHosts.copy(applying = false)) }
                        store.show(SecurityMessage.Raw(result.message))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                store.update { it.copy(adHosts = it.adHosts.copy(applying = false)) }
                store.show(SecurityMessage.Raw(t.message ?: "启用域名拦截失败"))
            }
        }
    }

    fun requestDisable() {
        val status = store.current.adHosts.status
        if (status == null) {
            store.show(SecurityMessage.Res(R.string.sec_adh_status_unavailable))
            return
        }
        if (!status.enabled) {
            store.show(SecurityMessage.Res(R.string.sec_adh_not_enabled))
            return
        }
        store.update { it.copy(dialog = SecurityDialog.ConfirmAdHostsDisable) }
    }

    fun confirmDisable() {
        if (store.current.dialog !is SecurityDialog.ConfirmAdHostsDisable) return
        if (busy()) return
        store.update {
            it.copy(
                dialog = null,
                adHosts = it.adHosts.copy(disabling = true, error = null),
            )
        }
        scope.launch {
            try {
                when (val result = blocker.disable()) {
                    is XgResult.Ok -> {
                        store.update {
                            it.copy(
                                adHosts = it.adHosts.copy(
                                    disabling = false,
                                    loaded = true,
                                    status = result.data,
                                    error = null,
                                ),
                            )
                        }
                        store.show(SecurityMessage.Res(R.string.sec_adh_toast_disabled))
                    }

                    is XgResult.Err -> {
                        store.update { it.copy(adHosts = it.adHosts.copy(disabling = false)) }
                        store.show(SecurityMessage.Raw(result.message))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                store.update { it.copy(adHosts = it.adHosts.copy(disabling = false)) }
                store.show(SecurityMessage.Raw(t.message ?: "关闭域名拦截失败"))
            }
        }
    }

    private fun busy(): Boolean {
        val state = store.current.adHosts
        return state.applying || state.disabling || state.loading
    }
}
