package com.xg.toolbox.feature.security.ui

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
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xg.toolbox.core.shell.RootInfo
import com.xg.toolbox.core.shell.RootScheme
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.displayText
import com.xg.toolbox.core.ui.component.XgHairline
import com.xg.toolbox.core.ui.component.XgLoadingSkeleton
import com.xg.toolbox.core.ui.component.XgNote
import com.xg.toolbox.core.ui.component.XgPanel
import com.xg.toolbox.core.ui.component.XgStatusChip
import com.xg.toolbox.core.ui.component.XgTextAction
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.theme.LocalXgColors
import com.xg.toolbox.core.ui.theme.XgRadius
import com.xg.toolbox.core.ui.theme.XgSize
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.core.ui.theme.XgType
import com.xg.toolbox.feature.security.R
import com.xg.toolbox.feature.security.RootCardState

@Composable
fun SecurityRootCard(
    root: RootCardState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    XgPanel(modifier = modifier) {
        RootTitleRow(root = root)
        Spacer(Modifier.height(XgSpacing.sm))
        XgHairline()
        Spacer(Modifier.height(XgSpacing.sm))
        if (root.state is RootState.Unknown && root.refreshing && root.info == null) {
            XgLoadingSkeleton(lines = 2)
        } else {
            RootMetaGrid(info = root.info)
        }
        Spacer(Modifier.height(XgSpacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            XgTextAction(
                text = stringResource(R.string.sec_root_refresh),
                onClick = onRefresh,
                enabled = !root.refreshing,
            )
            Spacer(Modifier.width(XgSpacing.sm))
            XgNote(
                text = if (root.state is RootState.Granted) {
                    stringResource(R.string.sec_root_hint_granted)
                } else {
                    stringResource(R.string.sec_root_hint_first)
                },
                tone = if (root.state is RootState.Granted) XgTone.Muted else XgTone.Warn,
            )
        }
        EvidenceSection(evidence = root.evidence)
        Spacer(Modifier.height(XgSpacing.xs))
        XgNote(text = stringResource(R.string.sec_root_scheme_note))
    }
}

@Composable
private fun RootTitleRow(root: RootCardState) {
    val colors = LocalXgColors.current
    val shape = RoundedCornerShape(XgRadius.small)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(XgSize.iconBox)
                .clip(shape)
                .background(colors.accentSoft)
                .border(1.dp, colors.accent.copy(alpha = 0.30f), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(XgSize.iconSmall),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.sec_root_title),
                style = XgType.cardTitle,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = rootStateText(root),
                style = XgType.note,
                color = colors.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(XgSpacing.sm))
        XgStatusChip(
            text = rootChipText(root),
            tone = rootChipTone(root),
        )
    }
}

@Composable
private fun RootMetaGrid(info: RootInfo?) {
    val scheme = info?.let { schemeLabel(it.scheme) + " " + it.version.ifBlank { "—" } } ?: "—"
    val uid = info?.let {
        if (it.uid == 0) {
            stringResource(R.string.sec_root_uid_value, it.uid)
        } else {
            stringResource(R.string.sec_root_uid_nonroot, it.uid)
        }
    } ?: "—"
    val suPath = info?.suPath?.takeIf { it.isNotBlank() } ?: "—"
    val selinux = info?.selinux?.takeIf { it.isNotBlank() } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(XgSpacing.sm)) {
        Row {
            RootMetaCell(
                label = stringResource(R.string.sec_root_meta_scheme),
                value = scheme,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(XgSpacing.md))
            RootMetaCell(
                label = stringResource(R.string.sec_root_meta_uid),
                value = uid,
                modifier = Modifier.weight(1f),
            )
        }
        Row {
            RootMetaCell(
                label = stringResource(R.string.sec_root_meta_su),
                value = suPath,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(XgSpacing.md))
            RootMetaCell(
                label = stringResource(R.string.sec_root_meta_selinux),
                value = selinux,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RootMetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalXgColors.current
    Column(modifier = modifier) {
        Text(
            text = label,
            style = XgType.fieldLabel,
            color = colors.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = XgType.monoValue,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EvidenceSection(evidence: List<String>) {
    if (evidence.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val colors = LocalXgColors.current
    Column(modifier = Modifier.padding(top = XgSpacing.xs)) {
        XgTextAction(
            text = stringResource(
                if (expanded) R.string.sec_root_evidence_hide else R.string.sec_root_evidence_show,
            ),
            onClick = { expanded = !expanded },
        )
        if (expanded) {
            Spacer(Modifier.height(XgSpacing.xs))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(XgRadius.small))
                    .background(colors.inset)
                    .padding(XgSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                evidence.forEach { line ->
                    Text(
                        text = line,
                        style = XgType.monoSmall,
                        color = colors.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun rootStateText(root: RootCardState): String {
    val state = root.state
    return when (state) {
        is RootState.Granted -> {
            val info = root.info
            if (info == null || info.scheme == RootScheme.NONE) {
                stringResource(R.string.sec_root_state_granted_plain)
            } else {
                stringResource(
                    R.string.sec_root_state_granted,
                    schemeLabel(info.scheme),
                    info.version.ifBlank { "—" },
                )
            }
        }

        is RootState.Denied -> state.reason.displayText()
        is RootState.Unsupported -> state.reason.ifBlank {
            stringResource(R.string.sec_root_chip_unsupported)
        }

        is RootState.Unknown -> if (root.refreshing) {
            stringResource(R.string.sec_root_state_unknown)
        } else {
            stringResource(R.string.sec_root_state_unchecked)
        }
    }
}

@Composable
private fun rootChipText(root: RootCardState): String = when {
    root.refreshing && root.state is RootState.Unknown -> stringResource(R.string.sec_root_chip_checking)
    root.state is RootState.Granted -> stringResource(R.string.sec_root_chip_granted)
    root.state is RootState.Denied -> stringResource(R.string.sec_root_chip_denied)
    root.state is RootState.Unsupported -> stringResource(R.string.sec_root_chip_unsupported)
    else -> stringResource(R.string.sec_root_chip_unknown)
}

@Composable
private fun rootChipTone(root: RootCardState): XgTone = when (root.state) {
    is RootState.Granted -> XgTone.Ok
    is RootState.Denied -> XgTone.Danger
    is RootState.Unsupported -> XgTone.Warn
    is RootState.Unknown -> XgTone.Muted
}

@Composable
private fun schemeLabel(scheme: RootScheme): String = when (scheme) {
    RootScheme.MAGISK -> stringResource(R.string.sec_scheme_magisk)
    RootScheme.KERNELSU -> stringResource(R.string.sec_scheme_kernelsu)
    RootScheme.APATCH -> stringResource(R.string.sec_scheme_apatch)
    RootScheme.OTHER -> stringResource(R.string.sec_scheme_other)
    RootScheme.NONE -> stringResource(R.string.sec_scheme_none)
}
