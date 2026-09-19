package com.c0d3c.listene

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add

// 卡片交互组件（Interactive）：阅读/取词/进度收集/互动题/GapMatch/图表/图表写作/写作报告/造句练习。由 AgentCardComponents.kt 细分而来，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentReadingTagChip(tag: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = AgentStudyTeal.copy(alpha = 0.13f)
    ) {
        Text(
            tag,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = AgentStudyTeal
        )
    }
}

// 练习完成时上报一次学习进度（best-effort，失败不影响体验）。done 由 false 变 true 时触发一次。
@Composable
internal fun AgentProgressTracker(
    done: Boolean,
    componentType: String,
    skill: String? = null,
    total: Int = 0,
    correct: Int = 0,
    score: Int? = null
) {
    val ctx = LocalContext.current
    LaunchedEffect(done, componentType, total, correct, score) {
        if (done) {
            runCatching { ProgressStore.record(ctx, componentType, skill, total, correct, score) }
            // 逐题难度自适应：用本次答对比例在线更新该技能的能力估计（Elo/IRT，本地）。
            runCatching { LearnerModelStore.updateFromCounts(ctx, skill, correct, total) }
            // #4B：把更新后的能力回灌统一用户模型（服务端合并 + 刷新本地缓存，供聊天全局上下文）。
            if (total > 0) runCatching { UserModelStore.push(ctx) }
        }
    }
}

// 练习完成时把答错的选择题自动收进错题本（SRS）。仅在「本次会话中 done 由 false 变 true」时批量收集一次：
// 打开一条历史上已核对过的记录（初始即 done=true）不重复收集，否则每次重开都会把旧错题重置回 0 盒。
@Composable
internal fun AgentWrongQuestionCollector(
    done: Boolean,
    questions: List<Question>,
    answers: Map<Int, Int>,
    componentType: String,
    skill: String?
) {
    val ctx = LocalContext.current
    var armed by remember { mutableStateOf(!done) }
    LaunchedEffect(done) {
        if (!done) {
            armed = true
            return@LaunchedEffect
        }
        if (!armed) return@LaunchedEffect
        armed = false
        questions.forEachIndexed { index, q ->
            val selected = answers[index]
            if (selected != null && selected != q.correctAnswer && q.questionText.isNotBlank()) {
                val correctText = q.options.getOrNull(q.correctAnswer).orEmpty()
                if (correctText.isNotBlank()) {
                    runCatching {
                        ReviewStore.addWrong(
                            ctx,
                            componentType = componentType,
                            skill = skill,
                            kind = "mcq",
                            prompt = q.questionText,
                            options = q.options,
                            answer = correctText,
                            explanation = q.explanation
                        )
                    }
                }
            }
        }
    }
}

// 交互题块（分页 + 选项 + 核对 + 反馈），供题组卡与阅读理解卡复用。
private val agentChartPalette = listOf(
    AgentStudyBlue, AgentStudyRose, AgentStudyTeal, AgentStudyAmber, AgentStudyViolet, AgentPracticeSuccess
)

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AgentChartView(model: AgentChartModel) {
    if (!model.hasData) return
    if (model.type != "pie" && model.seriesCount > 1) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            model.seriesNames.forEachIndexed { i, name ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(agentChartPalette[i % agentChartPalette.size]))
                    Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    val maxVal = (model.seriesValues.flatten().maxOrNull() ?: 1f).coerceAtLeast(1f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(if (model.type == "pie") 190.dp else 160.dp)
    ) {
        when (model.type) {
            "pie" -> {
                val first = model.seriesValues.map { it.firstOrNull() ?: 0f }
                val total = first.sum().coerceAtLeast(0.0001f)
                var start = -90f
                val diameter = minOf(size.width, size.height)
                val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
                first.forEachIndexed { i, v ->
                    val sweep = v / total * 360f
                    drawArc(
                        color = agentChartPalette[i % agentChartPalette.size],
                        startAngle = start,
                        sweepAngle = sweep,
                        useCenter = true,
                        topLeft = topLeft,
                        size = Size(diameter, diameter)
                    )
                    start += sweep
                }
            }
            "line" -> {
                val n = model.categories.size
                val stepX = if (n > 1) size.width / (n - 1) else size.width
                for (j in 0 until model.seriesCount) {
                    val path = Path()
                    model.categories.indices.forEach { i ->
                        val v = model.seriesValues[i].getOrNull(j) ?: 0f
                        val x = if (n > 1) i * stepX else size.width / 2f
                        val y = size.height - (v / maxVal) * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color = agentChartPalette[j % agentChartPalette.size], style = Stroke(width = 4f))
                    model.categories.indices.forEach { i ->
                        val v = model.seriesValues[i].getOrNull(j) ?: 0f
                        val x = if (n > 1) i * stepX else size.width / 2f
                        val y = size.height - (v / maxVal) * size.height
                        drawCircle(agentChartPalette[j % agentChartPalette.size], radius = 5f, center = Offset(x, y))
                    }
                }
            }
            else -> {
                val n = model.categories.size.coerceAtLeast(1)
                val s = model.seriesCount.coerceAtLeast(1)
                val groupWidth = size.width / n
                val barGap = groupWidth * 0.12f
                val barWidth = (groupWidth - barGap * 2) / s
                model.categories.indices.forEach { i ->
                    for (j in 0 until s) {
                        val v = model.seriesValues[i].getOrNull(j) ?: 0f
                        val h = (v / maxVal) * size.height
                        val x = i * groupWidth + barGap + j * barWidth
                        drawRect(
                            color = agentChartPalette[j % agentChartPalette.size],
                            topLeft = Offset(x, size.height - h),
                            size = Size(barWidth * 0.86f, h)
                        )
                    }
                }
            }
        }
    }
    if (model.type == "pie") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            model.categories.forEachIndexed { i, cat ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(agentChartPalette[i % agentChartPalette.size]))
                    Text(cat, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            model.categories.forEach { cat ->
                Text(cat, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

// 图表展示外壳（标题 + 图表画布）；数据不完整时给提示。供图表作文专用渲染与 chart 微元复用。
@Composable
internal fun AgentChartSurface(title: String, model: AgentChartModel) {
    if (model.hasData) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (title.isNotBlank()) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
                AgentChartView(model)
            }
        }
    } else {
        AgentCardInlineNotice("这张图表作文卡的数据不完整。")
    }
}

@Composable
internal fun AgentChartWritingBox(prompt: String, reference: String, instanceKey: String) {
    val scope = rememberCoroutineScope()
    var essay by rememberSaveable(instanceKey) { mutableStateOf("") }
    var grading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<WritingAssessment?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val wordCount = remember(essay) { essay.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .testTag("agent_chart_writing_box")
            .shadow(8.dp, RoundedCornerShape(8.dp), clip = false),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = essay,
                onValueChange = { if (!grading) essay = it },
                readOnly = grading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp)
                    .testTag("agent_chart_writing_input"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(AgentStudyBlue),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth()) {
                        if (essay.isBlank()) {
                            Text(
                                // 写作框已从图表作文通用化到自由写作（writing 微元），占位语不再假设图表。
                                "在这里输入你的英文正文。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        innerTextField()
                    }
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("$wordCount 词", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Text(
                    "清空",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = essay.isNotBlank() && !grading) { essay = ""; result = null }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (essay.isNotBlank() && !grading) AgentStudyBlue else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
    AgentTextAction(
        text = if (grading) "AI 正在批改作文…" else "AI 评分",
        onClick = {
            if (grading) return@AgentTextAction
            if (wordCount < 5) {
                AppNoticeBus.show("先写几句再评分")
                return@AgentTextAction
            }
            grading = true
            errorText = null
            scope.launch {
                try {
                    result = AgentConversationService.assessWriting(prompt, reference, essay)
                } catch (e: Exception) {
                    errorText = e.message ?: "作文评分失败"
                } finally {
                    grading = false
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_chart_writing_grade"),
        primary = true,
        enabled = !grading,
        icon = Icons.AutoMirrored.Filled.FactCheck,
        height = 38.dp
    )
    errorText?.let {
        Text(it, style = MaterialTheme.typography.labelMedium, color = AgentPracticeWrong)
    }
    result?.let { AgentWritingReport(it) }
    AgentProgressTracker(done = result != null, componentType = "chart_writing", skill = "写作", score = result?.overall)
}

@Composable
private fun AgentWritingReport(result: WritingAssessment) {
    val overallColor = agentSpeakingScoreColor(result.overall)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AgentCardDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(overallColor.copy(alpha = 0.12f))
                    .border(2.dp, overallColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${result.overall}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = overallColor)
                    Text("总分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AgentSpeakingScoreBar("内容完成", result.scores.taskAchievement)
                AgentSpeakingScoreBar("连贯", result.scores.coherence)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(modifier = Modifier.weight(1f)) { AgentSpeakingScoreBar("词汇", result.scores.vocabulary) }
            Column(modifier = Modifier.weight(1f)) { AgentSpeakingScoreBar("语法", result.scores.grammar) }
        }
        if (result.wordCount > 0) {
            Text("约 ${result.wordCount} 词", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (result.highlights.isNotEmpty()) {
            AgentSpeakingReportSection("亮点", AgentPracticeSuccess) {
                result.highlights.forEach { AgentSpeakingBullet(it, AgentPracticeSuccess) }
            }
        }
        if (result.improvements.isNotEmpty()) {
            AgentSpeakingReportSection("可以更好", AgentStudyAmber) {
                result.improvements.forEach { AgentSpeakingBullet(it, AgentStudyAmber) }
            }
        }
        if (result.corrections.isNotEmpty()) {
            AgentSpeakingReportSection("纠错", AgentPracticeWrong) {
                result.corrections.forEach { correction ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (correction.from.isNotBlank()) {
                                Text(correction.from, style = MaterialTheme.typography.bodySmall, color = AgentPracticeWrong, textDecoration = TextDecoration.LineThrough)
                            }
                            if (correction.to.isNotBlank()) {
                                Text("→", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(correction.to, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = AgentPracticeSuccess)
                            }
                        }
                        if (correction.note.isNotBlank()) {
                            Text(correction.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (result.sampleAnswer.isNotBlank()) {
            AgentSpeakingReportSection("参考范文", AgentStudyViolet) {
                Text(result.sampleAnswer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (result.comment.isNotBlank()) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = AgentStudyBlue.copy(alpha = 0.08f)) {
                Text(result.comment, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium, color = AgentStudyBlue, fontWeight = FontWeight.Medium)
            }
        }
    }
}

