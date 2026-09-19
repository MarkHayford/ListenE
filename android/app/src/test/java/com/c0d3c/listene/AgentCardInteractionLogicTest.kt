package com.c0d3c.listene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCardInteractionLogicTest {
    @Test
    fun answeringMaterialPreviewKeepsCurrentPageStable() {
        assertEquals(
            1,
            agentQuestionIndexAfterAnswer(
                currentIndex = 1,
                questionCount = 3,
                autoAdvance = false
            )
        )
    }



    @Test
    fun correctionPracticePrefersStructuredPairsOverItems() {
        val pairs = agentCorrectionPairs(
            listOf(AgentCardPair("He don't know.", "He doesn't know.", "third-person singular")),
            listOf("ignored -> ignored")
        )
        assertEquals(1, pairs.size)
        assertEquals("He don't know.", pairs[0].left)
        assertEquals("third-person singular", pairs[0].hint)
    }


    @Test
    fun buildCorrectionLineDerivesErrorTokensAndCorrectedSentence() {
        val lines = agentBuildCorrectionLines(
            listOf(AgentCardPair("I go to school yesterday.", "I went to school yesterday.", "past tense"))
        )
        assertEquals(1, lines.size)
        assertEquals(setOf(1), lines[0].errorIndices)
        assertEquals("I went to school yesterday.", lines[0].corrected)
        assertEquals("past tense", lines[0].explanation)
    }

    @Test
    fun buildErrorHuntLineLocatesErrorsInPassageAndAppliesFixes() {
        val line = agentBuildErrorHuntLine(
            "Yesterday she go to the market and buyed apple.",
            listOf(
                AgentCardPair("go", "went", ""),
                AgentCardPair("buyed", "bought", "")
            )
        )
        requireNotNull(line)
        assertTrue(line.errorIndices.contains(2))
        assertTrue(line.errorIndices.contains(7))
        assertTrue(line.corrected.contains("went"))
        assertTrue(line.corrected.contains("bought"))
    }

    @Test
    fun questionSetPagerClampsInsteadOfSkippingPages() {
        assertEquals(0, agentQuestionPagerTargetIndex(currentIndex = 0, questionCount = 3, delta = -1))
        assertEquals(1, agentQuestionPagerTargetIndex(currentIndex = 0, questionCount = 3, delta = 1))
        assertEquals(2, agentQuestionPagerTargetIndex(currentIndex = 2, questionCount = 3, delta = 1))
        assertEquals(1, agentQuestionPagerTargetIndex(currentIndex = 4, questionCount = 3, delta = -1))
    }

    @Test
    fun questionSetPromptIncludesQuestionNumberAndFullText() {
        assertEquals(
            "1. What does \"itinerary\" mean?",
            agentCardQuestionPromptText(0, "What does \"itinerary\" mean?")
        )
    }



    @Test
    fun speakingResponseFeedbackUsesAnswerLengthAndRetryState() {
        val short = "I agree."
        val medium = "I would choose this job because it gives me a chance to learn from customers every day and improve my communication skills."
        val long = (1..48).joinToString(" ") { "word" }

        assertEquals(false, agentSpeakingResponseCanComplete(short))
        assertEquals(true, agentSpeakingResponseCanComplete(medium))
        assertTrue(agentSpeakingResponseFeedbackText(short).contains("偏短"))
        assertTrue(agentSpeakingResponseFeedbackText(medium).contains("已经成形"))
        assertTrue(agentSpeakingResponseFeedbackText(long).contains("45-60 秒"))
        assertEquals(
            AgentWritingDraftUiState(medium, evaluated = true),
            agentWritingDraftStateAfterEvaluate(AgentWritingDraftUiState(medium, evaluated = false))
        )
        assertEquals(
            AgentWritingDraftUiState(medium, evaluated = false),
            agentWritingDraftStateAfterRetry(AgentWritingDraftUiState(medium, evaluated = true))
        )
    }





    @Test
    fun materialPreviewCheckRequiresAllAnswersWithExplicitReason() {
        val actionState = agentMaterialQuestionPreviewActionState(
            questionCount = 3,
            answeredCount = 2,
            revealed = false
        )

        assertFalse(actionState.enabled)
        assertEquals("请先完成全部题目", actionState.disabledReason)
    }

    @Test
    fun unrevealedAiReviewIsBlockedAndPromptedOnlyOnce() {
        val record = historyRecord(answersRevealed = false)
        val status = agentAiReviewChatStatusMessage(record)
        val existingMessages = listOf(
            AgentChatMessage(id = 1L, role = AgentChatRole.Agent, text = status.orEmpty())
        )

        assertEquals("请先在练习页核对答案后再使用 AI 分析。", status)
        assertFalse(agentCanStartAiReview(record))
        assertFalse(agentShouldAppendAiReviewStatusMessage(status.orEmpty(), existingMessages))
    }

    @Test
    fun revealedAiReviewCanStartWithoutStatusMessage() {
        val record = historyRecord(answersRevealed = true)

        assertNull(agentAiReviewChatStatusMessage(record))
        assertTrue(agentCanStartAiReview(record))
    }

    @Test
    fun practiceFeedbackExplanationIncludesPromptAndAnswer() {
        val explanation = agentPracticeFeedbackExplanation(
            type = AgentCardComponent.QuestionSet,
            answer = "B（at the station）",
            prompt = "Where will they meet after lunch?"
        )

        assertTrue(explanation.contains("正确答案：B（at the station）。"))
        assertTrue(explanation.contains("题干关键点：Where will they meet after lunch?。"))
        assertTrue(explanation.contains("提示："))
    }

    @Test
    fun shortProvidedPracticeExplanationIsSupplementedWithChineseAnalysis() {
        val explanation = agentPracticeFeedbackExplanation(
            type = AgentCardComponent.ShortAnswer,
            answer = "I visited the museum yesterday.",
            prompt = "Type this sentence in past tense.",
            providedExplanation = "Past tense."
        )

        assertTrue(explanation.contains("Past tense."))
        assertTrue(explanation.contains("正确答案：I visited the museum yesterday."))
        assertTrue(explanation.contains("题干关键点：Type this sentence in past tense."))
        assertTrue(explanation.contains("提示："))
        assertTrue(Regex("[\\u4e00-\\u9fff]").containsMatchIn(explanation))
    }

    @Test
    fun multiBlankClozeFeedbackUsesClozeSpecificExplanation() {
        val explanation = agentPracticeFeedbackExplanation(
            type = AgentCardComponent.Cloze,
            answer = "have / finished",
            prompt = "I ___ already ___.",
            providedExplanation = ""
        )

        assertTrue(explanation.contains("正确答案：have / finished。"))
        assertTrue(explanation.contains("空格前后"))
        assertTrue(explanation.contains("固定搭配"))
        assertFalse(explanation.contains("回到原文或语境找同义替换"))
    }

    @Test
    fun feedbackDisplayExplanationRemovesDuplicateExactAnswerLine() {
        val explanation = "正确答案：have / finished。题干关键点：I ___ already ___.。提示：看空格前后。"

        val display = agentPracticeFeedbackDisplayExplanation(
            explanation = explanation,
            answer = "have / finished"
        )

        assertFalse(display.contains("正确答案：have / finished。"))
        assertTrue(display.contains("题干关键点"))
        assertTrue(display.contains("提示："))
    }




    @Test
    fun writingDraftEvaluationLocksUntilRetry() {
        val empty = AgentWritingDraftUiState(draft = "", evaluated = false)
        assertFalse(agentWritingDraftCanEvaluate(empty.draft))
        assertEquals(empty, agentWritingDraftStateAfterEvaluate(empty))

        val typed = agentWritingDraftStateAfterTextChange(
            empty,
            "Dear Ms. Lee, I was sick yesterday and missed class. Could you please tell me the homework?"
        )
        assertTrue(agentWritingDraftCanEvaluate(typed.draft))
        assertEquals(17, agentWritingDraftWordCount(typed.draft))

        val evaluated = agentWritingDraftStateAfterEvaluate(typed)
        assertTrue(evaluated.evaluated)
        assertEquals(evaluated, agentWritingDraftStateAfterTextChange(evaluated, "changed"))
        assertFalse(agentWritingDraftStateAfterRetry(evaluated).evaluated)
        assertTrue(agentWritingDraftFeedbackText(evaluated.draft).contains("Draft saved"))
    }

    @Test
    fun providedQuestionSetExplanationIsSupplementedWithChineseAnalysis() {
        val feedback = agentQuestionSetFeedback(
            question = Question(
                questionText = "Where will they meet?",
                options = listOf("At the cafe", "At the station"),
                correctAnswer = 1,
                explanation = "The dialogue says they will meet at the station."
            ),
            selectedAnswer = 0
        )

        val explanation = requireNotNull(feedback).explanation
        assertTrue(explanation.contains("The dialogue says they will meet at the station."))
        assertTrue(explanation.contains("正确答案：B（At the station）。"))
        assertTrue(explanation.contains("题干关键点：Where will they meet?。"))
        assertTrue(explanation.contains("提示："))
        assertTrue(Regex("[\\u4e00-\\u9fff]").containsMatchIn(explanation))
    }

    @Test
    fun reviewItemExplanationFallsBackWhenNotStored() {
        // 错题本复习：老条目没存解析时按有无选项给通用核对指引，存了就用原文。
        fun item(explanation: String, options: List<String>) = ReviewItem(
            id = "r1", kind = "mcq", componentType = "question_preview", skill = "听力",
            prompt = "Where will they meet?", options = options, answer = "At the station",
            explanation = explanation, box = 0, dueAt = 0L, timesWrong = 1, timesReviewed = 0, due = true
        )
        assertEquals("原文解析。", reviewItemDisplayExplanation(item("原文解析。", listOf("A", "B"))))
        assertTrue(reviewItemDisplayExplanation(item("", listOf("A", "B"))).contains("干扰项"))
        assertTrue(reviewItemDisplayExplanation(item("", emptyList())).contains("参考答案"))
    }


    @Test
    fun singleLongQuestionSetCountsAsLongContent() {
        val longQuestion = "Read the situation carefully and choose the best answer. ".repeat(5) +
            "What should the student do next?"
        val component = agentCardComponent(
            AgentCardComponent.QuestionSet,
            questions = listOf(
                Question(
                    questionText = longQuestion,
                    options = listOf("Ask for help", "Leave now", "Ignore it", "Cancel the plan"),
                    correctAnswer = 0
                )
            )
        )

        assertTrue(AgentCardDisplayPayload.isLong(component))
    }


    @Test
    fun interactivePracticeCardsDoNotLimitExpandedFeedbackHeight() {
        listOf(
            AgentCardComponent.Cloze,
            AgentCardComponent.ShortAnswer,
            AgentCardComponent.SentenceBuilder,
            AgentCardComponent.QuestionSet
        ).forEach { type ->
            assertTrue(agentCardComponentHasInteractivePractice(type))
            assertFalse(
                agentCardAtomBlockLimitsExpandedHeight(
                    isLong = true,
                    expanded = true,
                    componentType = type
                )
            )
            assertFalse(
                agentCardAtomBlockLimitsCollapsedHeight(
                    isLong = true,
                    expanded = false,
                    componentType = type
                )
            )
        }

        assertTrue(
            agentCardAtomBlockLimitsExpandedHeight(
                isLong = true,
                expanded = true,
                componentType = AgentCardComponent.Summary
            )
        )
        assertFalse(
            agentCardAtomBlockLimitsExpandedHeight(
                isLong = true,
                expanded = false,
                componentType = AgentCardComponent.Summary
            )
        )
        assertTrue(
            agentCardAtomBlockLimitsCollapsedHeight(
                isLong = true,
                expanded = false,
                componentType = AgentCardComponent.Summary
            )
        )
    }








    @Test
    fun shortAnswerNormalizationAcceptsCommonContractions() {
        assertEquals(
            normalizeAgentShortAnswer("I'm going to the station."),
            normalizeAgentShortAnswer("I am going to the station")
        )
        assertEquals(
            normalizeAgentShortAnswer("They cannot find the ticket."),
            normalizeAgentShortAnswer("They can't find the ticket")
        )
    }

    @Test
    fun shortAnswerNormalizationAcceptsCorrectedSentenceWithoutFinalPeriod() {
        assertEquals(
            normalizeAgentShortAnswer("She goes to school every day."),
            normalizeAgentShortAnswer("She goes to school every day")
        )
        assertNotEquals(
            normalizeAgentShortAnswer("She goes to school every day."),
            normalizeAgentShortAnswer("She go to school every day.")
        )
    }

    @Test
    fun shortAnswerBookedTicketInputChecksAndRetriesLocally() {
        val answer = "I have already booked the ticket."
        val typed = agentShortAnswerStateAfterTextChange(
            AgentShortAnswerUiState(text = "", revealed = false),
            "i have already booked the ticket"
        )

        assertEquals("i have already booked the ticket", typed.text)
        assertEquals(normalizeAgentShortAnswer(answer), normalizeAgentShortAnswer(typed.text))
        assertNotEquals(
            normalizeAgentShortAnswer(answer),
            normalizeAgentShortAnswer("I have already changed the ticket.")
        )

        val revealed = typed.copy(revealed = true)
        assertEquals(revealed, agentShortAnswerStateAfterTextChange(revealed, "I have already changed the ticket."))
        assertEquals(typed, agentShortAnswerStateAfterRetry(revealed))

        val explanation = agentPracticeFeedbackExplanation(
            type = AgentCardComponent.ShortAnswer,
            answer = answer,
            prompt = "Type this sentence."
        )
        assertTrue(explanation.contains("I have already booked the ticket."))
        assertTrue(Regex("[\\u4e00-\\u9fff]").containsMatchIn(explanation))
    }

    @Test
    fun exportSuccessNoticeIncludesConcreteDownloadsLocation() {
        assertEquals(
            "已导出到下载目录 ListenE/Project_Feedback_Meeting.zip",
            agentExportSuccessNotice("Project_Feedback_Meeting.zip")
        )
    }

    private fun historyRecord(answersRevealed: Boolean): HistoryRecord =
        HistoryRecord(
            id = "record-1",
            scene = "practice",
            createdAt = 1L,
            content = ListeningContent(
                title = "Practice",
                script = "",
                questions = listOf(
                    Question(
                        questionText = "Where are they?",
                        options = listOf("Cafe", "Station"),
                        correctAnswer = 1
                    )
                )
            ),
            selectedAnswers = mapOf(0 to 1),
            answersRevealed = answersRevealed
        )
}
