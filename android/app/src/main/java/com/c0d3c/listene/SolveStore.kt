package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class SolveVocab(val word: String, val phonetic: String, val meaning: String, val example: String)

// 拍照答疑的结构化解题结果：读题回显 → 题型/技能 → 考点 → 分步解析 → 答案 → 易错点 → 生词。
data class SolveResult(
    val questionText: String,
    val questionType: String,
    val skill: String,
    val points: List<String>,
    val steps: List<String>,
    val answer: String,
    val pitfalls: List<String>,
    val vocab: List<SolveVocab>,
    val comment: String
) {
    val isEmpty: Boolean get() = questionText.isBlank() && answer.isBlank() && steps.isEmpty()
}

// 拍照答疑（云端、按用户）：上传题目图片/文档/文字，多模态模型读题并给出结构化解题。
object SolveStore {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun solve(
        ctx: Context,
        message: String,
        attachments: List<AgentInputAttachment>
    ): SolveResult? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val cleanMsg = message.trim()
        if (cleanMsg.isBlank() && attachments.isEmpty()) return@withContext null
        runCatching {
            val payload = buildJsonObject {
                put("message", cleanMsg)
                putJsonArray("attachments") {
                    attachments.forEach { a ->
                        addJsonObject {
                            put("name", a.name)
                            put("mimeType", a.mimeType)
                            put("base64", a.base64)
                            put("sizeBytes", a.sizeBytes)
                        }
                    }
                }
            }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/solve")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("solve failed: ${resp.code}")
                val o = parseJsonObjectOrNull(body) ?: error("solve parse failed")
                parseSolveResult(o)
            }
        }.getOrNull()
    }
}

// 纯函数：把服务端返回的 JSON 解析成 SolveResult（可单测）。
internal fun parseSolveResult(o: JsonObject): SolveResult {
    fun strArr(key: String): List<String> {
        val arr = o.arrOrNull(key) ?: JsonArray(emptyList())
        return List(arr.size) { arr.str(it).trim() }.filter { it.isNotBlank() }
    }
    val vocabArr = o.arrOrNull("vocab") ?: JsonArray(emptyList())
    val vocab = List(vocabArr.size) { i ->
        val v = vocabArr.objOrNull(i)
        SolveVocab(
            word = (v?.str("word") ?: "").trim(),
            phonetic = (v?.str("phonetic") ?: "").trim(),
            meaning = (v?.str("meaning") ?: "").trim(),
            example = (v?.str("example") ?: "").trim()
        )
    }.filter { it.word.isNotBlank() }
    return SolveResult(
        questionText = o.str("questionText").trim(),
        questionType = o.str("questionType").trim(),
        skill = o.str("skill").trim(),
        points = strArr("points"),
        steps = strArr("steps"),
        answer = o.str("answer").trim(),
        pitfalls = strArr("pitfalls"),
        vocab = vocab,
        comment = o.str("comment").trim()
    )
}

// 纯函数：把 SolveResult 序列化为 JSON——存入聊天消息(solveJson)，随会话持久化并可回渲染。
internal fun solveResultToJson(r: SolveResult): JsonObject = buildJsonObject {
    put("questionText", r.questionText)
    put("questionType", r.questionType)
    put("skill", r.skill)
    putJsonArray("points") { r.points.forEach { add(it) } }
    putJsonArray("steps") { r.steps.forEach { add(it) } }
    put("answer", r.answer)
    putJsonArray("pitfalls") { r.pitfalls.forEach { add(it) } }
    putJsonArray("vocab") {
        r.vocab.forEach { v ->
            addJsonObject {
                put("word", v.word); put("phonetic", v.phonetic); put("meaning", v.meaning); put("example", v.example)
            }
        }
    }
    put("comment", r.comment)
}
