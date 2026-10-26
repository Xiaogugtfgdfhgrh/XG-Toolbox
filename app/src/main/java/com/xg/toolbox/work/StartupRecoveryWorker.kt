package com.xg.toolbox.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.rootops.StartupRecoveryJournal
import com.xg.toolbox.feature.security.domain.AdHostsBlocker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

class StartupRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        var journal: StartupRecoveryJournal? = null
        return try {
            val entry = EntryPointAccessors.fromApplication(
                applicationContext,
                StartupRecoveryEntryPoint::class.java,
            )
            val recoveryJournal = entry.startupRecoveryJournal()
            journal = recoveryJournal
            when (val result = entry.adHostsBlocker().restorePersisted()) {
                is XgResult.Ok -> {
                    val message = result.data.message.orEmpty()
                    recoveryJournal.record("hosts-restore", true, message)
                    Result.success()
                }

                is XgResult.Err -> {
                    recoveryJournal.record("hosts-restore", false, result.detail ?: result.message)
                    XgLog.w(TAG, "hosts boot recovery failed: ${result.message}")
                    if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            journal?.record("hosts-restore", false, message)
            XgLog.e(TAG, "hosts boot recovery crashed", t)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "xg_startup_feature_recovery"
        private const val MAX_ATTEMPTS = 3
        private const val TAG = "StartupRecovery"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface StartupRecoveryEntryPoint {
    fun adHostsBlocker(): AdHostsBlocker
    fun startupRecoveryJournal(): StartupRecoveryJournal
}
