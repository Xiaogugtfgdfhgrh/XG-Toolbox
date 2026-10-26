package com.xg.toolbox.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BatchReceiptDao {

    @Insert
    suspend fun insert(entity: BatchReceiptEntity): Long

    @Query("SELECT * FROM batch_receipt ORDER BY ts DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BatchReceiptEntity>>

    @Query("SELECT * FROM batch_receipt WHERE id = :id")
    suspend fun get(id: Long): BatchReceiptEntity?
}
