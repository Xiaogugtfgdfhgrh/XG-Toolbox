package com.xg.toolbox

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.data.BehaviorPreferences
import com.xg.toolbox.core.rootops.RootOpsManager
import com.xg.toolbox.root.RootProbeCoordinator
import com.xg.toolbox.work.SnapshotCleanupWorker
import com.xg.toolbox.warmup.AppWarmupCoordinator
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class XgApp : Application() {

    @Inject
    lateinit var rootProbeCoordinator: RootProbeCoordinator

    @Inject
    lateinit var rootOpsManager: RootOpsManager

    @Inject
    lateinit var behaviorPreferences: BehaviorPreferences
    @Inject
    lateinit var warmupCoordinator: AppWarmupCoordinator


    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        XgLog.init(this)
        XgLog.i(TAG, "application created; scheduling background work")
        scheduleSnapshotCleanup()
        rootOpsManager.startKeepAliveIfEnabled()
        appScope.launch {
            behaviorPreferences.syncBootState()
            rootProbeCoordinator.ensureProbed()
            warmupCoordinator.start()
        }
    }


    private fun scheduleSnapshotCleanup() {
        runCatching {
            val request = PeriodicWorkRequestBuilder<SnapshotCleanupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                SnapshotCleanupWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onFailure { t ->
            XgLog.e(TAG, "schedule snapshot cleanup failed", t)
        }
    }

    private companion object {
        const val TAG = "XgApp"
    }
}
