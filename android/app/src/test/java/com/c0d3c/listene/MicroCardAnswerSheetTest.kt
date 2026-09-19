package com.c0d3c.listene

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微元卡「作答表」单测：核对答案时把逐题（题面/用户作答/正解/对错）落成 JSON 挂到消息上，
 * 是「AI 分析练习卡」的数据来源；另覆盖分析目标选择与回复文本格式化。
 */
class MicroCardAnswerSheetTest {
    @Test
    fun userAnswerTextCoversSelectionAndFillTypes() {
        val answers = MicroAnswerState()
        val choice = MicroNode.Choice(prompt = "p", options = listOf("go", "went"), answer = "went", multi = false)
        answers.choiceSelection[0] = 1
        assertEquals("went", microNodeUserAnswerText(choice, 0, answers))

        val clozeSelect = MicroNode.ClozeSelect(
            text = "I ___ to school ___ .",
            blanks = listOf(
                MicroClozeSelectBlank(options = listOf("go", "went"), answer = "went"),
                MicroClozeSelectBlank(options = listOf("yesterday", "tomorrow"), answer = "yesterday")
            )
        )
        answers.clozeSelectChoice[1] = mapOf(0 to 0, 1 to 1)
        assertEquals("go | tomorrow", microNodeUserAnswerText(clozeSelect, 1, answers))

        val openCloze = MicroNode.OpenCloze(prompt = "", text = "I ___ home ___ .", answers = listOf("go", "now"))
        answers.openClozeFill[2] = mapOf(0 to "went", 1 to "now")
        assertEquals("went | now", microNodeUserAnswerText(openCloze, 2, answers))

        val match = MicroNode.Match(prompt = "", pairs = listOf(MicroMatchPair("cat", "猫"), MicroMatchPair("dog", "狗")))
        answers.matchSelection[3] = mapOf(0 to "狗", 1 to "猫")
        assertEquals("cat → 狗；dog → 猫", microNodeUserAnswerText(match, 3, answers))

        val trueFalse = MicroNode.TrueFalse(
            prompt = "",
            statements = listOf(MicroTrueFalseItem("Sky is blue.", true), MicroTrueFalseItem("Fish fly.", false))
        )
        answers.trueFalseSelection[4] = mapOf(0 to true, 1 to true)
        assertEquals("Sky is blue.=对；Fish fly.=对", microNodeUserAnswerText(trueFalse, 4, answers))

        val proof = MicroNode.ProofParagraph(
            prompt = "",
            lines = listOf(
                MicroProofLine(text = "She go home.", answer = "She goes home.", note = ""),
                MicroProofLine(text = "I am fine.", answer = "I am fine.", note = "")
            )
        )
        answers.proofEdits[5] = mapOf(0 to "She goes home.", 1 to "I am fine.")
        assertEquals("She go home. → She goes home.", microNodeUserAnswerText(proof, 5, answers))

        // 未作答 → 空串
        assertEquals("", microNodeUserAnswerText(choice, 9, answers))
    }

    @Test
    fun answerSheetJsonCarriesPerQuestionFacts() {
        val nodes = listOf<MicroNode>(
            MicroNode.Text(text = "标题", role = "title"),
            MicroNode.Choice(prompt = "选一个", options = listOf("a", "b"), answer = "b", multi = false, explanation = "解析B"),
            MicroNode.OpenCloze(prompt = "", text = "I ___ home.", answers = listOf("go"))
        )
        val card = MicroCard(title = "混合卡", nodes = nodes)
        val answers = MicroAnswerState()
        answers.choiceSelection[1] = 0 // 答错
        answers.openClozeFill[2] = mapOf(0 to "go") // 答对
        val gradable = nodes.withIndex().filter { it.value !is MicroNode.Text }
        val sheet = microCardAnswerSheetJson(card, gradable, answers) { index, _ -> index == 2 }
        assertNotNull(sheet)
        requireNotNull(sheet)
        assertEquals("混合卡", sheet["title"]!!.jsonPrimitive.content)
        assertEquals(2, sheet["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, sheet["correct"]!!.jsonPrimitive.content.toInt())
        val items = sheet["items"]!!.jsonArray
        assertEquals(2, items.size)
        val first = items[0].jsonObject
        assertEquals("choice", first["type"]!!.jsonPrimitive.content)
        assertEquals("a", first["userAnswer"]!!.jsonPrimitive.content)
        assertEquals("b", first["correctAnswer"]!!.jsonPrimitive.content)
        assertFalse(first["correct"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("解析B", first["explanation"]!!.jsonPrimitive.content)
        val second = items[1].jsonObject
        assertEquals("open_cloze", second["type"]!!.jsonPrimitive.content)
        assertTrue(second["correct"]!!.jsonPrimitive.content.toBoolean())

        // 没有可判分项 → null（不挂作答表）
        val displayOnly = MicroCard(title = "展示", nodes = listOf(MicroNode.Text(text = "t", role = "")))
        assertNull(microCardAnswerSheetJson(displayOnly, emptyList(), MicroAnswerState()) { _, _ -> true })
    }

    @Test
    fun analysisTargetPrefersLatestArtifact() {
        val micro = AgentChatMessage(id = 200, role = AgentChatRole.Agent, text = "", microCardJson = "{}", microAnswersJson = "{\"total\":1}")
        val anchorOld = AgentChatMessage(id = 100, role = AgentChatRole.Agent, text = "素材", listeningRecordId = "r1")
        val anchorNew = AgentChatMessage(id = 300, role = AgentChatRole.Agent, text = "素材", listeningRecordId = "r2")

        // 没有已核对的练习卡 → 不选微卡分析
        assertFalse(agentPreferMicroAnalysis(listOf(anchorOld), hasListeningRecord = true))
        // 有练习卡、无听力记录 → 微卡分析
        assertTrue(agentPreferMicroAnalysis(listOf(micro), hasListeningRecord = false))
        // 练习卡比听力锚点新 → 微卡分析
        assertTrue(agentPreferMicroAnalysis(listOf(anchorOld, micro), hasListeningRecord = true))
        // 听力锚点比练习卡新 → 听力分析
        assertFalse(agentPreferMicroAnalysis(listOf(micro, anchorNew), hasListeningRecord = true))
        // 有听力记录但消息里没有锚点 → 就近微卡
        assertTrue(agentPreferMicroAnalysis(listOf(micro), hasListeningRecord = true))
        // 未核对的微卡（无作答表）不算
        val ungraded = AgentChatMessage(id = 400, role = AgentChatRole.Agent, text = "", microCardJson = "{}")
        assertNull(agentLatestGradedMicroMessage(listOf(ungraded)))
    }

    @Test
    fun microAnalysisReplyTextFormatsScoreAndInsights() {
        val result = AnalysisResult(
            summary = "整体不错，时态处需要巩固。",
            weakPoints = listOf("一般过去时"),
            suggestions = listOf("再练一组时态填空"),
            wrongQuestionInsights = listOf(
                WrongQuestionInsight(
                    questionIndex = 0,
                    question = "I ___ to school.",
                    selectedAnswer = "go",
                    correctAnswer = "went",
                    mistakeType = "时态",
                    insight = "yesterday 提示一般过去时。"
                )
            )
        )
        val text = agentMicroAnalysisReplyText(result, total = 3, correct = 2)
        assertTrue(text.contains("练习卡分析完成：整体不错，时态处需要巩固。"))
        assertTrue(text.contains("成绩：答对 2 / 3"))
        assertTrue(text.contains("I ___ to school."))
        assertTrue(text.contains("时态：yesterday 提示一般过去时。"))
        assertTrue(text.contains("薄弱点：一般过去时"))
        assertTrue(text.contains("下一步建议：再练一组时态填空"))
        // 作答表成绩解析
        assertEquals(3 to 2, agentMicroSheetScore("{\"total\":3,\"correct\":2}"))
        assertEquals(0 to 0, agentMicroSheetScore("not json"))
    }
}
