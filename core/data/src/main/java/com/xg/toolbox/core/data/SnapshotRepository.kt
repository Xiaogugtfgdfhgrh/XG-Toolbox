package com.xg.toolbox.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class SnapshotRepository @Inject constructor(private val snapshotDao: SnapshotDao) {

    
    suspend fun create(kind: String, target: String, payload: String, ttlMs: Long): Long =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            snapshotDao.insert(
                SnapshotEntity(
                    ts = now,
                    kind = kind,
                    target = target,
                    payload = payload,
                    expiresAt = now + ttlMs,
                ),
            )
        }

    suspend fun get(id: Long): SnapshotEntity? = withContext(Dispatchers.IO) { snapshotDao.get(id) }

    fun observeAll(): Flow<List<SnapshotEntity>> = snapshotDao.observeAll()

    suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) { snapshotDao.deleteById(id) }
    }

    
    suspend fun latest(kind: String, target: String): SnapshotEntity? =
        withContext(Dispatchers.IO) { snapshotDao.latest(kind, target) }

    
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) { snapshotDao.deleteExpired(now) }
}
