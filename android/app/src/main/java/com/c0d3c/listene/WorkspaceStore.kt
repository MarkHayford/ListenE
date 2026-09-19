package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class WorkspaceStatus { Active, Paused, Completed }

data class WorkspaceStep(
    val id: String,
    val title: String,
    val description: String,
    val status: String
)

data class WorkspacePlan(
    val title: String,
    val summary: String,
    val contentType: String,
    val materialPrompt: String,
    val difficulty: String,
    val speechRate: String,
    val voiceGender: String,
    val voiceProfile: String = "",
    val pitch: String = "",
    val accent: String = "",
    val tone: String = "",
    val rationale: String = "",
    val steps: List<WorkspaceStep> = emptyList()
)

data class WorkspaceEvent(
    val id: String,
    val type: String,
    val title: String,
    val description: String,
    val recordId: String = "",
    val containerId: String = "",
    val taskId: String = "",
    val targetKind: String = "",
    val targetId: String = "",
    val createdAt: Long = 0L
)

data class WorkspaceMemoryEntry(
    val key: String,
    val type: String,
    val content: String,
    val importance: Double = 0.0,
    val updatedAt: Long = 0L
)

data class LearningWorkspace(
    val id: String,
    val title: String,
    val need: String,
    val summary: String,
    val status: WorkspaceStatus = WorkspaceStatus.Active,
    val currentStep: String = "material",
    val createdAt: Long,
    val updatedAt: Long,
    val pinnedAt: Long = 0L,
    val plan: WorkspacePlan,
    val linkedRecordIds: List<String> = emptyList(),
    val linkedContainerIds: List<String> = emptyList(),
    val linkedPlanTaskIds: List<String> = emptyList(),
    val memorySummary: String = "",
    val memory: List<WorkspaceMemoryEntry> = emptyList(),
    val events: List<WorkspaceEvent> = emptyList()
)

data class WorkspaceCascadeTargets(
    val recordIds: List<String> = emptyList(),
    val containerIds: List<String> = emptyList(),
    val taskIds: List<String> = emptyList()
) {
    val isEmpty: Boolean get() = recordIds.isEmpty() && containerIds.isEmpty() && taskIds.isEmpty()
}

object WorkspaceStore {
    private const val PREFS_NAME = "learning_workspace_store"
    private const val KEY_WORKSPACES = "workspaces_v1"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private data class WorkspaceVoiceDirectives(
        val voiceGender: String,
        val voiceProfile: String,
        val pitch: String = "",
        val accent: String = "",
        val tone: String = ""
    )

    fun loadWorkspaces(ctx: Context): List<LearningWorkspace> {
        val raw = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
            .getString(KEY_WORKSPACES, "[]") ?: "[]"
        val arr = parseJsonArrayOrNull(raw) ?: JsonArray(emptyList())
        return sortForDisplay(
            List(arr.size) { i -> parseWorkspace(arr.objOrNull(i)) }
                .filter { it.id.isNotBlank() }
        )
    }

    fun saveWorkspaces(ctx: Context, workspaces: List<LearningWorkspace>) {
        val arr = buildJsonArray {
            sortForDisplay(workspaces).take(120).forEach { add(workspaceToJson(it)) }
        }
        ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WORKSPACES, arr.toString())
            .apply()
    }

    suspend fun refreshRemote(ctx: Context): List<LearningWorkspace> = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder()
                    .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/workspaces")
                    .get(),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "工作区同步失败"))
                val arr = parseJsonObjectBody(body, "工作区响应不是 JSON").arrOrNull("workspaces") ?: JsonArray(emptyList())
                val remote = List(arr.size) { index -> parseWorkspace(arr.objOrNull(index)) }
                    .filter { it.id.isNotBlank() }
                mergeAndSave(app, remote)
            }
        }.getOrElse {
            loadWorkspaces(app)
        }
    }

    suspend fun createWorkspace(ctx: Context, need: String): LearningWorkspace = withContext(Dispatchers.IO) {
        val cleanNeed = need.trim()
        if (cleanNeed.isBlank()) error("请先描述你的学习需求")
        runCatching {
            val payload = buildJsonObject { put("need", cleanNeed) }
            val req = AuthStore.applyAuth(
                Request.Builder()
                    .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/workspaces")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                ctx.applicationContext
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "创建工作区失败"))
                parseWorkspace(parseJsonObjectBody(body, "创建工作区响应不是 JSON").objOrNull("workspace"))
            }
        }.getOrElse {
            fallbackWorkspace(cleanNeed)
        }.also { workspace ->
            upsert(ctx.applicationContext, workspace)
        }
    }

    suspend fun recordEvent(
        ctx: Context,
        workspaceId: String?,
        type: String,
        title: String,
        description: String = "",
        recordId: String = "",
        containerId: String = "",
        taskId: String = "",
        currentStep: String = "",
        weakPoints: List<String> = emptyList(),
        memory: List<WorkspaceMemoryEntry> = emptyList()
    ) = withContext(Dispatchers.IO) {
        if (workspaceId.isNullOrBlank()) return@withContext
        val app = ctx.applicationContext
        val payload = eventPayloadJson(
            type = type,
            title = title,
            description = description,
            recordId = recordId,
            containerId = containerId,
            taskId = taskId,
            currentStep = currentStep,
            weakPoints = weakPoints,
            memory = memory
        )
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder()
                    .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/workspaces/$workspaceId/events")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "工作区事件同步失败"))
                val workspace = parseJsonObjectBody(body, "工作区事件响应不是 JSON").objOrNull("workspace")
                    ?.let { parseWorkspace(it) }
                if (workspace != null) upsert(app, workspace) else recordLocalEvent(app, workspaceId, type, title, description, recordId, containerId, taskId, currentStep)
            }
        }.getOrElse {
            recordLocalEvent(app, workspaceId, type, title, description, recordId, containerId, taskId, currentStep)
        }
    }

    internal fun eventPayloadJson(
        type: String,
        title: String,
        description: String = "",
        recordId: String = "",
        containerId: String = "",
        taskId: String = "",
        currentStep: String = "",
        weakPoints: List<String> = emptyList(),
        memory: List<WorkspaceMemoryEntry> = emptyList()
    ): JsonObject = buildJsonObject {
        put("type", type)
        put("title", title)
        put("description", description)
        put("recordId", recordId)
        put("containerId", containerId)
        put("taskId", taskId)
        put("currentStep", currentStep)
        if (weakPoints.isNotEmpty()) putJsonArray("weakPoints") { weakPoints.filter { it.isNotBlank() }.take(20).forEach { add(it) } }
        if (memory.isNotEmpty()) putJsonArray("memory") { memory.take(30).forEach { add(memoryToJson(it)) } }
    }

    fun upsert(ctx: Context, workspace: LearningWorkspace) {
        val existing = loadWorkspaces(ctx.applicationContext)
        saveWorkspaces(ctx.applicationContext, listOf(workspace) + existing.filterNot { it.id == workspace.id })
    }

    fun removeWorkspace(workspaces: List<LearningWorkspace>, workspaceId: String): List<LearningWorkspace> =
        sortForDisplay(workspaces.filterNot { it.id == workspaceId })

    fun activeAfterWorkspaceRemoval(
        previousActiveId: String?,
        remaining: List<LearningWorkspace>
    ): LearningWorkspace? {
        val sorted = sortForDisplay(remaining)
        val previousActive = previousActiveId?.let { id -> sorted.firstOrNull { it.id == id } }
        return previousActive ?: sorted.firstOrNull { it.status == WorkspaceStatus.Active } ?: sorted.firstOrNull()
    }

    fun sortForDisplay(workspaces: List<LearningWorkspace>): List<LearningWorkspace> =
        workspaces.sortedWith(
            compareByDescending<LearningWorkspace> { it.pinnedAt > 0L }
                .thenByDescending { it.pinnedAt }
                .thenByDescending { it.updatedAt }
        )

    suspend fun renameWorkspace(ctx: Context, workspaceId: String, title: String): LearningWorkspace? =
        updateWorkspaceMeta(ctx, workspaceId, title = title.trim().take(80).ifBlank { return null })

    suspend fun setWorkspacePinned(ctx: Context, workspaceId: String, pinned: Boolean): LearningWorkspace? =
        updateWorkspaceMeta(ctx, workspaceId, pinnedAt = if (pinned) System.currentTimeMillis() else 0L)

    private suspend fun updateWorkspaceMeta(
        ctx: Context,
        workspaceId: String,
        title: String? = null,
        pinnedAt: Long? = null
    ): LearningWorkspace? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val cleanId = workspaceId.trim()
        if (cleanId.isBlank()) return@withContext null
        val existing = loadWorkspaces(app).firstOrNull { it.id == cleanId } ?: return@withContext null
        val now = System.currentTimeMillis()
        val local = existing.copy(
            title = title?.trim()?.take(80)?.ifBlank { existing.title } ?: existing.title,
            pinnedAt = pinnedAt ?: existing.pinnedAt,
            updatedAt = now
        )
        val payload = buildJsonObject {
            put("title", local.title)
            put("pinnedAt", local.pinnedAt)
        }
        val req = AuthStore.applyAuth(
            Request.Builder()
                .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/workspaces/$cleanId")
                .patch(payload.toString().toRequestBody("application/json".toMediaType())),
            app
        ).build()
        val remote = http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "工作区更新失败"))
            parseJsonObjectBody(body, "工作区更新响应不是 JSON").objOrNull("workspace")
                ?.let { parseWorkspace(it) }
                ?: local
        }
        saveWorkspaces(app, listOf(remote) + loadWorkspaces(app).filterNot { it.id == cleanId })
        remote
    }

    fun cascadeTargetsFor(workspace: LearningWorkspace): WorkspaceCascadeTargets {
        val recordIds = linkedSetOf<String>()
        val containerIds = linkedSetOf<String>()
        val taskIds = linkedSetOf<String>()
        workspace.linkedRecordIds.forEach { recordIds.addClean(it) }
        workspace.linkedContainerIds.forEach { containerIds.addClean(it) }
        workspace.linkedPlanTaskIds.forEach { taskIds.addClean(it) }
        workspace.events.forEach { event ->
            recordIds.addClean(event.recordId)
            containerIds.addClean(event.containerId)
            taskIds.addClean(event.taskId)
            when (event.targetKind) {
                "history" -> recordIds.addClean(event.targetId)
                "task", "plan_task" -> taskIds.addClean(event.targetId)
            }
        }
        return WorkspaceCascadeTargets(recordIds.toList(), containerIds.toList(), taskIds.toList())
    }

    suspend fun deleteWorkspace(ctx: Context, workspaceId: String): Boolean = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val cleanId = workspaceId.trim()
        if (cleanId.isBlank()) return@withContext true
        val existing = loadWorkspaces(app)
        val workspace = existing.firstOrNull { it.id == cleanId }
        val cascadeTargets = workspace?.let(::cascadeTargetsFor)
        val req = AuthStore.applyAuth(
            Request.Builder()
                .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/workspaces/$cleanId")
                .delete(),
            app
        ).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "云端工作区删除失败"))
        }
        cascadeTargets?.let { purgeLocalCascade(app, it) }
        UserLibraryStore.deleteWorkspaceItems(app, cleanId, cascadeTargets?.recordIds.orEmpty().toSet())
        saveWorkspaces(app, removeWorkspace(existing, cleanId))
        true
    }

    fun stepsForCurrentStep(steps: List<WorkspaceStep>, currentStep: String): List<WorkspaceStep> {
        if (steps.isEmpty()) return emptyList()
        val activeIndex = steps.indexOfFirst { it.id == currentStep }.takeIf { it >= 0 } ?: 0
        return steps.mapIndexed { index, step ->
            val status = when {
                index < activeIndex -> "done"
                index == activeIndex -> "running"
                else -> "pending"
            }
            step.copy(status = status)
        }
    }

    private fun mergeAndSave(ctx: Context, remote: List<LearningWorkspace>): List<LearningWorkspace> {
        val local = loadWorkspaces(ctx)
        val merged = (remote + local)
            .groupBy { it.id }
            .map { (_, versions) -> mergeWorkspaceVersions(versions) }
            .let { sortForDisplay(it) }
        saveWorkspaces(ctx, merged)
        return merged
    }

    internal fun mergeWorkspaceVersions(versions: List<LearningWorkspace>): LearningWorkspace {
        val base = versions.maxBy { it.updatedAt }
        val summaries = versions
            .sortedBy { it.updatedAt }
            .map { it.memorySummary.trim() }
            .filter { it.isNotBlank() }
        val summary = summaries.lastOrNull().orEmpty()
        val memory = versions
            .flatMap { it.memory }
            .groupBy { it.key }
            .values
            .map { entries ->
                entries.maxWith(
                    compareBy<WorkspaceMemoryEntry> { it.importance }
                        .thenBy { it.updatedAt }
                )
            }
            .sortedWith(
                compareByDescending<WorkspaceMemoryEntry> { it.importance }
                    .thenByDescending { it.updatedAt }
            )
            .take(30)
        return base.copy(
            memorySummary = summary.ifBlank { base.memorySummary },
            memory = memory
        )
    }

    private fun recordLocalEvent(
        ctx: Context,
        workspaceId: String,
        type: String,
        title: String,
        description: String,
        recordId: String,
        containerId: String,
        taskId: String,
        currentStep: String
    ) {
        val now = System.currentTimeMillis()
        val workspaces = loadWorkspaces(ctx).map { ws ->
            val nextStep = currentStep.ifBlank { inferStep(type, ws.currentStep) }
            if (ws.id != workspaceId) ws else ws.copy(
                currentStep = nextStep,
                updatedAt = now,
                plan = ws.plan.copy(steps = stepsForCurrentStep(ws.plan.steps, nextStep)),
                linkedRecordIds = addUnique(ws.linkedRecordIds, listOf(recordId)),
                linkedContainerIds = addUnique(ws.linkedContainerIds, listOf(containerId)),
                linkedPlanTaskIds = addUnique(ws.linkedPlanTaskIds, listOf(taskId)),
                events = listOf(
                    WorkspaceEvent(
                        id = UUID.randomUUID().toString(),
                        type = type,
                        title = title,
                        description = description,
                        recordId = recordId,
                        containerId = containerId,
                        taskId = taskId,
                        createdAt = now
                    )
                ) + ws.events
            )
        }
        saveWorkspaces(ctx, workspaces)
    }

    private fun purgeLocalCascade(ctx: Context, targets: WorkspaceCascadeTargets) {
        if (targets.isEmpty) return
        purgeHistoryRecords(ctx, targets.recordIds.toSet())
        purgeAutoFollowupMarks(ctx, targets.recordIds)
    }

    private fun purgeHistoryRecords(ctx: Context, recordIds: Set<String>) {
        if (recordIds.isEmpty()) return
        val prefs = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, "listene_history"), Context.MODE_PRIVATE)
        val raw = prefs.getString("records", "[]") ?: "[]"
        val arr = parseJsonArrayOrNull(raw) ?: JsonArray(emptyList())
        val next = buildJsonArray {
            for (i in 0 until arr.size) {
                val record = arr.objOrNull(i) ?: continue
                if (record.str("id") in recordIds) {
                    val audioUrl = record.objOrNull("content")?.str("audioUrl").orEmpty()
                    if (audioUrl.startsWith("/")) runCatching { File(audioUrl).delete() }
                } else {
                    add(record)
                }
            }
        }
        prefs.edit().putString("records", next.toString()).apply()
    }

    private fun purgeAutoFollowupMarks(ctx: Context, recordIds: List<String>) {
        if (recordIds.isEmpty()) return
        val prefs = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, "agent_auto_followup_store_v2"), Context.MODE_PRIVATE)
        val editor = prefs.edit()
        recordIds.forEach { editor.remove("done_$it") }
        editor.apply()
    }

    private fun fallbackWorkspace(need: String): LearningWorkspace {
        val now = System.currentTimeMillis()
        if (!AgentConversationService.wantsNewListeningPractice(need)) {
            val title = need.trim().take(28).ifBlank { "英语问题" }
            val plan = WorkspacePlan(
                title = title,
                summary = "",
                contentType = "chat",
                materialPrompt = "",
                difficulty = "普通",
                speechRate = "medium",
                voiceGender = "female",
                voiceProfile = "Female adult English voice.",
                rationale = "本地兜底问答工作区",
                steps = defaultSteps("chat")
            )
            return LearningWorkspace(
                id = "local_ws_${now}",
                title = plan.title,
                need = need,
                summary = plan.summary,
                currentStep = "chat",
                createdAt = now,
                updatedAt = now,
                plan = plan,
                events = listOf(
                    WorkspaceEvent(
                        id = UUID.randomUUID().toString(),
                        type = "workspace_created",
                        title = "创建工作区",
                        description = "创建问答工作区",
                        createdAt = now
                    )
                )
            )
        }
        val contentType = if (Regex("文章|新闻|讲座|article|lecture|news", RegexOption.IGNORE_CASE).containsMatchIn(need)) "article" else "dialogue"
        val difficulty = when {
            Regex("困难|高级|雅思|托福|C1|advanced", RegexOption.IGNORE_CASE).containsMatchIn(need) -> "困难"
            Regex("简单|基础|A2|beginner", RegexOption.IGNORE_CASE).containsMatchIn(need) -> "简单"
            else -> "普通"
        }
        val speechRate = when {
            need.contains("慢") -> "slow"
            need.contains("快") -> "fast"
            else -> "medium"
        }
        val voiceDirectives = voiceDirectivesFromText(need)
        val title = need.take(18).ifBlank { "英语听力闭环" }
        val plan = WorkspacePlan(
            title = "${title}工作区",
            summary = "已建立自动听力闭环，先生成素材，再由答题结果驱动分析和复练。",
            contentType = contentType,
            materialPrompt = need,
            difficulty = difficulty,
            speechRate = speechRate,
            voiceGender = voiceDirectives.voiceGender,
            voiceProfile = voiceDirectives.voiceProfile,
            pitch = voiceDirectives.pitch,
            accent = voiceDirectives.accent,
            tone = voiceDirectives.tone,
            rationale = "本地兜底规划",
            steps = defaultSteps(contentType)
        )
        return LearningWorkspace(
            id = "local_ws_${now}",
            title = plan.title,
            need = need,
            summary = plan.summary,
            createdAt = now,
            updatedAt = now,
            plan = plan,
            events = listOf(
                WorkspaceEvent(
                    id = UUID.randomUUID().toString(),
                    type = "workspace_created",
                    title = "创建工作区",
                    description = plan.summary,
                    createdAt = now
                )
            )
        )
    }

    private fun voiceDirectivesFromText(text: String): WorkspaceVoiceDirectives {
        val lower = text.lowercase()
        val voiceGender = when {
            listOf("female", "woman", "girl", "女声", "女音", "女生", "女性").any { lower.contains(it) } -> "female"
            listOf("male", "man", "boy", "男声", "男音", "男生", "男性").any { lower.contains(it) } -> "male"
            else -> "female"
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
        val genderWord = if (voiceGender == "male") "Male" else "Female"
        val parts = buildList {
            add(genderWord)
            tone.takeIf { it.isNotBlank() }?.let { add("$it tone") }
            pitch.takeIf { it.isNotBlank() }?.let { add("$it pitch") }
            accent.takeIf { it.isNotBlank() }?.let { add("$it accent") }
            add("English voice")
        }
        return WorkspaceVoiceDirectives(
            voiceGender = voiceGender,
            voiceProfile = parts.joinToString(" ").trim(),
            pitch = pitch,
            accent = accent,
            tone = tone
        )
    }

    private fun defaultSteps(contentType: String): List<WorkspaceStep> =
        if (contentType == "chat") {
            listOf(WorkspaceStep("chat", "问答", "可继续提问。", "running"))
        } else {
            listOf(
                WorkspaceStep("material", if (contentType == "article") "生成文章听力" else "生成对话听力", "根据需求生成脚本、音频、题目与解析。", "running"),
                WorkspaceStep("practice", "完成听力练习", "听音频、答题并核对答案。", "pending"),
                WorkspaceStep("analysis", "AI 错因分析", "根据答题结果定位薄弱点。", "pending"),
                WorkspaceStep("review", "词句复练与计划", "沉淀词句、复听任务和间隔复习。", "pending")
            )
        }

    internal fun parseWorkspace(obj: JsonObject?): LearningWorkspace {
        val o = obj ?: JsonObject(emptyMap())
        val now = System.currentTimeMillis()
        val planObj = o.objOrNull("plan") ?: JsonObject(emptyMap())
        val currentStep = o.str("currentStep").ifBlank {
            if (planObj.str("contentType") == "chat") "chat" else "material"
        }
        val plan = parsePlan(planObj).let { parsed ->
            parsed.copy(steps = stepsForCurrentStep(parsed.steps, currentStep))
        }
        return LearningWorkspace(
            id = o.str("id"),
            title = o.str("title", plan.title.ifBlank { "英语学习工作区" }),
            need = o.str("need"),
            summary = o.str("summary", plan.summary),
            status = parseStatus(o.str("status")),
            currentStep = currentStep,
            createdAt = o.long("createdAt", now),
            updatedAt = o.long("updatedAt", o.long("createdAt", now)),
            pinnedAt = o.long("pinnedAt", 0L),
            plan = plan,
            linkedRecordIds = jsonStringList(o.arrOrNull("linkedRecordIds")),
            linkedContainerIds = jsonStringList(o.arrOrNull("linkedContainerIds")),
            linkedPlanTaskIds = jsonStringList(o.arrOrNull("linkedPlanTaskIds")),
            memorySummary = o.str("memorySummary"),
            memory = parseMemory(o.arrOrNull("memory")),
            events = parseEvents(o.arrOrNull("events"))
        )
    }

    private fun parsePlan(obj: JsonObject): WorkspacePlan {
        val contentType = obj.str("contentType").takeIf { it == "article" || it == "dialogue" || it == "chat" } ?: "dialogue"
        return WorkspacePlan(
            title = obj.str("title", "英语学习工作区"),
            summary = obj.str("summary"),
            contentType = contentType,
            materialPrompt = obj.str("materialPrompt"),
            difficulty = obj.str("difficulty", "普通"),
            speechRate = obj.str("speechRate", "medium"),
            voiceGender = obj.str("voiceGender", "female"),
            voiceProfile = obj.str("voiceProfile"),
            pitch = obj.str("pitch"),
            accent = obj.str("accent"),
            tone = obj.str("tone"),
            rationale = obj.str("rationale"),
            steps = parseSteps(obj.arrOrNull("steps")).ifEmpty { defaultSteps(contentType) }
        )
    }

    private fun parseSteps(arr: JsonArray?): List<WorkspaceStep> {
        if (arr == null) return emptyList()
        return List(arr.size) { i ->
            val o = arr.objOrNull(i) ?: JsonObject(emptyMap())
            WorkspaceStep(
                id = o.str("id", "step_$i"),
                title = o.str("title"),
                description = o.str("description"),
                status = o.str("status", if (i == 0) "running" else "pending")
            )
        }.filter { it.title.isNotBlank() }
    }

    private fun parseEvents(arr: JsonArray?): List<WorkspaceEvent> {
        if (arr == null) return emptyList()
        return List(arr.size) { i ->
            val o = arr.objOrNull(i) ?: JsonObject(emptyMap())
            WorkspaceEvent(
                id = o.str("id"),
                type = o.str("type"),
                title = o.str("title"),
                description = o.str("description"),
                recordId = o.str("recordId"),
                containerId = o.str("containerId"),
                taskId = o.str("taskId"),
                targetKind = o.str("targetKind"),
                targetId = o.str("targetId"),
                createdAt = o.long("createdAt", 0L)
            )
        }.filter { it.title.isNotBlank() || it.type.isNotBlank() }
    }

    private fun parseMemory(arr: JsonArray?): List<WorkspaceMemoryEntry> {
        if (arr == null) return emptyList()
        return List(arr.size) { i ->
            val o = arr.objOrNull(i) ?: JsonObject(emptyMap())
            WorkspaceMemoryEntry(
                key = o.str("key"),
                type = o.str("type", "fact"),
                content = o.str("content"),
                importance = o.double("importance", 0.0),
                updatedAt = o.long("updatedAt", 0L)
            )
        }.filter { it.key.isNotBlank() && it.content.isNotBlank() }.take(30)
    }

    private fun workspaceToJson(ws: LearningWorkspace): JsonObject = buildJsonObject {
        put("id", ws.id)
        put("title", ws.title)
        put("need", ws.need)
        put("summary", ws.summary)
        put("status", ws.status.name.lowercase())
        put("currentStep", ws.currentStep)
        put("createdAt", ws.createdAt)
        put("updatedAt", ws.updatedAt)
        put("pinnedAt", ws.pinnedAt)
        put("plan", planToJson(ws.plan))
        putJsonArray("linkedRecordIds") { ws.linkedRecordIds.forEach { add(it) } }
        putJsonArray("linkedContainerIds") { ws.linkedContainerIds.forEach { add(it) } }
        putJsonArray("linkedPlanTaskIds") { ws.linkedPlanTaskIds.forEach { add(it) } }
        put("memorySummary", ws.memorySummary)
        putJsonArray("memory") { ws.memory.take(30).forEach { add(memoryToJson(it)) } }
        putJsonArray("events") { ws.events.forEach { add(eventToJson(it)) } }
    }

    private fun planToJson(plan: WorkspacePlan): JsonObject = buildJsonObject {
        put("title", plan.title)
        put("summary", plan.summary)
        put("contentType", plan.contentType)
        put("materialPrompt", plan.materialPrompt)
        put("difficulty", plan.difficulty)
        put("speechRate", plan.speechRate)
        put("voiceGender", plan.voiceGender)
        put("voiceProfile", plan.voiceProfile)
        put("pitch", plan.pitch)
        put("accent", plan.accent)
        put("tone", plan.tone)
        put("rationale", plan.rationale)
        putJsonArray("steps") {
            plan.steps.forEach { step ->
                addJsonObject {
                    put("id", step.id)
                    put("title", step.title)
                    put("description", step.description)
                    put("status", step.status)
                }
            }
        }
    }

    private fun eventToJson(event: WorkspaceEvent): JsonObject = buildJsonObject {
        put("id", event.id)
        put("type", event.type)
        put("title", event.title)
        put("description", event.description)
        put("recordId", event.recordId)
        put("containerId", event.containerId)
        put("taskId", event.taskId)
        put("targetKind", event.targetKind)
        put("targetId", event.targetId)
        put("createdAt", event.createdAt)
    }

    private fun memoryToJson(memory: WorkspaceMemoryEntry): JsonObject = buildJsonObject {
        put("key", memory.key)
        put("type", memory.type)
        put("content", memory.content)
        put("importance", memory.importance)
        put("updatedAt", memory.updatedAt)
    }

    private fun parseStatus(raw: String): WorkspaceStatus =
        when (raw.lowercase()) {
            "completed", "done" -> WorkspaceStatus.Completed
            "paused" -> WorkspaceStatus.Paused
            else -> WorkspaceStatus.Active
        }

    private fun jsonStringList(arr: JsonArray?): List<String> {
        if (arr == null) return emptyList()
        return List(arr.size) { i -> arr.str(i).trim() }.filter { it.isNotBlank() }
    }

    private fun addUnique(existing: List<String>, additions: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        (existing + additions).forEach { item ->
            val clean = item.trim()
            if (clean.isNotBlank()) seen.add(clean)
        }
        return seen.toList()
    }

    private fun inferStep(type: String, fallback: String): String =
        when {
            type.contains("record", ignoreCase = true) -> "practice"
            type.contains("practice", ignoreCase = true) -> "analysis"
            type.contains("analysis", ignoreCase = true) -> "review"
            type.contains("container", ignoreCase = true) || type.contains("plan", ignoreCase = true) -> "review"
            else -> fallback
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

    private fun MutableSet<String>.addClean(value: String) {
        val clean = value.trim()
        if (clean.isNotBlank()) add(clean)
    }
}
