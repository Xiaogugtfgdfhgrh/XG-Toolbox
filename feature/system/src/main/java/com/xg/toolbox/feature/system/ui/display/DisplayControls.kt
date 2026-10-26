package com.xg.toolbox.feature.system.ui.display

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgChoiceRow
import com.xg.toolbox.core.ui.component.XgEmptyState
import com.xg.toolbox.core.ui.component.XgErrorState
import com.xg.toolbox.core.ui.component.XgGhostButton
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgStatusChip
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgToggleRow
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.display.DisplayState
import kotlin.math.roundToInt

@Composable
internal fun ResolutionControl(
    data: DisplayState,
    pendingSize: String?,
    editable: Boolean,
    onSelectSize: (String) -> Unit,
    onApplySize: () -> Unit,
    onResetSize: () -> Unit,
) {
    val colors = LocalXgColors.current
    val options = remember(data.size, data.physicalSize) { buildResolutionOptions(data) }
    val currentKey = parseResolution(data.size)?.let { (width, height) -> "${width}x$height" }
    val selectedKey = pendingSize ?: currentKey
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = XgSpacing.sm)) {
        ControlHeader(
            name = stringResource(R.string.sys_tune_display_resolution),
            note = stringResource(R.string.sys_tune_display_resolution_note),
            value = data.size.ifBlank { stringResource(R.string.sys_tune_value_none) },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        if (options.isEmpty()) {
            XgNote(
                text = stringResource(R.string.sys_tune_display_resolution_unavailable),
                tone = XgTone.Warn,
            )
        } else {
            TuneChipRow(
                options = options.map { option ->
                    TuneChipOption(
                        key = option.key,
                        label = resolutionLabel(option),
                        enabled = editable,
                    )
                },
                selectedKey = selectedKey,
                onSelect = { option -> onSelectSize(option.key) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pendingSize != null && pendingSize != currentKey) {
                XgMono(
                    text = stringResource(R.string.sys_tune_display_pending),
                    style = XgType.monoSmall,
                    color = colors.accent,
                )
            }
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.sys_tune_action_apply),
                onClick = onApplySize,
                enabled = editable && pendingSize != null && pendingSize != currentKey,
            )
            Spacer(Modifier.width(XgSpacing.xs))
            XgTextAction(
                text = stringResource(R.string.sys_tune_action_restore_defaults),
                onClick = onResetSize,
                enabled = editable,
                tone = XgTone.Muted,
            )
        }
    }
}

@Composable
internal fun RefreshControl(
    data: DisplayState,
    selectedHz: Float?,
    editable: Boolean,
    onSelectHz: (Float) -> Unit,
    onApplyRefresh: () -> Unit,
    onToggleForceHighRefresh: (Boolean) -> Unit,
) {
    val colors = LocalXgColors.current
    val options = remember(data.refreshOptions) { data.refreshOptions.distinct().sorted() }
    val has185 = options.contains(185)
    val chips = buildList {
        options.forEach { hz ->
            add(
                TuneChipOption(
                    key = hz.toString(),
                    label = stringResource(R.string.sys_tune_display_hz_option, hz),
                    enabled = editable,
                ),
            )
        }
        if (!has185) {
            add(
                TuneChipOption(
                    key = "185",
                    label = stringResource(R.string.sys_tune_display_hz_option, 185),
                    enabled = false,
                ),
            )
        }
    }
    val selectedKey = selectedHz?.roundToInt()?.toString()

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = XgSpacing.sm)) {
        ControlHeader(
            name = stringResource(R.string.sys_tune_display_refresh),
            note = stringResource(R.string.sys_tune_display_refresh_note),
            value = hzText(data.currentPeakHz),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        TuneChipRow(
            options = chips,
            selectedKey = selectedKey,
            onSelect = { option ->
                option.key.toFloatOrNull()?.let(onSelectHz)
            },
        )
        when {
            options.isEmpty() -> {
                Spacer(Modifier.height(XgSpacing.xs))
                XgNote(
                    text = stringResource(R.string.sys_tune_display_hz_none),
                    tone = XgTone.Warn,
                )
            }

            !has185 -> {
                Spacer(Modifier.height(XgSpacing.xs))
                XgNote(
                    text = stringResource(R.string.sys_tune_display_hz_185_unsupported),
                    tone = XgTone.Warn,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedHz != null) {
                XgMono(
                    text = stringResource(R.string.sys_tune_display_hz_option, selectedHz.roundToInt()),
                    style = XgType.monoSmall,
                    color = colors.muted,
                )
            }
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.sys_tune_display_refresh_apply),
                onClick = onApplyRefresh,
                enabled = editable && selectedHz != null,
            )
        }
        XgToggleRow(
            name = stringResource(R.string.sys_tune_display_force),
            note = stringResource(R.string.sys_tune_display_force_note),
            checked = data.forceHighRefresh,
            onCheckedChange = onToggleForceHighRefresh,
            enabled = editable && selectedHz != null,
        )
        if (selectedHz == null) {
            XgNote(
                text = stringResource(R.string.sys_tune_display_force_reason),
                tone = XgTone.Warn,
            )
        }
    }
}

@Composable
internal fun TouchSampleControl(
    data: DisplayState,
    editable: Boolean,
    onTouchSample: (Int?) -> Unit,
) {
    val options = remember(data.touchSampleOptions) { data.touchSampleOptions.distinct().sorted() }
    if (options.isEmpty()) return
    val chips = buildList {
        add(
            TuneChipOption(
                key = TOUCH_DEFAULT_KEY,
                label = stringResource(R.string.sys_tune_display_touch_default),
                enabled = editable,
            ),
        )
        options.forEach { hz ->
            add(
                TuneChipOption(
                    key = hz.toString(),
                    label = stringResource(R.string.sys_tune_display_hz_option, hz),
                    enabled = editable,
                ),
            )
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = XgSpacing.sm)) {
        ControlHeader(
            name = stringResource(R.string.sys_tune_display_touch),
            note = stringResource(R.string.sys_tune_display_touch_note),
            value = data.touchSampleHz?.let { hzText(it.toFloat()) }
                ?: stringResource(R.string.sys_tune_display_touch_default_value),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        TuneChipRow(
            options = chips,
            selectedKey = data.touchSampleHz?.toString() ?: TOUCH_DEFAULT_KEY,
            onSelect = { option ->
                val target = if (option.key == TOUCH_DEFAULT_KEY) {
                    null
                } else {
                    option.key.toIntOrNull()
                }
                onTouchSample(target)
            },
        )
    }
}

@Composable
internal fun DensityControl(
    data: DisplayState,
    densityDraft: Int?,
    editable: Boolean,
    onUpdateDensity: (Int) -> Unit,
    onApplyDensity: () -> Unit,
    onResetDensity: () -> Unit,
) {
    val colors = LocalXgColors.current
    val range = data.densityRange
    val validRange = !range.isEmpty()
    val value = if (validRange) {
        (densityDraft ?: data.density).coerceIn(
            range.first.coerceAtMost(range.last),
            range.last.coerceAtLeast(range.first),
        )
    } else {
        data.density
    }
    val hasSlider = validRange && range.first < range.last
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = XgSpacing.sm)) {
        ControlHeader(
            name = stringResource(R.string.sys_tune_display_density),
            note = stringResource(R.string.sys_tune_display_density_note),
            value = stringResource(R.string.sys_tune_display_density_value, value),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        if (!validRange) {
            XgNote(
                text = stringResource(R.string.sys_tune_display_density_unavailable),
                tone = XgTone.Warn,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XgMono(
                    text = stringResource(R.string.sys_tune_display_density_value, value),
                    style = XgType.monoSmall,
                    color = colors.accent,
                )
                Spacer(Modifier.weight(1f))
                XgMono(
                    text = stringResource(
                        R.string.sys_tune_display_density_range,
                        range.first,
                        range.last,
                    ),
                    style = XgType.monoSmall,
                    color = colors.faint,
                )
            }
            if (hasSlider) {
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onUpdateDensity(it.roundToInt()) },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    enabled = editable,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.accent,
                        activeTrackColor = colors.accent,
                        inactiveTrackColor = colors.line,
                        disabledThumbColor = colors.faint,
                        disabledActiveTrackColor = colors.faint,
                        disabledInactiveTrackColor = colors.line,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.sys_tune_display_apply_density),
                onClick = onApplyDensity,
                enabled = editable && hasSlider,
            )
            Spacer(Modifier.width(XgSpacing.xs))
            XgTextAction(
                text = stringResource(R.string.sys_tune_action_restore_defaults),
                onClick = onResetDensity,
                enabled = editable,
                tone = XgTone.Muted,
            )
        }
    }
}

@Composable
internal fun DcDimmingControl(
    data: DisplayState,
    editable: Boolean,
    onDcDimming: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = XgSpacing.sm)) {
        XgToggleRow(
            name = stringResource(R.string.sys_tune_display_dc),
            note = if (data.dcSupported) {
                stringResource(R.string.sys_tune_display_dc_note)
            } else {
                stringResource(R.string.sys_tune_display_dc_unsupported)
            },
            checked = data.dcEnabled,
            onCheckedChange = onDcDimming,
            enabled = editable && data.dcSupported,
        )
        if (!data.dcSupported) {
            XgNote(
                text = stringResource(R.string.sys_tune_display_dc_unsupported),
                tone = XgTone.Warn,
            )
        }
    }
}

@Composable
internal fun ColorTemperatureControl(
    data: DisplayState,
    colorTempDraft: Int?,
    editable: Boolean,
    onUpdateColorTemperature: (Int) -> Unit,
    onApplyColorTemperature: () -> Unit,
) {
    val colors = LocalXgColors.current
    val value = (colorTempDraft ?: data.colorTemperature ?: COLOR_TEMP_DEFAULT_K)
        .coerceIn(COLOR_TEMP_MIN_K, COLOR_TEMP_MAX_K)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = XgSpacing.sm)) {
        ControlHeader(
            name = stringResource(R.string.sys_tune_display_color),
            note = stringResource(R.string.sys_tune_display_color_note),
            value = if (data.colorTemperatureSupported) {
                stringResource(R.string.sys_tune_display_color_value, value)
            } else {
                stringResource(R.string.sys_tune_value_unsupported)
            },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        if (!data.colorTemperatureSupported) {
            XgNote(
                text = stringResource(R.string.sys_tune_display_color_unsupported),
                tone = XgTone.Warn,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                XgMono(
                    text = stringResource(R.string.sys_tune_display_color_value, value),
                    style = XgType.monoSmall,
                    color = colors.accent,
                )
                Spacer(Modifier.weight(1f))
                XgMono(
                    text = stringResource(
                        R.string.sys_tune_display_color_value,
                        COLOR_TEMP_MIN_K,
                    ) + "–" + stringResource(
                        R.string.sys_tune_display_color_value,
                        COLOR_TEMP_MAX_K,
                    ),
                    style = XgType.monoSmall,
                    color = colors.faint,
                )
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onUpdateColorTemperature(it.roundToInt()) },
                valueRange = COLOR_TEMP_MIN_K.toFloat()..COLOR_TEMP_MAX_K.toFloat(),
                enabled = editable,
                colors = SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.line,
                    disabledThumbColor = colors.faint,
                    disabledActiveTrackColor = colors.faint,
                    disabledInactiveTrackColor = colors.line,
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                XgTextAction(
                    text = stringResource(R.string.sys_tune_display_color_apply),
                    onClick = onApplyColorTemperature,
                    enabled = editable,
                )
            }
        }
    }
}

@Composable
internal fun NightModeControl(
    data: DisplayState,
    editable: Boolean,
    onNightMode: (String) -> Unit,
) {
    val modes = NIGHT_MODES
    val labels = listOf(
        stringResource(R.string.sys_tune_display_night_auto),
        stringResource(R.string.sys_tune_display_night_no),
        stringResource(R.string.sys_tune_display_night_yes),
    )
    val selectedIndex = modes.indexOf(data.nightMode.lowercase())
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = XgSpacing.sm)) {
        ControlHeader(
            name = stringResource(R.string.sys_tune_display_night),
            note = stringResource(R.string.sys_tune_display_night_note),
            value = nightModeLabel(data.nightMode),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        XgChoiceRow(
            options = labels,
            selectedIndex = selectedIndex,
            onSelect = { index -> modes.getOrNull(index)?.let(onNightMode) },
            enabled = editable,
        )
    }
}