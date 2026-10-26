package com.xg.toolbox.feature.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.xg.toolbox.core.data.SystemOrderStore
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.core.ui.component.XgEntryCard
import com.xg.toolbox.core.ui.component.XgReorderableColumn
import com.xg.toolbox.core.ui.component.XgSectionHeader
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.core.ui.liquid.xgPageSurfaceColor
import com.xg.toolbox.core.ui.theme.XgSpacing
import com.xg.toolbox.feature.security.SecurityCapabilityIds
import com.xg.toolbox.feature.security.SecurityCapabilityScreen
import com.xg.toolbox.feature.system.ui.clean.CleanPage
import com.xg.toolbox.feature.system.ui.display.DisplayPage
import com.xg.toolbox.feature.system.ui.flash.FlashPage
import com.xg.toolbox.feature.system.ui.optimize.OptimizePage
import com.xg.toolbox.feature.system.ui.perf.PerfPage
import com.xg.toolbox.feature.system.ui.processes.ProcessesPage
import com.xg.toolbox.feature.system.ui.scripts.ScriptsPage
import com.xg.toolbox.feature.system.ui.terminal.TerminalPage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class SystemSection(
    val id: String,
    val titleRes: Int,
    val descriptionRes: Int,
) {
    OPTIMIZE(SystemAnchors.OPTIMIZE, R.string.sys_core_optimize_title, R.string.sys_core_overview_desc_optimize),
    CLEAN(SystemAnchors.CLEAN, R.string.sys_core_clean_title, R.string.sys_core_overview_desc_clean),
    SCRIPTS(SystemAnchors.SCRIPTS, R.string.sys_core_scripts_title, R.string.sys_core_overview_desc_scripts),
    PERF(SystemAnchors.PERF, R.string.sys_core_overview_perf, R.string.sys_core_overview_desc_perf),
    DISPLAY(SystemAnchors.DISPLAY, R.string.sys_core_overview_display, R.string.sys_core_overview_desc_display),
    FLASH(SystemAnchors.FLASH, R.string.sys_core_overview_flash, R.string.sys_core_overview_desc_flash),
    PROCESSES(SystemAnchors.PROCESSES, R.string.sys_core_processes_title, R.string.sys_core_overview_desc_processes),
    TERMINAL(SystemAnchors.TERMINAL, R.string.sys_core_overview_terminal, R.string.sys_core_overview_desc_terminal),
    ;

    companion object {
        fun byId(id: String?): SystemSection? = entries.firstOrNull { it.id == id }
    }
}

private data class SecurityCapabilityEntry(
    val id: String,
    val ordinal: String,
    val titleRes: Int,
    val descriptionRes: Int,
)

private val securityCapabilityEntries = listOf(
    SecurityCapabilityEntry(
        id = SecurityCapabilityIds.MATRIX,
        ordinal = "S1",
        titleRes = R.string.sys_core_security_matrix_title,
        descriptionRes = R.string.sys_core_security_matrix_desc,
    ),
    SecurityCapabilityEntry(
        id = SecurityCapabilityIds.A11Y,
        ordinal = "S2",
        titleRes = R.string.sys_core_security_a11y_title,
        descriptionRes = R.string.sys_core_security_a11y_desc,
    ),
    SecurityCapabilityEntry(
        id = SecurityCapabilityIds.AUTOSTART,
        ordinal = "S3",
        titleRes = R.string.sys_core_security_autostart_title,
        descriptionRes = R.string.sys_core_security_autostart_desc,
    ),
    SecurityCapabilityEntry(
        id = SecurityCapabilityIds.ADS,
        ordinal = "S4",
        titleRes = R.string.sys_core_security_ads_title,
        descriptionRes = R.string.sys_core_security_ads_desc,
    ),
)

@HiltViewModel
internal class SystemOverviewViewModel @Inject constructor(
    rootStatusRepository: RootStatusRepository,
    private val systemOrderStore: SystemOrderStore,
) : ViewModel() {
    val rootState: StateFlow<RootState> = rootStatusRepository.state

    private val knownIds = SystemSection.entries.map { it.id }
    private val _sections = MutableStateFlow(SystemSection.entries.toList())
    val sections: StateFlow<List<SystemSection>> = _sections.asStateFlow()

    init {
        viewModelScope.launch {
            systemOrderStore.entryOrder.collect { saved ->
                val mergedIds = systemOrderStore.mergeOrder(saved, knownIds)
                _sections.value = mergedIds.mapNotNull(SystemSection::byId)
                if (mergedIds != saved) {
                    systemOrderStore.save(mergedIds, knownIds)
                }
            }
        }
    }

    fun reorderSections(reordered: List<SystemSection>) {
        val mergedIds = systemOrderStore.mergeOrder(reordered.map { it.id }, knownIds)
        _sections.value = mergedIds.mapNotNull(SystemSection::byId)
        viewModelScope.launch {
            systemOrderStore.save(mergedIds, knownIds)
        }
    }
}

@Composable
fun SystemOverview(
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: SystemOverviewViewModel = hiltViewModel()
    val rootState by viewModel.rootState.collectAsStateWithLifecycle()
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val status = overviewStatus(rootState)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(xgPageSurfaceColor())
            .statusBarsPadding(),
        contentPadding = PaddingValues(
            start = XgSpacing.screen,
            end = XgSpacing.screen,
            top = XgSpacing.sm,
            bottom = XgSpacing.contentBottom,
        ),
        verticalArrangement = Arrangement.spacedBy(XgSpacing.md),
    ) {
        item(key = "system-entry-list") {
            XgReorderableColumn(
                items = sections,
                key = { section -> section.id },
                onReorder = viewModel::reorderSections,
                spacing = XgSpacing.md,
            ) { index, section, _, itemModifier ->
                XgEntryCard(
                    ordinal = "0${index + 1}",
                    title = stringResource(section.titleRes),
                    subtitle = stringResource(section.descriptionRes),
                    badge = status.text,
                    badgeTone = status.tone,
                    onClick = { onOpen(section.id) },
                    modifier = itemModifier,
                )
            }
        }
        item(key = "security-capabilities-header") {
            XgSectionHeader(
                title = stringResource(R.string.sys_core_security_group),
                meta = stringResource(R.string.sys_core_security_group_meta),
            )
        }
        items(
            items = securityCapabilityEntries,
            key = { entry -> entry.id },
            contentType = { "security-capability-entry" },
        ) { entry ->
            XgEntryCard(
                ordinal = entry.ordinal,
                title = stringResource(entry.titleRes),
                subtitle = stringResource(entry.descriptionRes),
                badge = status.text,
                badgeTone = status.tone,
                onClick = { onOpen(entry.id) },
            )
        }
    }
}

@Composable
fun SystemScreen(
    sectionId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (SecurityCapabilityIds.isCapability(sectionId)) {
        SecurityCapabilityScreen(sectionId = sectionId, onBack = onBack)
        return
    }

    val section = SystemSection.byId(sectionId) ?: return
    when (section) {
        SystemSection.OPTIMIZE -> OptimizePage(onBack = onBack, modifier = modifier)
        SystemSection.CLEAN -> CleanPage(onBack = onBack, modifier = modifier)
        SystemSection.SCRIPTS -> ScriptsPage(onBack = onBack, modifier = modifier)
        SystemSection.PERF -> PerfPage(onBack = onBack, modifier = modifier)
        SystemSection.DISPLAY -> DisplayPage(onBack = onBack, modifier = modifier)
        SystemSection.FLASH -> FlashPage(onBack = onBack, modifier = modifier)
        SystemSection.PROCESSES -> ProcessesPage(onBack = onBack, modifier = modifier)
        SystemSection.TERMINAL -> TerminalPage(onBack = onBack, modifier = modifier)
    }
}

private data class OverviewStatus(val text: String, val tone: XgTone)

@Composable
private fun overviewStatus(rootState: RootState): OverviewStatus = when (rootState) {
    is RootState.Granted -> OverviewStatus(
        text = stringResource(R.string.sys_core_overview_status_ready),
        tone = XgTone.Ok,
    )

    is RootState.Unknown -> OverviewStatus(
        text = stringResource(R.string.sys_core_overview_status_checking),
        tone = XgTone.Muted,
    )

    is RootState.Denied -> OverviewStatus(
        text = stringResource(R.string.sys_core_overview_status_needs_root),
        tone = XgTone.Warn,
    )

    is RootState.Unsupported -> OverviewStatus(
        text = stringResource(R.string.sys_core_overview_status_unsupported),
        tone = XgTone.Danger,
    )
}