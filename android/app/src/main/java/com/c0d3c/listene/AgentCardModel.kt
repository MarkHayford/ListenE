package com.c0d3c.listene

enum class AgentCardComponent {
    Header,
    Chips,
    Actions,
    Summary,
    Progress,
    Audio,
    QuestionPreview,
    Transcript,
    SentenceTranscript,
    Feedback,
    Suggestion,
    Divider,
    Vocabulary,
    Phrase,
    Grammar,
    Translation,
    Examples,
    Pronunciation,
    Cloze,
    ShortAnswer,
    SentenceBuilder,
    Ordering,
    QuestionSet,
    Reading,
    GapMatch,
    ChartWriting,
    Compare,
    Correction,
    Rubric,
    ListeningCue,
    MinimalPair,
    WordFamily,
    Scenario,
    Register,
    SpeakingPrompt,
    WritingOutline,
    MistakePattern,
    Ethics,
    Debate,
    ErrorHunt,
    Storytelling,
    Paraphrase,
    Shadowing
}

enum class AgentCardComponentState {
    Default,
    Loading,
    Empty,
    Error,
    Disabled,
    Ready,
    Selected,
    Revealed
}

internal const val AGENT_CARD_MAX_QUESTION_SET_QUESTIONS = 20

data class AgentCardPair(
    val left: String = "",
    val right: String = "",
    val hint: String = ""
)

data class AgentCardLabeledText(
    val label: String = "",
    val text: String = ""
)

data class AgentCardComponentSpec(
    val type: AgentCardComponent,
    val title: String = "",
    val text: String = "",
    val value: Float? = null,
    val items: List<String> = emptyList(),
    val pairs: List<AgentCardPair> = emptyList(),
    val tokens: List<String> = emptyList(),
    val steps: List<AgentCardLabeledText> = emptyList(),
    val criteria: List<AgentCardLabeledText> = emptyList(),
    val options: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val questions: List<Question> = emptyList(),
    val answer: String = "",
    val explanation: String = "",
    val action: String = "",
    val primary: Boolean = false,
    val source: String = "",
    val state: AgentCardComponentState = AgentCardComponentState.Default
)

data class AgentCardReply(
    val text: String,
    val listeningRecordId: String = ""
)
