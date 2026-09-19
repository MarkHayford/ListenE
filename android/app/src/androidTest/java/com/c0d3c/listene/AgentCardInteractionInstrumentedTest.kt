package com.c0d3c.listene

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

/**
 * 确定性卡片交互测试：直接用固定 spec 渲染卡片组件并交互，不依赖实时 MiMo。
 * 覆盖 SentenceBuilder / SpeakingPrompt 的完整交互（取代原先非确定性、常 90s 超时的
 * 实时 MiMo E2E——那两个独立测试文件已删除，其全部覆盖合并到这里）。
 *
 * 组件外包一层可滚动 Column，使 performScrollTo 生效、且高卡片可触达。
 */
class AgentCardInteractionInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun renderScrollable(content: @Composable () -> Unit) {
        composeRule.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    content()
                }
            }
        }
    }

    @Test
    fun speakingPromptManualInputCompletesAndRetries() {
        renderScrollable {
            AgentCardSpeakingPromptComponent(
                component = AgentCardComponentSpec(
                    type = AgentCardComponent.SpeakingPrompt,
                    title = "口语练习",
                    text = "Tell me about a challenge you overcame at work."
                )
            )
        }

        // 手动输入框默认展开（feat 4e226593：口语卡手动输入默认展开），无需再点击「或手动输入文字答案」
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasTestTag("agent_speaking_response_input"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // 太短：完成按钮禁用
        composeRule.onNodeWithTag("agent_speaking_response_input", useUnmergedTree = true).performTextInput("Too short.")
        composeRule.onNodeWithTag("agent_speaking_response_action", useUnmergedTree = true)
            .performScrollTo().assertIsNotEnabled()

        // 足够长：启用 -> 完成
        composeRule.onNodeWithTag("agent_speaking_response_input", useUnmergedTree = true).performTextClearance()
        composeRule.onNodeWithTag("agent_speaking_response_input", useUnmergedTree = true)
            .performTextInput(
                "I would introduce myself briefly, explain one project I finished, " +
                    "and then describe how I solved a difficult problem with my team."
            )
        composeRule.onNodeWithTag("agent_speaking_response_action", useUnmergedTree = true)
            .performScrollTo().assertIsEnabled().performClick()
        composeRule.onNode(
            hasTestTag("agent_practice_feedback_status") and hasText("已完成口语练习", substring = true),
            useUnmergedTree = true
        ).performScrollTo().assertIsDisplayed()

        // 重做：再次点击完成按钮回到可编辑，清空重填更长文本后再次完成（覆盖完成→重做→再完成）
        composeRule.onNodeWithTag("agent_speaking_response_action", useUnmergedTree = true)
            .performScrollTo().performClick()
        composeRule.onNodeWithTag("agent_speaking_response_input", useUnmergedTree = true)
            .performScrollTo().performTextClearance()
        composeRule.onNodeWithTag("agent_speaking_response_input", useUnmergedTree = true)
            .performTextInput(
                "I would introduce myself briefly, explain one project I finished, " +
                    "and then describe how I solved a difficult problem with my team. " +
                    "I would close with one clear reason why I fit the role."
            )
        composeRule.onNodeWithTag("agent_speaking_response_action", useUnmergedTree = true)
            .performScrollTo().assertIsEnabled().performClick()
        composeRule.onNode(
            hasTestTag("agent_practice_feedback_status") and hasText("已完成口语练习", substring = true),
            useUnmergedTree = true
        ).performScrollTo().assertIsDisplayed()
    }

}
