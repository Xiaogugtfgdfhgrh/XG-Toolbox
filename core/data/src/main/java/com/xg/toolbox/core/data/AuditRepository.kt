package com.xg.toolbox.core.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

object AuditCategory {
    const val APP = "app"
    const val SYSTEM = "system"
    const val FLASH = "flash"
    const val SECURITY = "security"
    const val TERMINAL = "terminal"
}

object AuditResult {
    const val OK = "ok"
    const val FAILED = "failed"
    const val ROLLED_BACK = "rolled_back"
}

@Singleton
class AuditRepository @Inject constructor(private val auditDao: AuditDao) {

    
    suspend fun record(
        category: String,
        action: String,
        target: String,
        commands: String,
        result: String,
        message: String? = null,
        snapshotId: Long? = null,
    ): Long = withContext(Dispatchers.IO) {
        auditDao.insert(
            AuditEntity(
                ts = System.currentTimeMillis(),
                category = category,
                action = action,
                target = target,
                commands = commands,
                result = result,
                message = message,
                snapshotId = snapshotId,
            ),
        )
    }

    
    fun observeRecent(limit: Int = 3): Flow<List<AuditEntity>> = auditDao.observeRecent(limit)

    
    fun observeAll(limit: Int = 50): Flow<List<AuditEntity>> = auditDao.observeAll(limit)

    
    fun observePage(offset: Int): Flow<List<AuditEntity>> = auditDao.observePage(offset)

    
    suspend fun count(): Int = withContext(Dispatchers.IO) { auditDao.count() }
}
