package com.xg.toolbox.core.device

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class ProcessForegroundGate(context: Context) : Application.ActivityLifecycleCallbacks {

    private val startedCount = AtomicInteger(0)

    private val _isForeground: MutableStateFlow<Boolean>

    
    val isForeground: StateFlow<Boolean>

    init {
        val application = context.applicationContext as? Application
        _isForeground = MutableStateFlow(true)
        isForeground = _isForeground.asStateFlow()
        application?.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        if (startedCount.incrementAndGet() == 1) {
            _isForeground.value = true
        }
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        if (startedCount.decrementAndGet() <= 0) {
            startedCount.set(0)
            _isForeground.value = false
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
