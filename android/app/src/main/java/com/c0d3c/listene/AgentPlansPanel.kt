package com.c0d3c.listene

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.util.Locale

// 学习计划面板（Plans）：计划整屏 + 行 + 排程/到期弹窗。从 AgentListenEApp.kt 整屏抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentPlansPanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var plans by remember { mutableStateOf(PlanStore.loadPlans(ctx)) }
    var loading by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<StudyPlan?>(null) }
    var ignoringBattery by remember { mutableStateOf(true) }

    LaunchedEffect(refreshTick) {
        ignoringBattery = PlanReminderScheduler.isIgnoringBatteryOptimizations(ctx)
        loading = true
        plans = PlanStore.refreshRemote(ctx)
        PlanReminderScheduler.rescheduleAll(ctx)
        loading = false
    }

    editing?.let { target ->
        AgentPlanScheduleDialog(
            plan = target,
            onDismiss = { editing = null },
            onConfirm = { scheduledAt, recurrence ->
                editing = null
                scope.launch {
                    val updated = PlanStore.updatePlanSchedule(ctx, target.id, scheduledAt, recurrence)
                    PlanReminderScheduler.rescheduleAll(ctx)
                    plans = PlanStore.loadPlans(ctx)
                    if (updated != null) AppNoticeBus.success("已更新计划时间") else AppNoticeBus.error("更新失败，请重试")
                }
            }
        )
    }

    val showEmpty = !loading && plans.isEmpty()
    Box(modifier = modifier.fillMaxSize()) {
        AgentPullRefreshColumn(
            refreshing = loading,
            onRefresh = { refreshTick += 1 },
            modifier = Modifier.fillMaxSize(),
            horizontalPadding = 0.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentIconControl(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回侧工具栏",
                    onClick = onBack,
                    accent = MaterialTheme.colorScheme.onSurface,
                    size = 30.dp,
                    iconSize = 23.dp,
                    bordered = false
                )
                AgentSectionTitle("计划表", modifier = Modifier.weight(1f))
            }
            if (!ignoringBattery && plans.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AgentStudyAmber.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, AgentStudyAmber.copy(alpha = 0.32f)),
                    shadowElevation = 0.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                "提醒可能不准时",
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "未加入电池白名单，系统可能延迟或拦截到点提醒。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        AgentTextAction(
                            text = "去允许",
                            onClick = { PlanReminderScheduler.requestIgnoreBatteryOptimizations(ctx) },
                            height = 36.dp
                        )
                    }
                }
            }
            if (showEmpty) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("还没有学习计划", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            "在对话里说「帮我制定一个 7 天学习计划」，AI 会排好计划，并到点自动提醒你来练。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                        )
                    }
                }
            }
            plans.sortedBy { if (it.scheduledAt > 0L) it.scheduledAt else Long.MAX_VALUE }.forEach { plan ->
                AgentPlanRow(
                    plan = plan,
                    onEditTime = { editing = plan },
                    onDelete = {
                        scope.launch {
                            PlanStore.deletePlan(ctx, plan.id)
                            PlanReminderScheduler.rescheduleAll(ctx)
                            plans = PlanStore.loadPlans(ctx)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun AgentPlanRow(
    plan: StudyPlan,
    onEditTime: () -> Unit,
    onDelete: () -> Unit
) {
    AgentSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    plan.title.ifBlank { "学习计划" },
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (plan.detail.isNotBlank()) {
                    Text(
                        plan.detail,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(
                        Icons.Default.Event,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        agentPlanScheduleLabel(plan.scheduledAt, plan.recurrence),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
            AgentIconControl(
                icon = Icons.Default.EditNote,
                contentDescription = "修改时间",
                onClick = onEditTime,
                accent = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 36.dp,
                iconSize = 20.dp
            )
            AgentIconControl(
                icon = Icons.Default.Delete,
                contentDescription = "删除计划",
                onClick = onDelete,
                accent = AgentPracticeWrong,
                size = 36.dp,
                iconSize = 20.dp
            )
        }
    }
}

@Composable
private fun AgentPlanScheduleDialog(
    plan: StudyPlan,
    onDismiss: () -> Unit,
    onConfirm: (Long, String) -> Unit
) {
    val ctx = LocalContext.current
    var scheduledAt by remember(plan.id) {
        mutableStateOf(if (plan.scheduledAt > 0L) plan.scheduledAt else System.currentTimeMillis())
    }
    var recurrence by remember(plan.id) { mutableStateOf(plan.recurrence.ifBlank { "none" }) }
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Default.Event,
                title = "调整计划时间",
                subtitle = plan.title.ifBlank { "学习计划" }
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.54f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "提醒时间",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        java.text.SimpleDateFormat("yyyy-MM-dd  HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(scheduledAt)),
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AgentTextAction(
                        text = "选择日期时间",
                        onClick = {
                            val cal = java.util.Calendar.getInstance().apply { timeInMillis = scheduledAt }
                            android.app.DatePickerDialog(
                                ctx,
                                { _, y, m, d ->
                                    android.app.TimePickerDialog(
                                        ctx,
                                        { _, h, min ->
                                            val c = java.util.Calendar.getInstance()
                                            c.set(y, m, d, h, min, 0)
                                            c.set(java.util.Calendar.MILLISECOND, 0)
                                            scheduledAt = c.timeInMillis
                                        },
                                        cal.get(java.util.Calendar.HOUR_OF_DAY),
                                        cal.get(java.util.Calendar.MINUTE),
                                        true
                                    ).show()
                                },
                                cal.get(java.util.Calendar.YEAR),
                                cal.get(java.util.Calendar.MONTH),
                                cal.get(java.util.Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        height = 40.dp
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "重复",
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("none" to "不重复", "daily" to "每天", "weekly" to "每周").forEach { (value, label) ->
                        AgentSegmentPill(text = label, selected = recurrence == value, onClick = { recurrence = value })
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentTextAction(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f), height = 44.dp)
                AgentTextAction(text = "保存", onClick = { onConfirm(scheduledAt, recurrence) }, modifier = Modifier.weight(1f), primary = true, height = 44.dp)
            }
        }
    }
}

@Composable
internal fun AgentPlanDueDialog(
    plan: StudyPlan,
    onStart: () -> Unit,
    onSkip: () -> Unit,
    onPostpone: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.Default.Event,
                title = "学习计划到点啦",
                subtitle = "要现在开始这次训练吗？"
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                shadowElevation = 0.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.54f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        plan.title.ifBlank { "学习计划" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (plan.detail.isNotBlank()) {
                        Text(
                            plan.detail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentTextAction(text = "跳过", onClick = onSkip, modifier = Modifier.weight(1f), height = 42.dp)
                AgentTextAction(text = "推迟30分", onClick = onPostpone, modifier = Modifier.weight(1f), height = 42.dp)
                AgentTextAction(text = "开始训练", onClick = onStart, modifier = Modifier.weight(1f), primary = true, height = 42.dp)
            }
        }
    }
}
