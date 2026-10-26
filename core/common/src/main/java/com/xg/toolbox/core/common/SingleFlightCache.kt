package com.xg.toolbox.core.common

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SingleFlightResultCache<T : Any> {
    private val mutex = Mutex()
    private var cached: T? = null

    suspend fun get(forceRefresh: Boolean, load: suspend () -> XgResult<T>): XgResult<T> = mutex.withLock {
        if (!forceRefresh) {
            cached?.let { return@withLock XgResult.Ok(it) }
        }
        when (val fresh = load()) {
            is XgResult.Ok -> {
                cached = fresh.data
                fresh
            }

            is XgResult.Err -> fresh
        }
    }

    suspend fun invalidate() {
        mutex.withLock { cached = null }
    }
}

class SingleFlightValueCache<T : Any> {
    private val mutex = Mutex()
    private var cached: T? = null

    suspend fun get(forceRefresh: Boolean, load: suspend () -> T?): T? = mutex.withLock {
        if (!forceRefresh) {
            cached?.let { return@withLock it }
        }
        val fresh = load()
        if (fresh != null) {
            cached = fresh
        }
        fresh ?: cached
    }

    suspend fun invalidate() {
        mutex.withLock { cached = null }
    }
}
