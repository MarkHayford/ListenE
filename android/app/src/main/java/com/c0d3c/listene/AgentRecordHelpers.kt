package com.c0d3c.listene

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.text

// 记录/工作区辅助逻辑（Record Helpers）：可见工作区标题/被封禁卡片文案/当前记录解析/资料库当前记录/分析结果追加判定等。从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；同包，跨边界 private 按需放宽为 internal。

// 消息不再携带 cardSpec：聊天头部标题不再被卡片标题劫持，恒随工作区标题。
internal fun agentVisibleWorkspaceTitle(activeWorkspace: LearningWorkspace?): String {
    val workspaceTitle = activeWorkspace?.title.orEmpty()
    return agentCardDisplayTitle(workspaceTitle, workspaceTitle)
}

internal fun agentChatReplyText(
    text: String,
    attachments: List<AgentInputAttachment> = emptyList()
): String = text.ifBlank { if (attachments.isNotEmpty()) "已生成文件。" else "" }

internal fun agentShouldAppendAgentReply(
    text: String,
    attachments: List<AgentInputAttachment> = emptyList()
): Boolean = attachments.isNotEmpty() || text.isNotBlank()

internal fun agentCardCurrentRecord(
    activeWorkspace: LearningWorkspace?,
    history: List<HistoryRecord>,
    state: GenerationState,
    activeViewModelWorkspaceId: String?
): HistoryRecord? {
    val workspaceRecords = workspaceHistoryRecords(activeWorkspace, history)
    val success = state as? GenerationState.Success
    if (
        activeWorkspace != null &&
        activeViewModelWorkspaceId == activeWorkspace.id &&
        !success?.recordId.isNullOrBlank() &&
        (
            workspaceHasRecord(activeWorkspace, success?.recordId) ||
                workspaceRecords.any { it.id == success?.recordId } ||
                workspaceRecords.isEmpty()
        )
    ) {
        history.firstOrNull { it.id == success?.recordId }?.let { record ->
            return record.copy(
                selectedAnswers = success?.initialSelectedAnswers.orEmpty().ifEmpty { record.selectedAnswers },
                answersRevealed = record.answersRevealed || (success?.initialAnswersRevealed == true),
                content = success?.content ?: record.content
            )
        }
    }
    return workspaceRecords.firstOrNull()
}

internal fun agentCurrentWorkspaceRecordForEntry(
    activeWorkspace: LearningWorkspace?,
    history: List<HistoryRecord>,
    state: GenerationState,
    activeViewModelWorkspaceId: String?
): HistoryRecord? =
    activeWorkspace?.let {
        agentCardCurrentRecord(
            activeWorkspace = it,
            history = history,
            state = state,
            activeViewModelWorkspaceId = activeViewModelWorkspaceId
        )
    }

internal fun agentLibraryItemCurrentRecord(
    item: UserLibraryItem,
    workspaces: List<LearningWorkspace>,
    history: List<HistoryRecord>,
    state: GenerationState,
    activeViewModelWorkspaceId: String?
): HistoryRecord? {
    val explicitRecordId = userLibraryRecordId(item)
    if (explicitRecordId.isNotBlank()) {
        history.firstOrNull { it.id == explicitRecordId }?.let { return it }
    }
    val workspaceId = userLibraryWorkspaceId(item)
    val workspace = workspaces.firstOrNull { it.id == workspaceId } ?: return null
    val success = state as? GenerationState.Success
    if (
        activeViewModelWorkspaceId == workspace.id &&
        !success?.recordId.isNullOrBlank()
    ) {
        history.firstOrNull { it.id == success?.recordId }?.let { return it }
    }
    return workspaceHistoryRecords(workspace, history).firstOrNull()
}

internal fun agentAnalysisResultBelongsToWorkspace(
    workspace: LearningWorkspace?,
    recordId: String?,
    activeViewModelWorkspaceId: String?
): Boolean {
    if (workspace == null || recordId.isNullOrBlank()) return false
    return workspaceHasRecord(workspace, recordId) || activeViewModelWorkspaceId == workspace.id
}

internal data class AgentAnalysisResultChatAppendDecision(
    val shouldAppend: Boolean,
    val reply: AgentCardReply?
)

internal fun agentAnalysisResultChatAppendDecision(
    workspace: LearningWorkspace?,
    recordId: String?,
    resultSummary: String,
    activeViewModelWorkspaceId: String?,
    existingMessages: List<AgentChatMessage>,
    reply: AgentCardReply
): AgentAnalysisResultChatAppendDecision {
    val belongs = agentAnalysisResultBelongsToWorkspace(
        workspace = workspace,
        recordId = recordId,
        activeViewModelWorkspaceId = activeViewModelWorkspaceId
    )
    if (!belongs) return AgentAnalysisResultChatAppendDecision(false, null)
    val cleanSummary = resultSummary.trim()
    // 分析完成通知已是纯文本（C2），按文案/摘要去重，不再要求带卡。
    val alreadyAppended = existingMessages.any { message ->
        message.role == AgentChatRole.Agent &&
            (
                message.text.trim() == reply.text.trim() ||
                    (cleanSummary.isNotBlank() && message.text.contains(cleanSummary))
                )
    }
    return AgentAnalysisResultChatAppendDecision(
        shouldAppend = !alreadyAppended,
        reply = if (alreadyAppended) null else reply
    )
}
