package com.c0d3c.listene

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PlanAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED",
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                PlanReminderScheduler.rescheduleAll(app)
            }
            PlanReminderScheduler.ACTION_FIRE -> {
                val planId = intent.getStringExtra("planId").orEmpty()
                val title = intent.getStringExtra("title").orEmpty()
                val detail = intent.getStringExtra("detail").orEmpty()
                val recurrence = intent.getStringExtra("recurrence").orEmpty()
                val scheduledAt = intent.getLongExtra("scheduledAt", 0L)
                PlanReminderScheduler.notify(app, planId, title, detail)
                PlanReminderScheduler.addDue(app, StudyPlan(planId, title, detail, scheduledAt, recurrence))
                if (recurrence == "daily" || recurrence == "weekly") {
                    // 跳过刚触发的这次，调度下一次
                    val plan = StudyPlan(planId, title, detail, scheduledAt, recurrence)
                    PlanReminderScheduler.scheduleOne(app, plan, System.currentTimeMillis() + 60_000L)
                }
            }
        }
    }
}
