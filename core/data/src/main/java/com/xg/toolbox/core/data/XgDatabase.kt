package com.xg.toolbox.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AuditEntity::class,
        SnapshotEntity::class,
        BatchReceiptEntity::class,
        PartBackupEntity::class,
    ],
    version = XgDatabase.VERSION,
    exportSchema = true,
)
abstract class XgDatabase : RoomDatabase() {

    abstract fun auditDao(): AuditDao

    abstract fun snapshotDao(): SnapshotDao

    abstract fun batchReceiptDao(): BatchReceiptDao

    abstract fun partBackupDao(): PartBackupDao

    companion object {
        const val VERSION = 1
        const val NAME = "xg.db"
    }
}
