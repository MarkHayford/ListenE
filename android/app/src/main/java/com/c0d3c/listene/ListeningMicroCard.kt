package com.c0d3c.listene

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// 方案乙：听力素材“自包含微元卡”的集成渲染——真音频播放 + 听力题作答(持久化到 record) + 核对 + AI 复盘(带答案) + 查看原文。
// 与旧 question_preview 等价的关键行为：作答经 viewModel.updateHistoryAnswer 写回 record.selectedAnswers，
// 核对时 confirmAnswersRevealed 落盘，AI 复盘 onRequestAiReview(record) 即可拿到用户答案。仅在听力微元开关开启时使用。
@Composable
internal fun ListeningMicroCardView(
    record: HistoryRecord,
    instanceKey: String,
    viewModel: ListeningViewModel,
    onRequestAiReview: (HistoryRecord) -> Unit
) {
    val ctx = LocalContext.current
    val content = record.content
    val questions = remember(content) {
        content.questions.filter { it.questionText.isNotBlank() && it.options.size >= 2 }
    }
    var selected by remember(record.id) { mutableStateOf(record.selectedAnswers) }
    var revealed by rememberSaveable(instanceKey) { mutableStateOf(record.answersRevealed) }
    // 仅本次会话内点过「核对答案」才算一次新作答（重开已核对的记录不重复上报进度）。
    var gradedThisSession by remember(record.id) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        if (content.title.isNotBlank()) {
            Text(content.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        val audioUrl = content.audioUrl
        if (!audioUrl.isNullOrBlank()) {
            AgentAudioPlayer(audioUrl)
        }
        questions.forEachIndexed { qIndex, q ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "${qIndex + 1}. ${q.questionText}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                q.options.forEachIndexed { oIndex, option ->
                    val isSelected = selected[qIndex] == oIndex
                    val isAnswer = revealed && oIndex == q.correctAnswer
                    val tone = when {
                        revealed && isAnswer -> AgentPracticeSuccess
                        revealed && isSelected && !isAnswer -> AgentPracticeWrong
                        isSelected -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .border(BorderStroke(1.dp, tone.copy(alpha = 0.5f)), RoundedCornerShape(8.dp))
                            .background(tone.copy(alpha = if (isSelected || isAnswer) 0.10f else 0f))
                            .clickable(enabled = !revealed) {
                                selected = selected + (qIndex to oIndex)
                                viewModel.updateHistoryAnswer(ctx, record.id, qIndex, oIndex)
                            }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            option,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (revealed && isAnswer) AgentPracticeSuccess else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                // 核对后逐题解析：与旧 question_preview 同一套反馈（模型给的 explanation，没给则按题型兜底），
                // 修复「听力核对答案只标对错、没有解析」。
                if (revealed) {
                    agentQuestionSetFeedback(q, selected[qIndex])?.let { feedback ->
                        AgentCardPracticeFeedback(
                            correct = feedback.correct,
                            message = feedback.message,
                            answer = q.options.getOrNull(q.correctAnswer).orEmpty(),
                            explanation = feedback.explanation
                        )
                    }
                }
            }
        }
        val allAnswered = questions.isNotEmpty() && questions.indices.all { selected.containsKey(it) }
        val correctCount = questions.indices.count { selected[it] == questions[it].correctAnswer }
        AgentTextAction(
            text = if (revealed) "重新作答" else "核对答案",
            onClick = {
                if (revealed) {
                    revealed = false
                    gradedThisSession = false
                } else {
                    revealed = true
                    gradedThisSession = true
                    viewModel.confirmAnswersRevealed(ctx, record.id, selected)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            primary = true,
            enabled = revealed || allAnswered
        )
        // 与旧 question_preview 对齐的学习闭环：核对后上报进度（雷达/Elo）并把答错题收进错题本（SRS）。
        AgentProgressTracker(
            done = gradedThisSession,
            componentType = "question_preview",
            skill = "听力",
            total = questions.size,
            correct = correctCount
        )
        AgentWrongQuestionCollector(
            done = revealed,
            questions = questions,
            answers = selected,
            componentType = "question_preview",
            skill = "听力"
        )
        if (revealed) {
            AgentCardPracticeFeedback(
                correct = questions.isNotEmpty() && correctCount == questions.size,
                message = "答对 $correctCount / ${questions.size}",
                explanation = ""
            )
            AgentTextAction(
                text = "AI 复盘",
                onClick = { onRequestAiReview(record) },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.Analytics
            )
        }
        if (content.script.isNotBlank()) {
            var showScript by rememberSaveable(instanceKey, "script") { mutableStateOf(false) }
            AgentTextAction(
                text = if (showScript) "收起原文" else "查看原文 · 逐句精听",
                onClick = { showScript = !showScript },
                modifier = Modifier.fillMaxWidth()
            )
            if (showScript) {
                // 逐句精听：有音频时点句即播（cardSpec 退役后把逐句点播面接回自包含听力卡）；无音频退回纯文本原文。
                val groups = remember(content) { buildAgentParagraphSentenceGroups(content) }
                if (!audioUrl.isNullOrBlank() && groups.isNotEmpty()) {
                    AgentSentenceTranscriptSurface(
                        audioUrl = audioUrl,
                        groups = groups,
                        instanceKey = "${instanceKey}_sentences"
                    )
                } else {
                    AgentCardInlineNotice(content.script)
                }
            }
        }
    }
}
