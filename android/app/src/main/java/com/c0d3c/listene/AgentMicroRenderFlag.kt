package com.c0d3c.listene

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// 练习卡“生成”走微元的独立开关：开启后，识别为练习卡(practice_card)的请求改走
// POST /agent/micro（AI 实时拼微元，无固定题型），结果直接以 MicroCardView 渲染在聊天里；
// 生成失败自动回退到原 /agent/chat 固定题型卡（鲁棒兜底）。
// 默认开：练习生成默认走微元（无固定题型）。仪器测试在 ListeneTestRunner 里钉死为关，
// 以保持既有“固定题型生成+渲染”UI 测试确定性；旧固定题型路径仍作为兜底/可回退保留。
internal object AgentMicroGenerateFlag {
    var enabled by mutableStateOf(true)
}
