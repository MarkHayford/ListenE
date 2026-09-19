package com.c0d3c.listene

// 聊天消息的保留/清洗/去重合并、追加滚动与锚定、回复语音合成判定等纯逻辑。
// 无 Compose/Android 依赖、可单测；从 AgentListenEApp.kt 抽出，逐字搬移、行为不变。

private val agentLegacyBlockedChatTextPattern = Regex(
    "AI\\s*卡片.*(?:没有生成成功|生成失败|重置|坏卡|parse\\s*failed)|点击重试|换个说法再试",
    RegexOption.IGNORE_CASE
)

internal fun agentShouldKeepStoredChatMessage(message: AgentChatMessage): Boolean {
    if (message.role != AgentChatRole.Agent) return true
    if (message.attachments.isNotEmpty() || message.audioUrl.isNotBlank()) return true
    return !agentLegacyBlockedChatTextPattern.containsMatchIn(message.text)
}

// 存量旧协议卡的读档迁移（丢卡/素材锚点转 listeningRecordId）在 AgentChatMessageStore 解析层完成；
// 这里只做消息级保留判定与空消息过滤。
internal fun sanitizeStoredAgentChatMessages(messages: List<AgentChatMessage>): List<AgentChatMessage> =
    messages.filter(::agentShouldKeepStoredChatMessage)
        .filter { message ->
            message.text.isNotBlank() || message.attachments.isNotEmpty() ||
                message.audioUrl.isNotBlank() || message.microCardJson.isNotBlank() ||
                message.solveJson.isNotBlank() || message.roleplayJson.isNotBlank() ||
                message.listeningRecordId.isNotBlank() || message.shadowingJson.isNotBlank()
        }

internal fun mergeAgentChatMessagesForSync(
    current: List<AgentChatMessage>,
    incoming: List<AgentChatMessage>
): List<AgentChatMessage> {
    val seenIds = linkedSetOf<Long>()
    val seenContent = linkedSetOf<String>()
    return (incoming + current)
        .sortedBy { it.id }
        .filter { message ->
            val contentKey = listOf(
                message.role.name,
                message.text.trim(),
                message.attachments.joinToString("|") { attachment ->
                    listOf(attachment.name, attachment.mimeType, attachment.localPath, attachment.sourceRecordId).joinToString(":")
                },
                message.audioUrl
            ).joinToString("||")
            val fresh = message.id !in seenIds && contentKey !in seenContent
            seenIds.add(message.id)
            seenContent.add(contentKey)
            fresh
        }
        .takeLast(80)
}

// hasCardPayload：消息是否携带卡片类专用字段（micro/solve/roleplay/listeningRecordId/shadowing 任一非空）。
internal fun agentShouldScrollForAppendedChat(
    role: AgentChatRole,
    hasCardPayload: Boolean,
    attachments: List<AgentInputAttachment>,
    explicitScrollToBottom: Boolean
): Boolean =
    explicitScrollToBottom ||
        role == AgentChatRole.User ||
        (role == AgentChatRole.Agent && !hasCardPayload) ||
        attachments.isNotEmpty()

internal fun agentShouldAnchorAppendedCardAtTop(
    role: AgentChatRole,
    hasCardPayload: Boolean,
    explicitScrollToBottom: Boolean
): Boolean =
    !explicitScrollToBottom &&
        role == AgentChatRole.Agent &&
        hasCardPayload

internal fun agentCardAnchorAfterAppend(previousAnchor: Long?, appendedCard: Long?): Long? =
    appendedCard

internal fun agentShouldAutoScrollOnIme(imeVisible: Boolean, chatInputFocused: Boolean): Boolean =
    imeVisible && chatInputFocused

// cardSpec 退役后消息文本即为可信展示文本；历史上针对 AI 卡文案的漏答案清洗随卡片生成链路一并退役。
internal fun sanitizeAgentChatDisplayText(text: String): String = text.trim()

internal fun shouldSynthesizeAgentReplySpeech(
    replyMode: String,
    text: String,
    attachments: List<AgentInputAttachment>
): Boolean =
    replyMode == "voice" &&
        text.isNotBlank() &&
        attachments.isEmpty()

internal fun agentMaterialGenerationIntroText(materialNeed: String, introText: String): String =
    "素材生成中。完成后会自动显示音频、原文和题目。"
