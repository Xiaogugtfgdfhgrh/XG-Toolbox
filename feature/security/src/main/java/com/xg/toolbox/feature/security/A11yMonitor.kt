package com.xg.toolbox.feature.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.SettingsStore
import com.xg.toolbox.core.rootops.StartupRecoveryJournal
import com.xg.toolbox.feature.security.domain.AccessibilityGuard
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

object A11yMonitor {

    @Volatile
    var running: Boolean = false
        internal set

    
    fun start(context: Context): Boolean {
        return try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, A11yMonitorService::class.java),
            )
            true
        } catch (t: Throwable) {
            XgLog.e(TAG, "启动无障碍监视服务失败", t)
            false
        }
    }

    fun stop(context: Context) {
        try {
            context.stopService(Intent(context, A11yMonitorService::class.java))
        } catch (t: Throwable) {
            XgLog.e(TAG, "停止无障碍监视服务失败", t)
        }
    }

    internal const val TAG = "A11yMonitor"
}

@AndroidEntryPoint
class A11yMonitorService : Service() {

    @Inject
    lateinit var guard: AccessibilityGuard

    @Inject
    lateinit var settingsStore: SettingsStore

    @Inject
    lateinit var recoveryJournal: StartupRecoveryJournal

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null
    private var reconcileJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val foregroundFailure = runCatching { startForegroundCompat() }.exceptionOrNull()
        if (foregroundFailure != null) {
            recoveryJournal.record(
                "a11y-service",
                false,
                foregroundFailure.message ?: foregroundFailure.javaClass.simpleName,
            )
            XgLog.e(A11yMonitor.TAG, "无障碍监视前台服务启动失败", foregroundFailure)
            stopSelf()
            return
        }
        A11yMonitor.running = true
        recoveryJournal.record("a11y-service", true, "无障碍监视前台服务已启动")
        registerObserver()
        scope.launch {
            settingsStore.a11yWhitelist.collect { whitelist ->
                if (whitelist.isEmpty()) {
                    stopSelf()
                } else {
                    scheduleReconcile(initial = true)
                }
            }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        reconcileJob?.cancel()
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        scope.cancel()
        A11yMonitor.running = false
        super.onDestroy()
    }

    private fun registerObserver() {
        val handler = Handler(Looper.getMainLooper())
        val newObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                scheduleReconcile(initial = false)
            }
        }
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(SETTING_SERVICES),
            false,
            newObserver,
        )
        observer = newObserver
    }

    private fun scheduleReconcile(initial: Boolean) {
        reconcileJob?.cancel()
        reconcileJob = scope.launch {
            if (!initial) delay(DEBOUNCE_MS)
            reconcile()
        }
    }

    private suspend fun reconcile() {
        val state = guard.read()
        val whitelist = state.whitelist
        if (whitelist.isEmpty()) {
            stopSelf()
            return
        }
        val illegal = state.enabledServices.filter { it !in whitelist }.distinct()
        if (illegal.isEmpty()) return
        when (val result = guard.applyWhitelist(whitelist)) {
            is XgResult.Ok -> {
                val after = guard.read().enabledServices.toSet()
                val remaining = illegal.filter { it in after }
                if (remaining.isEmpty()) {
                    showToast(
                        getString(R.string.sec_a11y_monitor_blocked, illegal.joinToString("、")),
                    )
                } else {
                    showToast(
                        getString(
                            R.string.sec_a11y_monitor_write_failed,
                            "回读仍包含 ${remaining.joinToString("、")}",
                        ),
                    )
                }
            }

            is XgResult.Err -> {
                showToast(getString(R.string.sec_a11y_monitor_write_failed, result.message))
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.sec_a11y_monitor_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.sec_a11y_monitor_title))
            .setContentText(getString(R.string.sec_a11y_monitor_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
        launchIntent()?.let(builder::setContentIntent)
        return builder.build()
    }

    private fun launchIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val CHANNEL_ID = "xg_a11y_lock_monitor"
        const val NOTIFICATION_ID = 0x5EC1
        const val DEBOUNCE_MS = 400L
        const val SETTING_SERVICES = "enabled_accessibility_services"
    }
}
