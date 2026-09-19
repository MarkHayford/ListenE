package com.c0d3c.listene

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 聊天内联「拍照答疑」结果：复用 AgentSolveResultView，自带「加入错题本/生词本」处理（原独立 Solve 页已并入聊天）。
@Composable
internal fun AgentSolveInlineResult(solve: SolveResult, messageId: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var addedToReview by remember(messageId) { mutableStateOf(false) }
    val addedVocab = remember(messageId) { mutableStateMapOf<String, Boolean>() }
    AgentSolveResultView(
        result = solve,
        addedToReview = addedToReview,
        addedVocabWords = addedVocab.keys,
        onAddToReview = {
            if (addedToReview) return@AgentSolveResultView
            scope.launch {
                val explanation = buildList {
                    if (solve.points.isNotEmpty()) add("考点：" + solve.points.joinToString("；"))
                    if (solve.steps.isNotEmpty()) addAll(solve.steps.mapIndexed { i, s -> "${i + 1}. $s" })
                    if (solve.pitfalls.isNotEmpty()) add("易错点：" + solve.pitfalls.joinToString("；"))
                }.joinToString("\n")
                ReviewStore.addWrong(
                    ctx = ctx,
                    componentType = "solve",
                    skill = solve.skill.ifBlank { null },
                    kind = "qa",
                    prompt = solve.questionText.ifBlank { solve.answer },
                    options = emptyList(),
                    answer = solve.answer,
                    explanation = explanation
                )
                addedToReview = true
                AppNoticeBus.success("已加入错题本")
            }
        },
        onAddVocab = { v ->
            if (addedVocab.containsKey(v.word)) return@AgentSolveResultView
            scope.launch {
                val ok = VocabStore.add(ctx, VocabLookup(v.word, v.phonetic, v.meaning, v.example))
                if (ok) { addedVocab[v.word] = true; AppNoticeBus.success("已加入生词本：${v.word}") }
                else AppNoticeBus.error("加入失败，请重试")
            }
        }
    )
}

@Composable
internal fun AgentSolveResultView(
    result: SolveResult,
    addedToReview: Boolean,
    addedVocabWords: Set<String>,
    onAddToReview: () -> Unit,
    onAddVocab: (SolveVocab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().testTag("agent_solve_result"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 题型 / 技能标签
        if (result.questionType.isNotBlank() || result.skill.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (result.questionType.isNotBlank()) AgentSolveChip(result.questionType, AgentStudyBlue)
                if (result.skill.isNotBlank()) AgentSolveChip(result.skill, AgentStudyTeal)
            }
        }
        // 读题回显
        if (result.questionText.isNotBlank()) {
            AgentSolveSection("题目") {
                Text(result.questionText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        // 考点
        if (result.points.isNotEmpty()) {
            AgentSolveSection("考点") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    result.points.forEach { AgentSolveBullet(it, AgentStudyBlue) }
                }
            }
        }
        // 分步解析
        if (result.steps.isNotEmpty()) {
            AgentSolveSection("分步解析") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.steps.forEachIndexed { i, step ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                modifier = Modifier.size(22.dp).clip(CircleShape).background(AgentStudyViolet.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = AgentStudyViolet)
                            }
                            Text(step, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        // 答案
        if (result.answer.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AgentPracticeSuccess.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, AgentPracticeSuccess.copy(alpha = 0.32f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("答案", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = AgentPracticeSuccess)
                    Text(result.answer, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        // 易错点
        if (result.pitfalls.isNotEmpty()) {
            AgentSolveSection("易错点") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    result.pitfalls.forEach { AgentSolveBullet(it, AgentStudyAmber) }
                }
            }
        }
        // 一句点拨
        if (result.comment.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Lightbulb, contentDescription = null, tint = AgentStudyAmber, modifier = Modifier.size(16.dp))
                Text(result.comment, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
        }
        // 加入错题本
        AgentTextAction(
            text = if (addedToReview) "已加入错题本" else "加入错题本",
            onClick = onAddToReview,
            modifier = Modifier.fillMaxWidth().testTag("agent_solve_add_review"),
            primary = false,
            enabled = !addedToReview,
            icon = Icons.Default.CheckCircle,
            height = 42.dp
        )
        // 生词
        if (result.vocab.isNotEmpty()) {
            AgentSolveSection("生词") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.vocab.forEach { v ->
                        val added = addedVocabWords.contains(v.word)
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(v.word, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                                    if (v.phonetic.isNotBlank()) Text(v.phonetic, style = MaterialTheme.typography.labelSmall, color = AgentStudyTeal, modifier = Modifier.padding(bottom = 2.dp))
                                }
                                if (v.meaning.isNotBlank()) Text(v.meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                            }
                            AgentIconControl(
                                icon = if (added) Icons.Default.CheckCircle else Icons.Default.AddCircle,
                                contentDescription = if (added) "已加入生词本" else "加入生词本",
                                onClick = { if (!added) onAddVocab(v) },
                                accent = if (added) AgentPracticeSuccess else AgentStudyTeal,
                                size = 30.dp,
                                iconSize = 20.dp,
                                bordered = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentSolveSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        content()
    }
}

@Composable
private fun AgentSolveBullet(text: String, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.padding(top = 7.dp).size(5.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AgentSolveChip(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.12f)) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color)
    }
}
