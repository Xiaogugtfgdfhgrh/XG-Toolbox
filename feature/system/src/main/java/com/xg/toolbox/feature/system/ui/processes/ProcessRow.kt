package com.xg.toolbox.feature.system.ui.processes

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.common.Formatters
import com.xg.toolbox.core.ui.component.XgDangerButton
import com.xg.toolbox.core.ui.component.XgIconButton
import com.xg.toolbox.core.ui.component.XgKeyValueRow
import com.xg.toolbox.core.ui.component.XgStatusChip
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.system.R
import com.xg.toolbox.feature.system.domain.processes.ProcessItem
import com.xg.toolbox.feature.system.ui.optimize.SysCheckBox

private val ProcessAvatarSize = 28.dp

@Composable
fun ProcessRow(
    row: ProcessRowUi,
    selected: Boolean,
    expanded: Boolean,
    interactionEnabled: Boolean,
    viewModel: ProcessesViewModel,
    onToggleSelection: () -> Unit,
    onToggleExpanded: () -> Unit,
    onStop: (force: Boolean) -> Unit,
) {
    val colors = LocalXgColors.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = XgSize.rowMin)
                .clickable(role = Role.Button, onClick = onToggleExpanded)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val iconPackage = row.iconPackage
            if (iconPackage != null) {
                ProcessAvatar(
                    packageName = iconPackage,
                    title = row.title,
                    viewModel = viewModel,
                )
                Spacer(Modifier.width(XgSpacing.xs))
            }
            if (row.protected) {
                Box(
                    modifier = Modifier.size(XgSize.touch),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = row.protectedText,
                        tint = colors.faint,
                        modifier = Modifier.size(XgSize.iconSmall),
                    )
                }
            } else {
                SysCheckBox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() },
                    enabled = interactionEnabled,
                    a11yLabel = row.title,
                )
            }
            Spacer(Modifier.width(XgSpacing.xs))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.title,
                        style = XgType.rowTitle,
                        color = colors.ink,
                        maxLines = 2,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(XgSpacing.sm))
                    XgStatusChip(
                        text = row.groupLabel,
                        tone = row.groupTone,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.meta,
                    style = XgType.monoSmall,
                    color = colors.faint,
                )
                val protectedText = row.protectedText
                if (protectedText != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = protectedText,
                        style = XgType.note,
                        color = colors.warn,
                    )
                }
            }
            XgIconButton(
                icon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) {
                    stringResource(R.string.sys_core_processes_collapse_detail)
                } else {
                    stringResource(R.string.sys_core_processes_expand_detail)
                },
                onClick = onToggleExpanded,
            )
        }

        if (expanded) {
            ProcessDetail(
                item = row.item,
                groupLabel = row.groupLabel,
                protectedText = row.protectedText,
                interactionEnabled = interactionEnabled,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun ProcessAvatar(
    packageName: String,
    title: String,
    viewModel: ProcessesViewModel,
) {
    val colors = LocalXgColors.current
    val icon by produceState<ImageBitmap?>(
        initialValue = viewModel.peekIcon(packageName),
        key1 = packageName,
    ) {
        value = viewModel.loadIcon(packageName)
    }
    Box(
        modifier = Modifier
            .size(ProcessAvatarSize)
            .clip(RoundedCornerShape(XgRadius.small))
            .background(colors.inset),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(ProcessAvatarSize),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = title.take(1),
                style = XgType.chip,
                color = colors.muted,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ProcessDetail(
    item: ProcessItem,
    groupLabel: String,
    protectedText: String?,
    interactionEnabled: Boolean,
    onStop: (force: Boolean) -> Unit,
) {
    val colors = LocalXgColors.current
    val unknown = stringResource(R.string.sys_core_unknown)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = XgSpacing.sm)
            .clip(RoundedCornerShape(XgRadius.small))
            .background(colors.inset)
            .padding(horizontal = XgSpacing.sm, vertical = 6.dp),
    ) {
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_ppid),
            value = item.ppid.toString(),
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_user),
            value = item.user,
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_group),
            value = groupLabel,
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_vsz),
            value = Formatters.bytes(item.vszKb * 1024L),
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_threads),
            value = item.threads.toString(),
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_stat),
            value = item.stat,
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_oom),
            value = if (item.oomAdj == ProcessItem.NO_OOM_ADJ) {
                unknown
            } else {
                item.oomAdj.toString()
            },
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_selinux),
            value = item.selinux ?: unknown,
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_start),
            value = item.startTimeMs?.let { Formatters.fullDateTime(it) } ?: unknown,
            copyable = false,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_cmdline),
            value = item.cmdline.ifBlank { unknown },
            copyable = true,
        )
        XgKeyValueRow(
            label = stringResource(R.string.sys_core_processes_detail_cgroup),
            value = item.cgroup ?: unknown,
            copyable = false,
        )
        if (item.protected) {
            Text(
                text = protectedText ?: stringResource(
                    R.string.sys_core_processes_protected_reason,
                    item.protectedReason ?: stringResource(R.string.sys_core_processes_protected),
                ),
                style = XgType.note,
                color = colors.warn,
            )
        } else {
            Spacer(Modifier.height(XgSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(XgSpacing.sm),
            ) {
                XgDangerButton(
                    text = stringResource(R.string.sys_core_processes_stop),
                    onClick = { onStop(false) },
                    enabled = interactionEnabled,
                    modifier = Modifier.weight(1f),
                )
                XgDangerButton(
                    text = stringResource(R.string.sys_core_processes_force_stop),
                    onClick = { onStop(true) },
                    enabled = interactionEnabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
