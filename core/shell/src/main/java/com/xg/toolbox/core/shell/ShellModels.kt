package com.xg.toolbox.core.shell

import kotlinx.coroutines.flow.Flow

data class ShellResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
) {
    val ok: Boolean get() = code == 0
}

data class ShellLine(val text: String, val isErr: Boolean)

interface Shell {

    
    suspend fun exec(
        command: String,
        root: Boolean = true,
        timeoutMs: Long = 15_000,
        env: Map<String, String> = emptyMap(),
    ): ShellResult

    
    suspend fun execMany(
        commands: List<String>,
        root: Boolean = true,
        timeoutMs: Long = 15_000,
    ): List<ShellResult>

    
    fun execStream(
        command: String,
        root: Boolean = true,
        timeoutMs: Long = 20_000,
    ): Flow<ShellLine>
}
