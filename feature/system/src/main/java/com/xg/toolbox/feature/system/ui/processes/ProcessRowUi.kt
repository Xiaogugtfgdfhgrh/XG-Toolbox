package com.xg.toolbox.feature.system.ui.processes

import androidx.compose.runtime.Immutable
import com.xg.toolbox.core.ui.component.XgTone
import com.xg.toolbox.feature.system.domain.processes.ProcessGroup
import com.xg.toolbox.feature.system.domain.processes.ProcessItem

@Immutable
data class ProcessRowUi(
    val pid: Int,
    val title: String,
    val meta: String,
    val group: ProcessGroup,
    val groupLabel: String,
    val groupTone: XgTone,
    val protected: Boolean,
    val protectedText: String?,
    val iconPackage: String?,
    val item: ProcessItem,
)
