package com.xg.toolbox.warmup

import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.AppRepository
import com.xg.toolbox.core.device.DeviceInfoRepository
import com.xg.toolbox.core.device.MetricsSampler
import com.xg.toolbox.core.device.RiskEvaluator
import com.xg.toolbox.core.rootops.RootOpsManager
import com.xg.toolbox.core.shell.RootState
import com.xg.toolbox.core.shell.RootStatusRepository
import com.xg.toolbox.feature.security.domain.AccessibilityGuard
import com.xg.toolbox.feature.security.domain.AdHostsBlocker
import com.xg.toolbox.feature.security.domain.AppOpsController
import com.xg.toolbox.feature.security.domain.AutostartScanner
import com.xg.toolbox.feature.system.domain.clean.CleanController
import com.xg.toolbox.feature.system.domain.display.DisplayController
import com.xg.toolbox.feature.system.domain.flash.FlashController
import com.xg.toolbox.feature.system.domain.optimize.OptimizeController
import com.xg.toolbox.feature.system.domain.perf.PerfController
import com.xg.toolbox.feature.system.domain.processes.ProcessController
import com.xg.toolbox.feature.system.domain.scripts.ScriptRunner
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class WarmupKey {
    ROOT,
    ROOT_OPS,
    METRICS,
    DEVICE,
    RISKS,
    APPS,
    A11Y,
    HOSTS,
    AUTOSTART,
    APPOPS,
    SCRIPTS,
    PROCESSES,
    DISPLAY,
    PERF,
    FLASH,
    OPTIMIZE,
    CLEAN,
}

data class WarmupSnapshot(
    val key: WarmupKey,
    val count: Int,
    val completedAt: Long,
    val detail: String,
)

data class WarmupState(
    val running: Boolean = false,
    val completed: Boolean = false,
    val total: Int = WarmupKey.entries.size,
    val succeeded: Int = 0,
    val failed: Int = 0,
    val snapshots: Map<WarmupKey, WarmupSnapshot> = emptyMap(),
    val failures: Map<WarmupKey, String> = emptyMap(),
    val message: String = "",
)

@Singleton
class AppWarmupCoordinator @Inject constructor(
    private val rootStatusRepository: RootStatusRepository,
    private val rootOpsManager: RootOpsManager,
    private val metricsSampler: MetricsSampler,
    private val deviceInfoRepository: DeviceInfoRepository,
    private val riskEvaluator: RiskEvaluator,
    private val appRepository: AppRepository,
    private val accessibilityGuard: AccessibilityGuard,
    private val adHostsBlocker: AdHostsBlocker,
    private val autostartScanner: AutostartScanner,
    private val appOpsController: AppOpsController,
    private val scriptRunner: ScriptRunner,
    private val processController: ProcessController,
    private val displayController: DisplayController,
    private val perfController: PerfController,
    private val flashController: FlashController,
    private val optimizeController: OptimizeController,
    private val cleanController: CleanController,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val flightMutex = Mutex()
    private val inFlight = ConcurrentHashMap<WarmupKey, Deferred<WarmupSnapshot>>()
    private val cached = ConcurrentHashMap<WarmupKey, WarmupSnapshot>()
    private val failed = ConcurrentHashMap<WarmupKey, String>()

    private val _state = MutableStateFlow(WarmupState())
    val state: StateFlow<WarmupState> = _state.asStateFlow()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        publish("开始预热", completed = false)
        scope.launch {
            try {
                warmUp()
            } catch (t: Throwable) {
                XgLog.e(TAG, "warmup failed", t)
            } finally {
                running.set(false)
                _state.value = _state.value.copy(
                    running = false,
                    completed = true,
                    message = "预热流程结束：成功 ${cached.size}，失败 ${failed.size}",
                )
            }
        }
    }

    fun retryFailed() = start()

    fun cachedSnapshot(key: WarmupKey): WarmupSnapshot? = cached[key]

    private suspend fun warmUp() {
        publish("预热首屏机况")
        runBatch(priorityTasks(), parallel = 2)
        publish("预热应用、安全与系统状态")
        runBatch(secondaryTasks(), parallel = 2)
        delay(TERTIARY_DELAY_MS)
        publish("预热低优先级系统扫描")
        runBatch(tertiaryTasks(), parallel = 1)
    }

    private fun priorityTasks(): List<WarmTask> = listOf(
        WarmTask(WarmupKey.DEVICE) {
            count = deviceInfoRepository.collect().sumOf { it.fields.size }
        },
        WarmTask(WarmupKey.ROOT) {
            if (rootStatusRepository.state.value is RootState.Unknown) {
                rootStatusRepository.refresh()
            }
            count = 1
        },
        WarmTask(WarmupKey.METRICS) {
            val snapshot = withTimeoutOrNull(METRICS_TIMEOUT_MS) { metricsSampler.stream().first() }
                ?: error("指标采样超时")
            count = 1
            detail = "cpuCores=${snapshot.cpuCores}, battery=${snapshot.batteryPercent}"
        },
        WarmTask(WarmupKey.RISKS) {
            count = riskEvaluator.evaluate().size
        },
        WarmTask(WarmupKey.ROOT_OPS) {
            rootOpsManager.refreshStatus()
            count = 1
        },
    )
    private fun secondaryTasks(): List<WarmTask> = listOf(
        WarmTask(WarmupKey.APPS) {
            count = appRepository.list().requireOk("应用列表读取失败").size
        },
        WarmTask(WarmupKey.A11Y) {
            count = accessibilityGuard.read().enabledServices.size
        },
        WarmTask(WarmupKey.HOSTS) {
            val status = adHostsBlocker.status()
            count = status.domainCount
            detail = "enabled=${status.enabled}, supported=${status.supported}"
        },
        WarmTask(WarmupKey.AUTOSTART) {
            count = autostartScanner.list().requireOk("开机接收器读取失败").size
        },
        WarmTask(WarmupKey.APPOPS) {
            val apps = appRepository.list().requireOk("应用列表缓存不可用")
            count = appOpsController.load(apps.take(APPOPS_SAMPLE).map { it.packageName }).size
        },
        WarmTask(WarmupKey.SCRIPTS) {
            count = scriptRunner.scripts().size
        },
        WarmTask(WarmupKey.PROCESSES) {
            requireRoot()
            count = processController.list().requireOk("进程列表读取失败").size
        },
        WarmTask(WarmupKey.DISPLAY) {
            requireRoot()
            displayController.readState().requireOk("显示状态读取失败")
            count = 1
        },
        WarmTask(WarmupKey.PERF) {
            requireRoot()
            perfController.readState().requireOk("性能状态读取失败")
            count = 1
        },
        WarmTask(WarmupKey.FLASH) {
            requireRoot()
            flashController.environment().requireOk("刷写环境读取失败")
            count = 1
        },
    )

    private fun tertiaryTasks(): List<WarmTask> = listOf(
        WarmTask(WarmupKey.OPTIMIZE) {
            requireRoot()
            count = optimizeController.scan().requireOk("系统优化扫描失败").items.size
        },
        WarmTask(WarmupKey.CLEAN) {
            requireRoot()
            count = cleanController.scan().requireOk("垃圾扫描失败").categories.size
        },
    )

    private suspend fun runBatch(tasks: List<WarmTask>, parallel: Int) = coroutineScope {
        tasks.chunked(parallel.coerceAtLeast(1)).forEach { chunk ->
            chunk.map { task -> async { runTask(task) } }.awaitAll()
            publish("预热批次完成")
        }
    }

    private suspend fun runTask(task: WarmTask): WarmupSnapshot? {
        cached[task.key]?.let { return it }
        val deferred = flightMutex.withLock {
            inFlight[task.key] ?: scope.async { execute(task) }.also { created ->
                inFlight[task.key] = created
                created.invokeOnCompletion { inFlight.remove(task.key, created) }
            }
        }
        return try {
            deferred.await()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val message = t.message?.take(180) ?: t.javaClass.simpleName
            failed[task.key] = message
            XgLog.w(TAG, "warmup ${task.key} failed: $message")
            null
        } finally {
            publish("${task.key} 完成")
        }
    }

    private suspend fun execute(task: WarmTask): WarmupSnapshot {
        task.load()
        val snapshot = WarmupSnapshot(
            key = task.key,
            count = task.count,
            completedAt = System.currentTimeMillis(),
            detail = task.detail,
        )
        cached[task.key] = snapshot
        failed.remove(task.key)
        return snapshot
    }

    private fun publish(message: String, completed: Boolean = false) {
        _state.value = WarmupState(
            running = running.get(),
            completed = completed,
            total = WarmupKey.entries.size,
            succeeded = cached.size,
            failed = failed.size,
            snapshots = cached.toMap(),
            failures = failed.toMap(),
            message = message,
        )
    }

    private suspend fun requireRoot() {
        if (rootStatusRepository.state.value !is RootState.Granted) {
            error("Root 未授权，系统特权数据暂不预热")
        }
    }

    private fun <T> XgResult<T>.requireOk(context: String): T = when (this) {
        is XgResult.Ok -> data
        is XgResult.Err -> error("$context：$message")
    }

    private inner class WarmTask(
        val key: WarmupKey,
        private val block: suspend WarmTask.() -> Unit,
    ) {
        var count: Int = 0
        var detail: String = ""

        suspend fun load() = block()
    }

    private companion object {
        const val TAG = "AppWarmup"
        const val METRICS_TIMEOUT_MS = 2_500L
        const val TERTIARY_DELAY_MS = 1_200L
        const val APPOPS_SAMPLE = 24
    }
}
