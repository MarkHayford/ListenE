package com.c0d3c.listene

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// 微元卡「作答表」：核对答案时把每道可判分题的（题面/用户作答/正解/对错）落成 JSON，
// 挂在携带该卡的聊天消息上（message.microAnswersJson）。这是「AI 分析练习卡作答」的数据来源——
// 此前作答只存在 UI SavedState，AI 只能看到题目与正解、看不到用户答了什么。
// 题面/正解/解析复用 microNodeReviewInfo 的降解口径；用户作答由下面的 microNodeUserAnswerText 文本化。

// 把用户对某个可判分微元的作答文本化（未作答/不适用 → 空串）。与各渲染器的选择态字段一一对应。
internal fun microNodeUserAnswerText(node: MicroNode, index: Int, answers: MicroAnswerState): String = when (node) {
    is MicroNode.Choice -> answers.choiceSelection[index]?.let { node.options.getOrNull(it) }.orEmpty()
    is MicroNode.AudioChoice -> answers.choiceSelection[index]?.let { node.options.getOrNull(it) }.orEmpty()
    is MicroNode.DialogueComplete -> answers.choiceSelection[index]?.let { node.options.getOrNull(it) }.orEmpty()
    is MicroNode.MinimalPair -> answers.choiceSelection[index]?.let { node.options.getOrNull(it) }.orEmpty()
    is MicroNode.IpaRead -> answers.choiceSelection[index]?.let { node.options.getOrNull(it) }.orEmpty()
    is MicroNode.OddOneOut -> answers.choiceSelection[index]?.let { node.items.getOrNull(it) }.orEmpty()
    is MicroNode.Input -> answers.inputText[index].orEmpty()
    is MicroNode.Dictation -> answers.inputText[index].orEmpty()
    is MicroNode.SpellingBee -> answers.inputText[index].orEmpty()
    is MicroNode.SentenceTransform -> answers.inputText[index].orEmpty()
    is MicroNode.Translate -> answers.inputText[index].orEmpty()
    is MicroNode.ErrorCorrection -> answers.inputText[index].orEmpty()
    is MicroNode.Tokens -> {
        val tokens = agentSplitErrorTokens(node.text)
        (answers.tokenSelection[index] ?: emptySet()).sorted().mapNotNull { tokens.getOrNull(it) }.joinToString("、")
    }
    is MicroNode.HighlightSpan -> {
        val tokens = agentSplitErrorTokens(node.text)
        (answers.tokenSelection[index] ?: emptySet()).sorted().mapNotNull { tokens.getOrNull(it) }.joinToString("、")
    }
    is MicroNode.WordScramble -> {
        val tiles = microScrambleTiles(node)
        (answers.scrambleOrder[index] ?: emptyList()).mapNotNull { tiles.getOrNull(it) }.joinToString("")
    }
    is MicroNode.Order -> (answers.orderSelection[index] ?: emptyList())
        .mapNotNull { node.items.getOrNull(it) }.joinToString(" | ")
    is MicroNode.ReorderParagraph -> (answers.reorderOrder[index] ?: emptyList())
        .mapNotNull { node.sentences.getOrNull(it) }.joinToString(" | ")
    is MicroNode.RankOrder -> (answers.rankOrder[index] ?: emptyList())
        .mapNotNull { node.items.getOrNull(it) }.joinToString(" | ")
    is MicroNode.Match -> (answers.matchSelection[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, right) -> node.pairs.getOrNull(i)?.let { "${it.left} → $right" } }.joinToString("；")
    is MicroNode.Categorize -> (answers.categorizeSelection[index] ?: emptyMap()).entries
        .joinToString("；") { (item, cat) -> "$item → $cat" }
    is MicroNode.SentenceDiagram -> (answers.diagramSelection[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, label) -> node.items.getOrNull(i)?.let { "${it.text}=$label" } }.joinToString("；")
    is MicroNode.TrueFalse -> (answers.trueFalseSelection[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, v) -> node.statements.getOrNull(i)?.let { "${it.text}=${if (v) "对" else "错"}" } }.joinToString("；")
    is MicroNode.Tfng -> (answers.tfngSelection[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, v) ->
            val label = when (v) { "true" -> "正确"; "false" -> "错误"; else -> "未提及" }
            node.statements.getOrNull(i)?.let { "${it.text}=$label" }
        }.joinToString("；")
    is MicroNode.ClozeDrag -> microIndexedFillText(answers.clozeDragFill[index])
    is MicroNode.ListenFill -> microIndexedFillText(answers.listenFillFill[index])
    is MicroNode.OpenCloze -> microIndexedFillText(answers.openClozeFill[index])
    is MicroNode.ListenCloze -> microIndexedFillText(answers.listenClozeFill[index])
    is MicroNode.NoteComplete -> microIndexedFillText(answers.noteCompleteFill[index])
    is MicroNode.SummaryComplete -> microIndexedFillText(answers.summaryFill[index])
    is MicroNode.FillTable -> microIndexedFillText(answers.fillTableFill[index], separator = "；")
    is MicroNode.ClozeSelect -> (answers.clozeSelectChoice[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (b, o) -> node.blanks.getOrNull(b)?.options?.getOrNull(o) }.joinToString(" | ")
    is MicroNode.WordFormation -> (answers.wordFormFill[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, word) -> node.items.getOrNull(i)?.let { "${it.base} → $word" } }.joinToString("；")
    is MicroNode.StressMark -> answers.stressSelection[index]?.let { node.syllables.getOrNull(it) }.orEmpty()
    is MicroNode.WordSearch -> (answers.wordSearchFound[index] ?: emptySet()).joinToString("、")
    is MicroNode.Hangman -> (answers.hangmanGuessed[index] ?: emptySet()).sorted()
        .joinToString("、").let { if (it.isBlank()) "" else "已猜字母：$it" }
    is MicroNode.ProofParagraph -> (answers.proofEdits[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, edited) ->
            val line = node.lines.getOrNull(i) ?: return@mapNotNull null
            if (edited.trim() == line.text.trim()) null else "${line.text} → $edited"
        }.joinToString("；")
    is MicroNode.MatchHeadings -> (answers.matchHeadingsSelection[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, h) -> node.paragraphs.getOrNull(i)?.let { "${it.label.ifBlank { "段${i + 1}" }} → $h" } }.joinToString("；")
    is MicroNode.MatchInfo -> (answers.matchInfoSelection[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, label) -> node.statements.getOrNull(i)?.let { "${it.text} → $label" } }.joinToString("；")
    is MicroNode.MapLabel -> (answers.mapLabelSelection[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, label) -> node.items.getOrNull(i)?.let { "${it.text} → $label" } }.joinToString("；")
    is MicroNode.MatchSentenceEndings -> (answers.sentenceEndingSelection[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, ending) -> node.stems.getOrNull(i)?.let { "${it.text} → $ending" } }.joinToString("；")
    is MicroNode.ShortAnswer -> (answers.shortAnswerFill[index] ?: emptyMap()).entries.sortedBy { it.key }
        .mapNotNull { (i, text) -> node.questions.getOrNull(i)?.let { "${it.q} → $text" } }.joinToString("；")
    else -> ""
}

private fun microIndexedFillText(filled: Map<Int, String>?, separator: String = " | "): String =
    (filled ?: emptyMap()).entries.sortedBy { it.key }
        .joinToString(separator) { (i, word) -> word.ifBlank { "空${i + 1}未填" } }

// 整卡作答表 JSON：{title,total,correct,items:[{type,prompt,userAnswer,correctAnswer,correct,explanation}]}。
// 只包含能抽出题面+正解的可判分微元（与错题本同覆盖面）；correctness 传 MicroCardView 的 nodeCorrect。
internal fun microCardAnswerSheetJson(
    card: MicroCard,
    gradable: List<IndexedValue<MicroNode>>,
    answers: MicroAnswerState,
    correctness: (Int, MicroNode) -> Boolean
): JsonObject? {
    val items = gradable.mapNotNull { (index, node) ->
        val info = microNodeReviewInfo(node) ?: return@mapNotNull null
        val correct = correctness(index, node)
        buildJsonObject {
            put("type", info.componentType)
            put("prompt", info.prompt.take(600))
            put("userAnswer", microNodeUserAnswerText(node, index, answers).take(400))
            put("correctAnswer", info.answer.take(400))
            put("correct", correct)
            if (info.explanation.isNotBlank()) put("explanation", info.explanation.take(600))
        }
    }
    if (items.isEmpty()) return null
    val correctCount = gradable.count { (index, node) -> correctness(index, node) }
    return buildJsonObject {
        put("title", card.title.take(120))
        put("total", gradable.size)
        put("correct", correctCount)
        put("gradedAt", System.currentTimeMillis())
        putJsonArray("items") { items.forEach { add(it) } }
    }
}
