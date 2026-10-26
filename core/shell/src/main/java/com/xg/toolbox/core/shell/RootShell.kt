package com.xg.toolbox.core.shell

import android.os.SystemClock
import com.xg.toolbox.core.common.ErrorCatalog
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgLog
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
class RootShell @Inject constructor(
    private val suPathStore: SuPathStore,
) : Shell {

    private val _rootLossEvents = MutableSharedFlow<DenyReason>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    
    val rootLossEvents: SharedFlow<DenyReason> = _rootLossEvents.asSharedFlow()

    private val shellMutex = Mutex()
    @Volatile
    private var cachedLocalShell: String? = null

    override suspend fun exec(
        command: String,
        root: Boolean,
        timeoutMs: Long,
        env: Map<String, String>,
    ): ShellResult = withContext(Dispatchers.IO) {
        shellMutex.withLock { runOnce(command, root, timeoutMs, env) }
    }

    override suspend fun execMany(
        commands: List<String>,
        root: Boolean,
        timeoutMs: Long,
    ): List<ShellResult> = withContext(Dispatchers.IO) {
        shellMutex.withLock { commands.map { runOnce(it, root, timeoutMs, emptyMap()) } }
    }

    override fun execStream(command: String, root: Boolean, timeoutMs: Long): Flow<ShellLine> = channelFlow {
        shellMutex.withLock {
            val process = try {
                startProcess(command, root, emptyMap(), mergeError = false)
            } catch (t: Throwable) {
                XgLog.e(TAG, "无法启动流式命令", t)
                send(ShellLine(ErrorCatalog.message(startFailureCode(root)), true))
                return@withLock
            }
            val readers = mutableListOf<Job>()
            try {
                readers += launch(Dispatchers.IO) { pump(process.inputStream, false) { send(it) } }
                readers += launch(Dispatchers.IO) { pump(process.errorStream, true) { send(it) } }
                val finished = withTimeoutOrNull(timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS)) {
                    readers.forEach { it.join() }
                    true
                } ?: false
                if (!finished) {
                    process.destroyForcibly()
                    val code = if (root) ErrorCode.ERR_SU_TIMEOUT else ErrorCode.ERR_CMD_TIMEOUT
                    send(ShellLine(ErrorCatalog.message(code), true))
                } else {
                    withTimeoutOrNull(EXIT_WAIT_MS) {
                        withContext(Dispatchers.IO) { process.waitFor() }
                    }
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
                readers.forEach { it.cancel() }
            }
        }
    }.flowOn(Dispatchers.IO)

    
    private suspend fun runOnce(
        command: String,
        root: Boolean,
        timeoutMs: Long,
        env: Map<String, String>,
    ): ShellResult {
        val startedAt = SystemClock.elapsedRealtime()
        val process = try {
            startProcess(command, root, env, mergeError = true)
        } catch (t: Throwable) {
            return startFailure(root, t, startedAt)
        }
        val result: ShellResult = try {
            val captured = withTimeoutOrNull(timeoutMs.coerceAtLeast(MIN_TIMEOUT_MS)) {
                val stdoutDeferred = async(Dispatchers.IO) {
                    process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val stderrDeferred = async(Dispatchers.IO) {
                    process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()
                val exitCode = withTimeoutOrNull(EXIT_WAIT_MS) {
                    withContext(Dispatchers.IO) { process.waitFor() }
                } ?: -1
                Captured(stdout, stderr, exitCode)
            }
            if (captured == null || captured.exitCode < 0) {
                process.destroyForcibly()
                timeoutResult(root, startedAt)
            } else {
                toResult(root, captured, startedAt)
            }
        } catch (c: CancellationException) {
            process.destroyForcibly()
            throw c
        } catch (t: Throwable) {
            process.destroyForcibly()
            XgLog.w(TAG, "命令执行异常: ${t.javaClass.simpleName}: ${t.message}")
            ShellResult(
                code = ErrorCode.ERR_CMD_FAILED,
                stdout = "",
                stderr = ErrorCatalog.message(ErrorCode.ERR_CMD_FAILED),
                durationMs = SystemClock.elapsedRealtime() - startedAt,
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
        return result
    }

    
    private fun startProcess(
        command: String,
        root: Boolean,
        env: Map<String, String>,
        mergeError: Boolean,
    ): Process {
        val payload = buildString {
            if (mergeError) append("exec 2>&1; ")
            for ((key, value) in env) {
                if (!ENV_KEY.matches(key)) continue
                append("export ").append(key).append('=').append(shellQuote(value)).append("; ")
            }
            append(command)
        }
        val argvCandidates = if (root) {
            resolveSuCandidates()
        } else {
            listOf(resolveLocalShell(), "sh").distinct()
        }

        var lastError: Throwable? = null
        for (binary in argvCandidates) {
            try {
                return ProcessBuilder(binary, "-c", payload)
                    .redirectErrorStream(false)
                    .start()
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("无法启动 shell 进程")
    }

    private fun toResult(root: Boolean, captured: Captured, startedAt: Long): ShellResult {
        val combined = if (captured.stdout.isNotEmpty()) captured.stdout else captured.stderr
        val normalized = if (captured.exitCode != 0) ShellErrors.normalize(combined) else null
        val code = normalized ?: captured.exitCode
        val result = ShellResult(
            code = code,
            stdout = captured.stdout,
            stderr = captured.stderr,
            durationMs = SystemClock.elapsedRealtime() - startedAt,
        )
        if (root && normalized != null && ShellErrors.isSuLevelDenial(combined)) {
            ShellErrors.toDenyReason(code)?.let { emitRootLoss(it, combined) }
        }
        return result
    }

    private fun startFailure(root: Boolean, t: Throwable, startedAt: Long): ShellResult {
        val code = startFailureCode(root)
        XgLog.e(TAG, "无法启动 ${if (root) "su" else "sh"} 进程", t)
        if (root) emitRootLoss(DenyReason.NO_BINARY, t.message.orEmpty())
        return ShellResult(
            code = code,
            stdout = "",
            stderr = ErrorCatalog.message(code),
            durationMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private fun timeoutResult(root: Boolean, startedAt: Long): ShellResult {
        val code = if (root) ErrorCode.ERR_SU_TIMEOUT else ErrorCode.ERR_CMD_TIMEOUT
        return ShellResult(
            code = code,
            stdout = "",
            stderr = ErrorCatalog.message(code),
            durationMs = SystemClock.elapsedRealtime() - startedAt,
        )
    }

    private fun startFailureCode(root: Boolean): Int =
        if (root) ErrorCode.ERR_SU_MISSING else ErrorCode.ERR_CMD_FAILED

    private fun emitRootLoss(reason: DenyReason, output: String) {
        if (output.isNotBlank()) {
            XgLog.w(TAG, "su 拒绝：${output.trim().lineSequence().firstOrNull().orEmpty().take(120)}")
        }
        _rootLossEvents.tryEmit(reason)
    }

    private fun resolveSuCandidates(): List<String> = buildList {
        suPathStore.customPath.value?.trim()?.takeIf { it.isNotEmpty() }?.let(::add)
        addAll(SuBinary.candidates())
        if (none { it == "su" }) add("su")
    }.distinct()

    private fun resolveLocalShell(): String = cachedLocalShell ?: run {
        val path = if (File(SYSTEM_SHELL).canExecute()) SYSTEM_SHELL else "sh"
        cachedLocalShell = path
        path
    }

    
    private suspend fun pump(
        stream: InputStream,
        isErr: Boolean,
        emitLine: suspend (ShellLine) -> Unit,
    ) {
        try {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    emitLine(ShellLine(line, isErr))
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.w(TAG, "流式输出读取结束: ${t.javaClass.simpleName}")
        }
    }

    private data class Captured(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
    )

    private companion object {
        const val TAG = "RootShell"
        const val SYSTEM_SHELL = "/system/bin/sh"
        const val MIN_TIMEOUT_MS = 200L
        const val EXIT_WAIT_MS = 1_000L
        val ENV_KEY = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}
