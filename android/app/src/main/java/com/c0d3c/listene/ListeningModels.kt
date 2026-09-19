package com.c0d3c.listene

sealed class NavIntent {
    data class Listening(val recordId: String) : NavIntent()
}

// --- Data Models ---
data class ListeningTtsSegment(
    val speakerId: String = "",
    val speakerName: String = "",
    val speakerGender: String = "",
    val voiceProfile: String = "",
    val text: String = ""
)

data class ListeningSpeaker(
    val speakerId: String = "",
    val speakerName: String = "",
    val speakerGender: String = ""
)

data class ListeningAudioSegment(
    val id: String = "",
    /** turn | sentence */
    val kind: String = "sentence",
    val speakerId: String = "",
    val speakerName: String = "",
    val speakerGender: String = "",
    val text: String = "",
    val startMs: Int = 0,
    val endMs: Int = 0,
    val turnIndex: Int = 0,
    val sentenceIndex: Int = 0
)

data class ListeningContent(
    val title: String,
    val script: String,
    val questions: List<Question>,
    val audioUrl: String? = null,
    val ttsPrompt: String = "",
    val speakers: List<ListeningSpeaker> = emptyList(),
    val ttsSegments: List<ListeningTtsSegment> = emptyList(),
    val audioSegments: List<ListeningAudioSegment> = emptyList(),
    val voiceGender: String = "",
    val speechRate: String = "",
    val voiceProfile: String = "",
    val pitch: String = "",
    val accent: String = "",
    val tone: String = ""
)
data class Question(val questionText: String, val options: List<String>, val correctAnswer: Int, val explanation: String = "", val tag: String = "")
/** 听力历史筛选：对话 / 文章各自独立列表；AI 分析使用 [ALL] */
enum class ListeningHistoryKind { ALL, DIALOGUE, ARTICLE }

data class HistoryRecord(
    val id: String,
    val scene: String,
    val createdAt: Long,
    val content: ListeningContent,
    val selectedAnswers: Map<Int, Int> = emptyMap(),
    val analysisResult: AnalysisResult? = null,
    val answersRevealed: Boolean = false,
    /** dialogue | article */
    val contentType: String = "dialogue"
)
data class WrongQuestionInsight(
    val questionIndex: Int,
    val question: String,
    val selectedAnswer: String,
    val correctAnswer: String,
    val mistakeType: String,
    val insight: String,
    val focusSentence: String = "",
    val startMs: Int? = null,
    val endMs: Int? = null,
    val startRatio: Float? = null,
    val endRatio: Float? = null
)

data class AgentNextAction(
    val title: String,
    val description: String,
    /** review | word_sentence | relisten | plan */
    val actionType: String = "review"
)

data class AgentReviewItem(
    val text: String,
    /** word | sentence | skill */
    val itemType: String,
    val reason: String = ""
)

data class AgentPlanTask(
    val title: String,
    val practiceType: String,
    val offsetDays: Int,
    val hour: Int = 20,
    val minute: Int = 0
)

data class AnalysisResult(
    val summary: String,
    val weakPoints: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val diagnosisTags: List<String> = emptyList(),
    val wrongQuestionInsights: List<WrongQuestionInsight> = emptyList(),
    val nextActions: List<AgentNextAction> = emptyList(),
    val reviewItems: List<AgentReviewItem> = emptyList(),
    val recommendedPlanTasks: List<AgentPlanTask> = emptyList()
)


sealed class GenerationState {
    object Idle : GenerationState()
    object Loading : GenerationState()
    data class Success(
        val content: ListeningContent,
        val openedFromHistory: Boolean = false,
        val recordId: String? = null,
        val initialSelectedAnswers: Map<Int, Int> = emptyMap(),
        val initialAnswersRevealed: Boolean = false,
        val scene: String = ""
    ) : GenerationState()
    data class AnalysisLoading(val record: HistoryRecord) : GenerationState()
    data class AnalysisResultScreen(val record: HistoryRecord, val result: AnalysisResult) : GenerationState()
    data class Message(val message: String, val record: HistoryRecord? = null) : GenerationState()
    data class Error(val message: String, val record: HistoryRecord? = null) : GenerationState()
}
