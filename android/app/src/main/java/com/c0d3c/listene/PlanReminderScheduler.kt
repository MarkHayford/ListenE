package com.c0d3c.listene

import android.annotation.SuppressLint
import android.app.AlarmManager
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// 学习计划到点提醒：用 AlarmManager 精确闹钟，重复任务在 receiver 里滚动调度下一次。
object PlanReminderScheduler {
    const val CHANNEL_ID = "listene_plan_reminder"
    const val ACTION_FIRE = "com.c0d3c.listene.action.PLAN_REMINDER"
    private const val PREFS = "plan_reminder_scheduler"
    private const val KEY_SCHEDULED_IDS = "scheduled_ids"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val WEEK_MS = 7L * DAY_MS

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "学习计划提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "到点提醒你执行学习计划"
        }
        mgr.createNotificationChannel(channel)
    }

    fun hasNotificationPermission(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    // 是否已加入电池白名单（忽略电池优化）。未加入时后台精确闹钟可能被系统延迟/拦截。
    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(ctx: Context) {
        val ok = runCatching {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${ctx.packageName}")
                if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        }.isSuccess
        if (!ok) {
            runCatching {
                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            }
        }
    }

    // 计算 >= now 的下一次触发；一次性且已过 -> 0（不调度）。
    fun nextTrigger(scheduledAt: Long, recurrence: String, now: Long = System.currentTimeMillis()): Long {
        if (scheduledAt <= 0L) return 0L
        return when (recurrence) {
            "daily" -> if (scheduledAt >= now) scheduledAt else scheduledAt + (((now - scheduledAt) / DAY_MS) + 1) * DAY_MS
            "weekly" -> if (scheduledAt >= now) scheduledAt else scheduledAt + (((now - scheduledAt) / WEEK_MS) + 1) * WEEK_MS
            else -> if (scheduledAt >= now) scheduledAt else 0L
        }
    }

    private fun firePendingIntent(ctx: Context, plan: StudyPlan): PendingIntent {
        val intent = Intent(ctx, PlanAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra("planId", plan.id)
            putExtra("title", plan.title)
            putExtra("detail", plan.detail)
            putExtra("recurrence", plan.recurrence)
            putExtra("scheduledAt", plan.scheduledAt)
        }
        return PendingIntent.getBroadcast(
            ctx, plan.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelById(ctx: Context, planId: String) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(ctx, PlanAlarmReceiver::class.java).apply { action = ACTION_FIRE }
        val pi = PendingIntent.getBroadcast(
            ctx, planId.hashCode(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) am.cancel(pi)
    }

    fun scheduleOne(ctx: Context, plan: StudyPlan, now: Long = System.currentTimeMillis()) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val trigger = nextTrigger(plan.scheduledAt, plan.recurrence, now)
        if (trigger <= 0L) return
        val pi = firePendingIntent(ctx, plan)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    // 取消旧的、按当前计划重排所有提醒。计划创建/修改时间/删除、应用启动、开机后都应调用。
    fun rescheduleAll(ctx: Context) {
        val app = ctx.applicationContext
        ensureChannel(app)
        val prefs = app.getSharedPreferences(AuthStore.scopedPrefsName(app, PREFS), Context.MODE_PRIVATE)
        (prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()) ?: emptySet()).forEach { cancelById(app, it) }
        val now = System.currentTimeMillis()
        val scheduled = mutableSetOf<String>()
        PlanStore.loadPlans(app).forEach { plan ->
            if (nextTrigger(plan.scheduledAt, plan.recurrence, now) > 0L) {
                scheduleOne(app, plan, now)
                scheduled.add(plan.id)
            }
        }
        prefs.edit().putStringSet(KEY_SCHEDULED_IDS, scheduled).apply()
    }

    @SuppressLint("MissingPermission")
    fun notify(ctx: Context, planId: String, title: String, detail: String) {
        if (!hasNotificationPermission(ctx)) return
        ensureChannel(ctx)
        val openIntent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPi = PendingIntent.getActivity(
            ctx, planId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = detail.ifBlank { "到点啦，去完成今天的学习计划吧" }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title.ifBlank { "学习计划提醒" })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
        runCatching {
            NotificationManagerCompat.from(ctx).notify(("plan_$planId").hashCode(), builder.build())
        }
    }

    // ===== 已到点、等待用户在 App 内确认是否开始训练的计划 =====
    private const val KEY_PENDING_DUES = "pending_dues"

    fun addDue(ctx: Context, plan: StudyPlan) {
        val prefs = ctx.applicationContext.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS), Context.MODE_PRIVATE)
        val arr = parseJsonArrayOrNull(prefs.getString(KEY_PENDING_DUES, "[]") ?: "[]") ?: JsonArray(emptyList())
        val out = buildJsonArray {
            for (i in 0 until arr.size) {
                val o = arr.objOrNull(i) ?: continue
                if (o.str("id") != plan.id) add(o)
            }
            add(
                buildJsonObject {
                    put("id", plan.id); put("title", plan.title); put("detail", plan.detail)
                    put("scheduledAt", plan.scheduledAt); put("recurrence", plan.recurrence)
                }
            )
        }
        prefs.edit().putString(KEY_PENDING_DUES, out.toString()).apply()
        PlanDueBus.signal()
    }

    fun loadDues(ctx: Context): List<StudyPlan> {
        val prefs = ctx.applicationContext.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS), Context.MODE_PRIVATE)
        val arr = parseJsonArrayOrNull(prefs.getString(KEY_PENDING_DUES, "[]") ?: "[]") ?: JsonArray(emptyList())
        return List(arr.size) { i ->
            val o = arr.objOrNull(i)
            StudyPlan(o?.str("id") ?: "", o?.str("title") ?: "", o?.str("detail") ?: "", o?.long("scheduledAt", 0L) ?: 0L, o?.str("recurrence", "none") ?: "none")
        }.filter { it.id.isNotBlank() }
    }

    fun removeDue(ctx: Context, planId: String) {
        val prefs = ctx.applicationContext.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS), Context.MODE_PRIVATE)
        val arr = parseJsonArrayOrNull(prefs.getString(KEY_PENDING_DUES, "[]") ?: "[]") ?: JsonArray(emptyList())
        val out = buildJsonArray {
            for (i in 0 until arr.size) {
                val o = arr.objOrNull(i) ?: continue
                if (o.str("id") != planId) add(o)
            }
        }
        prefs.edit().putString(KEY_PENDING_DUES, out.toString()).apply()
    }
}

// 到点计划信号：闹钟触发或 App 回前台时通知 UI 重新检查待处理计划。
object PlanDueBus {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val events: SharedFlow<Unit> = _events.asSharedFlow()
    fun signal() { _events.tryEmit(Unit) }
}
