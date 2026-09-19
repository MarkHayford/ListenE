package com.c0d3c.listene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ListeningAiAnalysisGateTest {
    @Test
    fun practiceAiAnalysisRejectsUnrevealedStoredRecord() {
        val record = historyRecord(answersRevealed = false)

        val decision = listeningAiAnalysisRecordForPractice(record)

        assertNull(decision)
    }

    @Test
    fun practiceAiAnalysisAllowsRevealedStoredRecordWithoutChangingIt() {
        val record = historyRecord(answersRevealed = true)

        val decision = listeningAiAnalysisRecordForPractice(record)

        assertSame(record, decision)
        assertEquals(true, decision?.answersRevealed)
    }

    @Test
    fun practiceAiAnalysisRejectsMissingRecord() {
        assertNull(listeningAiAnalysisRecordForPractice(null))
    }

    private fun historyRecord(answersRevealed: Boolean) = HistoryRecord(
        id = "record_1",
        scene = "校园对话",
        createdAt = 1L,
        contentType = "dialogue",
        answersRevealed = answersRevealed,
        selectedAnswers = if (answersRevealed) mapOf(0 to 0) else emptyMap(),
        content = ListeningContent(
            title = "校园对话",
            script = "A: Hello.\nB: Hi.",
            questions = listOf(
                Question(
                    questionText = "What does B say?",
                    options = listOf("Hi", "Bye"),
                    correctAnswer = 0
                )
            )
        )
    )
}
