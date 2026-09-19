package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

// 统一用户模型（方案 #4B，客户端侧）：把端上 LearnerModelStore 的六技能能力上报到服务端统一画像，
// 并缓存服务端合并后的模型（abilities/preferences/weaknesses/notes）。聊天请求携带该模型 →
// 服务端 buildUserModelHint 注入全局上下文，让「App 懂你」跨功能生效（讲解难度/例子/侧重弱点）。
object UserModelStore {
    private const val PREFS = "user_model_cache_v1"
    private const val KEY = "model"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // 最近一次已知的统一模型（服务端合并结果）。供 buildChatPayload 无 ctx 场景直接读取。
    @Volatile
    private var cache: JsonObject? = null

    fun cachedForChat(): JsonObject? = cache

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS), Context.MODE_PRIVATE)

    private fun loadCacheIfNeeded(ctx: Context) {
        if (cache != null) return
        prefs(ctx).getString(KEY, null)?.let { cache = parseJsonObjectOrNull(it) }
    }

    private fun store(ctx: Context, model: JsonObject) {
        cache = model
        prefs(ctx).edit().putString(KEY, model.toString()).apply()
    }

    private fun currentAbilities(ctx: Context): JsonObject = buildJsonObject {
        LearnerModelStore.SKILLS.forEach { skill -> put(skill, LearnerModelStore.ability(ctx, skill)) }
    }

    // 上报当前能力到服务端并用合并结果刷新缓存。best-effort：任何失败都吞掉、绝不影响体验。
    suspend fun push(ctx: Context) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        loadCacheIfNeeded(app)
        runCatching {
            val payload = buildJsonObject { put("abilities", currentAbilities(app)) }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/user-model")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) parseJsonObjectOrNull(resp.body.string())?.let { store(app, it) }
            }
        }
        Unit
    }

    // 上报稳定弱点到统一用户模型（如口语陪练反馈要点）。best-effort，服务端去重合并。
    suspend fun pushWeaknesses(ctx: Context, weaknesses: List<String>) = withContext(Dispatchers.IO) {
        val list = weaknesses.map { it.trim() }.filter { it.isNotBlank() }.take(6)
        if (list.isEmpty()) return@withContext
        val app = ctx.applicationContext
        loadCacheIfNeeded(app)
        runCatching {
            val payload = buildJsonObject { putJsonArray("weaknesses") { list.forEach { add(it) } } }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/user-model")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) parseJsonObjectOrNull(resp.body.string())?.let { store(app, it) }
            }
        }
        Unit
    }

    // 拉取服务端统一模型到缓存（登录后/进入聊天时调用一次即可）。
    suspend fun fetch(ctx: Context) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        loadCacheIfNeeded(app)
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/user-model").get(),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) parseJsonObjectOrNull(resp.body.string())?.let { store(app, it) }
            }
        }
        Unit
    }
}
