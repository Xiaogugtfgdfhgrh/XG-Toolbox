package com.xg.toolbox.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

@Singleton
class PartBackupRepository @Inject constructor(private val partBackupDao: PartBackupDao) {

    
    suspend fun record(partition: String, path: String, bytes: Long, sha256: String): Long =
        withContext(Dispatchers.IO) {
            partBackupDao.insert(
                PartBackupEntity(
                    ts = System.currentTimeMillis(),
                    partition = partition,
                    path = path,
                    bytes = bytes,
                    sha256 = sha256,
                ),
            )
        }

    fun observeAll(): Flow<List<PartBackupEntity>> = partBackupDao.observeAll()

    fun observeRecent(limit: Int = 20): Flow<List<PartBackupEntity>> =
        partBackupDao.observeRecent(limit)

    
    suspend fun latestForPartition(partition: String): PartBackupEntity? =
        withContext(Dispatchers.IO) { partBackupDao.latestForPartition(partition) }

    suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) { partBackupDao.deleteById(id) }
    }
}
