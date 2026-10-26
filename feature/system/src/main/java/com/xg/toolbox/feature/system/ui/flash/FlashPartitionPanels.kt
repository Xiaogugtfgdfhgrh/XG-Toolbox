package com.xg.toolbox.feature.system.ui.flash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgChoiceRow
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgPanelHeader
import com.xg.toolbox.core.ui.component.XgSearchField
import com.xg.toolbox.core.ui.component.XgStatusChip
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.system.R

@Composable
internal fun FlashPartitionToolbar(
    detectedTotal: Int,
    availableCount: Int,
    resultCount: Int,
    currentSlot: String?,
    query: String,
    onQueryChange: (String) -> Unit,
    riskFilter: FlashPartitionRisk?,
    onRiskFilter: (FlashPartitionRisk?) -> Unit,
    slotFilter: FlashSlotFilter,
    onSlotFilter: (FlashSlotFilter) -> Unit,
    writableOnly: Boolean,
    onWritableOnly: (Boolean) -> Unit,
) {
    val riskOptions = listOf(
        stringResource(R.string.sys_tune_flash_filter_all),
        stringResource(R.string.sys_tune_flash_risk_safe),
        stringResource(R.string.sys_tune_flash_risk_caution),
        stringResource(R.string.sys_tune_flash_risk_dangerous),
    )
    val riskIndex = when (riskFilter) {
        null -> 0
        FlashPartitionRisk.SAFE -> 1
        FlashPartitionRisk.CAUTION -> 2
        FlashPartitionRisk.DANGEROUS -> 3
    }
    val slotOptions = listOf(
        stringResource(R.string.sys_tune_flash_slot_filter_all),
        stringResource(R.string.sys_tune_flash_slot_filter_current),
        stringResource(R.string.sys_tune_flash_slot_a),
        stringResource(R.string.sys_tune_flash_slot_b),
        stringResource(R.string.sys_tune_flash_slot_none),
    )
    XgPanel {
        XgPanelHeader(
            title = stringResource(R.string.sys_tune_flash_parts_title),
            meta = stringResource(R.string.sys_tune_flash_parts_detected, detectedTotal, availableCount),
            trailing = {
                currentSlot?.let { slot ->
                    XgBadge(
                        text = stringResource(R.string.sys_tune_flash_current_slot_value, slot),
                        tone = XgTone.Ok,
                    )
                }
            },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        XgSearchField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.sys_tune_flash_parts_search),
        )
        Spacer(Modifier.height(XgSpacing.sm))
        XgChoiceRow(
            options = riskOptions,
            selectedIndex = riskIndex,
            onSelect = { index ->
                onRiskFilter(
                    when (index) {
                        1 -> FlashPartitionRisk.SAFE
                        2 -> FlashPartitionRisk.CAUTION
                        3 -> FlashPartitionRisk.DANGEROUS
                        else -> null
                    },
                )
            },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        XgChoiceRow(
            options = slotOptions,
            selectedIndex = slotFilter.ordinal,
            onSelect = { index -> onSlotFilter(FlashSlotFilter.entries[index]) },
        )
        Spacer(Modifier.height(XgSpacing.sm))
        XgChoiceRow(
            options = listOf(
                stringResource(R.string.sys_tune_flash_filter_all),
                stringResource(R.string.sys_tune_flash_filter_writable_only),
            ),
            selectedIndex = if (writableOnly) 1 else 0,
            onSelect = { index -> onWritableOnly(index == 1) },
        )
        if (detectedTotal > availableCount) {
            Spacer(Modifier.height(XgSpacing.xs))
            Text(
                text = stringResource(
                    R.string.sys_tune_flash_parts_skipped_note,
                    detectedTotal - availableCount,
                ),
                style = XgType.note,
                color = LocalXgColors.current.faint,
            )
        }
        if (resultCount != availableCount) {
            Spacer(Modifier.height(XgSpacing.xs))
            Text(
                text = stringResource(R.string.sys_tune_flash_parts_filtered, resultCount),
                style = XgType.note,
                color = LocalXgColors.current.faint,
            )
        }
    }
}

@Composable
internal fun FlashPartitionGroupHeader(
    group: FlashPartitionGroup,
    count: Int,
) {
    val title = when (group) {
        FlashPartitionGroup.CURRENT -> stringResource(R.string.sys_tune_flash_group_current)
        FlashPartitionGroup.A -> stringResource(R.string.sys_tune_flash_group_a)
        FlashPartitionGroup.B -> stringResource(R.string.sys_tune_flash_group_b)
        FlashPartitionGroup.OTHER -> stringResource(R.string.sys_tune_flash_group_other)
    }
    val colors = LocalXgColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = XgSpacing.xs, bottom = XgSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = title, style = XgType.fieldLabel, color = colors.muted)
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.sys_tune_flash_group_count, count),
            style = XgType.note,
            color = colors.faint,
        )
    }
}

@Composable
internal fun FlashPartitionUiCard(
    partition: FlashPartitionUi,
    selected: Boolean,
    editable: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalXgColors.current
    XgPanel(
        modifier = Modifier.clickable(
            enabled = editable && partition.writable,
            onClick = onClick,
        ),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = partition.name,
                        style = XgType.rowTitle,
                        color = if (partition.writable) colors.ink else colors.faint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(4.dp))
                    XgBadge(
                        text = riskLabel(partition.risk),
                        tone = riskTone(partition.risk),
                    )
                    if (partition.isCurrentSlot) {
                        Spacer(Modifier.width(4.dp))
                        XgBadge(
                            text = stringResource(R.string.sys_tune_flash_part_current_slot_badge),
                            tone = XgTone.Ok,
                        )
                    }
                }
                Spacer(Modifier.height(XgSpacing.xs))
                Text(
                    text = stringResource(
                        R.string.sys_tune_flash_part_detail,
                        slotLabel(partition),
                        Formatters.bytes(partition.sizeBytes),
                        writableLabel(partition.writable),
                    ),
                    style = XgType.note,
                    color = colors.faint,
                )
            }
            if (selected) {
                Spacer(Modifier.weight(1f))
                XgStatusChip(
                    text = stringResource(R.string.sys_tune_flash_part_selected),
                    tone = XgTone.Accent,
                )
            }
        }
    }
}

@Composable
private fun riskLabel(risk: FlashPartitionRisk): String = when (risk) {
    FlashPartitionRisk.SAFE -> stringResource(R.string.sys_tune_flash_risk_safe)
    FlashPartitionRisk.CAUTION -> stringResource(R.string.sys_tune_flash_risk_caution)
    FlashPartitionRisk.DANGEROUS -> stringResource(R.string.sys_tune_flash_risk_dangerous)
}

private fun riskTone(risk: FlashPartitionRisk): XgTone = when (risk) {
    FlashPartitionRisk.SAFE -> XgTone.Ok
    FlashPartitionRisk.CAUTION -> XgTone.Warn
    FlashPartitionRisk.DANGEROUS -> XgTone.Danger
}

@Composable
private fun slotLabel(partition: FlashPartitionUi): String = when (partition.slot) {
    "A" -> stringResource(R.string.sys_tune_flash_slot_a)
    "B" -> stringResource(R.string.sys_tune_flash_slot_b)
    else -> stringResource(R.string.sys_tune_flash_slot_none)
}

@Composable
private fun writableLabel(writable: Boolean): String = if (writable) {
    stringResource(R.string.sys_tune_flash_part_writable)
} else {
    stringResource(R.string.sys_tune_flash_part_readonly)
}

