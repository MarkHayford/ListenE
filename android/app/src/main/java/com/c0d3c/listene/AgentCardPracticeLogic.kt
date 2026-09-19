package com.c0d3c.listene

// 卡片练习的纯逻辑（无 Compose/Android 依赖、可单测）。从 AgentListenEApp.kt 抽出，行为不变。
// 这些都是 internal，同模块的 UI 组件与测试照常调用，无需改动调用点。

// ---- Ordering（句子/段落排序）状态机与判定 ----




internal data class AgentOrderingUiState(
    val selectedIndexes: List<Int>,
    val revealed: Boolean
)







// ---- GapMatch（选句填空）空格数与正解解析 ----

internal fun agentGapMatchBlankCount(text: String): Int =
    Regex("【\\s*\\d+\\s*】|（\\s*\\d+\\s*）|\\(\\s*\\d+\\s*\\)|_{3,}").findAll(text).count()

internal fun agentGapMatchCorrectAnswers(answer: String, options: List<String>, blankCount: Int): List<Int> {
    if (blankCount <= 0) return emptyList()
    val raw = answer.trim()
    val tokens = when {
        raw.contains("|") -> raw.split("|")
        raw.contains("，") || raw.contains(",") -> raw.split(Regex("[，,]"))
        raw.contains("；") || raw.contains(";") -> raw.split(Regex("[；;]"))
        else -> raw.split(Regex("\\s+"))
    }.map { it.trim() }.filter { it.isNotEmpty() }
    val result = MutableList(blankCount) { -1 }
    tokens.forEachIndexed { index, token ->
        if (index < blankCount) result[index] = agentGapMatchResolveOption(token, options)
    }
    return result
}

private fun agentGapMatchResolveOption(token: String, options: List<String>): Int {
    val value = token.trim().removeSurrounding("\"").trim()
    if (value.isEmpty()) return -1
    if (value.length == 1 && value[0].uppercaseChar() in 'A'..'Z') {
        val idx = value[0].uppercaseChar() - 'A'
        if (idx in options.indices) return idx
    }
    value.toIntOrNull()?.let { num ->
        val idx = num - 1
        if (idx in options.indices) return idx
    }
    val norm = value.lowercase().replace(Regex("\\s+"), " ")
    val match = options.indexOfFirst { it.trim().lowercase().replace(Regex("\\s+"), " ") == norm }
    if (match >= 0) return match
    Regex("[A-Za-z]").findAll(value).lastOrNull()?.value?.let { letter ->
        val idx = letter.uppercase()[0] - 'A'
        if (idx in options.indices) return idx
    }
    return -1
}

// ---- SentenceBuilder（造句/排词）状态机与判定 ----


internal fun normalizeAgentSentenceAnswer(value: String): String =
    value.trim()
        .replace(Regex("[.!?。！？]+$"), "")
        .replace(Regex("\\s+"), " ")
        .lowercase()



internal data class AgentSentenceBuilderUiState(
    val selectedIndexes: List<Int>,
    val revealed: Boolean
)







// ---- QuestionSet（选择题组）作答态与反馈 ----

internal data class AgentQuestionSetActionState(
    val text: String,
    val enabled: Boolean,
    val disabledReason: String = "",
    val retry: Boolean = false
)

internal data class AgentQuestionSetFeedback(
    val correct: Boolean,
    val message: String,
    val explanation: String = ""
)


internal fun agentQuestionSetFeedback(question: Question, selectedAnswer: Int?): AgentQuestionSetFeedback? {
    val selected = selectedAnswer ?: return null
    val answer = question.options.getOrNull(question.correctAnswer) ?: return null
    val correct = selected == question.correctAnswer
    val selectedText = question.options.getOrNull(selected).orEmpty()
    val correctLetter = optionLabel(question.correctAnswer).take(1)
    val selectedLetter = optionLabel(selected).take(1)
    val answerLabel = if (answer.isNotBlank()) "$correctLetter（$answer）" else correctLetter
    val analysis = agentPracticeFeedbackExplanation(
        type = AgentCardComponent.QuestionSet,
        answer = answerLabel,
        prompt = question.questionText,
        providedExplanation = question.explanation
    )
    val explanation = if (correct) {
        "你选的是正确项。$analysis"
    } else {
        "你选择了 $selectedLetter${selectedText.takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()}，但这项和题干要求不完全匹配。" +
            analysis
    }
    return AgentQuestionSetFeedback(
        correct = correct,
        message = if (correct) "回答正确" else "不正确",
        explanation = explanation
    )
}


// 错题本复习「看答案」后的解析：条目存了解析用原文；老条目/未带解析的按有无选项给一句通用核对指引，
// 保证复习时不只有对错和正确答案（与练习卡「核对答案必有解析」同一约定）。
internal fun reviewItemDisplayExplanation(item: ReviewItem): String {
    val provided = item.explanation.trim()
    if (provided.isNotBlank()) return provided
    return if (item.options.isNotEmpty()) {
        "对照高亮的正确项回想当时的选择：先抓题干考点，再逐一排除与之不符的干扰项。"
    } else {
        "对照上方参考答案自查：先理解题干考点，再把答案放回原句或语境验证是否通顺、匹配。"
    }
}

// ---- Chart（图表写作）模型解析与参考摘要 ----

internal data class AgentChartModel(
    val type: String,
    val categories: List<String>,
    val seriesNames: List<String>,
    val seriesValues: List<List<Float>>
) {
    val seriesCount: Int get() = seriesNames.size
    val hasData: Boolean get() = categories.isNotEmpty() && seriesValues.any { row -> row.any { it != 0f } }
}

internal fun agentParseChart(items: List<String>, tokens: List<String>, typeRaw: String): AgentChartModel {
    val categories = mutableListOf<String>()
    val rows = mutableListOf<List<Float>>()
    items.forEach { item ->
        val sepIndex = item.indexOfFirst { it == ':' || it == '：' }
        val label = if (sepIndex >= 0) item.substring(0, sepIndex).trim() else ""
        val valuesPart = if (sepIndex >= 0) item.substring(sepIndex + 1) else ""
        val values = Regex("-?\\d+(?:\\.\\d+)?").findAll(valuesPart).map { it.value.toFloat() }.toList()
        if (label.isNotEmpty() && values.isNotEmpty()) {
            categories += label
            rows += values
        }
    }
    val seriesCount = rows.maxOfOrNull { it.size } ?: 0
    val seriesValues = rows.map { row -> List(seriesCount) { i -> row.getOrNull(i) ?: 0f } }
    val seriesNames = (0 until seriesCount).map { i -> tokens.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "系列${i + 1}" }
    return AgentChartModel(agentNormalizeChartType(typeRaw), categories, seriesNames, seriesValues)
}

// 归一化图表类型到渲染器可识别的 "bar"/"line"/"pie"（兼容中英多种写法）。
internal fun agentNormalizeChartType(typeRaw: String): String = when (typeRaw.trim().lowercase()) {
    "line", "折线", "折线图", "line_chart", "linechart" -> "line"
    "pie", "饼", "饼图", "pie_chart", "piechart" -> "pie"
    else -> "bar"
}

private fun agentFormatChartValue(v: Float): String = if (v % 1f == 0f) v.toInt().toString() else v.toString()

internal fun agentChartReferenceSummary(title: String, model: AgentChartModel): String {
    val sb = StringBuilder()
    if (title.isNotBlank()) sb.append("Chart: ").append(title).append("\n")
    sb.append("Type: ").append(model.type).append("\n")
    if (model.seriesCount > 1) sb.append("Series: ").append(model.seriesNames.joinToString(", ")).append("\n")
    model.categories.forEachIndexed { i, cat ->
        val vals = model.seriesValues.getOrNull(i).orEmpty()
        sb.append(cat).append(": ").append(vals.joinToString(", ") { agentFormatChartValue(it) }).append("\n")
    }
    return sb.toString().trim()
}
