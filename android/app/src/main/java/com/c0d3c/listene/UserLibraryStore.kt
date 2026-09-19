package com.c0d3c.listene

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

data class UserLibraryItem(
    val id: String,
    val kind: String,
    val title: String,
    val summary: String,
    val data: JsonObject,
    val createdAt: Long,
    val updatedAt: Long
)

data class UserLibraryFileAsset(
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String,
    val downloadUrl: String,
    val generated: Boolean,
    val previewUrl: String = ""
) {
    fun toAttachment(): AgentInputAttachment? =
        localPath.takeIf { it.isNotBlank() && File(it).exists() }?.let {
            AgentInputAttachment(
                name = name,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                localPath = it,
                generated = generated
            )
    }
}

data class UserLibraryWorkspacePurgeResult(
    val remaining: List<UserLibraryItem>,
    val removed: List<UserLibraryItem>
)

data class UserLibraryChangeEvent(
    val kind: String,
    val itemId: String,
    val action: String
)

enum class UserLibraryFilePreviewMode {
    Text,
    Image,
    Audio,
    Video,
    Web,
    External
}

enum class AgentLibraryClickDestination {
    CardDetail,
    FilePreview
}

enum class AgentActionMenuVisualStyle {
    RecentWorkspace
}

data class AgentLibraryMenuAction(
    val id: String,
    val label: String
)

internal fun userLibraryWorkspaceId(item: UserLibraryItem): String =
    item.data.str("workspaceId").trim()

internal fun userLibraryRecordId(item: UserLibraryItem): String =
    item.data.str("recordId")
        .ifBlank { item.data.str("latestRecordId") }
        .trim()

// 卡片库读数只认原生 microCard JSON（旧协议 cardSpec 及其读时降解已整体退役；存量旧协议条目不再渲染）。
internal fun userLibraryMicroCard(item: UserLibraryItem): MicroCard? =
    item.data.objOrNull("microCard")?.let { MicroCardParser.parse(it) }

// 听力练习卡（卡片库里与练习微元卡并列的一类）：data.listening 存整份听力清单(manifest)，
// 点开在卡片详情里内联可练（真音频+答题+核对+复盘），并可导出 ZIP 进文件库。
internal fun isListeningLibraryItem(item: UserLibraryItem): Boolean =
    normalizeUserLibraryKind(item.kind) == "cards" && item.data.objOrNull("listening") != null

// 从卡片库听力项还原出可播放/可作答/可导出的 HistoryRecord（内容取自 manifest，音频路径单独存 data.audioUrl）。
internal fun userLibraryListeningRecord(item: UserLibraryItem): HistoryRecord? {
    val manifest = item.data.objOrNull("listening") ?: return null
    val contentJson = manifest.objOrNull("content") ?: return null
    val content = parseListeningContentFromManifest(contentJson)
    val audioUrl = item.data.str("audioUrl").trim().ifBlank { content.audioUrl.orEmpty() }
    val finalContent = if (audioUrl.isNotBlank()) content.copy(audioUrl = audioUrl) else content
    return HistoryRecord(
        id = item.data.str("recordId").trim().ifBlank { item.id },
        scene = manifest.str("scene").ifBlank { item.title },
        createdAt = item.createdAt,
        content = finalContent,
        contentType = manifest.str("contentType", "dialogue")
    )
}

internal fun userLibraryPinnedAt(item: UserLibraryItem): Long =
    item.data.long("pinnedAt", 0L)

internal fun normalizeUserLibraryKind(kind: String): String =
    when (kind.trim()) {
        "card", "cards" -> "cards"
        "file", "files" -> "files"
        else -> kind.trim()
    }

internal fun agentLibraryClickDestination(kind: String): AgentLibraryClickDestination =
    when (normalizeUserLibraryKind(kind)) {
        "files" -> AgentLibraryClickDestination.FilePreview
        else -> AgentLibraryClickDestination.CardDetail
    }

internal fun agentLibraryRowShowsInlineDelete(kind: String): Boolean = false



internal fun agentLibraryCardMenuActions(
    isPinned: Boolean,
    canExportQuestionBank: Boolean = true
): List<AgentLibraryMenuAction> =
    buildList {
        if (canExportQuestionBank) add(AgentLibraryMenuAction("export_question_bank", "导出题库"))
        add(AgentLibraryMenuAction("pin", if (isPinned) "取消置顶" else "置顶"))
        add(AgentLibraryMenuAction("rename", "重命名"))
        add(AgentLibraryMenuAction("delete", "删除"))
    }

internal fun agentLibraryFileMenuActions(): List<AgentLibraryMenuAction> =
    listOf(
        AgentLibraryMenuAction("download", "下载"),
        AgentLibraryMenuAction("export", "导出"),
        AgentLibraryMenuAction("share", "分享"),
        AgentLibraryMenuAction("delete", "删除")
    )

internal fun sortUserLibraryItemsForDisplay(items: List<UserLibraryItem>): List<UserLibraryItem> =
    items.sortedWith(
        compareByDescending<UserLibraryItem> { userLibraryPinnedAt(it) > 0L }
            .thenByDescending { userLibraryPinnedAt(it) }
            .thenByDescending { it.updatedAt }
    )

internal fun userLibraryChangeMatchesKind(eventKind: String, routeKind: String): Boolean =
    normalizeUserLibraryKind(eventKind) == normalizeUserLibraryKind(routeKind)

internal fun upsertUserLibraryItem(items: List<UserLibraryItem>, item: UserLibraryItem): List<UserLibraryItem> {
    val normalizedItem = item.withNormalizedLibraryKind()
    val key = userLibraryItemStableKey(normalizedItem)
    val filtered = items.filterNot { existing ->
        existing.id == item.id || userLibraryItemStableKey(existing) == key
    }
    return sortUserLibraryItemsForDisplay((filtered + normalizedItem).filter { it.id.isNotBlank() })
}

internal fun removeUserLibraryItemsForWorkspace(
    items: List<UserLibraryItem>,
    workspaceId: String,
    recordIds: Set<String> = emptySet()
): UserLibraryWorkspacePurgeResult {
    val cleanWorkspaceId = workspaceId.trim()
    val cleanRecordIds = recordIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
    if (cleanWorkspaceId.isBlank() && cleanRecordIds.isEmpty()) return UserLibraryWorkspacePurgeResult(
        remaining = sortUserLibraryItemsForDisplay(items),
        removed = emptyList()
    )
    val removed = items.filter { item ->
        userLibraryWorkspaceId(item) == cleanWorkspaceId ||
            (cleanRecordIds.isNotEmpty() && userLibraryRecordId(item) in cleanRecordIds)
    }
    val remaining = items.filterNot { item -> item in removed }
    return UserLibraryWorkspacePurgeResult(
        remaining = sortUserLibraryItemsForDisplay(remaining),
        removed = sortUserLibraryItemsForDisplay(removed)
    )
}

internal fun mergeUserLibraryItems(
    localItems: List<UserLibraryItem>,
    remoteItems: List<UserLibraryItem>
): List<UserLibraryItem> {
    val merged = (remoteItems + localItems)
        .map { it.withNormalizedLibraryKind() }
        .filter(::userLibraryItemCanBeShown)
        .groupBy(::userLibraryItemStableKey)
        .values
        .mapNotNull(::mergeUserLibraryItemGroup)
    return sortUserLibraryItemsForDisplay(merged)
}

private fun mergeUserLibraryItemGroup(group: List<UserLibraryItem>): UserLibraryItem? {
    val latest = group.maxByOrNull { it.updatedAt } ?: return null
    if (normalizeUserLibraryKind(latest.kind) != "files") return latest
    val localPathSource = group.firstOrNull { it.data.str("localPath").trim().isNotBlank() }
    val downloadSource = group.firstOrNull { item ->
        item.data.str("downloadUrl")
            .ifBlank { item.data.str("url") }
            .ifBlank { item.data.str("href") }
            .trim()
            .isNotBlank()
    }
    val previewSource = group.firstOrNull { item ->
        item.data.str("previewUrl")
            .ifBlank { item.data.str("preview") }
            .ifBlank { item.data.str("viewUrl") }
            .trim()
            .isNotBlank()
    }
    if (localPathSource == null && downloadSource == null && previewSource == null) return latest
    var data = latest.data
    localPathSource?.data?.str("localPath")?.trim()?.takeIf { it.isNotBlank() }?.let { localPath ->
        if (data.str("localPath").trim().isBlank()) data = data.with("localPath", localPath)
    }
    downloadSource?.let { source ->
        val downloadUrl = source.data.str("downloadUrl")
            .ifBlank { source.data.str("url") }
            .ifBlank { source.data.str("href") }
            .trim()
        if (downloadUrl.isNotBlank() && data.str("downloadUrl").trim().isBlank()) {
            data = data.with("downloadUrl", downloadUrl)
        }
    }
    previewSource?.let { source ->
        val previewUrl = source.data.str("previewUrl")
            .ifBlank { source.data.str("preview") }
            .ifBlank { source.data.str("viewUrl") }
            .trim()
        if (userLibraryIsRemoteUrl(previewUrl) && data.str("previewUrl").trim().isBlank()) {
            data = data.with("previewUrl", previewUrl)
        }
    }
    return latest.copy(data = data)
}

private fun userLibraryIsRemoteUrl(value: String): Boolean =
    value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)

internal fun userLibraryItemCanBeShown(item: UserLibraryItem): Boolean {
    val kind = normalizeUserLibraryKind(item.kind)
    if (kind != "cards") return true
    // 听力练习卡没有 microCard 节点（内容存在 listening manifest 里），单独放行展示。
    if (item.data.objOrNull("listening") != null) return true
    val cardTitle = item.data.objOrNull("microCard")?.str("title")
        .orEmpty()
        .ifBlank { item.data.objOrNull("card")?.str("title").orEmpty() }
    if (Regex("AI\\s*卡片已重置|稳定练习卡片|坏卡片|card\\s*reset|parse\\s*failed", RegexOption.IGNORE_CASE)
            .containsMatchIn("${item.title} ${item.summary} $cardTitle")
    ) return false
    return userLibraryMicroCard(item)?.nodes?.isNotEmpty() == true
}

internal fun userLibraryItemStableKey(item: UserLibraryItem): String {
    val data = item.data
    val clientId = data.str("clientId").trim()
    if (clientId.isNotBlank()) return "client|${normalizeUserLibraryKind(item.kind)}|$clientId"
    val workspaceId = data.str("workspaceId")
    val kind = normalizeUserLibraryKind(item.kind)
    if (kind == "files") {
        val fileIdentity = data.str("recordId")
            .ifBlank { data.str("latestRecordId") }
            .ifBlank { data.str("localPath") }
            .ifBlank { data.str("downloadUrl") }
            .ifBlank { data.str("url") }
            .ifBlank { data.str("href") }
            .ifBlank { data.str("previewUrl") }
            .ifBlank { data.str("preview") }
            .ifBlank { data.str("viewUrl") }
            .ifBlank { item.id }
        return listOf(kind, workspaceId, item.title, data.str("mimeType").ifBlank { item.summary }, fileIdentity)
            .joinToString("|")
    }
    val cardJson = data.objOrNull("microCard")?.toString()
        .orEmpty()
        .ifBlank { data.objOrNull("card")?.toString().orEmpty() }
    return listOf(kind, workspaceId, item.title, cardJson)
        .joinToString("|")
}

private fun UserLibraryItem.withNormalizedLibraryKind(): UserLibraryItem {
    val normalizedKind = normalizeUserLibraryKind(kind)
    return if (normalizedKind == kind) this else copy(kind = normalizedKind)
}

internal fun renameUserLibraryCardItem(item: UserLibraryItem, title: String): UserLibraryItem {
    val cleanTitle = title.trim().take(140)
    if (cleanTitle.isBlank()) return item
    val base = userLibraryDataWithClientId(item)
    val micro = item.data.objOrNull("microCard")
    val nextData = if (micro != null) {
        base.with("microCard", JsonObject(micro.toMutableMap().apply { put("title", JsonPrimitive(cleanTitle)) }))
    } else {
        base
    }
    return item.copy(title = cleanTitle, data = nextData, updatedAt = System.currentTimeMillis())
}

internal fun setUserLibraryPinned(item: UserLibraryItem, pinned: Boolean): UserLibraryItem {
    val base = userLibraryDataWithClientId(item)
    val nextData = if (pinned) base.with("pinnedAt", System.currentTimeMillis()) else base.without("pinnedAt")
    return item.copy(data = nextData, updatedAt = System.currentTimeMillis())
}

internal fun userLibraryDataWithClientId(item: UserLibraryItem): JsonObject {
    val data = item.data
    return if (data.str("clientId").trim().isBlank()) {
        data.with("clientId", userLibraryFallbackClientId(item.kind, item.title, data))
    } else {
        data
    }
}

private fun userLibraryFallbackClientId(kind: String, title: String, data: JsonObject): String {
    val seed = listOf(
        normalizeUserLibraryKind(kind),
        data.str("workspaceId"),
        data.str("recordId").ifBlank { data.str("latestRecordId") },
        data.str("localPath"),
        data.objOrNull("card")?.toString().orEmpty(),
        title
    ).joinToString("|")
    return "client_${seed.hashCode().toUInt().toString(16)}"
}

internal fun userLibraryFileAsset(item: UserLibraryItem): UserLibraryFileAsset =
    UserLibraryFileAsset(
        name = item.title.ifBlank {
            item.data.str("name")
                .ifBlank { item.data.str("fileName") }
                .ifBlank { "ListenE 文件" }
        },
        mimeType = item.data.str("mimeType")
            .ifBlank { item.summary }
            .ifBlank { "application/octet-stream" },
        sizeBytes = item.data.long("sizeBytes", 0L),
        localPath = item.data.str("localPath").trim(),
        downloadUrl = item.data.str("downloadUrl")
            .ifBlank { item.data.str("url") }
            .ifBlank { item.data.str("href") }
            .trim(),
        generated = item.data.bool("generated", false),
        previewUrl = item.data.str("previewUrl")
            .ifBlank { item.data.str("preview") }
            .ifBlank { item.data.str("viewUrl") }
            .trim()
    )

internal fun userLibraryGeneratedFileData(
    workspaceId: String,
    attachment: AgentInputAttachment,
    extraData: JsonObject = JsonObject(emptyMap())
): JsonObject {
    var data = extraData
        .with("workspaceId", workspaceId)
        .with(
            "clientId",
            userLibraryFallbackClientId(
                "files",
                attachment.name,
                buildJsonObject {
                    put("workspaceId", workspaceId)
                    put("localPath", attachment.localPath)
                }
            )
        )
        .with("name", attachment.name)
        .with("fileName", attachment.name)
        .with("mimeType", attachment.mimeType)
        .with("sizeBytes", attachment.sizeBytes)
        .with("localPath", attachment.localPath)
        .with("generated", attachment.generated)
    if (attachment.sourceRecordId.isNotBlank()) {
        data = data.with("recordId", attachment.sourceRecordId)
    }
    return data
}


internal fun userLibraryFilePreviewMode(item: UserLibraryItem): UserLibraryFilePreviewMode {
    val asset = userLibraryFileAsset(item)
    val mime = asset.mimeType.lowercase()
    val extension = asset.name.substringAfterLast('.', "").lowercase()
    val hasLocal = asset.localPath.isNotBlank() && File(asset.localPath).exists()
    val hasPreviewOnline = userLibraryIsRemoteUrl(asset.previewUrl)
    val hasDownloadOnline = userLibraryIsRemoteUrl(asset.downloadUrl)
    val hasOnline = hasPreviewOnline || hasDownloadOnline
    val textExtensions = setOf("txt", "md", "markdown", "json", "csv", "xml", "log", "srt", "vtt", "docx")
    val htmlExtensions = setOf("html", "htm")
    return when {
        mime.startsWith("audio/") -> UserLibraryFilePreviewMode.Audio
        mime.startsWith("video/") -> UserLibraryFilePreviewMode.Video
        mime.startsWith("image/") && hasLocal -> UserLibraryFilePreviewMode.Image
        mime.startsWith("image/") && hasOnline -> UserLibraryFilePreviewMode.Web
        hasLocal && (
            mime.startsWith("text/") ||
                mime == "application/json" ||
                mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                extension in textExtensions
            ) ->
            UserLibraryFilePreviewMode.Text
        hasOnline && (mime == "text/html" || extension in htmlExtensions) -> UserLibraryFilePreviewMode.Web
        hasOnline && mime != "application/zip" && extension != "zip" && hasPreviewOnline -> UserLibraryFilePreviewMode.Web
        hasOnline && (mime.startsWith("text/") || mime == "application/json" || extension in textExtensions) ->
            UserLibraryFilePreviewMode.Web
        else -> UserLibraryFilePreviewMode.External
    }
}

object UserLibraryStore {
    private const val PREFS_NAME = "agent_user_library_local_v1"
    private const val KEY_PREFIX = "kind_"
    private const val TOMBSTONE_PREFIX = "deleted_"

    private val _changes = MutableSharedFlow<UserLibraryChangeEvent>(
        extraBufferCapacity = 32
    )
    val changes = _changes.asSharedFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun saveGeneratedFile(
        ctx: Context,
        workspaceId: String,
        attachment: AgentInputAttachment,
        extraData: JsonObject = JsonObject(emptyMap())
    ): UserLibraryItem = saveItem(
        ctx = ctx,
        kind = "files",
        title = attachment.name.ifBlank { "Agent 文件" },
        summary = attachment.mimeType.ifBlank { "由 Agent 生成的文件" },
        data = userLibraryGeneratedFileData(workspaceId, attachment, extraData)
    )

    // 微元练习卡入库（cardSpec 退役后聊天练习卡的库存形态）：直接存服务端原生 microCard JSON。
    suspend fun saveGeneratedMicroCard(
        ctx: Context,
        workspaceId: String,
        microCard: JsonObject
    ): UserLibraryItem {
        val title = microCard.str("title").ifBlank { "ListenE 练习卡" }
        return saveItem(
            ctx = ctx,
            kind = "cards",
            title = title,
            summary = "Agent 生成的练习卡片",
            data = buildJsonObject {
                put("workspaceId", workspaceId)
                put(
                    "clientId",
                    userLibraryFallbackClientId(
                        "cards",
                        title,
                        buildJsonObject {
                            put("workspaceId", workspaceId)
                            put("microCard", microCard)
                        }
                    )
                )
                put("microCard", microCard)
            }
        )
    }

    // 听力练习入卡片库：与练习卡并列的一类 cards 项，data.listening 存整份听力清单(manifest)，
    // 另存 recordId/workspaceId/audioUrl 供还原与 ZIP 导出。clientId 以 recordId 为种子，重复就绪时幂等去重。
    suspend fun saveGeneratedListeningCard(
        ctx: Context,
        workspaceId: String,
        record: HistoryRecord
    ): UserLibraryItem {
        val title = record.content.title.ifBlank { record.scene }.ifBlank { "听力练习" }
        val questionCount = record.content.questions.count { it.questionText.isNotBlank() }
        val manifest = parseJsonObjectOrNull(listeningManifestJson(record)) ?: JsonObject(emptyMap())
        val audioUrl = record.content.audioUrl.orEmpty()
        return saveItem(
            ctx = ctx,
            kind = "cards",
            title = title,
            summary = if (questionCount > 0) "听力练习 · $questionCount 题" else "听力练习",
            data = buildJsonObject {
                put("workspaceId", workspaceId)
                put("recordId", record.id)
                put("contentType", record.contentType)
                if (audioUrl.isNotBlank()) put("audioUrl", audioUrl)
                put("listening", manifest)
                put(
                    "clientId",
                    userLibraryFallbackClientId(
                        "cards",
                        title,
                        buildJsonObject {
                            put("workspaceId", workspaceId)
                            put("recordId", record.id)
                            put("listening", JsonPrimitive(record.id))
                        }
                    )
                )
            }
        )
    }

    private suspend fun saveItem(
        ctx: Context,
        kind: String,
        title: String,
        summary: String,
        data: JsonObject
    ): UserLibraryItem = withContext(Dispatchers.IO) {
        val normalizedKind = normalizeUserLibraryKind(kind)
        val now = System.currentTimeMillis()
        val localData = if (data.str("clientId").trim().isBlank()) {
            data.with("clientId", userLibraryFallbackClientId(normalizedKind, title, data))
        } else {
            data
        }
        val localItem = UserLibraryItem(
            id = localLibraryItemId(normalizedKind, title, data, now),
            kind = normalizedKind,
            title = title,
            summary = summary,
            data = localData,
            createdAt = now,
            updatedAt = now
        )
        saveLocalItem(ctx, localItem)
        emitChange(normalizedKind, localItem.id, "save")
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val payload = buildJsonObject {
                    put("title", title)
                    put("summary", summary)
                    put("data", localData)
                }
                val req = AuthStore.applyAuth(
                    Request.Builder()
                        .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/library/$normalizedKind")
                        .post(payload.toString().toRequestBody("application/json".toMediaType())),
                    ctx.applicationContext
                ).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("library sync failed: ${resp.code}")
                }
            }
        }
        localItem
    }

    suspend fun listItems(ctx: Context, kind: String): List<UserLibraryItem> = withContext(Dispatchers.IO) {
        val normalizedKind = normalizeUserLibraryKind(kind)
        val localItems = loadLocalItems(ctx, normalizedKind)
        val req = AuthStore.applyAuth(
            Request.Builder()
                .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/library/$normalizedKind")
                .get(),
            ctx.applicationContext
        ).build()
        val remoteItems = runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "库同步失败"))
                val arr = parseJsonObjectOrNull(body)?.arrOrNull("items")
                    ?: error("库响应不是 JSON")
                List(arr.size) { index ->
                    parseItem(arr.objOrNull(index))
                }.filter { it.id.isNotBlank() }
            }
        }.getOrDefault(emptyList())
        val tombstones = loadDeletedLibraryKeys(ctx, normalizedKind)
        mergeUserLibraryItems(localItems, remoteItems)
            .filterNot { it.id in tombstones || userLibraryItemStableKey(it) in tombstones }
    }

    private fun parseItem(obj: JsonObject?): UserLibraryItem =
        UserLibraryItem(
            id = obj?.str("id") ?: "",
            kind = normalizeUserLibraryKind(obj?.str("kind") ?: ""),
            title = obj?.str("title") ?: "",
            summary = obj?.str("summary") ?: "",
            data = obj?.objOrNull("data") ?: JsonObject(emptyMap()),
            createdAt = obj?.long("createdAt", 0L) ?: 0L,
            updatedAt = obj?.long("updatedAt", 0L) ?: 0L
        )

    suspend fun deleteItem(ctx: Context, kind: String, id: String) = withContext(Dispatchers.IO) {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return@withContext
        val normalizedKind = normalizeUserLibraryKind(kind)
        markLocalLibraryDeleted(ctx, normalizedKind, cleanId)
        deleteLocalItem(ctx, normalizedKind, cleanId)
        emitChange(normalizedKind, cleanId, "delete")
        val req = AuthStore.applyAuth(
            Request.Builder()
                .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/library/$normalizedKind/${Uri.encode(cleanId)}")
                .delete(),
                ctx.applicationContext
        ).build()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "删除失败"))
                }
            }
        }
    }

    suspend fun updateItem(ctx: Context, kind: String, item: UserLibraryItem): UserLibraryItem = withContext(Dispatchers.IO) {
        val normalizedKind = normalizeUserLibraryKind(kind)
        val normalizedItem = item.copy(
            kind = normalizedKind,
            data = userLibraryDataWithClientId(item.copy(kind = normalizedKind)),
            updatedAt = System.currentTimeMillis()
        )
        saveLocalItem(ctx, normalizedItem)
        emitChange(normalizedKind, normalizedItem.id, "update")
        val payload = buildJsonObject {
            put("id", normalizedItem.id)
            put("title", normalizedItem.title)
            put("summary", normalizedItem.summary)
            put("data", normalizedItem.data)
        }
        val req = AuthStore.applyAuth(
            Request.Builder()
                .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/library/$normalizedKind")
                .post(payload.toString().toRequestBody("application/json".toMediaType())),
                ctx.applicationContext
        ).build()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                http.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "更新失败"))
                    parseJsonObjectOrNull(body)?.objOrNull("item")?.let { parseItem(it) }
                        ?.let { remoteItem ->
                            saveLocalItem(ctx, remoteItem.copy(data = userLibraryDataWithClientId(remoteItem)))
                            emitChange(normalizedKind, remoteItem.id, "update")
                        }
                }
            }
        }
        normalizedItem
    }

    suspend fun deleteWorkspaceItems(
        ctx: Context,
        workspaceId: String,
        recordIds: Set<String> = emptySet()
    ): Int = withContext(Dispatchers.IO) {
        val cleanWorkspaceId = workspaceId.trim()
        val cleanRecordIds = recordIds.map { it.trim() }.filter { it.isNotBlank() }.toSet()
        if (cleanWorkspaceId.isBlank() && cleanRecordIds.isEmpty()) return@withContext 0
        val kinds = listOf("cards", "files")
        var deleted = 0
        for (kind in kinds) {
            val items = runCatching { listItems(ctx, kind) }.getOrDefault(emptyList())
            removeUserLibraryItemsForWorkspace(items, cleanWorkspaceId, cleanRecordIds).removed.forEach { item ->
                runCatching { deleteItem(ctx, kind, item.id) }
                    .onSuccess { deleted += 1 }
            }
        }
        deleted
    }

    private fun httpErrorMessage(code: Int, body: String, fallback: String): String {
        val detail = parseJsonObjectOrNull(body)?.str("detail")?.takeIf { it.isNotBlank() }
        val preview = body.replace(Regex("""\s+"""), " ").trim().ifBlank { "响应为空" }.take(160)
        return detail ?: "$fallback (HTTP $code)：$preview"
    }

    private fun emitChange(kind: String, itemId: String, action: String) {
        _changes.tryEmit(UserLibraryChangeEvent(normalizeUserLibraryKind(kind), itemId, action))
    }

    private fun loadLocalItems(ctx: Context, kind: String): List<UserLibraryItem> {
        val normalizedKind = normalizeUserLibraryKind(kind)
        val prefs = ctx.applicationContext
            .getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
        return libraryKindStorageKeys(normalizedKind)
            .flatMap { storageKind ->
                val raw = prefs.getString(KEY_PREFIX + storageKind, "[]") ?: "[]"
                val arr = parseJsonArrayOrNull(raw) ?: kotlinx.serialization.json.JsonArray(emptyList())
                List(arr.size) { index ->
                    parseItem(arr.objOrNull(index))
                }
            }
            .filter { it.id.isNotBlank() && normalizeUserLibraryKind(it.kind) == normalizedKind }
            .let { items -> mergeUserLibraryItems(localItems = items, remoteItems = emptyList()) }
    }

    private fun saveLocalItem(ctx: Context, item: UserLibraryItem) {
        val normalizedItem = item.withNormalizedLibraryKind()
        val next = upsertUserLibraryItem(loadLocalItems(ctx, normalizedItem.kind), normalizedItem)
        saveLocalItems(ctx, normalizedItem.kind, next)
    }

    private fun deleteLocalItem(ctx: Context, kind: String, id: String) {
        val normalizedKind = normalizeUserLibraryKind(kind)
        val current = loadLocalItems(ctx, normalizedKind)
        val targetKeys = current
            .filter { it.id == id }
            .map(::userLibraryItemStableKey)
            .toSet()
        val next = current.filterNot {
            it.id == id || userLibraryItemStableKey(it) in targetKeys
        }
        saveLocalItems(ctx, normalizedKind, next)
    }

    private fun markLocalLibraryDeleted(ctx: Context, kind: String, id: String) {
        val normalizedKind = normalizeUserLibraryKind(kind)
        val current = loadLocalItems(ctx, normalizedKind)
        val keys = current
            .filter { it.id == id }
            .flatMap { listOf(it.id, userLibraryItemStableKey(it)) }
            .ifEmpty { listOf(id) }
            .filter { it.isNotBlank() }
        if (keys.isEmpty()) return
        val existing = loadDeletedLibraryKeys(ctx, normalizedKind).toMutableSet()
        existing.addAll(keys)
        saveDeletedLibraryKeys(ctx, normalizedKind, existing)
    }

    private fun saveLocalItems(ctx: Context, kind: String, items: List<UserLibraryItem>) {
        val normalizedKind = normalizeUserLibraryKind(kind)
        val arr = buildJsonArray {
            items.forEach { item ->
                val normalizedItem = item.withNormalizedLibraryKind()
                add(
                    buildJsonObject {
                        put("id", normalizedItem.id)
                        put("kind", normalizedItem.kind)
                        put("title", normalizedItem.title)
                        put("summary", normalizedItem.summary)
                        put("data", normalizedItem.data)
                        put("createdAt", normalizedItem.createdAt)
                        put("updatedAt", normalizedItem.updatedAt)
                    }
                )
            }
        }
        ctx.applicationContext
            .getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFIX + normalizedKind, arr.toString())
            .apply {
                libraryKindStorageKeys(normalizedKind)
                    .filterNot { it == normalizedKind }
                    .forEach { remove(KEY_PREFIX + it) }
            }
            .apply()
    }

    private fun libraryKindStorageKeys(kind: String): List<String> =
        when (normalizeUserLibraryKind(kind)) {
            "cards" -> listOf("cards", "card")
            "files" -> listOf("files", "file")
            else -> listOf(kind.trim())
        }

    private fun loadDeletedLibraryKeys(ctx: Context, kind: String): Set<String> {
        val normalizedKind = normalizeUserLibraryKind(kind)
        val raw = ctx.applicationContext
            .getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
            .getString(TOMBSTONE_PREFIX + normalizedKind, "[]") ?: "[]"
        val arr = parseJsonArrayOrNull(raw) ?: kotlinx.serialization.json.JsonArray(emptyList())
        return List(arr.size) { index -> arr.str(index).trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun saveDeletedLibraryKeys(ctx: Context, kind: String, keys: Set<String>) {
        val arr = buildJsonArray { keys.take(300).forEach { add(it) } }
        ctx.applicationContext
            .getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
            .edit()
            .putString(TOMBSTONE_PREFIX + normalizeUserLibraryKind(kind), arr.toString())
            .apply()
    }

    private fun localLibraryItemId(kind: String, title: String, data: JsonObject, now: Long): String {
        val seed = listOf(
            kind,
            title,
            data.str("workspaceId"),
            data.str("localPath"),
            data.objOrNull("card")?.toString().orEmpty()
        ).joinToString("|")
        return "local_${seed.hashCode().toUInt().toString(16)}_$now"
    }
}
