package com.c0d3c.listene

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

// 微元卡实验屏（隔离、可独立挂载）：输入需求 → 后端 AI 实时拼微元 → MicroCardView 渲染 + 判分。
// 不依赖、不修改现有 AgentChat / 题型管线；要上线只需在任意入口（Drawer 项 / 调试页）调用本 Composable。
@Composable
internal fun MicroCardLabScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var input by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<MicroCardResult?>(null) }
    // 自适应难度读数：随作答自动调整（LearnerModelStore 本地 Elo/IRT）。result 变化触发重算。
    val adaptiveLevel = remember(result) { LearnerModelStore.overallTargetLevel(ctx) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("微元卡实验（AI 实时组合）", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "自适应难度：当前综合水平约 ${LearnerModelStore.cefr(LearnerModelStore.overallAbility(ctx))} · 出题按 $adaptiveLevel 左右（随作答自动调整）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AgentCardInlineNotice("输入需求（如“出一道过去时的句子改错”“给我一段听力完形”），AI 用微元实时拼卡，无固定题型。")
        AgentTextAction(
            text = if (AgentMicroGenerateFlag.enabled) "练习生成：走微元（已开，点击关）" else "练习生成：走固定题型（点击开，练习请求改走 /agent/micro 实时拼卡）",
            onClick = { AgentMicroGenerateFlag.enabled = !AgentMicroGenerateFlag.enabled },
            modifier = Modifier.fillMaxWidth()
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            if (input.isBlank()) {
                Text("描述你想要的练习…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        AgentTextAction(
            text = if (loading) "生成中…" else "生成微元卡",
            onClick = {
                if (!loading && input.isNotBlank()) {
                    loading = true
                    result = null
                    scope.launch {
                        result = MicroCardStore.generate(input, adaptiveLevel)
                        loading = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            primary = true,
            enabled = !loading && input.isNotBlank()
        )
        if (loading) AgentThinkingText("AI 正在拼卡…")
        result?.let { r ->
            if (r.error.isNotBlank()) AgentCardInlineNotice("出错：${r.error}")
            r.card?.let { card -> MicroCardView(card, instanceKey = "micro_lab_card") }
            if (!r.ok && r.report.isNotBlank()) AgentCardInlineNotice("校验：\n${r.report}")
        }
    }
}
