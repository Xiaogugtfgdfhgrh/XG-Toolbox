package com.xg.toolbox.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class BatchReceiptRepository @Inject constructor(private val batchReceiptDao: BatchReceiptDao) {

    
    suspend fun record(
        action: String,
        total: Int,
        ok: Int,
        failed: Int,
        skipped: Int,
        body: String,
    ): Long = withContext(Dispatchers.IO) {
        batchReceiptDao.insert(
            BatchReceiptEntity(
                ts = System.currentTimeMillis(),
                action = action,
                total = total,
                ok = ok,
                failed = failed,
                skipped = skipped,
                body = body,
            ),
        )
    }

    fun observeRecent(limit: Int = 20): Flow<List<BatchReceiptEntity>> =
        batchReceiptDao.observeRecent(limit)

    suspend fun get(id: Long): BatchReceiptEntity? = withContext(Dispatchers.IO) {
        batchReceiptDao.get(id)
    }
}
