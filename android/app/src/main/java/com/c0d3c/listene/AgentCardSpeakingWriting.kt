package com.c0d3c.listene

import android.Manifest
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 卡片口语与写作组件（Card Speaking/Writing）：口语提示/评测/报告/作答 + 写作大纲/草稿 + 原子块/行/操作。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentCardSpeakingPromptComponent(component: AgentCardComponentSpec) {
    AgentCardAtomBlock(
        component = component,
        defaultTitle = "口语练习",
        icon = Icons.Default.Mic,
        accent = AgentStudyRose,
        autoExpandable = false,
        showHeaderText = false
    ) {
        val prompts = AgentCardDisplayPayload.speakingPrompts(component)
        if (prompts.isNotEmpty()) {
            prompts.take(agentCardVisibleLimit(6)).forEachIndexed { index, prompt ->
                AgentCardNumberedRow(index + 1, prompt)
            }
        } else {
            val prompt = component.text.ifBlank { component.items.firstOrNull().orEmpty() }
            if (prompt.isNotBlank()) {
                Text(
                    prompt,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        val options = AgentCardDisplayPayload.options(component)
            .filterNot { option -> prompts.any { it == option } }
        if (options.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            AgentCardTokenRow(options.take(agentCardVisibleLimit(8)))
        }
        val taskText = remember(prompts, component.text, component.items) {
            val joined = prompts.take(3).joinToString("  ").trim()
            joined.ifBlank { component.text.ifBlank { component.items.firstOrNull().orEmpty() } }.trim()
        }
        AgentCardSpeakingAssessBox(taskText)
        var showManualInput by remember { mutableStateOf(true) }
        Text(
            if (showManualInput) "收起手动输入" else "或手动输入文字答案",
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { showManualInput = !showManualInput }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        AnimatedVisibility(visible = showManualInput) {
            AgentCardSpeakingResponseBox()
        }
    }
}

@Composable
private fun AgentCardSpeakingAssessBox(promptText: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var assessing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SpeakingAssessment?>(null) }
    var errorText by remember { mutableStateOf<String?>(null) }
    val recordFile = remember { mutableStateOf<File?>(null) }
    val recordJob = remember { mutableStateOf<Job?>(null) }
    val startedAt = remember { mutableStateOf(0L) }
    val maxMs = 120_000L

    fun startRecording() {
        if (recording) return
        errorText = null
        result = null
        val file = File(ctx.cacheDir, "speaking_${System.currentTimeMillis()}.wav")
        recordFile.value = file
        startedAt.value = System.currentTimeMillis()
        elapsedMs = 0L
        recording = true
        recordJob.value = scope.launch(Dispatchers.IO) {
            runCatching { recordAgentWavFile(file) }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    recording = false
                    recordJob.value = null
                    recordFile.value = null
                    errorText = e.message ?: "录音启动失败"
                }
            }
        }
    }

    fun stopAndAssess() {
        if (!recording) return
        recording = false
        val durationMs = (System.currentTimeMillis() - startedAt.value).coerceAtLeast(0L)
        val job = recordJob.value
        recordJob.value = null
        val file = recordFile.value
        recordFile.value = null
        scope.launch {
            job?.cancelAndJoin()
            if (file == null || !file.exists() || file.length() <= 44L) {
                errorText = "没有录到声音，请重试"
                file?.let { runCatching { it.delete() } }
                return@launch
            }
            assessing = true
            errorText = null
            try {
                val base64 = withContext(Dispatchers.IO) {
                    Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                }
                result = AgentConversationService.assessSpeaking(
                    base64 = base64,
                    prompt = promptText,
                    durationMs = durationMs
                )
            } catch (e: Exception) {
                errorText = e.message ?: "口语评测失败"
            } finally {
                assessing = false
                runCatching { file.delete() }
            }
        }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else errorText = "需要麦克风权限才能录音"
    }

    LaunchedEffect(recording) {
        while (recording) {
            elapsedMs = System.currentTimeMillis() - startedAt.value
            if (elapsedMs >= maxMs) {
                stopAndAssess()
                break
            }
            delay(100)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            recordJob.value?.cancel()
            recordFile.value?.let { runCatching { it.delete() } }
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("agent_speaking_assess_box")
            .shadow(8.dp, RoundedCornerShape(8.dp), clip = false),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val micEnabled = !assessing
            val micColor = if (recording) AgentPracticeWrong else AgentStudyRose
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(micColor.copy(alpha = if (micEnabled) 0.10f else 0.05f))
                    .clickable(enabled = micEnabled) {
                        if (recording) {
                            stopAndAssess()
                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            startRecording()
                        } else {
                            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (assessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = AgentStudyRose
                    )
                } else {
                    Icon(
                        imageVector = if (recording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = null,
                        tint = micColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
                val label = when {
                    assessing -> "AI 正在评测你的发音…"
                    recording -> "点击停止并评分 · ${agentSpeakingElapsedLabel(elapsedMs)}"
                    result != null -> "重新录音作答"
                    else -> "点击录音，朗读你的口语回答"
                }
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (assessing) AgentStudyRose else micColor
                )
            }
            if (recording) {
                Text(
                    "建议作答 30-60 秒，最长 2 分钟自动停止。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            errorText?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = AgentPracticeWrong)
            }
            result?.let { AgentSpeakingReport(it) }
            AgentProgressTracker(done = result != null, componentType = "speaking_prompt", skill = "口语", score = result?.overall)
        }
    }
}

private fun agentSpeakingElapsedLabel(ms: Long): String {
    val totalSec = (ms / 1000L).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}

internal fun agentSpeakingScoreColor(score: Int): Color = when {
    score >= 80 -> AgentPracticeSuccess
    score >= 60 -> AgentStudyAmber
    else -> AgentPracticeWrong
}

@Composable
internal fun AgentSpeakingScoreBar(label: String, score: Int) {
    val color = agentSpeakingScoreColor(score)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$score", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth((score / 100f).coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

// 发音细节（音素级）：口语评测 + 跟读练习共用。展示总述 + 每个念错词的 音标/单词分/具体音素问题(目标音→听着像 + 纠正提示)。为空则不渲染。
@Composable
internal fun AgentPronunciationDetailSection(detail: SpeakingPronunciationDetail) {
    if (detail.summary.isBlank() && detail.words.isEmpty()) return
    AgentSpeakingReportSection("发音细节 · 音素级", AgentStudyViolet) {
        if (detail.summary.isNotBlank()) {
            Text(
                detail.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        detail.words.forEach { word ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        word.word,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (word.ipa.isNotBlank()) {
                        Text(word.ipa, style = MaterialTheme.typography.labelMedium, color = AgentStudyTeal)
                    }
                    if (word.score in 1..100) {
                        val scoreColor = agentSpeakingScoreColor(word.score)
                        Text(
                            "${word.score}",
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(scoreColor.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = scoreColor
                        )
                    }
                }
                word.issues.forEach { issue ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val phonemeLabel = buildString {
                            append(issue.phoneme)
                            if (issue.heard.isNotBlank()) append(" → 听着像 ").append(issue.heard)
                        }
                        if (phonemeLabel.isNotBlank()) {
                            Text(
                                phonemeLabel,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AgentPracticeWrong.copy(alpha = 0.10f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = AgentPracticeWrong
                            )
                        }
                        if (issue.tip.isNotBlank()) {
                            Text(
                                issue.tip,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSpeakingReport(result: SpeakingAssessment) {
    val overallColor = agentSpeakingScoreColor(result.overall)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AgentCardDivider()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(overallColor.copy(alpha = 0.12f))
                    .border(2.dp, overallColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${result.overall}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = overallColor
                    )
                    Text("总分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AgentSpeakingScoreBar("发音", result.scores.pronunciation)
                AgentSpeakingScoreBar("流利度", result.scores.fluency)
                AgentSpeakingScoreBar("语法", result.scores.grammar)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AgentSpeakingScoreBar("词汇", result.scores.vocabulary)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AgentSpeakingScoreBar("内容", result.scores.content)
            }
        }
        if (result.wpm > 0) {
            Text(
                "语速约 ${result.wpm} 词/分钟",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (result.transcript.isNotBlank()) {
            AgentSpeakingReportSection("你说的（AI 转写）", AgentStudyBlue) {
                Text(
                    result.transcript,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        AgentPronunciationDetailSection(result.pronunciationDetail)
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
                                Text(
                                    correction.from,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AgentPracticeWrong,
                                    textDecoration = TextDecoration.LineThrough
                                )
                            }
                            if (correction.to.isNotBlank()) {
                                Text("→", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    correction.to,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AgentPracticeSuccess
                                )
                            }
                        }
                        if (correction.note.isNotBlank()) {
                            Text(
                                correction.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        if (result.sampleAnswer.isNotBlank()) {
            AgentSpeakingReportSection("参考范例", AgentStudyViolet) {
                Text(
                    result.sampleAnswer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (result.comment.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = AgentStudyRose.copy(alpha = 0.08f)
            ) {
                Text(
                    result.comment,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AgentStudyRose,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
internal fun AgentSpeakingReportSection(title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = accent)
        content()
    }
}

@Composable
internal fun AgentSpeakingBullet(text: String, accent: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = accent)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun AgentCardSpeakingResponseBox() {
    val instanceKey = LocalAgentCardComponentInstanceKey.current
    var response by rememberSaveable("speaking:${agentCardWritingDraftSaveableKey(instanceKey)}") { mutableStateOf("") }
    var completed by rememberSaveable("speaking:${agentCardWritingDraftSaveableKey(instanceKey)}:completed") { mutableStateOf(false) }
    val wordCount = remember(response) { agentWritingDraftWordCount(response) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 148.dp)
            .testTag("agent_speaking_response_box")
            .shadow(8.dp, RoundedCornerShape(8.dp), clip = false),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BasicTextField(
                value = response,
                onValueChange = {
                    val next = agentWritingDraftStateAfterTextChange(
                        AgentWritingDraftUiState(response, completed),
                        it
                    )
                    response = next.draft
                    completed = next.evaluated
                },
                readOnly = completed,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .testTag("agent_speaking_response_input"),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(AgentStudyRose),
                decorationBox = { innerTextField ->
                    Box(Modifier.fillMaxWidth()) {
                        if (response.isBlank()) {
                            Text(
                                "写下你刚刚口头回答的要点，或直接输入口语答案。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        innerTextField()
                    }
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${response.length} 字符 · $wordCount 词",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    "清空",
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .testTag("agent_speaking_response_clear")
                        .clickable(enabled = response.isNotBlank() && !completed) { response = "" }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (response.isNotBlank() && !completed) AgentStudyRose else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            AgentTextAction(
                text = if (completed) "重新练习" else "完成口语练习",
                onClick = {
                    if (completed) {
                        val next = agentWritingDraftStateAfterRetry(AgentWritingDraftUiState(response, completed))
                        response = next.draft
                        completed = next.evaluated
                    } else {
                        if (agentSpeakingResponseCanComplete(response)) {
                            val next = agentWritingDraftStateAfterEvaluate(AgentWritingDraftUiState(response, completed))
                            response = next.draft
                            completed = next.evaluated
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("agent_speaking_response_action"),
                primary = true,
                enabled = completed || agentSpeakingResponseCanComplete(response),
                icon = if (completed) Icons.Default.EditNote else Icons.Default.CheckCircle,
                height = 38.dp
            )
            if (completed) {
                AgentCardPracticeFeedback(
                    correct = true,
                    message = "已完成口语练习",
                    answer = "",
                    explanation = agentSpeakingResponseFeedbackText(response)
                )
            }
        }
    }
}

internal fun agentSpeakingResponseFeedbackText(response: String): String {
    val wordCount = agentWritingDraftWordCount(response)
    return when {
        wordCount >= 45 -> "回答长度适合 45-60 秒口语练习。下一步可以检查连接词、例子和结尾是否自然。"
        wordCount >= 20 -> "回答已经成形。可以再补一个具体细节或原因，让口语内容更完整。"
        else -> "回答偏短。建议补充原因、例子或结果，形成更完整的口语答案。"
    }
}

@Composable
internal fun agentCardVisibleLimit(collapsedLimit: Int): Int =
    if (LocalAgentCardExpanded.current) Int.MAX_VALUE else collapsedLimit

@Composable
internal fun AgentCardAtomBlock(
    component: AgentCardComponentSpec,
    defaultTitle: String,
    icon: ImageVector,
    accent: Color? = null,
    forceExpandable: Boolean = false,
    autoExpandable: Boolean = true,
    initiallyExpanded: Boolean = agentCardComponentStartsExpanded(component.type),
    hideLongContentWhenCollapsed: Boolean = false,
    showHeaderText: Boolean = false,
    collapsedText: String = "已收起部分内容，点击展开。",
    content: @Composable ColumnScope.() -> Unit
) {
    if (!agentCardAtomBlockShouldRender(component)) return
    val tone = accent ?: MaterialTheme.colorScheme.primary
    val isLong = !agentCardComponentHasInteractivePractice(component.type) &&
        autoExpandable &&
        (forceExpandable || AgentCardDisplayPayload.isLong(component))
    val effectiveHideLongContentWhenCollapsed =
        hideLongContentWhenCollapsed || agentCardComponentUsesHiddenCollapsedBody(component)
    val componentInstanceKey = LocalAgentCardComponentInstanceKey.current
    var expanded by rememberSaveable(
        componentInstanceKey,
        component.type.name,
        component.title,
        component.text,
        component.items.hashCode(),
        component.pairs.hashCode(),
        component.tokens.hashCode(),
        component.steps.hashCode(),
        component.criteria.hashCode(),
        component.examples.hashCode()
    ) { mutableStateOf(initiallyExpanded) }
    val limitExpandedHeight = agentCardAtomBlockLimitsExpandedHeight(isLong, expanded, component.type)
    val limitCollapsedHeight = agentCardAtomBlockLimitsCollapsedHeight(isLong, expanded, component.type)
    val expandInteractionSource = remember { MutableInteractionSource() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (isLong) {
                            Modifier.clickable(
                                interactionSource = expandInteractionSource,
                                indication = null,
                                onClick = { expanded = !expanded }
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                AgentCardInlineHeader(
                    icon = icon,
                    title = agentCardComponentDisplayTitle(component, defaultTitle),
                    text = agentCardAtomHeaderText(component, showHeaderText)
                )
            }
            if (isLong) {
                AgentIconControl(
                    icon = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收回" else "展开",
                    onClick = { expanded = !expanded },
                    size = 34.dp,
                    iconSize = 20.dp,
                    accent = tone
                )
            }
        }
        CompositionLocalProvider(LocalAgentCardExpanded provides (!isLong || expanded)) {
            val expandedScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            limitCollapsedHeight -> Modifier.heightIn(max = agentCardCollapsedMaxHeightDp(component.type).dp)
                            limitExpandedHeight -> Modifier.heightIn(max = 360.dp).verticalScroll(expandedScrollState)
                            else -> Modifier
                        }
                    )
                    .clip(RoundedCornerShape(10.dp))
                    .then(
                        if (isLong && !expanded) {
                            Modifier.clickable(
                                interactionSource = expandInteractionSource,
                                indication = null,
                                onClick = { expanded = true }
                            )
                        } else {
                            Modifier
                        }
                    )
                    .background(tone.copy(alpha = 0.055f))
                    .border(BorderStroke(1.dp, tone.copy(alpha = 0.16f)), RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                if (!agentCardCollapsedBodyRendersContent(isLong, expanded, effectiveHideLongContentWhenCollapsed)) {
                    AgentCardInlineNotice(collapsedText)
                    AgentTextAction(
                        text = "展开",
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        height = 36.dp,
                        icon = Icons.Default.KeyboardArrowDown,
                        accent = tone
                    )
                } else {
                    content()
                }
            }
        }
        if (isLong && !expanded && !effectiveHideLongContentWhenCollapsed) {
            Text(
                collapsedText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = expandInteractionSource,
                        indication = null,
                        onClick = { expanded = true }
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun agentCardAtomHeaderText(
    component: AgentCardComponentSpec,
    showHeaderText: Boolean = false
): String = if (!showHeaderText || component.items.isNotEmpty()) "" else component.text.take(60)

@Composable
internal fun AgentCardKeyValueRow(label: String, value: String, accent: Color) {
    if (label.isBlank() && value.isBlank()) return
    val display = agentCardKeyValueRowTexts(label, value)
    if (display.first.isBlank()) {
        Text(
            display.second,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text(
            display.first,
            modifier = Modifier.widthIn(min = 42.dp, max = 116.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = accent,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            display.second,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun agentCardKeyValueRowTexts(label: String, value: String): Pair<String, String> {
    val cleanLabel = label.trim()
    val cleanValue = value.trim()
    if (cleanLabel.isBlank() && cleanValue.isBlank()) return "" to ""
    if (cleanValue.isBlank()) return "" to cleanLabel
    return cleanLabel.ifBlank { "•" } to cleanValue
}

@Composable
internal fun AgentCardSoundPairRow(left: String, right: String) {
    if (left.isBlank() && right.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AgentCardSoundToken(left.ifBlank { right }, Modifier.weight(1f))
        Text(
            "vs",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            color = AgentStudyRose
        )
        AgentCardSoundToken(right.ifBlank { left }, Modifier.weight(1f))
    }
}

@Composable
private fun AgentCardSoundToken(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.86f),
        border = BorderStroke(1.dp, AgentStudyRose.copy(alpha = 0.20f))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun AgentCardBulletRow(text: String) {
    if (text.isBlank()) return
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(5.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.72f))
        )
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun AgentCardNumberedRow(index: Int, text: String, accent: Color = MaterialTheme.colorScheme.primary) {
    if (text.isBlank()) return
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            index.toString(),
            modifier = Modifier.width(22.dp),
            color = accent,
            fontWeight = FontWeight.Black,
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentCardTokenRow(items: List<String>) {
    if (items.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        items.filter { it.isNotBlank() }.forEach { item ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
            ) {
                Text(
                    item,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun AgentCardDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.54f))
    )
}

@Composable
internal fun AgentSelectableText(text: String, selectable: Boolean, onDoneSelecting: () -> Unit = {}) {
    if (selectable) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectionContainer {
                Text(
                    text,
                    color = MaterialTheme.colorScheme.onSurface,
                    // 聊天气泡正文（用户 + AI）用 bodyLarge(16sp) 提升可读性，比默认 bodyMedium(14sp) 大一档。
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            Text(
                "完成选择",
                fontSize = 15.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onDoneSelecting)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    } else {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurface,
            // 聊天气泡正文（用户 + AI）用 bodyLarge(16sp) 提升可读性，比默认 bodyMedium(14sp) 大一档。
            style = MaterialTheme.typography.bodyLarge
        )
    }
}
