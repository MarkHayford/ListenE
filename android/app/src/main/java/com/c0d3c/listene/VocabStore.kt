package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class VocabWord(
    val id: String,
    val word: String,
    val phonetic: String,
    val meaning: String,
    val example: String,
    val box: Int,
    val dueAt: Long,
    val timesReviewed: Int,
    val due: Boolean
)

data class VocabLookup(val word: String, val phonetic: String, val meaning: String, val example: String)
data class VocabList(val items: List<VocabWord>, val dueCount: Int, val total: Int)

// 生词本（云端、按用户）：AI 查词 + 间隔复习。
object VocabStore {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun lookup(ctx: Context, word: String): VocabLookup? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val term = word.trim()
        if (term.isBlank()) return@withContext null
        runCatching {
            val payload = buildJsonObject { put("word", term) }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/vocab/lookup")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("lookup failed: ${resp.code}")
                val o = parseJsonObjectOrNull(body) ?: error("lookup parse failed")
                VocabLookup(
                    word = o.str("word", term),
                    phonetic = o.str("phonetic"),
                    meaning = o.str("meaning"),
                    example = o.str("example")
                )
            }
        }.getOrNull()
    }

    suspend fun add(ctx: Context, lookup: VocabLookup): Boolean = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        if (lookup.word.isBlank()) return@withContext false
        runCatching {
            val payload = buildJsonObject {
                put("word", lookup.word)
                put("phonetic", lookup.phonetic)
                put("meaning", lookup.meaning)
                put("example", lookup.example)
            }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/vocab")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp -> resp.isSuccessful }
        }.getOrElse { false }
    }

    suspend fun fetchList(ctx: Context): VocabList = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/vocab").get(),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("vocab list failed: ${resp.code}")
                val o = parseJsonObjectOrNull(body) ?: error("vocab list parse failed")
                val arr = o.arrOrNull("items") ?: JsonArray(emptyList())
                val items = List(arr.size) { parseWord(arr.objOrNull(it)) }
                    .filter { it.id.isNotBlank() }
                VocabList(items, o.int("dueCount"), o.int("total"))
            }
        }.getOrElse { VocabList(emptyList(), 0, 0) }
    }

    suspend fun grade(ctx: Context, id: String, remembered: Boolean): Boolean = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val cleanId = id.trim()
        if (cleanId.isBlank()) return@withContext false
        runCatching {
            val payload = buildJsonObject { put("remembered", remembered) }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/vocab/$cleanId/grade")
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
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/vocab/$cleanId").delete(),
                app
            ).build()
            http.newCall(req).execute().use { resp -> resp.isSuccessful }
        }.getOrElse { false }
    }

    private fun parseWord(o: JsonObject?): VocabWord = VocabWord(
        id = o?.str("id") ?: "",
        word = o?.str("word") ?: "",
        phonetic = o?.str("phonetic") ?: "",
        meaning = o?.str("meaning") ?: "",
        example = o?.str("example") ?: "",
        box = o?.int("box") ?: 0,
        dueAt = o?.long("dueAt") ?: 0L,
        timesReviewed = o?.int("timesReviewed") ?: 0,
        due = o?.bool("due") ?: false
    )
}
