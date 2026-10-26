package com.xg.toolbox.feature.system.domain.scripts

data class ScriptInfo(
    val id: String,
    val name: String,
    val description: String,
    val estimate: String,
)

data class ScriptLine(
    val text: String,
    val failed: Boolean = false,
)

data class ScriptResult(
    val exitCode: Int,
    val freedBytes: Long?,
    val message: String,
)

internal data class ScriptManifestEntry(
    val id: String,
    val name: String,
    val desc: String,
    val estimate: String,
)
