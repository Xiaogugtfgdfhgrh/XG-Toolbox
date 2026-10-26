package com.xg.toolbox.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "audit_log", indices = [Index(value = ["ts"])])
data class AuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val category: String,
    val action: String,
    val target: String,
    val commands: String,
    val result: String,
    val message: String?,
    val snapshotId: Long?,
)

@Entity(tableName = "snapshot", indices = [Index(value = ["expiresAt"])])
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val kind: String,
    val target: String,
    val payload: String,
    val expiresAt: Long,
)

@Entity(tableName = "batch_receipt", indices = [Index(value = ["ts"])])
data class BatchReceiptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val action: String,
    val total: Int,
    val ok: Int,
    val failed: Int,
    val skipped: Int,
    val body: String,
)

@Entity(tableName = "part_backup", indices = [Index(value = ["ts"])])
data class PartBackupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val partition: String,
    val path: String,
    val bytes: Long,
    val sha256: String,
)
