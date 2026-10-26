package com.xg.toolbox.core.common

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleFlightCacheTest {

    @Test
    fun resultCacheReusesSuccessfulValueUntilInvalidated() = runBlocking {
        val calls = AtomicInteger()
        val cache = SingleFlightResultCache<String>()

        assertEquals("first", cache.get(forceRefresh = false) {
            calls.incrementAndGet()
            XgResult.Ok("first")
        }.getOrNull())
        assertEquals("first", cache.get(forceRefresh = false) {
            calls.incrementAndGet()
            XgResult.Ok("second")
        }.getOrNull())
        assertEquals(1, calls.get())

        cache.invalidate()
        assertEquals("third", cache.get(forceRefresh = false) {
            calls.incrementAndGet()
            XgResult.Ok("third")
        }.getOrNull())
        assertEquals(2, calls.get())
    }

    @Test
    fun resultCacheFailureKeepsPreviousGoodValue() = runBlocking {
        val calls = AtomicInteger()
        val cache = SingleFlightResultCache<String>()

        cache.get(forceRefresh = false) {
            calls.incrementAndGet()
            XgResult.Ok("good")
        }

        val forced = cache.get(forceRefresh = true) {
            calls.incrementAndGet()
            XgResult.Err(ErrorCode.ERR_CMD_FAILED, "failed")
        }
        assertTrue(forced is XgResult.Err)

        val fallback = cache.get(forceRefresh = false) {
            calls.incrementAndGet()
            XgResult.Ok("unexpected")
        }
        assertEquals("good", fallback.getOrNull())
        assertEquals(2, calls.get())
    }

    @Test
    fun valueCacheFailureKeepsPreviousGoodValue() = runBlocking {
        val calls = AtomicInteger()
        val cache = SingleFlightValueCache<String>()

        assertEquals("good", cache.get(forceRefresh = false) {
            calls.incrementAndGet()
            "good"
        })
        assertEquals("good", cache.get(forceRefresh = true) {
            calls.incrementAndGet()
            null
        })
        assertEquals("good", cache.get(forceRefresh = false) {
            calls.incrementAndGet()
            "unexpected"
        })
        assertEquals(2, calls.get())
    }

    @Test
    fun concurrentCallersShareOneInFlightLoad() = runBlocking {
        val calls = AtomicInteger()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = SingleFlightResultCache<String>()

        val jobs = (1..12).map {
            async(Dispatchers.Default) {
                cache.get(forceRefresh = false) {
                    calls.incrementAndGet()
                    entered.complete(Unit)
                    release.await()
                    XgResult.Ok("shared")
                }.getOrNull()
            }
        }
        withTimeout(5_000) { entered.await() }
        release.complete(Unit)
        assertEquals(List(12) { "shared" }, jobs.awaitAll())
        assertEquals(1, calls.get())
    }

    @Test
    fun cancelledLoaderReleasesMutexAndCanBeRetried() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val cache = SingleFlightResultCache<String>()

        val cancelled = launch(Dispatchers.Default) {
            cache.get(forceRefresh = false) {
                entered.complete(Unit)
                never.await()
                XgResult.Ok("cancelled")
            }
        }
        withTimeout(5_000) { entered.await() }
        cancelled.cancelAndJoin()

        val retried = withTimeout(5_000) {
            async(Dispatchers.Default) {
                cache.get(forceRefresh = false) { XgResult.Ok("retried") }.getOrNull()
            }
        }.await()
        assertEquals("retried", retried)
    }
}
