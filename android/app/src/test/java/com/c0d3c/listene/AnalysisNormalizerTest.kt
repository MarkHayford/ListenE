package com.c0d3c.listene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisNormalizerTest {
    @Test
    fun keepsActualWrongAnswerWhenAiClaimsAllCorrect() {
        val record = sampleRecord(selectedAnswers = mapOf(0 to 0))
        val raw = AnalysisResult(
            summary = "全部正确，可以进入下一步。",
            weakPoints = emptyList(),
            suggestions = emptyList(),
            wrongQuestionInsights = emptyList()
        )

        val normalized = AnalysisNormalizer.normalize(record, raw)

        assertEquals(1, normalized.wrongQuestionInsights.size)
        assertEquals(0, normalized.wrongQuestionInsights.first().questionIndex)
        assertEquals("It was cheaper.", normalized.wrongQuestionInsights.first().selectedAnswer)
        assertEquals("The later train was fully booked.", normalized.wrongQuestionInsights.first().correctAnswer)
        assertTrue(normalized.summary.contains("1 道错题"))
    }

    @Test
    fun removesAiHallucinatedWrongInsightWhenAnswerIsCorrect() {
        val record = sampleRecord(selectedAnswers = mapOf(0 to 1))
        val raw = AnalysisResult(
            summary = "本次有 1 道错题，需要复盘。",
            weakPoints = listOf("细节定位"),
            suggestions = listOf("复听错题"),
            wrongQuestionInsights = listOf(
                WrongQuestionInsight(
                    questionIndex = 0,
                    question = "Why did Alex choose the earlier train?",
                    selectedAnswer = "It was cheaper.",
                    correctAnswer = "The later train was fully booked.",
                    mistakeType = "细节定位",
                    insight = "AI 幻觉出来的错题"
                )
            )
        )

        val normalized = AnalysisNormalizer.normalize(record, raw)

        assertEquals(emptyList<WrongQuestionInsight>(), normalized.wrongQuestionInsights)
        assertTrue(normalized.summary.contains("没有发现错题"))
    }

    private fun sampleRecord(selectedAnswers: Map<Int, Int>): HistoryRecord =
        HistoryRecord(
            id = "record_1",
            scene = "Train booking",
            createdAt = 1L,
            content = ListeningContent(
                title = "Train booking",
                script = "Alex wanted to take the earlier train because the later train was fully booked.",
                questions = listOf(
                    Question(
                        questionText = "Why did Alex choose the earlier train?",
                        options = listOf(
                            "It was cheaper.",
                            "The later train was fully booked.",
                            "It was faster.",
                            "He missed a meeting."
                        ),
                        correctAnswer = 1,
                        explanation = "The script says the later train was fully booked."
                    )
                )
            ),
            selectedAnswers = selectedAnswers,
            answersRevealed = true
        )
}
