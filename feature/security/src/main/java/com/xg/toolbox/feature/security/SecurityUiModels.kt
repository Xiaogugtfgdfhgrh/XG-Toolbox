package com.xg.toolbox.feature.security

import androidx.compose.runtime.Immutable
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppEntry
import com.xg.toolbox.core.shell.RootInfo
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.feature.security.domain.AdHit
import com.xg.toolbox.feature.security.domain.AdHostsStatus
import com.xg.toolbox.feature.security.domain.AppOpDefinition
import com.xg.toolbox.feature.security.domain.AppOpState
import com.xg.toolbox.feature.security.domain.BootReceiverInfo
import com.xg.toolbox.feature.security.domain.VendorAutostartManager

enum class SecurityPage {
    OVERVIEW,
    MATRIX,
    A11Y,
    AUTOSTART,
    ADS,
}

enum class MatrixFilter { USER, SYSTEM, ALL }

data class UiError(
    val code: Int?,
    val message: String,
    val detail: String? = null,
) {
    companion object {
        fun of(err: XgResult.Err): UiError = UiError(err.code, err.message, err.detail)

        fun of(throwable: Throwable, fallback: String): UiError =
            UiError(code = null, message = throwable.message?.takeIf { it.isNotBlank() } ?: fallback)
    }
}

sealed interface SecurityMessage {
    data class Raw(val text: String) : SecurityMessage

    data class Res(val resId: Int, val args: List<Any> = emptyList()) : SecurityMessage

    
    data class AppOpUpdated(
        val appLabel: String,
        val opLabel: String,
        val state: AppOpState,
    ) : SecurityMessage
}

sealed interface SecurityDialog {
    
    data object ConfirmA11yUnlock : SecurityDialog

    
    data class ConfirmAdFreeze(val packageName: String, val label: String) : SecurityDialog

    
    data class ConfirmAdUnfreeze(val packageName: String, val label: String) : SecurityDialog

    
    data class ConfirmAdUninstall(val packageName: String, val label: String) : SecurityDialog

    
    data object ConfirmAdHostsDisable : SecurityDialog
}

data class RootCardState(
    val state: RootState = RootState.Unknown,
    val info: RootInfo? = null,
    val refreshing: Boolean = false,
    val evidence: List<String> = emptyList(),
)

@Immutable
data class MatrixCellUi(
    val op: String,
    val label: String,
    val state: AppOpState?,
    val pending: Boolean = false,
)

@Immutable
data class MatrixRowUi(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val cells: List<MatrixCellUi>,
    val error: String? = null,
)

@Immutable
data class MatrixUiState(
    val definitions: List<AppOpDefinition> = emptyList(),
    val rows: List<MatrixRowUi> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: UiError? = null,
    val query: String = "",
    val filter: MatrixFilter = MatrixFilter.USER,
    val visibleCount: Int = 0,
    val filteredCount: Int = 0,
    val systemSampleSize: Int = 0,
    val systemTotal: Int = 0,
    val loaded: Boolean = false,
)

data class A11yServiceUi(
    val service: String,
    val packageName: String,
    val className: String,
)

data class A11yUiState(
    val loading: Boolean = true,
    val error: UiError? = null,
    val services: List<A11yServiceUi> = emptyList(),
    val locked: Boolean = false,
    val whitelist: Set<String> = emptySet(),
    val draftWhitelist: Set<String> = emptySet(),
    val hasWritePermission: Boolean = true,
    val applying: Boolean = false,
    val loaded: Boolean = false,
    val monitorStartRequested: Boolean = false,
)

data class AutostartGroup(
    val packageName: String,
    val label: String,
    val receivers: List<BootReceiverInfo>,
)

data class AutostartUiState(
    val loading: Boolean = false,
    val error: UiError? = null,
    val groups: List<AutostartGroup> = emptyList(),
    val totalReceivers: Int = 0,
    val expanded: Set<String> = emptySet(),
    val pending: Set<String> = emptySet(),
    val loaded: Boolean = false,
    
    val vendorManagers: List<VendorAutostartManager> = emptyList(),
)

data class AdSdkGroup(
    val sdk: String,
    val hits: List<AdHit>,
)

enum class AdActionKind { FREEZE, UNFREEZE, UNINSTALL }

@Immutable
data class AdActionOutcome(
    val kind: AdActionKind,
    val stillHits: Boolean?,
    val hits: Int = 0,
    val error: String? = null,
)

data class AdsUiState(
    val scanning: Boolean = false,
    val scanned: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val groups: List<AdSdkGroup> = emptyList(),
    val totalHits: Int = 0,
    val appCount: Int = 0,
    val sdkCount: Int = 0,
    val error: UiError? = null,
    val freezing: String? = null,
    val unfreezing: String? = null,
    val uninstalling: String? = null,
    val frozen: Set<String> = emptySet(),
    
    val outcomes: Map<String, AdActionOutcome> = emptyMap(),
    
    val rescanning: Set<String> = emptySet(),
)

@Immutable
data class AdHostsUiState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val status: AdHostsStatus? = null,
    val applying: Boolean = false,
    val disabling: Boolean = false,
    val error: UiError? = null,
)

data class SecurityUiState(
    val root: RootCardState = RootCardState(),
    val apps: List<AppEntry> = emptyList(),
    val appsLoading: Boolean = true,
    val appsError: UiError? = null,
    val matrix: MatrixUiState = MatrixUiState(),
    val a11y: A11yUiState = A11yUiState(),
    val autostart: AutostartUiState = AutostartUiState(),
    val ads: AdsUiState = AdsUiState(),
    val adHosts: AdHostsUiState = AdHostsUiState(),
    val dialog: SecurityDialog? = null,
) {
    
    val rootGranted: Boolean get() = root.state is RootState.Granted
}
