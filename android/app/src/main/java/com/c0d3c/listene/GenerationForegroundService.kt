package com.c0d3c.listene

import android.annotation.SuppressLint
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * 前台服务：生成期间把进程"钉"在前台，降低退到后台时被系统回收的概率，保障后台也能跑完。
 * 因 Android 12+ 禁止从后台启动前台服务，故在生成开始（App 仍在前台）时启动；完成后停止。
 * 常驻通知为 IMPORTANCE_LOW（无声），只在生成期间存在。
 */
class GenerationForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    // startForeground 已由 Manifest 声明 dataSync 前台服务类型与权限保障；lint 无法追踪，故抑制。
    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "ListenE 生成中" }
                val stage = intent.getStringExtra(EXTRA_STAGE).orEmpty().ifBlank { "准备中..." }
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(this, title, stage, progress),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    } else {
                        0
                    }
                )
            }
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 确保服务销毁时常驻通知一并移除（stopService 路径也会走到这里）。
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 2999
        private const val ACTION_START = "com.c0d3c.listene.fgs.START"
        private const val ACTION_STOP = "com.c0d3c.listene.fgs.STOP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_STAGE = "stage"
        private const val EXTRA_PROGRESS = "progress"

        private fun buildNotification(context: Context, title: String, stage: String, progress: Int): Notification {
            GenerationNotificationManager.ensureChannel(context)
            return NotificationCompat.Builder(context, GenerationNotificationManager.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(title)
                .setContentText(stage)
                .setProgress(100, progress.coerceIn(0, 100), false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .build()
        }

        /** 生成开始时调用（此时 App 在前台，允许启动前台服务）。 */
        fun start(context: Context, title: String, stage: String = "准备中...", progress: Int = 0) {
            val intent = Intent(context, GenerationForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_STAGE, stage)
                putExtra(EXTRA_PROGRESS, progress)
            }
            // 极端情况下若已在后台，startForegroundService 可能被系统拒绝；兜底不崩，退化为 best-effort。
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        /** 直接更新常驻通知（走 NotificationManagerCompat，后台调用安全，不受"后台启服务"限制）。 */
        @SuppressLint("MissingPermission")
        fun updateProgress(context: Context, title: String, stage: String, progress: Int) {
            if (!GenerationNotificationManager.hasNotificationPermission(context)) return
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, buildNotification(context, title, stage, progress))
        }

        /** 停止前台服务（stopService 后台调用安全；服务未运行时为无操作）。onDestroy 会移除常驻通知。 */
        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, GenerationForegroundService::class.java))
            }
        }
    }
}
