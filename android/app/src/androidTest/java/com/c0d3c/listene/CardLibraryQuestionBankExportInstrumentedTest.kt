package com.c0d3c.listene

import android.content.Context
import android.os.Environment
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

class CardLibraryQuestionBankExportInstrumentedTest {
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: TestRule = RuleChain
        .outerRule(SeedLibraryCardRule())
        .around(composeRule)

    @Test
    fun cardLibraryRowAndDetailExportsCurrentCardQuestionBankToFileLibrary() {
        openCardLibrary()

        composeRule.onNodeWithTag("agent_library_card_export_$ROW_CARD_ID", useUnmergedTree = true)
            .performTouchInput { click(center) }
        waitForExportedFile(ROW_CARD_ID)

        composeRule.onNodeWithTag("agent_library_card_row_$DETAIL_CARD_ID", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("agent_library_card_detail_export", useUnmergedTree = true)
            .assertIsDisplayed()
            .performTouchInput { click(center) }
        waitForExportedFile(DETAIL_CARD_ID)

        val files = exportedQuestionBankFiles()
        assertExportedFile(files, ROW_CARD_ID, "Row Export Practice")
        assertExportedFile(files, DETAIL_CARD_ID, "Detail Export Practice")
    }

    private fun openCardLibrary() {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodes(hasTestTag("agent_drawer_menu"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("agent_drawer_menu", useUnmergedTree = true)
            .performTouchInput { click(center) }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodes(hasTestTag("agent_library_cards_entry"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithTag("agent_library_cards_entry", useUnmergedTree = true)
            .performTouchInput { click(center) }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodes(hasTestTag("agent_library_card_row_$ROW_CARD_ID"), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                composeRule.onAllNodes(hasTestTag("agent_library_card_row_$DETAIL_CARD_ID"), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
    }

    private fun waitForExportedFile(cardId: String) {
        composeRule.waitUntil(timeoutMillis = 30_000) {
            exportedQuestionBankFiles().any { item ->
                val data = item.objOrNull("data") ?: buildJsonObject {  }
                data.str("sourceKind") == "card_question_bank" &&
                    data.str("sourceCardId") == cardId &&
                    data.str("localPath").let { it.isNotBlank() && File(it).exists() } &&
                    data.str("fileName").endsWith("_question_bank.docx")
            }
        }
    }

    private fun exportedQuestionBankFiles(): List<JsonObject> {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val raw = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, USER_LIBRARY_PREFS), Context.MODE_PRIVATE)
            .getString("kind_files", "[]") ?: "[]"
        val arr = parseJsonArrayOrNull(raw) ?: JsonArray(emptyList())
        return List(arr.size) { index -> arr.objOrNull(index) ?: buildJsonObject {  } }
            .filter { item ->
                (item.objOrNull("data") ?: buildJsonObject {  }).str("sourceKind") == "card_question_bank"
            }
    }

    private fun assertExportedFile(files: List<JsonObject>, cardId: String, expectedTitle: String) {
        val item = files.firstOrNull { file ->
            (file.objOrNull("data") ?: buildJsonObject {  }).str("sourceCardId") == cardId
        }
        assertNotNull("missing exported file for $cardId", item)
        val data = item!!.objOrNull("data") ?: buildJsonObject {  }
        val localFile = File(data.str("localPath"))

        assertEquals("card_question_bank", data.str("sourceKind"))
        assertEquals(cardId, data.str("sourceCardId"))
        assertEquals(expectedTitle, data.str("sourceCardTitle"))
        assertEquals(SEEDED_WORKSPACE_ID, data.str("workspaceId"))
        assertTrue(data.str("fileName").endsWith("_question_bank.docx"))
        assertTrue("local docx should exist", localFile.exists() && localFile.length() > 0L)
        assertTrue("download copy should exist", hasDownloadCopy(data.str("fileName")))
    }

    private fun hasDownloadCopy(fileName: String): Boolean {
        val downloadDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "ListenE"
        )
        return File(downloadDir, fileName).exists()
    }

    private class SeedLibraryCardRule : TestRule {
        override fun apply(base: Statement, description: Description): Statement =
            object : Statement() {
                override fun evaluate() {
                    val ctx = ApplicationProvider.getApplicationContext<Context>()
                    AuthStore.clearSession(ctx)
                    runBlocking {
                        runCatching {
                            AuthStore.register(
                                ctx = ctx,
                                email = "card-export-ui-${System.currentTimeMillis()}@listene.test",
                                password = "CardExportUi123!",
                                displayName = "Card Export UI"
                            )
                        }.recoverCatching {
                            AuthStore.login(
                                ctx = ctx,
                                email = "card-export-ui-fallback@listene.test",
                                password = "CardExportUi123!"
                            )
                        }.getOrThrow()
                    }
                    // 登录后再清理/种子：本地存储已按账号隔离（scopedPrefsName），必须写当前账号的作用域 prefs。
                    clearLocalState(ctx)
                    seedWorkspace(ctx)
                    seedCardLibrary(ctx)
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
            // 清空历史导出物：uniqueAgentOutputFile 遇到同名会追加 -N 后缀，会让导出文件名不再以
            // _question_bank.docx 结尾，导致断言反复失败。清掉后导出名才是确定的。
            File(ctx.filesDir, "agent_outputs").listFiles()?.forEach { it.delete() }
        }

        private fun seedWorkspace(ctx: Context) {
            val now = System.currentTimeMillis()
            val workspace = LearningWorkspace(
                id = SEEDED_WORKSPACE_ID,
                title = "Card Export Workspace",
                need = "Practice question bank export",
                summary = "Seeded workspace for card library export",
                currentStep = "chat",
                createdAt = now,
                updatedAt = now,
                plan = WorkspacePlan(
                    title = "Card Export Workspace",
                    summary = "Seeded workspace for card library export",
                    contentType = "chat",
                    materialPrompt = "",
                    difficulty = "normal",
                    speechRate = "medium",
                    voiceGender = "female",
                    voiceProfile = "Female adult English voice.",
                    steps = listOf(WorkspaceStep("chat", "Chat", "Seeded chat workspace.", "running"))
                )
            )
            WorkspaceStore.saveWorkspaces(ctx, listOf(workspace))
        }

        private fun seedCardLibrary(ctx: Context) {
            val now = System.currentTimeMillis()
            val cards = buildJsonArray { add(cardItem(ROW_CARD_ID, "Row Export Practice", now + 2)); add(cardItem(DETAIL_CARD_ID, "Detail Export Practice", now + 1)); add(materialOnlyCardItem(now)) }
            ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, USER_LIBRARY_PREFS), Context.MODE_PRIVATE)
                .edit()
                .putString("kind_cards", cards.toString())
                .commit()
        }

        // 种子一律用原生 microCard JSON：旧协议 card(cardSpec) 读档已退役，库只认 microCard。
        private fun cardItem(id: String, title: String, updatedAt: Long): JsonObject =
            buildJsonObject { put("id", id); put("kind", "cards"); put("title", title); put("summary", "Seeded exportable question card"); put("data", buildJsonObject { put("workspaceId", SEEDED_WORKSPACE_ID); put("clientId", "client_$id"); put("microCard", questionBankMicroCardJson(title)) }); put("createdAt", updatedAt); put("updatedAt", updatedAt) }

        private fun materialOnlyCardItem(updatedAt: Long): JsonObject =
            buildJsonObject { put("id", MATERIAL_CARD_ID); put("kind", "cards"); put("title", "Material Only Card"); put("summary", "Seeded non-exportable card"); put("data", buildJsonObject { put("workspaceId", SEEDED_WORKSPACE_ID); put("clientId", "client_$MATERIAL_CARD_ID"); put("microCard", buildJsonObject { put("title", "Material Only Card"); put("nodes", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "This card has material but no exportable questions.") }) }) }) }); put("createdAt", updatedAt); put("updatedAt", updatedAt) }

        private fun questionBankMicroCardJson(title: String): JsonObject =
            buildJsonObject { put("title", title); put("nodes", buildJsonArray { add(buildJsonObject { put("type", "choice"); put("prompt", "Which sentence is correct?"); put("options", buildJsonArray { (listOf("I am ready.", "I ready.")).forEach { add(it) } }); put("answer", "I am ready."); put("explanation", "Use be before ready.") }); add(buildJsonObject { put("type", "choice"); put("prompt", "Choose the polite request."); put("options", buildJsonArray { (listOf("Give me it.", "Could I have it?")).forEach { add(it) } }); put("answer", "Could I have it?"); put("explanation", "Could I have it is polite.") }) }) }
    }

    companion object {
        private const val USER_LIBRARY_PREFS = "agent_user_library_local_v1"
        private const val SEEDED_WORKSPACE_ID = "ws_card_export_ui"
        private const val ROW_CARD_ID = "card_row_export_ui"
        private const val DETAIL_CARD_ID = "card_detail_export_ui"
        private const val MATERIAL_CARD_ID = "card_material_only_ui"
    }
}
