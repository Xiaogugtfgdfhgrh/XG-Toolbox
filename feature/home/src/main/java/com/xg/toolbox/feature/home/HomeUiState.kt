package com.xg.toolbox.feature.home

import androidx.compose.runtime.Immutable
import com.xg.toolbox.core.data.AuditEntity
import com.xg.toolbox.core.device.DeviceField
import com.xg.toolbox.core.device.RebootTargetInfo
import com.xg.toolbox.core.device.RiskItem
import com.xg.toolbox.core.shell.RootInfo
import com.xg.toolbox.core.shell.RootState

@Immutable
data class HomeSummaryUi(
    val name: String,
    val meta: String,
    val unlocked: Boolean,
    val rooted: Boolean,
)

@Immutable
data class HomeUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val summary: HomeSummaryUi? = null,
    
    val dataVersion: Int = 0,
    val fields: List<DeviceField> = emptyList(),
    val risks: List<RiskItem> = emptyList(),
    val riskLoaded: Boolean = false,
    val riskError: String? = null,
    val recent: List<AuditEntity> = emptyList(),
    val recentLoaded: Boolean = false,
    val recentError: String? = null,
    val expandedAuditId: Long? = null,
    val rebootTargets: List<RebootTargetInfo> = emptyList(),
    val pendingReboot: RebootTargetInfo? = null,
    val rebooting: Boolean = false,
    val readOnly: Boolean = false,
    val rootState: RootState = RootState.Unknown,
    val rootInfo: RootInfo? = null,
)

sealed interface HomeEvent {
    
    data object RebootSent : HomeEvent

    
    data class Error(val message: String) : HomeEvent
}
