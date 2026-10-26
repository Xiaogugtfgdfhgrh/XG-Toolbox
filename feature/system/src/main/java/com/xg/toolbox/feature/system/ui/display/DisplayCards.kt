package com.xg.toolbox.feature.system.ui.display

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.feature.system.domain.display.DisplayState
import kotlin.math.roundToInt

@Composable
internal fun ResolutionCard(
    data: DisplayState,
    editable: Boolean,
    onApply: (String) -> Unit,
    onReset: () -> Unit,
) {
    var pendingSize by rememberSaveable(data.size, data.physicalSize) {
        mutableStateOf<String?>(null)
    }
    XgPanel {
        ResolutionControl(
            data = data,
            pendingSize = pendingSize,
            editable = editable,
            onSelectSize = { size -> pendingSize = size },
            onApplySize = {
                pendingSize?.let(onApply)
                pendingSize = null
            },
            onResetSize = {
                pendingSize = null
                onReset()
            },
        )
    }
}

@Composable
internal fun RefreshCard(
    data: DisplayState,
    editable: Boolean,
    onApply: (Float, Boolean) -> Unit,
) {
    val defaultHz = defaultSelectedHz(data)
    var selectedHz by rememberSaveable(data.currentPeakHz, data.refreshOptions) {
        mutableStateOf(defaultHz)
    }
    XgPanel {
        RefreshControl(
            data = data,
            selectedHz = selectedHz,
            editable = editable,
            onSelectHz = { hz -> selectedHz = hz },
            onApplyRefresh = {
                (selectedHz ?: defaultHz)?.let { hz -> onApply(hz, data.forceHighRefresh) }
            },
            onToggleForceHighRefresh = { enabled ->
                (selectedHz ?: defaultHz)?.let { hz -> onApply(hz, enabled) }
            },
        )
    }
}

@Composable
internal fun TouchSampleCard(
    data: DisplayState,
    editable: Boolean,
    onTouchSample: (Int?) -> Unit,
) {
    XgPanel {
        TouchSampleControl(
            data = data,
            editable = editable,
            onTouchSample = onTouchSample,
        )
    }
}

@Composable
internal fun DensityCard(
    data: DisplayState,
    editable: Boolean,
    onApply: (Int) -> Unit,
    onReset: () -> Unit,
) {
    var draft by rememberSaveable(data.density) { mutableIntStateOf(data.density) }
    XgPanel {
        DensityControl(
            data = data,
            densityDraft = draft,
            editable = editable,
            onUpdateDensity = { value -> draft = value },
            onApplyDensity = { onApply(draft) },
            onResetDensity = {
                draft = data.density
                onReset()
            },
        )
    }
}

@Composable
internal fun DcDimmingCard(
    data: DisplayState,
    editable: Boolean,
    onDcDimming: (Boolean) -> Unit,
) {
    XgPanel {
        DcDimmingControl(
            data = data,
            editable = editable,
            onDcDimming = onDcDimming,
        )
    }
}

@Composable
internal fun ColorTemperatureCard(
    data: DisplayState,
    editable: Boolean,
    onApply: (Int) -> Unit,
) {
    var draft by rememberSaveable(data.colorTemperature) {
        mutableIntStateOf(data.colorTemperature ?: COLOR_TEMP_DEFAULT_K)
    }
    XgPanel {
        ColorTemperatureControl(
            data = data,
            colorTempDraft = draft,
            editable = editable,
            onUpdateColorTemperature = { value -> draft = value },
            onApplyColorTemperature = { onApply(draft) },
        )
    }
}

@Composable
internal fun NightModeCard(
    data: DisplayState,
    editable: Boolean,
    onNightMode: (String) -> Unit,
) {
    XgPanel {
        NightModeControl(
            data = data,
            editable = editable,
            onNightMode = onNightMode,
        )
    }
}

private fun defaultSelectedHz(data: DisplayState): Float? {
    val options = data.refreshOptions
    if (options.isEmpty()) return null
    val peak = data.currentPeakHz?.roundToInt()
    val hz = if (peak != null && options.contains(peak)) peak else options.max()
    return hz.toFloat()
}