package com.c0d3c.listene

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.text
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.add

// 聊天模型 + 工作区创建流程 + 卡片显示/折叠/标题/写作草稿逻辑 + 主题色/CompositionLocal 等核心声明。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

enum class AgentChatRole {
    User,
    Agent
}

data class AgentChatMessage(
    val id: Long,
    val role: AgentChatRole,
    val text: String,
    val attachments: List<AgentInputAttachment> = emptyList(),
    val audioUrl: String = "",
    val failed: Boolean = false,
    // 非空时表示这条 AI 消息携带一张「微元卡」(服务端 /agent/micro 实时拼出的 MicroCard JSON)，渲染走 MicroCardView。
    // cardSpec（旧协议整卡）已彻底退役：消息不再携带 AgentCardSpec，卡片一律经下面的专用字段表达。
    val microCardJson: String = "",
    // 非空时表示用户已对上面的微元卡「核对答案」，内容为作答表 JSON（microCardAnswerSheetJson：
    // 逐题 题面/用户作答/正解/对错）。「AI 分析」意图靠它对练习卡逐题复盘。
    val microAnswersJson: String = "",
    // 非空时表示这条 AI 消息携带一次「拍照答疑」结构化解题结果(SolveResult JSON)，渲染走 AgentSolveResultView。
    val solveJson: String = "",
    // 非空时表示这是一张「已结束的口语陪练」卡（对话快照+评分+可继续），渲染走 AgentRoleplayFinishedCard。
    val roleplayJson: String = "",
    // 非空时表示这是「听力素材锚点」消息：渲染为自包含听力微元（按 id 解析 record；
    // "current" 表示跟随工作区当前素材——存量旧协议锚点迁移时用）。cardSpec 退役后的素材入口。
    val listeningRecordId: String = "",
    // 非空时表示这是「影子跟读」卡（topic/level/sentences JSON），渲染走 AgentCardShadowingComponent。
    val shadowingJson: String = ""
)

internal fun agentShouldCreateWorkspaceForListeningRequest(
    currentWorkspace: LearningWorkspace?,
    materialNeed: String,
    currentRecord: HistoryRecord?
): Boolean {
    val workspace = currentWorkspace ?: return false
    val cleanNeed = materialNeed.trim()
    if (cleanNeed.isBlank()) return false
    val hasExistingMaterial = currentRecord != null ||
        workspace.linkedRecordIds.any { it.isNotBlank() } ||
        workspace.events.any { it.recordId.isNotBlank() } ||
        agentWorkspaceLooksLikeListeningMaterialFlow(workspace)
    if (!hasExistingMaterial) return false

    val requestedTokens = agentListeningWorkspaceTopicTokens(cleanNeed)
    if (requestedTokens.isEmpty()) return false
    val currentTokens = agentListeningWorkspaceTopicTokens(
        listOf(
            workspace.title,
            workspace.need,
            workspace.summary,
            workspace.plan.title,
            workspace.plan.summary,
            workspace.plan.materialPrompt,
            currentRecord?.scene.orEmpty(),
            currentRecord?.content?.title.orEmpty(),
            currentRecord?.content?.script.orEmpty().take(500)
        ).joinToString(" ")
    )
    if (currentTokens.isEmpty()) return false
    return requestedTokens.intersect(currentTokens).isEmpty()
}

internal fun agentShouldCreateFreshWorkspaceForExplicitListeningRequest(
    currentWorkspace: LearningWorkspace?,
    message: String,
    currentRecord: HistoryRecord?
): Boolean {
    val workspace = currentWorkspace ?: return false
    if (!agentLooksLikeFreshListeningMaterialRequest(message)) return false
    if (agentRequestsCurrentWorkspace(message)) return false
    return currentRecord != null ||
        workspace.linkedRecordIds.any { it.isNotBlank() } ||
        workspace.events.any { it.recordId.isNotBlank() } ||
        agentWorkspaceLooksLikeListeningMaterialFlow(workspace)
}

private fun agentLooksLikeFreshListeningMaterialRequest(text: String): Boolean {
    val lower = text.lowercase()
    val listening = listOf("听力", "听音频", "音频", "listening", "audio").any { lower.contains(it) }
    val material = listOf("素材", "material", "practice", "training", "练习", "训练").any { lower.contains(it) }
    val create = listOf("生成", "创建", "来一套", "来一段", "做一套", "出一套", "再生成", "重新生成", "generate", "create", "make").any { lower.contains(it) }
    return listening && material && create
}

private fun agentRequestsCurrentWorkspace(text: String): Boolean {
    val lower = text.lowercase()
    return listOf(
        "当前工作区",
        "这个工作区",
        "同一工作区",
        "继续当前",
        "current workspace",
        "this workspace",
        "same workspace"
    ).any { lower.contains(it) }
}

private fun agentWorkspaceLooksLikeListeningMaterialFlow(workspace: LearningWorkspace): Boolean {
    val contentType = workspace.plan.contentType.lowercase()
    val materialPrompt = workspace.plan.materialPrompt.trim()
    val step = workspace.currentStep.lowercase()
    if (contentType !in setOf("dialogue", "article")) return false
    if (materialPrompt.isBlank()) return false
    return step in setOf("material", "practice", "analysis", "review")
}

private fun agentListeningWorkspaceTopicTokens(text: String): Set<String> {
    val lower = text.lowercase()
    val english = Regex("[a-z][a-z'-]{2,}")
        .findAll(lower)
        .map { it.value.trim('\'', '-') }
        .filter { it.length >= 3 }
        .filterNot { it in agentListeningWorkspaceGenericTokens }
        .toSet()
    val chinese = agentListeningWorkspaceChineseTopicTokens
        .filter { lower.contains(it) }
        .toSet()
    return english + chinese
}

private val agentListeningWorkspaceGenericTokens = setOf(
    "a1", "a2", "b1", "b2", "c1", "c2",
    "accent", "advanced", "answer", "answers", "audio", "beginner", "british", "card", "cards",
    "conversation", "create", "dialog", "dialogue", "easy", "english", "exactly", "exercise",
    "fast", "female", "generate", "hard", "intermediate", "listen", "listening", "male", "material",
    "medium", "normal", "only", "practice", "question", "questions", "quiz", "script", "slow",
    "speaker", "speakers", "speed", "test", "training", "transcript", "voice", "voices",
    "about", "above", "after", "again", "also", "another", "around", "because", "before", "between",
    "could", "detail", "details", "does", "doing", "from", "have", "help", "into", "make", "more",
    "need", "needs", "some", "that", "their", "there", "these", "they", "this", "through", "tonight",
    "want", "wants", "what", "when", "where", "which", "with", "would", "your"
)

private val agentListeningWorkspaceChineseTopicTokens = setOf(
    "餐厅", "预订", "预约", "订座", "点餐", "咖啡", "酒店", "机场", "旅行", "旅游", "校园", "课堂",
    "公园", "会议", "商务", "面试", "购物", "医院", "银行", "图书馆", "新闻", "故事", "讲座"
)


internal fun agentCardComponentStartsExpanded(type: AgentCardComponent): Boolean =
    type in setOf(
        AgentCardComponent.Cloze,
        AgentCardComponent.QuestionSet,
        AgentCardComponent.ShortAnswer,
        AgentCardComponent.SentenceBuilder,
        AgentCardComponent.Ordering,
        AgentCardComponent.WritingOutline,
        AgentCardComponent.SpeakingPrompt,
        AgentCardComponent.Correction,
        AgentCardComponent.ErrorHunt
    )

internal fun agentCardCollapsedBodyRendersContent(
    isLong: Boolean,
    expanded: Boolean,
    hideLongContentWhenCollapsed: Boolean
): Boolean = !isLong || expanded || !hideLongContentWhenCollapsed

internal fun agentCardComponentUsesHiddenCollapsedBody(component: AgentCardComponentSpec): Boolean =
    component.type in setOf(
        AgentCardComponent.Transcript,
        AgentCardComponent.SentenceTranscript
    )

internal fun agentCardComponentHasInteractivePractice(type: AgentCardComponent): Boolean =
    type in setOf(
        AgentCardComponent.Cloze,
        AgentCardComponent.ShortAnswer,
        AgentCardComponent.SentenceBuilder,
        AgentCardComponent.Ordering,
        AgentCardComponent.QuestionSet,
        AgentCardComponent.SpeakingPrompt,
        AgentCardComponent.Correction,
        AgentCardComponent.ErrorHunt
    )

internal fun agentCardAtomBlockLimitsExpandedHeight(
    isLong: Boolean,
    expanded: Boolean,
    componentType: AgentCardComponent
): Boolean = isLong && expanded && !agentCardComponentHasInteractivePractice(componentType)

internal fun agentCardAtomBlockLimitsCollapsedHeight(
    isLong: Boolean,
    expanded: Boolean,
    componentType: AgentCardComponent
): Boolean = isLong && !expanded && !agentCardComponentHasInteractivePractice(componentType)

internal fun agentCardCollapsedMaxHeightDp(componentType: AgentCardComponent): Int =
    if (agentCardComponentHasInteractivePractice(componentType)) 320 else 168


internal fun agentCardAtomBlockShouldRender(component: AgentCardComponentSpec): Boolean =
    AgentCardDisplayPayload.hasContent(component) || component.type in setOf(
        AgentCardComponent.Cloze,
        AgentCardComponent.ShortAnswer,
        AgentCardComponent.SentenceBuilder,
        AgentCardComponent.Ordering,
        AgentCardComponent.QuestionSet
    )


internal enum class AgentTranscriptClipScope(val label: String, val icon: ImageVector) {
    Turns("对话", Icons.Default.Headset),
    Sentences("句子", Icons.Default.GraphicEq)
}

internal val AgentSendArrowUp: ImageVector = ImageVector.Builder(
    name = "AgentSendArrowUp",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 4f)
        lineTo(5f, 11f)
        lineTo(6.45f, 12.45f)
        lineTo(11f, 7.9f)
        lineTo(11f, 20f)
        lineTo(13f, 20f)
        lineTo(13f, 7.9f)
        lineTo(17.55f, 12.45f)
        lineTo(19f, 11f)
        close()
    }
}.build()

internal val AgentPracticeSuccess = Color(0xFF15803D)
internal val AgentPracticeWrong = Color(0xFFDC2626)
internal val AgentStudyBlue = Color(0xFF2563EB)
internal val AgentStudyTeal = Color(0xFF0F766E)
internal val AgentStudyAmber = Color(0xFFA16207)
internal val AgentStudyRose = Color(0xFFE11D48)
internal val AgentStudyViolet = Color(0xFF7C3AED)
internal val LocalAgentCardExpanded = staticCompositionLocalOf { false }
internal val LocalAgentCardComponentInstanceKey = staticCompositionLocalOf { "" }
internal fun agentChatMessageBottomSpacerDp(): Int = 184

internal val AgentChatMessageBottomSpacer = agentChatMessageBottomSpacerDp().dp

internal fun agentPracticeFeedbackExplanation(
    type: AgentCardComponent,
    answer: String,
    prompt: String = "",
    providedExplanation: String = ""
): String {
    val explanation = providedExplanation.trim()
    val cleanAnswer = answer.trim()
    if (cleanAnswer.isBlank()) return explanation
    val promptHint = prompt.trim()
        .replace(Regex("\\s+"), " ")
        .take(96)
        .takeIf { it.isNotBlank() }
        ?.let { "题干关键点：$it。" }
        .orEmpty()
    val tip = when (type) {
        AgentCardComponent.Cloze -> "先看空格前后的主语、时态、介词和固定搭配，判断这里需要哪类词；再把答案放回原句读一遍，确认语法和意思都通顺。"
        AgentCardComponent.ShortAnswer -> "不要只看拼写；还要检查题干关键词是否完整覆盖、时态是否一致、词序是否符合英文表达，避免漏掉必要的功能词。"
        AgentCardComponent.SentenceBuilder -> "先找主语和谓语搭好句子骨架，再放宾语、时间、地点等修饰信息；最后按完整句重读一遍，确认没有多词或漏词。"
        AgentCardComponent.QuestionSet -> "先圈出题干关键词，确认题目问人物、地点、原因还是细节；再回到原文或语境找同义替换，并用不符合题意的选项做排除。"
        else -> "先理解题干，再把答案放回原句或语境中检查，确认语法、含义和上下文都匹配。"
    }
    val fallback = "正确答案：$cleanAnswer。${promptHint}提示：$tip"
    if (explanation.isBlank()) return fallback
    val detailedEnough = Regex("[\\u4e00-\\u9fff]").containsMatchIn(explanation) &&
        explanation.length >= 36 &&
        (explanation.contains("答案") || explanation.contains("因为") || explanation.contains("提示") || explanation.contains("题干"))
    return if (detailedEnough) explanation else "$explanation $fallback"
}

internal fun agentPracticeFeedbackDisplayExplanation(explanation: String, answer: String): String {
    val clean = explanation.trim()
    val cleanAnswer = answer.trim()
    if (clean.isBlank() || cleanAnswer.isBlank()) return clean
    return clean
        .replace(
            Regex("\\s*正确答案：\\s*${Regex.escape(cleanAnswer)}[。.]\\s*"),
            " "
        )
        .replace(Regex("\\s+"), " ")
        .trim()
}

internal fun agentCardWritingDraftSaveableKey(instanceKey: String): String =
    "${instanceKey.ifBlank { "writing_outline" }}:draft"

internal data class AgentWritingDraftUiState(
    val draft: String,
    val evaluated: Boolean
)

internal fun agentWritingDraftWordCount(draft: String): Int =
    draft.trim().split(Regex("\\s+")).count { it.isNotBlank() }

internal fun agentWritingDraftCanEvaluate(draft: String): Boolean =
    draft.trim().isNotBlank()

internal fun agentSpeakingResponseCanComplete(response: String): Boolean =
    agentWritingDraftWordCount(response) >= 12

internal fun agentWritingDraftStateAfterTextChange(
    state: AgentWritingDraftUiState,
    nextDraft: String
): AgentWritingDraftUiState =
    if (state.evaluated) state else state.copy(draft = nextDraft)

internal fun agentWritingDraftStateAfterEvaluate(state: AgentWritingDraftUiState): AgentWritingDraftUiState =
    if (agentWritingDraftCanEvaluate(state.draft)) state.copy(evaluated = true) else state

internal fun agentWritingDraftStateAfterRetry(state: AgentWritingDraftUiState): AgentWritingDraftUiState =
    state.copy(evaluated = false)

internal fun agentWritingDraftFeedbackText(draft: String): String {
    val wordCount = agentWritingDraftWordCount(draft)
    return when {
        wordCount < 40 -> "Draft saved. Add more detail so the writing fully answers the task."
        wordCount > 120 -> "Draft saved. Consider cutting extra words and keeping the message focused."
        else -> "Draft saved. Check task coverage, organization, grammar, and closing tone."
    }
}

internal fun agentCardDisplayTitle(title: String, fallback: String): String {
    val clean = title.trim().replace(Regex("\\s+"), " ")
    if (clean.isBlank()) return fallback
    val normalized = clean.lowercase().replace(Regex("[_\\s-]+"), " ")
    return when {
        normalized == "script" || normalized == "transcript" -> "原文"
        normalized == "audio" -> "音频"
        normalized == "listening audio" -> "听力音频"
        normalized == "question set" -> "题组"
        normalized == "question preview" -> "题目预览"
        Regex("^fill[-\\s]*in[-\\s]*the[-\\s]*blank\\s*questions?$", RegexOption.IGNORE_CASE).matches(clean) -> "填空题"
        normalized == "passage" -> "短文"
        normalized == "sentence transcript" -> "逐句点播"
        normalized == "listening cue" || normalized == "listening cues" -> "听力信号"
        normalized == "sentence builder" -> "组句"
        normalized == "ordering" -> "排序"
        normalized == "short answer" || normalized == "input answer" || normalized == "typed answer" -> "输入答案"
        normalized == "cloze practice" || normalized == "cloze" -> "填空"
        normalized == "writing outline" -> "写作提纲"
        normalized == "speaking prompt" -> "口语提示"
        normalized == "minimal pair" || normalized == "minimal pairs" -> "音素对照"
        normalized == "vocabulary" -> "词汇"
        normalized == "phrase" -> "短语"
        normalized == "grammar" -> "语法"
        normalized == "translation" -> "翻译"
        normalized == "examples" || normalized == "example" -> "例句"
        normalized == "pronunciation" -> "发音"
        normalized == "compare" -> "对比"
        normalized == "correction" -> "纠错"
        normalized == "rubric" -> "评分标准"
        normalized == "word family" -> "词族"
        normalized == "scenario" -> "场景表达"
        normalized == "register" -> "语气转换"
        normalized == "mistake pattern" -> "错因模式"
        agentCardSinglePracticeTitleFromMixedLabel(clean) != null -> agentCardSinglePracticeTitleFromMixedLabel(clean).orEmpty()
        else -> clean
    }
}

internal fun agentCardComponentDisplayTitle(component: AgentCardComponentSpec, fallback: String): String {
    val clean = component.title.trim()
    if (clean.isBlank()) return fallback
    return if (agentCardComponentTitleMentionsOtherPracticeTypes(clean, component.type)) {
        fallback
    } else {
        agentCardDisplayTitle(clean, fallback)
    }
}

internal fun agentCardComponentTitleMentionsOtherPracticeTypes(title: String, type: AgentCardComponent): Boolean {
    val lower = title.lowercase()
    val mentions = mapOf(
        AgentCardComponent.Cloze to Regex("十五\\s*选\\s*\\d+|\\d+\\s*选\\s*\\d+|[一二两三四五六七八九十]+选[一二两三四五六七八九十]+|fill[-\\s]*in[-\\s]*the[-\\s]*blank|cloze|填空|选词"),
        AgentCardComponent.QuestionSet to Regex("question\\s*set|multiple[-\\s]*choice|quiz|题组|选择题"),
        AgentCardComponent.ShortAnswer to Regex("short\\s*answer|typed\\s*answer|input\\s*answer|短答|输入答案"),
        AgentCardComponent.SentenceBuilder to Regex("sentence\\s*builder|word\\s*order|reorder|组句|连词成句"),
        AgentCardComponent.Ordering to Regex("ordering|sequenc|paragraph\\s*order|logical\\s*order|排序")
    )
    return mentions.any { (candidate, pattern) ->
        candidate != type && pattern.containsMatchIn(lower)
    }
}

internal fun agentCardSinglePracticeTitleFromMixedLabel(title: String): String? {
    val clean = title.trim().replace(Regex("\\s+"), " ")
    if (clean.isBlank()) return null
    val lower = clean.lowercase()
    val explicitWordBankTitle = Regex("(十五\\s*选\\s*\\d+|\\d+\\s*选\\s*\\d+|[一二两三四五六七八九十]+选[一二两三四五六七八九十]+)")
        .find(clean)
        ?.value
        ?.replace(Regex("\\s+"), "")
    if (
        explicitWordBankTitle != null &&
        !Regex("sentence\\s*builder|word\\s*order|reorder|组句|连词成句|ordering|sequenc|paragraph\\s*order|logical\\s*order|排序|short\\s*answer|typed\\s*answer|input\\s*answer|短答|输入答案").containsMatchIn(lower)
    ) {
        return explicitWordBankTitle
    }
    val hits = buildList {
        if (Regex("十五\\s*选\\s*\\d+|\\d+\\s*选\\s*\\d+|[一二两三四五六七八九十]+选[一二两三四五六七八九十]+").containsMatchIn(clean)) {
            add(AgentCardComponent.Cloze)
        }
        if (Regex("fill[-\\s]*in[-\\s]*the[-\\s]*blank|cloze|填空|选词").containsMatchIn(lower)) {
            add(AgentCardComponent.Cloze)
        }
        if (Regex("question\\s*set|multiple[-\\s]*choice|quiz|题组|选择题").containsMatchIn(lower)) {
            add(AgentCardComponent.QuestionSet)
        }
        if (Regex("short\\s*answer|typed\\s*answer|input\\s*answer|短答|输入答案").containsMatchIn(lower)) {
            add(AgentCardComponent.ShortAnswer)
        }
        if (Regex("sentence\\s*builder|word\\s*order|reorder|组句|连词成句").containsMatchIn(lower)) {
            add(AgentCardComponent.SentenceBuilder)
        }
        if (Regex("ordering|sequenc|paragraph\\s*order|logical\\s*order|排序").containsMatchIn(lower)) {
            add(AgentCardComponent.Ordering)
        }
    }.distinct()
    val hasMixedLabelSeparator = Regex("[/|+、,，·]|\\b(?:and|with)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clean)
    if (hits.size != 1 || !hasMixedLabelSeparator) return null
    return when (hits.single()) {
        AgentCardComponent.Cloze -> {
            explicitWordBankTitle ?: "填空"
        }
        AgentCardComponent.QuestionSet -> "题组"
        AgentCardComponent.ShortAnswer -> "输入答案"
        AgentCardComponent.SentenceBuilder -> "组句"
        AgentCardComponent.Ordering -> "排序"
        else -> null
    }
}

internal fun agentCardClozeDisplayTitle(component: AgentCardComponentSpec): String {
    val baseTitle = agentCardDisplayTitle(component.title, "填空")
    val answers = AgentCardDisplayPayload.clozeAnswers(component)
    val optionCount = AgentCardDisplayPayload.options(component).size
    val isWordBankTitle = Regex(
        "十五选十|\\d+\\s*选\\s*\\d+|[一二两三四五六七八九十]+选[一二两三四五六七八九十]+|word\\s*bank|选词填空",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(component.title)
    if (!isWordBankTitle || optionCount <= 0 || answers.size <= 1) return baseTitle
    return "${agentCardChineseCountLabel(optionCount)}选${agentCardChineseCountLabel(answers.size)}"
}


private fun agentCardChineseCountLabel(count: Int): String {
    val value = count.coerceAtLeast(0)
    val digits = listOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    if (value <= 10) return if (value == 10) "十" else digits[value]
    if (value < 20) return "十" + if (value % 10 == 0) "" else digits[value % 10]
    if (value < 100) {
        val tens = value / 10
        val ones = value % 10
        return digits[tens] + "十" + if (ones == 0) "" else digits[ones]
    }
    return value.toString()
}

internal fun agentLibraryCardDisplayTitle(itemTitle: String, cardTitle: String = ""): String =
    agentCardDisplayTitle(itemTitle.ifBlank { cardTitle }, "卡片")
