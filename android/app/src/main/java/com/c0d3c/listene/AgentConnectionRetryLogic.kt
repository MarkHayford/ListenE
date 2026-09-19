package com.c0d3c.listene

// 重试 / 连接（重连）判定与文案的纯逻辑（卡片动作分发随 cardSpec 渲染链路退役）。
// 无 Compose/Android 依赖、可单测；从 AgentListenEApp.kt 抽出，逐字搬移、行为不变。

internal data class AgentRetryRequest(
    val message: String,
    val attachments: List<AgentInputAttachment> = emptyList(),
    val displayText: String? = null,
    val reusesVisibleUserMessage: Boolean = false
)

internal fun agentIsRetryOnlyPrompt(input: String): Boolean {
    val text = input.trim()
    if (text.isBlank()) return false
    return Regex(
        "^(?:重试|再试一次|重新连接|重连|重新请求|重新发送|retry|try\\s+again|reconnect)(?:生成(?:这张)?卡(?:片)?|这个操作|当前操作|一下|一次)?[。.!！]?$",
        RegexOption.IGNORE_CASE
    ).matches(text)
}

internal fun agentRetryUnavailableMessage(): String =
    "没有可重试的上一条请求，请重新输入需求。"

internal fun agentConnectionRetryButtonText(): String = "重新连接"

internal fun agentConnectionFailureTitle(): String = "Agent 连接失败"

internal fun agentConnectionFailureMessage(error: String): String =
    error.trim().ifBlank { "当前与 Agent 的连接已断开，请重新连接后再试。" }

internal fun agentReconnectSuccessToast(): String = "连接已恢复"

internal fun agentReconnectFailureToast(error: String?): String =
    error?.trim()?.takeIf { it.isNotBlank() } ?: "重新连接失败，请稍后再试。"

internal fun agentRetryRequestForError(
    input: String,
    lastRequest: AgentRetryRequest?,
    messages: List<AgentChatMessage> = emptyList()
): AgentRetryRequest? {
    val cleanInput = input.trim()
    lastRequest?.let { return it.copy(reusesVisibleUserMessage = true) }
    if (cleanInput.isNotBlank() && !agentIsRetryOnlyPrompt(cleanInput)) return null
    return messages
        .asReversed()
        .firstOrNull { it.role == AgentChatRole.User && (it.text.isNotBlank() || it.attachments.isNotEmpty()) }
        ?.let { message ->
            AgentRetryRequest(
                message = message.text.trim().ifBlank { "请理解我发送的文件。" },
                attachments = message.attachments,
                displayText = message.text.takeIf { it.isNotBlank() },
                reusesVisibleUserMessage = true
            )
        }
}

internal fun agentInterruptionMessage(error: Throwable): String =
    error.message?.trim()?.takeIf { it.isNotBlank() } ?: "Agent 处理失败，请重试。"

// 判断异常是否为「登录态失效(401/未授权)」——用于触发静默续期/跳登录，而不是弹「连接失败」大卡。
internal fun agentErrorLooksLikeAuthExpiry(error: Throwable?): Boolean {
    val msg = error?.message?.lowercase()?.trim().orEmpty()
    if (msg.isBlank()) return false
    return msg.contains("401") ||
        msg.contains("unauthorized") ||
        msg.contains("登录状态已失效") ||
        msg.contains("登录已过期") ||
        msg.contains("未授权")
}
