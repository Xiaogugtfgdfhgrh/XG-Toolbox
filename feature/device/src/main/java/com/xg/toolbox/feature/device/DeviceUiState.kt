package com.xg.toolbox.feature.device

import androidx.compose.runtime.Immutable
import com.xg.toolbox.core.device.FieldRestriction

@Immutable
data class DeviceFieldUi(
    val id: String,
    val ordinal: Int,
    val name: String,
    val propertyKey: String,
    val value: String?,
    val restriction: FieldRestriction,
    val note: String?,
)

@Immutable
data class DeviceGroupUi(
    val id: String,
    val ordinal: Int,
    val title: String,
    val fields: List<DeviceFieldUi>,
    val preview: String,
    val matchCount: Int = 0,
    val matchedFieldIds: Set<String> = emptySet(),
)

@Immutable
data class SerialEditUi(
    val visible: Boolean = false,
    val currentSerial: String? = null,
    val input: String = "",
    val inputError: String? = null,
    val loading: Boolean = false,
    val supported: Boolean = false,
    val resetpropPath: String? = null,
    val capabilityReason: String? = null,
    val capabilityError: String? = null,
    val submitting: Boolean = false,
    val submitError: String? = null,
)

sealed interface DeviceEvent {
    data class SerialChanged(
        val before: String?,
        val after: String?,
        val requiresReboot: Boolean,
    ) : DeviceEvent
}

internal val DeviceFieldUi.isSerialNumberField: Boolean
    get() = id == "identity.serial" || propertyKey == "ro.serialno"

internal val DeviceFieldUi.isImeiField: Boolean
    get() = id == "network.imei" || propertyKey == "telephony.imei"

internal val DeviceFieldUi.isUnmaskedIdentityField: Boolean
    get() = isSerialNumberField || isImeiField

@Immutable
data class DeviceSummaryUi(
    val name: String,
    val meta: String,
    val unlocked: Boolean,
    val rooted: Boolean,
)

@Immutable
data class DeviceUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val groups: List<DeviceGroupUi> = emptyList(),
    val visibleGroups: List<DeviceGroupUi> = emptyList(),
    val summary: DeviceSummaryUi? = null,
    val query: String = "",
    val totalFields: Int = 0,
    val error: String? = null,
    val dataVersion: Int = 0,
    val serialEdit: SerialEditUi = SerialEditUi(),
) {
    
    val hasData: Boolean get() = groups.isNotEmpty()

    
    val isSearching: Boolean get() = query.isNotBlank()

    
    val matchTotal: Int get() = if (isSearching) visibleGroups.sumOf { it.matchCount } else 0
}
