package com.c0d3c.listene

// 微元卡导出降解：把微元节点抽成「题干/选项/答案/解析」与「材料段落」两类，
// 供聊天历史摘要（microCardExportableSummary）与题库 docx 导出共用一套口径。
// 材料/运行时节点（音频、逐句、进度、图表、跟读等）不参与导出。

internal data class MicroExportQuestion(
    val prompt: String,
    val options: List<String> = emptyList(),
    val answer: String = "",
    val explanation: String = ""
)

// 展示性材料段落（正文/短文/词表）；非材料节点返回 null。
internal fun microNodeExportSection(node: MicroNode): String? = when (node) {
    is MicroNode.Text -> node.text.trim().ifBlank { null }
    is MicroNode.Passage -> node.text.trim().ifBlank { null }
    is MicroNode.Flashcard -> node.cards
        .filter { it.front.isNotBlank() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n") { "${it.front} - ${it.back}" }
    else -> null
}

// 可判分/可导出题目节点 → 导出题面；覆盖不了的类型走 microNodeReviewInfo 兜底，仍不行返回 null。
internal fun microNodeExportQuestion(node: MicroNode): MicroExportQuestion? {
    fun of(prompt: String, options: List<String> = emptyList(), answer: String = "", explanation: String = ""): MicroExportQuestion? =
        prompt.trim().ifBlank { null }?.let { MicroExportQuestion(it, options, answer.trim(), explanation.trim()) }
    return when (node) {
        is MicroNode.Tokens -> of("点选找错：${node.text}", answer = node.correct, explanation = node.explanation)
        is MicroNode.Match -> of(
            node.prompt.ifBlank { "连线配对" },
            answer = node.pairs.joinToString("；") { "${it.left} → ${it.right}" },
            explanation = node.explanation
        )
        is MicroNode.Categorize -> of(
            node.prompt.ifBlank { "归类" },
            answer = node.categories.joinToString("；") { "${it.name}: ${it.items.joinToString("、")}" },
            explanation = node.explanation
        )
        is MicroNode.OpenCloze -> of(
            listOf(node.prompt.trim(), node.text.trim()).filter { it.isNotBlank() }.joinToString("\n"),
            answer = node.answers.joinToString(" | ")
        )
        is MicroNode.ClozeSelect -> of(
            node.text,
            answer = node.blanks.joinToString(" | ") { it.answer },
            options = node.blanks.flatMap { it.options }.distinct()
        )
        is MicroNode.ClozeDrag -> of(node.text, options = node.bank, answer = node.answers.joinToString(" | "))
        is MicroNode.ListenFill -> of(
            "听力填空：${node.text}",
            options = node.bank,
            answer = node.answers.joinToString(" | ")
        )
        is MicroNode.ListenCloze -> of("听力填空（打字）：${node.text}", answer = node.answers.joinToString(" | "))
        is MicroNode.HighlightSpan -> of(
            "${node.prompt.ifBlank { "篇章框选" }}\n${node.text}",
            answer = node.answers.joinToString("、"),
            explanation = node.explanation
        )
        is MicroNode.WordFormation -> of(
            node.prompt.ifBlank { "词形转换" },
            answer = node.items.joinToString("；") { "${it.base}(${it.target}) → ${it.answer}" }
        )
        is MicroNode.ProofParagraph -> of(
            node.prompt.ifBlank { "短文改错" } + "\n" + node.lines.joinToString("\n") { it.text },
            answer = node.lines
                .filter { it.answer.isNotBlank() && it.answer.trim() != it.text.trim() }
                .joinToString("；") { "${it.text} → ${it.answer}" }
        )
        is MicroNode.Order -> of(
            node.prompt.ifBlank { "连词成句：${node.items.joinToString(" / ")}" },
            answer = node.answer,
            explanation = node.explanation
        )
        is MicroNode.ReorderParagraph -> of(
            node.prompt.ifBlank { "语篇排序" },
            answer = node.sentences.joinToString(" | ")
        )
        is MicroNode.RankOrder -> of(
            node.prompt.ifBlank { "程度排序（${node.from} → ${node.to}）" },
            answer = node.items.joinToString(" | "),
            explanation = node.explanation
        )
        is MicroNode.FillTable -> of(
            node.title.ifBlank { "表格填空" } + "\n" + node.headers.joinToString(" | "),
            answer = node.blanks.joinToString("；") { coord ->
                node.rows.getOrNull(coord.row)?.getOrNull(coord.col).orEmpty()
            }
        )
        is MicroNode.SentenceDiagram -> of(
            node.prompt.ifBlank { "句子成分：${node.sentence}" },
            answer = node.items.joinToString("；") { "${it.text}=${it.label}" }
        )
        is MicroNode.WordSearch -> of(
            node.prompt.ifBlank { "单词找词" },
            answer = node.words.joinToString("、")
        )
        else -> microNodeReviewInfo(node)?.let { info ->
            MicroExportQuestion(info.prompt, info.options, info.answer, info.explanation)
        }
    }
}

// 微元卡是否有可导出题目（题库导出资格）。
internal fun microCardCanExportQuestionBank(card: MicroCard): Boolean =
    card.nodes.any { microNodeExportQuestion(it) != null }
