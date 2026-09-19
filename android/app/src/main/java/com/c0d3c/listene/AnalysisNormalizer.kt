package com.c0d3c.listene

import java.util.Locale

object AnalysisNormalizer {
    fun normalize(record: HistoryRecord, raw: AnalysisResult): AnalysisResult {
        val actualWrongInsights = buildActualWrongInsights(record)
        val actualWrongIndexes = actualWrongInsights.map { it.questionIndex }.toSet()
        val rawInsightByIndex = raw.wrongQuestionInsights
            .filter { it.questionIndex in actualWrongIndexes }
            .associateBy { it.questionIndex }
        val insights = actualWrongInsights.map { fallback ->
            mergeInsight(fallback, rawInsightByIndex[fallback.questionIndex])
        }
        val weakPoints = raw.weakPoints.ifEmpty { insights.map { it.mistakeType }.distinct() }
        val suggestions = raw.suggestions.ifEmpty {
            if (insights.isNotEmpty()) {
                listOf("先复听错题对应的信息句，再核对关键词、转折和因果关系。")
            } else {
                listOf("保持不看原文复听一遍，确认关键信息能稳定听出。")
            }
        }
        val summary = normalizeSummary(raw.summary, insights.size, raw.wrongQuestionInsights.isNotEmpty())
        val tags = (raw.diagnosisTags + weakPoints.map { deriveDiagnosisTag(it) } + insights.map { it.mistakeType })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
        val reviewItems = if (raw.reviewItems.isNotEmpty()) raw.reviewItems else buildFallbackReviewItems(record, raw, insights)
        val actions = if (raw.nextActions.isNotEmpty()) raw.nextActions else buildFallbackNextActions(record, raw, insights)
        val planTasks = if (raw.recommendedPlanTasks.isNotEmpty()) raw.recommendedPlanTasks else buildFallbackPlanTasks(record, raw, insights)
        return raw.copy(
            summary = summary,
            weakPoints = weakPoints,
            suggestions = suggestions,
            diagnosisTags = tags,
            wrongQuestionInsights = insights,
            nextActions = actions,
            reviewItems = reviewItems,
            recommendedPlanTasks = planTasks
        )
    }

    fun buildActualWrongInsights(record: HistoryRecord): List<WrongQuestionInsight> =
        record.content.questions.mapIndexedNotNull { index, question ->
            val selected = record.selectedAnswers[index] ?: return@mapIndexedNotNull null
            if (selected == question.correctAnswer) return@mapIndexedNotNull null
            val selectedText = question.options.getOrNull(selected).orEmpty()
            val correctText = question.options.getOrNull(question.correctAnswer).orEmpty()
            WrongQuestionInsight(
                questionIndex = index,
                question = question.questionText,
                selectedAnswer = selectedText,
                correctAnswer = correctText,
                mistakeType = inferMistakeType(question.questionText, question.explanation),
                insight = question.explanation.ifBlank { "把这道题对应的信息句重新听一遍，确认关键词、转折和因果关系。" },
                focusSentence = findFocusSentence(record.content.script, question)
            )
        }

    private fun mergeInsight(fallback: WrongQuestionInsight, raw: WrongQuestionInsight?): WrongQuestionInsight {
        if (raw == null) return fallback
        return fallback.copy(
            mistakeType = raw.mistakeType.ifBlank { fallback.mistakeType },
            insight = raw.insight.ifBlank { fallback.insight },
            focusSentence = raw.focusSentence.ifBlank { fallback.focusSentence },
            startMs = raw.startMs,
            endMs = raw.endMs,
            startRatio = raw.startRatio,
            endRatio = raw.endRatio
        )
    }

    private fun normalizeSummary(rawSummary: String, wrongCount: Int, hadRawWrongInsights: Boolean): String {
        val summary = rawSummary.trim()
        if (wrongCount <= 0) {
            if (hadRawWrongInsights || summaryLooksWrong(summary)) {
                return "本次答题没有发现错题，可以进入复听巩固和间隔复习。"
            }
            return summary.ifBlank { "本次答题没有发现错题，可以进入复听巩固和间隔复习。" }
        }
        if (summary.isBlank() || summaryLooksAllCorrect(summary)) {
            return "本次共有 ${wrongCount} 道错题，AI 已按实际答题结果生成错因复盘。"
        }
        return summary
    }

    private fun summaryLooksWrong(summary: String): Boolean {
        val text = summary.lowercase(Locale.ROOT)
        return listOf("错题", "答错", "错误", "wrong", "mistake", "incorrect")
            .any { it in text }
    }

    private fun summaryLooksAllCorrect(summary: String): Boolean {
        val text = summary.lowercase(Locale.ROOT)
        return listOf("全对", "全部正确", "完全正确", "没有错题", "无错题", "all correct", "no wrong")
            .any { it in text }
    }

    private fun buildFallbackReviewItems(
        record: HistoryRecord,
        result: AnalysisResult,
        insights: List<WrongQuestionInsight>
    ): List<AgentReviewItem> {
        val focusSentences = insights.map { it.focusSentence }.filter { it.isNotBlank() }
        val extractedSentences = WordSentenceExtractor.extractSentences(record.content.script)
            .filter { sentence -> focusSentences.none { NormalizeKeys.forSentence(it) == NormalizeKeys.forSentence(sentence) } }
            .take(4)
        val words = WordSentenceExtractor.extractWords(
            (result.weakPoints + result.suggestions + insights.map { it.question + " " + it.correctAnswer }).joinToString(" ")
        ).take(8)
        return (
            focusSentences.map { AgentReviewItem(it, "sentence", "错题相关信息句") } +
                extractedSentences.map { AgentReviewItem(it, "sentence", "适合精听拆句") } +
                words.map { AgentReviewItem(it, "word", "报告中反复出现的关键词") }
            ).distinctBy { it.itemType + NormalizeKeys.forSentence(it.text) }.take(12)
    }

    private fun buildFallbackNextActions(
        record: HistoryRecord,
        result: AnalysisResult,
        insights: List<WrongQuestionInsight>
    ): List<AgentNextAction> {
        val weak = result.weakPoints.firstOrNull()?.let { "重点处理：$it" }
            ?: insights.firstOrNull()?.let { "重点处理：${it.mistakeType}" }
            ?: "先复听巩固本次正确题的信息定位"
        val contentType = if (record.contentType == "article") "文章" else "对话"
        return listOf(
            AgentNextAction("复盘错题", weak, "review"),
            AgentNextAction("复听测试", "回到这套$contentType 听力，先不看原文复听，再核对错题", "relisten"),
            AgentNextAction("间隔复习", "把今天、3 天后、7 天后的复盘任务加入计划", "plan")
        )
    }

    private fun buildFallbackPlanTasks(
        record: HistoryRecord,
        result: AnalysisResult,
        insights: List<WrongQuestionInsight>
    ): List<AgentPlanTask> {
        val type = if (record.contentType == "article") "文章听力" else "对话听力"
        val weakTitle = result.diagnosisTags.firstOrNull()
            ?: result.weakPoints.firstOrNull()?.take(12)
            ?: insights.firstOrNull()?.mistakeType
            ?: "听力复盘"
        return listOf(
            AgentPlanTask("复听并订正：${record.content.title.take(18)}", type, offsetDays = 0, hour = 20, minute = 0),
            AgentPlanTask("弱项精听强化：$weakTitle", type, offsetDays = 1, hour = 20, minute = 20),
            AgentPlanTask("3 天后复听测试：${record.content.title.take(16)}", type, offsetDays = 3, hour = 20, minute = 0),
            AgentPlanTask("7 天后弱项回测：$weakTitle", type, offsetDays = 7, hour = 20, minute = 0)
        )
    }

    private fun deriveDiagnosisTag(text: String): String {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            "detail" in lower || "细节" in text -> "细节定位"
            "main" in lower || "主旨" in text -> "主旨理解"
            "infer" in lower || "推断" in text -> "推断能力"
            "vocab" in lower || "词" in text -> "词汇识别"
            "speed" in lower || "语速" in text -> "语速适应"
            "sentence" in lower || "句" in text -> "长句解析"
            else -> text.take(12)
        }
    }

    private fun inferMistakeType(questionText: String, explanation: String): String {
        val text = "$questionText $explanation".lowercase(Locale.ROOT)
        return when {
            "main" in text || "purpose" in text || "主旨" in questionText -> "主旨理解"
            "infer" in text || "imply" in text || "推断" in questionText -> "推断能力"
            "why" in text || "because" in text || "原因" in questionText -> "因果关系"
            "detail" in text || "when" in text || "where" in text || "细节" in questionText -> "细节定位"
            else -> "信息定位"
        }
    }

    private fun findFocusSentence(script: String, question: Question): String {
        val sentences = WordSentenceExtractor.extractSentences(script)
        if (sentences.isEmpty()) return ""
        val tokens = WordSentenceExtractor.extractWords(
            question.questionText + " " +
                question.options.getOrNull(question.correctAnswer).orEmpty() + " " +
                question.explanation
        ).take(12).toSet()
        if (tokens.isEmpty()) return sentences.first()
        return sentences.maxByOrNull { sentence ->
            WordSentenceExtractor.extractWords(sentence).count { it in tokens }
        }.orEmpty()
    }
}
