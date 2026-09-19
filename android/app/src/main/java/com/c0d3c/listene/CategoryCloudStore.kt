package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// 相册式分类的云端同步（P4）：登录用户把本地分类+归属同步到云端，跨设备一致。
// 策略：启动拉取——云端有数据则覆盖本地；云端为空则用本地播种(保护老用户升级不丢本地分类)。改动防抖后整块上传(last-write-wins)。
object CategoryCloudStore {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun loggedIn(ctx: Context) = AuthStore.currentUserId(ctx) != "anon"

    suspend fun pull(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        if (!loggedIn(app)) return@withContext false
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/categories").get(),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("pull failed: ${resp.code}")
                val o = parseJsonObjectOrNull(resp.body.string()) ?: error("pull parse failed")
                val data = o.objOrNull("data") ?: return@use false
                if (categoryDataHasContent(data)) {
                    LibraryCategoryStore.replaceAllFromJson(app, data.toString())
                    true
                } else {
                    if (LibraryCategoryStore.hasAnyData(app)) pushInternal(app)
                    false
                }
            }
        }.getOrDefault(false)
    }

    suspend fun push(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        if (!loggedIn(app)) return@withContext false
        pushInternal(app)
    }

    private fun pushInternal(app: Context): Boolean = runCatching {
        val dataObj = parseJsonObjectOrNull(LibraryCategoryStore.serializeAll(app)) ?: JsonObject(emptyMap())
        val payload = buildJsonObject { put("data", dataObj) }
        val req = AuthStore.applyAuth(
            Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/categories")
                .put(payload.toString().toRequestBody("application/json".toMediaType())),
            app
        ).build()
        http.newCall(req).execute().use { resp -> resp.isSuccessful }
    }.getOrDefault(false)
}

// 判断云端下发的分类数据是否非空（任一域有分类或归属）。
internal fun categoryDataHasContent(data: JsonObject): Boolean {
    for (dom in listOf(LibraryCategoryStore.DOMAIN_WORKSPACE, LibraryCategoryStore.DOMAIN_CARDS, LibraryCategoryStore.DOMAIN_FILES)) {
        val d = data.objOrNull(dom) ?: continue
        val cats = d.arrOrNull("categories")
        if (cats != null && cats.size > 0) return true
        val assign = d.objOrNull("assignments")
        if (assign != null && assign.isNotEmpty()) return true
    }
    return false
}
