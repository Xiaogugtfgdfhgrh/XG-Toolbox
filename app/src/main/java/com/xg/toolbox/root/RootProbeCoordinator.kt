package com.xg.toolbox.root

import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.shell.RootStatusRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RootProbeCoordinator @Inject constructor(
    private val repository: RootStatusRepository,
) {
    private val mutex = Mutex()
    private val _settled = MutableStateFlow(false)

    
    val settled: StateFlow<Boolean> = _settled.asStateFlow()

    
    suspend fun ensureProbed() {
        if (_settled.value) return
        mutex.withLock {
            if (_settled.value) return
            probeLocked()
        }
    }

    
    suspend fun reprobe() {
        mutex.withLock { probeLocked() }
    }

    private suspend fun probeLocked() {
        try {
            repository.refresh()
        } catch (t: Throwable) {
            XgLog.e(TAG, "root probe failed", t)
        } finally {
            _settled.value = true
        }
    }

    private companion object {
        const val TAG = "RootProbe"
    }
}
