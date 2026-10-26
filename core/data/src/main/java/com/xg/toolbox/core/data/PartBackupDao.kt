package com.xg.toolbox.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PartBackupDao {

    @Insert
    suspend fun insert(entity: PartBackupEntity): Long

    @Query("SELECT * FROM part_backup ORDER BY ts DESC, id DESC")
    fun observeAll(): Flow<List<PartBackupEntity>>

    @Query("SELECT * FROM part_backup ORDER BY ts DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<PartBackupEntity>>

    @Query("SELECT * FROM part_backup WHERE partition = :partition ORDER BY ts DESC, id DESC LIMIT 1")
    suspend fun latestForPartition(partition: String): PartBackupEntity?

    @Query("DELETE FROM part_backup WHERE id = :id")
    suspend fun deleteById(id: Long)
}
