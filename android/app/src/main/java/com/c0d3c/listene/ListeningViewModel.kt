package com.c0d3c.listene

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private data class ListeningAudioResult(
    val audioUrl: String,
    val audioSegments: List<ListeningAudioSegment> = emptyList()
)

private data class ListeningVoiceDirectives(
    val voiceGender: String?,
    val speechRate: String?,
    val voiceProfile: String = "",
    val pitch: String = "",
    val accent: String = "",
    val tone: String = ""
)

// 听力素材生成的实时进度（聊天里据此渲染进度条，替代原来那句静态「素材生成中」文案）。
// stage：当前阶段中文文案；percent：0..100 粗粒度进度；workspaceId：归属工作区（只在其聊天里显示）。
data class ListeningGenProgress(
    val stage: String,
    val percent: Int,
    val workspaceId: String?
)

internal fun agentMaterialDifficulty(materialNeed: String, plan: WorkspacePlan): String {
    val text = materialNeed.trim()
    val requested = when {
        Regex("简单|基础|初级|入门|A1|A2|beginner|easy", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "简单"
        Regex("困难|高级|进阶|雅思|托福|C1|C2|advanced|hard|difficult", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "困难"
        Regex("普通|中等|中级|B1|B2|normal|medium|intermediate", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "普通"
        else -> ""
    }
    return requested.ifBlank { plan.difficulty.ifBlank { "普通" } }
}

internal const val ListeningAnalysisNeedsRevealedAnswersMessage =
    "请先在练习页对该套题点击「核对答案」后，再使用 AI 分析。"

internal fun listeningAiAnalysisRecordForPractice(record: HistoryRecord?): HistoryRecord? =
    record?.takeIf { it.answersRevealed }

// --- ViewModel ---
class ListeningViewModel : ViewModel() {
    private val _uiState = MutableStateFlow<GenerationState>(GenerationState.Idle)
    val uiState: StateFlow<GenerationState> = _uiState
    // 分析进行中的 recordId（显式、可观察）：聊天侧据此稳定显示“分析中”，不受 _uiState 被其它状态覆盖的影响。
    private val _analysisInProgressRecordId = MutableStateFlow<String?>(null)
    val analysisInProgressRecordId: StateFlow<String?> = _analysisInProgressRecordId
    private val _history = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val history: StateFlow<List<HistoryRecord>> = _history
    private val _showHistoryList = MutableStateFlow(false)
    val showHistoryList: StateFlow<Boolean> = _showHistoryList
    private val _historyListKind = MutableStateFlow<ListeningHistoryKind?>(null)
    val historyListKind: StateFlow<ListeningHistoryKind?> = _historyListKind
    private val historyPrefsName = "listene_history"
    private val historyKey = "records"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private var generationJob: Job? = null
    private var analysisJob: Job? = null
    private var analysisRecordId: String? = null
    private var lastAppContext: Context? = null
    private var listenNotifTitle = "听力内容生成中"
    private var listenNotifStage = "正在连接 AI 服务..."
    private var listenNotifProgress = 8
    // 素材生成实时进度（聊天内进度条订阅）：非空=正在生成，随阶段刷新 stage/percent，终态清空。
    private val _materialProgress = MutableStateFlow<ListeningGenProgress?>(null)
    val materialProgress: StateFlow<ListeningGenProgress?> = _materialProgress
    private var materialProgressWorkspaceId: String? = null
    private var activeWorkspaceId: String? = null

    fun startAgentWorkspace(
        context: Context,
        scope: kotlinx.coroutines.CoroutineScope,
        workspace: LearningWorkspace
    ) = startAgentWorkspace(context, workspace)

    fun startAgentWorkspace(
        context: Context,
        workspace: LearningWorkspace,
        materialNeed: String = ""
    ) {
        val plan = workspace.plan
        val prompt = materialNeed.trim().ifBlank { plan.materialPrompt.ifBlank { workspace.need } }
        val title = materialNeed.trim().take(32).ifBlank { workspace.title.ifBlank { workspace.need } }
        val materialDifficulty = agentMaterialDifficulty(materialNeed, plan)
        val voiceDirectives = voiceDirectivesForMaterialNeed(materialNeed, plan)
        activeWorkspaceId = workspace.id
        viewModelScope.launch(Dispatchers.IO) {
            WorkspaceStore.recordEvent(
                context.applicationContext,
                workspace.id,
                type = "agent_material_start",
                title = "Agent 开始生成听力素材",
                description = plan.summary,
                currentStep = "material"
            )
        }
        if (plan.contentType == "article") {
            val voice = if (voiceDirectives.voiceGender == "male") "男声" else "女声"
            generateArticleFromWorkspace(
                context,
                viewModelScope,
                workspace.id,
                prompt,
                materialDifficulty,
                voice,
                speechRateToCn(voiceDirectives.speechRate.orEmpty()),
                voiceDirectives.voiceProfile,
                voiceDirectives.pitch,
                voiceDirectives.accent,
                voiceDirectives.tone
            )
        } else {
            generateDialogueFromWorkspace(
                context,
                viewModelScope,
                workspace.id,
                prompt,
                materialDifficulty,
                speechRateToCn(voiceDirectives.speechRate.orEmpty()),
                title,
                voiceDirectives.voiceGender,
                voiceDirectives.voiceProfile,
                voiceDirectives.pitch,
                voiceDirectives.accent,
                voiceDirectives.tone
            )
        }
    }

    private fun generateDialogueFromWorkspace(
        context: Context,
        scope: kotlinx.coroutines.CoroutineScope,
        workspaceId: String,
        scene: String,
        difficulty: String,
        speed: String,
        historyTitle: String,
        voiceGender: String? = null,
        voiceProfile: String = "",
        pitch: String = "",
        accent: String = "",
        tone: String = ""
    ) {
        val speechRate = when (speed) {
            "慢" -> "slow"
            "快" -> "fast"
            else -> "medium"
        }
        activeWorkspaceId = workspaceId
        launchGeneration(context, scope, historyTitle, scene, difficulty, "", "dialogue", voiceGender, speechRate, workspaceId, voiceProfile, pitch, accent, tone)
    }

    private fun generateArticleFromWorkspace(
        context: Context,
        scope: kotlinx.coroutines.CoroutineScope,
        workspaceId: String,
        topic: String,
        difficulty: String,
        voice: String,
        speed: String,
        voiceProfile: String = "",
        pitch: String = "",
        accent: String = "",
        tone: String = ""
    ) {
        val voiceGender = if (voice == "男声") "male" else "female"
        val speechRate = when (speed) {
            "快" -> "fast"
            "慢" -> "slow"
            else -> "medium"
        }
        activeWorkspaceId = workspaceId
        launchGeneration(context, scope, "文章·$topic", topic, difficulty, "", "article", voiceGender, speechRate, workspaceId, voiceProfile, pitch, accent, tone)
    }

    private fun speechRateToCn(rate: String): String =
        when (rate.lowercase()) {
            "slow" -> "慢"
            "fast" -> "快"
            else -> "中"
        }

    private fun voiceDirectivesForMaterialNeed(materialNeed: String, plan: WorkspacePlan): ListeningVoiceDirectives {
        val parsed = parseVoiceDirectives(materialNeed)
        val hasOverride = parsed.voiceGender != null ||
            parsed.speechRate != null ||
            parsed.voiceProfile.isNotBlank() ||
            parsed.pitch.isNotBlank() ||
            parsed.accent.isNotBlank() ||
            parsed.tone.isNotBlank()
        return ListeningVoiceDirectives(
            voiceGender = parsed.voiceGender ?: plan.voiceGender,
            speechRate = parsed.speechRate ?: plan.speechRate,
            voiceProfile = if (hasOverride && parsed.voiceProfile.isNotBlank()) parsed.voiceProfile else plan.voiceProfile,
            pitch = parsed.pitch.ifBlank { plan.pitch },
            accent = parsed.accent.ifBlank { plan.accent },
            tone = parsed.tone.ifBlank { plan.tone }
        )
    }

    private fun parseVoiceDirectives(text: String): ListeningVoiceDirectives {
        if (text.isBlank()) return ListeningVoiceDirectives(null, null)
        val voiceGender = when {
            Regex("female|woman|girl|女声|女音|女生|女性", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "female"
            Regex("male|man|boy|男声|男音|男生|男性", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "male"
            else -> null
        }
        val speechRate = when {
            Regex("slow|0\\.\\d\\s*倍|慢速|语速慢|说慢|慢一点|慢点|慢些|慢慢读|读慢点|放慢|别太快|不要太快|正常偏慢", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "slow"
            Regex("fast|1\\.[2-9]\\s*倍|快速|语速快|说快|快一点|快点|快些|偏快", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "fast"
            else -> null
        }
        val pitch = when {
            Regex("low|deep|baritone|bass|低音|低沉|偏低", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "low"
            Regex("high|bright|treble|高音|偏高|清亮|尖亮", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "high"
            Regex("mid|middle|neutral|中音|自然音调|普通音调", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "medium"
            else -> ""
        }
        val accent = when {
            Regex("british|uk|rp|英音|英式|英国", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "british"
            Regex("american|us|美音|美式|美国", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "american"
            Regex("australian|澳音|澳式|澳大利亚", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "australian"
            Regex("canadian|加拿大", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "canadian"
            else -> ""
        }
        val tone = when {
            Regex("gentle|soft|温柔|柔和", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "gentle"
            Regex("warm|friendly|亲切|温暖", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "warm"
            Regex("calm|steady|沉稳|平静|克制", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "calm"
            Regex("energetic|lively|活泼|有活力", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "energetic"
            Regex("serious|formal|严肃|正式", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "serious"
            Regex("professional|broadcast|主播|专业", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "professional"
            else -> ""
        }
        val hasVoiceProfile = voiceGender != null || pitch.isNotBlank() || accent.isNotBlank() || tone.isNotBlank()
        val profile = if (hasVoiceProfile) {
            buildList {
                add(if (voiceGender == "male") "Male" else "Female")
                tone.takeIf { it.isNotBlank() }?.let { add("$it tone") }
                pitch.takeIf { it.isNotBlank() }?.let { add("$it pitch") }
                accent.takeIf { it.isNotBlank() }?.let { add("$it accent") }
                add("English voice")
            }.joinToString(" ")
        } else {
            ""
        }
        return ListeningVoiceDirectives(voiceGender, speechRate, profile, pitch, accent, tone)
    }

    fun refreshHistory(ctx: Context) {
        _history.value = loadHistory(ctx.applicationContext)
    }

    suspend fun getOrFetchHistoryRecord(context: Context, recordId: String?): HistoryRecord? = withContext(Dispatchers.IO) {
        if (recordId.isNullOrBlank()) return@withContext null
        loadHistory(context.applicationContext).find { it.id == recordId }
    }

    fun insertImportedRecord(ctx: Context, record: HistoryRecord) {
        val existing = loadHistory(ctx.applicationContext)
        val updated = listOf(record) + existing.filterNot { it.id == record.id }
        saveHistoryRecords(ctx.applicationContext, updated.take(50))
        refreshHistory(ctx)
    }

    suspend fun ensureImportedRecordAudio(
        context: Context,
        record: HistoryRecord,
        manifestRoot: JsonObject? = null
    ): HistoryRecord = withContext(Dispatchers.IO) {
        if (hasPlayableLocalAudio(record.content.audioUrl)) return@withContext record
        val remoteUrl = record.content.audioUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
        if (remoteUrl != null) {
            val localPath = downloadAudioToFile(context, remoteUrl)
            if (localPath.isNotBlank()) {
                return@withContext record.copy(content = record.content.copy(audioUrl = localPath))
            }
        }
        val synthesized = requestListeningAudioUrl(record, manifestRoot)
        val localPath = downloadAudioToFile(context, synthesized.audioUrl)
        if (localPath.isBlank()) record
        else record.copy(
            content = record.content.copy(
                audioUrl = localPath,
                audioSegments = synthesized.audioSegments.ifEmpty { record.content.audioSegments }
            )
        )
    }

    private fun hasPlayableLocalAudio(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (path.startsWith("http", ignoreCase = true)) return false
        val file = when {
            path.startsWith("/") -> File(path)
            path.startsWith("file://") -> File(path.removePrefix("file://"))
            else -> return false
        }
        return file.exists() && file.length() > 0L
    }

    private suspend fun requestListeningAudioUrl(record: HistoryRecord, manifestRoot: JsonObject?): ListeningAudioResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("script", record.content.script)
            put("contentType", record.contentType)
            put("ttsPrompt", record.content.ttsPrompt)
            val manifestSegments = manifestRoot?.arrOrNull("ttsSegments")?.takeIf { it.isNotEmpty() }
            val recordSegments = listeningTtsSegmentsToJson(record.content.ttsSegments).takeIf { it.isNotEmpty() }
            (manifestSegments ?: recordSegments)?.let { put("ttsSegments", it) }
            val voiceGender = manifestRoot?.str("voiceGender")?.takeIf { it.isNotBlank() }
                ?: record.content.voiceGender.takeIf { it.isNotBlank() }
            voiceGender?.let { put("voiceGender", it) }
            val speechRate = manifestRoot?.str("speechRate")?.takeIf { it.isNotBlank() }
                ?: record.content.speechRate.takeIf { it.isNotBlank() }
            speechRate?.let { put("speechRate", it) }
            val voiceProfile = manifestRoot?.str("voiceProfile")?.takeIf { it.isNotBlank() }
                ?: record.content.voiceProfile.takeIf { it.isNotBlank() }
            voiceProfile?.let { put("voiceProfile", it) }
            val pitch = manifestRoot?.str("pitch")?.takeIf { it.isNotBlank() }
                ?: record.content.pitch.takeIf { it.isNotBlank() }
            pitch?.let { put("pitch", it) }
            val accent = manifestRoot?.str("accent")?.takeIf { it.isNotBlank() }
                ?: record.content.accent.takeIf { it.isNotBlank() }
            accent?.let { put("accent", it) }
            val tone = manifestRoot?.str("tone")?.takeIf { it.isNotBlank() }
                ?: record.content.tone.takeIf { it.isNotBlank() }
            tone?.let { put("tone", it) }
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/listening/synthesize")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "音频生成失败"))
            return@withContext parseListeningAudioResult(parseJsonObjectBody(body, "音频生成响应不是 JSON"))
        }
    }

    private suspend fun requestListeningAudioUrl(
        content: ListeningContent,
        contentType: String,
        voiceGender: String?,
        speechRate: String?,
        voiceProfile: String = "",
        pitch: String = "",
        accent: String = "",
        tone: String = ""
    ): ListeningAudioResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("script", content.script)
            put("contentType", contentType)
            put("ttsPrompt", content.ttsPrompt)
            listeningTtsSegmentsToJson(content.ttsSegments).takeIf { it.isNotEmpty() }?.let { put("ttsSegments", it) }
            voiceGender?.takeIf { it.isNotBlank() }?.let { put("voiceGender", it) }
            speechRate?.takeIf { it.isNotBlank() }?.let { put("speechRate", it) }
            voiceProfile.takeIf { it.isNotBlank() }?.let { put("voiceProfile", it) }
            pitch.takeIf { it.isNotBlank() }?.let { put("pitch", it) }
            accent.takeIf { it.isNotBlank() }?.let { put("accent", it) }
            tone.takeIf { it.isNotBlank() }?.let { put("tone", it) }
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/listening/synthesize")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "音频生成失败"))
            return@withContext parseListeningAudioResult(parseJsonObjectBody(body, "音频生成响应不是 JSON"))
        }
    }

    private fun kindForRecord(record: HistoryRecord): ListeningHistoryKind =
        if (record.contentType == "article") ListeningHistoryKind.ARTICLE else ListeningHistoryKind.DIALOGUE

    private fun launchGeneration(
        context: Context,
        @Suppress("UNUSED_PARAMETER") scope: kotlinx.coroutines.CoroutineScope,
        historyScene: String,
        requestScene: String,
        difficulty: String,
        enhancedDetails: String,
        contentType: String,
        voiceGender: String?,
        speechRate: String?,
        workspaceId: String?,
        voiceProfile: String = "",
        pitch: String = "",
        accent: String = "",
        tone: String = ""
    ) {
        generationJob?.cancel()
        val appCtx = context.applicationContext
        lastAppContext = appCtx
        val notifTitle = if (contentType == "article") "文章听力生成中" else "对话听力生成中"
        listenNotifTitle = notifTitle
        listenNotifStage = "正在连接 AI 服务..."
        listenNotifProgress = 8
        // 聊天内进度条：从开始就置为「连接中 8%」，之后各阶段随 postListenProgress 刷新。
        materialProgressWorkspaceId = workspaceId
        _materialProgress.value = ListeningGenProgress("正在连接 AI 服务…", 8, workspaceId)
        // 生成开始时（App 仍在前台）就启动前台服务，保障退到后台也能跑完；受「完成通知」总开关控制。
        if (AppSettings.isNotifyOnCompleteEnabled(appCtx)) {
            GenerationForegroundService.start(appCtx, notifTitle, "正在连接 AI 服务...", 8)
        }
        generationJob = viewModelScope.launch {
            if (AppForegroundTracker.isForeground) _uiState.value = GenerationState.Loading
            runCatching {
                postListenProgress(appCtx, notifTitle, "正在生成文本和题目...", 8)
                val content = requestListeningContent(requestScene, difficulty, enhancedDetails, contentType, voiceGender, speechRate, voiceProfile, pitch, accent, tone)
                // 文本先到：音频合成前先把原文/题目渲染出来（recordId 暂空——作答待音频就绪后的正式 Success 再启用，
                // 这段等待通常只几秒），避免用户对着转圈空等整段 TTS 合成+下载。
                if (AppForegroundTracker.isForeground) {
                    val previewScript = if (contentType == "article") formatArticleScript(content.script) else formatScript(content.script)
                    _uiState.value = GenerationState.Success(
                        content.copy(
                            script = previewScript,
                            voiceGender = voiceGender.orEmpty(),
                            speechRate = speechRate.orEmpty(),
                            voiceProfile = voiceProfile,
                            pitch = pitch,
                            accent = accent,
                            tone = tone
                        ),
                        recordId = null,
                        scene = historyScene
                    )
                }
                postListenProgress(appCtx, notifTitle, "正在合成音频...", 42)
                val audio = content.audioUrl?.takeIf { it.isNotBlank() }?.let {
                    ListeningAudioResult(it, content.audioSegments)
                } ?: requestListeningAudioUrl(content, contentType, voiceGender, speechRate, voiceProfile, pitch, accent, tone)
                postListenProgress(appCtx, notifTitle, "正在下载音频...", 68)
                val localPath = downloadAudioToFile(context, audio.audioUrl)
                postListenProgress(appCtx, notifTitle, "正在整理练习内容...", 78)
                val formattedScript = if (contentType == "article") formatArticleScript(content.script) else formatScript(content.script)
                content.copy(
                    audioUrl = localPath,
                    script = formattedScript,
                    audioSegments = audio.audioSegments.ifEmpty { content.audioSegments },
                    voiceGender = voiceGender.orEmpty(),
                    speechRate = speechRate.orEmpty(),
                    voiceProfile = voiceProfile,
                    pitch = pitch,
                    accent = accent,
                    tone = tone
                )
            }.onSuccess { content ->
                val recordId = saveHistory(appCtx, historyScene, content, contentType)
                if (!workspaceId.isNullOrBlank()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        WorkspaceStore.recordEvent(
                            appCtx,
                            workspaceId,
                            type = "material_record_created",
                            title = "听力素材已生成",
                            description = content.title,
                            recordId = recordId,
                            currentStep = "practice"
                        )
                    }
                }
                refreshHistory(context)
                if (!AppForegroundTracker.isForeground) {
                    GenerationNotificationManager.showListeningComplete(appCtx, recordId, historyScene)
                    _uiState.value = GenerationState.Idle
                } else {
                    _uiState.value = GenerationState.Success(content, recordId = recordId, scene = historyScene)
                }
            }.onFailure { error ->
                val msg = formatNetworkError(error)
                if (!AppForegroundTracker.isForeground) {
                    GenerationNotificationManager.showListeningError(appCtx, msg)
                    _uiState.value = GenerationState.Idle
                } else {
                    _uiState.value = GenerationState.Error(msg)
                }
            }
            // 生成收尾（成功/失败均到此）：清空聊天内进度条，让结果卡/错误提示接替显示。
            _materialProgress.value = null
            materialProgressWorkspaceId = null
            GenerationForegroundService.stop(appCtx)
            GenerationNotificationManager.cancelListeningProgress(appCtx)
        }
    }

    private fun postListenProgress(
        ctx: Context,
        title: String,
        stage: String,
        progress: Int
    ) {
        listenNotifTitle = title
        listenNotifStage = stage
        listenNotifProgress = progress
        // 聊天内进度条与通知同源刷新（不受「完成通知」开关影响，始终反映真实阶段）。
        _materialProgress.value = ListeningGenProgress(stage, progress, materialProgressWorkspaceId)
        if (!AppSettings.isNotifyOnCompleteEnabled(ctx)) return
        // 生成期间由前台服务承载常驻进度通知（前台/后台一致更新）。
        GenerationForegroundService.updateProgress(ctx, title, stage, progress)
    }

    private fun formatArticleScript(raw: String): String {
        val normalized = normalizeEscapedLineBreaks(raw)
        if (normalized.contains("\n\n")) return normalized.trim()
        if (normalized.contains("\n")) return normalized.replace("\n", "\n\n").trim()
        return normalized.trim()
    }

    private fun formatScript(raw: String): String {
        val normalized = normalizeEscapedLineBreaks(raw)
        if (normalized.contains("\n\n")) return normalized
        if (normalized.contains("\n")) return normalized.replace("\n", "\n\n")
        val speakerRegex = Regex("(\\s*)([A-Z][a-z]*|[A-Z])[:：]")
        return normalized.replace(speakerRegex) { "\n\n${it.value.trim()}" }.trim()
    }

    private fun normalizeEscapedLineBreaks(raw: String): String =
        raw.replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\r\n", "\n")

    private suspend fun downloadAudioToFile(ctx: Context, url: String): String = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext ""
        val file = File(ctx.filesDir, "audio_${System.currentTimeMillis()}.wav")
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("音频下载失败")
            file.outputStream().use { out -> resp.body.byteStream().copyTo(out) }
        }
        file.absolutePath
    }

    fun openHistoryRecordForWorkspace(record: HistoryRecord, workspaceId: String) {
        activeWorkspaceId = workspaceId
        openHistoryRecordInternal(record)
    }

    fun activateWorkspace(workspaceId: String?) {
        activeWorkspaceId = workspaceId
    }

    fun activeWorkspaceIdSnapshot(): String? = activeWorkspaceId

    private fun openHistoryRecordInternal(record: HistoryRecord) {
        _historyListKind.value = kindForRecord(record)
        _showHistoryList.value = false
        _uiState.value = GenerationState.Success(
            content = record.content,
            openedFromHistory = true,
            recordId = record.id,
            initialSelectedAnswers = record.selectedAnswers,
            initialAnswersRevealed = record.answersRevealed,
            scene = record.scene
        )
    }

    fun updateHistoryAnswer(context: Context, recordId: String?, qIdx: Int, oIdx: Int) {
        if (recordId.isNullOrBlank()) return
        val records = loadHistory(context.applicationContext)
        val target = records.find { it.id == recordId } ?: return
        if (target.answersRevealed) return
        val nextAnswers = target.selectedAnswers + (qIdx to oIdx)
        val updated = records.map { if (it.id == recordId) it.copy(selectedAnswers = nextAnswers) else it }
        saveHistoryRecords(context.applicationContext, updated)
        refreshHistory(context)
        val current = _uiState.value
        if (current is GenerationState.Success && current.recordId == recordId) {
            _uiState.value = current.copy(initialSelectedAnswers = nextAnswers)
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                patchRemoteHistoryRecord(
                    recordId,
                    buildJsonObject { put("selectedAnswers", selectedAnswersToJson(nextAnswers)) }
                )
            }
        }
    }

    fun confirmAnswersRevealed(context: Context, recordId: String?, answers: Map<Int, Int>) {
        if (recordId.isNullOrBlank()) return
        val updated = loadHistory(context.applicationContext).map { r ->
            if (r.id != recordId) r else r.copy(selectedAnswers = answers, answersRevealed = true)
        }
        saveHistoryRecords(context.applicationContext, updated, synchronous = true)
        refreshHistory(context)
        val current = _uiState.value
        if (current is GenerationState.Success && current.recordId == recordId) {
            _uiState.value = current.copy(
                initialSelectedAnswers = answers,
                initialAnswersRevealed = true
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                patchRemoteHistoryRecord(
                    recordId,
                    buildJsonObject {
                        put("selectedAnswers", selectedAnswersToJson(answers))
                        put("answersRevealed", true)
                    }
                )
                val workspaceId = activeWorkspaceId
                if (!workspaceId.isNullOrBlank()) {
                    WorkspaceStore.recordEvent(
                        context.applicationContext,
                        workspaceId,
                        type = "practice_answers_revealed",
                        title = "完成答题并核对答案",
                        description = "Agent 将根据答题结果进入错因分析",
                        recordId = recordId,
                        currentStep = "analysis"
                    )
                }
            }
        }
    }

    fun getHistoryRecord(context: Context, recordId: String?): HistoryRecord? =
        recordId?.let { id -> loadHistory(context.applicationContext).find { it.id == id } }

    fun analyzeRecord(
        context: Context,
        @Suppress("UNUSED_PARAMETER") scope: kotlinx.coroutines.CoroutineScope,
        record: HistoryRecord
    ) = analyzeRecord(context, record)

    fun analyzeRecord(
        context: Context,
        record: HistoryRecord
    ) {
        if (!record.answersRevealed) {
            _uiState.value = GenerationState.Message(ListeningAnalysisNeedsRevealedAnswersMessage, record)
            return
        }
        record.analysisResult?.let { _uiState.value = GenerationState.AnalysisResultScreen(record, it); return }
        if (analysisRecordId == record.id && analysisJob?.isActive == true) {
            _uiState.value = GenerationState.AnalysisLoading(record)
            return
        }
        _uiState.value = GenerationState.AnalysisLoading(record)
        analysisRecordId = record.id
        _analysisInProgressRecordId.value = record.id
        analysisJob = viewModelScope.launch {
            try {
            runCatching { requestAnalysis(record) }.onSuccess { res ->
                saveAnalysisResult(context.applicationContext, record.id, res)
                LearningAgentStore.recordAnalysis(context.applicationContext, res)
                syncRemoteHistoryRecord(record.copy(analysisResult = res))
                syncAgentAnalysis(record, res)
                val workspaceId = activeWorkspaceId
                if (!workspaceId.isNullOrBlank()) {
                    WorkspaceStore.recordEvent(
                        context.applicationContext,
                        workspaceId,
                        type = "analysis_completed",
                        title = "AI 错因分析完成",
                        description = res.summary,
                        recordId = record.id,
                        currentStep = "review",
                        weakPoints = res.weakPoints
                    )
                }
                refreshHistory(context)
                val fresh = loadHistory(context.applicationContext).firstOrNull { it.id == record.id } ?: record.copy(analysisResult = res)
                _uiState.value = GenerationState.AnalysisResultScreen(fresh, res)
            }.onFailure { e -> _uiState.value = GenerationState.Error(formatNetworkError(e), record) }
            } finally {
                if (analysisRecordId == record.id) {
                    analysisRecordId = null
                    _analysisInProgressRecordId.value = null
                }
            }
        }
    }

    private fun formatNetworkError(e: Throwable): String {
        e.printStackTrace()
        val message = e.message.orEmpty()
        if (
            message.contains("HTTP 504", ignoreCase = true) ||
            message.contains("504 Gateway", ignoreCase = true) ||
            message.contains("Gateway Time-out", ignoreCase = true)
        ) {
            return "AI 生成耗时过长，网关已超时。请重新生成；现在已改为分步生成，能降低再次超时的概率。"
        }
        if (message.contains("<html", ignoreCase = true)) {
            return "后端网关返回了网页错误页，请稍后重试。"
        }
        message.takeIf { it.contains("不是 JSON") || it.contains("响应不是 JSON") }?.let { return it }
        return when (e) {
            is SocketTimeoutException -> "连接超时"
            is UnknownHostException -> "网络异常 (DNS解析失败)"
            is javax.net.ssl.SSLHandshakeException -> "SSL证书验证失败 (请检查证书链)"
            is kotlinx.serialization.SerializationException -> "后端返回了非 JSON 响应：${e.message.orEmpty().take(180)}"
            else -> "错误: ${e.javaClass.simpleName} - ${e.message}"
        }
    }

    private suspend fun requestListeningContent(
        s: String,
        d: String,
        dt: String,
        contentType: String = "dialogue",
        voiceGender: String? = null,
        speechRate: String? = null,
        voiceProfile: String = "",
        pitch: String = "",
        accent: String = "",
        tone: String = ""
    ): ListeningContent = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("scene", s)
            put("difficulty", d)
            put("details", dt)
            put("contentType", contentType)
            put("skipAudio", true)
            voiceGender?.let { put("voiceGender", it) }
            speechRate?.let { put("speechRate", it) }
            voiceProfile.takeIf { it.isNotBlank() }?.let { put("voiceProfile", it) }
            pitch.takeIf { it.isNotBlank() }?.let { put("pitch", it) }
            accent.takeIf { it.isNotBlank() }?.let { put("accent", it) }
            tone.takeIf { it.isNotBlank() }?.let { put("tone", it) }
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/listening/generate")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "听力生成失败"))
            parseListeningContent(parseJsonObjectBody(body, "听力生成响应不是 JSON"))
        }
    }

    private suspend fun requestAnalysis(r: HistoryRecord): AnalysisResult = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("title", r.content.title)
            put("script", r.content.script)
            put("questions", questionsToJson(r.content.questions))
            putJsonArray("selectedAnswers") {
                r.selectedAnswers.forEach { (q, o) ->
                    addJsonObject {
                        put("questionIndex", q)
                        put("selectedAnswer", o)
                    }
                }
            }
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/analyze-mistakes")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) {
                error(httpErrorMessage(resp.code, body, "分析失败"))
            }
            val j = parseJsonObjectBody(body, "分析响应不是 JSON")
            val summary = j.str("summary").trim()
            if (summary.isBlank()) error("分析结果为空，请重试")
            normalizeAnalysisResult(
                record = r,
                raw = AnalysisResult(
                    summary = summary,
                    weakPoints = jsonArrayToStringList(j.arrOrNull("weakPoints")),
                    suggestions = jsonArrayToStringList(j.arrOrNull("suggestions")),
                    diagnosisTags = jsonArrayToStringList(j.arrOrNull("diagnosisTags")),
                    wrongQuestionInsights = parseWrongQuestionInsights(j.arrOrNull("wrongQuestionInsights")),
                    nextActions = parseAgentNextActions(j.arrOrNull("nextActions")),
                    reviewItems = parseAgentReviewItems(j.arrOrNull("reviewItems")),
                    recommendedPlanTasks = parseAgentPlanTasks(j.arrOrNull("recommendedPlanTasks"))
                )
            )
        }
    }

    private fun syncAgentAnalysis(record: HistoryRecord, result: AnalysisResult) {
        val payload = buildJsonObject {
            put("recordId", record.id)
            put("title", record.content.title.ifBlank { record.scene })
            put("contentType", record.contentType)
            put("result", analysisResultToJson(result))
        }
        postAgentPayload("/agent/analysis", payload)
    }

    private fun postAgentPayload(path: String, payload: JsonObject) {
        // User learning state is local-only. Agent event endpoints are kept server-side for API compatibility,
        // but the Android app must not upload history, workspace progress, or learning telemetry.
    }

    private fun parseJsonObjectBody(body: String, fallback: String): JsonObject =
        parseJsonObjectOrNull(body) ?: error("$fallback：${responsePreview(body)}")

    private fun httpErrorMessage(code: Int, body: String, fallback: String): String {
        val detail = parseJsonObjectOrNull(body)?.str("detail")?.takeIf { it.isNotBlank() }
        return detail ?: "$fallback (HTTP $code)：${responsePreview(body)}"
    }

    private fun responsePreview(body: String): String {
        val text = body.replace(Regex("""\s+"""), " ").trim()
        return text.ifBlank { "响应为空" }.take(180)
    }

    private fun parseListeningAudioResult(j: JsonObject): ListeningAudioResult {
        val audioUrl = j.str("audioUrl").takeIf { it.isNotBlank() } ?: error("音频生成失败")
        return ListeningAudioResult(
            audioUrl = audioUrl,
            audioSegments = parseListeningAudioSegments(j.arrOrNull("audioSegments"))
        )
    }

    private fun parseListeningContent(j: JsonObject?): ListeningContent {
        val root = j ?: JsonObject(emptyMap())
        val qJ = root.arrOrNull("questions") ?: JsonArray(emptyList())
        val qs = buildList {
            for (i in 0 until qJ.size) {
                val item = qJ.objOrNull(i) ?: continue
                val oJ = item.arrOrNull("options") ?: JsonArray(emptyList())
                val opts = List(oJ.size) { stripRedundantOptionPrefix(oJ.str(it)) }
                val rawAnswer = when {
                    item.containsKey("answer") -> item.str("answer")
                    item.containsKey("correctAnswer") -> item.str("correctAnswer")
                    item.containsKey("correct_answer") -> item.str("correct_answer")
                    item.containsKey("correct") -> item.str("correct")
                    else -> ""
                }
                val answerIndex = agentQuestionAnswerIndex(rawAnswer, opts) ?: continue
                add(Question(item.str("questionText"), opts, answerIndex, item.str("explanation")))
            }
        }
        return ListeningContent(
            title = root.str("title", "Practice"),
            script = normalizeEscapedLineBreaks(root.str("script")),
            questions = qs,
            audioUrl = root.str("audioUrl"),
            ttsPrompt = root.str("ttsPrompt"),
            speakers = parseListeningSpeakers(root.arrOrNull("speakers")),
            ttsSegments = parseListeningTtsSegments(root.arrOrNull("ttsSegments")),
            audioSegments = parseListeningAudioSegments(root.arrOrNull("audioSegments")),
            voiceGender = root.str("voiceGender"),
            speechRate = root.str("speechRate"),
            voiceProfile = root.str("voiceProfile"),
            pitch = root.str("pitch"),
            accent = root.str("accent"),
            tone = root.str("tone")
        )
    }

    private fun saveHistory(ctx: Context, s: String, c: ListeningContent, contentType: String): String {
        val prefs = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, historyPrefsName), Context.MODE_PRIVATE)
        val existing = parseJsonArrayOrNull(prefs.getString(historyKey, "[]") ?: "[]") ?: JsonArray(emptyList())
        val id = System.currentTimeMillis().toString()
        val createdAt = System.currentTimeMillis()
        val record = buildJsonObject {
            put("id", id)
            put("scene", s)
            put("createdAt", createdAt)
            put("content", contentToJson(c))
            put("contentType", contentType)
            put("selectedAnswers", buildJsonObject { })
            put("answersRevealed", false)
            put("analysisResult", JsonNull)
        }
        val updated = buildJsonArray {
            add(record)
            for (i in 0 until minOf(existing.size, 29)) existing.objOrNull(i)?.let { add(it) }
        }
        prefs.edit().putString(historyKey, updated.toString()).apply()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                saveRemoteHistoryRecord(
                    HistoryRecord(
                        id = id,
                        scene = s,
                        createdAt = createdAt,
                        content = c,
                        contentType = contentType
                    )
                )
            }
        }
        return id
    }

    private fun loadHistory(ctx: Context): List<HistoryRecord> {
        val prefs = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, historyPrefsName), Context.MODE_PRIVATE)
        val j = parseJsonArrayOrNull(prefs.getString(historyKey, "[]") ?: "[]") ?: JsonArray(emptyList())
        return List(j.size) { i ->
            parseHistoryRecord(j.objOrNull(i))
        }.filter { it.id.isNotBlank() }
    }

    private fun parseHistoryRecord(item: JsonObject?): HistoryRecord {
        val o = item ?: JsonObject(emptyMap())
        val scene = o.str("scene")
        return HistoryRecord(
            id = o.str("id"),
            scene = scene,
            createdAt = o.long("createdAt", 0L),
            content = parseListeningContent(o.objOrNull("content")),
            selectedAnswers = parseSelectedAnswers(o.objOrNull("selectedAnswers")),
            analysisResult = parseAnalysisResult(o.objOrNull("analysisResult")),
            answersRevealed = o.bool("answersRevealed", false),
            contentType = o.str("contentType").takeIf { it == "dialogue" || it == "article" }
                ?: if (scene.startsWith("文章")) "article" else "dialogue"
        )
    }

    private fun saveAnalysisResult(ctx: Context, id: String, res: AnalysisResult) {
        val records = loadHistory(ctx).map { if (it.id == id) it.copy(analysisResult = res) else it }
        saveHistoryRecords(ctx, records)
    }

    private fun saveHistoryRecords(ctx: Context, records: List<HistoryRecord>, synchronous: Boolean = false) {
        val j = buildJsonArray {
            records.forEach { r ->
                addJsonObject {
                    put("id", r.id)
                    put("scene", r.scene)
                    put("createdAt", r.createdAt)
                    put("contentType", r.contentType)
                    put("content", contentToJson(r.content))
                    put("selectedAnswers", selectedAnswersToJson(r.selectedAnswers))
                    put("answersRevealed", r.answersRevealed)
                    put("analysisResult", r.analysisResult?.let { analysisResultToJson(it) } ?: JsonNull)
                }
            }
        }
        val ed = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, historyPrefsName), Context.MODE_PRIVATE).edit().putString(historyKey, j.toString())
        if (synchronous) ed.commit() else ed.apply()
    }

    private fun syncRemoteHistoryRecord(record: HistoryRecord) {
        // Local-only history; backend is stateless compute.
    }

    private fun saveRemoteHistoryRecord(record: HistoryRecord) {
        // Local-only history; backend is stateless compute.
    }

    private fun patchRemoteHistoryRecord(recordId: String, payload: JsonObject) {
        // Local-only history; backend is stateless compute.
    }

    private fun contentToJson(c: ListeningContent): JsonObject = buildJsonObject {
        put("title", c.title)
        put("script", c.script)
        put("questions", questionsToJson(c.questions))
        put("audioUrl", c.audioUrl.orEmpty())
        put("ttsPrompt", c.ttsPrompt)
        put("speakers", listeningSpeakersToJson(c.speakers))
        put("ttsSegments", listeningTtsSegmentsToJson(c.ttsSegments))
        put("audioSegments", listeningAudioSegmentsToJson(c.audioSegments))
        put("voiceGender", c.voiceGender)
        put("speechRate", c.speechRate)
        put("voiceProfile", c.voiceProfile)
        put("pitch", c.pitch)
        put("accent", c.accent)
        put("tone", c.tone)
    }

    private fun parseListeningSpeakers(a: JsonArray?): List<ListeningSpeaker> {
        if (a == null) return emptyList()
        return List(a.size) { i ->
            val item = a.objOrNull(i) ?: JsonObject(emptyMap())
            ListeningSpeaker(
                speakerId = item.str("speakerId"),
                speakerName = item.str("speakerName"),
                speakerGender = item.str("speakerGender")
            )
        }.filter { it.speakerId.isNotBlank() || it.speakerName.isNotBlank() }
    }

    private fun listeningSpeakersToJson(speakers: List<ListeningSpeaker>): JsonArray = buildJsonArray {
        speakers.filter { it.speakerId.isNotBlank() || it.speakerName.isNotBlank() }.forEach { speaker ->
            addJsonObject {
                put("speakerId", speaker.speakerId)
                put("speakerName", speaker.speakerName)
                put("speakerGender", speaker.speakerGender)
            }
        }
    }

    private fun parseListeningTtsSegments(a: JsonArray?): List<ListeningTtsSegment> {
        if (a == null) return emptyList()
        return List(a.size) { i ->
            val item = a.objOrNull(i) ?: JsonObject(emptyMap())
            ListeningTtsSegment(
                speakerId = item.str("speakerId"),
                speakerName = item.str("speakerName"),
                speakerGender = item.str("speakerGender"),
                voiceProfile = item.str("voiceProfile"),
                text = item.str("text")
            )
        }.filter { it.text.isNotBlank() }
    }

    private fun listeningTtsSegmentsToJson(segments: List<ListeningTtsSegment>): JsonArray = buildJsonArray {
        segments.filter { it.text.isNotBlank() }.forEach { segment ->
            addJsonObject {
                put("speakerId", segment.speakerId)
                put("speakerName", segment.speakerName)
                put("speakerGender", segment.speakerGender)
                put("voiceProfile", segment.voiceProfile)
                put("text", segment.text)
            }
        }
    }

    private fun parseListeningAudioSegments(a: JsonArray?): List<ListeningAudioSegment> {
        if (a == null) return emptyList()
        return List(a.size) { i ->
            val item = a.objOrNull(i) ?: JsonObject(emptyMap())
            val start = item.int("startMs", 0).coerceAtLeast(0)
            val end = item.int("endMs", 0).coerceAtLeast(0)
            ListeningAudioSegment(
                id = item.str("id"),
                kind = item.str("kind", "sentence"),
                speakerId = item.str("speakerId"),
                speakerName = item.str("speakerName"),
                speakerGender = item.str("speakerGender"),
                text = item.str("text"),
                startMs = start,
                endMs = end,
                turnIndex = item.int("turnIndex", 0),
                sentenceIndex = item.int("sentenceIndex", 0)
            )
        }.filter { it.text.isNotBlank() && it.endMs > it.startMs }
    }

    private fun listeningAudioSegmentsToJson(segments: List<ListeningAudioSegment>): JsonArray = buildJsonArray {
        segments.filter { it.text.isNotBlank() && it.endMs > it.startMs }.forEach { segment ->
            addJsonObject {
                put("id", segment.id)
                put("kind", segment.kind)
                put("speakerId", segment.speakerId)
                put("speakerName", segment.speakerName)
                put("speakerGender", segment.speakerGender)
                put("text", segment.text)
                put("startMs", segment.startMs)
                put("endMs", segment.endMs)
                put("turnIndex", segment.turnIndex)
                put("sentenceIndex", segment.sentenceIndex)
            }
        }
    }

    private fun questionsToJson(qs: List<Question>): JsonArray = buildJsonArray {
        qs.forEach { q ->
            addJsonObject {
                put("questionText", q.questionText)
                putJsonArray("options") { q.options.forEach { add(it) } }
                put("correctAnswer", q.correctAnswer)
                put("explanation", q.explanation)
            }
        }
    }

    private fun parseSelectedAnswers(j: JsonObject?) = buildMap {
        j?.keys?.forEach { k -> put(k.toInt(), j.int(k)) }
    }

    private fun selectedAnswersToJson(m: Map<Int, Int>): JsonObject = buildJsonObject {
        m.forEach { (k, v) -> put(k.toString(), v) }
    }

    private fun jsonArrayToStringList(a: JsonArray?) = List(a?.size ?: 0) { a!!.str(it).trim() }.filter { it.isNotBlank() }

    private fun parseAnalysisResult(j: JsonObject?) = j?.let {
        AnalysisResult(
            it.str("summary"),
            jsonArrayToStringList(it.arrOrNull("weakPoints")),
            jsonArrayToStringList(it.arrOrNull("suggestions")),
            diagnosisTags = jsonArrayToStringList(it.arrOrNull("diagnosisTags")),
            wrongQuestionInsights = parseWrongQuestionInsights(it.arrOrNull("wrongQuestionInsights")),
            nextActions = parseAgentNextActions(it.arrOrNull("nextActions")),
            reviewItems = parseAgentReviewItems(it.arrOrNull("reviewItems")),
            recommendedPlanTasks = parseAgentPlanTasks(it.arrOrNull("recommendedPlanTasks"))
        )
    }

    private fun analysisResultToJson(r: AnalysisResult): JsonObject = buildJsonObject {
        put("summary", r.summary)
        putJsonArray("weakPoints") { r.weakPoints.forEach { add(it) } }
        putJsonArray("suggestions") { r.suggestions.forEach { add(it) } }
        putJsonArray("diagnosisTags") { r.diagnosisTags.forEach { add(it) } }
        put("wrongQuestionInsights", wrongQuestionInsightsToJson(r.wrongQuestionInsights))
        put("nextActions", agentNextActionsToJson(r.nextActions))
        put("reviewItems", agentReviewItemsToJson(r.reviewItems))
        put("recommendedPlanTasks", agentPlanTasksToJson(r.recommendedPlanTasks))
    }

    private fun parseWrongQuestionInsights(a: JsonArray?): List<WrongQuestionInsight> {
        if (a == null) return emptyList()
        return List(a.size) { i ->
            val o = a.objOrNull(i) ?: JsonObject(emptyMap())
            WrongQuestionInsight(
                questionIndex = o.int("questionIndex", -1),
                question = o.str("question"),
                selectedAnswer = o.str("selectedAnswer"),
                correctAnswer = o.str("correctAnswer"),
                mistakeType = o.str("mistakeType", "理解偏差"),
                insight = o.str("insight"),
                focusSentence = o.str("focusSentence"),
                startMs = o.optionalInt("startMs"),
                endMs = o.optionalInt("endMs"),
                startRatio = o.optionalFloat("startRatio"),
                endRatio = o.optionalFloat("endRatio")
            )
        }.filter { it.question.isNotBlank() || it.insight.isNotBlank() || it.focusSentence.isNotBlank() }
    }

    private fun parseAgentNextActions(a: JsonArray?): List<AgentNextAction> {
        if (a == null) return emptyList()
        return List(a.size) { i ->
            val o = a.objOrNull(i) ?: JsonObject(emptyMap())
            AgentNextAction(
                title = o.str("title"),
                description = o.str("description"),
                actionType = o.str("actionType", "review")
            )
        }.filter { it.title.isNotBlank() || it.description.isNotBlank() }
    }

    private fun parseAgentReviewItems(a: JsonArray?): List<AgentReviewItem> {
        if (a == null) return emptyList()
        return List(a.size) { i ->
            val o = a.objOrNull(i) ?: JsonObject(emptyMap())
            AgentReviewItem(
                text = o.str("text"),
                itemType = o.str("itemType", "sentence"),
                reason = o.str("reason")
            )
        }.filter { it.text.isNotBlank() }
    }

    private fun parseAgentPlanTasks(a: JsonArray?): List<AgentPlanTask> {
        if (a == null) return emptyList()
        return List(a.size) { i ->
            val o = a.objOrNull(i) ?: JsonObject(emptyMap())
            AgentPlanTask(
                title = o.str("title", "AI 复盘练习"),
                practiceType = o.str("practiceType", "对话听力"),
                offsetDays = o.int("offsetDays", i).coerceIn(0, 30),
                hour = o.int("hour", 20).coerceIn(0, 23),
                minute = o.int("minute", 0).coerceIn(0, 59)
            )
        }.filter { it.title.isNotBlank() }
    }

    private fun wrongQuestionInsightsToJson(items: List<WrongQuestionInsight>): JsonArray = buildJsonArray {
        items.forEach { item ->
            addJsonObject {
                put("questionIndex", item.questionIndex)
                put("question", item.question)
                put("selectedAnswer", item.selectedAnswer)
                put("correctAnswer", item.correctAnswer)
                put("mistakeType", item.mistakeType)
                put("insight", item.insight)
                put("focusSentence", item.focusSentence)
                if (item.startMs != null) put("startMs", item.startMs) else put("startMs", JsonNull)
                if (item.endMs != null) put("endMs", item.endMs) else put("endMs", JsonNull)
                if (item.startRatio != null) put("startRatio", item.startRatio) else put("startRatio", JsonNull)
                if (item.endRatio != null) put("endRatio", item.endRatio) else put("endRatio", JsonNull)
            }
        }
    }

    private fun agentNextActionsToJson(items: List<AgentNextAction>): JsonArray = buildJsonArray {
        items.forEach { item ->
            addJsonObject {
                put("title", item.title)
                put("description", item.description)
                put("actionType", item.actionType)
            }
        }
    }

    private fun agentReviewItemsToJson(items: List<AgentReviewItem>): JsonArray = buildJsonArray {
        items.forEach { item ->
            addJsonObject {
                put("text", item.text)
                put("itemType", item.itemType)
                put("reason", item.reason)
            }
        }
    }

    private fun agentPlanTasksToJson(items: List<AgentPlanTask>): JsonArray = buildJsonArray {
        items.forEach { item ->
            addJsonObject {
                put("title", item.title)
                put("practiceType", item.practiceType)
                put("offsetDays", item.offsetDays)
                put("hour", item.hour)
                put("minute", item.minute)
            }
        }
    }

    private fun normalizeAnalysisResult(record: HistoryRecord, raw: AnalysisResult): AnalysisResult {
        return AnalysisNormalizer.normalize(record, raw)
    }

    private fun stripRedundantOptionPrefix(raw: String): String {
        var t = raw.trimStart()
        val patterns = listOf(
            Regex("""(?i)^\([A-Z]\)\s*"""),
            Regex("""(?i)^[A-Z]\)\s*"""),
            Regex("""(?i)^[A-Z][.．]\s*"""),
            Regex("""(?i)^[A-Z][:：]\s*"""),
            Regex("""^\d+(?:[.．]|[)）])\s*""")
        )
        for (p in patterns) {
            val n = p.replaceFirst(t, "")
            if (n != t) return n.trim()
        }
        return t.trim()
    }

    fun reset() {
        if (_uiState.value is GenerationState.Loading && AppForegroundTracker.isForeground) {
            generationJob?.cancel()
            lastAppContext?.let { GenerationNotificationManager.cancelListeningProgress(it) }
        }
        _showHistoryList.value = false
        _historyListKind.value = null
        _uiState.value = GenerationState.Idle
    }
}

private fun JsonObject.optionalInt(name: String): Int? {
    val v = this[name] ?: return null
    if (v is JsonNull) return null
    return (v as? JsonPrimitive)?.content?.toIntOrNull()
}

private fun JsonObject.optionalFloat(name: String): Float? {
    val v = this[name] ?: return null
    if (v is JsonNull) return null
    return (v as? JsonPrimitive)?.content?.toDoubleOrNull()?.toFloat()
}
