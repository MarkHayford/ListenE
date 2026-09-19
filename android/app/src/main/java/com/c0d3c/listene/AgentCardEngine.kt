package com.c0d3c.listene

fun agentCardComponent(
    type: AgentCardComponent,
    title: String = "",
    text: String = "",
    value: Float? = null,
    items: List<String> = emptyList(),
    pairs: List<AgentCardPair> = emptyList(),
    tokens: List<String> = emptyList(),
    steps: List<AgentCardLabeledText> = emptyList(),
    criteria: List<AgentCardLabeledText> = emptyList(),
    options: List<String> = emptyList(),
    examples: List<String> = emptyList(),
    questions: List<Question> = emptyList(),
    answer: String = "",
    explanation: String = "",
    action: String = "",
    primary: Boolean = false,
    source: String = "",
    state: AgentCardComponentState = AgentCardComponentState.Default
): AgentCardComponentSpec = AgentCardComponentSpec(
    type = type,
    title = title,
    text = text,
    value = value,
    items = items,
    pairs = pairs,
    tokens = tokens,
    steps = steps,
    criteria = criteria,
    options = options,
    examples = examples,
    questions = questions,
    answer = answer,
    explanation = explanation,
    action = action,
    primary = primary,
    source = source,
    state = state
)

object AgentCardDisplayPayload {
    fun hasContent(component: AgentCardComponentSpec): Boolean =
        component.title.isNotBlank() ||
            component.text.isNotBlank() ||
            component.items.isNotEmpty() ||
            component.pairs.isNotEmpty() ||
            component.tokens.isNotEmpty() ||
            component.steps.isNotEmpty() ||
            component.criteria.isNotEmpty() ||
            component.options.isNotEmpty() ||
            component.examples.isNotEmpty() ||
            component.questions.isNotEmpty() ||
            component.answer.isNotBlank()

    fun textRows(component: AgentCardComponentSpec): List<String> =
        when {
            component.examples.isNotEmpty() -> component.examples
            component.items.isNotEmpty() -> component.items
            else -> splitTextRows(component.text)
        }.filter { it.isNotBlank() }

    fun options(component: AgentCardComponentSpec): List<String> =
        when {
            component.options.isNotEmpty() -> component.options
            component.text.isBlank() && component.items.size > 1 -> component.items.drop(1)
            else -> component.items
        }.filter { it.isNotBlank() }

    fun speakingPrompts(component: AgentCardComponentSpec): List<String> =
        when {
            component.type != AgentCardComponent.SpeakingPrompt -> emptyList()
            component.items.size > 1 -> component.items
            component.text.isBlank() && component.items.isNotEmpty() -> component.items
            else -> emptyList()
        }.filter { it.isNotBlank() }

    fun answerIndex(component: AgentCardComponentSpec): Int? {
        val answer = component.answer.trim()
        if (answer.isBlank()) return null
        val options = options(component)
        return agentQuestionAnswerIndex(answer, options)
    }

    fun clozeAnswers(component: AgentCardComponentSpec): List<String> {
        val raw = component.answer.trim()
        if (raw.isBlank()) return emptyList()
        return raw
            .split(Regex("\\s*(?:\\||/|,|;|、|，|；|\\n|→|->)\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(raw) }
    }

    fun pairs(component: AgentCardComponentSpec): List<AgentCardPair> =
        when {
            component.pairs.isNotEmpty() -> component.pairs
            component.items.isNotEmpty() -> component.items.mapNotNull { parsePair(it) }
            else -> splitTextRows(component.text).mapNotNull { parsePair(it) }
        }.filter { it.left.isNotBlank() || it.right.isNotBlank() || it.hint.isNotBlank() }

    fun tokenRows(component: AgentCardComponentSpec): List<List<String>> =
        when {
            component.tokens.isNotEmpty() -> listOf(component.tokens)
            component.items.isNotEmpty() -> component.items.map { splitTokens(it) }
            else -> splitTextRows(component.text).map { splitTokens(it) }
        }.map { row -> row.filter { it.isNotBlank() } }.filter { it.isNotEmpty() }

    fun sentenceBuilderWords(component: AgentCardComponentSpec): List<String> {
        val baseWords = component.items.ifEmpty { component.tokens }.map { it.trim() }.filter { it.isNotBlank() }
        val answerWords = splitSentenceAnswerWords(component.answer)
        if (baseWords.isNotEmpty() && sentenceBuilderChunksMatchAnswer(baseWords, component.answer)) return baseWords.take(12)
        if (answerWords.isNotEmpty()) return deterministicSentenceBuilderFallbackWords(answerWords)
        return baseWords.take(12)
    }

    fun orderingItems(component: AgentCardComponentSpec): List<String> =
        component.items
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(8)

    fun orderingAnswerParts(component: AgentCardComponentSpec): List<String> {
        val answerParts = splitOrderingAnswerParts(component.answer)
        if (answerParts.isNotEmpty()) return answerParts.take(8)
        return orderingItems(component)
    }

    fun labeledRows(component: AgentCardComponentSpec): List<AgentCardLabeledText> {
        val typedRows = when {
            component.steps.isNotEmpty() -> component.steps
            component.criteria.isNotEmpty() -> component.criteria
            else -> emptyList()
        }
        if (typedRows.isNotEmpty()) return typedRows
            .filter { it.label.isNotBlank() || it.text.isNotBlank() }
        return textRows(component).mapNotNull { row ->
            val pair = parseLabel(row)
            AgentCardLabeledText(pair.first, pair.second)
                .takeIf { it.label.isNotBlank() || it.text.isNotBlank() }
        }
    }

    fun questions(component: AgentCardComponentSpec): List<Question> =
        component.questions.filter { question ->
            question.questionText.isNotBlank() &&
                question.options.size >= 2 &&
                question.correctAnswer in question.options.indices
        }

    fun isLong(component: AgentCardComponentSpec): Boolean {
        val textLengthLimit = if (component.type == AgentCardComponent.Summary) 220 else 180
        if (component.text.length > textLengthLimit) return true
        return when (component.type) {
            AgentCardComponent.Vocabulary,
            AgentCardComponent.Phrase,
            AgentCardComponent.ListeningCue -> entryRows(component).size > 6
            AgentCardComponent.Grammar -> component.items.size > 4
            AgentCardComponent.Translation -> entryRows(component).size > 5
            AgentCardComponent.Examples -> textRows(component).size > 5
            AgentCardComponent.Pronunciation -> component.items.size > 5
            AgentCardComponent.Cloze -> options(component).size > 8
            AgentCardComponent.QuestionSet -> questions(component).size > 1 ||
                questions(component).any { it.options.size > 4 || it.questionText.length > 120 }
            AgentCardComponent.Ordering -> orderingItems(component).size > 4 ||
                orderingItems(component).any { it.length > 110 }
            AgentCardComponent.Compare -> pairs(component).size > 6
            AgentCardComponent.Correction -> labeledRows(component).size > 5
            AgentCardComponent.Rubric -> labeledRows(component).size > 6
            AgentCardComponent.MinimalPair -> pairs(component).size > 5
            AgentCardComponent.WordFamily -> tokenRows(component).size > 5 || tokenRows(component).any { it.size > 8 }
            AgentCardComponent.Scenario -> pairs(component).size > 5 ||
                textRows(component).size > 3 ||
                textRows(component).any { it.length > 72 }
            AgentCardComponent.Register -> pairs(component).size > 4
            AgentCardComponent.SpeakingPrompt -> speakingPrompts(component).size > 5 || options(component).size > 8
            AgentCardComponent.WritingOutline -> labeledRows(component).size > 6
            AgentCardComponent.MistakePattern -> labeledRows(component).size > 5
            else -> component.items.size > 6 ||
                component.pairs.size > 5 ||
                component.tokens.size > 8 ||
                component.steps.size > 6 ||
                component.criteria.size > 6 ||
                component.options.size > 8 ||
                component.examples.size > 5 ||
                component.questions.size > 1
        }
    }

    private fun entryRows(component: AgentCardComponentSpec): List<String> =
        component.items.ifEmpty { splitTextRows(component.text) }

    private fun splitTextRows(text: String): List<String> =
        text.split('\n', '；', ';')
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun parsePair(raw: String): AgentCardPair? {
        val labelPair = parseLabel(raw)
        if (labelPair.first.isBlank() && labelPair.second.isBlank()) return null
        return AgentCardPair(left = labelPair.first, right = labelPair.second)
    }

    private fun parseLabel(raw: String): Pair<String, String> {
        val value = raw.trim()
        val separators = listOf(" -> ", "->", " => ", "=>", " → ", "→", " = ", "=", " | ", " - ", "：", ":")
        separators.forEach { separator ->
            val index = value.indexOf(separator)
            if (index > 0) {
                return value.take(index).trim() to value.drop(index + separator.length).trim()
            }
        }
        Regex("^([A-Za-z][A-Za-z'-]{1,24})\\s*/\\s*([A-Za-z][A-Za-z'-]{1,24})$").matchEntire(value)?.let { match ->
            return match.groupValues[1].trim() to match.groupValues[2].trim()
        }
        return value to ""
    }

    private fun splitTokens(raw: String): List<String> =
        raw.split("|", "、", ",", "，", " ")
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun splitSentenceAnswerWords(raw: String): List<String> =
        raw.replace(Regex("[.!?。！？]+$"), "")
            .split(Regex("\\s+"))
            .map { it.trim().trim(',', ';', ':', '"', '\'', '“', '”') }
            .filter { it.isNotBlank() }

    private fun splitOrderingAnswerParts(raw: String): List<String> {
        val value = raw.trim()
        if (value.isBlank()) return emptyList()
        val pipeParts = value.split(Regex("\\s*\\|\\s*"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (pipeParts.size >= 2) return pipeParts
        return Regex("[^.!?。！？]+[.!?。！？]")
            .findAll(value)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun deterministicSentenceBuilderFallbackWords(words: List<String>): List<String> {
        val capped = words.take(12)
        if (capped.size <= 1) return capped
        val shuffled = capped.indices
            .sortedWith(compareBy<Int> { ((it + 1) * 7) % (capped.size + 3) }.thenBy { it })
            .map { capped[it] }
        return if (shuffled == capped) capped.reversed() else shuffled
    }

    private fun sentenceBuilderChunksMatchAnswer(chunks: List<String>, answer: String): Boolean {
        val target = normalizeSentenceBuilderPhrase(answer)
        val cleanChunks = chunks.map(::normalizeSentenceBuilderPhrase).filter { it.isNotBlank() }
        if (target.isBlank() || cleanChunks.size != chunks.size) return false
        fun canBuild(remaining: String, used: BooleanArray): Boolean {
            if (remaining.isBlank()) return used.all { it }
            cleanChunks.forEachIndexed { index, chunk ->
                if (!used[index] && (remaining == chunk || remaining.startsWith("$chunk "))) {
                    used[index] = true
                    if (canBuild(remaining.removePrefix(chunk).trimStart(), used)) return true
                    used[index] = false
                }
            }
            return false
        }
        return canBuild(target, BooleanArray(cleanChunks.size))
    }

    private fun normalizeSentenceBuilderPhrase(raw: String): String =
        raw.trim()
            .replace(Regex("[.!?。！？]+$"), "")
            .replace(Regex("\\s+"), " ")
            .lowercase()
}

object AgentCardEngine {
    // 旧渲染器退役 C2：欢迎/生成中/分析完成是纯通知，文本气泡足矣，不再造 cardSpec。
    fun enteredWorkspace(workspace: LearningWorkspace): AgentCardReply = AgentCardReply(
        text = "已进入「${workspace.title}」。你可以继续问英语问题，也可以描述想要的听力素材；需要卡片时会由 AI 实时组装。"
    )

    fun materialGenerating(workspace: LearningWorkspace, need: String = ""): AgentCardReply = AgentCardReply(
        text = listOf(
            "素材生成中（${need.take(48).ifBlank { workspace.title }}）。",
            "完成后会请求 AI 根据上下文实时生成练习卡。"
        ).joinToString("")
    )

    fun materialReady(record: HistoryRecord?): AgentCardReply = AgentCardReply(
        text = "素材已生成，可以直接播放音频、查看原文并答题。",
        listeningRecordId = record?.id?.ifBlank { "current" } ?: "current"
    )

    fun analysisReady(record: HistoryRecord?): AgentCardReply {
        val result = record?.analysisResult
        val lines = buildList {
            add(result?.summary?.takeIf { it.isNotBlank() }?.let { "AI 分析完成：$it" } ?: "AI 分析完成。")
            result?.weakPoints?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { points ->
                add("薄弱点：${points.joinToString("；")}")
            }
            result?.suggestions?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { tips ->
                add("下一步建议：${tips.joinToString("；")}")
            }
        }
        return AgentCardReply(text = lines.joinToString("\n"))
    }
}

