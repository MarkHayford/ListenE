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

data class SkillScore(val skill: String, val score: Int, val count: Int)
data class ProgressTrendDay(val date: String, val count: Int, val avgScore: Int)
data class ProgressSummary(
    val skills: List<SkillScore> = emptyList(),
    val totalSessions: Int = 0,
    val totalAnswered: Int = 0,
    val totalCorrect: Int = 0,
    val todaySessions: Int = 0,
    val streakDays: Int = 0,
    val weakestSkill: String = "",
    val trend: List<ProgressTrendDay> = emptyList()
)

// 学习进度（云端、按用户）：练习完成即上报，看板按 6 大技能聚合。
object ProgressStore {
    // 六边形固定 6 轴（与后端 SKILLS 顺序一致）。
    val SKILLS = listOf("听力", "词汇", "语法", "阅读", "写作", "口语")

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    // 上报一次练习结果。best-effort：任何失败都吞掉，绝不影响练习体验。
    suspend fun record(
        ctx: Context,
        componentType: String,
        skill: String? = null,
        total: Int = 0,
        correct: Int = 0,
        score: Int? = null
    ) = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        runCatching {
            val payload = buildJsonObject {
                put("componentType", componentType)
                put("total", total.coerceAtLeast(0))
                put("correct", correct.coerceAtLeast(0))
                if (!skill.isNullOrBlank()) put("skill", skill)
                if (score != null) put("score", score.coerceIn(0, 100))
            }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/progress")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp -> resp.body.string() }
        }
        Unit
    }

    suspend fun fetchSummary(ctx: Context): ProgressSummary = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/progress/summary").get(),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("progress summary failed: ${resp.code}")
                parseSummary(parseJsonObjectOrNull(body) ?: error("progress summary parse failed"))
            }
        }.getOrElse { ProgressSummary() }
    }

    private fun parseSummary(o: JsonObject): ProgressSummary {
        val skillsArr = o.arrOrNull("skills") ?: JsonArray(emptyList())
        val bySkill = HashMap<String, SkillScore>()
        for (i in 0 until skillsArr.size) {
            val s = skillsArr.objOrNull(i) ?: continue
            val name = s.str("skill")
            bySkill[name] = SkillScore(name, s.int("score").coerceIn(0, 100), s.int("count").coerceAtLeast(0))
        }
        // 始终按固定 6 轴顺序返回，保证雷达图稳定（缺的补 0）。
        val skills = SKILLS.map { bySkill[it] ?: SkillScore(it, 0, 0) }
        val trendArr = o.arrOrNull("trend") ?: JsonArray(emptyList())
        val trend = List(trendArr.size) { i ->
            val t = trendArr.objOrNull(i)
            ProgressTrendDay(t?.str("date") ?: "", t?.int("count") ?: 0, t?.int("avgScore") ?: 0)
        }
        return ProgressSummary(
            skills = skills,
            totalSessions = o.int("totalSessions"),
            totalAnswered = o.int("totalAnswered"),
            totalCorrect = o.int("totalCorrect"),
            todaySessions = o.int("todaySessions"),
            streakDays = o.int("streakDays"),
            weakestSkill = o.str("weakestSkill"),
            trend = trend
        )
    }
}
