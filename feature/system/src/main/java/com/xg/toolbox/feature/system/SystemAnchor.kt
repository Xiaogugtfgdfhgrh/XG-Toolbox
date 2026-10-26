package com.xg.toolbox.feature.system

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.xg.toolbox.core.ui.component.XgAnchor
import com.xg.toolbox.feature.system.R

object SystemAnchors {
    const val OPTIMIZE = "optimize"
    const val CLEAN = "clean"
    const val SCRIPTS = "scripts"
    const val PERF = "perf"
    const val DISPLAY = "display"
    const val FLASH = "flash"
    const val PROCESSES = "processes"
    const val TERMINAL = "terminal"

    
    val ids: List<String> = listOf(
        OPTIMIZE,
        CLEAN,
        SCRIPTS,
        PERF,
        DISPLAY,
        FLASH,
        PROCESSES,
        TERMINAL,
    )

    
    fun indexOf(id: String?): Int = if (id == null) -1 else ids.indexOf(id)
}

@Composable
fun rememberSystemAnchors(): List<XgAnchor> = listOf(
    XgAnchor(SystemAnchors.OPTIMIZE, stringResource(R.string.sys_core_anchor_optimize)),
    XgAnchor(SystemAnchors.CLEAN, stringResource(R.string.sys_core_anchor_clean)),
    XgAnchor(SystemAnchors.SCRIPTS, stringResource(R.string.sys_core_anchor_scripts)),
    XgAnchor(SystemAnchors.PERF, stringResource(R.string.sys_core_anchor_perf)),
    XgAnchor(SystemAnchors.DISPLAY, stringResource(R.string.sys_core_anchor_display)),
    XgAnchor(SystemAnchors.FLASH, stringResource(R.string.sys_core_anchor_flash)),
    XgAnchor(SystemAnchors.PROCESSES, stringResource(R.string.sys_core_anchor_processes)),
    XgAnchor(SystemAnchors.TERMINAL, stringResource(R.string.sys_core_anchor_terminal)),
)
