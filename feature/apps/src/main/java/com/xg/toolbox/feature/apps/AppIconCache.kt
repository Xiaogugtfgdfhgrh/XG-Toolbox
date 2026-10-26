package com.xg.toolbox.feature.apps

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class AppIconCache @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val appContext = context.applicationContext
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decodePermits = Semaphore(MAX_CONCURRENT_DECODES)
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val keysByPackage = ConcurrentHashMap<String, String>()
    private val generation = AtomicInteger()
    private val cacheLock = Any()

    @Volatile
    private var lru: LruCache<String, ImageBitmap>? = null

    init {
        registerMemoryCallbacks()
    }

    @Suppress("DEPRECATION")
    private fun registerMemoryCallbacks() {
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    clear()
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                clear()
            }
        })
    }

    
    fun peek(packageName: String, versionCode: Long): ImageBitmap? =
        lru?.get(keyOf(packageName, versionCode))

    
    suspend fun load(packageName: String, versionCode: Long): ImageBitmap? {
        val key = keyOf(packageName, versionCode)
        keysByPackage.put(packageName, key)?.takeIf { it != key }?.let { stale -> cache().remove(stale) }
        if (keysByPackage.size > MAX_TRACKED_PACKAGES) keysByPackage.clear()
        peek(packageName, versionCode)?.let { return it }
        inFlight[key]?.let { return awaitSafely(it) }
        val currentGeneration = generation.get()
        val task = decodeScope.async {
            decodePermits.withPermit { decodeAndCache(packageName, key, currentGeneration) }
        }
        val existing = inFlight.putIfAbsent(key, task)
        if (existing != null) {
            task.cancel()
            return awaitSafely(existing)
        }
        task.invokeOnCompletion { inFlight.remove(key, task) }
        return awaitSafely(task)
    }

    private suspend fun awaitSafely(task: Deferred<ImageBitmap?>): ImageBitmap? = try {
        task.await()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        null
    }

    private fun keyOf(packageName: String, versionCode: Long): String = "$packageName@$versionCode"

    private fun decodeAndCache(
        packageName: String,
        key: String,
        expectedGeneration: Int,
    ): ImageBitmap? {
        if (generation.get() != expectedGeneration) return null
        cache().get(key)?.let { return it }
        val bitmap = runCatching {
            appContext.packageManager
                .getApplicationIcon(packageName)
                .toBitmap(ICON_PIXELS, ICON_PIXELS)
                .asImageBitmap()
        }.getOrNull()
        if (bitmap == null || generation.get() != expectedGeneration) return null
        cache().put(key, bitmap)
        return bitmap
    }

    private fun cache(): LruCache<String, ImageBitmap> {
        lru?.let { return it }
        synchronized(cacheLock) {
            lru?.let { return it }
            val capacity = ((Runtime.getRuntime().maxMemory() / 16L) / ENTRY_BYTES)
                .toInt()
                .coerceIn(MIN_CAPACITY, MAX_CAPACITY)
            return LruCache<String, ImageBitmap>(capacity).also { lru = it }
        }
    }

    private fun clear() {
        generation.incrementAndGet()
        keysByPackage.clear()
        synchronized(cacheLock) {
            lru?.evictAll()
        }
    }

    companion object {
        const val ICON_PIXELS = 80
        private const val MIN_CAPACITY = 128
        private const val MAX_CAPACITY = 512
        private const val MAX_TRACKED_PACKAGES = 1024
        private const val MAX_CONCURRENT_DECODES = 3
        private val ENTRY_BYTES = ICON_PIXELS.toLong() * ICON_PIXELS.toLong() * 4L
    }
}
