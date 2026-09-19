package com.c0d3c.listene

internal fun cleanAgentClozeInstructionLeak(value: String): String =
    value.replace(Regex("\\s+(?:exactly\\s+)?[0-9一二两三四五六七八九十]+\\s+(?:\\w+\\s+){0,2}blanks?\\b[\\s\\S]*$", RegexOption.IGNORE_CASE), "")
        .trim()

internal fun agentCardClozeTextFromSourceSentence(
    component: AgentCardComponentSpec,
    sourceSentence: String
): String {
    if (component.type != AgentCardComponent.Cloze) return ""
    if (!agentCardClozeTextIsGenericBlankLabels(component.text)) return ""
    val answers = agentCardClozeAnswerPartsForRepair(component)
    if (answers.isEmpty()) return ""
    var text = sourceSentence.trim()
    var blankCount = agentCardClozeBlankCount(text)
    answers.forEach { answer ->
        if (blankCount >= answers.size) return@forEach
        val pattern = Regex("\\b${Regex.escape(answer).replace("\\ ", "\\s+")}\\b", RegexOption.IGNORE_CASE)
        if (!pattern.containsMatchIn(text)) return ""
        text = pattern.replaceFirst(text, "___")
        blankCount = agentCardClozeBlankCount(text)
    }
    return if (blankCount == answers.size) text.trim() else ""
}

internal fun agentCardClozeTextFromSourceSentence(
    answers: List<String>,
    sourceSentence: String,
    requestedCount: Int
): String {
    if (answers.size < requestedCount) return ""
    var text = sourceSentence.trim()
    if (text.isBlank()) return ""
    var blankCount = agentCardClozeBlankCount(text)
    answers.take(requestedCount).forEach { answer ->
        if (blankCount >= requestedCount) return@forEach
        val pattern = Regex("\\b${Regex.escape(answer).replace("\\ ", "\\s+")}\\b", RegexOption.IGNORE_CASE)
        if (!pattern.containsMatchIn(text)) return ""
        text = pattern.replaceFirst(text, "___")
        blankCount = agentCardClozeBlankCount(text)
    }
    return if (blankCount == requestedCount) text.trim() else ""
}

internal fun mergeExplicitClozeOptions(
    options: List<String>,
    answers: List<String>,
    constraint: AgentClozeConstraint?
): List<String> {
    val explicitOptions = constraint?.options.orEmpty()
    val cleanOptions = cleanClozeOptionCandidates(options, answers, constraint)
    val base = if (explicitOptions.isNotEmpty()) {
        uniqueClozeTexts(explicitOptions + answers)
    } else if (constraint?.strict == true) {
        uniqueClozeTexts(answers + cleanOptions)
    } else {
        uniqueClozeTexts(cleanOptions + answers)
    }
    val requested = constraint?.optionCount?.coerceIn(0, 8) ?: 0
    if (requested == 0 || base.size >= requested) return base
    return uniqueClozeTexts(base + fallbackClozeDistractorsForConstraint(constraint, answers))
        .take(requested.coerceAtLeast(2))
}

private fun uniqueClozeTexts(items: List<String>): List<String> {
    val seen = mutableSetOf<String>()
    return items
        .map { it.trim().take(80) }
        .filter { it.isNotBlank() }
        .filter { seen.add(it.lowercase().replace(Regex("\\s+"), " ")) }
}

internal fun cleanClozeOptionCandidates(
    options: List<String>,
    answers: List<String>,
    constraint: AgentClozeConstraint?
): List<String> {
    val answerLookup = answers.map { normalizeClozeOptionLookupText(it) }.toSet()
    val sourceTokens = Regex("[a-z][a-z']{1,}", RegexOption.IGNORE_CASE)
        .findAll(constraint?.sourceSentence.orEmpty().replace(Regex("_{2,}"), " "))
        .map { it.value.lowercase() }
        .toSet()
    return options
        .map { it.trim().take(80) }
        .filter { it.isNotBlank() }
        .filter { item ->
            val normalized = normalizeClozeOptionLookupText(item)
            if (normalized in answerLookup) return@filter true
            if (Regex("^(?:text|sentence|prompt|题干|文本)\\s*[:：]", RegexOption.IGNORE_CASE).containsMatchIn(item)) return@filter false
            if (Regex("_{2,}").containsMatchIn(item)) return@filter false
            val words = Regex("[a-z][a-z']{1,}", RegexOption.IGNORE_CASE)
                .findAll(item)
                .map { it.value.lowercase() }
                .toList()
            val allWords = Regex("[a-z][a-z']*", RegexOption.IGNORE_CASE)
                .findAll(item)
                .map { it.value.lowercase() }
                .toList()
            val contentWords = allWords.filterNot { it in setOf("a", "an", "the", "to", "at", "in", "on", "and", "or", "of", "for", "with") }
            if (allWords.size >= 2 && contentWords.size <= 1) return@filter false
            if (words.size >= 2 && words.all { it in sourceTokens }) return@filter false
            true
        }
}

private fun fallbackClozeDistractorsForConstraint(
    constraint: AgentClozeConstraint?,
    answers: List<String>
): List<String> {
    val source = constraint?.sourceSentence.orEmpty()
    val answerLookup = answers.map { normalizeClozeOptionLookupText(it) }.toSet()
    val pool = if (Regex("daily|routine|morning|breakfast|work|school|日常|早上", RegexOption.IGNORE_CASE).containsMatchIn(source)) {
        listOf("get dressed", "eat breakfast", "brush my teeth", "study", "cook", "sleep", "read")
    } else {
        listOf("go", "make", "take", "get", "do", "have", "play", "read")
    }
    return pool.filter { normalizeClozeOptionLookupText(it) !in answerLookup }
}

private fun normalizeClozeOptionLookupText(value: String): String =
    value.lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

internal fun agentCardClozeTextIsGenericBlankLabels(value: String): Boolean {
    val text = value.trim()
    if (text.isBlank()) return false
    return Regex("^(?:Blank\\s*\\d+\\s*[:：]?\\s*_{2,}\\s*)+$", RegexOption.IGNORE_CASE).matches(text) ||
        Regex("^(?:\\d+[\\.)、]\\s*_{2,}\\s*)+$").matches(text)
}

internal fun agentCardClozeAnswerPartsForRepair(component: AgentCardComponentSpec): List<String> {
    val answers = AgentCardDisplayPayload.clozeAnswers(component)
    if (answers.size == 1 && agentCardClozeBlankCount(component.text) > 1) {
        return answers.first().split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }
    }
    return answers
}

data class AgentClozeConstraint(
    val answerParts: List<String> = emptyList(),
    val options: List<String> = emptyList(),
    val sourceSentence: String = "",
    val optionCount: Int = 0,
    val strict: Boolean = false
)

fun agentCardClozeBlankCount(text: String): Int =
    Regex("_{2,}(?:\\d+|[A-Za-z])_{2,}|_{2,}|\\[\\s*blank\\s*\\]", RegexOption.IGNORE_CASE).findAll(text).count()

fun agentCardRepairClozeTextBlankCount(
    component: AgentCardComponentSpec,
    requestedCount: Int = 0,
    constraint: AgentClozeConstraint? = null
): AgentCardComponentSpec {
    if (component.type != AgentCardComponent.Cloze) return component
    val explicitAnswers = constraint?.answerParts.orEmpty()
    val safeRequestedCount = requestedCount.takeIf { it > 0 } ?: explicitAnswers.size
    val answers = if (explicitAnswers.isNotEmpty()) explicitAnswers else agentCardClozeAnswerPartsForRepair(component)
    if (answers.isEmpty()) return component
    val cleanedText = cleanAgentClozeInstructionLeak(component.text)
    val targetCount = safeRequestedCount.takeIf { it > 0 } ?: answers.size
    val sourceText = agentCardClozeTextFromSourceSentence(answers, constraint?.sourceSentence.orEmpty(), targetCount)
    val options = mergeExplicitClozeOptions(component.options, answers, constraint)
    if (sourceText.isNotBlank() && agentCardClozeTextIsGenericBlankLabels(cleanedText)) {
        return component.copy(
            text = sourceText,
            options = options,
            answer = answers.take(targetCount).joinToString(" | ")
        )
    }
    if (agentCardClozeBlankCount(cleanedText) >= targetCount) {
        return component.copy(
            text = cleanedText,
            options = options,
            answer = if (explicitAnswers.isNotEmpty()) answers.take(targetCount).joinToString(" | ") else component.answer
        )
    }
    var repairedText = cleanedText
    var blankCount = agentCardClozeBlankCount(repairedText)
    answers.forEach { answer ->
        if (blankCount >= targetCount) return@forEach
        val pattern = Regex("\\b${Regex.escape(answer).replace("\\ ", "\\s+")}\\b", RegexOption.IGNORE_CASE)
        if (!pattern.containsMatchIn(repairedText)) return@forEach
        repairedText = pattern.replaceFirst(repairedText, "___")
        blankCount = agentCardClozeBlankCount(repairedText)
    }
    return if (blankCount == targetCount) {
        component.copy(
            text = repairedText.trim(),
            options = options,
            answer = if (explicitAnswers.isNotEmpty()) answers.take(targetCount).joinToString(" | ") else component.answer
        )
    } else {
        component
    }
}
