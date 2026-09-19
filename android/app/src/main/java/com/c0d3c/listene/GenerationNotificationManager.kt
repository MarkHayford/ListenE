package com.c0d3c.listene

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object GenerationNotificationManager {
    const val CHANNEL_ID = "listene_generation"
    const val EXTRA_OPEN_LISTENING_RECORD = "open_listening_record_id"

    private const val PROGRESS_LISTENING = 3001

    fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "内容生成",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "听力与单词句子练习后台处理进度"
            setShowBadge(true)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    fun updateListeningProgress(context: Context, title: String, stage: String, progress: Int) {
        postProgress(context, PROGRESS_LISTENING, title, stage, progress)
    }

    private fun postProgress(context: Context, id: Int, title: String, stage: String, progress: Int) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(stage)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        notify(context, id, builder)
    }

    fun cancelListeningProgress(context: Context) = cancel(context, PROGRESS_LISTENING)
    fun showListeningComplete(context: Context, recordId: String, scene: String) {
        cancelListeningProgress(context)
        showComplete(
            context,
            id = 4001,
            title = "听力练习已生成",
            text = scene,
            intent = pendingMainIntent(context, listeningRecordId = recordId)
        )
    }

    fun showListeningError(context: Context, message: String) {
        cancelListeningProgress(context)
        showComplete(context, 4011, "听力生成失败", message, pendingMainIntent(context))
    }

    fun showAgentReplyComplete(context: Context, summary: String) {
        val text = summary.replace("\n", " ").trim().take(160).ifBlank { "点按返回应用查看 AI 的回复。" }
        showComplete(context, 4002, "AI 回复已生成", text, pendingMainIntent(context))
    }

    fun showAgentReplyError(context: Context, message: String) {
        val text = message.replace("\n", " ").trim().take(160).ifBlank { "请返回应用重试后再发送。" }
        showComplete(context, 4012, "AI 回复生成失败", text, pendingMainIntent(context))
    }

    private fun showComplete(
        context: Context,
        id: Int,
        title: String,
        text: String,
        intent: PendingIntent
    ) {
        if (!AppSettings.isNotifyOnCompleteEnabled(context)) return
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        notify(context, id, builder)
    }

    private fun pendingMainIntent(
        context: Context,
        listeningRecordId: String? = null
    ): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            listeningRecordId?.let { putExtra(EXTRA_OPEN_LISTENING_RECORD, it) }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val key = listeningRecordId ?: "main"
        return PendingIntent.getActivity(context, key.hashCode(), intent, flags)
    }

    // 上一行 hasNotificationPermission() 已守卫 POST_NOTIFICATIONS；lint 无法跨函数追踪，故在此抑制。
    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (!hasNotificationPermission(context)) return
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }

    private fun cancel(context: Context, id: Int) {
        NotificationManagerCompat.from(context).cancel(id)
    }
}
