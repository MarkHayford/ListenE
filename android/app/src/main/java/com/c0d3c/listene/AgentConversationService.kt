package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class AgentConversationIntent {
    Chat,
    NewListeningPractice,
    PracticeCard,
    StudyPlan
}

// AI 生成的计划项（相对时间）：dayOffset 相对今天的天数，hour/minute 为当天时刻，recurrence=none/daily/weekly。
data class AgentPlanItem(
    val title: String,
    val detail: String = "",
    val dayOffset: Int = 0,
    val hour: Int = 20,
    val minute: Int = 0,
    val recurrence: String = "none"
)

data class AgentConversationDecision(
    val reply: String,
    val intent: AgentConversationIntent,
    val practiceNeed: String = "",
    // /agent/chat 在 practice_card 意图下原生附带的微元卡 JSON（withMicro 请求）。
    val microCardJson: String = "",
    val outputFiles: List<AgentOutputFileSpec> = emptyList(),
    val planItems: List<AgentPlanItem> = emptyList()
)

data class AgentInputAttachment(
    val name: String,
    val mimeType: String,
    val base64: String = "",
    val sizeBytes: Long = 0L,
    val localPath: String = "",
    val generated: Boolean = false,
    val sourceRecordId: String = "",
    val sourceKind: String = "",
    val sourceCardId: String = "",
    val sourceCardTitle: String = "",
    val sourceMessageId: Long = 0L,
    val copyToDownloads: Boolean = false
)

data class AgentOutputFileSpec(
    val name: String,
    val mimeType: String,
    val content: String,
    val format: String = ""
)

data class AgentSpeechResult(
    val audioUrl: String,
    val format: String = "",
    val model: String = ""
)

data class SpeakingScore(
    val pronunciation: Int = 0,
    val fluency: Int = 0,
    val grammar: Int = 0,
    val vocabulary: Int = 0,
    val content: Int = 0
)

data class SpeakingCorrection(
    val from: String,
    val to: String,
    val note: String = ""
)

// 音素级发音细节：某个词里具体哪个音发错了（目标音标 vs 听起来像）+ 纠正提示。
data class SpeakingPhonemeIssue(
    val phoneme: String,
    val heard: String,
    val tip: String
)

data class SpeakingWordPronunciation(
    val word: String,
    val ipa: String,
    val score: Int,
    val issues: List<SpeakingPhonemeIssue>
)

data class SpeakingPronunciationDetail(
    val summary: String = "",
    val words: List<SpeakingWordPronunciation> = emptyList()
)

data class SpeakingAssessment(
    val transcript: String,
    val overall: Int,
    val scores: SpeakingScore,
    val wpm: Int,
    val highlights: List<String>,
    val improvements: List<String>,
    val corrections: List<SpeakingCorrection>,
    val sampleAnswer: String,
    val comment: String,
    val pronunciationDetail: SpeakingPronunciationDetail = SpeakingPronunciationDetail()
)

data class WritingScore(
    val taskAchievement: Int = 0,
    val coherence: Int = 0,
    val vocabulary: Int = 0,
    val grammar: Int = 0
)

data class WritingAssessment(
    val overall: Int,
    val scores: WritingScore,
    val wordCount: Int,
    val highlights: List<String>,
    val improvements: List<String>,
    val corrections: List<SpeakingCorrection>,
    val sampleAnswer: String,
    val comment: String
)

data class ShadowingSentence(val text: String, val translation: String)
data class RoleplayMessage(val role: String, val content: String, val hint: String = "", val audioPath: String = "")
data class RoleplayTurnResult(val reply: String, val hint: String)
data class RoleplayScore(
    val fluency: Int = 0,
    val grammar: Int = 0,
    val vocabulary: Int = 0,
    val taskCompletion: Int = 0
)
data class RoleplayFeedbackResult(
    val overall: Int,
    val scores: RoleplayScore,
    val highlights: List<String>,
    val improvements: List<String>,
    val betterLines: List<SpeakingCorrection>,
    val comment: String
)

object AgentConversationService {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // 长阅读等重内容卡片生成较慢（后端 MiMo 超时已放宽到 ~180s），客户端读超时需大于后端单次上限，避免客户端先断。
        .readTimeout(210, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun wantsNewListeningPractice(message: String): Boolean {
        val lower = message.lowercase()
        val wordBankClozeHit = Regex(
            "选词填空|word\\s*bank|cloze|fill[-\\s]*in[-\\s]*the[-\\s]*blank|fill[-\\s]*in[-\\s]*blank",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(message)
        val explicitListeningHit = Regex(
            "听力|听音频|音频|listening|audio|听一段|听的|精听|复听|逐句听",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(message)
        if (wordBankClozeHit && !explicitListeningHit) return false
        val listeningHit = listOf(
            "听力", "听音频", "听一段", "听的", "精听", "复听", "逐句听", "音频",
            "listening", "ielts listening", "toefl listening"
        ).any { lower.contains(it) }
        val creationHit = listOf(
            "生成", "创建", "来一套", "来一段", "来段", "来个", "整段", "出段", "做一套", "出一套", "出题", "训练", "练习", "习题", "题目", "素材", "卡片",
            "practice", "training", "quiz", "test", "material"
        ).any { lower.contains(it) }
        val materialSwitchHit = listOf("换一套", "新素材", "再生成", "重新生成", "再来一套").any { lower.contains(it) }
        val examListeningHit = Regex("雅思|托福|四级|六级|高考|考研", RegexOption.IGNORE_CASE).containsMatchIn(message) &&
            Regex("听力|listening|练|训练|题|practice|training|test", RegexOption.IGNORE_CASE).containsMatchIn(message)
        return materialSwitchHit || (listeningHit && creationHit) || examListeningHit
    }

    internal fun wantsSentenceBuilderPractice(message: String): Boolean {
        val text = message.trim()
        val positiveText = messageWithoutNegatedPracticeMentions(text)
        val lower = positiveText.lowercase()
        val paragraphOrdering = Regex("paragraph|段落|段", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            Regex("ordering|order|reorder|排序|排顺序|重新排|正确顺序", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (paragraphOrdering) return false
        return Regex(
            "sentence\\s*builder|word\\s*order|reorder|组句|连词成句|重新排成句子|单词顺序|句子排序",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(lower)
    }

    internal fun wantsClozePractice(message: String): Boolean {
        val text = message.trim()
        val positiveText = messageWithoutNegatedPracticeMentions(text)
        if (!Regex(
                "cloze|fill[-\\s]*in[-\\s]*(?:the[-\\s]*)?blank|word\\s*bank|填空|选词填空|多空|挖空",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(positiveText)
        ) return false
        val paragraphOrdering = Regex("paragraph|段落", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
            Regex("ordering|order|reorder|排序|排顺序", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (paragraphOrdering) return false
        return Regex(
            "practice|exercise|card|quiz|create|generate|make|出\\s*\\d*|请出|给我|来\\s*(?:一|1)?\\s*(?:个|张|道|套)?|练习|训练|卡|题|题目|挖空",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(positiveText)
    }

    internal fun wantsWritingPractice(message: String): Boolean {
        val text = message.trim()
        val positiveText = messageWithoutNegatedPracticeMentions(text)
        if (!Regex(
                "writing|essay|email|write\\s+(?:a|an|your|the)|写作|作文|邮件|写一封|正文|草稿",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(positiveText)
        ) return false
        if (Regex("only\\s+outline|outline.{0,16}only|只要.{0,8}提纲", RegexOption.IGNORE_CASE).containsMatchIn(positiveText)) {
            return false
        }
        return Regex(
            "practice|exercise|card|create|generate|make|让我写|请让我写|练习|训练|卡|输入|正文|草稿|draft|input|writing\\s*box|write\\s+(?:a|an|your|the)",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(positiveText)
    }

    private fun messageWithoutNegatedPracticeMentions(message: String): String {
        val componentCue =
            "sentence[_\\s-]*builder|word\\s*order|reorder|组句|连词成句|重新排成句子|单词顺序|句子排序|" +
                "cloze|fill[-\\s]*in[-\\s]*(?:the[-\\s]*)?blank|word\\s*bank|填空|选词填空|多空|挖空|" +
                "writing|essay|email|write|写作|作文|邮件|正文|草稿"
        return message.replace(
            Regex("(?:不要|不需要|不用|无需|别|别加|no|without|not).{0,24}?(?:$componentCue)", RegexOption.IGNORE_CASE),
            " "
        )
    }

    private const val AGENT_DECIDE_MAX_ATTEMPTS = 3

    private class AgentHttpStatusException(val code: Int) : IOException("Agent chat failed: $code")

    // 瞬时故障（连接被拒/重置/中断、5xx/429/408）值得静默重试；读超时已等满 readTimeout，再试只会更久，故不重试。
    private fun isRetryableAgentError(e: Throwable): Boolean = when (e) {
        is AgentHttpStatusException -> e.code == 408 || e.code == 429 || e.code in 500..599
        is java.net.SocketTimeoutException -> false
        is IOException -> true
        else -> false
    }

    suspend fun decide(
        ctx: Context,
        message: String,
        workspace: LearningWorkspace?,
        recentMessages: List<AgentChatMessage>,
        attachments: List<AgentInputAttachment> = emptyList(),
        currentRecord: HistoryRecord? = null
    ): AgentConversationDecision = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(AGENT_DECIDE_MAX_ATTEMPTS) { attempt ->
            try {
                return@withContext remoteDecision(message, workspace, recentMessages, attachments, currentRecord)
            } catch (e: Throwable) {
                lastError = e
                if (attempt >= AGENT_DECIDE_MAX_ATTEMPTS - 1 || !isRetryableAgentError(e)) throw e
                delay(if (attempt == 0) 600L else 1500L)
            }
        }
        throw (lastError ?: IllegalStateException("agent decide failed"))
    }

    private fun buildChatPayload(
        message: String,
        workspace: LearningWorkspace?,
        recentMessages: List<AgentChatMessage>,
        attachments: List<AgentInputAttachment>,
        currentRecord: HistoryRecord?
    ): JsonObject = buildJsonObject {
        put("message", message)
        // 练习生成走微元时，请求 /agent/chat[/stream] 直接附带原生微元卡（省一次 /agent/micro 往返）。
        put("withMicro", AgentMicroGenerateFlag.enabled)
        put("workspaceTitle", workspace?.title.orEmpty())
        put("workspaceNeed", workspace?.need.orEmpty())
        put("workspaceContentType", workspace?.plan?.contentType.orEmpty())
        put("workspaceMemorySummary", workspace?.memorySummary.orEmpty())
        put("workspaceMemory", agentWorkspaceMemoryPayloadJson(workspace))
        // #4B：携带统一用户模型（跨功能长期画像），服务端注入全局上下文；无缓存则省略。
        UserModelStore.cachedForChat()?.let { put("userModel", it) }
        putJsonArray("attachments") {
            attachments.forEach { attachment ->
                addJsonObject {
                    put("name", attachment.name)
                    put("mimeType", attachment.mimeType)
                    put("base64", attachment.base64)
                    put("sizeBytes", attachment.sizeBytes)
                }
            }
        }
        putJsonArray("recentMessages") {
            recentMessages.takeLast(8).forEach { msg -> add(agentRecentMessagePayloadJson(msg)) }
        }
        currentRecord?.let { put("currentRecordSummary", currentRecordSummaryJson(it)) }
    }

    private fun decisionFromObj(obj: JsonObject, message: String, workspace: LearningWorkspace?): AgentConversationDecision {
        val decision = AgentConversationDecision(
            reply = obj.str("reply").trim().ifBlank { fallbackDecision(message, workspace).reply },
            intent = when (obj.str("intent")) {
                "new_listening_practice" -> AgentConversationIntent.NewListeningPractice
                "practice_card" -> AgentConversationIntent.PracticeCard
                "study_plan" -> AgentConversationIntent.StudyPlan
                else -> AgentConversationIntent.Chat
            },
            practiceNeed = obj.str("practiceNeed").trim(),
            microCardJson = obj.objOrNull("microCard")?.toString().orEmpty(),
            outputFiles = parseOutputFiles(obj.arrOrNull("outputFiles") ?: obj.arrOrNull("files")),
            planItems = parsePlanItems(obj.arrOrNull("planItems"))
        )
        return decision
    }

    private fun remoteDecision(
        message: String,
        workspace: LearningWorkspace?,
        recentMessages: List<AgentChatMessage>,
        attachments: List<AgentInputAttachment> = emptyList(),
        currentRecord: HistoryRecord? = null
    ): AgentConversationDecision {
        val payload = buildChatPayload(message, workspace, recentMessages, attachments, currentRecord)
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/chat")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) throw AgentHttpStatusException(resp.code)
            val obj = parseJsonObjectOrNull(body) ?: error("Agent chat response is not JSON")
            return decisionFromObj(obj, message, workspace)
        }
    }

    // ②流式：消费 /agent/chat/stream 的 SSE 阶段事件（onStage 展示进度）+ done 事件（最终决策/微元卡）。
    // 任一异常由调用方回退到阻塞 decide()，保证鲁棒。
    // onStage 带阶段类型（intent/generating/validating）+ 展示文案：generating/validating 只在
    // 微元组卡时出现，调用方可据此校正「练习卡生成中」的本地预判。
    suspend fun decideStream(
        ctx: Context,
        message: String,
        workspace: LearningWorkspace?,
        recentMessages: List<AgentChatMessage>,
        attachments: List<AgentInputAttachment> = emptyList(),
        currentRecord: HistoryRecord? = null,
        onStage: (stage: String, text: String) -> Unit = { _, _ -> },
        onReplyDelta: (String) -> Unit = {}
    ): AgentConversationDecision = withContext(Dispatchers.IO) {
        val payload = buildChatPayload(message, workspace, recentMessages, attachments, currentRecord)
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/chat/stream")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw AgentHttpStatusException(resp.code)
            val source = resp.body.source()
            var event = ""
            val data = StringBuilder()
            var doneObj: JsonObject? = null
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    if (data.isNotEmpty()) {
                        val payloadStr = data.toString()
                        when (event) {
                            "stage" -> parseJsonObjectOrNull(payloadStr)?.let { obj ->
                                val text = obj.str("text")
                                if (text.isNotBlank()) onStage(obj.str("stage"), text)
                            }
                            // 回复正文流式增量（打字机上屏）；done 里的完整 reply 仍是权威版本。
                            "delta" -> parseJsonObjectOrNull(payloadStr)?.str("text")?.takeIf { it.isNotEmpty() }?.let(onReplyDelta)
                            "done" -> doneObj = parseJsonObjectOrNull(payloadStr)
                            "error" -> throw IllegalStateException("agent chat stream error")
                        }
                    }
                    event = ""
                    data.clear()
                    continue
                }
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val seg = line.removePrefix("data:").removePrefix(" ")
                        if (data.isNotEmpty()) data.append("\n")
                        data.append(seg)
                    }
                }
            }
            val obj = doneObj ?: error("agent chat stream: no done event")
            return@withContext decisionFromObj(obj, message, workspace)
        }
    }

    private fun parseOutputFiles(arr: JsonArray?): List<AgentOutputFileSpec> {
        if (arr == null) return emptyList()
        return List(arr.size) { index ->
            val obj = arr.objOrNull(index) ?: JsonObject(emptyMap())
            AgentOutputFileSpec(
                name = obj.str("name").trim(),
                mimeType = obj.str("mimeType").trim(),
                content = obj.str("content"),
                format = obj.str("format").trim()
            )
        }.filter { it.name.isNotBlank() && it.content.isNotBlank() }.take(3)
    }

    private fun parsePlanItems(arr: JsonArray?): List<AgentPlanItem> {
        if (arr == null) return emptyList()
        return List(arr.size) { index ->
            val obj = arr.objOrNull(index) ?: JsonObject(emptyMap())
            AgentPlanItem(
                title = obj.str("title").trim().take(120),
                detail = obj.str("detail").trim().take(400),
                dayOffset = obj.int("dayOffset", 0).coerceIn(0, 365),
                hour = obj.int("hour", 20).coerceIn(0, 23),
                minute = obj.int("minute", 0).coerceIn(0, 59),
                recurrence = PlanStore.normalizeRecurrence(obj.str("recurrence", "none"))
            )
        }.filter { it.title.isNotBlank() }.take(20)
    }

    internal fun currentRecordSummaryJson(record: HistoryRecord): JsonObject = buildJsonObject {
        put("id", record.id)
        put("title", record.content.title.ifBlank { record.scene })
        put("scene", record.scene)
        put("contentType", record.contentType)
        put("audioReady", !record.content.audioUrl.isNullOrBlank())
        put("audioUrl", record.content.audioUrl.orEmpty())
        put("questionCount", record.content.questions.size)
        put("answeredCount", record.selectedAnswers.size)
        put("answersRevealed", record.answersRevealed)
        put("correctCount", record.content.questions.mapIndexed { index, question ->
            record.selectedAnswers[index] == question.correctAnswer
        }.count { it })
        put("scriptPreview", record.content.script.take(900))
        putJsonArray("questions") {
            record.content.questions.take(6).forEachIndexed { index, question ->
                addJsonObject {
                    put("index", index)
                    put("questionText", question.questionText)
                    putJsonArray("options") { question.options.take(4).forEach { add(it) } }
                    put("correctAnswer", question.correctAnswer)
                    put("explanation", question.explanation)
                }
            }
        }
        put("analysisSummary", record.analysisResult?.summary.orEmpty())
        putJsonArray("weakPoints") { record.analysisResult?.weakPoints.orEmpty().take(6).forEach { add(it) } }
        putJsonArray("suggestions") { record.analysisResult?.suggestions.orEmpty().take(6).forEach { add(it) } }
        putJsonArray("wrongInsights") {
            record.analysisResult?.wrongQuestionInsights.orEmpty().take(6).forEach { insight ->
                addJsonObject {
                    put("questionIndex", insight.questionIndex)
                    put("mistakeType", insight.mistakeType)
                    put("insight", insight.insight)
                    put("focusSentence", insight.focusSentence)
                }
            }
        }
    }

    suspend fun transcribeAudio(
        base64: String,
        mimeType: String = "audio/mp4",
        language: String = "auto"
    ): String = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("base64", base64)
            put("mimeType", mimeType)
            put("language", language)
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/asr")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error("ASR failed: ${resp.code}")
            val obj = parseJsonObjectOrNull(body)
                ?: error("ASR response is not JSON")
            obj.str("text").trim()
        }
    }

    // TTS 结果缓存（按 文本+音色）：同一句重听不再反复调用 TTS API，复用同一段已生成音频。
    private val ttsCache = java.util.concurrent.ConcurrentHashMap<String, AgentSpeechResult>()

    suspend fun synthesizeSpeech(
        text: String,
        voiceProfile: String = "",
        voiceMode: String = "default"
    ): AgentSpeechResult = withContext(Dispatchers.IO) {
        val cacheKey = "$voiceMode|$voiceProfile|$text"
        ttsCache[cacheKey]?.let { return@withContext it }
        val payload = buildJsonObject {
            put("text", text)
            put("voiceProfile", voiceProfile)
            put("voiceMode", voiceMode)
            put("format", "wav")
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/tts")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error("TTS failed: ${resp.code}")
            val obj = parseJsonObjectOrNull(body)
                ?: error("TTS response is not JSON")
            val result = AgentSpeechResult(
                audioUrl = obj.str("audioUrl"),
                format = obj.str("format"),
                model = obj.str("model")
            )
            if (result.audioUrl.isNotBlank()) {
                if (ttsCache.size > 200) ttsCache.clear()
                ttsCache[cacheKey] = result
            }
            result
        }
    }

    // 口语评测：把录音(base64 WAV)+题目+时长交给后端多模态评分，返回五维评分与反馈。
    suspend fun assessSpeaking(
        base64: String,
        prompt: String,
        durationMs: Long,
        mimeType: String = "audio/wav"
    ): SpeakingAssessment = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("base64", base64)
            put("mimeType", mimeType)
            put("prompt", prompt)
            put("durationMs", durationMs)
            put("language", "en")
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/speaking")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error("口语评测失败：${resp.code}")
            val obj = parseJsonObjectOrNull(body)
                ?: error("口语评测返回的不是 JSON")
            parseSpeakingAssessment(obj)
        }
    }

    private fun parseSpeakingAssessment(obj: JsonObject): SpeakingAssessment {
        val s = obj.objOrNull("scores") ?: JsonObject(emptyMap())
        fun strList(name: String): List<String> {
            val arr = obj.arrOrNull(name) ?: return emptyList()
            return List(arr.size) { arr.str(it).trim() }.filter { it.isNotBlank() }
        }
        val corrArr = obj.arrOrNull("corrections")
        val corrections = if (corrArr == null) emptyList() else List(corrArr.size) { index ->
            val c = corrArr.objOrNull(index) ?: JsonObject(emptyMap())
            SpeakingCorrection(
                from = c.str("from").trim(),
                to = c.str("to").trim(),
                note = c.str("note").trim()
            )
        }.filter { it.from.isNotBlank() || it.to.isNotBlank() }
        val pdObj = obj.objOrNull("pronunciationDetail") ?: obj.objOrNull("pronunciation_detail")
        val pronunciationDetail = if (pdObj == null) SpeakingPronunciationDetail() else {
            val wordsArr = pdObj.arrOrNull("words")
            val words = if (wordsArr == null) emptyList() else List(wordsArr.size) { wi ->
                val w = wordsArr.objOrNull(wi) ?: JsonObject(emptyMap())
                val issuesArr = w.arrOrNull("issues")
                val issues = if (issuesArr == null) emptyList() else List(issuesArr.size) { ii ->
                    val issue = issuesArr.objOrNull(ii) ?: JsonObject(emptyMap())
                    SpeakingPhonemeIssue(
                        phoneme = issue.str("phoneme").trim(),
                        heard = issue.str("heard").trim(),
                        tip = issue.str("tip").trim()
                    )
                }.filter { it.phoneme.isNotBlank() || it.tip.isNotBlank() }
                SpeakingWordPronunciation(
                    word = w.str("word").trim(),
                    ipa = w.str("ipa").trim(),
                    score = w.int("score").coerceIn(0, 100),
                    issues = issues
                )
            }.filter { it.word.isNotBlank() }
            SpeakingPronunciationDetail(summary = pdObj.str("summary").trim(), words = words)
        }
        return SpeakingAssessment(
            transcript = obj.str("transcript").trim(),
            overall = obj.int("overall").coerceIn(0, 100),
            scores = SpeakingScore(
                pronunciation = s.int("pronunciation").coerceIn(0, 100),
                fluency = s.int("fluency").coerceIn(0, 100),
                grammar = s.int("grammar").coerceIn(0, 100),
                vocabulary = s.int("vocabulary").coerceIn(0, 100),
                content = s.int("content").coerceIn(0, 100)
            ),
            wpm = obj.int("wpm").coerceAtLeast(0),
            highlights = strList("highlights"),
            improvements = strList("improvements"),
            corrections = corrections,
            sampleAnswer = obj.str("sampleAnswer").trim(),
            comment = obj.str("comment").trim(),
            pronunciationDetail = pronunciationDetail
        )
    }

    // 写作评分（图表作文）：题目要求 + 图表数据摘要 + 学生作文 -> 四维评分。
    suspend fun assessWriting(
        prompt: String,
        reference: String,
        essay: String
    ): WritingAssessment = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("prompt", prompt)
            put("reference", reference)
            put("essay", essay)
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/writing")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error("作文评分失败：${resp.code}")
            val obj = parseJsonObjectOrNull(body)
                ?: error("作文评分返回的不是 JSON")
            parseWritingAssessment(obj)
        }
    }

    // 练习卡作答分析：把微元卡「作答表」(microCardAnswerSheetJson) 交给后端 /analyze-practice 做逐题错因诊断。
    // 结果复用 AnalysisResult 模型（听力专属的复听定位字段留空）。
    suspend fun analyzeMicroPractice(sheetJson: String): AnalysisResult = withContext(Dispatchers.IO) {
        val sheet = parseJsonObjectOrNull(sheetJson) ?: error("作答表数据无效")
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/analyze-practice")
            .post(sheet.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                val detail = parseJsonObjectOrNull(body)?.str("detail")?.takeIf { it.isNotBlank() }
                error(detail ?: "练习卡分析失败 (HTTP ${resp.code})")
            }
            val obj = parseJsonObjectOrNull(body) ?: error("分析响应不是 JSON")
            val summary = obj.str("summary").trim()
            if (summary.isBlank()) error("分析结果为空，请重试")
            fun strList(name: String): List<String> {
                val arr = obj.arrOrNull(name) ?: return emptyList()
                return List(arr.size) { arr.str(it).trim() }.filter { it.isNotBlank() }
            }
            val insightsArr = obj.arrOrNull("wrongQuestionInsights")
            val insights = if (insightsArr == null) emptyList() else List(insightsArr.size) { i ->
                val o = insightsArr.objOrNull(i) ?: JsonObject(emptyMap())
                WrongQuestionInsight(
                    questionIndex = o.int("questionIndex", -1),
                    question = o.str("question"),
                    selectedAnswer = o.str("selectedAnswer"),
                    correctAnswer = o.str("correctAnswer"),
                    mistakeType = o.str("mistakeType"),
                    insight = o.str("insight")
                )
            }.filter { it.question.isNotBlank() || it.insight.isNotBlank() }
            AnalysisResult(
                summary = summary,
                weakPoints = strList("weakPoints"),
                suggestions = strList("suggestions"),
                diagnosisTags = strList("diagnosisTags"),
                wrongQuestionInsights = insights
            )
        }
    }

    private fun parseWritingAssessment(obj: JsonObject): WritingAssessment {
        val s = obj.objOrNull("scores") ?: JsonObject(emptyMap())
        fun strList(name: String): List<String> {
            val arr = obj.arrOrNull(name) ?: return emptyList()
            return List(arr.size) { arr.str(it).trim() }.filter { it.isNotBlank() }
        }
        val corrArr = obj.arrOrNull("corrections")
        val corrections = if (corrArr == null) emptyList() else List(corrArr.size) { index ->
            val c = corrArr.objOrNull(index) ?: JsonObject(emptyMap())
            SpeakingCorrection(
                from = c.str("from").trim(),
                to = c.str("to").trim(),
                note = c.str("note").trim()
            )
        }.filter { it.from.isNotBlank() || it.to.isNotBlank() }
        return WritingAssessment(
            overall = obj.int("overall").coerceIn(0, 100),
            scores = WritingScore(
                taskAchievement = s.int("taskAchievement").coerceIn(0, 100),
                coherence = s.int("coherence").coerceIn(0, 100),
                vocabulary = s.int("vocabulary").coerceIn(0, 100),
                grammar = s.int("grammar").coerceIn(0, 100)
            ),
            wordCount = obj.int("wordCount").coerceAtLeast(0),
            highlights = strList("highlights"),
            improvements = strList("improvements"),
            corrections = corrections,
            sampleAnswer = obj.str("sampleAnswer").trim(),
            comment = obj.str("comment").trim()
        )
    }

    // 影子跟读：按主题/难度生成跟读句子。
    suspend fun generateShadowing(
        topic: String,
        level: String,
        count: Int = 6
    ): List<ShadowingSentence> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("topic", topic)
            put("level", level)
            put("count", count)
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/shadowing")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error("生成跟读句子失败：${resp.code}")
            val obj = parseJsonObjectOrNull(body) ?: error("跟读返回的不是 JSON")
            val arr = obj.arrOrNull("sentences") ?: JsonArray(emptyList())
            List(arr.size) { i ->
                val s = arr.objOrNull(i) ?: JsonObject(emptyMap())
                ShadowingSentence(s.str("text").trim(), s.str("translation").trim())
            }.filter { it.text.isNotBlank() }
        }
    }

    // 口语陪练：单轮对话（AI 扮演场景另一方）。
    suspend fun roleplayTurn(
        scenario: String,
        history: List<RoleplayMessage>,
        userText: String
    ): RoleplayTurnResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("scenario", scenario)
            put("userText", userText)
            putJsonArray("history") {
                history.forEach { addJsonObject { put("role", it.role); put("content", it.content) } }
            }
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/roleplay/turn")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error("口语陪练失败：${resp.code}")
            val obj = parseJsonObjectOrNull(body) ?: error("口语陪练返回的不是 JSON")
            RoleplayTurnResult(obj.str("reply").trim(), obj.str("hint").trim())
        }
    }

    // 口语陪练：结束评分。
    suspend fun roleplayFeedback(
        scenario: String,
        history: List<RoleplayMessage>
    ): RoleplayFeedbackResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("scenario", scenario)
            putJsonArray("history") {
                history.forEach { addJsonObject { put("role", it.role); put("content", it.content) } }
            }
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/roleplay/feedback")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error("口语评分失败：${resp.code}")
            val obj = parseJsonObjectOrNull(body) ?: error("口语评分返回的不是 JSON")
            val s = obj.objOrNull("scores") ?: JsonObject(emptyMap())
            fun strList(name: String): List<String> {
                val arr = obj.arrOrNull(name) ?: return emptyList()
                return List(arr.size) { arr.str(it).trim() }.filter { it.isNotBlank() }
            }
            val blArr = obj.arrOrNull("betterLines")
            val betterLines = if (blArr == null) emptyList() else List(blArr.size) { i ->
                val c = blArr.objOrNull(i) ?: JsonObject(emptyMap())
                SpeakingCorrection(c.str("from").trim(), c.str("to").trim(), "")
            }.filter { it.from.isNotBlank() || it.to.isNotBlank() }
            RoleplayFeedbackResult(
                overall = obj.int("overall").coerceIn(0, 100),
                scores = RoleplayScore(
                    fluency = s.int("fluency").coerceIn(0, 100),
                    grammar = s.int("grammar").coerceIn(0, 100),
                    vocabulary = s.int("vocabulary").coerceIn(0, 100),
                    taskCompletion = s.int("taskCompletion").coerceIn(0, 100)
                ),
                highlights = strList("highlights"),
                improvements = strList("improvements"),
                betterLines = betterLines,
                comment = obj.str("comment").trim()
            )
        }
    }

    private fun fallbackDecision(message: String, workspace: LearningWorkspace?): AgentConversationDecision {
        val text = message.trim()
        val lower = text.lowercase()
        if (wantsSentenceBuilderPractice(text) || wantsClozePractice(text) || wantsWritingPractice(text)) {
            // 本地固定题型引擎已退役：练习卡一律由服务端微元引擎生成，这里只兜 reply 文案。
            return AgentConversationDecision(reply = "好的，练习卡来了。", intent = AgentConversationIntent.PracticeCard)
        }
        if (wantsNewListeningPractice(text)) {
            return AgentConversationDecision(
                reply = if (workspace == null) {
                    "我会先创建工作区并生成素材，完成后由 AI 判断下一步并实时渲染练习卡片。"
                } else {
                    "我会在当前工作区追加一套听力素材，完成后由 AI 判断下一步并实时渲染练习卡片。"
                },
                intent = AgentConversationIntent.NewListeningPractice,
                practiceNeed = text
            )
        }
        val reply = when {
            Regex("现在完成时|present perfect", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                "现在完成时通常表示过去发生、但和现在有关系的动作。结构是 have/has + 过去分词，比如 “I have finished my homework.” 重点不是动作发生在过去，而是它对现在的结果或经验。"
            Regex("过去式|一般过去时|past tense", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                "一般过去时表示过去某个明确时间发生的动作，常和 yesterday、last week、in 2020 这类时间状语一起用。动词通常用过去式，比如 “I watched a movie yesterday.”"
            lower.contains("翻译") || lower.contains("translate") ->
                "可以，把你要翻译的英文或中文直接发给我。我会给你自然表达、直译含义和适合听力场景的说法。"
            lower.contains("区别") || lower.contains("difference") ->
                "可以，我可以帮你比较两个英语表达的语义、语气和使用场景。把要比较的单词或句子发过来就行。"
            workspace != null ->
                "可以。工作区只是保存上下文和产物的容器；我会根据你每一条新消息单独判断是答疑、生成听力素材，还是组装练习卡片。"
            else ->
                "可以。我是围绕英语学习和听力训练的 AI Agent。你可以直接问英语单词、句子、语法、翻译和表达区别；如果想练听力，说清楚场景、难度和目标，我会生成素材并组装练习卡片。"
        }
        return AgentConversationDecision(reply = reply, intent = AgentConversationIntent.Chat)
    }

}

internal fun agentRecentMessagePayloadJson(message: AgentChatMessage): JsonObject =
    buildJsonObject {
        put("role", if (message.role == AgentChatRole.User) "user" else "assistant")
        put("content", agentRecentMessageContent(message))
    }

internal fun agentWorkspaceMemoryPayloadJson(workspace: LearningWorkspace?): JsonArray =
    buildJsonArray {
        workspace?.memory.orEmpty().take(30).forEach { item ->
            addJsonObject {
                put("key", item.key)
                put("type", item.type)
                put("content", item.content)
                put("importance", item.importance)
                put("updatedAt", item.updatedAt)
            }
        }
    }

private fun agentRecentMessageContent(message: AgentChatMessage): String =
    listOf(
        message.text.trim(),
        message.microCardJson.takeIf { it.isNotBlank() }
            ?.let { MicroCardParser.parse(it) }
            ?.let { microCardExportableSummary(it) }
            .orEmpty()
    ).filter { it.isNotBlank() }.joinToString("\n\n").take(6000)

private fun agentOptionLetter(index: Int): String =
    ('A'.code + index).toChar().toString()

// 微元卡 → 历史可导出摘要（沿用旧 cardSpec 摘要口径：Agent card: 标题 + 编号题目 + A./Correct answer:/Explanation: 行）。
// cardSpec 退役后练习卡都是微元卡；不序列化进 recentMessages 的话，「导出刚才的题目」这类历史导出路径就看不到题目内容。
// 节点降解口径复用 MicroCardExport（材料/运行时节点不进摘要）。internal 便于单测。
internal fun microCardExportableSummary(card: MicroCard): String {
    val lines = mutableListOf("Agent card: ${card.title.ifBlank { "ListenE 练习卡" }}")
    var questionCount = 0
    var contentSections = 0
    card.nodes.forEach { node ->
        microNodeExportSection(node)?.let { section ->
            contentSections += 1
            lines += section
            return@forEach
        }
        microNodeExportQuestion(node)?.let { q ->
            questionCount += 1
            lines += "$questionCount. ${q.prompt}"
            q.options.map { it.trim() }.filter { it.isNotBlank() }.take(8).forEachIndexed { index, option ->
                lines += "${agentOptionLetter(index)}. $option"
            }
            if (q.answer.isNotBlank()) lines += "Correct answer: ${q.answer}"
            if (q.explanation.isNotBlank()) lines += "Explanation: ${q.explanation}"
        }
    }
    return if (questionCount > 0 || contentSections > 0) lines.joinToString("\n") else ""
}
