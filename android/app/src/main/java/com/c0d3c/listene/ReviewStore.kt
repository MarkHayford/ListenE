package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ReviewItem(
    val id: String,
    val kind: String,
    val componentType: String,
    val skill: String,
    val prompt: String,
    val options: List<String>,
    val answer: String,
    val explanation: String,
    val box: Int,
    val dueAt: Long,
    val timesWrong: Int,
    val timesReviewed: Int,
    val due: Boolean
)

data class ReviewList(val items: List<ReviewItem>, val dueCount: Int, val total: Int)

// 错题本 + 间隔复习（云端、按用户）。答错自动入库；复习时上报「记得/忘了」。
object ReviewStore {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // 答错自动收集。best-effort：失败静默，绝不影响练习。
    suspend fun addWrong(
        ctx: Context,
        componentType: String,
        skill: String?,
        kind: String,
        prompt: String,
        options: List<String>,
        answer: String,
        explanation: String
    ) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        if (prompt.isBlank() || answer.isBlank()) return@withContext
        runCatching {
            val payload = buildJsonObject {
                put("componentType", componentType)
                put("kind", kind)
                put("prompt", prompt)
                putJsonArray("options") { options.forEach { add(it) } }
                put("answer", answer)
                put("explanation", explanation)
                if (!skill.isNullOrBlank()) put("skill", skill)
            }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/review")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp -> resp.body.string() }
        }
        Unit
    }

    suspend fun fetchList(ctx: Context): ReviewList = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/review").get(),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("review list failed: ${resp.code}")
                val o = parseJsonObjectOrNull(body) ?: error("review list parse failed")
                val arr = o.arrOrNull("items") ?: JsonArray(emptyList())
                val items = List(arr.size) { parseItem(arr.objOrNull(it)) }
                    .filter { it.id.isNotBlank() }
                ReviewList(items, o.int("dueCount"), o.int("total"))
            }
        }.getOrElse { ReviewList(emptyList(), 0, 0) }
    }

    suspend fun grade(ctx: Context, id: String, remembered: Boolean): Boolean = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val cleanId = id.trim()
        if (cleanId.isBlank()) return@withContext false
        runCatching {
            val payload = buildJsonObject { put("remembered", remembered) }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/review/$cleanId/grade")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp -> resp.isSuccessful }
        }.getOrElse { false }
    }

    suspend fun delete(ctx: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val cleanId = id.trim()
        if (cleanId.isBlank()) return@withContext false
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/review/$cleanId").delete(),
                app
            ).build()
            http.newCall(req).execute().use { resp -> resp.isSuccessful }
        }.getOrElse { false }
    }

    private fun parseItem(o: JsonObject?): ReviewItem {
        val optArr = o?.arrOrNull("options") ?: JsonArray(emptyList())
        return ReviewItem(
            id = o?.str("id") ?: "",
            kind = o?.str("kind", "mcq") ?: "mcq",
            componentType = o?.str("componentType") ?: "",
            skill = o?.str("skill") ?: "",
            prompt = o?.str("prompt") ?: "",
            options = List(optArr.size) { optArr.str(it) }.filter { it.isNotBlank() },
            answer = o?.str("answer") ?: "",
            explanation = o?.str("explanation") ?: "",
            box = o?.int("box") ?: 0,
            dueAt = o?.long("dueAt") ?: 0L,
            timesWrong = o?.int("timesWrong") ?: 0,
            timesReviewed = o?.int("timesReviewed") ?: 0,
            due = o?.bool("due") ?: false
        )
    }
}
