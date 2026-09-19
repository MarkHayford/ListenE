package com.c0d3c.listene

import android.content.Context
import android.os.Environment
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File

class ChatQuestionBankExportInstrumentedTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(SeedChatCardRule())
        .around(composeRule)

    @Test
    fun chatExportRequestExportsLatestQuestionBankCardToFileLibraryAndDownloads() {
        sendChat("导出刚才那套题库 docx")

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodes(
                hasTestTag(agentAttachmentFileTestTag(EXPECTED_EXPORT_NAME)),
                useUnmergedTree = true
            ).fetchSemanticsNodes().isNotEmpty()
        }
        waitForExportedFile()

        val exported = requireNotNull(exportedQuestionBankFile())
        val data = exported.objOrNull("data")!!
        val localFile = File(data.str("localPath"))

        assertEquals("chat_question_bank", data.str("sourceKind"))
        assertEquals("Travel Chat Practice", data.str("sourceCardTitle"))
        assertEquals(SEED_CARD_MESSAGE_ID, data.long("sourceMessageId"))
        assertEquals(SEEDED_WORKSPACE_ID, data.str("workspaceId"))
        assertTrue(data.str("fileName").endsWith("_question_bank.docx"))
        assertTrue("local docx should exist", localFile.exists() && localFile.length() > 0L)
        assertTrue("download copy should exist", downloadCopy(data.str("fileName")).exists())
    }

    private fun sendChat(text: String) {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodes(hasTestTag("agent_chat_input") and isEnabled(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNode(hasTestTag("agent_chat_input") and isEnabled(), useUnmergedTree = true)
            .performTextClearance()
        composeRule.onNode(hasTestTag("agent_chat_input") and isEnabled(), useUnmergedTree = true)
            .performTextInput(text)
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("agent_chat_send") and isEnabled(), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("agent_chat_send", useUnmergedTree = true)
            .performTouchInput { click(center) }
    }

    private fun waitForExportedFile() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            exportedQuestionBankFile()?.let { item ->
                val data = item.objOrNull("data") ?: buildJsonObject {  }
                data.str("localPath").let { it.isNotBlank() && File(it).exists() } &&
                    downloadCopy(data.str("fileName")).exists()
            } == true
        }
    }

    private fun exportedQuestionBankFile(): JsonObject? {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val raw = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, USER_LIBRARY_PREFS), Context.MODE_PRIVATE)
            .getString("kind_files", "[]") ?: "[]"
        val arr = parseJsonArrayOrNull(raw) ?: JsonArray(emptyList())
        return List(arr.size) { index -> arr.objOrNull(index) ?: buildJsonObject {  } }
            .firstOrNull { item ->
                val data = item.objOrNull("data") ?: buildJsonObject {  }
                data.str("sourceKind") == "chat_question_bank" &&
                    data.long("sourceMessageId") == SEED_CARD_MESSAGE_ID
            }
    }

    private fun downloadCopy(fileName: String): File =
        File(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ListenE"),
            fileName
        )

    private class SeedChatCardRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val ctx = ApplicationProvider.getApplicationContext<Context>()
                    AuthStore.clearSession(ctx)
                    runBlocking {
                        runCatching {
                            AuthStore.register(
                                ctx = ctx,
                                email = "chat-export-ui-${System.currentTimeMillis()}@listene.test",
                                password = "ChatExportUi123!",
                                displayName = "Chat Export UI"
                            )
                        }.recoverCatching {
                            AuthStore.login(
                                ctx = ctx,
                                email = "chat-export-ui-fallback@listene.test",
                                password = "ChatExportUi123!"
                            )
                        }.getOrThrow()
                    }
                    // 登录后再清理/种子：本地存储已按账号隔离（scopedPrefsName），必须写当前账号的作用域 prefs。
                    clearLocalState(ctx)
                    seedWorkspace(ctx)
                    seedChat(ctx)
                    base.evaluate()
                }
            }

        private fun clearLocalState(ctx: Context) {
            listOf(
                "learning_workspace_store",
                "agent_chat_messages_v1",
                USER_LIBRARY_PREFS,
                "agent_auto_followup_store_v2"
            ).forEach { prefs ->
                ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, prefs), Context.MODE_PRIVATE)
                    .edit().clear().commit()
            }
            // 清空历史导出物：uniqueAgentOutputFile 遇到同名会追加 -N 后缀，会让导出文件名不再等于
            // EXPECTED_EXPORT_NAME，导致等待附件与断言反复失败。清掉后导出名才是确定的。
            File(ctx.filesDir, "agent_outputs").listFiles()?.forEach { it.delete() }
        }

        private fun seedWorkspace(ctx: Context) {
            val now = System.currentTimeMillis()
            WorkspaceStore.saveWorkspaces(
                ctx,
                listOf(
                    LearningWorkspace(
                        id = SEEDED_WORKSPACE_ID,
                        title = "Chat Export Workspace",
                        need = "Practice chat question bank export",
                        summary = "Seeded workspace for chat export",
                        currentStep = "chat",
                        createdAt = now,
                        updatedAt = now,
                        plan = WorkspacePlan(
                            title = "Chat Export Workspace",
                            summary = "Seeded workspace for chat export",
                            contentType = "chat",
                            materialPrompt = "",
                            difficulty = "normal",
                            speechRate = "medium",
                            voiceGender = "female",
                            voiceProfile = "Female adult English voice.",
                            steps = listOf(WorkspaceStep("chat", "Chat", "Seeded chat workspace.", "running"))
                        )
                    )
                )
            )
        }

        // cardSpec 已退役（退役 #4：存量旧卡读档即丢）：种子用微元卡（microCard），与线上聊天练习卡形态一致。
        private fun seedChat(ctx: Context) {
            val messages = buildJsonArray { add(buildJsonObject { put("id", 10L); put("role", "agent"); put("text", "Here is background material."); put("microCard", materialOnlyMicroJson()) }); add(buildJsonObject { put("id", SEED_CARD_MESSAGE_ID); put("role", "agent"); put("text", "Here is the current question bank."); put("microCard", questionBankMicroJson()) }) }
            ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, "agent_chat_messages_v1"), Context.MODE_PRIVATE)
                .edit()
                .putString("workspace_$SEEDED_WORKSPACE_ID", messages.toString())
                .commit()
        }

        private fun materialOnlyMicroJson(): JsonObject =
            buildJsonObject { put("title", "Material Only"); put("nodes", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "Only background material.") }) }) }

        private fun questionBankMicroJson(): JsonObject =
            buildJsonObject { put("title", "Travel Chat Practice"); put("nodes", buildJsonArray { add(buildJsonObject { put("type", "choice"); put("prompt", "Which sentence asks for a ticket?"); put("options", buildJsonArray { (listOf("I need a ticket.", "I need a table.")).forEach { add(it) } }); put("answer", "I need a ticket."); put("explanation", "Ticket is the travel word.") }) }) }
    }

    companion object {
        private const val USER_LIBRARY_PREFS = "agent_user_library_local_v1"
        private const val SEEDED_WORKSPACE_ID = "ws_chat_export_ui"
        private const val SEED_CARD_MESSAGE_ID = 22L
        private const val EXPECTED_EXPORT_NAME = "Travel_Chat_Practice_question_bank.docx"
    }
}
