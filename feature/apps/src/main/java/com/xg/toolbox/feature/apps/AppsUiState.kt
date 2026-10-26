package com.xg.toolbox.feature.apps

import androidx.compose.runtime.Immutable
import java.util.Locale
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppDetail
import com.xg.toolbox.core.data.AppEntry
import com.xg.toolbox.core.data.UninstallOutcome
import com.xg.toolbox.feature.apps.domain.AbiStatus
import com.xg.toolbox.feature.apps.domain.BatchAction
import com.xg.toolbox.feature.apps.domain.BatchPreflight
import com.xg.toolbox.feature.apps.domain.BatchProgress
import com.xg.toolbox.feature.apps.domain.BatchReceipt
import com.xg.toolbox.feature.apps.domain.ModuleInfo

sealed interface AppsPage {
    data object Overview : AppsPage
    data class Detail(val packageName: String) : AppsPage
    data object Modules : AppsPage
    data object Batch : AppsPage
    data object AndroidId : AppsPage
}

enum class AppsFilter { ALL, USER, SYSTEM, FROZEN }

enum class AppsSort { NAME, MEMORY, INSTALL_TIME }

sealed interface AbiLabel {
    data object Bit64 : AbiLabel
    data object Bit32 : AbiLabel
    data class Raw(val value: String) : AbiLabel
    data object Unknown : AbiLabel
}

@Immutable
data class AppRowUi(
    val packageName: String,
    val label: String,
    val labelLower: String,
    val packageLower: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val frozen: Boolean,
    val abiLabel: AbiLabel,
    val sizeText: String,
    val installedAt: Long,
    val apkSizeBytes: Long,
    val memoryBytes: Long?,
)

sealed interface MemoryState {
    data object NotRunning : MemoryState
    data class Running(val bytes: Long) : MemoryState
    data object Unavailable : MemoryState
}

sealed interface TrafficState {
    data class Available(val rxBytes: Long, val txBytes: Long, val windowDays: Int) : TrafficState
    data object Unauthorized : TrafficState
    data object Unavailable : TrafficState
}

@Immutable
data class AppDetailUi(
    val detail: AppDetail,
    val memory: MemoryState?,
    val memoryLoading: Boolean,
    val traffic: TrafficState?,
    val trafficLoading: Boolean,
    val usageError: XgResult.Err?,
    val abiStatus: AbiStatus?,
    val abiLoading: Boolean,
    val abiError: XgResult.Err?,
)

@Immutable
data class PendingUndo(
    val packageName: String,
    val label: String,
    val outcome: UninstallOutcome,
    val expiresAt: Long,
)

@Immutable
data class InFlightAction(val packageName: String, val kind: ActionKind)

enum class ActionKind { FREEZE, UNFREEZE, UNINSTALL, UNDO_UNINSTALL, ABI, SSAID, CLEAR_CACHE, CLEAR_DATA }

enum class BatchStep { SELECT, PREFLIGHT, EXECUTING, RECEIPT }

@Immutable
data class BatchUiState(
    val step: BatchStep = BatchStep.SELECT,
    val action: BatchAction = BatchAction.FREEZE,
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val visible: List<AppRowUi> = emptyList(),
    val preflight: BatchPreflight? = null,
    val preflightLoading: Boolean = false,
    val preflightError: XgResult.Err? = null,
    val progress: BatchProgress? = null,
    val executing: Boolean = false,
    val receipt: BatchReceipt? = null,
    val receiptError: String? = null,
    val undoRunning: Boolean = false,
    val undoResult: String? = null,
    val undoError: String? = null,
) {
    val canStart: Boolean get() = !preflightLoading && preflight?.canRun == true
}

@Immutable
data class ModulesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: XgResult.Err? = null,
    val modules: List<ModuleInfo> = emptyList(),
    val changingId: String? = null,
    val removingId: String? = null,
    val lastActionError: String? = null,
)

@Immutable
data class GlobalIdResult(
    val rewrittenEntries: Int,
    val totalEntries: Int,
    val requiresReboot: Boolean,
)

@Immutable
data class AndroidIdUiState(
    val userApps: List<AppRowUi> = emptyList(),
    val appsLoading: Boolean = true,
    val appsError: XgResult.Err? = null,
    val query: String = "",
    val visibleApps: List<AppRowUi> = emptyList(),
    val targetPackage: String? = null,
    val targetLabel: String? = null,
    val currentSsaid: String? = null,
    val currentSsaidLoading: Boolean = false,
    val currentSsaidError: XgResult.Err? = null,
    val globalInput: String = "",
    val globalError: String? = null,
    val globalApplying: Boolean = false,
    val globalEntryCount: Int? = null,
    val globalEntryCountLoading: Boolean = false,
    val globalEntryCountError: XgResult.Err? = null,
    val globalOutcome: GlobalIdResult? = null,
    val ssaidError: String? = null,
    val ssaidApplying: Boolean = false,
    val ssaidApplied: Boolean = false,
)

@Immutable
data class ActionFailure(
    val packageName: String,
    val code: Int,
    val message: String,
    val detail: String?,
)

sealed interface AppsDialog {
    data class UninstallConfirm(
        val packageName: String,
        val label: String,
        val apkSizeBytes: Long,
        val backupLimitBytes: Long,
    ) : AppsDialog

    data class AbiConfirm(
        val packageName: String,
        val label: String,
        val currentAbi: String?,
        val targetAbi: String,
    ) : AppsDialog

    data class ClearCacheConfirm(
        val packageName: String,
        val label: String,
    ) : AppsDialog

    data class ClearDataConfirm(
        val packageName: String,
        val label: String,
    ) : AppsDialog

    data class ModuleRemoveConfirm(val module: ModuleInfo) : AppsDialog

    data class SsaidConfirm(
        val packageName: String,
        val label: String,
    ) : AppsDialog

    data class GlobalIdConfirm(
        val id16: String,
        val entryCount: Int,
    ) : AppsDialog
}

@Immutable
data class AppsUiState(
    val rows: List<AppRowUi> = emptyList(),
    val visibleRows: List<AppRowUi> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val listError: XgResult.Err? = null,
    val refreshError: XgResult.Err? = null,
    val query: String = "",
    val filter: AppsFilter = AppsFilter.ALL,
    val sort: AppsSort = AppsSort.NAME,
    val frozenCount: Int = 0,
    val detailPackage: String? = null,
    val detail: AppDetailUi? = null,
    val detailLoading: Boolean = false,
    val detailError: XgResult.Err? = null,
    val actionFailure: ActionFailure? = null,
    val inFlight: InFlightAction? = null,
    val pendingUndo: PendingUndo? = null,
    val batch: BatchUiState = BatchUiState(),
    val modules: ModulesUiState = ModulesUiState(),
    val androidId: AndroidIdUiState = AndroidIdUiState(),
    val dialog: AppsDialog? = null,
    val rebootPrompt: String? = null,
    val rootGranted: Boolean = false,
    val readOnly: Boolean = false,
) {
    val canWrite: Boolean get() = rootGranted && !readOnly

    val writeBlockedReason: String?
        get() = when {
            !rootGranted -> REASON_ROOT_REQUIRED
            readOnly -> REASON_READONLY
            else -> null
        }

    val hasNoAppsAtAll: Boolean get() = rows.isEmpty()

    companion object {
        const val REASON_ROOT_REQUIRED = "root_required"
        const val REASON_READONLY = "readonly"
    }
}

fun AppEntry.toRowUi(memoryBytes: Long? = null): AppRowUi = AppRowUi(
    packageName = packageName,
    label = label,
    labelLower = label.lowercase(Locale.US),
    packageLower = packageName.lowercase(Locale.US),
    versionCode = versionCode,
    isSystem = isSystem,
    frozen = isFrozen,
    abiLabel = abiLabelOf(primaryAbi),
    sizeText = if (apkSizeBytes > 0) Formatters.bytes(apkSizeBytes) else "—",
    installedAt = installedAt,
    apkSizeBytes = apkSizeBytes,
    memoryBytes = memoryBytes,
)

fun abiLabelOf(abi: String?): AbiLabel = when {
    abi == null -> AbiLabel.Unknown
    abi == "arm64-v8a" || abi == "x86_64" -> AbiLabel.Bit64
    abi == "armeabi-v7a" || abi == "armeabi" || abi == "x86" -> AbiLabel.Bit32
    else -> AbiLabel.Raw(abi)
}

fun com.xg.toolbox.core.data.AppUsage.toTrafficState(): TrafficState {
    val rx = trafficRxBytes
    val tx = trafficTxBytes
    return if (rx != null || tx != null) {
        TrafficState.Available(rxBytes = rx ?: 0L, txBytes = tx ?: 0L, windowDays = windowDays)
    } else if (trafficReason != null) {
        TrafficState.Unauthorized
    } else {
        TrafficState.Unavailable
    }
}

fun com.xg.toolbox.core.data.AppUsage.toMemoryState(): MemoryState {
    val bytes = memoryBytes
    return when {
        !memoryRunning -> MemoryState.NotRunning
        bytes == null -> MemoryState.Unavailable
        else -> MemoryState.Running(bytes)
    }
}

