package com.c0d3c.listene

import android.speech.tts.TextToSpeech
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import java.util.Locale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 材料运行时类微元（QuestionPreview/Feedback 等）需要的运行时上下文，经 CompositionLocal 注入，
// 让微元节点保持「纯数据」的同时仍能落库 / 触发 AI 复盘。非材料语境（实验室/聊天整卡）默认为 null，渲染优雅降级。
internal val LocalAgentMicroRecord = staticCompositionLocalOf<HistoryRecord?> { null }
internal val LocalAgentMicroViewModel = staticCompositionLocalOf<ListeningViewModel?> { null }
internal val LocalAgentMicroOnRequestAiReview = staticCompositionLocalOf<((HistoryRecord) -> Unit)?> { null }
// 核对答案时向宿主上报「作答表」JSON（microCardAnswerSheetJson），聊天场景把它挂到消息上供 AI 分析；
// 非聊天场景（实验室/卡片库）为 null，静默跳过。
internal val LocalAgentMicroOnGraded = staticCompositionLocalOf<((String) -> Unit)?> { null }
// 卡内「交给 AI 分析」按钮：点按时携带最新作答表请求逐题分析（与听力卡「AI 复盘」对齐）。
internal val LocalAgentMicroOnRequestSheetAnalysis = staticCompositionLocalOf<((String) -> Unit)?> { null }

// 微元卡渲染器（spike）：把 AI 实时组合出的扁平微元列表按类型渲染，挂统一“核对答案”。
// 复用现有原子（AgentTextAction / 反馈 / KeyValueRow / InlineNotice）与改错卡判分逻辑。
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MicroCardView(card: MicroCard, instanceKey: String) {
    if (card.nodes.isEmpty()) return
    var revealed by rememberSaveable(instanceKey) { mutableStateOf(false) }
    // 错因回灌：本次「核对答案」是否把答错项写进了错题本（用于反馈栏文案）。
    var recordedToReview by rememberSaveable(instanceKey) { mutableStateOf(false) }
    // 全部答题过程状态：单一 Saveable holder（旋转/深色切换/进程回收不丢答案），
    // 各字段含义见 MicroAnswerState；下面的局部别名保持原有代码零改动。
    val answers = rememberMicroAnswerState(instanceKey)
    val choiceSelection = answers.choiceSelection
    val tokenSelection = answers.tokenSelection
    val inputText = answers.inputText
    val orderSelection = answers.orderSelection
    val matchSelection = answers.matchSelection
    val categorizeSelection = answers.categorizeSelection
    // 听写/听辨用的端上 TTS 朗读器（无需权限/依赖）。
    val speak = rememberMicroSpeaker()
    // 错因回灌到错题本用（best-effort，静默）。
    val reviewCtx = LocalContext.current
    val reviewScope = rememberCoroutineScope()
    val onGraded = LocalAgentMicroOnGraded.current
    val onRequestSheetAnalysis = LocalAgentMicroOnRequestSheetAnalysis.current
    val clozeDragFill = answers.clozeDragFill
    val diagramSelection = answers.diagramSelection
    val scrambleOrder = answers.scrambleOrder
    val trueFalseSelection = answers.trueFalseSelection
    val tfngSelection = answers.tfngSelection
    val fillTableFill = answers.fillTableFill
    val clozeSelectChoice = answers.clozeSelectChoice
    val listenFillFill = answers.listenFillFill
    val wordFormFill = answers.wordFormFill
    val openClozeFill = answers.openClozeFill
    val listenClozeFill = answers.listenClozeFill
    val wordSearchFound = answers.wordSearchFound
    val wordSearchAnchor = answers.wordSearchAnchor
    val hangmanGuessed = answers.hangmanGuessed
    val proofEdits = answers.proofEdits
    val stressSelection = answers.stressSelection
    val reorderOrder = answers.reorderOrder
    val rankOrder = answers.rankOrder
    val matchHeadingsSelection = answers.matchHeadingsSelection
    val matchInfoSelection = answers.matchInfoSelection
    val mapLabelSelection = answers.mapLabelSelection
    val sentenceEndingSelection = answers.sentenceEndingSelection
    val noteCompleteFill = answers.noteCompleteFill
    val summaryFill = answers.summaryFill
    val shortAnswerFill = answers.shortAnswerFill

    val gradable = card.nodes.withIndex().filter {
        it.value is MicroNode.Choice || it.value is MicroNode.Tokens || it.value is MicroNode.Input ||
            it.value is MicroNode.Order || it.value is MicroNode.Match || it.value is MicroNode.Categorize ||
            it.value is MicroNode.Dictation || it.value is MicroNode.AudioChoice || it.value is MicroNode.ClozeDrag ||
            it.value is MicroNode.HighlightSpan || it.value is MicroNode.SentenceDiagram || it.value is MicroNode.WordScramble ||
            it.value is MicroNode.TrueFalse || it.value is MicroNode.Tfng || it.value is MicroNode.FillTable || it.value is MicroNode.ClozeSelect ||
            it.value is MicroNode.ListenFill || it.value is MicroNode.WordFormation || it.value is MicroNode.StressMark ||
            it.value is MicroNode.ReorderParagraph || it.value is MicroNode.OddOneOut ||
            it.value is MicroNode.RankOrder || it.value is MicroNode.SpellingBee ||
            it.value is MicroNode.SentenceTransform || it.value is MicroNode.OpenCloze ||
            it.value is MicroNode.Translate || it.value is MicroNode.ListenCloze ||
            it.value is MicroNode.ErrorCorrection || it.value is MicroNode.DialogueComplete ||
            it.value is MicroNode.WordSearch || it.value is MicroNode.Hangman ||
            it.value is MicroNode.ProofParagraph ||
            it.value is MicroNode.MatchHeadings || it.value is MicroNode.MatchInfo ||
            it.value is MicroNode.MinimalPair || it.value is MicroNode.IpaRead ||
            it.value is MicroNode.MapLabel || it.value is MicroNode.NoteComplete ||
            it.value is MicroNode.MatchSentenceEndings || it.value is MicroNode.SummaryComplete ||
            it.value is MicroNode.ShortAnswer
    }
    fun nodeCorrect(index: Int, node: MicroNode): Boolean = when (node) {
        is MicroNode.Choice -> microChoiceCorrect(choiceSelection[index], node.options, node.answer)
        is MicroNode.Input -> microInputCorrect(inputText[index].orEmpty(), node.answer)
        is MicroNode.Dictation -> microInputCorrect(inputText[index].orEmpty(), node.text)
        is MicroNode.AudioChoice -> microChoiceCorrect(choiceSelection[index], node.options, node.answer)
        is MicroNode.ClozeDrag -> microClozeDragCorrect(clozeDragFill[index] ?: emptyMap(), node.answers)
        is MicroNode.Tokens -> {
            val errors = microTokensErrorIndices(node)
            errors.isNotEmpty() && (tokenSelection[index] ?: emptySet()) == errors
        }
        is MicroNode.HighlightSpan -> {
            val target = microHighlightTargetIndices(node)
            target.isNotEmpty() && (tokenSelection[index] ?: emptySet()) == target
        }
        is MicroNode.SentenceDiagram -> microDiagramCorrect(diagramSelection[index] ?: emptyMap(), node.items)
        is MicroNode.WordScramble -> {
            val tiles = microScrambleTiles(node)
            val assembled = (scrambleOrder[index] ?: emptyList()).map { tiles.getOrElse(it) { "" } }
            microWordScrambleCorrect(assembled, node.word)
        }
        is MicroNode.TrueFalse -> microTrueFalseCorrect(trueFalseSelection[index] ?: emptyMap(), node.statements)
        is MicroNode.Tfng -> microTfngCorrect(tfngSelection[index] ?: emptyMap(), node.statements)
        is MicroNode.FillTable -> microFillTableCorrect(fillTableFill[index] ?: emptyMap(), node)
        is MicroNode.ClozeSelect -> microClozeSelectCorrect(clozeSelectChoice[index] ?: emptyMap(), node.blanks)
        is MicroNode.ListenFill -> microClozeDragCorrect(listenFillFill[index] ?: emptyMap(), node.answers)
        is MicroNode.WordFormation -> microWordFormationCorrect(wordFormFill[index] ?: emptyMap(), node.items)
        is MicroNode.StressMark -> microStressMarkCorrect(stressSelection[index], node)
        is MicroNode.ReorderParagraph -> {
            val sequence = (reorderOrder[index] ?: emptyList()).map { node.sentences.getOrElse(it) { "" } }
            microReorderParagraphCorrect(sequence, node.sentences)
        }
        is MicroNode.OddOneOut -> microChoiceCorrect(choiceSelection[index], node.items, node.answer)
        is MicroNode.RankOrder -> {
            val sequence = (rankOrder[index] ?: emptyList()).map { node.items.getOrElse(it) { "" } }
            microRankOrderCorrect(sequence, node.items)
        }
        is MicroNode.SpellingBee -> microSpellingCorrect(inputText[index].orEmpty(), node.word)
        is MicroNode.SentenceTransform -> microSentenceTransformCorrect(inputText[index].orEmpty(), node.answer, node.accept)
        is MicroNode.OpenCloze -> microOpenClozeCorrect(openClozeFill[index] ?: emptyMap(), node.answers)
        is MicroNode.Translate -> microTranslateCorrect(inputText[index].orEmpty(), node.answer, node.accept)
        is MicroNode.ListenCloze -> microOpenClozeCorrect(listenClozeFill[index] ?: emptyMap(), node.answers)
        is MicroNode.ErrorCorrection -> microSentenceTransformCorrect(inputText[index].orEmpty(), node.answer, node.accept)
        is MicroNode.DialogueComplete -> microChoiceCorrect(choiceSelection[index], node.options, node.answer)
        is MicroNode.WordSearch -> microWordSearchCorrect(wordSearchFound[index] ?: emptySet(), node.words)
        is MicroNode.Hangman -> microHangmanCorrect(hangmanGuessed[index] ?: emptySet(), node.word, node.maxWrong)
        is MicroNode.ProofParagraph -> microProofParagraphCorrect(proofEdits[index] ?: emptyMap(), node.lines)
        is MicroNode.Order -> {
            val sequence = (orderSelection[index] ?: emptyList()).map { node.items.getOrElse(it) { "" } }
            microOrderCorrect(sequence, node.answer)
        }
        is MicroNode.Match -> microMatchCorrect(matchSelection[index] ?: emptyMap(), node.pairs)
        is MicroNode.Categorize -> microCategorizeCorrect(categorizeSelection[index] ?: emptyMap(), node.categories)
        is MicroNode.MatchHeadings -> microMatchHeadingsCorrect(matchHeadingsSelection[index] ?: emptyMap(), node.paragraphs)
        is MicroNode.MatchInfo -> microMatchInfoCorrect(matchInfoSelection[index] ?: emptyMap(), node.statements)
        is MicroNode.MinimalPair -> microChoiceCorrect(choiceSelection[index], node.options, node.answer)
        is MicroNode.IpaRead -> microChoiceCorrect(choiceSelection[index], node.options, node.answer)
        is MicroNode.MapLabel -> microMapLabelCorrect(mapLabelSelection[index] ?: emptyMap(), node.items)
        is MicroNode.NoteComplete -> microOpenClozeCorrect(noteCompleteFill[index] ?: emptyMap(), node.answers)
        is MicroNode.MatchSentenceEndings -> microSentenceEndingsCorrect(sentenceEndingSelection[index] ?: emptyMap(), node.stems)
        is MicroNode.SummaryComplete -> microClozeDragCorrect(summaryFill[index] ?: emptyMap(), node.answers)
        is MicroNode.ShortAnswer -> microShortAnswerCorrect(shortAnswerFill[index] ?: emptyMap(), node.questions)
        else -> true
    }
    val allCorrect = gradable.isNotEmpty() && gradable.all { nodeCorrect(it.index, it.value) }
    val anyAnswered = choiceSelection.isNotEmpty() ||
        tokenSelection.values.any { it.isNotEmpty() } ||
        inputText.values.any { it.isNotBlank() } ||
        orderSelection.values.any { it.isNotEmpty() } ||
        matchSelection.values.any { it.isNotEmpty() } ||
        categorizeSelection.values.any { it.isNotEmpty() } ||
        clozeDragFill.values.any { it.isNotEmpty() } ||
        diagramSelection.values.any { it.isNotEmpty() } ||
        scrambleOrder.values.any { it.isNotEmpty() } ||
        trueFalseSelection.values.any { it.isNotEmpty() } ||
        tfngSelection.values.any { it.isNotEmpty() } ||
        fillTableFill.values.any { it.isNotEmpty() } ||
        clozeSelectChoice.values.any { it.isNotEmpty() } ||
        listenFillFill.values.any { it.isNotEmpty() } ||
        wordFormFill.values.any { it.isNotEmpty() } ||
        stressSelection.isNotEmpty() ||
        reorderOrder.values.any { it.isNotEmpty() } ||
        rankOrder.values.any { it.isNotEmpty() } ||
        openClozeFill.values.any { it.isNotEmpty() } ||
        listenClozeFill.values.any { it.isNotEmpty() } ||
        wordSearchFound.values.any { it.isNotEmpty() } ||
        hangmanGuessed.values.any { it.isNotEmpty() } ||
        proofEdits.values.any { it.isNotEmpty() } ||
        matchHeadingsSelection.values.any { it.isNotEmpty() } ||
        matchInfoSelection.values.any { it.isNotEmpty() } ||
        mapLabelSelection.values.any { it.isNotEmpty() } ||
        sentenceEndingSelection.values.any { it.isNotEmpty() } ||
        noteCompleteFill.values.any { it.isNotEmpty() } ||
        summaryFill.values.any { it.isNotEmpty() } ||
        shortAnswerFill.values.any { it.isNotEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        if (card.title.isNotBlank()) {
            Text(card.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        card.nodes.forEachIndexed { index, node ->
            when (node) {
                is MicroNode.Text -> Text(
                    node.text,
                    style = if (node.role == "title") MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    color = if (node.role == "hint") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (node.role == "title") FontWeight.Bold else FontWeight.Normal
                )
                is MicroNode.Passage -> AgentCardInlineNotice(node.text)
                is MicroNode.Audio -> {
                    val src = node.src.trim()
                    if (src.startsWith("http", ignoreCase = true) || src.startsWith("/") || src.startsWith("file", ignoreCase = true)) {
                        AgentAudioPlayer(src) // 复用现有 ExoPlayer 播放器：真实 URL/路径直接播放
                    } else {
                        AgentCardInlineNotice("🔊 ${src.ifBlank { "audio" }}")
                    }
                }
                is MicroNode.Reveal -> MicroRevealView(node)
                is MicroNode.Flashcard -> MicroFlashcardView(node)
                is MicroNode.Progress -> MicroProgressView(node)
                is MicroNode.Table -> MicroTableView(node)
                is MicroNode.Timeline -> MicroTimelineView(node)
                is MicroNode.SentenceDiagram -> MicroSentenceDiagramView(
                    node = node,
                    selected = diagramSelection[index] ?: emptyMap(),
                    revealed = revealed,
                    onSelect = { itemIndex, label ->
                        if (!revealed) {
                            val current = diagramSelection[index] ?: emptyMap()
                            diagramSelection[index] = current + (itemIndex to label)
                        }
                    }
                )
                is MicroNode.WordScramble -> MicroWordScrambleView(
                    node = node,
                    selected = scrambleOrder[index] ?: emptyList(),
                    revealed = revealed,
                    onAdd = { tileIndex ->
                        if (!revealed) {
                            val current = scrambleOrder[index] ?: emptyList()
                            if (tileIndex !in current) scrambleOrder[index] = current + tileIndex
                        }
                    },
                    onRemove = { pos ->
                        if (!revealed) {
                            val current = scrambleOrder[index] ?: emptyList()
                            if (pos in current.indices) scrambleOrder[index] = current.toMutableList().also { it.removeAt(pos) }
                        }
                    }
                )
                is MicroNode.TrueFalse -> MicroTrueFalseView(
                    node = node,
                    selected = trueFalseSelection[index] ?: emptyMap(),
                    revealed = revealed,
                    onJudge = { stmtIndex, value ->
                        if (!revealed) {
                            val current = trueFalseSelection[index] ?: emptyMap()
                            trueFalseSelection[index] = current + (stmtIndex to value)
                        }
                    }
                )
                is MicroNode.Tfng -> MicroTfngView(
                    node = node,
                    selected = tfngSelection[index] ?: emptyMap(),
                    revealed = revealed,
                    onJudge = { stmtIndex, value ->
                        if (!revealed) {
                            val current = tfngSelection[index] ?: emptyMap()
                            tfngSelection[index] = current + (stmtIndex to value)
                        }
                    }
                )
                is MicroNode.MatchHeadings -> MicroMatchHeadingsView(
                    node = node,
                    selected = matchHeadingsSelection[index] ?: emptyMap(),
                    revealed = revealed,
                    onSelect = { paraIndex, heading ->
                        if (!revealed) {
                            val current = matchHeadingsSelection[index] ?: emptyMap()
                            matchHeadingsSelection[index] = current + (paraIndex to heading)
                        }
                    }
                )
                is MicroNode.MatchInfo -> MicroMatchInfoView(
                    node = node,
                    selected = matchInfoSelection[index] ?: emptyMap(),
                    revealed = revealed,
                    onSelect = { stmtIndex, option ->
                        if (!revealed) {
                            val current = matchInfoSelection[index] ?: emptyMap()
                            matchInfoSelection[index] = current + (stmtIndex to option)
                        }
                    }
                )
                is MicroNode.FillTable -> MicroFillTableView(
                    node = node,
                    filled = fillTableFill[index] ?: emptyMap(),
                    revealed = revealed,
                    onSet = { blankIndex, word ->
                        if (!revealed) {
                            val current = fillTableFill[index] ?: emptyMap()
                            fillTableFill[index] = current + (blankIndex to word)
                        }
                    },
                    onClear = { blankIndex ->
                        if (!revealed) {
                            val current = fillTableFill[index] ?: emptyMap()
                            fillTableFill[index] = current - blankIndex
                        }
                    }
                )
                is MicroNode.ClozeSelect -> MicroClozeSelectView(
                    node = node,
                    selected = clozeSelectChoice[index] ?: emptyMap(),
                    revealed = revealed,
                    onSelect = { blankIndex, optionIndex ->
                        if (!revealed) {
                            val current = clozeSelectChoice[index] ?: emptyMap()
                            clozeSelectChoice[index] = current + (blankIndex to optionIndex)
                        }
                    }
                )
                is MicroNode.ListenFill -> MicroListenFillView(
                    node = node,
                    speak = speak,
                    filled = listenFillFill[index] ?: emptyMap(),
                    revealed = revealed,
                    onSet = { blankIndex, word ->
                        if (!revealed) {
                            val current = listenFillFill[index] ?: emptyMap()
                            listenFillFill[index] = current + (blankIndex to word)
                        }
                    },
                    onClear = { blankIndex ->
                        if (!revealed) {
                            val current = listenFillFill[index] ?: emptyMap()
                            listenFillFill[index] = current - blankIndex
                        }
                    }
                )
                is MicroNode.WordFormation -> MicroWordFormationView(
                    node = node,
                    filled = wordFormFill[index] ?: emptyMap(),
                    revealed = revealed,
                    onSet = { itemIndex, word ->
                        if (!revealed) {
                            val current = wordFormFill[index] ?: emptyMap()
                            wordFormFill[index] = current + (itemIndex to word)
                        }
                    },
                    onClear = { itemIndex ->
                        if (!revealed) {
                            val current = wordFormFill[index] ?: emptyMap()
                            wordFormFill[index] = current - itemIndex
                        }
                    }
                )
                is MicroNode.StressMark -> MicroStressMarkView(
                    node = node,
                    selected = stressSelection[index],
                    revealed = revealed,
                    onSelect = { syllableIndex ->
                        if (!revealed) stressSelection[index] = syllableIndex
                    }
                )
                is MicroNode.ReorderParagraph -> MicroReorderParagraphView(
                    node = node,
                    selected = reorderOrder[index] ?: emptyList(),
                    revealed = revealed,
                    onAdd = { sentenceIndex ->
                        if (!revealed) {
                            val current = reorderOrder[index] ?: emptyList()
                            if (sentenceIndex !in current) reorderOrder[index] = current + sentenceIndex
                        }
                    },
                    onRemove = { pos ->
                        if (!revealed) {
                            val current = reorderOrder[index] ?: emptyList()
                            if (pos in current.indices) reorderOrder[index] = current.toMutableList().also { it.removeAt(pos) }
                        }
                    }
                )
                is MicroNode.OddOneOut -> MicroOddOneOutView(
                    node = node,
                    selected = choiceSelection[index],
                    revealed = revealed,
                    onSelect = { if (!revealed) choiceSelection[index] = it }
                )
                is MicroNode.RankOrder -> MicroRankOrderView(
                    node = node,
                    selected = rankOrder[index] ?: emptyList(),
                    revealed = revealed,
                    onAdd = { itemIndex ->
                        if (!revealed) {
                            val current = rankOrder[index] ?: emptyList()
                            if (itemIndex !in current) rankOrder[index] = current + itemIndex
                        }
                    },
                    onRemove = { pos ->
                        if (!revealed) {
                            val current = rankOrder[index] ?: emptyList()
                            if (pos in current.indices) rankOrder[index] = current.toMutableList().also { it.removeAt(pos) }
                        }
                    }
                )
                is MicroNode.SentenceTranscript -> AgentSentenceTranscriptSurface(
                    audioUrl = node.audioUrl.ifBlank { null },
                    groups = node.groups,
                    instanceKey = "${instanceKey}_st_$index"
                )
                is MicroNode.QuestionPreview -> AgentQuestionPreviewSurface(
                    fallbackText = node.fallbackText,
                    instanceKey = "${instanceKey}_qp_$index"
                )
                is MicroNode.Feedback -> AgentFeedbackSurface(fallbackText = node.fallbackText)
                is MicroNode.Chart -> AgentChartSurface(
                    title = node.title,
                    model = remember(node) { node.toChartModel() }
                )
                is MicroNode.Writing -> MicroWritingView(
                    node = node,
                    instanceKey = "${instanceKey}_writing_$index"
                )
                is MicroNode.Choice -> MicroChoiceView(
                    node = node,
                    selected = choiceSelection[index],
                    revealed = revealed,
                    onSelect = { if (!revealed) choiceSelection[index] = it }
                )
                is MicroNode.Input -> MicroInputView(
                    node = node,
                    value = inputText[index].orEmpty(),
                    revealed = revealed,
                    onChange = { if (!revealed) inputText[index] = it }
                )
                is MicroNode.Tokens -> MicroTokensView(
                    node = node,
                    selected = tokenSelection[index] ?: emptySet(),
                    revealed = revealed,
                    onToggle = { tokenIndex ->
                        if (!revealed) {
                            val current = tokenSelection[index] ?: emptySet()
                            tokenSelection[index] = if (tokenIndex in current) current - tokenIndex else current + tokenIndex
                        }
                    }
                )
                is MicroNode.Order -> MicroOrderView(
                    node = node,
                    selected = orderSelection[index] ?: emptyList(),
                    revealed = revealed,
                    onAdd = { itemIndex ->
                        if (!revealed) {
                            val current = orderSelection[index] ?: emptyList()
                            if (itemIndex !in current) orderSelection[index] = current + itemIndex
                        }
                    },
                    onRemove = { pos ->
                        if (!revealed) {
                            val current = orderSelection[index] ?: emptyList()
                            if (pos in current.indices) orderSelection[index] = current.toMutableList().also { it.removeAt(pos) }
                        }
                    }
                )
                is MicroNode.Match -> MicroMatchView(
                    node = node,
                    selected = matchSelection[index] ?: emptyMap(),
                    revealed = revealed,
                    onSelect = { leftIndex, rightValue ->
                        if (!revealed) {
                            val current = matchSelection[index] ?: emptyMap()
                            matchSelection[index] = current + (leftIndex to rightValue)
                        }
                    }
                )
                is MicroNode.Categorize -> MicroCategorizeView(
                    node = node,
                    selected = categorizeSelection[index] ?: emptyMap(),
                    revealed = revealed,
                    onSelect = { item, category ->
                        if (!revealed) {
                            val current = categorizeSelection[index] ?: emptyMap()
                            categorizeSelection[index] = current + (item to category)
                        }
                    }
                )
                is MicroNode.Dictation -> MicroDictationView(
                    node = node,
                    speak = speak,
                    value = inputText[index].orEmpty(),
                    revealed = revealed,
                    onChange = { if (!revealed) inputText[index] = it }
                )
                is MicroNode.SpellingBee -> MicroSpellingBeeView(
                    node = node,
                    speak = speak,
                    value = inputText[index].orEmpty(),
                    revealed = revealed,
                    onChange = { if (!revealed) inputText[index] = it }
                )
                is MicroNode.SentenceTransform -> MicroSentenceTransformView(
                    node = node,
                    value = inputText[index].orEmpty(),
                    revealed = revealed,
                    onChange = { if (!revealed) inputText[index] = it }
                )
                is MicroNode.OpenCloze -> MicroOpenClozeView(
                    node = node,
                    filled = openClozeFill[index] ?: emptyMap(),
                    revealed = revealed,
                    onSet = { blankIndex, text ->
                        if (!revealed) {
                            val current = openClozeFill[index] ?: emptyMap()
                            openClozeFill[index] = current + (blankIndex to text)
                        }
                    }
                )
                is MicroNode.Translate -> MicroTranslateView(
                    node = node,
                    value = inputText[index].orEmpty(),
                    revealed = revealed,
                    onChange = { if (!revealed) inputText[index] = it }
                )
                is MicroNode.ListenCloze -> MicroListenClozeView(
                    node = node,
                    speak = speak,
                    filled = listenClozeFill[index] ?: emptyMap(),
                    revealed = revealed,
                    onSet = { blankIndex, text ->
                        if (!revealed) {
                            val current = listenClozeFill[index] ?: emptyMap()
                            listenClozeFill[index] = current + (blankIndex to text)
                        }
                    }
                )
                is MicroNode.ErrorCorrection -> MicroErrorCorrectionView(
                    node = node,
                    value = inputText[index].orEmpty(),
                    revealed = revealed,
                    onChange = { if (!revealed) inputText[index] = it }
                )
                is MicroNode.DialogueComplete -> MicroDialogueCompleteView(
                    node = node,
                    selected = choiceSelection[index],
                    revealed = revealed,
                    onSelect = { if (!revealed) choiceSelection[index] = it }
                )
                is MicroNode.WordSearch -> MicroWordSearchView(
                    node = node,
                    found = wordSearchFound[index] ?: emptySet(),
                    anchor = wordSearchAnchor[index],
                    revealed = revealed,
                    onTapCell = { r, c ->
                        if (!revealed) {
                            val a = wordSearchAnchor[index]
                            if (a == null) {
                                wordSearchAnchor[index] = r to c
                            } else {
                                val cells = microWordSearchLineCells(a.first, a.second, r, c)
                                if (cells != null) {
                                    val picked = cells.joinToString("") { (rr, cc) -> node.grid.getOrNull(rr)?.getOrNull(cc).orEmpty() }
                                    val match = node.words.firstOrNull { w ->
                                        val nw = w.filter { ch -> ch.isLetterOrDigit() }
                                        nw.length >= 2 && (nw.equals(picked, ignoreCase = true) || nw.equals(picked.reversed(), ignoreCase = true))
                                    }
                                    if (match != null) {
                                        wordSearchFound[index] = (wordSearchFound[index] ?: emptySet()) + match
                                    }
                                }
                                wordSearchAnchor.remove(index)
                            }
                        }
                    }
                )
                is MicroNode.Hangman -> MicroHangmanView(
                    node = node,
                    guessed = hangmanGuessed[index] ?: emptySet(),
                    revealed = revealed,
                    onGuess = { ch ->
                        if (!revealed) {
                            hangmanGuessed[index] = (hangmanGuessed[index] ?: emptySet()) + ch.uppercaseChar()
                        }
                    }
                )
                is MicroNode.ProofParagraph -> MicroProofParagraphView(
                    node = node,
                    edits = proofEdits[index] ?: emptyMap(),
                    revealed = revealed,
                    onEdit = { lineIndex, text ->
                        if (!revealed) {
                            proofEdits[index] = (proofEdits[index] ?: emptyMap()) + (lineIndex to text)
                        }
                    }
                )
                is MicroNode.AudioChoice -> MicroAudioChoiceView(
                    node = node,
                    speak = speak,
                    selected = choiceSelection[index],
                    revealed = revealed,
                    onSelect = { if (!revealed) choiceSelection[index] = it }
                )
                is MicroNode.SpeakScore -> MicroSpeakScoreView(node)
                is MicroNode.RoleplayTurn -> MicroRoleplayChatView(
                    node = node,
                    instanceKey = "${instanceKey}_rp_$index"
                )
                is MicroNode.AiHint -> MicroAiHintView(
                    node = node,
                    instanceKey = "${instanceKey}_hint_$index"
                )
                is MicroNode.HighlightSpan -> MicroHighlightSpanView(
                    node = node,
                    selected = tokenSelection[index] ?: emptySet(),
                    revealed = revealed,
                    onToggle = { tokenIndex ->
                        if (!revealed) {
                            val current = tokenSelection[index] ?: emptySet()
                            tokenSelection[index] = if (tokenIndex in current) current - tokenIndex else current + tokenIndex
                        }
                    }
                )
                is MicroNode.TimedChallenge -> MicroTimedChallengeView(
                    node = node,
                    instanceKey = "${instanceKey}_timed_$index"
                )
                is MicroNode.PairsMemory -> MicroPairsMemoryView(
                    node = node,
                    instanceKey = "${instanceKey}_pm_$index"
                )
                is MicroNode.ClozeDrag -> MicroClozeDragView(
                    node = node,
                    filled = clozeDragFill[index] ?: emptyMap(),
                    revealed = revealed,
                    onSet = { blankIndex, word ->
                        if (!revealed) {
                            val current = clozeDragFill[index] ?: emptyMap()
                            clozeDragFill[index] = current + (blankIndex to word)
                        }
                    },
                    onClear = { blankIndex ->
                        if (!revealed) {
                            val current = clozeDragFill[index] ?: emptyMap()
                            clozeDragFill[index] = current - blankIndex
                        }
                    }
                )
                is MicroNode.Monologue -> MicroMonologueView(node)
                is MicroNode.Shadowing -> MicroShadowingView(node = node, speak = speak)
                is MicroNode.MinimalPair -> MicroMinimalPairView(
                    node = node,
                    speak = speak,
                    selected = choiceSelection[index],
                    revealed = revealed,
                    onSelect = { if (!revealed) choiceSelection[index] = it }
                )
                is MicroNode.IpaRead -> MicroIpaReadView(
                    node = node,
                    speak = speak,
                    selected = choiceSelection[index],
                    revealed = revealed,
                    onSelect = { if (!revealed) choiceSelection[index] = it }
                )
                is MicroNode.SoundLink -> MicroSoundLinkView(node = node, speak = speak)
                is MicroNode.MapLabel -> MicroMapLabelView(
                    node = node,
                    speak = speak,
                    selected = mapLabelSelection[index] ?: emptyMap(),
                    revealed = revealed,
                    onSelect = { itemIndex, label ->
                        if (!revealed) {
                            val current = mapLabelSelection[index] ?: emptyMap()
                            mapLabelSelection[index] = current + (itemIndex to label)
                        }
                    }
                )
                is MicroNode.NoteComplete -> MicroNoteCompleteView(
                    node = node,
                    speak = speak,
                    filled = noteCompleteFill[index] ?: emptyMap(),
                    revealed = revealed,
                    onSet = { blankIndex, text ->
                        if (!revealed) {
                            val current = noteCompleteFill[index] ?: emptyMap()
                            noteCompleteFill[index] = current + (blankIndex to text)
                        }
                    }
                )
                is MicroNode.MatchSentenceEndings -> MicroMatchSentenceEndingsView(
                    node = node,
                    selected = sentenceEndingSelection[index] ?: emptyMap(),
                    revealed = revealed,
                    onSelect = { stemIndex, ending ->
                        if (!revealed) {
                            val current = sentenceEndingSelection[index] ?: emptyMap()
                            sentenceEndingSelection[index] = current + (stemIndex to ending)
                        }
                    }
                )
                is MicroNode.SummaryComplete -> MicroSummaryCompleteView(
                    node = node,
                    filled = summaryFill[index] ?: emptyMap(),
                    revealed = revealed,
                    onSet = { blankIndex, word ->
                        if (!revealed) {
                            val current = summaryFill[index] ?: emptyMap()
                            summaryFill[index] = current + (blankIndex to word)
                        }
                    },
                    onClear = { blankIndex ->
                        if (!revealed) {
                            val current = summaryFill[index] ?: emptyMap()
                            summaryFill[index] = current - blankIndex
                        }
                    }
                )
                is MicroNode.ShortAnswer -> MicroShortAnswerView(
                    node = node,
                    filled = shortAnswerFill[index] ?: emptyMap(),
                    revealed = revealed,
                    onSet = { qIndex, text ->
                        if (!revealed) {
                            val current = shortAnswerFill[index] ?: emptyMap()
                            shortAnswerFill[index] = current + (qIndex to text)
                        }
                    }
                )
                is MicroNode.GuidedWriting -> MicroGuidedWritingView(node = node, instanceKey = "${instanceKey}_gw_$index")
            }
            // 核对后：为「选择/判断/匹配」类补展示参考答案（其渲染器只给绿/红），再对所有可判分题型统一展示解析（含按题型兜底）。
            if (revealed) {
                MicroRevealAnswerNotice(node)
                MicroRevealExplanationNotice(node)
            }
        }
        if (gradable.isNotEmpty()) {
            AgentTextAction(
                text = if (revealed) "重新作答" else "核对答案",
                onClick = {
                    if (revealed) {
                        revealed = false
                        recordedToReview = false
                        choiceSelection.clear()
                        tokenSelection.clear()
                        inputText.clear()
                        orderSelection.clear()
                        matchSelection.clear()
                        categorizeSelection.clear()
                        clozeDragFill.clear()
                        diagramSelection.clear()
                        scrambleOrder.clear()
                        trueFalseSelection.clear()
                        tfngSelection.clear()
                        fillTableFill.clear()
                        clozeSelectChoice.clear()
                        listenFillFill.clear()
                        wordFormFill.clear()
                        stressSelection.clear()
                        reorderOrder.clear()
                        rankOrder.clear()
                        openClozeFill.clear()
                        listenClozeFill.clear()
                        wordSearchFound.clear()
                        wordSearchAnchor.clear()
                        hangmanGuessed.clear()
                        proofEdits.clear()
                        matchHeadingsSelection.clear()
                        matchInfoSelection.clear()
                        mapLabelSelection.clear()
                        sentenceEndingSelection.clear()
                        noteCompleteFill.clear()
                        summaryFill.clear()
                        shortAnswerFill.clear()
                    } else {
                        revealed = true
                        // 作答表上报：把逐题（题面/用户作答/正解/对错）落到宿主消息上，供「AI 分析」逐题复盘。
                        if (onGraded != null) {
                            microCardAnswerSheetJson(card, gradable, answers, ::nodeCorrect)
                                ?.let { onGraded(it.toString()) }
                        }
                        // 错因回灌：把本次答错的可判分微元写进错题本（间隔复习），并带上题型技能维度。best-effort，静默。
                        val wrongInfos = gradable
                            .filter { !nodeCorrect(it.index, it.value) }
                            .mapNotNull { e -> microNodeReviewInfo(e.value)?.let { it to microNodeSkill(e.value) } }
                        if (wrongInfos.isNotEmpty()) {
                            recordedToReview = true
                            reviewScope.launch {
                                wrongInfos.forEach { (info, skill) ->
                                    runCatching {
                                        ReviewStore.addWrong(
                                            reviewCtx,
                                            componentType = info.componentType,
                                            skill = skill,
                                            kind = "micro",
                                            prompt = info.prompt,
                                            options = info.options,
                                            answer = info.answer,
                                            explanation = info.explanation
                                        )
                                    }
                                }
                            }
                        }
                        // 能力反馈闭环：判分结果按整卡主导技能在线更新本地 Elo 能力，并回灌统一
                        // 用户模型（服务端难度自适应吃真实作答，此前只有 43 题型卡有此回路）。
                        // best-effort，静默失败。
                        val gradedTotal = gradable.size
                        val gradedCorrect = gradable.count { nodeCorrect(it.index, it.value) }
                        // 主导技能按整卡全部节点投票（口语/写作等自带流程节点也计入维度倾向），
                        // 判分计数仍只来自可判分节点；纯口语/写作卡 gradedTotal=0 依旧不更新。
                        val dominantSkill = microCardDominantSkill(card.nodes)
                        if (gradedTotal > 0 && dominantSkill != null) {
                            reviewScope.launch {
                                // 学习进度：微卡片核对也计入雷达/打卡（与听力题目控件、每日挑战同一数据源）。
                                runCatching { ProgressStore.record(reviewCtx, "micro_card", dominantSkill, gradedTotal, gradedCorrect) }
                                runCatching { LearnerModelStore.updateFromCounts(reviewCtx, dominantSkill, gradedCorrect, gradedTotal) }
                                runCatching { UserModelStore.push(reviewCtx) }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                primary = true,
                enabled = revealed || anyAnswered,
                icon = if (revealed) Icons.Default.EditNote else Icons.AutoMirrored.Filled.FactCheck,
                height = 38.dp
            )
            if (revealed) {
                AgentCardPracticeFeedback(
                    correct = allCorrect,
                    message = if (allCorrect) "全部正确" else "还有未答对",
                    explanation = if (!allCorrect && recordedToReview) {
                        "答错的题已加入错题本，可稍后在错题本间隔复习。逐项对照绿/红标记修正。"
                    } else {
                        "逐项对照绿/红标记修正。"
                    }
                )
                // 与听力卡「AI 复盘」对齐：核对后一键把作答表交给 AI 逐题分析（仅聊天场景有宿主时显示）。
                if (onRequestSheetAnalysis != null) {
                    AgentTextAction(
                        text = "交给 AI 分析",
                        onClick = {
                            microCardAnswerSheetJson(card, gradable, answers, ::nodeCorrect)
                                ?.let { onRequestSheetAnalysis(it.toString()) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = Icons.Default.Analytics,
                        height = 38.dp
                    )
                }
            }
        }
    }
}

// 判分后为「选择/判断/匹配」类展示「参考答案」——这些题型渲染器只给绿/红、无文字正解；
// 其它题型 microRevealAnswerText 返回 null 即跳过（正解已在其渲染器内联给出）。解析统一由 MicroRevealExplanationNotice 展示。
@Composable
private fun MicroRevealAnswerNotice(node: MicroNode) {
    val answer = microRevealAnswerText(node) ?: return
    AgentCardInlineNotice("参考答案\n$answer")
}

// 「核对答案」后的统一解析：所有可判分题型都展示一段解析——模型给了 explanation 就用它，否则按题型兜底，
// 保证每张练习卡核对后都有解析（修复「生成练习卡片后核对答案没有解析」）。非可判分微元返回空、不展示。
@Composable
private fun MicroRevealExplanationNotice(node: MicroNode) {
    val explanation = microRevealExplanationText(node)
    if (explanation.isNotBlank()) AgentCardInlineNotice("解析：$explanation")
}

@Composable
private fun MicroChoiceView(
    node: MicroNode.Choice,
    selected: Int?,
    revealed: Boolean,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        node.options.forEachIndexed { i, option ->
            val isSelected = selected == i
            val isAnswer = revealed && option.trim().equals(node.answer.trim(), ignoreCase = true)
            val targetTone = when {
                revealed && isAnswer -> AgentPracticeSuccess
                revealed && isSelected && !isAnswer -> AgentPracticeWrong
                isSelected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            val active = isSelected || isAnswer
            val tone by animateColorAsState(targetTone, tween(180, easing = FastOutSlowInEasing), label = "choiceTone")
            val fill by animateColorAsState(
                targetTone.copy(alpha = if (active) 0.12f else 0f),
                tween(180, easing = FastOutSlowInEasing),
                label = "choiceFill"
            )
            // 未选/未揭示时用可读的中性灰做序号与描边（此前用 outlineVariant 当文字色，字母近乎不可见）。
            val idleInk = MaterialTheme.colorScheme.onSurfaceVariant
            val idleBadge = MaterialTheme.colorScheme.surfaceVariant
            val idleBorder = MaterialTheme.colorScheme.outlineVariant
            val badgeFill = if (active) tone.copy(alpha = 0.16f) else idleBadge
            val letterInk = if (active) tone else idleInk
            val borderColor = if (active) tone.copy(alpha = 0.55f) else idleBorder
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
                    .background(fill)
                    .clickable(enabled = !revealed) { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(badgeFill),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        ('A' + i).toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = letterInk
                    )
                }
                Text(
                    option,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (revealed && isAnswer) AgentPracticeSuccess else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (revealed && isAnswer) {
                    Icon(Icons.Default.Check, contentDescription = "正确答案", tint = AgentPracticeSuccess, modifier = Modifier.size(18.dp))
                } else if (revealed && isSelected) {
                    Icon(Icons.Default.Close, contentDescription = "你的选择有误", tint = AgentPracticeWrong, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun MicroInputView(
    node: MicroNode.Input,
    value: String,
    revealed: Boolean,
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp))
                .padding(10.dp)
        ) {
            if (value.isBlank()) {
                Text("输入答案…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                readOnly = revealed,
                singleLine = !node.multiline,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (revealed && node.answer.isNotBlank()) {
            val ok = microInputCorrect(value, node.answer)
            AgentCardKeyValueRow(if (ok) "正确" else "参考答案", node.answer, if (ok) AgentPracticeSuccess else AgentPracticeWrong)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroTokensView(
    node: MicroNode.Tokens,
    selected: Set<Int>,
    revealed: Boolean,
    onToggle: (Int) -> Unit
) {
    val tokens = remember(node.text) { agentSplitErrorTokens(node.text) }
    val errorIndices = remember(node) { microTokensErrorIndices(node) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            tokens.forEachIndexed { i, token ->
                val isSelected = i in selected
                val isError = i in errorIndices
                val (background, foreground, decoration) = when {
                    !revealed && isSelected -> Triple(AgentStudyRose.copy(alpha = 0.16f), AgentStudyRose, null)
                    !revealed -> Triple(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), MaterialTheme.colorScheme.onSurface, null)
                    isError && isSelected -> Triple(AgentPracticeSuccess.copy(alpha = 0.16f), AgentPracticeSuccess, null)
                    isSelected -> Triple(AgentPracticeWrong.copy(alpha = 0.16f), AgentPracticeWrong, TextDecoration.LineThrough)
                    isError -> Triple(AgentStudyAmber.copy(alpha = 0.18f), AgentStudyAmber, TextDecoration.Underline)
                    else -> Triple(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), MaterialTheme.colorScheme.onSurface, null)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(background)
                        .then(if (revealed) Modifier else Modifier.clickable { onToggle(i) })
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(
                        token,
                        style = MaterialTheme.typography.bodyMedium,
                        color = foreground,
                        fontWeight = FontWeight.Medium,
                        textDecoration = decoration
                    )
                }
            }
        }
        if (revealed && node.correct.isNotBlank()) AgentCardKeyValueRow("正确", node.correct, AgentPracticeSuccess)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroOrderView(
    node: MicroNode.Order,
    selected: List<Int>,
    revealed: Boolean,
    onAdd: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Text("已排顺序：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected.isEmpty()) {
                Text("（点下方词块按顺序排列，再核对）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            selected.forEachIndexed { pos, itemIndex ->
                MicroChip(
                    text = "${pos + 1}. ${node.items.getOrElse(itemIndex) { "" }}",
                    background = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    foreground = MaterialTheme.colorScheme.primary,
                    enabled = !revealed,
                    onClick = { onRemove(pos) }
                )
            }
        }
        val remaining = node.items.indices.filter { it !in selected }
        if (!revealed && remaining.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                remaining.forEach { itemIndex ->
                    MicroChip(
                        text = node.items[itemIndex],
                        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        foreground = MaterialTheme.colorScheme.onSurface,
                        enabled = true,
                        onClick = { onAdd(itemIndex) }
                    )
                }
            }
        }
        if (revealed) {
            val ok = microOrderCorrect(selected.map { node.items.getOrElse(it) { "" } }, node.answer)
            AgentCardKeyValueRow(if (ok) "正确" else "参考答案", node.answer, if (ok) AgentPracticeSuccess else AgentPracticeWrong)
        }
    }
}

// 连线匹配微元：右项打乱成共享选项池，每个左项从池中点选其对应右项（点选式，复用 MicroChip）。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroMatchView(
    node: MicroNode.Match,
    selected: Map<Int, String>,
    revealed: Boolean,
    onSelect: (Int, String) -> Unit
) {
    val pool = remember(node) { node.pairs.map { it.right }.distinct().shuffled() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        node.pairs.forEachIndexed { i, pair ->
            val pick = selected[i]
            val rowCorrect = revealed && pick != null &&
                normalizeAgentShortAnswer(pick).equals(normalizeAgentShortAnswer(pair.right), ignoreCase = true)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(pair.left, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pool.forEach { right ->
                        val isPicked = pick != null &&
                            normalizeAgentShortAnswer(pick).equals(normalizeAgentShortAnswer(right), ignoreCase = true)
                        val isThisCorrect = normalizeAgentShortAnswer(right).equals(normalizeAgentShortAnswer(pair.right), ignoreCase = true)
                        val (bg, fg) = when {
                            revealed && isThisCorrect -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                            revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                            isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                        }
                        MicroChip(text = right, background = bg, foreground = fg, enabled = !revealed, onClick = { onSelect(i, right) })
                    }
                }
                if (revealed && !rowCorrect) {
                    AgentCardKeyValueRow("正确", pair.right, AgentPracticeSuccess)
                }
            }
        }
    }
}

// 归类分桶微元：类别名为共享选项池，每个项（打乱顺序）点选其所属类别（点选式，复用 MicroChip）。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroCategorizeView(
    node: MicroNode.Categorize,
    selected: Map<String, String>,
    revealed: Boolean,
    onSelect: (String, String) -> Unit
) {
    val pool = remember(node) { node.categories.map { it.name } }
    val items = remember(node) { node.categories.flatMap { c -> c.items.map { it to c.name } }.shuffled() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        items.forEach { (item, correctCat) ->
            val pick = selected[item]
            val rowCorrect = revealed && pick != null &&
                normalizeAgentShortAnswer(pick).equals(normalizeAgentShortAnswer(correctCat), ignoreCase = true)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pool.forEach { cat ->
                        val isPicked = pick != null &&
                            normalizeAgentShortAnswer(pick).equals(normalizeAgentShortAnswer(cat), ignoreCase = true)
                        val isThisCorrect = normalizeAgentShortAnswer(cat).equals(normalizeAgentShortAnswer(correctCat), ignoreCase = true)
                        val (bg, fg) = when {
                            revealed && isThisCorrect -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                            revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                            isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                        }
                        MicroChip(text = cat, background = bg, foreground = fg, enabled = !revealed, onClick = { onSelect(item, cat) })
                    }
                }
                if (revealed && !rowCorrect) {
                    AgentCardKeyValueRow("正确", correctCat, AgentPracticeSuccess)
                }
            }
        }
    }
}

@Composable
private fun MicroChip(
    text: String,
    background: Color,
    foreground: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val bg by animateColorAsState(background, tween(180, easing = FastOutSlowInEasing), label = "chipBg")
    val fg by animateColorAsState(foreground, tween(180, easing = FastOutSlowInEasing), label = "chipFg")
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = fg, fontWeight = FontWeight.Medium)
    }
}

// 进度微元：材料卡进度条（复用 AgentLinearProgress），label 右对齐显示「x / y」「答对…」「%」。纯展示。
@Composable
private fun MicroProgressView(node: MicroNode.Progress) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "进度",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (node.label.isNotBlank()) {
                Text(
                    node.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        AgentLinearProgress(progress = node.value)
    }
}

@Composable
private fun MicroRevealView(node: MicroNode.Reveal) {
    var open by rememberSaveable(node.label, node.content) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AgentTextAction(
            text = node.label.ifBlank { if (open) "收起" else "展开" },
            onClick = { open = !open },
            modifier = Modifier.fillMaxWidth(),
            height = 34.dp
        )
        if (open && node.content.isNotBlank()) AgentCardInlineNotice(node.content)
    }
}

// 翻卡微元：每张卡点击翻面（rotationY 3D 动画，ease-out），正面词/问 ↔ 背面义/答；纯学习记忆、不判分。
@Composable
private fun MicroFlashcardView(node: MicroNode.Flashcard) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        node.cards.forEachIndexed { i, card ->
            MicroFlipCard(front = card.front, back = card.back, index = i + 1, total = node.cards.size)
        }
    }
}

@Composable
private fun MicroFlipCard(front: String, back: String, index: Int, total: Int) {
    var flipped by rememberSaveable(front, back) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "flip"
    )
    val showingBack = rotation > 90f
    // 正反面配色区分：正面品牌色浅染、背面中性表面。
    val faceColor = if (showingBack) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            }
            .clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .background(faceColor)
            .clickable { flipped = !flipped }
            .padding(16.dp)
    ) {
        if (total > 1) {
            Text(
                "$index / $total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer { if (showingBack) rotationY = 180f }
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { if (showingBack) rotationY = 180f },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (!showingBack) {
                Text(front, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("点击翻面", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(back, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

// 写作微元：写作要求 + 自带 AI 四维评分的作文框（复用图表作文同款评分框）。
@Composable
private fun MicroWritingView(node: MicroNode.Writing, instanceKey: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(
                node.prompt,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        AgentChartWritingBox(prompt = node.prompt, reference = node.reference, instanceKey = instanceKey)
    }
}

// 端上 TTS 朗读器（android.speech.tts，无需权限/第三方依赖）；听写/听辨微元的音频源。
// 惰性初始化：首次点播才创建引擎（每张微元卡都会调本函数，聊天多卡并存时
// 预建引擎会同时绑定多个 TTS service）；初始化期间的点播记为 pending，就绪后补播。
@Composable
internal fun rememberMicroSpeaker(): (String) -> Unit {
    val context = LocalContext.current.applicationContext
    val engineState = remember { mutableStateOf<TextToSpeech?>(null) }
    val ready = remember { mutableStateOf(false) }
    val pending = remember { mutableStateOf("") }
    DisposableEffect(Unit) {
        onDispose {
            engineState.value?.stop()
            engineState.value?.shutdown()
            engineState.value = null
            ready.value = false
        }
    }
    return remember {
        { text: String ->
            val t = text.trim()
            if (t.isNotBlank()) {
                val engine = engineState.value
                when {
                    engine == null -> {
                        pending.value = t
                        var created: TextToSpeech? = null
                        created = TextToSpeech(context) { status ->
                            if (status == TextToSpeech.SUCCESS) {
                                created?.language = Locale.US
                                ready.value = true
                                val p = pending.value
                                pending.value = ""
                                if (p.isNotBlank()) created?.speak(p, TextToSpeech.QUEUE_FLUSH, null, "micro_tts")
                            }
                        }
                        engineState.value = created
                    }
                    ready.value -> engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, "micro_tts")
                    else -> pending.value = t // 引擎初始化中：记住最后一次点播，就绪后补播
                }
            }
        }
    }
}

@Composable
private fun MicroPlayButton(label: String, onPlay: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable { onPlay() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
    }
}

// 听写微元：点 🔊 朗读 → 打字听写 → diff 判分（text 即答案）。
@Composable
private fun MicroDictationView(
    node: MicroNode.Dictation,
    speak: (String) -> Unit,
    value: String,
    revealed: Boolean,
    onChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MicroPlayButton("播放") { speak(node.text) }
            if (node.hint.isNotBlank()) {
                Text(node.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            if (value.isBlank()) {
                Text("听到后在此输入…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                readOnly = revealed,
                singleLine = false,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (revealed && node.text.isNotBlank()) {
            val ok = microInputCorrect(value, node.text)
            AgentCardKeyValueRow(if (ok) "正确" else "原文", node.text, if (ok) AgentPracticeSuccess else AgentPracticeWrong)
        }
    }
}

// 听辨微元：点 🔊 朗读 audioText → 从 options 选所听到的（复用 MicroChoiceView 的判分/样式）。
@Composable
private fun MicroAudioChoiceView(
    node: MicroNode.AudioChoice,
    speak: (String) -> Unit,
    selected: Int?,
    revealed: Boolean,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MicroPlayButton("播放并选择") { speak(node.audioText) }
        MicroChoiceView(
            node = MicroNode.Choice(prompt = node.prompt, options = node.options, answer = node.answer, multi = false),
            selected = selected,
            revealed = revealed,
            onSelect = onSelect
        )
    }
}

// 点选填空微元：text 按 ___ 切段，段间渲染空位；点词库的词依次填入第一个空位，点已填空位可清除。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroClozeDragView(
    node: MicroNode.ClozeDrag,
    filled: Map<Int, String>,
    revealed: Boolean,
    onSet: (Int, String) -> Unit,
    onClear: (Int) -> Unit
) {
    val segments = remember(node.text) { node.text.split(Regex("_{2,}")) }
    val blankCount = (segments.size - 1).coerceAtLeast(0)
    val bank = remember(node) { node.bank.shuffled() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            segments.forEachIndexed { si, seg ->
                seg.trim().split(Regex("\\s+")).forEach { w ->
                    if (w.isNotBlank()) {
                        Text(
                            w,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                }
                if (si < blankCount) {
                    val word = filled[si]
                    val answer = node.answers.getOrElse(si) { "" }
                    val isCorrect = word != null && normalizeAgentShortAnswer(word).equals(normalizeAgentShortAnswer(answer), ignoreCase = true)
                    val tone = when {
                        revealed && word != null && isCorrect -> AgentPracticeSuccess
                        revealed && word != null -> AgentPracticeWrong
                        word != null -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.outlineVariant
                    }
                    Box(
                        modifier = Modifier
                            .heightIn(min = 34.dp)
                            .widthIn(min = 52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(BorderStroke(1.dp, tone.copy(alpha = 0.6f)), RoundedCornerShape(8.dp))
                            .background(if (word != null) tone.copy(alpha = 0.10f) else Color.Transparent)
                            .then(if (!revealed && word != null) Modifier.clickable { onClear(si) } else Modifier)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            word ?: "▢${si + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (word != null) tone else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (!revealed) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bank.forEach { w ->
                    MicroChip(
                        text = w,
                        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        foreground = MaterialTheme.colorScheme.onSurface,
                        enabled = true,
                        onClick = {
                            val nextEmpty = (0 until blankCount).firstOrNull { filled[it] == null }
                            if (nextEmpty != null) onSet(nextEmpty, w)
                        }
                    )
                }
            }
        }
        if (revealed) {
            val answerLine = (0 until blankCount).joinToString("   ") { "空${it + 1}: ${node.answers.getOrElse(it) { "" }}" }
            if (answerLine.isNotBlank()) AgentCardKeyValueRow("参考", answerLine, AgentPracticeSuccess)
        }
    }
}

// 跟读评分微元：复用现有口语录音+AI 评分组件（自带录音权限/MediaRecorder/assessSpeaking），自带评分不走统一判分。
@Composable
private fun MicroSpeakScoreView(node: MicroNode.SpeakScore) {
    val spec = remember(node) {
        AgentCardComponentSpec(
            type = AgentCardComponent.SpeakingPrompt,
            text = node.text.ifBlank { node.prompts.firstOrNull().orEmpty() },
            items = node.prompts
        )
    }
    AgentCardSpeakingPromptComponent(spec)
}

// 对话轮微元（roleplay_turn）渲染已迁移到 MicroRoleplayChat.kt 的 MicroRoleplayChatView
// （自由聊天 + 语音 ASR/TTS + 跨会话记忆 + 结束评分）。

// 篇章框选微元：在整段文本里点选所有符合条件的词（点选式，复用改错卡分词/样式）；核对时绿=正确选中、黄=漏选、红=多选。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroHighlightSpanView(
    node: MicroNode.HighlightSpan,
    selected: Set<Int>,
    revealed: Boolean,
    onToggle: (Int) -> Unit
) {
    val tokens = remember(node.text) { agentSplitErrorTokens(node.text) }
    val targetIndices = remember(node) { microHighlightTargetIndices(node) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tokens.forEachIndexed { i, token ->
                val isSelected = i in selected
                val isTarget = i in targetIndices
                val (background, foreground) = when {
                    !revealed && isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) to MaterialTheme.colorScheme.primary
                    !revealed -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                    isTarget && isSelected -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                    isTarget && !isSelected -> AgentStudyAmber.copy(alpha = 0.18f) to AgentStudyAmber
                    isSelected -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(background)
                        .then(if (revealed) Modifier else Modifier.clickable { onToggle(i) })
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) {
                    Text(token, style = MaterialTheme.typography.bodyMedium, color = foreground, fontWeight = FontWeight.Medium)
                }
            }
        }
        if (revealed) {
            val answerLine = node.answers.filter { it.isNotBlank() }.joinToString("、")
            if (answerLine.isNotBlank()) AgentCardKeyValueRow("应选", answerLine, AgentPracticeSuccess)
        }
    }
}

// 渐进提示微元：卡住时逐条揭示提示（由浅到深），最后才揭示答案。脚手架引导、非统一判分、自带状态。
@Composable
private fun MicroAiHintView(node: MicroNode.AiHint, instanceKey: String) {
    var revealCount by rememberSaveable(instanceKey) { mutableStateOf(0) }
    var answerShown by rememberSaveable(instanceKey) { mutableStateOf(false) }
    val hintCount = node.hints.size
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(
                node.prompt,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        for (i in 0 until revealCount.coerceAtMost(hintCount)) {
            AgentCardInlineNotice("💡 提示 ${i + 1}：${node.hints[i]}")
        }
        if (answerShown && node.answer.isNotBlank()) {
            AgentCardKeyValueRow("答案", node.answer, AgentPracticeSuccess)
        }
        if (!answerShown) {
            val hasMoreHints = revealCount < hintCount
            AgentTextAction(
                text = if (hasMoreHints) "看提示（${revealCount + 1}/$hintCount）" else "看答案",
                onClick = { if (hasMoreHints) revealCount += 1 else answerShown = true },
                modifier = Modifier.fillMaxWidth(),
                primary = !hasMoreHints,
                icon = if (hasMoreHints) null else Icons.AutoMirrored.Filled.FactCheck,
                height = 38.dp
            )
        } else {
            AgentTextAction(
                text = "重新开始",
                onClick = { revealCount = 0; answerShown = false },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.EditNote,
                height = 34.dp
            )
        }
    }
}

// 限时抢答微元：倒计时进行时点选项即作答（选项即提交），又快又对得分越高，超时判未答；「再来一次」重置。自带计时/计分，不走统一判分。
@Composable
private fun MicroTimedChallengeView(node: MicroNode.TimedChallenge, instanceKey: String) {
    val total = node.seconds.coerceIn(5, 120)
    var remaining by rememberSaveable(instanceKey) { mutableStateOf(total) }
    var selected by rememberSaveable(instanceKey) { mutableStateOf(-1) }
    var submitted by rememberSaveable(instanceKey) { mutableStateOf(false) }
    LaunchedEffect(instanceKey, submitted) {
        if (!submitted) {
            while (remaining > 0 && !submitted) {
                delay(1000)
                remaining -= 1
            }
            if (remaining <= 0) submitted = true
        }
    }
    val isCorrect = submitted && selected >= 0 && microChoiceCorrect(selected, node.options, node.answer)
    val timedOut = submitted && selected < 0
    // 抢答自带流程、不走统一「核对答案」：答错（选了错误选项）在此单独回灌错题本；
    // 超时未选不算（无作答证据）。wrongRecorded 防重开已提交状态时重复回灌。best-effort，静默。
    val reviewCtx = LocalContext.current
    var wrongRecorded by rememberSaveable(instanceKey) { mutableStateOf(false) }
    LaunchedEffect(submitted) {
        if (submitted && selected >= 0 && !isCorrect && !wrongRecorded) {
            wrongRecorded = true
            microNodeReviewInfo(node)?.let { info ->
                runCatching {
                    ReviewStore.addWrong(
                        reviewCtx,
                        componentType = info.componentType,
                        skill = microNodeSkill(node),
                        kind = "micro",
                        prompt = info.prompt,
                        options = info.options,
                        answer = info.answer,
                        explanation = info.explanation
                    )
                }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (node.prompt.isNotBlank()) {
                Text(
                    node.prompt,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            val timerTone = if (!submitted && remaining <= 3) AgentPracticeWrong else MaterialTheme.colorScheme.primary
            Text("⏱ ${remaining}s", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = timerTone)
        }
        AgentLinearProgress(progress = if (total > 0) remaining.toFloat() / total.toFloat() else 0f)
        MicroChoiceView(
            node = MicroNode.Choice(prompt = "", options = node.options, answer = node.answer, multi = false),
            selected = if (selected >= 0) selected else null,
            revealed = submitted,
            onSelect = { if (!submitted) { selected = it; submitted = true } }
        )
        if (submitted) {
            AgentCardPracticeFeedback(
                correct = isCorrect,
                message = when {
                    timedOut -> "⏱ 超时未作答"
                    isCorrect -> "答对！用时 ${total - remaining}s（剩 ${remaining}s，越快越高分）"
                    else -> "答错了"
                },
                explanation = if (!isCorrect) "正确答案：${node.answer}" else "反应很快，继续保持！"
            )
            AgentTextAction(
                text = "再来一次",
                onClick = { remaining = total; selected = -1; submitted = false; wrongRecorded = false },
                modifier = Modifier.fillMaxWidth(),
                icon = Icons.Default.EditNote,
                height = 34.dp
            )
        }
    }
}

// 表格微元：结构化展示（对比/词形变化/语法表）。表头浅品牌色，行交替浅底，整体描边圆角。纯展示、不判分。
@Composable
private fun MicroTableView(node: MicroNode.Table) {
    val colCount = maxOf(node.headers.size, node.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (node.title.isNotBlank()) {
            Text(node.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
        ) {
            if (node.headers.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))) {
                    for (ci in 0 until colCount) {
                        Text(
                            node.headers.getOrElse(ci) { "" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            node.rows.forEachIndexed { ri, row ->
                val rowBg = if (ri % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.Transparent
                Row(modifier = Modifier.fillMaxWidth().background(rowBg)) {
                    for (ci in 0 until colCount) {
                        Text(
                            row.getOrElse(ci) { "" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

// 句子成分微元：先展示整句，再逐块给标签池点选（点选式，复用 MicroChip/主题）；核对时绿=对、红=错并给正确标签。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroSentenceDiagramView(
    node: MicroNode.SentenceDiagram,
    selected: Map<Int, String>,
    revealed: Boolean,
    onSelect: (Int, String) -> Unit
) {
    val pool = remember(node) {
        node.labels.ifEmpty { node.items.map { it.label } }.map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        if (node.sentence.isNotBlank()) AgentCardInlineNotice(node.sentence)
        node.items.forEachIndexed { i, item ->
            val pick = selected[i]
            val rowCorrect = revealed && pick != null &&
                normalizeAgentShortAnswer(pick).equals(normalizeAgentShortAnswer(item.label), ignoreCase = true)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(item.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    pool.forEach { label ->
                        val isPicked = pick != null &&
                            normalizeAgentShortAnswer(pick).equals(normalizeAgentShortAnswer(label), ignoreCase = true)
                        val isThisCorrect = normalizeAgentShortAnswer(label).equals(normalizeAgentShortAnswer(item.label), ignoreCase = true)
                        val (bg, fg) = when {
                            revealed && isThisCorrect -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                            revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                            isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                        }
                        MicroChip(text = label, background = bg, foreground = fg, enabled = !revealed, onClick = { onSelect(i, label) })
                    }
                }
                if (revealed && !rowCorrect) {
                    AgentCardKeyValueRow("正确", item.label, AgentPracticeSuccess)
                }
            }
        }
    }
}

// 字母重组微元：给提示/释义，从打乱字母池按序点选拼词；已拼区点字母可移除。核对时绿=拼对、红=拼错并给正确拼写。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroWordScrambleView(
    node: MicroNode.WordScramble,
    selected: List<Int>,
    revealed: Boolean,
    onAdd: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    val tiles = remember(node) { microScrambleTiles(node) }
    val displayOrder = remember(node) { tiles.indices.shuffled() }
    val correct = microWordScrambleCorrect(selected.map { tiles.getOrElse(it) { "" } }, node.word)
    val assembledTone = when {
        revealed && correct -> AgentPracticeSuccess
        revealed -> AgentPracticeWrong
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🔤 重组字母拼出单词", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.hint.isNotBlank()) {
            Text("提示：${node.hint}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected.isEmpty()) {
                Text("（点下方字母按顺序拼词，再核对）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            selected.forEachIndexed { pos, tileIndex ->
                MicroChip(
                    text = tiles.getOrElse(tileIndex) { "" },
                    background = assembledTone.copy(alpha = 0.12f),
                    foreground = assembledTone,
                    enabled = !revealed,
                    onClick = { onRemove(pos) }
                )
            }
        }
        if (!revealed) {
            val remaining = displayOrder.filter { it !in selected }
            if (remaining.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    remaining.forEach { tileIndex ->
                        MicroChip(
                            text = tiles[tileIndex],
                            background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            foreground = MaterialTheme.colorScheme.onSurface,
                            enabled = true,
                            onClick = { onAdd(tileIndex) }
                        )
                    }
                }
            }
        }
        if (revealed) {
            AgentCardKeyValueRow(if (correct) "正确" else "正确拼写", node.word, if (correct) AgentPracticeSuccess else AgentPracticeWrong)
        }
    }
}

// 判断对错微元：逐句展示陈述，每句点「对/错」二选一（点选式，复用 MicroChip/主题）；核对时正确项绿、误选红。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroTrueFalseView(
    node: MicroNode.TrueFalse,
    selected: Map<Int, Boolean>,
    revealed: Boolean,
    onJudge: (Int, Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        node.statements.forEachIndexed { i, st ->
            val pick = selected[i]
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${i + 1}. ${st.text}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(true to "对", false to "错").forEach { (value, label) ->
                        val isPicked = pick == value
                        val isThisCorrect = st.answer == value
                        val (bg, fg) = when {
                            revealed && isThisCorrect -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                            revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                            isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                        }
                        MicroChip(text = label, background = bg, foreground = fg, enabled = !revealed, onClick = { onJudge(i, value) })
                    }
                }
            }
        }
    }
}

// 判断三态微元：逐句在 正确/错误/未提及 中三选一；核对时该句正确项绿、误选红。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroTfngView(
    node: MicroNode.Tfng,
    selected: Map<Int, String>,
    revealed: Boolean,
    onJudge: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        node.statements.forEachIndexed { i, st ->
            val pick = selected[i]
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("${i + 1}. ${st.text}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("true" to "正确", "false" to "错误", "not_given" to "未提及").forEach { (value, label) ->
                        val isPicked = pick == value
                        val isThisCorrect = st.answer == value
                        val (bg, fg) = when {
                            revealed && isThisCorrect -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                            revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                            isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                        }
                        MicroChip(text = label, background = bg, foreground = fg, enabled = !revealed, onClick = { onJudge(i, value) })
                    }
                }
            }
        }
    }
}

// IELTS 段落标题匹配微元：逐段落从标题库点选一个标题；核对时该段正确标题绿、误选红。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroMatchHeadingsView(
    node: MicroNode.MatchHeadings,
    selected: Map<Int, String>,
    revealed: Boolean,
    onSelect: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        node.paragraphs.forEachIndexed { i, para ->
            val pick = selected[i]
            val label = para.label.ifBlank { ('A' + i).toString() }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$label. ${para.text}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    node.headings.forEach { heading ->
                        val isPicked = pick == heading
                        val isThisCorrect = para.answer == heading
                        val (bg, fg) = when {
                            revealed && isThisCorrect -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                            revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                            isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                        }
                        MicroChip(text = heading, background = bg, foreground = fg, enabled = !revealed, onClick = { onSelect(i, heading) })
                    }
                }
            }
        }
    }
}

// IELTS 信息匹配微元：逐条信息点选它所属的段落标签；核对时正确段落绿、误选红。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroMatchInfoView(
    node: MicroNode.MatchInfo,
    selected: Map<Int, String>,
    revealed: Boolean,
    onSelect: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        node.statements.forEachIndexed { i, st ->
            val pick = selected[i]
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${i + 1}. ${st.text}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    node.options.forEach { option ->
                        val isPicked = pick == option
                        val isThisCorrect = st.answer == option
                        val (bg, fg) = when {
                            revealed && isThisCorrect -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                            revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                            isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                        }
                        MicroChip(text = option, background = bg, foreground = fg, enabled = !revealed, onClick = { onSelect(i, option) })
                    }
                }
            }
        }
    }
}

// 表格填空微元：展示表格，挖空处为可填空位；从下方词库点词填入下一个空位，点已填空位可清除。核对时绿=填对/红=填错并露正确答案。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroFillTableView(
    node: MicroNode.FillTable,
    filled: Map<Int, String>,
    revealed: Boolean,
    onSet: (Int, String) -> Unit,
    onClear: (Int) -> Unit
) {
    val colCount = maxOf(node.headers.size, node.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return
    val blankIndexByCell = remember(node) {
        node.blanks.withIndex().associate { (i, c) -> (c.row to c.col) to i }
    }
    val expectedAnswers = remember(node) { node.blanks.map { microFillTableExpected(node, it) } }
    val bank = remember(node) {
        (if (node.bank.isNotEmpty()) node.bank else expectedAnswers).filter { it.isNotBlank() }.shuffled()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (node.title.isNotBlank()) {
            Text(node.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
        ) {
            if (node.headers.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))) {
                    for (ci in 0 until colCount) {
                        Text(
                            node.headers.getOrElse(ci) { "" },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            node.rows.forEachIndexed { ri, row ->
                val rowBg = if (ri % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.Transparent
                Row(modifier = Modifier.fillMaxWidth().background(rowBg), verticalAlignment = Alignment.CenterVertically) {
                    for (ci in 0 until colCount) {
                        val bi = blankIndexByCell[ri to ci]
                        if (bi != null) {
                            val word = filled[bi]
                            val expected = expectedAnswers.getOrElse(bi) { "" }
                            val isCorrect = word != null &&
                                normalizeAgentShortAnswer(word).equals(normalizeAgentShortAnswer(expected), ignoreCase = true)
                            val tone = when {
                                revealed && isCorrect -> AgentPracticeSuccess
                                revealed -> AgentPracticeWrong
                                word != null -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outlineVariant
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                                    .heightIn(min = 32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(BorderStroke(1.dp, tone.copy(alpha = 0.6f)), RoundedCornerShape(8.dp))
                                    .background(if (word != null) tone.copy(alpha = 0.10f) else Color.Transparent)
                                    .then(if (!revealed && word != null) Modifier.clickable { onClear(bi) } else Modifier)
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (revealed && !isCorrect) expected.ifBlank { "▢" } else (word ?: "▢"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = if (word != null || revealed) tone else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text(
                                row.getOrElse(ci) { "" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
        if (!revealed) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bank.forEach { w ->
                    MicroChip(
                        text = w,
                        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        foreground = MaterialTheme.colorScheme.onSurface,
                        enabled = true,
                        onClick = {
                            val nextEmpty = node.blanks.indices.firstOrNull { filled[it] == null }
                            if (nextEmpty != null) onSet(nextEmpty, w)
                        }
                    )
                }
            }
        }
    }
}

private data class MicroMemoCard(val id: Int, val pairId: Int, val text: String)

// 记忆翻牌配对微元：每对生成两张牌(左/右)，盖牌打乱；翻两张，配对成功则锁定高亮、失败短暂展示后自动盖回。自带步数/进度，「再来一次」重开。纯游戏、不走统一判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroPairsMemoryView(node: MicroNode.PairsMemory, instanceKey: String) {
    var round by rememberSaveable(instanceKey) { mutableStateOf(0) }
    val cards = remember(node, round) {
        node.pairs.flatMapIndexed { p, pair ->
            listOf(
                MicroMemoCard(id = p * 2, pairId = p, text = pair.left),
                MicroMemoCard(id = p * 2 + 1, pairId = p, text = pair.right)
            )
        }.shuffled()
    }
    val matchedPairs = remember(node, round) { mutableStateListOf<Int>() }
    val flipped = remember(node, round) { mutableStateListOf<Int>() }
    var moves by remember(node, round) { mutableStateOf(0) }

    // 翻开两张后：匹配→锁定并清空翻开态；不匹配→短暂展示后自动盖回。均计一步。
    LaunchedEffect(flipped.toList(), round) {
        if (flipped.size == 2) {
            moves += 1
            val a = cards.firstOrNull { it.id == flipped[0] }
            val b = cards.firstOrNull { it.id == flipped[1] }
            if (a != null && b != null && a.pairId == b.pairId) {
                matchedPairs.add(a.pairId)
                flipped.clear()
            } else {
                delay(800)
                flipped.clear()
            }
        }
    }
    val done = node.pairs.isNotEmpty() && matchedPairs.size == node.pairs.size

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("步数 $moves", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("配对 ${matchedPairs.size} / ${node.pairs.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cards.forEach { c ->
                val isMatched = c.pairId in matchedPairs
                val isFaceUp = isMatched || c.id in flipped
                val (bg, fg) = when {
                    isMatched -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                    isFaceUp -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .widthIn(min = 72.dp)
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(BorderStroke(1.dp, (if (isFaceUp) fg else MaterialTheme.colorScheme.outlineVariant).copy(alpha = 0.55f)), RoundedCornerShape(10.dp))
                        .background(bg)
                        .then(if (!isFaceUp && flipped.size < 2) Modifier.clickable { if (c.id !in flipped) flipped.add(c.id) } else Modifier)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isFaceUp) c.text else "?",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = fg
                    )
                }
            }
        }
        if (done) {
            AgentCardInlineNotice("🎉 全部配对完成！用了 $moves 步。")
        }
        AgentTextAction(
            text = "再来一次",
            onClick = { round += 1 },
            modifier = Modifier.fillMaxWidth(),
            icon = Icons.Default.EditNote,
            height = 34.dp
        )
    }
}

// 完形选择微元：展示带空位的段落（空位显示所选项或 ▢n），下方逐空给独立选项点选；核对时该空正确项绿、误选红。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroClozeSelectView(
    node: MicroNode.ClozeSelect,
    selected: Map<Int, Int>,
    revealed: Boolean,
    onSelect: (Int, Int) -> Unit
) {
    val segments = remember(node.text) { node.text.split(Regex("_{2,}")) }
    val blankCount = (segments.size - 1).coerceAtLeast(0)
    val display = buildString {
        segments.forEachIndexed { si, seg ->
            append(seg)
            if (si < blankCount) {
                val b = node.blanks.getOrNull(si)
                val pick = selected[si]?.let { b?.options?.getOrNull(it) }
                append(if (pick != null) "【$pick】" else " ▢${si + 1} ")
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AgentCardInlineNotice(display)
        for (i in 0 until blankCount) {
            val b = node.blanks.getOrNull(i) ?: continue
            if (b.options.isEmpty()) continue
            val pick = selected[i]
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    "空 ${i + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    b.options.forEachIndexed { oi, opt ->
                        val isPicked = pick == oi
                        val isAnswer = normalizeAgentShortAnswer(opt).equals(normalizeAgentShortAnswer(b.answer), ignoreCase = true)
                        val (bg, fg) = when {
                            revealed && isAnswer -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                            revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                            isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                        }
                        MicroChip(text = opt, background = bg, foreground = fg, enabled = !revealed, onClick = { onSelect(i, oi) })
                    }
                }
            }
        }
    }
}

// 听力填空微元：先播放整句(TTS)，再把缺词从词库点填回（复用点选填空 MicroClozeDragView 的填空/判分体）。可判分。
@Composable
private fun MicroListenFillView(
    node: MicroNode.ListenFill,
    speak: (String) -> Unit,
    filled: Map<Int, String>,
    revealed: Boolean,
    onSet: (Int, String) -> Unit,
    onClear: (Int) -> Unit
) {
    val clozeNode = remember(node) { MicroNode.ClozeDrag(text = node.text, bank = node.bank, answers = node.answers) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MicroPlayButton("播放句子") { speak(node.audioText) }
        MicroClozeDragView(
            node = clozeNode,
            filled = filled,
            revealed = revealed,
            onSet = onSet,
            onClear = onClear
        )
    }
}

// 词形转换微元：逐项「原词 (目标词性) →」给空位，从词库点填派生词，点已填可清；核对时绿=对/红=错并给正确派生词。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroWordFormationView(
    node: MicroNode.WordFormation,
    filled: Map<Int, String>,
    revealed: Boolean,
    onSet: (Int, String) -> Unit,
    onClear: (Int) -> Unit
) {
    val bank = remember(node) {
        (if (node.bank.isNotEmpty()) node.bank else node.items.map { it.answer }).filter { it.isNotBlank() }.shuffled()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        node.items.forEachIndexed { i, item ->
            val word = filled[i]
            val isCorrect = word != null && normalizeAgentShortAnswer(word).equals(normalizeAgentShortAnswer(item.answer), ignoreCase = true)
            val tone = when {
                revealed && isCorrect -> AgentPracticeSuccess
                revealed && word != null -> AgentPracticeWrong
                word != null -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            val label = buildString {
                append(item.base)
                if (item.target.isNotBlank()) append(" (").append(item.target).append(")")
                append(" →")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .widthIn(min = 96.dp)
                        .heightIn(min = 34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(BorderStroke(1.dp, tone.copy(alpha = 0.6f)), RoundedCornerShape(8.dp))
                        .background(if (word != null) tone.copy(alpha = 0.10f) else Color.Transparent)
                        .then(if (!revealed && word != null) Modifier.clickable { onClear(i) } else Modifier)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (revealed && !isCorrect) item.answer else (word ?: "▢"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (word != null || revealed) tone else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (!revealed) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bank.forEach { w ->
                    MicroChip(
                        text = w,
                        background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        foreground = MaterialTheme.colorScheme.onSurface,
                        enabled = true,
                        onClick = {
                            val nextEmpty = node.items.indices.firstOrNull { filled[it] == null }
                            if (nextEmpty != null) onSet(nextEmpty, w)
                        }
                    )
                }
            }
        }
    }
}

// 时间线微元：竖向时间线，每个事件 = 圆点 + 连接线 + 时间/标题/描述。纯展示、不判分。
@Composable
private fun MicroTimelineView(node: MicroNode.Timeline) {
    if (node.events.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.fillMaxWidth()) {
        if (node.title.isNotBlank()) {
            Text(
                node.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        node.events.forEachIndexed { i, ev ->
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.width(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    if (i < node.events.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .weight(1f)
                                .padding(top = 2.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).padding(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (ev.time.isNotBlank()) {
                        Text(ev.time, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(ev.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    if (ev.detail.isNotBlank()) {
                        Text(ev.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// 单词重音微元：把单词拆成音节，点出重读音节（单选，复用 MicroChip/主题）；核对时正确音节绿、误选红。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroStressMarkView(
    node: MicroNode.StressMark,
    selected: Int?,
    revealed: Boolean,
    onSelect: (Int) -> Unit
) {
    val target = node.stress - 1
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🔉 点出重读音节", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.word.isNotBlank()) {
            Text(node.word, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            node.syllables.forEachIndexed { i, syl ->
                val isPicked = selected == i
                val isTarget = i == target
                val (bg, fg) = when {
                    revealed && isTarget -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                    revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                    isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                }
                MicroChip(text = syl, background = bg, foreground = fg, enabled = !revealed, onClick = { onSelect(i) })
            }
        }
        if (revealed) {
            val correctSyl = node.syllables.getOrNull(target).orEmpty()
            if (correctSyl.isNotBlank()) {
                AgentCardKeyValueRow("重读", "第 ${node.stress} 个音节：$correctSyl", AgentPracticeSuccess)
            }
        }
    }
}

// 整句块（语篇排序用）：整宽、可换行的句子块，比 MicroChip 更适合承载长句（点选加入/移出顺序）。
@Composable
private fun MicroSentenceBlock(
    text: String,
    background: Color,
    foreground: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(BorderStroke(1.dp, foreground.copy(alpha = 0.45f)), RoundedCornerShape(10.dp))
            .background(background)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = foreground)
    }
}

// 语篇排序微元：sentences 按正确顺序给出，端上打乱成句块；点句块按序排列（点已排句可移回），核对时对照正确顺序绿/红。可判分。
@Composable
private fun MicroReorderParagraphView(
    node: MicroNode.ReorderParagraph,
    selected: List<Int>,
    revealed: Boolean,
    onAdd: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    val displayOrder = remember(node) { node.sentences.indices.shuffled() }
    val correct = microReorderParagraphCorrect(selected.map { node.sentences.getOrElse(it) { "" } }, node.sentences)
    val assembledTone = when {
        revealed && correct -> AgentPracticeSuccess
        revealed -> AgentPracticeWrong
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("🧩 排出连贯的段落", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("已排顺序：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            if (selected.isEmpty()) {
                Text("（点下方句子按顺序排列，再核对）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            selected.forEachIndexed { pos, sentenceIndex ->
                MicroSentenceBlock(
                    text = "${pos + 1}. ${node.sentences.getOrElse(sentenceIndex) { "" }}",
                    background = assembledTone.copy(alpha = 0.10f),
                    foreground = assembledTone,
                    enabled = !revealed,
                    onClick = { onRemove(pos) }
                )
            }
        }
        if (!revealed) {
            val remaining = displayOrder.filter { it !in selected }
            if (remaining.isNotEmpty()) {
                Text("可选句子：", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    remaining.forEach { sentenceIndex ->
                        MicroSentenceBlock(
                            text = node.sentences[sentenceIndex],
                            background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            foreground = MaterialTheme.colorScheme.onSurface,
                            enabled = true,
                            onClick = { onAdd(sentenceIndex) }
                        )
                    }
                }
            }
        }
        if (revealed) {
            AgentCardKeyValueRow(
                if (correct) "正确" else "参考顺序",
                node.sentences.joinToString(" "),
                if (correct) AgentPracticeSuccess else AgentPracticeWrong
            )
        }
    }
}

// 选异类微元：一组词/项里点出不属于同类的那个（单选，复用 MicroChip/主题）；核对时异类项绿、误选红，并给同类规律说明。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroOddOneOutView(
    node: MicroNode.OddOneOut,
    selected: Int?,
    revealed: Boolean,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(node.prompt.ifBlank { "选出不属于同类的一个" }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            node.items.forEachIndexed { i, item ->
                val isPicked = selected == i
                val isAnswer = item.trim().equals(node.answer.trim(), ignoreCase = true)
                val (bg, fg) = when {
                    revealed && isAnswer -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                    revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                    isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                }
                MicroChip(text = item, background = bg, foreground = fg, enabled = !revealed, onClick = { onSelect(i) })
            }
        }
        if (revealed) {
            AgentCardKeyValueRow("异类", node.answer, AgentPracticeSuccess)
        }
    }
}

// 程度排序微元：items 为「低→高」正确顺序，端上打乱成横向 chip；点 chip 按序排列（点已排可移回），两端显示标尺刻度 from/to。核对对照正确顺序绿/红。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroRankOrderView(
    node: MicroNode.RankOrder,
    selected: List<Int>,
    revealed: Boolean,
    onAdd: (Int) -> Unit,
    onRemove: (Int) -> Unit
) {
    val displayOrder = remember(node) { node.items.indices.shuffled() }
    val correct = microRankOrderCorrect(selected.map { node.items.getOrElse(it) { "" } }, node.items)
    val assembledTone = when {
        revealed && correct -> AgentPracticeSuccess
        revealed -> AgentPracticeWrong
        else -> MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("📊 按程度从低到高排列", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (node.from.isNotBlank() || node.to.isNotBlank()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(node.from.ifBlank { "低" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text("→", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(node.to.ifBlank { "高" }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected.isEmpty()) {
                Text("（点下方词块按程度从低到高排列，再核对）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            selected.forEachIndexed { pos, itemIndex ->
                MicroChip(
                    text = "${pos + 1}. ${node.items.getOrElse(itemIndex) { "" }}",
                    background = assembledTone.copy(alpha = 0.12f),
                    foreground = assembledTone,
                    enabled = !revealed,
                    onClick = { onRemove(pos) }
                )
            }
        }
        if (!revealed) {
            val remaining = displayOrder.filter { it !in selected }
            if (remaining.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    remaining.forEach { itemIndex ->
                        MicroChip(
                            text = node.items[itemIndex],
                            background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            foreground = MaterialTheme.colorScheme.onSurface,
                            enabled = true,
                            onClick = { onAdd(itemIndex) }
                        )
                    }
                }
            }
        }
        if (revealed) {
            AgentCardKeyValueRow(
                if (correct) "正确" else "参考顺序",
                node.items.joinToString(" < "),
                if (correct) AgentPracticeSuccess else AgentPracticeWrong
            )
        }
    }
}

// 把例句里的目标词（忽略大小写、词边界）隐成下划线，避免展示语境时泄露拼写；找不到或异常就原样返回。
private fun maskSpellingWord(example: String, word: String): String {
    val w = word.trim()
    if (w.isEmpty() || example.isBlank()) return example
    return try {
        example.replace(Regex("(?i)\\b" + Regex.escape(w) + "\\b"), "____")
    } catch (_: Throwable) {
        example
    }
}

// 听音拼写微元：🔊 播放整词 / 逐字母朗读；看中文释义 + 字母数提示，把单词打字拼出（example 语境句自动隐去该词）。
// 核对时对照正确拼写绿/红。判分复用 inputText 状态与 microSpellingCorrect（与字母重组同一套拼写归一）。可判分。
@Composable
private fun MicroSpellingBeeView(
    node: MicroNode.SpellingBee,
    speak: (String) -> Unit,
    value: String,
    revealed: Boolean,
    onChange: (String) -> Unit
) {
    val letterCount = remember(node.word) { node.word.count { it.isLetterOrDigit() } }
    val spelledOut = remember(node.word) { node.word.filter { it.isLetterOrDigit() }.toCharArray().joinToString(" ") }
    val maskedExample = remember(node.word, node.example) { maskSpellingWord(node.example, node.word) }
    val correct = microSpellingCorrect(value, node.word)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🔤 听音拼写", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MicroPlayButton("播放单词") { speak(node.word) }
            if (spelledOut.isNotBlank()) MicroPlayButton("逐字母") { speak(spelledOut) }
        }
        if (node.hint.isNotBlank()) {
            AgentCardKeyValueRow("释义", node.hint, MaterialTheme.colorScheme.primary)
        }
        if (letterCount > 0) {
            Text("共 $letterCount 个字母", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!revealed && maskedExample.isNotBlank()) {
            Text("例：$maskedExample", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            if (value.isBlank()) {
                Text("听发音后在此拼写…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                readOnly = revealed,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (revealed && node.word.isNotBlank()) {
            AgentCardKeyValueRow(if (correct) "正确" else "正确拼写", node.word, if (correct) AgentPracticeSuccess else AgentPracticeWrong)
            if (node.example.isNotBlank()) AgentCardInlineNotice("例：${node.example}")
        }
    }
}

// 句型转换微元：展示「转换要求 + 原句」，用户打字写出改写句；核对对照标准答案绿/红（answer + accept 归一容错）。判分复用 inputText 状态与 microSentenceTransformCorrect。可判分。
@Composable
private fun MicroSentenceTransformView(
    node: MicroNode.SentenceTransform,
    value: String,
    revealed: Boolean,
    onChange: (String) -> Unit
) {
    val correct = microSentenceTransformCorrect(value, node.answer, node.accept)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✍️ 句型转换", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            AgentCardKeyValueRow("要求", node.prompt, MaterialTheme.colorScheme.primary)
        }
        if (node.source.isNotBlank()) {
            Text("原句：${node.source}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (node.hint.isNotBlank()) {
            Text(node.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            if (value.isBlank()) {
                Text("在此写出改写后的句子…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                readOnly = revealed,
                singleLine = false,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (revealed && node.answer.isNotBlank()) {
            AgentCardKeyValueRow(if (correct) "正确" else "参考答案", node.answer, if (correct) AgentPracticeSuccess else AgentPracticeWrong)
        }
    }
}

// 开放式填空微元：展示挖空篇章（空位标 (n) ______），下方逐空「打字」作答（无选项/无词库）；核对逐空对照答案，对✓、错显示参考答案。可判分。
@Composable
private fun MicroOpenClozeView(
    node: MicroNode.OpenCloze,
    filled: Map<Int, String>,
    revealed: Boolean,
    onSet: (Int, String) -> Unit
) {
    val segments = remember(node.text) { node.text.split(Regex("_{2,}")) }
    val display = remember(node.text) {
        buildString {
            segments.forEachIndexed { i, seg ->
                append(seg)
                if (i < segments.size - 1) append(" (${i + 1}) ______ ")
            }
        }.trim()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("📝 开放式填空", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        for (i in node.answers.indices) {
            val value = filled[i].orEmpty()
            val ok = revealed && microOpenClozeCorrect(mapOf(0 to value), listOf(node.answers[i]))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("(${i + 1})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (value.isBlank()) {
                        Text("填第 ${i + 1} 空…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { onSet(i, it) },
                        readOnly = revealed,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (revealed) {
                    Text(
                        if (ok) "✓" else node.answers[i],
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (ok) AgentPracticeSuccess else AgentPracticeWrong
                    )
                }
            }
        }
    }
}

// 翻译微元：显示方向徽标（中→英/英→中）+ 原句，用户打字写译文；核对对照参考译文绿/红（answer + accept 归一容错）。判分复用 inputText 状态与 microTranslateCorrect。可判分。
@Composable
private fun MicroTranslateView(
    node: MicroNode.Translate,
    value: String,
    revealed: Boolean,
    onChange: (String) -> Unit
) {
    val correct = microTranslateCorrect(value, node.answer, node.accept)
    val dirLabel = if (node.direction.equals("en2zh", ignoreCase = true)) "英 → 中" else "中 → 英"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🌐 翻译", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(dirLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        if (node.source.isNotBlank()) {
            Text(node.source, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        if (node.hint.isNotBlank()) {
            Text(node.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            if (value.isBlank()) {
                Text("在此输入译文…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                readOnly = revealed,
                singleLine = false,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (revealed && node.answer.isNotBlank()) {
            AgentCardKeyValueRow(if (correct) "正确" else "参考译文", node.answer, if (correct) AgentPracticeSuccess else AgentPracticeWrong)
        }
    }
}

// 听力填空·打字版微元：🔊 播放整句，展示挖空句 (n) ______，下方逐空「打字」填缺词（无词库）；核对逐空对✓/错显示参考答案。判分复用 microOpenClozeCorrect。可判分。
@Composable
private fun MicroListenClozeView(
    node: MicroNode.ListenCloze,
    speak: (String) -> Unit,
    filled: Map<Int, String>,
    revealed: Boolean,
    onSet: (Int, String) -> Unit
) {
    val segments = remember(node.text) { node.text.split(Regex("_{2,}")) }
    val display = remember(node.text) {
        buildString {
            segments.forEachIndexed { i, seg ->
                append(seg)
                if (i < segments.size - 1) append(" (${i + 1}) ______ ")
            }
        }.trim()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🎧 听力填空", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        MicroPlayButton("播放句子") { speak(node.audioText) }
        if (display.isNotBlank()) {
            Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        for (i in node.answers.indices) {
            val value = filled[i].orEmpty()
            val ok = revealed && microOpenClozeCorrect(mapOf(0 to value), listOf(node.answers[i]))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("(${i + 1})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (value.isBlank()) {
                        Text("听到后填第 ${i + 1} 空…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { onSet(i, it) },
                        readOnly = revealed,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (revealed) {
                    Text(
                        if (ok) "✓" else node.answers[i],
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (ok) AgentPracticeSuccess else AgentPracticeWrong
                    )
                }
            }
        }
    }
}

// 句子改错微元：展示含错句 + 可选提示，用户打字写出改正后的整句；核对对照标准答案绿/红，reveal 给错误说明。判分复用 microSentenceTransformCorrect。可判分。
@Composable
private fun MicroErrorCorrectionView(
    node: MicroNode.ErrorCorrection,
    value: String,
    revealed: Boolean,
    onChange: (String) -> Unit
) {
    val correct = microSentenceTransformCorrect(value, node.answer, node.accept)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("❌ 找出并改正错误", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            AgentCardKeyValueRow("提示", node.prompt, MaterialTheme.colorScheme.primary)
        }
        if (node.sentence.isNotBlank()) {
            Text("原句：${node.sentence}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            if (value.isBlank()) {
                Text("在此写出改正后的句子…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                readOnly = revealed,
                singleLine = false,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (revealed && node.answer.isNotBlank()) {
            AgentCardKeyValueRow(if (correct) "正确" else "参考答案", node.answer, if (correct) AgentPracticeSuccess else AgentPracticeWrong)
        }
    }
}

// 补全对话微元：情景对话以聊天气泡展示（首个说话人靠左、其余靠右），下方从 options 选最合适的回应（复用 MicroChoiceView 判分/样式），reveal 给 explanation。可判分。
@Composable
private fun MicroDialogueCompleteView(
    node: MicroNode.DialogueComplete,
    selected: Int?,
    revealed: Boolean,
    onSelect: (Int) -> Unit
) {
    val firstSpeaker = remember(node) { node.turns.firstOrNull()?.speaker.orEmpty() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("💬 补全对话", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        node.turns.forEach { t ->
            val mine = t.speaker.isNotBlank() && t.speaker != firstSpeaker
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    if (t.speaker.isNotBlank()) {
                        Text(t.speaker, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(t.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        MicroChoiceView(
            node = MicroNode.Choice(prompt = "选出最合适的回应", options = node.options, answer = node.answer, multi = false),
            selected = selected,
            revealed = revealed,
            onSelect = onSelect
        )
    }
}

// 单词找词微元：字母网格逐格可点（testTag ws_r_c）；点首字母格再点尾字母格 → 连线判定是否命中某目标词，命中即高亮绿并计入 found。词表下方勾掉已找到；揭示时高亮全部词路径。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroWordSearchView(
    node: MicroNode.WordSearch,
    found: Set<String>,
    anchor: Pair<Int, Int>?,
    revealed: Boolean,
    onTapCell: (Int, Int) -> Unit
) {
    val highlightWords = if (revealed) node.words else found.toList()
    val foundCells = remember(node, found, revealed) {
        highlightWords.mapNotNull { microWordSearchPath(node.grid, it) }.flatten().toSet()
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🔎 单词找词", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        node.grid.forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEachIndexed { c, ch ->
                    val isFound = (r to c) in foundCells
                    val isAnchor = anchor != null && anchor.first == r && anchor.second == c
                    val (bg, fg) = when {
                        isFound -> AgentPracticeSuccess.copy(alpha = 0.18f) to AgentPracticeSuccess
                        isAnchor -> MaterialTheme.colorScheme.primary.copy(alpha = 0.20f) to MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(bg)
                            .testTag("ws_${r}_${c}")
                            .clickable(enabled = !revealed) { onTapCell(r, c) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(ch.uppercase(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = fg)
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            node.words.forEach { w ->
                val got = found.any { it.filter { ch -> ch.isLetterOrDigit() }.equals(w.filter { ch -> ch.isLetterOrDigit() }, ignoreCase = true) }
                Text(
                    (if (got) "✓ " else "") + w,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (got) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (got || revealed) AgentPracticeSuccess else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (got) TextDecoration.LineThrough else null
                )
            }
        }
        if (!revealed) {
            Text("点「首字母格」再点「尾字母格」选词（可横/竖/斜、正反皆可）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// 猜词游戏微元：释义 + 掩码词（已猜字母显形,其余 _）+ 错误/生命计数；A–Z 字母键（testTag hm_X）逐个猜，猜对绿、猜错红扣命；用完命或猜出即止。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroHangmanView(
    node: MicroNode.Hangman,
    guessed: Set<Char>,
    revealed: Boolean,
    onGuess: (Char) -> Unit
) {
    val letters = remember(node.word) { node.word.uppercase().filter { it.isLetter() }.toSet() }
    val g = guessed.map { it.uppercaseChar() }.toSet()
    val wrong = g.count { it !in letters }
    val limit = node.maxWrong.coerceAtLeast(1)
    val solved = letters.isNotEmpty() && letters.all { it in g }
    val busted = wrong >= limit
    val finished = revealed || solved || busted
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🎯 猜词游戏", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.hint.isNotBlank()) AgentCardKeyValueRow("释义", node.hint, MaterialTheme.colorScheme.primary)
        val masked = node.word.uppercase().map { ch ->
            when {
                !ch.isLetter() -> ch.toString()
                ch in g || finished -> ch.toString()
                else -> "_"
            }
        }.joinToString(" ")
        Text(
            masked,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = when { revealed || solved -> AgentPracticeSuccess; busted -> AgentPracticeWrong; else -> MaterialTheme.colorScheme.onSurface }
        )
        Text(
            "错误 $wrong / $limit" + if (busted && !solved) "（已用完）" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (busted && !solved) AgentPracticeWrong else MaterialTheme.colorScheme.onSurfaceVariant
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ('A'..'Z').forEach { ch ->
                val picked = ch in g
                val inWord = ch in letters
                val (bg, fg) = when {
                    picked && inWord -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                    picked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                }
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(bg)
                        .testTag("hm_$ch")
                        .clickable(enabled = !finished && !picked) { onGuess(ch) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(ch.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = fg)
                }
            }
        }
        if (revealed) {
            AgentCardKeyValueRow(if (solved) "已猜出" else "答案", node.word, if (solved) AgentPracticeSuccess else AgentPracticeWrong)
        }
    }
}

// 短文改错微元：逐行给出原文，每行一个可编辑框（预填原句，用户就地改正；有的行本就正确无需动）；核对时逐行框变绿/红，错行给「应为」+说明。判分复用统一文本判分。可判分。
@Composable
private fun MicroProofParagraphView(
    node: MicroNode.ProofParagraph,
    edits: Map<Int, String>,
    revealed: Boolean,
    onEdit: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✏️ 短文改错", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(
            node.prompt.ifBlank { "在每行上直接改正错误（有的行可能没错）" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        node.lines.forEachIndexed { i, line ->
            val value = edits[i] ?: line.text
            val correctAns = line.answer.ifBlank { line.text }
            val ok = revealed && microTypedAnswerCorrect(value, correctAns, emptyList())
            val borderColor = when {
                revealed && ok -> AgentPracticeSuccess
                revealed -> AgentPracticeWrong
                else -> MaterialTheme.colorScheme.outlineVariant
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        BasicTextField(
                            value = value,
                            onValueChange = { onEdit(i, it) },
                            readOnly = revealed,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                if (revealed && !ok) {
                    AgentCardKeyValueRow("应为", correctAns, AgentPracticeWrong)
                    if (line.note.isNotBlank()) AgentCardInlineNotice(line.note)
                }
            }
        }
    }
}

// 看图说话 / 话题独白微元：情景(图片文字描述) + 要点提示 + 复用口语提示组件（自带录音+AI 评分），非统一判分。
@Composable
private fun MicroMonologueView(node: MicroNode.Monologue) {
    val spec = remember(node) {
        AgentCardComponentSpec(type = AgentCardComponent.SpeakingPrompt, text = node.prompt.ifBlank { "看图/看话题，开口说一段" })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🗣 看图说话 / 话题独白", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.scene.isNotBlank()) AgentCardInlineNotice("情景：${node.scene}")
        if (node.points.isNotEmpty()) {
            Text("要点提示", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            node.points.forEachIndexed { i, p -> AgentCardNumberedRow(i + 1, p) }
        }
        AgentCardSpeakingPromptComponent(spec)
    }
}

// 影子跟读微元：🔊 播放示范音 → 复用口语提示组件跟读录音+AI 评分；translation 中文对照可选。非统一判分。
@Composable
private fun MicroShadowingView(node: MicroNode.Shadowing, speak: (String) -> Unit) {
    val spec = remember(node) {
        AgentCardComponentSpec(type = AgentCardComponent.SpeakingPrompt, text = node.text.ifBlank { "跟读示范句" })
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🎧 影子跟读", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.text.isNotBlank()) MicroPlayButton("播放示范") { speak(node.text) }
        if (node.text.isNotBlank()) {
            Text(node.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        if (node.translation.isNotBlank()) {
            Text(node.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AgentCardSpeakingPromptComponent(spec)
    }
}

// 最小对立对听辨微元：🔊 播放 audioText（answer 词）→ 从两个近音词里选（复用 MicroChoiceView 判分/样式）；ipa 与 options 对齐做发音提示。可判分。
@Composable
private fun MicroMinimalPairView(
    node: MicroNode.MinimalPair,
    speak: (String) -> Unit,
    selected: Int?,
    revealed: Boolean,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🔊 最小对立对", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        MicroPlayButton("播放并选择") { speak(node.audioText) }
        MicroChoiceView(
            node = MicroNode.Choice(prompt = node.prompt.ifBlank { "听到的是哪个词？" }, options = node.options, answer = node.answer, multi = false),
            selected = selected,
            revealed = revealed,
            onSelect = onSelect
        )
        if (node.ipa.isNotEmpty()) {
            val ipaLine = node.options.mapIndexed { i, w -> "$w ${node.ipa.getOrElse(i) { "" }}".trim() }
                .filter { it.isNotBlank() }
                .joinToString("    ·    ")
            if (ipaLine.isNotBlank()) {
                Text(ipaLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// 音标认读微元：展示 IPA 音标 + 🔊 例词发音 → 从 options 选含该音的词（复用 MicroChoiceView 判分/样式）。可判分。
@Composable
private fun MicroIpaReadView(
    node: MicroNode.IpaRead,
    speak: (String) -> Unit,
    selected: Int?,
    revealed: Boolean,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🔤 音标认读", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (node.symbol.isNotBlank()) {
                Text(node.symbol, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            if (node.example.isNotBlank()) MicroPlayButton("例词发音") { speak(node.example) }
        }
        MicroChoiceView(
            node = MicroNode.Choice(prompt = node.prompt.ifBlank { "含该音的词是？" }, options = node.options, answer = node.answer, multi = false),
            selected = selected,
            revealed = revealed,
            onSelect = onSelect
        )
    }
}

// 连读/弱读/语调微元：🔊 播放整句 + 高亮连读/弱读片段 marks + note 讲解。展示 + 听，纯学习不判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroSoundLinkView(node: MicroNode.SoundLink, speak: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🔗 连读 / 弱读 / 语调", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.text.isNotBlank()) MicroPlayButton("播放") { speak(node.text) }
        if (node.text.isNotBlank()) {
            Text(node.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        }
        if (node.marks.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.marks.filter { it.isNotBlank() }.forEach { m ->
                    MicroChip(
                        text = m,
                        background = AgentStudyRose.copy(alpha = 0.14f),
                        foreground = AgentStudyRose,
                        enabled = false,
                        onClick = {}
                    )
                }
            }
        }
        if (node.note.isNotBlank()) AgentCardInlineNotice(node.note)
    }
}

// 听力位置标注微元（雅思 Part2 风格）：🔊 播放 audioText + layout 文字描述地图，逐个地点从 options 位置标签里选（复用 MatchInfo 交互/配色）。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroMapLabelView(
    node: MicroNode.MapLabel,
    speak: (String) -> Unit,
    selected: Map<Int, String>,
    revealed: Boolean,
    onSelect: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("🗺 位置标注", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        MicroPlayButton("播放") { speak(node.audioText) }
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        if (node.layout.isNotBlank()) AgentCardInlineNotice(node.layout)
        node.items.forEachIndexed { i, item ->
            val pick = selected[i]
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${i + 1}. ${item.text}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    node.options.forEach { option ->
                        val isPicked = pick == option
                        // 高亮口径与 microMapLabelCorrect 判分一致（归一比对），避免「判对但不见绿」。
                        val isThisCorrect = normalizeAgentShortAnswer(option)
                            .equals(normalizeAgentShortAnswer(item.answer), ignoreCase = true)
                        val (bg, fg) = when {
                            revealed && isThisCorrect -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                            revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                            isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                        }
                        MicroChip(text = option, background = bg, foreground = fg, enabled = !revealed, onClick = { onSelect(i, option) })
                    }
                }
            }
        }
    }
}

// 长音频笔记填空微元：🔊 播放 audioText + 笔记标题，逐空「打字」填缺词（复用 MicroListenClozeView 展示/判分）。可判分。
@Composable
private fun MicroNoteCompleteView(
    node: MicroNode.NoteComplete,
    speak: (String) -> Unit,
    filled: Map<Int, String>,
    revealed: Boolean,
    onSet: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🗒 笔记填空", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.title.isNotBlank()) {
            Text(node.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        }
        MicroListenClozeView(
            node = MicroNode.ListenCloze(audioText = node.audioText, text = node.text, answers = node.answers),
            speak = speak,
            filled = filled,
            revealed = revealed,
            onSet = onSet
        )
    }
}

// 句尾配对微元：每个句子开头从「句尾选项池 endings」里选其正确句尾（复用 MatchInfo 交互/配色）。可判分。
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MicroMatchSentenceEndingsView(
    node: MicroNode.MatchSentenceEndings,
    selected: Map<Int, String>,
    revealed: Boolean,
    onSelect: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("🔗 句尾配对", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        node.stems.forEachIndexed { i, stem ->
            val pick = selected[i]
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${i + 1}. ${stem.text} …", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    node.endings.forEach { ending ->
                        val isPicked = pick == ending
                        // 高亮口径与 microSentenceEndingsCorrect 判分一致（归一比对），避免「判对但不见绿」。
                        val isThisCorrect = normalizeAgentShortAnswer(ending)
                            .equals(normalizeAgentShortAnswer(stem.answer), ignoreCase = true)
                        val (bg, fg) = when {
                            revealed && isThisCorrect -> AgentPracticeSuccess.copy(alpha = 0.16f) to AgentPracticeSuccess
                            revealed && isPicked -> AgentPracticeWrong.copy(alpha = 0.16f) to AgentPracticeWrong
                            isPicked -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) to MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f) to MaterialTheme.colorScheme.onSurface
                        }
                        MicroChip(text = ending, background = bg, foreground = fg, enabled = !revealed, onClick = { onSelect(i, ending) })
                    }
                }
            }
        }
    }
}

// 摘要/流程图填空微元（跨篇选词）：摘要含 ___，从词库 bank 点词依次填空（复用 MicroClozeDragView 交互/判分）。可判分。
@Composable
private fun MicroSummaryCompleteView(
    node: MicroNode.SummaryComplete,
    filled: Map<Int, String>,
    revealed: Boolean,
    onSet: (Int, String) -> Unit,
    onClear: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("🧩 摘要填空", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MicroClozeDragView(
            node = MicroNode.ClozeDrag(text = node.text, bank = node.bank, answers = node.answers),
            filled = filled,
            revealed = revealed,
            onSet = onSet,
            onClear = onClear
        )
    }
}

// 篇章简答微元：逐题给出题干，用户「打字」简答；核对对照参考答案(answer + accept 归一容错)绿/红。可判分。
@Composable
private fun MicroShortAnswerView(
    node: MicroNode.ShortAnswer,
    filled: Map<Int, String>,
    revealed: Boolean,
    onSet: (Int, String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("❓ 篇章简答", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        node.questions.forEachIndexed { i, q ->
            val value = filled[i].orEmpty()
            val ok = revealed && microTypedAnswerCorrect(value, q.answer, q.accept)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${i + 1}. ${q.q}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(
                            BorderStroke(1.dp, if (revealed) (if (ok) AgentPracticeSuccess else AgentPracticeWrong) else MaterialTheme.colorScheme.outlineVariant),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    if (value.isBlank()) {
                        Text("简要作答…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = { onSet(i, it) },
                        readOnly = revealed,
                        singleLine = false,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (revealed && q.answer.isNotBlank()) {
                    AgentCardKeyValueRow(if (ok) "正确" else "参考答案", q.answer, if (ok) AgentPracticeSuccess else AgentPracticeWrong)
                }
            }
        }
    }
}

// 结构化引导写作微元：分阶段脚手架（提纲→段落→成文）+ 复用图表作文同款 AI 四维评分框。非统一判分。
@Composable
private fun MicroGuidedWritingView(node: MicroNode.GuidedWriting, instanceKey: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("✍️ 引导写作", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        if (node.prompt.isNotBlank()) {
            Text(node.prompt, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        }
        if (node.steps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                node.steps.forEachIndexed { i, step ->
                    val head = step.label.ifBlank { "步骤 ${i + 1}" }
                    AgentCardKeyValueRow(head, step.hint, MaterialTheme.colorScheme.primary)
                }
            }
        }
        AgentChartWritingBox(prompt = node.prompt, reference = node.reference, instanceKey = instanceKey)
    }
}
