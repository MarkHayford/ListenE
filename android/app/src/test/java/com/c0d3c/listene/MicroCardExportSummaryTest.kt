package com.c0d3c.listene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 微元卡 → 历史可导出摘要：cardSpec 退役后，「导出刚才的题目」历史路径靠它看到微元卡内容。
class MicroCardExportSummaryTest {

    @Test
    fun summaryCoversQuestionsOptionsAnswers() {
        val card = MicroCard(
            title = "一般过去时小测",
            nodes = listOf(
                MicroNode.Text("先读题再作答", role = "title"),
                MicroNode.Passage("Yesterday Tom visited his grandmother."),
                MicroNode.Choice(
                    prompt = "Which sentence is in past simple?",
                    options = listOf("He goes home.", "He went home."),
                    answer = "He went home.",
                    multi = false,
                    explanation = "went 是过去式"
                ),
                MicroNode.OpenCloze(prompt = "", text = "She ___ to school yesterday.", answers = listOf("went")),
                MicroNode.Match(
                    prompt = "配对",
                    pairs = listOf(MicroMatchPair("apple", "苹果"), MicroMatchPair("cat", "猫"))
                ),
                MicroNode.ProofParagraph(
                    prompt = "短文改错",
                    lines = listOf(
                        MicroProofLine("She go home.", "She goes home.", ""),
                        MicroProofLine("I am fine.", "I am fine.", "")
                    )
                )
            )
        )
        val summary = microCardExportableSummary(card)
        assertTrue(summary.startsWith("Agent card: 一般过去时小测"))
        assertTrue(summary.contains("Yesterday Tom visited his grandmother."))
        assertTrue(summary.contains("1. Which sentence is in past simple?"))
        assertTrue(summary.contains("A. He goes home."))
        assertTrue(summary.contains("B. He went home."))
        assertTrue(summary.contains("Correct answer: He went home."))
        assertTrue(summary.contains("Explanation: went 是过去式"))
        assertTrue(summary.contains("2. She ___ to school yesterday."))
        assertTrue(summary.contains("Correct answer: went"))
        assertTrue(summary.contains("apple → 苹果"))
        // 短文改错只导出有错的行
        assertTrue(summary.contains("She go home. → She goes home."))
        assertTrue(!summary.contains("I am fine. → "))
    }

    @Test
    fun reviewInfoFallbackCoversTypedAnswerNodes() {
        val card = MicroCard(
            title = "",
            nodes = listOf(
                MicroNode.ErrorCorrection(
                    prompt = "",
                    sentence = "She go to school yesterday",
                    answer = "She went to school yesterday",
                    accept = emptyList(),
                    explanation = "过去时"
                ),
                MicroNode.Translate(
                    direction = "zh2en",
                    source = "我每天走路上学",
                    answer = "I walk to school every day.",
                    accept = emptyList(),
                    hint = ""
                )
            )
        )
        val summary = microCardExportableSummary(card)
        assertTrue(summary.startsWith("Agent card: ListenE 练习卡"))
        assertTrue(summary.contains("改错：She go to school yesterday"))
        assertTrue(summary.contains("Correct answer: She went to school yesterday"))
        assertTrue(summary.contains("翻译：我每天走路上学"))
        assertTrue(summary.contains("Correct answer: I walk to school every day."))
    }

    @Test
    fun materialOnlyCardYieldsEmptySummary() {
        val card = MicroCard(
            title = "素材卡",
            nodes = listOf(
                MicroNode.Progress(label = "3 / 5", value = 0.6f),
                MicroNode.Audio(src = "https://example.com/a.mp3")
            )
        )
        assertEquals("", microCardExportableSummary(card))
    }

    @Test
    fun parserRoundTripFromJson() {
        val json = """{"title":"填空","nodes":[{"type":"open_cloze","text":"I'm good ___ English.","answers":["at"]}]}"""
        val card = MicroCardParser.parse(json)
        requireNotNull(card)
        val summary = microCardExportableSummary(card)
        assertTrue(summary.contains("1. I'm good ___ English."))
        assertTrue(summary.contains("Correct answer: at"))
    }

    @Test
    fun microQuestionBankDocxCoversQuestionsAndMaterial() {
        val card = MicroCard(
            title = "Travel Chat Practice",
            nodes = listOf(
                MicroNode.Text("Only background material.", role = "body"),
                MicroNode.Choice(
                    prompt = "Which sentence asks for a ticket?",
                    options = listOf("I need a ticket.", "I need a table."),
                    answer = "I need a ticket.",
                    multi = false,
                    explanation = "Ticket is the travel word."
                )
            )
        )
        assertTrue(microCardCanExportQuestionBank(card))
        assertEquals("Travel_Chat_Practice_question_bank.docx", microCardQuestionBankExportName(card))
        val paragraphs = buildMicroCardQuestionBankDocxParagraphs(card)
        assertTrue(paragraphs.contains("卡片：Travel Chat Practice"))
        assertTrue(paragraphs.contains("【学习材料】"))
        assertTrue(paragraphs.contains("Only background material."))
        assertTrue(paragraphs.contains("1. Which sentence asks for a ticket?"))
        assertTrue(paragraphs.contains("A. I need a ticket."))
        assertTrue(paragraphs.contains("正确答案：I need a ticket."))
        assertTrue(paragraphs.contains("解析：Ticket is the travel word."))
    }

    @Test
    fun microQuestionBankSelectionPicksLatestExportableMicroMessage() {
        val exportable = """{"title":"新题库","nodes":[{"type":"choice","prompt":"Q?","options":["a","b"],"answer":"a"}]}"""
        val materialOnly = """{"title":"素材","nodes":[{"type":"text","text":"background"}]}"""
        val messages = listOf(
            AgentChatMessage(1L, AgentChatRole.Agent, "旧题", microCardJson = exportable),
            AgentChatMessage(2L, AgentChatRole.Agent, "素材", microCardJson = materialOnly),
            AgentChatMessage(3L, AgentChatRole.User, "导出"),
            AgentChatMessage(4L, AgentChatRole.Agent, "新题", microCardJson = exportable.replace("新题库", "最新题库"))
        )
        val selected = requireNotNull(latestMicroQuestionBankCardSelection(messages))
        assertEquals(4L, selected.messageId)
        assertEquals("最新题库", selected.card.title)
        // 素材卡（无可判分题目）不可导出。
        val none = latestMicroQuestionBankCardSelection(
            listOf(AgentChatMessage(9L, AgentChatRole.Agent, "素材", microCardJson = materialOnly))
        )
        assertEquals(null, none)
    }
}
