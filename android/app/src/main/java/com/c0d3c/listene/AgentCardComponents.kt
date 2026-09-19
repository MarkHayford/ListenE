package com.c0d3c.listene

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add

// 卡片渲染与练习组件（Card Components）：卡片宿主/头部/各题型展示组件 + 翻译/完形/选择/简答/排序/题组/阅读/GapMatch/图表/写作/造句等练习。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

@Composable
internal fun AgentChatEmptyState(
    activeWorkspace: LearningWorkspace?,
    modifier: Modifier = Modifier,
    onPromptClick: (String) -> Unit = {}
) {
    // 首屏起步：友好标题 + 几个可点的示例需求（点了直接把需求填进输入框，用户再发送）。
    // 覆盖 拍照答疑 / 出题 / 口语陪练 / 听力 四条主路径，让新用户 5 秒内知道能干嘛。
    val suggestions = listOf(
        "我拍了一道不会的题，帮我讲讲思路和答案",
        "出一道四级选词填空练练",
        "陪我用英文聊聊今天做了什么",
        "给我一段短对话听力，配几道题"
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "想学点什么？",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "直接说出你的需求，或点下面一个开始",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            suggestions.forEach { prompt ->
                Surface(
                    onClick = { onPromptClick(prompt) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        prompt,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Text(
                "💡 输入框右边的按钮 = 口语陪练；发送图片 = 拍照答疑；左上角菜单里有工作区 / 卡片库 / 错题本等",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

internal fun agentChatInputEnterKeyShouldSend(enabled: Boolean, value: String): Boolean =
    enabled && value.isNotBlank()

@Composable
internal fun AgentCardInlineHeader(
    icon: ImageVector,
    title: String,
    text: String = ""
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        ) {
            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (text.isNotBlank()) {
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
internal fun AgentCardInlineNotice(text: String) {
    if (text.isBlank()) return
    Text(
        text,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun AgentCardAnalysisInline(result: AnalysisResult) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            result.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis
        )
        if (result.diagnosisTags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                result.diagnosisTags.take(6).forEach { AgentAssistChip(it) }
            }
        }
        result.wrongQuestionInsights.take(2).forEach { insight ->
            AgentCardKeyValueRow(
                label = "第 ${insight.questionIndex + 1} 题",
                value = insight.mistakeType.ifBlank { insight.insight }.ifBlank { insight.question },
                accent = AgentPracticeWrong
            )
        }
        result.suggestions.take(2).forEach { item ->
            AgentCardBulletRow(item)
        }
    }
}

internal fun agentMaterialStatusText(record: HistoryRecord?, fallback: String = ""): String {
    if (record == null) return fallback
    val parts = buildList {
        val questionCount = record.content.questions.size
        if (questionCount > 0) add("${questionCount} 题")
        add(if (record.content.audioUrl.orEmpty().isNotBlank()) "有音频" else "无音频")
        add(if (record.content.script.orEmpty().isNotBlank()) "有原文" else "无原文")
    }
    return parts.joinToString(" | ").ifBlank { fallback }
}

internal fun agentCardProgressLabel(
    componentText: String,
    questionCount: Int,
    answeredCount: Int,
    revealed: Boolean,
    correctCount: Int,
    fallbackProgress: Float
): String {
    if (questionCount > 0) {
        return if (revealed) "答对 $correctCount / $questionCount" else "$answeredCount / $questionCount"
    }
    return componentText.ifBlank { "${(fallbackProgress * 100).toInt()}%" }
}

// 题目控件共享面：被微元节点 MicroNode.QuestionPreview 复用。
// record / viewModel / onRequestAiReview 经 CompositionLocal 注入（材料语境由 AgentCardMicroComponent 提供）。
@Composable
internal fun AgentQuestionPreviewSurface(fallbackText: String, instanceKey: String) {
    val ctx = LocalContext.current
    val record = LocalAgentMicroRecord.current
    val viewModel = LocalAgentMicroViewModel.current
    val onRequestAiReview = LocalAgentMicroOnRequestAiReview.current
    val questions = record?.content?.questions.orEmpty()
    if (record == null || viewModel == null || questions.isEmpty()) {
        AgentCardInlineNotice(fallbackText.ifBlank { "素材生成后，题目控件会在卡片内直接显示。" })
        return
    }
    val recordKey = "${record.id}_${record.answersRevealed}_${record.selectedAnswers.hashCode()}_$instanceKey"
    val answers = remember(recordKey) { mutableStateMapOf<Int, Int>().apply { putAll(record.selectedAnswers) } }
    var revealed by remember(recordKey) { mutableStateOf(record.answersRevealed) }
    var questionIndex by rememberSaveable(record.id, instanceKey) { mutableIntStateOf(0) }
    val safeQuestionIndex = questionIndex.coerceIn(0, questions.lastIndex)
    val answeredCount = answers.size
    val correctCount = questions.countIndexed { index, question -> answers[index] == question.correctAnswer }
    val actionState = agentMaterialQuestionPreviewActionState(
        questionCount = questions.size,
        answeredCount = answeredCount,
        revealed = revealed
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentCardInlineHeader(
            icon = Icons.AutoMirrored.Filled.FactCheck,
            title = "题目控件",
            text = fallbackText.ifBlank {
                val progress = "第 ${safeQuestionIndex + 1}/${questions.size} 题"
                if (revealed) "$progress · 答对 $correctCount / ${questions.size}" else "$progress · 已完成 $answeredCount / ${questions.size}"
            }
        )
        AgentCardQuestionPager(
            index = safeQuestionIndex,
            total = questions.size,
            onPrevious = { questionIndex = agentQuestionPagerTargetIndex(safeQuestionIndex, questions.size, -1) },
            onNext = { questionIndex = agentQuestionPagerTargetIndex(safeQuestionIndex, questions.size, 1) }
        )
        AgentCardQuestionAtom(
            index = safeQuestionIndex,
            question = questions[safeQuestionIndex],
            selectedAnswer = answers[safeQuestionIndex],
            revealed = revealed,
            onAnswer = { optionIndex ->
                if (!revealed) {
                    answers[safeQuestionIndex] = optionIndex
                    viewModel.updateHistoryAnswer(ctx, record.id, safeQuestionIndex, optionIndex)
                    questionIndex = agentQuestionIndexAfterAnswer(safeQuestionIndex, questions.size)
                }
            }
        )
        if (revealed) {
            agentQuestionSetFeedback(questions[safeQuestionIndex], answers[safeQuestionIndex])?.let { feedback ->
                val answer = questions[safeQuestionIndex].options
                    .getOrNull(questions[safeQuestionIndex].correctAnswer)
                    .orEmpty()
                AgentCardPracticeFeedback(
                    correct = feedback.correct,
                    message = feedback.message,
                    answer = answer,
                    explanation = feedback.explanation
                )
            }
        }
        AgentTextAction(
            text = if (actionState.enabled) actionState.text else actionState.disabledReason,
            onClick = {
                if (!revealed) {
                    if (answers.size < questions.size) {
                        AppNoticeBus.show("请先完成全部题目")
                        return@AgentTextAction
                    }
                    viewModel.confirmAnswersRevealed(ctx, record.id, answers.toMap())
                    revealed = true
                    AppNoticeBus.success("已核对答案")
                } else {
                    val latest = viewModel.getHistoryRecord(ctx, record.id)
                        ?.copy(answersRevealed = true, selectedAnswers = answers.toMap())
                        ?: record.copy(answersRevealed = true, selectedAnswers = answers.toMap())
                    onRequestAiReview?.invoke(latest)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            primary = true,
            enabled = actionState.enabled,
            height = 42.dp,
            icon = if (revealed) Icons.Default.AutoAwesome else Icons.AutoMirrored.Filled.FactCheck
        )
        AgentProgressTracker(
            done = revealed,
            componentType = "question_preview",
            skill = "听力",
            total = questions.size,
            correct = correctCount
        )
        AgentWrongQuestionCollector(
            done = revealed,
            questions = questions,
            answers = answers,
            componentType = "question_preview",
            skill = "听力"
        )
    }
}

@Composable
internal fun AgentCardQuestionPager(
    index: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    if (total <= 1) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AgentIconControl(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "上一题",
            onClick = onPrevious,
            enabled = index > 0,
            size = 34.dp,
            iconSize = 20.dp,
            testTag = "agent_question_previous"
        )
        Text(
            "题目 ${index + 1} / $total",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AgentIconControl(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "下一题",
            onClick = onNext,
            enabled = index < total - 1,
            size = 34.dp,
            iconSize = 20.dp,
            testTag = "agent_question_next"
        )
    }
}

@Composable
internal fun AgentCardQuestionAtom(
    index: Int,
    question: Question,
    selectedAnswer: Int?,
    revealed: Boolean,
    onAnswer: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (question.tag.isNotBlank()) {
            AgentReadingTagChip(question.tag)
        }
        AgentCardQuestionPrompt(index = index, questionText = question.questionText)
        question.options.forEachIndexed { optionIndex, option ->
            val selected = selectedAnswer == optionIndex
            val correct = question.correctAnswer == optionIndex
            val color = when {
                revealed && correct -> AgentPracticeSuccess.copy(alpha = 0.11f)
                revealed && selected && !correct -> AgentPracticeWrong.copy(alpha = 0.12f)
                selected -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.11f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
            }
            val borderColor = when {
                revealed && correct -> AgentPracticeSuccess.copy(alpha = 0.34f)
                revealed && selected && !correct -> AgentPracticeWrong.copy(alpha = 0.34f)
                selected -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.30f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)
            }
            val content: @Composable () -> Unit = {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text(optionLabel(optionIndex).take(1), fontWeight = FontWeight.Black)
                    Text(option, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (revealed && correct) {
                        Icon(Icons.Default.CheckCircle, contentDescription = "正确", tint = AgentPracticeSuccess, modifier = Modifier.size(17.dp))
                    } else if (revealed && selected) {
                        Icon(Icons.Default.Cancel, contentDescription = "答错", tint = AgentPracticeWrong, modifier = Modifier.size(17.dp))
                    }
                }
            }
            if (agentQuestionOptionEnabled(revealed)) {
                Surface(
                    onClick = { onAnswer(optionIndex) },
                    shape = RoundedCornerShape(8.dp),
                    color = color,
                    border = BorderStroke(1.dp, borderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent_question_option_${index}_$optionIndex"),
                    content = content
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = color,
                    border = BorderStroke(1.dp, borderColor),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent_question_option_${index}_$optionIndex"),
                    content = content
                )
            }
        }
    }
}

@Composable
private fun AgentCardQuestionPrompt(index: Int, questionText: String) {
    val displayText = agentCardQuestionPromptText(index, questionText)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.70f))
    ) {
        Text(
            displayText,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = true
        )
    }
}

internal fun agentCardQuestionPromptText(index: Int, questionText: String): String =
    "${index + 1}. ${questionText.trim()}".trim()

internal fun agentCardResolvedTranscriptText(
    componentText: String,
    recordScript: String,
    source: String
): String {
    val transcript = componentText.trim()
    val materialScript = recordScript.trim()
    if (materialScript.isBlank()) return transcript
    if (source.equals("latestMaterial.script", ignoreCase = true)) return materialScript
    if (transcript.isBlank()) return materialScript
    return if (agentCardTranscriptPreviewMatchesMaterial(transcript, materialScript)) {
        materialScript
    } else {
        transcript
    }
}

private fun agentCardTranscriptPreviewMatchesMaterial(preview: String, materialScript: String): Boolean {
    val previewBody = preview.trim().trimEnd('.', '…').trim()
    if (previewBody.length < 40 || previewBody.length == preview.trim().length) return false
    val normalizedPreview = agentCardNormalizeTranscriptForPrefix(previewBody)
    val normalizedMaterial = agentCardNormalizeTranscriptForPrefix(materialScript)
    return normalizedPreview.isNotBlank() && normalizedMaterial.startsWith(normalizedPreview)
}

private fun agentCardNormalizeTranscriptForPrefix(text: String): String =
    text.replace(Regex("\\s+"), " ").trim()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentSentenceVocabPickerDialog(sentence: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val words = remember(sentence) {
        sentence.split(Regex("\\s+"))
            .map { it.replace(Regex("^[^\\p{L}]+|[^\\p{L}]+$"), "") }
            .filter { it.length >= 2 && it.any { c -> c.isLetter() } }
            .distinct()
            .take(40)
    }
    var selected by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VocabLookup?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun lookup(word: String) {
        selected = word; result = null; error = null; looking = true
        scope.launch {
            val r = VocabStore.lookup(ctx, word)
            looking = false
            if (r == null) error = "查询失败，请重试" else result = r
        }
    }
    Dialog(onDismissRequest = onDismiss) {
        AgentFloatingPanel {
            AgentFloatingPanelHeader(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = "挑生词",
                subtitle = "点这句里不认识的词，查询后可加入生词本。"
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                words.forEach { w ->
                    val isSel = selected == w
                    Surface(
                        onClick = { lookup(w) },
                        shape = CircleShape,
                        color = if (isSel) AgentStudyTeal.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(1.dp, (if (isSel) AgentStudyTeal else MaterialTheme.colorScheme.outlineVariant).copy(alpha = 0.5f))
                    ) {
                        Text(
                            w,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSel) AgentStudyTeal else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
            if (looking) AgentThinkingText("查询中…")
            error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = AgentPracticeWrong) }
            result?.let { res ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AgentStudyTeal.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, AgentStudyTeal.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(res.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            if (res.phonetic.isNotBlank()) Text(res.phonetic, style = MaterialTheme.typography.bodySmall, color = AgentStudyTeal, modifier = Modifier.padding(bottom = 2.dp))
                        }
                        if (res.meaning.isNotBlank()) Text(res.meaning, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        if (res.example.isNotBlank()) Text(res.example, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f))
                        AgentTextAction(
                            text = "加入生词本",
                            onClick = {
                                scope.launch {
                                    val ok = VocabStore.add(ctx, res)
                                    if (ok) { AppNoticeBus.success("已加入生词本：${res.word}"); result = null; selected = null }
                                    else AppNoticeBus.error("加入失败，请重试")
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            primary = true,
                            icon = Icons.Default.AddCircle,
                            height = 38.dp
                        )
                    }
                }
            }
            AgentTextAction(text = "关闭", onClick = onDismiss, modifier = Modifier.fillMaxWidth(), height = 42.dp)
        }
    }
}

// 逐句点播共享面：被微元节点 MicroNode.SentenceTranscript 复用（同 Chart→AgentChartSurface 模式）。
// 入参为「预构建的段落/句子分组 + 音频地址」，自带 ExoPlayer 片段播放 + 点句跳播 + 生词挑选。
@Composable
internal fun AgentSentenceTranscriptSurface(
    audioUrl: String?,
    groups: List<AgentParagraphSentenceGroup>,
    instanceKey: String
) {
    if (groups.isEmpty()) {
        AgentCardInlineNotice("这套素材没有解析出可定位的英文句子。")
        return
    }
    var paragraphIndex by rememberSaveable(instanceKey) { mutableIntStateOf(0) }
    val safeParagraphIndex = paragraphIndex.coerceIn(0, groups.lastIndex)
    val paragraph = groups[safeParagraphIndex]
    var sentenceIndex by rememberSaveable(instanceKey, safeParagraphIndex) { mutableIntStateOf(0) }
    var autoPlayRequest by remember(instanceKey) { mutableIntStateOf(0) }
    var autoPlayClipId by remember(instanceKey) { mutableStateOf<String?>(null) }
    val safeSentenceIndex = sentenceIndex.coerceIn(0, paragraph.sentences.lastIndex.coerceAtLeast(0))
    val clip = paragraph.sentences[safeSentenceIndex]
    var vocabPickerSentence by remember(instanceKey) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentCardInlineHeader(
            icon = Icons.Default.GraphicEq,
            title = "逐句点播",
            text = "段落 ${safeParagraphIndex + 1}/${groups.size} · 句子 ${safeSentenceIndex + 1}/${paragraph.sentences.size}"
        )
        AgentSegmentClipPlayer(
            audioUrl = audioUrl,
            clip = clip,
            autoPlayRequest = if (autoPlayClipId == clip.id) autoPlayRequest else 0
        )
        AgentTextAction(
            text = "挑生词加入生词本",
            onClick = { vocabPickerSentence = clip.text },
            modifier = Modifier.fillMaxWidth(),
            primary = false,
            icon = Icons.AutoMirrored.Filled.MenuBook,
            height = 38.dp
        )
        vocabPickerSentence?.let { sentence ->
            AgentSentenceVocabPickerDialog(sentence = sentence, onDismiss = { vocabPickerSentence = null })
        }
        AgentTranscriptSentenceList(
            groups = groups,
            activeParagraphIndex = safeParagraphIndex,
            activeSentenceIndex = safeSentenceIndex,
            onSentenceClick = { nextParagraphIndex, nextSentenceIndex, nextClip ->
                paragraphIndex = nextParagraphIndex
                sentenceIndex = nextSentenceIndex
                autoPlayClipId = nextClip.id
                autoPlayRequest += 1
            }
        )
    }
}

// 反馈控件共享面：被微元节点 MicroNode.Feedback 复用。record / onRequestAiReview 经 CompositionLocal 注入。
@Composable
internal fun AgentFeedbackSurface(fallbackText: String) {
    val record = LocalAgentMicroRecord.current
    val onRequestAiReview = LocalAgentMicroOnRequestAiReview.current
    val questionCount = record?.content?.questions?.size ?: 0
    val correctCount = record?.takeIf { it.answersRevealed }?.let {
        it.content.questions.countIndexed { index, question -> it.selectedAnswers[index] == question.correctAnswer }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentCardInlineHeader(
            icon = Icons.Default.CheckCircle,
            title = "反馈控件",
            text = fallbackText.ifBlank {
                when {
                    record == null -> "等待素材和答题记录"
                    !record.answersRevealed -> "核对答案后显示对错反馈"
                    else -> "答对 ${correctCount ?: 0} / $questionCount 题"
                }
            }
        )
        when {
            record == null -> AgentCardInlineNotice("素材生成后，这里会显示答题反馈。")
            !record.answersRevealed -> AgentCardInlineNotice("完成并核对题目后，AI 可以继续生成分析卡片。")
            record.analysisResult != null -> AgentCardAnalysisInline(record.analysisResult)
            else -> AgentTextAction(
                text = "交给 AI 分析",
                onClick = { onRequestAiReview?.invoke(record) },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                icon = Icons.Default.AutoAwesome
            )
        }
    }
}

internal fun agentCardGrammarItemsToDisplay(component: AgentCardComponentSpec): List<String> {
    val body = normalizeAgentCardDisplayDedupText(component.text)
    return component.items.filter { item ->
        val normalized = normalizeAgentCardDisplayDedupText(item)
        normalized.isNotBlank() && !agentCardDisplayTextAlreadyCovered(normalized, body)
    }
}

private fun agentCardDisplayTextAlreadyCovered(item: String, body: String): Boolean {
    if (body.isBlank()) return false
    return item == body || body.contains(item) || item.contains(body)
}

private fun normalizeAgentCardDisplayDedupText(value: String): String =
    value.trim().lowercase().replace(Regex("\\s+"), " ")

