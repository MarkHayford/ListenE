package com.c0d3c.listene

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class WritingDraftCardInstrumentedTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(SeedAuthSessionRule())
        .around(composeRule)

    // 本地固定题型引擎退役后，写作练习由服务端微元引擎出 writing 微元
    // （MicroWritingView：agent_chart_writing_input 草稿框 + agent_chart_writing_grade AI 四维评分）。
    @Test
    fun chineseWritingPracticeCreatesEditableDraftBoxAndEvaluationFlow() {
        val prompt =
            "B1 \u5199\u4f5c\u7ec3\u4e60\u5361\uff1a\u8bf7\u8ba9\u6211\u5199\u4e00\u5c01 80-100 \u8bcd\u82f1\u6587\u90ae\u4ef6\u7ed9\u670b\u53cb\uff0c" +
                "\u56e0\u4e3a\u4e0d\u80fd\u53c2\u52a0\u5468\u672b\u8ba1\u5212\u800c\u9053\u6b49\uff0c\u8bf4\u660e\u539f\u56e0\uff0c" +
                "\u63d0\u51fa\u8865\u6551\u5b89\u6392\uff0c\u5e76\u793c\u8c8c\u7ed3\u5c3e\u3002" +
                "\u53ea\u8981\u81ea\u7531\u5199\u4f5c\u7ec3\u4e60\uff0c\u5fc5\u987b\u80fd\u8f93\u5165\u6b63\u6587\u5e76\u7531 AI \u6279\u6539\u3002"
        val draft =
            "Dear Anna, I am really sorry that I cannot join our weekend plan. My aunt is visiting suddenly, and my parents need me to help prepare dinner and take care of my little cousin. I know we planned this trip carefully, so I feel bad about changing it. Could we meet next Saturday instead? I can book the tickets and choose a cafe near the station. Thank you for understanding, and I hope we can still have a great day together. Best, Mia"

        // 首条 bootstrap 消息必须是明确的纯聊天：练习类首条会被后端判成 practice_card 并串行等微元生成。
        sendChat("你好")
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodes(hasText("你好", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        sendChat(prompt)

        waitUntilOrDump("micro writing box", timeoutMillis = 120_000) {
            composeRule.onAllNodes(hasTestTag("agent_chart_writing_input"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                composeRule.onAllNodes(hasTestTag("agent_chart_writing_grade"), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }

        composeRule.onNodeWithTag("agent_chart_writing_input", useUnmergedTree = true)
            .performScrollTo()
            .performTextInput(draft)
        composeRule.onNodeWithTag("agent_chart_writing_grade", useUnmergedTree = true)
            .performScrollTo()
            .performTouchInput { click(center) }

        // AI 四维批改（活体模型调用）：总分圆环 + 词汇/语法评分条出现即为评分完成。
        waitUntilOrDump("writing report", timeoutMillis = 120_000) {
            composeRule.onAllNodes(hasText("\u603b\u5206", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("\u8bcd\u6c47", substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // 清空草稿 → 词数归零（精确匹配“0 词”，避免撞上题干/占位里的“…词”）。
        composeRule.onNode(hasText("\u6e05\u7a7a"), useUnmergedTree = true)
            .performScrollTo()
            .performTouchInput { click(center) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("0 \u8bcd"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitUntilOrDump(label: String, timeoutMillis: Long, condition: () -> Boolean) {
        try {
            composeRule.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (error: Throwable) {
            composeRule.onRoot(useUnmergedTree = true).printToLog("WritingDraft:$label")
            throw error
        }
    }

    private fun sendChat(text: String) {
        composeRule.waitUntil(timeoutMillis = 60_000) {
            composeRule.onAllNodes(hasTestTag("agent_chat_input") and isEnabled(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // 必须等上一条回复完全收尾（「停止生成」按钮消失）再打字/点发送，否则两处竞态：
        // 1) 发送/停止是同一颗按钮（生成中点击=停止），会把上一条回复掐掉、本条被 loading 守卫丢弃；
        // 2) 回复流程中建工作区的 updateChatInput("") 副作用会把刚打进输入框的文字清空，
        //    按钮永远回不到「发送」语义。两者正是本套件此前 90s 超时的根因。
        composeRule.waitUntil(timeoutMillis = 120_000) {
            composeRule.onAllNodes(hasContentDescription("停止生成"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        composeRule.onNode(hasTestTag("agent_chat_input") and isEnabled(), useUnmergedTree = true).performTextClearance()
        composeRule.onNode(hasTestTag("agent_chat_input") and isEnabled(), useUnmergedTree = true).performTextInput(text)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasContentDescription("发送"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNode(hasTestTag("agent_chat_send"), useUnmergedTree = true)
            .performTouchInput { click(center) }
    }

    private class SeedAuthSessionRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val ctx = ApplicationProvider.getApplicationContext<Context>()
                    val email = "writing-ui-${System.currentTimeMillis()}@listene.test"
                    AuthStore.clearSession(ctx)
                    runBlocking {
                        runCatching {
                            AuthStore.register(
                                ctx = ctx,
                                email = email,
                                password = "WritingUi123!",
                                displayName = "Writing UI"
                            )
                        }.recoverCatching {
                            AuthStore.login(
                                ctx = ctx,
                                email = email,
                                password = "WritingUi123!"
                            )
                        }
                    }.getOrThrow()
                    listOf(
                        "learning_workspace_store",
                        "agent_chat_messages_v1",
                        "agent_user_library_local_v1"
                    ).forEach { prefs ->
                        ctx.getSharedPreferences(prefs, Context.MODE_PRIVATE).edit().clear().commit()
                    }
                    base.evaluate()
                }
            }
    }
}
