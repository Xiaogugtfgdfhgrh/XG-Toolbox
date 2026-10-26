package com.xg.toolbox.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AuditDao {

    @Insert
    suspend fun insert(entity: AuditEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY ts DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<AuditEntity>>

    @Query("SELECT * FROM audit_log ORDER BY ts DESC, id DESC LIMIT :limit")
    fun observeAll(limit: Int): Flow<List<AuditEntity>>

    
    @Query("SELECT * FROM audit_log ORDER BY ts DESC, id DESC LIMIT :limit OFFSET :offset")
    fun observePage(offset: Int, limit: Int = PAGE_SIZE): Flow<List<AuditEntity>>

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun count(): Int

    companion object {
        const val PAGE_SIZE = 50
    }
}
