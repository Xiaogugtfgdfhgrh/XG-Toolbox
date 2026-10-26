package com.xg.toolbox.core.shell

import com.xg.toolbox.core.common.ErrorCatalog
import com.xg.toolbox.core.common.ErrorCode
import com.xg.toolbox.core.common.XgLog
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter

object TerminalText {

    private val ANSI = Regex("\u001B\\[[;\\d]*[ -/]*[@-~]")
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]")

    
    fun stripAnsi(text: String): String = ANSI.replace(text, "")

    
    fun clean(text: String): String = CONTROL.replace(stripAnsi(text), "·")
}

@Singleton
class TerminalSession @Inject constructor(private val shell: Shell) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionMutex = Mutex()

    private val _lines = MutableSharedFlow<ShellLine>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    
    val lines: SharedFlow<ShellLine> = _lines.asSharedFlow()

    private val _running = MutableStateFlow(false)

    
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _rootSession = MutableStateFlow(false)

    
    val isRootSession: StateFlow<Boolean> = _rootSession.asStateFlow()

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var sessionGeneration = 0
    private var readerJobs: List<Job> = emptyList()
    private var exitJob: Job? = null

    
    suspend fun start() {
        sessionMutex.withLock {
            if (_running.value && process?.isAlive == true) return
            stopLocked()
            val rootAvailable = preflightRoot()
            val command = if (rootAvailable) {
                arrayOf(SuBinary.resolveOrPath(), "-c", "exec /system/bin/sh")
            } else {
                arrayOf(if (File(SYSTEM_SHELL).canExecute()) SYSTEM_SHELL else "sh")
            }
            val newProcess = try {
                ProcessBuilder(*command).redirectErrorStream(false).start()
            } catch (t: Throwable) {
                XgLog.e(TAG, "终端会话启动失败", t)
                _lines.tryEmit(ShellLine("终端会话启动失败：${t.message ?: t.javaClass.simpleName}", true))
                return
            }
            openLocked(newProcess)
            _rootSession.value = rootAvailable
            val hint = if (rootAvailable) {
                "— Root 终端已连接 —"
            } else {
                "— 未获得 Root，已启动普通 shell（仅只读命令可用）—"
            }
            _lines.tryEmit(ShellLine(hint, false))
        }
    }

    
    suspend fun send(command: String) {
        withContext(Dispatchers.IO) {
            sessionMutex.withLock {
                val writer = stdin
                if (!_running.value || writer == null) {
                    _lines.tryEmit(ShellLine("会话未启动，命令未执行：$command", true))
                    return@withLock
                }
                try {
                    writer.write(command)
                    writer.write("\n")
                    writer.flush()
                } catch (t: Throwable) {
                    XgLog.w(TAG, "终端写入失败: ${t.javaClass.simpleName}")
                    _lines.tryEmit(ShellLine("写入终端失败：${t.message ?: t.javaClass.simpleName}", true))
                }
            }
        }
    }

    
    fun interrupt() {
        scope.launch {
            sessionMutex.withLock {
                val writer = stdin ?: return@withLock
                try {
                    writer.write(INTERRUPT_CHAR)
                    writer.flush()
                } catch (t: Throwable) {
                    XgLog.w(TAG, "终端中断写入失败: ${t.javaClass.simpleName}")
                }
            }
        }
    }

    
    fun close() {
        scope.launch {
            sessionMutex.withLock { stopLocked() }
        }
    }

    
    private suspend fun preflightRoot(): Boolean {
        val result = shell.exec("id -u", root = true, timeoutMs = PREFLIGHT_TIMEOUT_MS)
        return result.ok && result.stdout.trim() == "0"
    }

    private fun openLocked(newProcess: Process) {
        val generation = ++sessionGeneration
        process = newProcess
        try {
            stdin = newProcess.outputStream.bufferedWriter(Charsets.UTF_8)
        } catch (t: Throwable) {
            XgLog.e(TAG, "终端 stdin 获取失败", t)
            newProcess.destroyForcibly()
            return
        }
        _running.value = true
        readerJobs = listOf(
            scope.launch { pump(newProcess.inputStream, false) },
            scope.launch { pump(newProcess.errorStream, true) },
        )
        exitJob = scope.launch {
            val code = withContext(Dispatchers.IO) { newProcess.waitFor() }
            if (generation != sessionGeneration) return@launch
            _running.value = false
            _rootSession.value = false
            stdin = null
            process = null
            readerJobs = emptyList()
            exitJob = null
            if (code == 0) {
                _lines.tryEmit(ShellLine("[会话已结束]", false))
            } else {
                _lines.tryEmit(ShellLine("[会话已结束，退出码 $code]", true))
            }
        }
    }

    private suspend fun pump(stream: InputStream, isErr: Boolean) {
        try {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    _lines.emit(ShellLine(TerminalText.clean(line), isErr))
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            XgLog.w(TAG, "终端输出读取结束: ${t.javaClass.simpleName}")
        }
    }

    
    private fun stopLocked() {
        sessionGeneration++
        readerJobs.forEach { it.cancel() }
        exitJob?.cancel()
        readerJobs = emptyList()
        exitJob = null
        try {
            stdin?.close()
        } catch (_: Throwable) {
        }
        stdin = null
        process?.let { running ->
            if (running.isAlive) running.destroyForcibly()
        }
        process = null
        _rootSession.value = false
        if (_running.value) {
            _running.value = false
            _lines.tryEmit(ShellLine("[会话已关闭]", false))
        }
    }

    private companion object {
        const val TAG = "TerminalSession"
        const val SYSTEM_SHELL = "/system/bin/sh"
        const val PREFLIGHT_TIMEOUT_MS = 5_000L
        const val INTERRUPT_CHAR = "\u0003"
    }
}
