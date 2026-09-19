package com.c0d3c.listene

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 卡片练习组件（Practice）：翻译/完形/多空完形/选择/简答/排序/题组等可作答练习 + 反馈/选项。由 AgentCardComponents.kt 细分而来，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

internal fun normalizeAgentClozeAnswer(value: String): String =
    value.trim().lowercase().replace(Regex("\\s+"), " ")

@Composable
internal fun AgentCardPracticeFeedback(
    correct: Boolean,
    message: String = if (correct) "回答正确" else "不正确",
    answer: String = "",
    explanation: String
) {
    val tone = if (correct) AgentPracticeSuccess else AgentPracticeWrong
    val displayExplanation = agentPracticeFeedbackDisplayExplanation(explanation, answer)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tone.copy(alpha = 0.10f))
            .border(
                BorderStroke(1.dp, tone.copy(alpha = 0.24f)),
                RoundedCornerShape(12.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(tone.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (correct) Icons.Default.CheckCircle else Icons.Default.Cancel,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(17.dp)
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                message,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("agent_practice_feedback_status"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Black,
                color = tone,
                softWrap = true,
                overflow = TextOverflow.Clip
            )
            if (answer.isNotBlank()) {
                Text(
                    "正确答案：$answer",
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("agent_practice_feedback_answer"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    softWrap = true,
                    overflow = TextOverflow.Clip
                )
            }
            if (displayExplanation.isNotBlank()) {
                Text(
                    displayExplanation,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                    overflow = TextOverflow.Clip
                )
            }
        }
    }
}

@Composable
internal fun AgentCardChoiceChip(
    text: String,
    selected: Boolean,
    correct: Boolean,
    revealed: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tone = when {
        revealed && correct -> AgentPracticeSuccess
        revealed && selected && !correct -> AgentPracticeWrong
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(18.dp),
        color = tone.copy(alpha = if (selected || revealed && correct) 0.11f else 0.06f),
        border = BorderStroke(1.dp, tone.copy(alpha = if (selected || revealed && correct) 0.34f else 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (revealed && correct) {
                Icon(Icons.Default.CheckCircle, contentDescription = "正确", tint = AgentPracticeSuccess, modifier = Modifier.size(15.dp))
            } else if (revealed && selected) {
                Icon(Icons.Default.Cancel, contentDescription = "答错", tint = AgentPracticeWrong, modifier = Modifier.size(15.dp))
            }
        }
    }
}

internal fun normalizeAgentShortAnswer(value: String): String =
    value.trim()
        .replace('’', '\'')
        .replace('‘', '\'')
        .replace(Regex("\\bi'm\\b", RegexOption.IGNORE_CASE), "i am")
        .replace(Regex("\\byou're\\b", RegexOption.IGNORE_CASE), "you are")
        .replace(Regex("\\bhe's\\b", RegexOption.IGNORE_CASE), "he is")
        .replace(Regex("\\bshe's\\b", RegexOption.IGNORE_CASE), "she is")
        .replace(Regex("\\bit's\\b", RegexOption.IGNORE_CASE), "it is")
        .replace(Regex("\\bwe're\\b", RegexOption.IGNORE_CASE), "we are")
        .replace(Regex("\\bthey're\\b", RegexOption.IGNORE_CASE), "they are")
        .replace(Regex("\\bcan't\\b", RegexOption.IGNORE_CASE), "cannot")
        .replace(Regex("\\bwon't\\b", RegexOption.IGNORE_CASE), "will not")
        .replace(Regex("\\bdon't\\b", RegexOption.IGNORE_CASE), "do not")
        .replace(Regex("\\bdoesn't\\b", RegexOption.IGNORE_CASE), "does not")
        .replace(Regex("\\bdidn't\\b", RegexOption.IGNORE_CASE), "did not")
        .replace(Regex("\\bisn't\\b", RegexOption.IGNORE_CASE), "is not")
        .replace(Regex("\\baren't\\b", RegexOption.IGNORE_CASE), "are not")
        .replace(Regex("\\bwasn't\\b", RegexOption.IGNORE_CASE), "was not")
        .replace(Regex("\\bweren't\\b", RegexOption.IGNORE_CASE), "were not")
        .replace(Regex("[.!?。！？]+$"), "")
        .replace(Regex("[,，]"), "")
        .replace(Regex("\\s+"), " ")
        .lowercase()

