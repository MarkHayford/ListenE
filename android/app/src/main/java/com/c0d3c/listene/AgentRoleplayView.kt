package com.c0d3c.listene

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// AI 角色扮演对话（Roleplay）：场景/口音/输入框/气泡/反馈报告。从 AgentListenEApp.kt 整屏抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

internal data class AgentRoleplayScenario(val label: String, val scene: String, val desc: String)

internal val agentRoleplayScenarios = listOf(
    AgentRoleplayScenario("咖啡馆点单", "ordering coffee and a snack at a café", "点单、加料、结账"),
    AgentRoleplayScenario("求职面试", "a job interview for an entry-level position", "自我介绍、回答提问"),
    AgentRoleplayScenario("问路", "asking a stranger for directions to a museum", "询问路线、听懂指引"),
    AgentRoleplayScenario("购物退换", "returning a product at a store", "说明问题、申请退换"),
    AgentRoleplayScenario("看医生", "a visit to the doctor describing your symptoms", "描述症状、听医嘱"),
    AgentRoleplayScenario("机场值机", "checking in for a flight at the airport", "值机、托运、选座"),
    AgentRoleplayScenario("餐厅订位", "booking a table at a restaurant by phone", "电话订位、改时间"),
    AgentRoleplayScenario("自由聊天", "friendly daily small talk", "日常寒暄、随便聊聊")
)

private val agentRoleplayAccents = listOf(AgentStudyBlue, AgentStudyTeal, AgentStudyAmber, AgentStudyViolet, AgentStudyRose)

@Composable
internal fun AgentRoleplayInputField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(AgentStudyBlue),
            decorationBox = { inner ->
                Box {
                    if (value.isBlank()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyMedium)
                    inner()
                }
            }
        )
    }
}

@Composable
internal fun AgentRoleplayBubble(message: RoleplayMessage, ttsLoading: Boolean, onReplay: () -> Unit) {
    val isUser = message.role == "user"
    if (isUser) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.62f)),
                modifier = Modifier
                    .widthIn(min = 44.dp, max = 300.dp)
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(18.dp),
                        clip = false,
                        ambientColor = Color.Black.copy(alpha = 0.035f),
                        spotColor = Color.Black.copy(alpha = 0.05f)
                    )
            ) {
                Text(
                    message.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 24.sp
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (ttsLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp, color = AgentStudyBlue)
                        Text("语音生成中…", style = MaterialTheme.typography.labelSmall, color = AgentStudyBlue)
                    }
                } else {
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onReplay() }.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "重听", tint = AgentStudyBlue, modifier = Modifier.size(16.dp))
                        Text("重听", style = MaterialTheme.typography.labelSmall, color = AgentStudyBlue)
                    }
                }
                if (message.hint.isNotBlank()) {
                    Text("提示：${message.hint}", style = MaterialTheme.typography.labelSmall, color = AgentStudyAmber)
                }
            }
        }
    }
}

@Composable
internal fun AgentRoleplayFeedbackReport(result: RoleplayFeedbackResult) {
    val overallColor = agentSpeakingScoreColor(result.overall)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(76.dp).clip(CircleShape).background(overallColor.copy(alpha = 0.12f)).border(2.dp, overallColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${result.overall}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = overallColor)
                    Text("总分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AgentSpeakingScoreBar("流利度", result.scores.fluency)
                AgentSpeakingScoreBar("语法", result.scores.grammar)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(modifier = Modifier.weight(1f)) { AgentSpeakingScoreBar("词汇", result.scores.vocabulary) }
            Column(modifier = Modifier.weight(1f)) { AgentSpeakingScoreBar("任务完成", result.scores.taskCompletion) }
        }
        if (result.highlights.isNotEmpty()) {
            AgentSpeakingReportSection("亮点", AgentPracticeSuccess) { result.highlights.forEach { AgentSpeakingBullet(it, AgentPracticeSuccess) } }
        }
        if (result.improvements.isNotEmpty()) {
            AgentSpeakingReportSection("可以更好", AgentStudyAmber) { result.improvements.forEach { AgentSpeakingBullet(it, AgentStudyAmber) } }
        }
        if (result.betterLines.isNotEmpty()) {
            AgentSpeakingReportSection("更地道的说法", AgentStudyViolet) {
                result.betterLines.forEach { line ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        if (line.from.isNotBlank()) Text(line.from, style = MaterialTheme.typography.bodySmall, color = AgentPracticeWrong, textDecoration = TextDecoration.LineThrough)
                        if (line.to.isNotBlank()) Text("→ ${line.to}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = AgentPracticeSuccess)
                    }
                }
            }
        }
        if (result.comment.isNotBlank()) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = AgentStudyRose.copy(alpha = 0.08f)) {
                Text(result.comment, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium, color = AgentStudyRose, fontWeight = FontWeight.Medium)
            }
        }
    }
}
