package com.c0d3c.listene

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 真机渲染验证：拍照答疑的结构化解题视图能渲染读题/解析/答案/生词，
 * 且「加入错题本」「加入生词本」回调可触发。
 */
class AgentSolveInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun solveResultRendersAndFiresCallbacks() {
        val result = SolveResult(
            questionText = "She ___ to school every day.",
            questionType = "语法单选",
            skill = "语法",
            points = listOf("第三人称单数"),
            steps = listOf("主语是第三人称单数", "动词 go 变 goes"),
            answer = "goes",
            pitfalls = listOf("别用原形 go"),
            vocab = listOf(SolveVocab("school", "/skuːl/", "学校", "I go to school.")),
            comment = "做得好"
        )
        var addedReview = false
        var addedVocab = ""
        composeRule.setContent {
            MaterialTheme {
                AgentSolveResultView(
                    result = result,
                    addedToReview = false,
                    addedVocabWords = emptySet(),
                    onAddToReview = { addedReview = true },
                    onAddVocab = { addedVocab = it.word }
                )
            }
        }
        composeRule.onNodeWithTag("agent_solve_result").assertIsDisplayed()
        composeRule.onNodeWithText("goes").assertIsDisplayed()
        composeRule.onNodeWithText("分步解析").assertIsDisplayed()
        composeRule.onNodeWithText("school").assertIsDisplayed()

        composeRule.onNodeWithTag("agent_solve_add_review").performClick()
        assertTrue("点「加入错题本」应触发回调", addedReview)
    }
}
