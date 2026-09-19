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
import java.util.concurrent.TimeUnit

// 工作区聊天消息的本地(SharedPreferences)/远端持久化。
// 从 AgentListenEApp.kt 原样抽出（拆分超大单文件），行为/格式不变。
internal object AgentChatMessageStore {
    private const val PREFS_NAME = "agent_chat_messages_v1"
    private const val KEY_PREFIX = "workspace_"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun load(ctx: Context, workspaceId: String): List<AgentChatMessage> {
        if (workspaceId.isBlank()) return emptyList()
        val raw = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
            .getString(KEY_PREFIX + workspaceId, "[]") ?: "[]"
        val arr = parseJsonArrayOrNull(raw) ?: JsonArray(emptyList())
        return parseMessages(arr)
    }

    suspend fun loadRemote(ctx: Context, workspaceId: String): List<AgentChatMessage> = withContext(Dispatchers.IO) {
        if (workspaceId.isBlank()) return@withContext emptyList()
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder()
                    .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/workspaces/$workspaceId/messages")
                    .get(),
                ctx.applicationContext
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("messages failed: ${resp.code}")
                val arr = parseJsonObjectOrNull(body)?.arrOrNull("messages") ?: JsonArray(emptyList())
                parseMessages(arr)
            }
        }.getOrDefault(emptyList())
    }

    suspend fun saveRemote(ctx: Context, workspaceId: String, messages: List<AgentChatMessage>): LearningWorkspace? = withContext(Dispatchers.IO) {
        if (workspaceId.isBlank()) return@withContext null
        runCatching {
            val payload = buildJsonObject { putJsonArray("messages") { messages.takeLast(80).forEach { add(messageToJson(it)) } } }
            val req = AuthStore.applyAuth(
                Request.Builder()
                    .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/workspaces/$workspaceId/messages")
                    .put(payload.toString().toRequestBody("application/json".toMediaType())),
                ctx.applicationContext
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("message sync failed: ${resp.code}")
                parseJsonObjectOrNull(body)?.objOrNull("workspace")
                    ?.let { WorkspaceStore.parseWorkspace(it) }
                    ?.takeIf { it.id.isNotBlank() }
                    ?.also { WorkspaceStore.upsert(ctx.applicationContext, it) }
            }
        }.getOrNull()
    }

    private fun parseMessages(arr: JsonArray): List<AgentChatMessage> =
        List(arr.size) { index ->
            val obj = arr.objOrNull(index) ?: JsonObject(emptyMap())
            AgentChatMessage(
                id = obj.long("id", System.currentTimeMillis() + index),
                role = if (obj.str("role") == "user") AgentChatRole.User else AgentChatRole.Agent,
                text = obj.str("text"),
                attachments = parseAttachments(obj.arrOrNull("attachments")),
                audioUrl = obj.str("audioUrl"),
                microCardJson = obj.objOrNull("microCard")?.toString().orEmpty(),
                microAnswersJson = obj.objOrNull("microAnswers")?.toString().orEmpty(),
                solveJson = obj.objOrNull("solve")?.toString().orEmpty(),
                roleplayJson = obj.objOrNull("roleplay")?.toString().orEmpty(),
                listeningRecordId = obj.str("listeningRecordId"),
                shadowingJson = obj.objOrNull("shadowing")?.toString().orEmpty()
            )
        }.filter { message ->
            message.text.isNotBlank() ||
                message.attachments.isNotEmpty() ||
                message.audioUrl.isNotBlank() ||
                message.microCardJson.isNotBlank() ||
                message.solveJson.isNotBlank() ||
                message.roleplayJson.isNotBlank() ||
                message.listeningRecordId.isNotBlank() ||
                message.shadowingJson.isNotBlank()
        }.takeLast(80)

    fun save(ctx: Context, workspaceId: String, messages: List<AgentChatMessage>) {
        if (workspaceId.isBlank()) return
        val arr = buildJsonArray { messages.takeLast(80).forEach { message -> add(messageToJson(message)) } }
        ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + workspaceId, arr.toString())
            .apply()
    }

    private fun messageToJson(message: AgentChatMessage): JsonObject = buildJsonObject {
        put("id", message.id)
        put("role", if (message.role == AgentChatRole.User) "user" else "agent")
        put("text", message.text)
        putJsonArray("attachments") {
            message.attachments.forEach { attachment ->
                addJsonObject {
                    put("name", attachment.name)
                    put("mimeType", attachment.mimeType)
                    put("sizeBytes", attachment.sizeBytes)
                    put("localPath", attachment.localPath)
                    put("generated", attachment.generated)
                    put("sourceRecordId", attachment.sourceRecordId)
                    put("sourceKind", attachment.sourceKind)
                    put("sourceCardId", attachment.sourceCardId)
                    put("sourceCardTitle", attachment.sourceCardTitle)
                    put("sourceMessageId", attachment.sourceMessageId)
                    put("copyToDownloads", attachment.copyToDownloads)
                }
            }
        }
        put("audioUrl", message.audioUrl)
        if (message.microCardJson.isNotBlank()) {
            parseJsonObjectOrNull(message.microCardJson)?.let { put("microCard", it) }
        }
        if (message.microAnswersJson.isNotBlank()) {
            parseJsonObjectOrNull(message.microAnswersJson)?.let { put("microAnswers", it) }
        }
        if (message.solveJson.isNotBlank()) {
            parseJsonObjectOrNull(message.solveJson)?.let { put("solve", it) }
        }
        if (message.roleplayJson.isNotBlank()) {
            parseJsonObjectOrNull(message.roleplayJson)?.let { put("roleplay", it) }
        }
        if (message.listeningRecordId.isNotBlank()) put("listeningRecordId", message.listeningRecordId)
        if (message.shadowingJson.isNotBlank()) {
            parseJsonObjectOrNull(message.shadowingJson)?.let { put("shadowing", it) }
        }
    }

    fun delete(ctx: Context, workspaceId: String) {
        if (workspaceId.isBlank()) return
        ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PREFIX + workspaceId)
            .apply()
    }

    private fun parseAttachments(arr: JsonArray?): List<AgentInputAttachment> {
        if (arr == null) return emptyList()
        return List(arr.size) { index ->
            val obj = arr.objOrNull(index) ?: JsonObject(emptyMap())
            AgentInputAttachment(
                name = obj.str("name"),
                mimeType = obj.str("mimeType"),
                sizeBytes = obj.long("sizeBytes", 0L),
                localPath = obj.str("localPath"),
                generated = obj.bool("generated", false),
                sourceRecordId = obj.str("sourceRecordId"),
                sourceKind = obj.str("sourceKind"),
                sourceCardId = obj.str("sourceCardId"),
                sourceCardTitle = obj.str("sourceCardTitle"),
                sourceMessageId = obj.long("sourceMessageId", 0L),
                copyToDownloads = obj.bool("copyToDownloads", false)
            )
        }.filter { it.name.isNotBlank() || it.mimeType.isNotBlank() }
    }
}
