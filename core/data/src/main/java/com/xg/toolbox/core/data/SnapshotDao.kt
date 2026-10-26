package com.xg.toolbox.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SnapshotDao {

    @Insert
    suspend fun insert(entity: SnapshotEntity): Long

    @Query("SELECT * FROM snapshot WHERE id = :id")
    suspend fun get(id: Long): SnapshotEntity?

    @Query("SELECT * FROM snapshot ORDER BY ts DESC, id DESC")
    fun observeAll(): Flow<List<SnapshotEntity>>

    
    @Query("SELECT * FROM snapshot WHERE kind = :kind AND target = :target ORDER BY ts DESC, id DESC LIMIT 1")
    suspend fun latest(kind: String, target: String): SnapshotEntity?

    @Query("DELETE FROM snapshot WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM snapshot WHERE expiresAt < :now ORDER BY ts ASC")
    suspend fun expired(now: Long): List<SnapshotEntity>

    @Query("DELETE FROM snapshot WHERE expiresAt < :now")
    suspend fun deleteExpired(now: Long): Int
}
