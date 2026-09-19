package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// 学习计划（云端、按用户）。内容（title/detail）只由 AI 写入；用户只能改时间/重复或删除。
data class StudyPlan(
    val id: String,
    val title: String,
    val detail: String,
    val scheduledAt: Long,
    val recurrence: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
)

// AI 生成、待写入云端的计划项（scheduledAt 已由客户端按本地时区换算成绝对时间）。
data class StudyPlanDraft(
    val title: String,
    val detail: String,
    val scheduledAt: Long,
    val recurrence: String
)

object PlanStore {
    private const val PREFS_NAME = "study_plan_store"
    private const val KEY_PLANS = "plans_v1"
    val RECURRENCES = listOf("none", "daily", "weekly")

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun loadPlans(ctx: Context): List<StudyPlan> {
        val raw = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE).getString(KEY_PLANS, "[]") ?: "[]"
        val arr = parseJsonArrayOrNull(raw) ?: JsonArray(emptyList())
        return sortForDisplay(
            List(arr.size) { i -> parsePlan(arr.objOrNull(i)) }.filter { it.id.isNotBlank() }
        )
    }

    fun savePlans(ctx: Context, plans: List<StudyPlan>) {
        val arr = buildJsonArray {
            sortForDisplay(dedupe(plans)).take(500).forEach { add(planToJson(it)) }
        }
        ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE).edit().putString(KEY_PLANS, arr.toString()).apply()
    }

    fun sortForDisplay(plans: List<StudyPlan>): List<StudyPlan> =
        plans.sortedBy { if (it.scheduledAt > 0L) it.scheduledAt else Long.MAX_VALUE }

    private fun dedupe(plans: List<StudyPlan>): List<StudyPlan> =
        plans.associateBy { it.id }.values.toList()

    suspend fun refreshRemote(ctx: Context): List<StudyPlan> = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/plans").get(),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "计划同步失败"))
                val arr = parseJsonObjectBody(body, "计划响应不是 JSON").arrOrNull("plans") ?: JsonArray(emptyList())
                val remote = List(arr.size) { parsePlan(arr.objOrNull(it)) }
                    .filter { it.id.isNotBlank() }
                savePlans(app, remote)
                remote
            }
        }.getOrElse { loadPlans(app) }
    }

    suspend fun createPlans(ctx: Context, drafts: List<StudyPlanDraft>): List<StudyPlan> = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val valid = drafts.filter { it.title.isNotBlank() }
        if (valid.isEmpty()) return@withContext emptyList()
        runCatching {
            val items = buildJsonArray {
                valid.forEach { d ->
                    add(buildJsonObject {
                        put("title", d.title)
                        put("detail", d.detail)
                        put("scheduledAt", d.scheduledAt)
                        put("recurrence", normalizeRecurrence(d.recurrence))
                    })
                }
            }
            val payload = buildJsonObject { put("items", items) }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/plans")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "创建计划失败"))
                val arr = parseJsonObjectBody(body, "创建计划响应不是 JSON").arrOrNull("plans") ?: JsonArray(emptyList())
                val created = List(arr.size) { parsePlan(arr.objOrNull(it)) }
                    .filter { it.id.isNotBlank() }
                savePlans(app, created + loadPlans(app))
                created
            }
        }.getOrElse { emptyList() }
    }

    suspend fun updatePlanSchedule(ctx: Context, id: String, scheduledAt: Long, recurrence: String): StudyPlan? =
        withContext(Dispatchers.IO) {
            val app = ctx.applicationContext
            val cleanId = id.trim()
            if (cleanId.isBlank()) return@withContext null
            runCatching {
                val payload = buildJsonObject {
                    put("scheduledAt", scheduledAt)
                    put("recurrence", normalizeRecurrence(recurrence))
                }
                val req = AuthStore.applyAuth(
                    Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/plans/$cleanId")
                        .patch(payload.toString().toRequestBody("application/json".toMediaType())),
                    app
                ).build()
                http.newCall(req).execute().use { resp ->
                    val body = resp.body.string()
                    if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "更新计划失败"))
                    val plan = parsePlan(parseJsonObjectBody(body, "更新计划响应不是 JSON").objOrNull("plan"))
                    if (plan.id.isNotBlank()) {
                        savePlans(app, listOf(plan) + loadPlans(app).filterNot { it.id == cleanId })
                        plan
                    } else null
                }
            }.getOrNull()
        }

    suspend fun deletePlan(ctx: Context, id: String): Boolean = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val cleanId = id.trim()
        if (cleanId.isBlank()) return@withContext true
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/plans/$cleanId").delete(),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error(httpErrorMessage(resp.code, body, "删除计划失败"))
            }
            savePlans(app, loadPlans(app).filterNot { it.id == cleanId })
            true
        }.getOrElse { false }
    }

    fun normalizeRecurrence(value: String): String =
        value.trim().lowercase().takeIf { it in RECURRENCES } ?: "none"

    private fun parsePlan(o: JsonObject?): StudyPlan {
        val scheduledAt = o?.let { it.long("scheduledAt", it.long("scheduled_at", 0L)) } ?: 0L
        return StudyPlan(
            id = o?.str("id") ?: "",
            title = o?.str("title") ?: "",
            detail = o?.str("detail") ?: "",
            scheduledAt = scheduledAt,
            recurrence = normalizeRecurrence(o?.str("recurrence", "none") ?: "none"),
            createdAt = o?.long("createdAt", 0L) ?: 0L,
            updatedAt = o?.long("updatedAt", 0L) ?: 0L
        )
    }

    private fun planToJson(p: StudyPlan): JsonObject = buildJsonObject {
        put("id", p.id)
        put("title", p.title)
        put("detail", p.detail)
        put("scheduledAt", p.scheduledAt)
        put("recurrence", p.recurrence)
        put("createdAt", p.createdAt)
        put("updatedAt", p.updatedAt)
    }

    private fun parseJsonObjectBody(body: String, fallback: String): JsonObject =
        parseJsonObjectOrNull(body) ?: error("$fallback：${body.take(160)}")

    private fun httpErrorMessage(code: Int, body: String, fallback: String): String {
        val detail = parseJsonObjectOrNull(body)?.str("detail")?.takeIf { it.isNotBlank() }
        return detail ?: "$fallback (HTTP $code)"
    }
}
