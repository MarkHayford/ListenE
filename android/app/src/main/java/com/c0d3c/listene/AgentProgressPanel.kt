package com.c0d3c.listene

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

// 学习进度面板（Progress）：总览 + 每日挑战卡 + 进度统计 + 技能雷达 + 趋势图等 UI 组件。
// 从 AgentListenEApp.kt 整屏抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentProgressPanel(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var summary by remember { mutableStateOf(ProgressSummary()) }
    var loading by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // 每日挑战整合进进度页
    var daily by remember { mutableStateOf<DailyChallenge?>(null) }
    var dailyLoading by remember { mutableStateOf(false) }
    var dailyQuiz by remember { mutableStateOf(false) }
    var dailyAnswers by remember { mutableStateOf(emptyMap<Int, DailyAnswerDraft>()) }
    var dailySubmitting by remember { mutableStateOf(false) }
    var dailyResult by remember { mutableStateOf<DailyResult?>(null) }
    // 进度统计与每日挑战分开加载：首次拉取 /daily 会触发服务端生成今日题目（可能数十秒），
    // 若与进度串行共用一个 loading，顶部「同步中」会一直挂着收不回。现在下拉刷新只等进度统计，
    // 每日挑战独立加载，占位/失败态只影响挑战卡自身。
    LaunchedEffect(refreshTick) {
        loading = true
        summary = ProgressStore.fetchSummary(ctx)
        loading = false
    }
    LaunchedEffect(refreshTick) {
        dailyLoading = true
        // 拉取失败（返回 null）时保留上一次的题目，避免已加载的挑战卡退回“准备中”。
        daily = DailyStore.getToday(ctx) ?: daily
        dailyLoading = false
    }
    val hasData = summary.totalSessions > 0
    Box(modifier = modifier.fillMaxSize()) {
        AgentPullRefreshColumn(
            refreshing = loading,
            onRefresh = { refreshTick += 1 },
            modifier = Modifier.fillMaxSize(),
            horizontalPadding = 0.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AgentIconControl(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (dailyQuiz) "返回进度" else "返回侧工具栏",
                    onClick = { if (dailyQuiz) { dailyQuiz = false } else onBack() },
                    accent = MaterialTheme.colorScheme.onSurface,
                    size = 30.dp,
                    iconSize = 23.dp,
                    bordered = false
                )
                AgentSectionTitle(if (dailyQuiz) "每日挑战" else "我的进度", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))

            if (dailyQuiz) {
                val ch = daily
                val r = dailyResult
                when {
                    r != null -> {
                        AgentDailyResultHeader(r.score, r.correct, r.total)
                        r.results.forEachIndexed { i, q ->
                            AgentDailyResultCard(i, q)
                        }
                        AgentTextAction("完成，返回进度", onClick = { dailyQuiz = false; refreshTick += 1 }, modifier = Modifier.fillMaxWidth(), primary = true, icon = Icons.Default.CheckCircle, height = 46.dp)
                    }
                    ch != null -> {
                        Text("今日 ${ch.total} 题 · 跨技能多题型小测，完成保持连续打卡。", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 2.dp))
                        ch.questions.forEachIndexed { i, q ->
                            AgentDailyAnswerCard(i, q, dailyAnswers[i] ?: DailyAnswerDraft()) { d -> dailyAnswers = dailyAnswers + (i to d) }
                        }
                        val allAnswered = ch.questions.indices.all { ch.questions[it].isAnswered(dailyAnswers[it]) }
                        AgentTextAction(
                            text = if (dailySubmitting) "提交中…" else if (allAnswered) "提交答案" else "答完全部题再提交",
                            onClick = {
                                if (dailySubmitting || !allAnswered) return@AgentTextAction
                                dailySubmitting = true
                                scope.launch {
                                    val picks = (0 until ch.total).map { dailyAnswers[it] ?: DailyAnswerDraft() }
                                    val res = DailyStore.submit(ctx, picks)
                                    dailySubmitting = false
                                    if (res != null) dailyResult = res else AppNoticeBus.error("提交失败，请重试")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            primary = true,
                            enabled = allAnswered && !dailySubmitting,
                            icon = Icons.AutoMirrored.Filled.FactCheck,
                            height = 46.dp
                        )
                    }
                }
            } else {
                AgentDailyStatusCard(
                    daily = daily,
                    loading = dailyLoading,
                    onStart = { dailyAnswers = emptyMap(); dailyResult = null; dailyQuiz = true }
                )
                if (!hasData) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = AgentStudyBlue.copy(alpha = 0.06f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("还没有学习数据", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "完成任意练习卡(题组/完形/阅读/七选五/口语/作文…)后，这里会显示技能雷达、学习趋势和连续打卡。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
                            )
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AgentProgressStat("连续打卡", "${summary.streakDays}", "天", AgentStudyRose, Modifier.weight(1f))
                        AgentProgressStat("今日练习", "${summary.todaySessions}", "次", AgentStudyTeal, Modifier.weight(1f))
                        AgentProgressStat("累计练习", "${summary.totalSessions}", "次", AgentStudyBlue, Modifier.weight(1f))
                    }
                    AgentProgressCard("技能雷达") {
                        AgentSkillRadar(summary.skills)
                    }
                    if (summary.weakestSkill.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = AgentStudyAmber.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, AgentStudyAmber.copy(alpha = 0.32f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text("薄弱技能：${summary.weakestSkill}", fontWeight = FontWeight.SemiBold, color = AgentStudyAmber, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "在对话里说「来一套${summary.weakestSkill}练习」就能针对性提升。",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    if (summary.trend.any { it.count > 0 }) {
                        AgentProgressCard("近 14 天趋势（平均分）") {
                            AgentProgressTrend(summary.trend)
                        }
                    }
                    if (summary.totalAnswered > 0) {
                        val rate = (summary.totalCorrect * 100f / summary.totalAnswered).roundToInt()
                        Text(
                            "累计答题 ${summary.totalAnswered} 道 · 答对 ${summary.totalCorrect} 道 · 正确率 ${rate}%",
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun AgentDailyStatusCard(daily: DailyChallenge?, loading: Boolean, onStart: () -> Unit) {
    val completed = daily?.completed == true
    val accent = if (completed) AgentPracticeSuccess else AgentStudyAmber
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = accent.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.30f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier.size(50.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (completed) Icons.Default.CheckCircle else Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(28.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("每日挑战", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    when {
                        daily == null && loading -> "正在准备今天的题目…（首次生成约需十几秒）"
                        daily == null -> "今日题目暂时没拉到，下拉刷新重试"
                        completed -> "今日已完成 · 得分 ${daily.score}，明天再来"
                        else -> "今日 ${daily.total} 题跨技能小测，完成 +1 连续打卡"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (daily != null && !completed) {
                Surface(
                    onClick = onStart,
                    shape = RoundedCornerShape(12.dp),
                    color = accent,
                    modifier = Modifier.height(40.dp)
                ) {
                    Row(Modifier.padding(horizontal = 14.dp).fillMaxHeight(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("去挑战", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentProgressStat(label: String, value: String, unit: String, accent: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
                Text(unit, style = MaterialTheme.typography.labelSmall, color = accent.copy(alpha = 0.8f), modifier = Modifier.padding(bottom = 3.dp))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AgentProgressCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@Composable
private fun AgentSkillRadar(skills: List<SkillScore>) {
    if (skills.isEmpty()) return
    val n = skills.size
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val fill = AgentStudyBlue
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        val side = minOf(maxWidth, maxHeight)
        Canvas(Modifier.size(side)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension / 2f * 0.70f
            fun pt(i: Int, r: Float): Offset {
                val ang = ((-90.0 + i * 360.0 / n) * PI / 180.0)
                return Offset(cx + (r * cos(ang)).toFloat(), cy + (r * sin(ang)).toFloat())
            }
            listOf(0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
                val ring = Path()
                for (i in 0 until n) {
                    val p = pt(i, radius * frac)
                    if (i == 0) ring.moveTo(p.x, p.y) else ring.lineTo(p.x, p.y)
                }
                ring.close()
                drawPath(ring, color = gridColor, style = Stroke(width = 2f))
            }
            for (i in 0 until n) {
                drawLine(gridColor, Offset(cx, cy), pt(i, radius), strokeWidth = 2f)
            }
            val data = Path()
            skills.forEachIndexed { i, s ->
                val p = pt(i, radius * (s.score.coerceIn(0, 100) / 100f))
                if (i == 0) data.moveTo(p.x, p.y) else data.lineTo(p.x, p.y)
            }
            data.close()
            drawPath(data, color = fill.copy(alpha = 0.22f))
            drawPath(data, color = fill, style = Stroke(width = 4f))
            skills.forEachIndexed { i, s ->
                drawCircle(fill, radius = 5f, center = pt(i, radius * (s.score.coerceIn(0, 100) / 100f)))
            }
        }
        val labelRadius = side / 2f * 0.90f
        skills.forEachIndexed { i, s ->
            val ang = (-90.0 + i * 360.0 / n) * PI / 180.0
            val dx = (labelRadius.value * cos(ang)).toFloat().dp
            val dy = (labelRadius.value * sin(ang)).toFloat().dp
            Column(
                modifier = Modifier.align(Alignment.Center).offset(x = dx, y = dy),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(s.skill, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("${s.score}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = agentSpeakingScoreColor(s.score))
            }
        }
    }
}

@Composable
private fun AgentProgressTrend(trend: List<ProgressTrendDay>) {
    if (trend.isEmpty()) return
    val line = AgentStudyTeal
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    Canvas(Modifier.fillMaxWidth().height(96.dp)) {
        val n = trend.size
        val stepX = if (n > 1) size.width / (n - 1) else size.width
        drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        val path = Path()
        trend.forEachIndexed { i, d ->
            val x = if (n > 1) i * stepX else size.width / 2f
            val y = size.height - (d.avgScore.coerceIn(0, 100) / 100f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = line, style = Stroke(width = 4f))
        trend.forEachIndexed { i, d ->
            if (d.count > 0) {
                val x = if (n > 1) i * stepX else size.width / 2f
                val y = size.height - (d.avgScore.coerceIn(0, 100) / 100f) * size.height
                drawCircle(line, radius = 4f, center = Offset(x, y))
            }
        }
    }
}
