package com.c0d3c.listene

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

internal data class MicroCardResult(
    val ok: Boolean,
    val card: MicroCard?,
    val report: String,
    val error: String = "",
    // 服务端返回的原始 card JSON（用于落库/重建，渲染时由 MicroCardParser 再解析）。
    val cardJson: String = ""
)

// 微元卡（实验）取数：POST /agent/micro，让后端 AI 实时把微元拼成卡，返回 {ok,card,report}。
// 与现有 AgentConversationService 同样的 OkHttp 范式；与旧题型管线隔离。
internal object MicroCardStore {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(200, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun generate(message: String, level: String? = null): MicroCardResult = withContext(Dispatchers.IO) {
        val trimmed = message.trim()
        if (trimmed.isEmpty()) return@withContext MicroCardResult(false, null, "", "请输入需求")
        // level：自适应出题难度（CEFR 段位，来自 LearnerModelStore），后端据此把控难度。
        val payload = buildJsonObject {
            put("message", trimmed)
            if (!level.isNullOrBlank()) put("level", level)
        }
        val req = Request.Builder()
            .url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/micro")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val bodyText = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    return@withContext MicroCardResult(false, null, "", "HTTP ${resp.code}")
                }
                val obj = parseJsonObjectOrNull(bodyText)
                    ?: return@withContext MicroCardResult(false, null, "", "无效响应")
                val cardObj = obj.objOrNull("card")
                val card = if (cardObj != null) MicroCardParser.parse(cardObj) else null
                MicroCardResult(
                    ok = obj.bool("ok"),
                    card = card,
                    report = obj.str("report"),
                    cardJson = cardObj?.toString().orEmpty()
                )
            }
        } catch (e: Exception) {
            MicroCardResult(false, null, "", e.message ?: "网络错误")
        }
    }
}
