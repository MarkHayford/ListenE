package com.c0d3c.listene

// 「当前题目 / 题库」导出意图识别与导出规格的纯逻辑（无 Compose/Android 依赖、可单测）。
// 从 AgentListenEApp.kt 抽出，逐字搬移、行为不变；均为 internal，同模块调用点无需改动。

import java.util.Locale

internal fun agentUserRequestsCurrentQuestionExport(message: String): Boolean {
    if (
        AgentConversationService.wantsSentenceBuilderPractice(message) ||
        AgentConversationService.wantsClozePractice(message) ||
        AgentConversationService.wantsWritingPractice(message)
    ) {
        return false
    }
    val exportMessage = stripNegatedAgentQuestionExportTerms(message)
    val lower = exportMessage.lowercase(Locale.ROOT)
    val normalized = lower.replace(Regex("[_\\-]+"), " ")
    val fuzzyCurrentExportHit = Regex(
        "(?:刚才|刚刚|当前|这个|这套|那套|上面|本套).{0,10}(?:导出|下载|发给我|发送|分享|保存|文件|包|打包)|" +
            "(?:导出|下载|发给我|发送|分享|保存|打包|做成|整理成).{0,10}(?:刚才|刚刚|当前|这个|这套|那套|上面|本套)|" +
            "(?:我要|给我|生成|做成|发我).{0,6}(?:文件卡片|文件|包)|" +
            "(?:export|send|save|share|download).{0,24}(?:previous|current|last|this).{0,16}(?:set|practice|quiz|questions?).{0,16}(?:file|document|package)?|" +
            "(?:previous|current|last|this).{0,16}(?:set|practice|quiz|questions?|listening).{0,24}(?:export|send|save|share|download|file|document|package|zip)|" +
            "(?:export|send|save|share|download).{0,24}(?:previous|current|last|this).{0,16}(?:listening).{0,16}(?:file|document|package|zip)?",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(exportMessage) || Regex(
        "(?:export|send|save|share|download).{0,24}(?:previous|current|last|this).{0,16}(?:set|practice|quiz|questions?).{0,16}(?:file|document|package)?|" +
            "(?:previous|current|last|this).{0,16}(?:set|practice|quiz|questions?|listening).{0,24}(?:export|send|save|share|download|file|document|package|zip)|" +
            "(?:export|send|save|share|download).{0,24}(?:previous|current|last|this).{0,16}(?:listening).{0,16}(?:file|document|package|zip)?",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(normalized)
    val exportHit = listOf(
        "导出", "下载", "发给我", "生成文件", "文件",
        "保存", "分享", "发送", "做成", "整理成", "压缩包", "听力包", "打包",
        "export", "download", "save", "share", "file", "document", "docx", "zip"
    ).any { lower.contains(it) || normalized.contains(it) } ||
        Regex("\\bsend\\b.{0,36}(?:previous|current|last|this|file|document|docx|zip|package|questions?|quiz|worksheet|set)|(?:previous|current|last|this|file|document|docx|zip|package|questions?|quiz|worksheet|set).{0,36}\\bsend\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
        fuzzyCurrentExportHit ||
        Regex("(?:只要|仅要).{0,12}(?:题|题目|答案).{0,12}(?:不要|不需要|无|没有).{0,8}(?:音频|录音)", RegexOption.IGNORE_CASE).containsMatchIn(exportMessage) ||
        Regex("\\bword\\s+(?:file|document|doc|format)\\b", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    val questionHit = listOf(
        "题", "题目", "问题", "答案", "解析", "练习题",
        "试题", "习题", "题组", "测验", "听力包", "听力练习", "练习",
        "question", "questions", "quiz", "worksheet", "answer", "answers", "listening practice", "practice", "exercise"
    ).any { lower.contains(it) || normalized.contains(it) }
    val audioPackageHit = Regex(
        "(?:打包|压缩包|zip|导出|下载|发给我|发送|保存).{0,12}(?:录音|音频|audio)|(?:录音|音频|audio).{0,12}(?:打包|压缩包|zip|导出|下载|发给我|发送|保存)",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(exportMessage)
    val currentFormatOnlyHit = Regex(
        "(?:导出|下载|发给我|发送|保存|分享|export|download|send|save|share).{0,12}(?:zip|docx|word\\b|文档)|(?:zip|docx|word\\b|文档).{0,12}(?:导出|下载|发给我|发送|保存|分享|export|download|send|save|share)",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(exportMessage)
    return exportHit && (questionHit || audioPackageHit || currentFormatOnlyHit || fuzzyCurrentExportHit)
}

internal fun stripNegatedAgentQuestionExportTerms(message: String): String =
    message.replace(
        Regex(
            "(?:不要|不需要|不用|无需|别|别加|无|没有).{0,18}(?:题目|试题|习题|练习题|选择题|听力题|题组|测验|文档|文件|压缩包|素材包|quiz|questions?|question\\s*set|worksheet|docx|word|txt|markdown|md|json|csv|html|zip)|" +
                "\\b(?:no|without)\\s+(?:extra\\s+)?(?:questions?|question\\s*set|quiz|worksheet|docx|word\\s*(?:document|doc|file)?|file|txt|markdown|md|json|csv|html|zip)\\b",
            RegexOption.IGNORE_CASE
        ),
        " "
    )

internal enum class AgentCurrentQuestionExportFormat {
    ZipWithAudio,
    DocxWithoutAudio
}

internal data class AgentCurrentQuestionExportSpec(
    val format: AgentCurrentQuestionExportFormat,
    val fileName: String,
    val mimeType: String
)

internal const val AGENT_DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

internal fun agentCurrentQuestionExportFormat(message: String): AgentCurrentQuestionExportFormat {
    val lower = message.lowercase(Locale.ROOT)
    val noAudio = Regex(
        "(?:只要|仅要|不要|不需要|无|没有).{0,14}(?:音频|录音)|(?:只要|仅要).{0,12}(?:题|题目|答案).{0,12}(?:不要|不需要|无|没有).{0,8}(?:音频|录音)|(?:no|without)\\s+audio",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(message)
    val docx = lower.contains("docx") ||
        Regex("\\bword\\b|\\bdoc\\b|word\\s+(?:file|document|doc|format)|word\\s*文档", RegexOption.IGNORE_CASE)
            .containsMatchIn(message)
    val zip = lower.contains("zip") ||
        listOf("压缩包", "听力包", "打包").any { lower.contains(it) } ||
        Regex("\\bpackage\\b", RegexOption.IGNORE_CASE).containsMatchIn(message)
    val audioIncluded = Regex(
        "(?:带|含|包含|放|加入|附上).{0,12}(?:音频|录音)|(?:with|include|including)\\s+audio",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(message)
    return if (noAudio || docx) {
        AgentCurrentQuestionExportFormat.DocxWithoutAudio
    } else if (zip || audioIncluded) {
        AgentCurrentQuestionExportFormat.ZipWithAudio
    } else {
        AgentCurrentQuestionExportFormat.ZipWithAudio
    }
}

internal fun agentCurrentQuestionExportSpec(
    record: HistoryRecord?,
    message: String
): AgentCurrentQuestionExportSpec? {
    if (record == null || record.content.questions.isEmpty()) return null
    val format = agentCurrentQuestionExportFormat(message)
    return when (format) {
        AgentCurrentQuestionExportFormat.ZipWithAudio -> AgentCurrentQuestionExportSpec(
            format = format,
            fileName = agentListeningExportZipName(record),
            mimeType = "application/zip"
        )
        AgentCurrentQuestionExportFormat.DocxWithoutAudio -> AgentCurrentQuestionExportSpec(
            format = format,
            fileName = agentListeningExportDocxName(record),
            mimeType = AGENT_DOCX_MIME_TYPE
        )
    }
}

internal fun agentCurrentQuestionExportSuccessReply(format: AgentCurrentQuestionExportFormat): String =
    when (format) {
        AgentCurrentQuestionExportFormat.ZipWithAudio -> "已导出当前听力题 ZIP 包，包含题目、原文和听力录音。"
        AgentCurrentQuestionExportFormat.DocxWithoutAudio -> "已导出当前听力题 Word 文档，包含题目和答案，不包含录音。"
    }

internal fun agentCurrentQuestionExportFailureReply(format: AgentCurrentQuestionExportFormat): String =
    when (format) {
        AgentCurrentQuestionExportFormat.ZipWithAudio -> "听力包导出失败，请确认当前题目有可用录音后重试。"
        AgentCurrentQuestionExportFormat.DocxWithoutAudio -> "Word 文档导出失败，请稍后重试。"
    }

internal fun agentCurrentQuestionExportUnavailableReply(record: HistoryRecord?): String =
    if (record == null || record.content.questions.isEmpty()) "当前工作区还没有可导出的题目。"
    else "当前题目暂时无法导出，请稍后重试。"

internal data class AgentCardQuestionBankExportSpec(
    val fileName: String,
    val mimeType: String
)

private val agentCardQuestionBankExportableTypes = setOf(
    AgentCardComponent.QuestionSet,
    AgentCardComponent.Cloze,
    AgentCardComponent.ShortAnswer,
    AgentCardComponent.SentenceBuilder,
    AgentCardComponent.Ordering
)

internal fun agentQuestionExportShouldPreferListening(message: String): Boolean {
    val lower = message.lowercase(Locale.ROOT)
    val noAudio = Regex(
        "(?:不要|不需要|无|没有).{0,8}(?:音频|录音)|(?:no|without)\\s+audio",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(message)
    val listeningPackage = Regex(
        "听力|压缩包|打包|zip|listening|package",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(lower)
    val audioIncluded = !noAudio && Regex(
        "录音|音频|audio|recording",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(lower)
    return listeningPackage || audioIncluded
}

// 微元卡题库选择（cardSpec 退役后的聊天练习卡形态）：取最近一条含可导出题目的微元卡消息。
internal data class AgentMicroQuestionBankSelection(
    val messageId: Long,
    val card: MicroCard
)

internal fun latestMicroQuestionBankCardSelection(messages: List<AgentChatMessage>): AgentMicroQuestionBankSelection? =
    messages.asReversed()
        .asSequence()
        .filter { it.role == AgentChatRole.Agent && it.microCardJson.isNotBlank() }
        .mapNotNull { message ->
            MicroCardParser.parse(message.microCardJson)
                ?.takeIf(::microCardCanExportQuestionBank)
                ?.let { AgentMicroQuestionBankSelection(message.id, it) }
        }
        .firstOrNull()

internal fun agentAttachmentFileTestTag(name: String): String =
    "agent_attachment_file_" + name
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "file" }

internal fun agentCardQuestionBankExportSuccessReply(): String =
    "已导出刚才那套题库 Word 文档，包含题目和答案。"

internal fun agentCardQuestionBankExportFailureReply(): String =
    "题库 Word 文档导出失败，请稍后重试。"
