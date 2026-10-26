package com.xg.toolbox.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.device.RebootTargetInfo
import com.xg.toolbox.core.ui.component.XgBadge
import com.xg.toolbox.core.ui.component.XgContentTransition
import com.xg.toolbox.core.ui.component.XgIconButton
import com.xg.toolbox.core.ui.component.XgMetricCard
import com.xg.toolbox.core.ui.component.XgMono
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType

@Composable
internal fun HomeSummaryCard(summary: HomeSummaryUi?) {
    val colors = LocalXgColors.current
    XgPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(XgSize.avatar)
                    .clip(RoundedCornerShape(XgRadius.small))
                    .background(colors.inset)
                    .border(1.dp, colors.lineStrong, RoundedCornerShape(XgRadius.small)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Smartphone,
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(XgSize.iconSmall),
                )
            }
            Spacer(Modifier.width(XgSpacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = summary?.name?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.home_summary_loading),
                    style = XgType.cardTitle,
                    color = colors.ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!summary?.meta.isNullOrBlank()) {
                    Spacer(Modifier.size(XgSpacing.xs))
                    XgMono(
                        text = summary?.meta.orEmpty(),
                        style = XgType.monoSmall,
                        color = colors.faint,
                    )
                }
            }
            Spacer(Modifier.width(XgSpacing.sm))
            when {
                summary?.unlocked == true -> XgBadge(
                    text = stringResource(R.string.home_summary_unlocked),
                    tone = XgTone.Warn,
                )
                summary?.rooted == true -> XgBadge(
                    text = stringResource(R.string.home_summary_rooted),
                    tone = XgTone.Accent,
                )
                else -> Unit
            }
        }
    }
}

@Composable
internal fun RebootMenuButton(
    targets: List<RebootTargetInfo>,
    enabled: Boolean,
    onSelect: (RebootTargetInfo) -> Unit,
) {
    val colors = LocalXgColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        XgIconButton(
            icon = Icons.Outlined.MoreVert,
            contentDescription = stringResource(R.string.home_menu_description),
            onClick = { expanded = true },
            enabled = enabled,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Text(
                text = stringResource(R.string.home_menu_label),
                style = XgType.label,
                color = colors.faint,
                modifier = Modifier.padding(horizontal = XgSpacing.md, vertical = XgSpacing.xs),
            )
            targets.forEach { target ->
                if (target.id == SHUTDOWN_TARGET_ID) {
                    HorizontalDivider(color = colors.line)
                }
                RebootMenuItem(
                    target = target,
                    onSelect = {
                        expanded = false
                        onSelect(target)
                    },
                )
            }
        }
    }
}

@Composable
private fun RebootMenuItem(target: RebootTargetInfo, onSelect: () -> Unit) {
    val colors = LocalXgColors.current
    val nameColor = when {
        !target.available -> colors.faint
        target.dangerous -> colors.danger
        else -> colors.ink
    }
    val note = if (target.available) target.note else target.unavailableReason ?: target.note
    DropdownMenuItem(
        onClick = onSelect,
        enabled = target.available,
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = target.name,
                    style = XgType.rowTitle,
                    color = nameColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.isNotBlank()) {
                    Text(
                        text = note,
                        style = XgType.rowNote,
                        color = colors.faint,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}

@Composable
internal fun HomeMetricsGrid(metrics: State<HomeMetricsUi>) {
    val hasDataState = remember(metrics) { derivedStateOf { metrics.value.hasData } }
    val hasData = hasDataState.value
    XgContentTransition(
        stateKey = hasData,
        loading = !hasData,
        error = null,
        onRetry = null,
        skeletonLines = 6,
        initiallyLoaded = hasData,
    ) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm)) {
                HomeMetricSlotView(metrics, HomeMetricSlot.Cpu, Modifier.weight(1f))
                HomeMetricSlotView(metrics, HomeMetricSlot.Gpu, Modifier.weight(1f))
            }
            Spacer(Modifier.height(XgSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm)) {
                HomeMetricSlotView(metrics, HomeMetricSlot.Memory, Modifier.weight(1f))
                HomeMetricSlotView(metrics, HomeMetricSlot.Temperature, Modifier.weight(1f))
            }
            Spacer(Modifier.height(XgSpacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm)) {
                HomeMetricSlotView(metrics, HomeMetricSlot.Battery, Modifier.weight(1f))
                HomeMetricSlotView(metrics, HomeMetricSlot.Storage, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HomeMetricSlotView(
    metrics: State<HomeMetricsUi>,
    slot: HomeMetricSlot,
    modifier: Modifier = Modifier,
) {
    val metric = metrics.value.metric(slot)
    XgMetricCard(
        name = stringResource(slot.nameRes),
        hint = metric.hint,
        value = metric.value,
        unit = metric.unit,
        points = metric.points,
        pointsCount = metric.pointsCount,
        pointsRevision = metric.revision,
        tone = slot.tone,
        modifier = modifier,
    )
}

private enum class HomeMetricSlot(val nameRes: Int, val tone: XgTone) {
    Cpu(R.string.home_metric_cpu, XgTone.Accent),
    Gpu(R.string.home_metric_gpu, XgTone.Accent),
    Memory(R.string.home_metric_memory, XgTone.Accent),
    Temperature(R.string.home_metric_temperature, XgTone.Warn),
    Battery(R.string.home_metric_battery, XgTone.Muted),
    Storage(R.string.home_metric_storage, XgTone.Muted),
}

private fun HomeMetricsUi.metric(slot: HomeMetricSlot): HomeMetricUi = when (slot) {
    HomeMetricSlot.Cpu -> cpu
    HomeMetricSlot.Gpu -> gpu
    HomeMetricSlot.Memory -> memory
    HomeMetricSlot.Temperature -> temperature
    HomeMetricSlot.Battery -> battery
    HomeMetricSlot.Storage -> storage
}

private const val SHUTDOWN_TARGET_ID = "shutdown"
