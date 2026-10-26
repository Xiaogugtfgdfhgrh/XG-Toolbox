package com.xg.toolbox.feature.security

import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppRepository
import com.xg.toolbox.feature.security.domain.AdScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class AdScanController(
    private val scope: CoroutineScope,
    private val store: SecurityStateStore,
    private val scanner: AdScanner,
    private val appRepository: AppRepository,
) {

    fun scan() {
        if (store.current.ads.scanning) return
        store.update {
            it.copy(
                ads = it.ads.copy(
                    scanning = true,
                    scanned = false,
                    done = 0,
                    total = 0,
                    groups = emptyList(),
                    totalHits = 0,
                    appCount = 0,
                    sdkCount = 0,
                    error = null,
                    outcomes = emptyMap(),
                    rescanning = emptySet(),
                ),
            )
        }
        scope.launch {
            try {
                val result = scanner.scan(packages = null) { done, total ->
                    store.update { it.copy(ads = it.ads.copy(done = done, total = total)) }
                }
                when (result) {
                    is XgResult.Ok -> {
                        val hits = result.data
                        val groups = hits
                            .groupBy { it.sdk }
                            .map { (sdk, sdkHits) ->
                                AdSdkGroup(
                                    sdk = sdk,
                                    hits = sdkHits.sortedBy { hit -> hit.appLabel.lowercase() },
                                )
                            }
                            .sortedWith(
                                compareByDescending<AdSdkGroup> { it.hits.size }
                                    .thenBy { it.sdk },
                            )
                        val frozen = when (val apps = appRepository.list()) {
                            is XgResult.Ok -> apps.data
                                .filter { app -> app.isFrozen }
                                .map { app -> app.packageName }
                                .toSet()

                            is XgResult.Err -> emptySet()
                        }
                        store.update {
                            it.copy(
                                ads = it.ads.copy(
                                    scanning = false,
                                    scanned = true,
                                    groups = groups,
                                    totalHits = hits.size,
                                    appCount = hits.map { hit -> hit.packageName }.distinct().size,
                                    sdkCount = groups.size,
                                    frozen = frozen,
                                    error = null,
                                ),
                            )
                        }
                    }

                    is XgResult.Err -> {
                        store.update {
                            it.copy(
                                ads = it.ads.copy(
                                    scanning = false,
                                    scanned = false,
                                    error = UiError.of(result),
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
                        ads = it.ads.copy(
                            scanning = false,
                            error = UiError.of(t, "广告组件扫描失败"),
                        ),
                    )
                }
            }
        }
    }

    fun requestFreeze(packageName: String, label: String) {
        if (!store.current.rootGranted) {
            store.show(SecurityMessage.Res(R.string.sec_ads_need_root))
            return
        }
        store.update {
            it.copy(dialog = SecurityDialog.ConfirmAdFreeze(packageName, label))
        }
    }

    fun confirmFreeze() {
        val dialog = store.current.dialog as? SecurityDialog.ConfirmAdFreeze ?: return
        store.update { it.copy(dialog = null, ads = it.ads.copy(freezing = dialog.packageName)) }
        scope.launch {
            try {
                when (val result = appRepository.freeze(dialog.packageName)) {
                    is XgResult.Ok -> {
                        store.update {
                            it.copy(
                                ads = it.ads.copy(
                                    freezing = null,
                                    frozen = it.ads.frozen + dialog.packageName,
                                ),
                            )
                        }
                        store.show(
                            SecurityMessage.Res(
                                R.string.sec_ads_toast_frozen,
                                listOf(dialog.label),
                            ),
                        )
                        rescanAfterAction(dialog.packageName, AdActionKind.FREEZE)
                    }

                    is XgResult.Err -> {
                        store.update { it.copy(ads = it.ads.copy(freezing = null)) }
                        store.show(SecurityMessage.Raw(result.message))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                store.update { it.copy(ads = it.ads.copy(freezing = null)) }
                store.show(SecurityMessage.Raw(t.message ?: "冻结应用失败"))
            }
        }
    }

    fun requestUnfreeze(packageName: String, label: String) {
        if (!store.current.rootGranted) {
            store.show(SecurityMessage.Res(R.string.sec_ads_need_root))
            return
        }
        store.update {
            it.copy(dialog = SecurityDialog.ConfirmAdUnfreeze(packageName, label))
        }
    }

    fun confirmUnfreeze() {
        val dialog = store.current.dialog as? SecurityDialog.ConfirmAdUnfreeze ?: return
        store.update { it.copy(dialog = null, ads = it.ads.copy(unfreezing = dialog.packageName)) }
        scope.launch {
            try {
                when (val result = appRepository.unfreeze(dialog.packageName)) {
                    is XgResult.Ok -> {
                        store.update {
                            it.copy(
                                ads = it.ads.copy(
                                    unfreezing = null,
                                    frozen = it.ads.frozen - dialog.packageName,
                                ),
                            )
                        }
                        store.show(
                            SecurityMessage.Res(
                                R.string.sec_ads_toast_unfrozen,
                                listOf(dialog.label),
                            ),
                        )
                        rescanAfterAction(dialog.packageName, AdActionKind.UNFREEZE)
                    }

                    is XgResult.Err -> {
                        store.update { it.copy(ads = it.ads.copy(unfreezing = null)) }
                        store.show(SecurityMessage.Raw(result.message))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                store.update { it.copy(ads = it.ads.copy(unfreezing = null)) }
                store.show(SecurityMessage.Raw(t.message ?: "解冻应用失败"))
            }
        }
    }

    fun requestUninstall(packageName: String, label: String) {
        if (!store.current.rootGranted) {
            store.show(SecurityMessage.Res(R.string.sec_ads_need_root))
            return
        }
        store.update {
            it.copy(dialog = SecurityDialog.ConfirmAdUninstall(packageName, label))
        }
    }

    fun confirmUninstall() {
        val dialog = store.current.dialog as? SecurityDialog.ConfirmAdUninstall ?: return
        store.update { it.copy(dialog = null, ads = it.ads.copy(uninstalling = dialog.packageName)) }
        scope.launch {
            try {
                when (val result = appRepository.uninstall(dialog.packageName)) {
                    is XgResult.Ok -> {
                        store.update { state ->
                            val groups = state.ads.groups.mapNotNull { group ->
                                val hits = group.hits.filterNot { hit ->
                                    hit.packageName == dialog.packageName
                                }
                                if (hits.isEmpty()) null else group.copy(hits = hits)
                            }
                            state.copy(
                                ads = state.ads.copy(
                                    uninstalling = null,
                                    groups = groups,
                                    totalHits = groups.sumOf { group -> group.hits.size },
                                    appCount = groups
                                        .flatMap { group -> group.hits }
                                        .map { hit -> hit.packageName }
                                        .distinct()
                                        .size,
                                    sdkCount = groups.size,
                                    frozen = state.ads.frozen - dialog.packageName,
                                ),
                            )
                        }
                        store.show(
                            SecurityMessage.Res(
                                R.string.sec_ads_toast_uninstalled,
                                listOf(dialog.label),
                            ),
                        )
                        rescanAfterAction(dialog.packageName, AdActionKind.UNINSTALL)
                    }

                    is XgResult.Err -> {
                        store.update { it.copy(ads = it.ads.copy(uninstalling = null)) }
                        store.show(SecurityMessage.Raw(result.message))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                store.update { it.copy(ads = it.ads.copy(uninstalling = null)) }
                store.show(SecurityMessage.Raw(t.message ?: "卸载应用失败"))
            }
        }
    }

    
    private fun rescanAfterAction(packageName: String, kind: AdActionKind) {
        store.update {
            it.copy(
                ads = it.ads.copy(
                    uninstalling = null,
                    rescanning = it.ads.rescanning + packageName,
                    outcomes = it.ads.outcomes - packageName,
                ),
            )
        }
        scope.launch {
            val outcome = try {
                when (val result = scanner.scan(packages = listOf(packageName))) {
                    is XgResult.Ok -> AdActionOutcome(
                        kind = kind,
                        stillHits = result.data.isNotEmpty(),
                        hits = result.data.size,
                    )

                    is XgResult.Err -> AdActionOutcome(
                        kind = kind,
                        stillHits = null,
                        error = result.message,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AdActionOutcome(
                    kind = kind,
                    stillHits = null,
                    error = t.message ?: "重扫失败",
                )
            }
            store.update {
                it.copy(
                    ads = it.ads.copy(
                        rescanning = it.ads.rescanning - packageName,
                        outcomes = it.ads.outcomes + (packageName to outcome),
                    ),
                )
            }
        }
    }
}
