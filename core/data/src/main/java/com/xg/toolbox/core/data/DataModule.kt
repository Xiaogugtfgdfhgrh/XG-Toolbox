package com.xg.toolbox.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): XgDatabase =
        Room.databaseBuilder(context, XgDatabase::class.java, XgDatabase.NAME).build()

    @Provides
    fun provideAuditDao(database: XgDatabase): AuditDao = database.auditDao()

    @Provides
    fun provideSnapshotDao(database: XgDatabase): SnapshotDao = database.snapshotDao()

    @Provides
    fun provideBatchReceiptDao(database: XgDatabase): BatchReceiptDao = database.batchReceiptDao()

    @Provides
    fun providePartBackupDao(database: XgDatabase): PartBackupDao = database.partBackupDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsDataStore
}
