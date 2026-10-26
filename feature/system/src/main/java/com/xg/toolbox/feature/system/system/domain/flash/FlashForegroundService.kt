package com.xg.toolbox.feature.system.domain.flash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xg.toolbox.core.common.XgLog
import java.util.concurrent.atomic.AtomicBoolean

class FlashForegroundService : Service() {

    private lateinit var notificationManager: NotificationManager

    @Volatile
    private var partition: String = ""

    @Volatile
    private var lastPercent: Float = 0f

    @Volatile
    private var lastStage: String = "准备写入…"

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                abortFlag.set(false)
                partition = intent.getStringExtra(EXTRA_PARTITION).orEmpty()
                lastPercent = 0f
                lastStage = "准备写入…"
                startForegroundWithNotification()
            }
            ACTION_ABORT -> {
                abortFlag.set(true)
                lastStage = "正在中止…"
                refreshNotification()
            }
            ACTION_STOP -> finishService()
            else -> if (partition.isBlank()) finishService()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    
    private fun refresh(percent: Float, stage: String) {
        lastPercent = percent.coerceIn(0f, 100f)
        lastStage = stage
        refreshNotification()
    }

    private fun refreshNotification() {
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        }.onFailure { XgLog.w(TAG, "更新通知失败：${it.javaClass.simpleName}") }
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun buildNotification(): Notification {
        val abortIntent = PendingIntent.getService(
            this,
            ABORT_REQUEST_CODE,
            Intent(this, FlashForegroundService::class.java).setAction(ACTION_ABORT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = if (partition.isBlank()) "正在写入分区" else "正在写入 $partition"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(lastStage)
            .setProgress(100, lastPercent.toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "中止", abortIntent)
            .build()
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "镜像写入",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "镜像写入分区的进度与中止动作"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "FlashForegroundService"
        private const val CHANNEL_ID = "flash"
        private const val NOTIFICATION_ID = 1001
        private const val ABORT_REQUEST_CODE = 2001

        const val EXTRA_PARTITION = "partition"
        const val ACTION_START = "com.xg.toolbox.action.FLASH_START"
        const val ACTION_ABORT = "com.xg.toolbox.action.FLASH_ABORT"
        const val ACTION_STOP = "com.xg.toolbox.action.FLASH_STOP"

        private val abortFlag = AtomicBoolean(false)

        @Volatile
        private var instance: FlashForegroundService? = null

        
        fun abortRequested(): Boolean = abortFlag.get()

        
        fun start(context: Context, partition: String) {
            val intent = Intent(context, FlashForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PARTITION, partition)
            }
            context.startForegroundService(intent)
        }

        
        fun update(percent: Float, stage: String) {
            instance?.refresh(percent, stage)
        }

        
        fun stop(context: Context) {
            val current = instance
            if (current != null) {
                current.finishService()
            } else {
                context.stopService(Intent(context, FlashForegroundService::class.java))
            }
        }
    }
}
