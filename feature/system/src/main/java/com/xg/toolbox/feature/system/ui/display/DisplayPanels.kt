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
internal fun ReadOnlyBanner() {
    val colors = LocalXgColors.current
    XgPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XgStatusChip(
                text = stringResource(R.string.sys_tune_status_readonly),
                tone = XgTone.Warn,
            )
            Spacer(Modifier.width(XgSpacing.sm))
            Text(
                text = stringResource(R.string.sys_tune_readonly_note),
                style = XgType.note,
                color = colors.muted,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun DisplayNoticeRow(
    notice: DisplayNotice,
    onDismiss: () -> Unit,
) {
    val colors = LocalXgColors.current
    val label = when (notice.kind) {
        DisplayNoticeKind.APPLIED -> stringResource(R.string.sys_tune_display_notice_applied)
        DisplayNoticeKind.READBACK_FAILED -> stringResource(R.string.sys_tune_display_notice_readback_failed)
        DisplayNoticeKind.FAILED -> stringResource(R.string.sys_tune_status_failed)
        DisplayNoticeKind.BLOCKED -> stringResource(R.string.sys_tune_display_notice_blocked)
    }
    val tone = when (notice.kind) {
        DisplayNoticeKind.APPLIED -> XgTone.Ok
        DisplayNoticeKind.READBACK_FAILED, DisplayNoticeKind.FAILED, DisplayNoticeKind.BLOCKED -> XgTone.Danger
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            XgStatusChip(text = label, tone = tone)
            Spacer(Modifier.weight(1f))
            XgTextAction(
                text = stringResource(R.string.sys_tune_action_clear),
                onClick = onDismiss,
                tone = XgTone.Muted,
            )
        }
        if (!notice.detail.isNullOrBlank()) {
            Spacer(Modifier.height(XgSpacing.xs))
            Text(
                text = notice.detail.orEmpty(),
                style = XgType.note,
                color = colors.muted,
            )
        }
    }
}

@Composable
internal fun DisplaySummaryPanel(data: DisplayState) {
    val none = stringResource(R.string.sys_tune_value_none)
    val entries = listOf(
        stringResource(R.string.sys_tune_display_summary_size) to data.size.ifBlank { none },
        stringResource(R.string.sys_tune_display_summary_refresh) to hzText(data.currentPeakHz),
        stringResource(R.string.sys_tune_display_summary_touch) to (
            data.touchSampleHz?.let { hzText(it.toFloat()) }
                ?: stringResource(R.string.sys_tune_display_touch_default_value)
            ),
        stringResource(R.string.sys_tune_display_summary_force) to if (data.forceHighRefresh) {
            stringResource(R.string.sys_tune_display_force_on)
        } else {
            stringResource(R.string.sys_tune_display_force_off)
        },
        stringResource(R.string.sys_tune_display_summary_density) to
            stringResource(R.string.sys_tune_display_density_value, data.density),
        stringResource(R.string.sys_tune_display_summary_dc) to when {
            !data.dcSupported -> stringResource(R.string.sys_tune_value_unsupported)
            data.dcEnabled -> stringResource(R.string.sys_tune_value_on)
            else -> stringResource(R.string.sys_tune_value_off)
        },
    )
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_display_summary),
            meta = stringResource(R.string.sys_tune_display_summary_meta),
        )
        Spacer(Modifier.height(XgSpacing.md))
        ValueGrid(entries)
    }
}

@Composable
internal fun DisplayControlsPanel(
    data: DisplayState,
    state: DisplayUiState,
    onSelectHz: (Float) -> Unit,
    onSelectSize: (String) -> Unit,
    onUpdateDensity: (Int) -> Unit,
    onUpdateColorTemperature: (Int) -> Unit,
    onApplySize: () -> Unit,
    onResetSize: () -> Unit,
    onApplyDensity: () -> Unit,
    onResetDensity: () -> Unit,
    onApplyRefresh: () -> Unit,
    onToggleForceHighRefresh: (Boolean) -> Unit,
    onTouchSample: (Int?) -> Unit,
    onDcDimming: (Boolean) -> Unit,
    onApplyColorTemperature: () -> Unit,
    onNightMode: (String) -> Unit,
) {
    val editable = !state.readOnly && !state.busy
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_display_controls),
            trailing = {
                if (state.busy) {
                    XgStatusChip(
                        text = stringResource(R.string.sys_tune_status_busy),
                        tone = XgTone.Accent,
                    )
                }
            },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        ResolutionControl(
            data = data,
            pendingSize = state.pendingSize,
            editable = editable,
            onSelectSize = onSelectSize,
            onApplySize = onApplySize,
            onResetSize = onResetSize,
        )
        XgHairline()
        RefreshControl(
            data = data,
            selectedHz = state.selectedHz,
            editable = editable,
            onSelectHz = onSelectHz,
            onApplyRefresh = onApplyRefresh,
            onToggleForceHighRefresh = onToggleForceHighRefresh,
        )
        if (data.touchSampleOptions.isNotEmpty()) {
            XgHairline()
            TouchSampleControl(
                data = data,
                editable = editable,
                onTouchSample = onTouchSample,
            )
        }
        XgHairline()
        DensityControl(
            data = data,
            densityDraft = state.densityDraft,
            editable = editable,
            onUpdateDensity = onUpdateDensity,
            onApplyDensity = onApplyDensity,
            onResetDensity = onResetDensity,
        )
        XgHairline()
        DcDimmingControl(
            data = data,
            editable = editable,
            onDcDimming = onDcDimming,
        )
        XgHairline()
        ColorTemperatureControl(
            data = data,
            colorTempDraft = state.colorTempDraft,
            editable = editable,
            onUpdateColorTemperature = onUpdateColorTemperature,
            onApplyColorTemperature = onApplyColorTemperature,
        )
        XgHairline()
        NightModeControl(
            data = data,
            editable = editable,
            onNightMode = onNightMode,
        )
        if (state.readOnly) {
            Spacer(Modifier.height(XgSpacing.sm))
            XgNote(text = stringResource(R.string.sys_tune_display_notice_blocked), tone = XgTone.Warn)
        }
    }
}

@Composable
internal fun RestoreDefaultsPanel(
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_display_restore_title),
            meta = stringResource(R.string.sys_tune_display_restore_note),
        )
        Spacer(Modifier.height(XgSpacing.md))
        XgGhostButton(
            text = stringResource(R.string.sys_tune_display_restore_action),
            onClick = onRestore,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun DisplayReadbackPanel(
    data: DisplayState,
    lastOperation: DisplayOp?,
) {
    val none = stringResource(R.string.sys_tune_value_none)
    val colorTemperature = data.colorTemperature
    val entries = listOf(
        stringResource(R.string.sys_tune_display_label_size) to data.size.ifBlank { none },
        stringResource(R.string.sys_tune_display_label_physical) to data.physicalSize.ifBlank { none },
        stringResource(R.string.sys_tune_display_label_density) to
            stringResource(R.string.sys_tune_display_density_value, data.density),
        stringResource(R.string.sys_tune_display_label_peak) to hzText(data.currentPeakHz),
        stringResource(R.string.sys_tune_display_label_min) to hzText(data.currentMinHz),
        stringResource(R.string.sys_tune_display_label_force) to if (data.forceHighRefresh) {
            stringResource(R.string.sys_tune_display_force_on)
        } else {
            stringResource(R.string.sys_tune_display_force_off)
        },
        stringResource(R.string.sys_tune_display_label_touch) to (
            data.touchSampleHz?.let { hzText(it.toFloat()) }
                ?: stringResource(R.string.sys_tune_display_touch_default_value)
            ),
        stringResource(R.string.sys_tune_display_label_dc) to when {
            !data.dcSupported -> stringResource(R.string.sys_tune_value_unsupported)
            data.dcEnabled -> stringResource(R.string.sys_tune_value_on)
            else -> stringResource(R.string.sys_tune_value_off)
        },
        stringResource(R.string.sys_tune_display_label_color) to when {
            !data.colorTemperatureSupported -> stringResource(R.string.sys_tune_value_unsupported)
            colorTemperature == null -> none
            else -> stringResource(R.string.sys_tune_display_color_value, colorTemperature)
        },
        stringResource(R.string.sys_tune_display_label_night) to nightModeLabel(data.nightMode),
    )
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_display_readback),
            meta = stringResource(R.string.sys_tune_display_readback_meta),
            trailing = {
                XgBadge(
                    text = stringResource(R.string.sys_tune_display_readback_badge),
                    tone = XgTone.Ok,
                )
            },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        if (lastOperation == null) {
            XgNote(text = stringResource(R.string.sys_tune_display_readback_none))
        } else {
            XgNote(
                text = stringResource(
                    R.string.sys_tune_display_last_op,
                    displayOpLabel(lastOperation),
                ),
                tone = XgTone.Accent,
            )
        }
        Spacer(Modifier.height(XgSpacing.md))
        ValueGrid(entries)
    }
}

@Composable
internal fun ValueGrid(entries: List<Pair<String, String>>) {
    val colors = LocalXgColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(XgSpacing.md),
    ) {
        entries.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(XgSpacing.lg),
            ) {
                row.forEach { (label, value) ->
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = XgType.metricName,
                            color = colors.faint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(XgSpacing.xs))
                        XgMono(text = value, style = XgType.monoValueMedium, color = colors.ink)
                    }
                }
                if (row.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun ControlHeader(
    name: String,
    note: String,
    value: String,
) {
    val colors = LocalXgColors.current
    Row(verticalAlignment = Alignment.Top) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = XgType.rowTitle,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(XgSpacing.xs))
            Text(
                text = note,
                style = XgType.rowNote,
                color = colors.faint,
            )
        }
        Spacer(Modifier.width(XgSpacing.sm))
        XgMono(
            text = value,
            style = XgType.monoSmall,
            color = colors.muted,
        )
    }
}

internal data class TuneChipOption(
    val key: String,
    val label: String,
    val enabled: Boolean,
)

@Composable
internal fun TuneChipRow(
    options: List<TuneChipOption>,
    selectedKey: String?,
    onSelect: (TuneChipOption) -> Unit,
) {
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm),
    ) {
        options.forEach { option ->
            val isSelected = option.key == selectedKey
            val shape = RoundedCornerShape(XgRadius.chip)
            Box(
                modifier = Modifier
                    .heightIn(min = XgSize.anchorH)
                    .clip(shape)
                    .background(if (isSelected) colors.accentSoft else colors.surface2)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) colors.accent else colors.lineStrong,
                        shape = shape,
                    )
                    .clickable(
                        enabled = option.enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    )
                    .semantics { selected = isSelected }
                    .padding(horizontal = XgSpacing.md),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = XgType.chip,
                    color = when {
                        isSelected -> colors.accent
                        option.enabled -> colors.muted
                        else -> colors.faint
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal data class ResolutionOption(
    val key: String,
    val width: Int,
    val height: Int,
    val kind: ResolutionKind,
)

internal enum class ResolutionKind {
    NATIVE,
    CURRENT,
    BALANCED,
}

internal fun buildResolutionOptions(data: DisplayState): List<ResolutionOption> {
    val result = mutableListOf<ResolutionOption>()
    fun add(width: Int, height: Int, kind: ResolutionKind) {
        if (width <= 0 || height <= 0) return
        val key = "${width}x$height"
        if (result.none { it.key == key }) {
            result.add(ResolutionOption(key, width, height, kind))
        }
    }
    val native = parseResolution(data.physicalSize)
    val current = parseResolution(data.size)
    if (native != null) {
        add(native.first, native.second, ResolutionKind.NATIVE)
    }
    if (current != null) {
        add(current.first, current.second, ResolutionKind.CURRENT)
    }
    if (native != null) {
        val balancedWidth = evenDimension(native.first * 3 / 4)
        val balancedHeight = evenDimension(native.second * 3 / 4)
        add(balancedWidth, balancedHeight, ResolutionKind.BALANCED)
    }
    return result
}

internal fun evenDimension(value: Int): Int {
    val even = if (value % 2 == 0) value else value - 1
    return even.coerceAtLeast(2)
}

@Composable
internal fun resolutionLabel(option: ResolutionOption): String = when (option.kind) {
    ResolutionKind.NATIVE -> stringResource(
        R.string.sys_tune_display_resolution_native,
        option.width,
        option.height,
    )

    ResolutionKind.CURRENT -> stringResource(
        R.string.sys_tune_display_resolution_current,
        option.width,
        option.height,
    )

    ResolutionKind.BALANCED -> stringResource(
        R.string.sys_tune_display_resolution_balanced,
        option.width,
        option.height,
    )
}

@Composable
internal fun hzText(hz: Float?): String =
    hz?.let { stringResource(R.string.sys_tune_display_hz_option, it.roundToInt()) }
        ?: stringResource(R.string.sys_tune_value_none)

@Composable
internal fun nightModeLabel(mode: String): String = when (mode.lowercase()) {
    "yes" -> stringResource(R.string.sys_tune_display_night_yes)
    "no" -> stringResource(R.string.sys_tune_display_night_no)
    "auto" -> stringResource(R.string.sys_tune_display_night_auto)
    else -> stringResource(R.string.sys_tune_value_none)
}

@Composable
internal fun displayOpLabel(operation: DisplayOp): String = when (operation) {
    DisplayOp.SIZE -> stringResource(R.string.sys_tune_display_op_size)
    DisplayOp.SIZE_RESET -> stringResource(R.string.sys_tune_display_op_size_reset)
    DisplayOp.DENSITY -> stringResource(R.string.sys_tune_display_op_density)
    DisplayOp.DENSITY_RESET -> stringResource(R.string.sys_tune_display_op_density_reset)
    DisplayOp.REFRESH -> stringResource(R.string.sys_tune_display_op_refresh)
    DisplayOp.TOUCH -> stringResource(R.string.sys_tune_display_op_touch)
    DisplayOp.DC -> stringResource(R.string.sys_tune_display_op_dc)
    DisplayOp.COLOR -> stringResource(R.string.sys_tune_display_op_color)
    DisplayOp.NIGHT -> stringResource(R.string.sys_tune_display_op_night)
    DisplayOp.RESTORE -> stringResource(R.string.sys_tune_display_op_restore)
}

internal const val TOUCH_DEFAULT_KEY = "default"
internal val NIGHT_MODES = listOf("auto", "no", "yes")