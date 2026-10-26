package com.xg.toolbox.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.data.SnapshotRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class SnapshotCleanupWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val repository = EntryPointAccessors.fromApplication(
                applicationContext,
                SnapshotCleanupEntryPoint::class.java,
            ).snapshotRepository()
            val removed = repository.purgeExpired(System.currentTimeMillis())
            XgLog.i(TAG, "snapshot cleanup finished, removed=$removed")
            Result.success(workDataOf(KEY_REMOVED to removed))
        } catch (t: Throwable) {
            XgLog.e(TAG, "snapshot cleanup failed", t)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "xg_snapshot_cleanup"
        const val KEY_REMOVED = "removed"
        private const val MAX_ATTEMPTS = 3
        private const val TAG = "SnapshotCleanup"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SnapshotCleanupEntryPoint {
    fun snapshotRepository(): SnapshotRepository
}
