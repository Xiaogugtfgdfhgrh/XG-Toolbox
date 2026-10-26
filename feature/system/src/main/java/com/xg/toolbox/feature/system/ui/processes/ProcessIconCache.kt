package com.xg.toolbox.feature.system.ui.processes

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Singleton
class ProcessIconCache @Inject constructor(
    @ApplicationContext context: Context,
) {

    private val appContext = context.applicationContext
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decodePermits = Semaphore(MAX_CONCURRENT_DECODES)
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()
    private val versionKeys = ConcurrentHashMap<String, String>()
    private val generation = AtomicInteger()
    private val cacheLock = Any()

    @Volatile
    private var lru: LruCache<String, ImageBitmap>? = null

    init {
        appContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) clear()
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            override fun onLowMemory() {
                clear()
            }
        })
    }

    
    fun peek(packageName: String): ImageBitmap? {
        val key = versionKeys[packageName] ?: return null
        return cache().get(key)
    }

    
    suspend fun load(packageName: String): ImageBitmap? {
        versionKeys[packageName]?.let { key -> cache().get(key)?.let { return it } }
        inFlight[packageName]?.let { return it.await() }
        val currentGeneration = generation.get()
        val task = decodeScope.async {
            decodePermits.withPermit {
                val key = resolveKey(packageName, currentGeneration) ?: return@withPermit null
                decodeAndCache(packageName, key, currentGeneration)
            }
        }
        val existing = inFlight.putIfAbsent(packageName, task)
        if (existing != null) {
            task.cancel()
            return existing.await()
        }
        task.invokeOnCompletion { inFlight.remove(packageName, task) }
        return task.await()
    }

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

    
    private fun resolveKey(packageName: String, expectedGeneration: Int): String? = runCatching {
        val manager = appContext.packageManager
        manager.getApplicationInfo(packageName, 0)
        val versionCode = manager.getPackageInfo(packageName, 0).longVersionCode
        val key = "$packageName@$versionCode"
        if (generation.get() == expectedGeneration) {
            versionKeys.put(packageName, key)?.takeIf { it != key }?.let { stale -> cache().remove(stale) }
            if (versionKeys.size > MAX_VERSION_KEYS) versionKeys.clear()
        }
        key
    }.getOrNull()

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
        versionKeys.clear()
        synchronized(cacheLock) {
            lru?.evictAll()
        }
    }

    private companion object {
        
        const val ICON_PIXELS = 84
        const val MIN_CAPACITY = 128
        const val MAX_CAPACITY = 384
        const val MAX_VERSION_KEYS = 1024
        const val MAX_CONCURRENT_DECODES = 3
        val ENTRY_BYTES = ICON_PIXELS.toLong() * ICON_PIXELS.toLong() * 4L
    }
}
