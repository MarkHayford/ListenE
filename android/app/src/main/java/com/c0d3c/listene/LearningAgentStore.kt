package com.c0d3c.listene

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class LearningProfile(
    val totalAnalyses: Int = 0,
    val tagCounts: Map<String, Int> = emptyMap(),
    val recentWeakPoints: List<String> = emptyList(),
    val recentActions: List<String> = emptyList(),
    val updatedAt: Long = 0L
) {
    val topTags: List<Pair<String, Int>>
        get() = tagCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
}

object LearningAgentStore {
    private const val PREFS_NAME = "learning_agent_store"
    private const val KEY_PROFILE = "profile_v1"

    fun loadProfile(ctx: Context): LearningProfile {
        val raw = ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
            .getString(KEY_PROFILE, null)
            ?: return LearningProfile()
        val obj = parseJsonObjectOrNull(raw) ?: return LearningProfile()
        return parseProfile(obj)
    }

    fun parseProfile(obj: JsonObject): LearningProfile {
        val tagObj = obj.objOrNull("tagCounts") ?: JsonObject(emptyMap())
        val tagCounts = buildMap {
            tagObj.keys.forEach { key ->
                val count = tagObj.int(key, 0)
                if (key.isNotBlank() && count > 0) put(key, count)
            }
        }
        return LearningProfile(
            totalAnalyses = obj.int("totalAnalyses", 0),
            tagCounts = tagCounts,
            recentWeakPoints = jsonArrayToStringList(obj.arrOrNull("recentWeakPoints")),
            recentActions = jsonArrayToStringList(obj.arrOrNull("recentActions")),
            updatedAt = obj.long("updatedAt", 0L)
        )
    }

    fun recordAnalysis(ctx: Context, result: AnalysisResult) {
        val existing = loadProfile(ctx)
        val nextTags = existing.tagCounts.toMutableMap()
        val tags = (result.diagnosisTags + result.weakPoints.map { it.take(18) })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        tags.forEach { tag -> nextTags[tag] = (nextTags[tag] ?: 0) + 1 }

        val weakPoints = (result.weakPoints + existing.recentWeakPoints)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)
        val actions = (result.nextActions.map { it.title } + result.suggestions + existing.recentActions)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(10)

        saveProfile(
            ctx,
            LearningProfile(
                totalAnalyses = existing.totalAnalyses + 1,
                tagCounts = nextTags,
                recentWeakPoints = weakPoints,
                recentActions = actions,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private fun saveProfile(ctx: Context, profile: LearningProfile) {
        val obj = buildJsonObject {
            put("totalAnalyses", profile.totalAnalyses)
            putJsonObject("tagCounts") {
                profile.tagCounts.forEach { (tag, count) -> put(tag, count) }
            }
            putJsonArray("recentWeakPoints") { profile.recentWeakPoints.forEach { add(it) } }
            putJsonArray("recentActions") { profile.recentActions.forEach { add(it) } }
            put("updatedAt", profile.updatedAt)
        }
        ctx.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS_NAME), Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILE, obj.toString())
            .apply()
    }

    private fun jsonArrayToStringList(a: JsonArray?): List<String> {
        if (a == null) return emptyList()
        return List(a.size) { i -> a.str(i).trim() }.filter { it.isNotBlank() }
    }
}
