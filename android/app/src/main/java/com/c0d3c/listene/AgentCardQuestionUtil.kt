package com.c0d3c.listene

internal fun stripAgentQuestionOptionLabel(value: String): String =
    value.trim()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("^\\s*[A-Da-d][\\.)、:：]\\s+"), "")
        .trim()

internal fun agentQuestionAnswerIndex(raw: String, options: List<String>): Int? {
    val value = raw.trim().trimEnd('.', ')', '、', ':', '：')
    if (value.isBlank()) return null
    val number = value.toIntOrNull()
    if (number != null) {
        if (number in options.indices) return number
        if (number - 1 in options.indices) return number - 1
    }
    val labelIndex = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(value.uppercase())
    if (labelIndex >= 0 && labelIndex in options.indices) return labelIndex
    return options.indexOfFirst { normalizeAgentQuestionAnswerText(it) == normalizeAgentQuestionAnswerText(value) }
        .takeIf { it >= 0 }
}

internal fun normalizeAgentQuestionAnswerText(value: String): String =
    stripAgentQuestionOptionLabel(value).lowercase().replace(Regex("\\s+"), " ")
