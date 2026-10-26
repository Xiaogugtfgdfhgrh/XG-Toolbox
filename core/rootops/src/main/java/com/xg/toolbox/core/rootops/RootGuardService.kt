package com.xg.toolbox.core.rootops

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.xg.toolbox.core.common.XgLog
import com.xg.toolbox.core.common.XgResult
import com.xg.toolbox.core.data.BehaviorPreferences
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Low-importance foreground service that keeps the app process eligible for the user's
 * boot/keep-alive policy. A detached root watchdog (managed by RootOpsManager) handles
 * process recreation when Android or an OEM cleans up the foreground service.
 */
@AndroidEntryPoint
class RootGuardService : Service() {

    @Inject
    lateinit var rootOpsManager: RootOpsManager

    @Inject
    lateinit var behaviorPreferences: BehaviorPreferences

    @Inject
    lateinit var recoveryJournal: StartupRecoveryJournal

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var foregroundStarted = false
    private var observersStarted = false
    private val bootRecoveryStarted = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        foregroundStarted = startForegroundSafely()
        if (!foregroundStarted) {
            XgLog.e(TAG, "startForeground failed; stopping root guard service")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) return START_NOT_STICKY

        if (!observersStarted) {
            observersStarted = true
            launchServiceTask("observe-preferences") { observeUserPreferences() }
            launchServiceTask("guard-service-started") { rootOpsManager.onGuardServiceStarted() }
        }
        if (intent?.getBooleanExtra(EXTRA_BOOT_RECOVERY, false) == true &&
            bootRecoveryStarted.compareAndSet(false, true)
        ) {
            launchServiceTask("boot-recovery") { restoreBootPrivileges() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun restoreBootPrivileges() {
        val result = rootOpsManager.restoreAutostartPrivileges()
        when (result) {
            is XgResult.Ok -> recoveryJournal.record("boot-privileges", true, result.data)
            is XgResult.Err -> {
                val detail = result.detail?.let { "${result.message}；$it" } ?: result.message
                recoveryJournal.record("boot-privileges", false, detail)
                XgLog.w(TAG, "boot privilege recovery failed: $detail")
            }
        }
    }

    private suspend fun observeUserPreferences() {
        combine(
            behaviorPreferences.autostartEnabled,
            behaviorPreferences.rootKeepAliveEnabled,
        ) { autostart, keepAlive -> autostart to keepAlive }
            .catch { t -> XgLog.e(TAG, "observe preferences failed", t) }
            .distinctUntilChanged()
            .collect { (autostart, keepAlive) ->
                try {
                    rootOpsManager.onGuardServicePreferencesChanged(autostart, keepAlive)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    recoveryJournal.record(
                        "guard-service-preferences",
                        false,
                        "${t.javaClass.simpleName}: ${t.message.orEmpty().take(180)}",
                    )
                    XgLog.e(TAG, "apply guard service preferences failed", t)
                }
            }
    }

    private fun launchServiceTask(label: String, block: suspend () -> Unit) {
        serviceScope.launch {
            try {
                block()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                XgLog.e(TAG, "$label failed", t)
                runCatching {
                    recoveryJournal.record(
                        "service-$label",
                        false,
                        "${t.javaClass.simpleName}: ${t.message.orEmpty().take(180)}",
                    )
                }
            }
        }
    }

    private fun startForegroundSafely(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        true
    } catch (t: Throwable) {
        XgLog.e(TAG, "foreground service start failed", t)
        false
    }

    private fun buildNotification(): android.app.Notification {
        val launchIntent = Intent().apply {
            setClassName(packageName, MAIN_ACTIVITY_CLASS)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_root_guard)
            .setContentTitle(getString(R.string.root_guard_notification_title))
            .setContentText(getString(R.string.root_guard_notification_text))
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.root_guard_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.root_guard_channel_description)
            setShowBadge(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RootGuardService"
        private const val CHANNEL_ID = "root_guard_channel"
        private const val NOTIFICATION_ID = 0x5847
        private const val MAIN_ACTIVITY_CLASS = "com.xg.toolbox.MainActivity"
        const val EXTRA_BOOT_RECOVERY = "boot_recovery"
        const val ACTION_ENSURE_GUARD = "com.xg.toolbox.core.rootops.ENSURE_GUARD"

        fun start(context: Context, bootRecovery: Boolean = false) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, RootGuardService::class.java)
                    .setAction(ACTION_ENSURE_GUARD)
                    .putExtra(EXTRA_BOOT_RECOVERY, bootRecovery),
            )
        }
    }
}
