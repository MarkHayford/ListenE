package com.c0d3c.listene

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 方案 A 在模拟器(test_api36)上的功能验收 —— 把原本以为“只能人工真机”的验收尽量自动化。
 * 自动化能覆盖：图表作文微元的 AI 评分“出分”(活体 client→prod)、听力微元的渲染+作答→核对→反馈→AI复盘→原文 全流程。
 * 仍需人耳/人眼的残余：音频实际“出声”是否悦耳、视觉观感，自动化无法判断。
 */
class MicroAcceptanceInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    // A·图表作文微元：活体 AI 四维作文评分确实“出分”（debug 包 10.0.2.2:8001 → 宿主 prod /agent/writing）。
    @Test
    fun chartWritingMicroLiveAiGradingReturnsScore() {
        val reference = "Chart: Annual Sales\nType: bar\n2019: 10\n2020: 20\n2021: 30"
        val essay = "The chart illustrates that annual sales rose steadily from 10 units in 2019 to 30 units in 2021. " +
            "Overall, there was a clear upward trend across the three years, with the figure first doubling and then tripling."
        val result = runBlocking {
            AgentConversationService.assessWriting("Describe the chart in about 150 words.", reference, essay)
        }
        assertTrue("AI 评分 overall 应在 0..100，实际=${result.overall}", result.overall in 0..100)
        assertTrue("应统计作文词数，实际=${result.wordCount}", result.wordCount > 0)
    }

    // A·听力微元：音频控件 + 题目渲染，作答→核对→反馈(答对x/n)→AI复盘→查看原文 全流程在模拟器可走通。
    @Test
    fun listeningMicroCardRendersAudioAndRunsFullCheckFlow() {
        val record = HistoryRecord(
            id = "it_listen_micro_1",
            scene = "对话",
            createdAt = 0L,
            content = ListeningContent(
                title = "Cafe Order",
                script = "A: Can I have a coffee? B: Sure, anything else?",
                questions = listOf(
                    Question(
                        questionText = "What does A order?",
                        options = listOf("Tea", "Coffee"),
                        correctAnswer = 1,
                        explanation = "A asks for a coffee."
                    )
                ),
                audioUrl = "https://example.com/cafe.mp3"
            )
        )
        val viewModel = ListeningViewModel()
        composeRule.setContent {
            MaterialTheme {
                ListeningMicroCardView(
                    record = record,
                    instanceKey = "it_listen_micro",
                    viewModel = viewModel,
                    onRequestAiReview = {}
                )
            }
        }
        composeRule.onNodeWithText("Cafe Order").assertIsDisplayed()
        // 音频控件已渲染（真"出声"需人耳，渲染/可交互可自动验证）。
        composeRule.onNodeWithContentDescription("播放音频").assertIsDisplayed()
        composeRule.onNodeWithText("1. What does A order?").assertIsDisplayed()
        composeRule.onNodeWithText("Coffee").performClick()
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("答对 1 / 1").assertIsDisplayed()
        composeRule.onNodeWithText("AI 复盘").assertIsDisplayed()
        composeRule.onNodeWithText("查看原文").performClick()
        composeRule.onNodeWithText("A: Can I have a coffee? B: Sure, anything else?").assertIsDisplayed()
    }
}
