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

// 每日挑战题型：选择 / 选词填空 / 连词成句 / 句子排序 / 短答 / 翻译。
object DailyQuestionType {
    const val MCQ = "mcq"
    const val CLOZE = "cloze"
    const val SENTENCE_BUILDER = "sentence_builder"
    const val ORDERING = "ordering"
    const val SHORT_ANSWER = "short_answer"
    const val TRANSLATION = "translation"
}

data class DailyQuestion(
    val type: String = DailyQuestionType.MCQ,
    val questionText: String,
    val options: List<String> = emptyList(),
    val tokens: List<String> = emptyList(),
    val items: List<String> = emptyList(),
    val skill: String = "",
    // 选择/选词：正确选项下标（结果阶段才有）。
    val correctAnswer: Int = -1,
    // 连词成句/短答/翻译：正确答案文本（结果阶段才有）。
    val answer: String = "",
    // 句子排序：正确顺序（结果阶段才有）。
    val answerList: List<String> = emptyList(),
    val explanation: String = "",
    // 结果阶段：用户作答。
    val your: Int = -1,
    val yourOrder: List<String> = emptyList(),
    val yourText: String = "",
    val correct: Boolean = false
)

// 单题作答草稿：按题型只用其中一个字段（选项下标 / 排列顺序 / 文本）。
data class DailyAnswerDraft(
    val choice: Int? = null,
    val order: List<String> = emptyList(),
    val text: String = ""
)

fun DailyQuestion.isPickOne(): Boolean =
    type == DailyQuestionType.MCQ || type == DailyQuestionType.CLOZE

fun DailyQuestion.isArrange(): Boolean =
    type == DailyQuestionType.SENTENCE_BUILDER || type == DailyQuestionType.ORDERING

fun DailyQuestion.isTyped(): Boolean =
    type == DailyQuestionType.SHORT_ANSWER || type == DailyQuestionType.TRANSLATION

// 该题作答用的可排列块（连词成句用 tokens、句子排序用 items）。
fun DailyQuestion.arrangePieces(): List<String> =
    if (type == DailyQuestionType.ORDERING) items else tokens

// 判断一个草稿是否算"已作答"（用于提交按钮可用性）。
fun DailyQuestion.isAnswered(draft: DailyAnswerDraft?): Boolean {
    if (draft == null) return false
    return when {
        isPickOne() -> draft.choice != null
        isArrange() -> draft.order.size >= arrangePieces().size && arrangePieces().isNotEmpty()
        isTyped() -> draft.text.isNotBlank()
        else -> draft.choice != null
    }
}

data class DailyChallenge(
    val day: Long,
    val completed: Boolean,
    val score: Int,
    val total: Int,
    val questions: List<DailyQuestion>,
    val loaded: Boolean = true
)

data class DailyResult(
    val score: Int,
    val correct: Int,
    val total: Int,
    val alreadyCompleted: Boolean,
    val results: List<DailyQuestion>
)

// 每日挑战（云端、按自然日）。
object DailyStore {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun getToday(ctx: Context): DailyChallenge? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        runCatching {
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/daily").get(),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("daily failed: ${resp.code}")
                val o = parseJsonObjectOrNull(body) ?: error("daily parse failed")
                DailyChallenge(
                    day = o.long("day"),
                    completed = o.bool("completed"),
                    score = o.int("score"),
                    total = o.int("total"),
                    questions = parseQuestions(o.arrOrNull("questions"))
                )
            }
        }.getOrNull()
    }

    suspend fun submit(ctx: Context, answers: List<DailyAnswerDraft>): DailyResult? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        runCatching {
            val payload = buildJsonObject {
                putJsonArray("answers") {
                    answers.forEach { draft ->
                        addJsonObject {
                            if (draft.choice != null) put("choice", draft.choice)
                            if (draft.order.isNotEmpty()) putJsonArray("order") { draft.order.forEach { add(it) } }
                            if (draft.text.isNotBlank()) put("text", draft.text)
                        }
                    }
                }
            }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/daily/submit")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("daily submit failed: ${resp.code}")
                val o = parseJsonObjectOrNull(body) ?: error("daily submit parse failed")
                DailyResult(
                    score = o.int("score"),
                    correct = o.int("correct"),
                    total = o.int("total"),
                    alreadyCompleted = o.bool("alreadyCompleted"),
                    results = parseQuestions(o.arrOrNull("results"))
                )
            }
        }.getOrNull()
    }

    private fun JsonArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return List(size) { str(it).trim() }.filter { it.isNotBlank() }
    }

    private fun parseQuestions(arr: JsonArray?): List<DailyQuestion> {
        if (arr == null) return emptyList()
        return List(arr.size) { i ->
            val q = arr.objOrNull(i) ?: JsonObject(emptyMap())
            val type = q.str("type", DailyQuestionType.MCQ).ifBlank { DailyQuestionType.MCQ }
            val answerArr = q.arrOrNull("answer")
            DailyQuestion(
                type = type,
                questionText = q.str("questionText").trim(),
                options = q.arrOrNull("options").toStringList(),
                tokens = q.arrOrNull("tokens").toStringList(),
                items = q.arrOrNull("items").toStringList(),
                skill = q.str("skill"),
                correctAnswer = if (q.containsKey("correctAnswer")) q.int("correctAnswer", -1) else -1,
                answer = if (answerArr != null) "" else q.str("answer").trim(),
                answerList = answerArr.toStringList(),
                explanation = q.str("explanation").trim(),
                your = if (q.containsKey("your")) q.int("your", -1) else -1,
                yourOrder = q.arrOrNull("yourOrder").toStringList(),
                yourText = q.str("yourText").trim(),
                correct = q.bool("correct")
            )
        }.filter { it.questionText.isNotBlank() }
    }
}
