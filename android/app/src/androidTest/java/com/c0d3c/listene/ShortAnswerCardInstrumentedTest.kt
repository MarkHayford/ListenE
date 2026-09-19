package com.c0d3c.listene

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

/**
 * 确定性短答/默写交互测试：旧 AgentCardShortAnswerComponent 已随 cardSpec 协议退役，
 * 该流程现由 MicroNode.Input + 卡级「核对答案/重新作答」承担。
 * 覆盖 错答→核对→看正解→重新作答→改对 的完整状态机（重作会清空输入）。
 */
class ShortAnswerCardInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun microInputChecksWrongRetryAndCorrectAnswer() {
        val answer = "I have already booked the ticket."
        val card = MicroCard(
            title = "输入答案",
            nodes = listOf(
                MicroNode.Input(prompt = "用英文写出：我已经订好票了。", answer = answer, multiline = false)
            )
        )
        composeRule.setContent { MaterialTheme { MicroCardView(card, instanceKey = "it_short_answer") } }

        // 错答 -> 核对 -> 揭示参考答案
        composeRule.onNode(hasSetTextAction()).performTextInput("I have already changed the ticket.")
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("参考答案").assertIsDisplayed()
        composeRule.onNodeWithText(answer).assertIsDisplayed()

        // 重新作答（输入清空）-> 改对（大小写/末尾标点归一化）-> 「正确」
        composeRule.onNodeWithText("重新作答").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("i have already booked the ticket")
        composeRule.onNodeWithText("核对答案").performClick()
        composeRule.onNodeWithText("正确").assertIsDisplayed()
        composeRule.onNodeWithText(answer).assertIsDisplayed()
    }
}
