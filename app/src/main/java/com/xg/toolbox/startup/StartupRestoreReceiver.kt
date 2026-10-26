package com.xg.toolbox.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.core.rootops.StartupRecoveryContract
import com.xg.toolbox.core.rootops.StartupRecoveryJournal
import com.xg.toolbox.feature.security.A11yMonitor
import com.xg.toolbox.work.StartupRecoveryWorker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class StartupRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StartupRecoveryContract.ACTION_RECOVER_BACKGROUND_FEATURES) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            var journal: StartupRecoveryJournal? = null
            try {
                val entry = EntryPointAccessors.fromApplication(
                    context.applicationContext,
                    StartupRestoreEntryPoint::class.java,
                )
                val recoveryJournal = entry.startupRecoveryJournal()
                journal = recoveryJournal
                val source = intent.getStringExtra(EXTRA_SOURCE).orEmpty()
                try {
                    val whitelist = withTimeoutOrNull(A11Y_READ_TIMEOUT_MS) {
                        entry.settingsStore().a11yWhitelist.first()
                    } ?: error("读取无障碍白名单超时")
                    if (whitelist.isNotEmpty()) {
                        if (!A11yMonitor.start(context)) {
                            error("无障碍监视前台服务启动请求被拒绝")
                        }
                        recoveryJournal.record("a11y-monitor", true, "source=$source，白名单 ${whitelist.size} 项")
                    } else {
                        recoveryJournal.record("a11y-monitor", true, "source=$source，用户未启用无障碍监视")
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    recoveryJournal.record("a11y-monitor", false, t.message ?: t.javaClass.simpleName)
                    XgLog.e(TAG, "a11y boot recovery failed", t)
                }

                runCatching {
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        StartupRecoveryWorker.UNIQUE_NAME,
                        ExistingWorkPolicy.KEEP,
                        OneTimeWorkRequestBuilder<StartupRecoveryWorker>().build(),
                    )
                }.onSuccess {
                    recoveryJournal.record("hosts-worker-enqueue", true, "source=$source")
                }.onFailure { t ->
                    recoveryJournal.record("hosts-worker-enqueue", false, t.message ?: "enqueue failed")
                    XgLog.e(TAG, "hosts recovery enqueue failed", t)
                }
            } catch (c: CancellationException) {
                journal?.record("startup-restore", false, "cancelled")
                throw c
            } catch (t: Throwable) {
                journal?.record("startup-restore", false, t.message ?: t.javaClass.simpleName)
                XgLog.e(TAG, "startup restore dispatch failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "StartupRestore"
        const val EXTRA_SOURCE = "source"
        const val A11Y_READ_TIMEOUT_MS = 7_000L
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StartupRestoreEntryPoint {
    fun settingsStore(): SettingsStore
    fun startupRecoveryJournal(): StartupRecoveryJournal
}
