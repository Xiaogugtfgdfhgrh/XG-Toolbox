package com.xg.toolbox.feature.system.ui.perf

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgToggleRow
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.perf.PerfControl
import com.xg.toolbox.feature.system.domain.perf.PerfState

@Composable
internal fun PerfControlCard(
    control: PerfControl,
    readOnly: Boolean,
    busy: Boolean,
    onRequestApply: (String) -> Unit,
) {
    var pendingChoiceId by rememberSaveable(control.id, control.current) {
        mutableStateOf<String?>(null)
    }
    XgPanel {
        PerfControlBlock(
            control = control,
            pendingChoiceId = pendingChoiceId,
            readOnly = readOnly,
            busy = busy,
            onSelectChoice = { choiceId -> pendingChoiceId = choiceId },
            onApply = {
                pendingChoiceId?.let(onRequestApply)
            },
        )
    }
}

@Composable
internal fun MemoryCompressionCard(
    data: PerfState,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val label = stringResource(R.string.sys_tune_perf_memory)
    val unavailable = stringResource(R.string.sys_tune_value_unavailable)
    val memoryState = data.summary.entries.firstOrNull { it.key == label }?.value
    val available = memoryState?.contains(unavailable) != true
    XgPanel {
        XgToggleRow(
            name = label,
            note = if (available) {
                stringResource(R.string.sys_tune_perf_memory_note)
            } else {
                stringResource(R.string.sys_tune_perf_memory_unavailable)
            },
            checked = data.memoryCompression,
            onCheckedChange = onToggle,
            enabled = enabled && available,
        )
    }
}

@Composable
internal fun ThermalGuardCard(
    data: PerfState,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val thermalKey = stringResource(R.string.sys_tune_perf_thermal_key)
    val unavailable = stringResource(R.string.sys_tune_value_unavailable)
    val thermalState = data.summary.entries.firstOrNull { it.key.contains(thermalKey) }?.value
    val available = thermalState?.contains(unavailable) != true
    XgPanel {
        XgToggleRow(
            name = stringResource(R.string.sys_tune_perf_thermal),
            note = if (available) {
                stringResource(R.string.sys_tune_perf_thermal_note)
            } else {
                stringResource(R.string.sys_tune_perf_thermal_unavailable)
            },
            checked = data.thermalGuard,
            onCheckedChange = onToggle,
            enabled = enabled && available,
        )
    }
}