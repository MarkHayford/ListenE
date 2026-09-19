package com.c0d3c.listene

// 练习卡交互状态机：选择 / 翻译 / 语域 / 完形 / 短答等纯逻辑（无 Compose）。
// 从 AgentListenEApp.kt 抽出（零行为变更），由 AgentCardInteractionLogicTest 覆盖。

internal data class AgentMaterialQuestionPreviewActionState(
    val text: String,
    val enabled: Boolean,
    val disabledReason: String = ""
)

internal fun agentMaterialQuestionPreviewActionState(
    questionCount: Int,
    answeredCount: Int,
    revealed: Boolean
): AgentMaterialQuestionPreviewActionState {
    val total = questionCount.coerceAtLeast(0)
    val answered = answeredCount.coerceIn(0, total)
    val enabled = if (revealed) total > 0 else total > 0 && answered >= total
    val disabledReason = when {
        enabled -> ""
        total <= 0 -> "暂无题目可核对"
        revealed -> "暂无题目可分析"
        answered <= 0 -> "请先作答"
        else -> "请先完成全部题目"
    }
    return AgentMaterialQuestionPreviewActionState(
        text = if (revealed) "交给 AI 分析" else "核对答案",
        enabled = enabled,
        disabledReason = disabledReason
    )
}

internal fun agentQuestionPagerTargetIndex(
    currentIndex: Int,
    questionCount: Int,
    delta: Int
): Int {
    val lastIndex = questionCount - 1
    if (lastIndex <= 0) return 0
    return (currentIndex.coerceIn(0, lastIndex) + delta.coerceIn(-1, 1)).coerceIn(0, lastIndex)
}

internal fun agentQuestionIndexAfterAnswer(
    currentIndex: Int,
    questionCount: Int,
    autoAdvance: Boolean = false
): Int =
    if (autoAdvance) agentQuestionPagerTargetIndex(currentIndex, questionCount, 1)
    else currentIndex.coerceIn(0, (questionCount - 1).coerceAtLeast(0))

internal fun agentQuestionOptionEnabled(revealed: Boolean): Boolean = !revealed

internal data class AgentSingleChoiceUiState(
    val selectedIndex: Int?,
    val revealed: Boolean
)



internal data class AgentTranslationPracticeUiState(
    val answers: List<String>,
    val revealed: Boolean
)



internal fun normalizeAgentTranslationAnswer(value: String): String =
    normalizeAgentShortAnswer(value)
        .replace(Regex("[。！？!?.,，；;：:\\s]+"), "")
        .lowercase()

internal fun agentTranslationAnswerMatches(input: String, answer: String): Boolean {
    val normalizedInput = normalizeAgentTranslationAnswer(input)
    val normalizedAnswer = normalizeAgentTranslationAnswer(answer)
    return normalizedInput.isNotBlank() && normalizedInput == normalizedAnswer
}


internal data class AgentRegisterPracticeUiState(
    val answers: List<String>,
    val revealed: Boolean
)



internal fun normalizeAgentRegisterAnswer(value: String): String =
    normalizeAgentShortAnswer(value)
        .replace(Regex("[.!?。！？,，；;：:\\s]+"), "")
        .lowercase()

internal fun agentRegisterAnswerMatches(input: String, answer: String): Boolean {
    val normalizedInput = normalizeAgentRegisterAnswer(input)
    val normalizedAnswer = normalizeAgentRegisterAnswer(answer)
    return normalizedInput.isNotBlank() && normalizedInput == normalizedAnswer
}


internal data class AgentMultiBlankClozeUiState(
    val selected: List<String>,
    val activeBlank: Int,
    val revealed: Boolean
)

internal fun Int.coerceInClozeSelection(selected: List<String>): Int =
    if (selected.isEmpty()) 0 else coerceIn(selected.indices)

internal data class AgentShortAnswerUiState(
    val text: String,
    val revealed: Boolean
)

internal fun agentShortAnswerStateAfterTextChange(
    state: AgentShortAnswerUiState,
    text: String
): AgentShortAnswerUiState =
    if (state.revealed) state else state.copy(text = text)

internal fun agentShortAnswerStateAfterRetry(state: AgentShortAnswerUiState): AgentShortAnswerUiState =
    state.copy(revealed = false)

// ---- 句子改错 / 校对找错（点选错误位置）纯逻辑 ----
// 用户点选自己认为有错的 token，再核对：选对=命中、误选=假阳性、漏选=遗漏。
// 错误 token 位置优先由「错句↔正确句」词级 diff 推导（兼容旧的 "wrong -> correct" 数据），
// 篇章找错则把每处错误片段在短文里定位。

internal data class AgentFindErrorLine(
    val tokens: List<String>,
    val errorIndices: Set<Int>,
    val corrected: String,
    val explanation: String
)

internal data class AgentFindErrorUiState(
    val selected: Set<Int>,
    val revealed: Boolean
)




internal fun agentSplitErrorTokens(text: String): List<String> =
    text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

private val AGENT_ERROR_TOKEN_TRIM = Regex("^\\p{P}+|\\p{P}+$")

internal fun normalizeAgentErrorToken(token: String): String =
    token.trim().replace(AGENT_ERROR_TOKEN_TRIM, "").lowercase()

private val AGENT_CORRECTION_MISTAKE_PREFIX =
    Regex("^(?:mistake|error|错误|错因)\\s*[:：]", RegexOption.IGNORE_CASE)

private val AGENT_CORRECTION_ARROW = Regex("\\s*(?:->|=>|→|⇒)\\s*")

// 把一条 "wrong -> correct" 解析为 pair；错因说明类（Mistake: ...）不是改错对，返回 null。
internal fun agentSplitCorrectionPair(raw: String): AgentCardPair? {
    val text = raw.trim()
    if (text.isBlank() || AGENT_CORRECTION_MISTAKE_PREFIX.containsMatchIn(text)) return null
    val parts = AGENT_CORRECTION_ARROW.split(text, limit = 2)
    if (parts.size != 2) return null
    val left = parts[0].trim()
    val right = parts[1].trim()
    if (left.isBlank() || right.isBlank()) return null
    return AgentCardPair(left = left, right = right, hint = "")
}

// 归一化改错题对：优先用结构化 pairs（left=错句, right=正确句, hint=解析），否则解析 items 里的箭头对。
internal fun agentCorrectionPairs(pairs: List<AgentCardPair>, items: List<String>): List<AgentCardPair> {
    val fromPairs = pairs.filter { it.left.isNotBlank() && it.right.isNotBlank() }
    if (fromPairs.isNotEmpty()) return fromPairs
    return items.mapNotNull { agentSplitCorrectionPair(it) }
}


// 词级 LCS：返回「错句」里未被对齐到「正确句」的 token 下标（即被替换/删除处）。
internal fun agentFindErrorIndicesByDiff(wrong: List<String>, correct: List<String>): Set<Int> {
    if (wrong.isEmpty()) return emptySet()
    if (correct.isEmpty()) return wrong.indices.toSet()
    val a = wrong.map { normalizeAgentErrorToken(it) }
    val b = correct.map { normalizeAgentErrorToken(it) }
    val n = a.size
    val m = b.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val matched = HashSet<Int>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> { matched.add(i); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> i++
            else -> j++
        }
    }
    return (0 until n).filterNot { it in matched }.toSet()
}

// 在短文 token 序列里定位某个错误片段，返回覆盖到的 token 下标。
internal fun agentLocatePhraseTokenIndices(passage: List<String>, phrase: String): Set<Int> {
    val target = agentSplitErrorTokens(phrase).map { normalizeAgentErrorToken(it) }.filter { it.isNotBlank() }
    if (target.isEmpty()) return emptySet()
    val norm = passage.map { normalizeAgentErrorToken(it) }
    if (target.size <= norm.size) {
        for (start in 0..(norm.size - target.size)) {
            var ok = true
            for (k in target.indices) {
                if (norm[start + k] != target[k]) { ok = false; break }
            }
            if (ok) return (start until start + target.size).toSet()
        }
    }
    val single = target.toSet()
    return norm.indices.filter { norm[it] in single }.toSet()
}

internal fun agentApplyCorrectionReplacement(text: String, wrong: String, correct: String): String {
    if (wrong.isBlank()) return text
    val idx = text.indexOf(wrong, ignoreCase = true)
    return if (idx >= 0) text.substring(0, idx) + correct + text.substring(idx + wrong.length) else text
}

internal fun agentBuildCorrectionLines(pairs: List<AgentCardPair>): List<AgentFindErrorLine> =
    pairs.mapNotNull { pair ->
        val wrong = pair.left.trim()
        if (wrong.isBlank()) return@mapNotNull null
        val correct = pair.right.trim()
        val tokens = agentSplitErrorTokens(wrong)
        if (tokens.isEmpty()) return@mapNotNull null
        val errorIndices = if (correct.isNotBlank()) {
            agentFindErrorIndicesByDiff(tokens, agentSplitErrorTokens(correct))
        } else {
            emptySet()
        }
        AgentFindErrorLine(
            tokens = tokens,
            errorIndices = errorIndices,
            corrected = correct.ifBlank { wrong },
            explanation = pair.hint.trim()
        )
    }

internal fun agentBuildErrorHuntLine(passage: String, pairs: List<AgentCardPair>): AgentFindErrorLine? {
    val tokens = agentSplitErrorTokens(passage)
    if (tokens.isEmpty()) return null
    val errorIndices = sortedSetOf<Int>()
    var corrected = passage.trim()
    val notes = mutableListOf<String>()
    pairs.forEach { pair ->
        val wrong = pair.left.trim()
        val right = pair.right.trim()
        if (wrong.isNotBlank()) {
            errorIndices.addAll(agentLocatePhraseTokenIndices(tokens, wrong))
            if (right.isNotBlank()) corrected = agentApplyCorrectionReplacement(corrected, wrong, right)
        }
        val edit = listOf(wrong, right).filter { it.isNotBlank() }.joinToString(" → ")
        val note = listOf(edit, pair.hint.trim()).filter { it.isNotBlank() }.joinToString("  ")
        if (note.isNotBlank()) notes.add(note)
    }
    return AgentFindErrorLine(
        tokens = tokens,
        errorIndices = errorIndices.toSet(),
        corrected = corrected,
        explanation = notes.joinToString("\n")
    )
}

