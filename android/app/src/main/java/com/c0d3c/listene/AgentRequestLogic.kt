package com.c0d3c.listene

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.text
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.serialization.json.add
import java.util.Locale

// 听力请求/分析/AI 复盘/计划等纯逻辑 + 资料库种类枚举 + 抽屉页/动作菜单目标模型。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

internal enum class AgentLibraryKind(
    val route: String,
    val title: String
) {
    Cards(
        "cards",
        "卡片库"
    ),
    Files(
        "files",
        "文件库"
    )
}

internal fun agentUriLooksLikeListeningPackage(name: String, mimeType: String): Boolean {
    val cleanMime = mimeType.lowercase().trim()
    val extension = name.substringAfterLast('.', "").lowercase()
    return cleanMime == "application/zip" ||
        cleanMime == "application/x-zip-compressed" ||
        extension == "zip"
}

internal fun agentImportedListeningWorkspaceNeed(record: HistoryRecord): String {
    val title = record.content.title.ifBlank { record.scene }.ifBlank { "导入的听力练习" }
    return "导入听力练习：${title.take(80)}"
}

internal fun agentUserRequestsCurrentAiAnalysis(message: String): Boolean {
    val text = message.trim()
    if (text.isBlank()) return false
    val hasAnalysisCue = Regex(
        "(AI\\s*)?分析|错因|错题|复盘|薄弱点|薄弱项|哪里错|错哪|讲一下错|帮我看|review\\s+my|analy[sz]e|mistake|wrong\\s+answer|weak\\s+point",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text)
    if (!hasAnalysisCue) return false
    val hasNewPracticeCue = Regex(
        "生成|出题|来一套|再来|创建|练习|训练|听力素材|new\\s+practice|create|generate",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text)
    val hasExplicitReviewCue = Regex(
        "分析|错因|复盘|错哪|哪里错|review|analy[sz]e|mistake|wrong\\s+answer|weak\\s+point",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text)
    return !hasNewPracticeCue || hasExplicitReviewCue
}

// 客户端启发式：判断这条用户消息是否在“要生成学习内容”（练习/听力/阅读/写作/单词/翻译/对话…）。
// 仅用于选择加载指示样式（进度条卡 vs 一行小字）；真正意图由后端 decide 决定，故从宽判定，
// 误判只影响指示器外观、不影响功能。听力素材也纳入：生成期间同样显示进度条卡。
internal fun agentUserRequestsPracticeCard(message: String): Boolean {
    val text = message.trim()
    if (text.isBlank()) return false
    return Regex(
        "练习|练一下|出题|做题|刷题|出\\s*\\d*\\s*(?:道|题|份|组|套)|来\\s*\\d*\\s*(?:道|题|组|套)|几道题|题目|" +
            "卡片|测验|默写|听写|完形|七选五|阅读理解|改错|纠错|造句|连词成句|背单词|记单词|" +
            "听力|听一(?:段|篇|则|遍)|听写材料|对话|" +
            "阅读|文章|短文|写作|作文|范文|口语|跟读|翻译|单词|生词|短语|语法|句型|" +
            "生成|出一|来一|做一|写一|给我(?:出|来|做|写|生成|讲|整)|帮我(?:出|来|做|写|生成|练|整)|" +
            "quiz|practice|exercise|worksheet|drill|flashcard|cloze|paraphrase|dictation|" +
            "reading|writing|essay|article|vocabulary|translate|dialogue|listening|grammar|" +
            "make\\s+(?:a\\s+)?(?:card|quiz|exercise)|generate|create",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(text)
}

internal fun agentShouldShowChatAnalysisLoading(
    analysisInProgressRecordId: String?,
    activeWorkspace: LearningWorkspace?,
    activeViewModelWorkspaceId: String?,
    existingMessages: List<AgentChatMessage> = emptyList()
): Boolean {
    // 用“分析进行中的 recordId”这一显式、可观察信号判断，而非易被覆盖的 _uiState==AnalysisLoading，
    // 避免长时间分析过程中指示器被瞬时清掉（表现为“分析中突然消失、过一会又重现”）。
    val recordId = analysisInProgressRecordId?.takeIf { it.isNotBlank() } ?: return false
    if (agentLatestMessageShowsAnalysisProgress(existingMessages)) return false
    val workspace = activeWorkspace ?: return false
    val activeWorkspaceMatches = activeViewModelWorkspaceId.isNullOrBlank() || activeViewModelWorkspaceId == workspace.id
    val ownsRecord = workspaceHasRecord(workspace, recordId)
    return activeWorkspaceMatches && ownsRecord
}

internal fun agentShouldShowChatThinkingLoading(
    loading: Boolean,
    analysisLoading: Boolean
): Boolean = loading && !analysisLoading

internal fun agentLatestMessageShowsAnalysisProgress(messages: List<AgentChatMessage>): Boolean {
    val text = messages.lastOrNull { it.role == AgentChatRole.Agent }?.text?.trim().orEmpty()
    if (text.isBlank()) return false
    if (Regex("分析完成|完成分析|结果如下|summary", RegexOption.IGNORE_CASE).containsMatchIn(text)) return false
    return Regex("正在.*分析|分析.*(?:中|进行中|答题结果)", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
        Regex("(?:AI\\s*)?正在分析(?:中)?", RegexOption.IGNORE_CASE).containsMatchIn(text)
}

internal fun agentMaterialReadyFollowupPrompt(record: HistoryRecord?, workspace: LearningWorkspace): String =
    "听力素材已生成：${record?.content?.title?.ifBlank { record.scene } ?: workspace.title}。请只展示听力音频、原文和核心题目。"

// 「AI 分析」目标选择：工作区里同时可能有已核对的练习卡（消息挂 microAnswersJson）和听力答题记录，
// 取「最近的作答产物」——已核对练习卡消息比最近一条听力素材锚点消息更新时，分析练习卡；否则走听力错因分析。
internal fun agentLatestGradedMicroMessage(messages: List<AgentChatMessage>): AgentChatMessage? =
    messages.lastOrNull { it.microAnswersJson.isNotBlank() && it.microCardJson.isNotBlank() }

internal fun agentPreferMicroAnalysis(
    messages: List<AgentChatMessage>,
    hasListeningRecord: Boolean
): Boolean {
    val micro = agentLatestGradedMicroMessage(messages) ?: return false
    if (!hasListeningRecord) return true
    val anchor = messages.lastOrNull { it.listeningRecordId.isNotBlank() } ?: return true
    return micro.id > anchor.id
}

// 练习卡分析回复文本：摘要 + 成绩 + 逐题错因（最多 4 条）+ 薄弱点 + 建议（与听力 analysisReady 同风格的纯文本通知）。
internal fun agentMicroAnalysisReplyText(result: AnalysisResult, total: Int, correct: Int): String = buildList {
    add(result.summary.takeIf { it.isNotBlank() }?.let { "练习卡分析完成：$it" } ?: "练习卡分析完成。")
    if (total > 0) add("成绩：答对 $correct / $total")
    result.wrongQuestionInsights
        .filter { it.insight.isNotBlank() || it.mistakeType.isNotBlank() }
        .take(4)
        .forEach { w ->
            val head = w.question.trim().ifBlank { "第 ${w.questionIndex + 1} 题" }.take(60)
            val detail = listOf(w.mistakeType.trim(), w.insight.trim()).filter { it.isNotBlank() }.joinToString("：")
            add("· $head —— $detail")
        }
    result.weakPoints.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { points ->
        add("薄弱点：${points.joinToString("；")}")
    }
    result.suggestions.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { tips ->
        add("下一步建议：${tips.joinToString("；")}")
    }
}.joinToString("\n")

// 从作答表 JSON 里取 total/correct（供回复文本用）；解析失败回 (0,0)。
internal fun agentMicroSheetScore(sheetJson: String): Pair<Int, Int> {
    val obj = parseJsonObjectOrNull(sheetJson) ?: return 0 to 0
    return obj.int("total", 0) to obj.int("correct", 0)
}

internal fun agentAnalysisReadyFollowupPrompt(resultSummary: String): String =
    "AI 分析已完成：${resultSummary.trim()}。请根据答题结果生成一张简洁的中文分析卡，只包含反馈、错因和下一步建议。"

internal fun agentCardTextContainsInternalInstructionLeak(value: String): Boolean =
    Regex(
        "(请根据当前工作区|最近对话和用户目标|只实时渲染当前需要|不要输出固定流程|捆绑多个练习模式)",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(value)

internal fun agentListeningExportZipName(record: HistoryRecord): String =
    "${sanitizeExportName(record.content.title.ifBlank { record.scene }.ifBlank { "listening_practice" })}.zip"

internal fun agentListeningExportDocxName(record: HistoryRecord): String =
    "${sanitizeExportName(record.content.title.ifBlank { record.scene }.ifBlank { "listening_practice" })}.docx"

internal fun agentAnalysisRecordForRequest(stored: HistoryRecord?, requested: HistoryRecord): HistoryRecord {
    if (stored == null) return requested
    val revealed = stored.answersRevealed || requested.answersRevealed
    val answers = if (stored.selectedAnswers.isNotEmpty()) stored.selectedAnswers else requested.selectedAnswers
    return stored.copy(
        selectedAnswers = answers,
        answersRevealed = revealed,
        analysisResult = stored.analysisResult ?: requested.analysisResult
    )
}

internal fun agentAiReviewChatStatusMessage(record: HistoryRecord): String? =
    if (record.answersRevealed) null else "请先在练习页核对答案后再使用 AI 分析。"

internal fun agentCanStartAiReview(record: HistoryRecord): Boolean =
    agentAiReviewChatStatusMessage(record) == null

internal data class AgentAiReviewEntryDecision(
    val record: HistoryRecord,
    val canAnalyze: Boolean,
    val statusMessage: String?,
    val shouldAppendStatusMessage: Boolean
)

internal fun agentAiReviewEntryDecision(
    stored: HistoryRecord?,
    requested: HistoryRecord,
    existingMessages: List<AgentChatMessage>
): AgentAiReviewEntryDecision {
    val latest = agentAnalysisRecordForRequest(stored, requested)
    val status = agentAiReviewChatStatusMessage(latest)
    return AgentAiReviewEntryDecision(
        record = latest,
        canAnalyze = status == null,
        statusMessage = status,
        shouldAppendStatusMessage = status?.let { agentShouldAppendAiReviewStatusMessage(it, existingMessages) } == true
    )
}

internal fun agentShouldAppendAiReviewStatusMessage(
    status: String,
    messages: List<AgentChatMessage>
): Boolean {
    val cleanStatus = status.trim()
    if (cleanStatus.isBlank()) return false
    return messages.lastOrNull { it.role == AgentChatRole.Agent }?.text?.trim() != cleanStatus
}

internal fun agentIsGeneratedWorkspaceDescription(value: String): Boolean =
    value.contains("已按首句建立学习上下文") ||
        value.contains("首句问题上下文") ||
        value.contains("后续每条消息会独立判断") ||
        value.contains("后续每条消息都会独立判断") ||
        value.contains("AI 会根据对话实时生成下一步")

internal fun agentPlanItemToEpochMillis(dayOffset: Int, hour: Int, minute: Int): Long {
    val cal = java.util.Calendar.getInstance()
    cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset.coerceIn(0, 365))
    cal.set(java.util.Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
    cal.set(java.util.Calendar.MINUTE, minute.coerceIn(0, 59))
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

internal fun agentPlanScheduleLabel(scheduledAt: Long, recurrence: String): String {
    val timeStr = if (scheduledAt > 0L) {
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(scheduledAt))
    } else {
        "未设时间"
    }
    val rec = when (recurrence) {
        "daily" -> "每天"
        "weekly" -> "每周"
        else -> ""
    }
    return if (rec.isNotBlank()) "$rec $timeStr" else timeStr
}

internal sealed class AgentDrawerPage {
    data object Home : AgentDrawerPage()
    data object Workspaces : AgentDrawerPage()
    data object Plans : AgentDrawerPage()
    data object Progress : AgentDrawerPage()
    data object Review : AgentDrawerPage()
    data object Vocab : AgentDrawerPage()
    data object Settings : AgentDrawerPage()
    data object Help : AgentDrawerPage()
    data object Account : AgentDrawerPage()
    data class Library(val kind: AgentLibraryKind) : AgentDrawerPage()
}

internal data class AgentWorkspaceActionMenuTarget(
    val workspace: LearningWorkspace,
    val anchorTopLeft: IntOffset,
    val anchorSize: IntSize
)

internal data class AgentLibraryFileActionMenuTarget(
    val item: UserLibraryItem,
    val anchorTopLeft: IntOffset,
    val anchorSize: IntSize
)

internal data class AgentLibraryCardActionMenuTarget(
    val item: UserLibraryItem,
    val anchorTopLeft: IntOffset,
    val anchorSize: IntSize
)
