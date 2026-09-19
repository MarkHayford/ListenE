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

// AI 一键整理的返回：建议的分类名清单 + 项目id→分类名 的归属建议。
data class OrganizeProposal(val categories: List<String>, val assignments: Map<String, String>) {
    val isEmpty: Boolean get() = categories.isEmpty() || assignments.isEmpty()
}

// AI 一键整理（云端）：把当前列表项目的标题/摘要交给模型，归纳成几个分类并逐项归类；客户端预览后套用。
object OrganizeStore {
    data class Item(val id: String, val title: String, val summary: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun organize(
        ctx: Context,
        items: List<Item>,
        existingCategories: List<String>,
        maxCategories: Int = 8
    ): OrganizeProposal? = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        if (items.isEmpty()) return@withContext null
        runCatching {
            val payload = buildJsonObject {
                putJsonArray("items") {
                    items.forEach { it0 ->
                        addJsonObject {
                            put("id", it0.id)
                            put("title", it0.title)
                            put("summary", it0.summary)
                        }
                    }
                }
                putJsonArray("existingCategories") { existingCategories.forEach { add(it) } }
                put("maxCategories", maxCategories)
            }
            val req = AuthStore.applyAuth(
                Request.Builder().url(BuildConfig.API_BASE_URL.trimEnd('/') + "/agent/organize")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())),
                app
            ).build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body.string()
                if (!resp.isSuccessful) error("organize failed: ${resp.code}")
                parseOrganizeProposal(parseJsonObjectOrNull(body) ?: error("organize parse failed"))
            }
        }.getOrNull()
    }
}

// 纯函数：解析服务端 {categories, assignments}（可单测）。
internal fun parseOrganizeProposal(o: JsonObject): OrganizeProposal {
    val catArr = o.arrOrNull("categories") ?: JsonArray(emptyList())
    val categories = List(catArr.size) { catArr.str(it).trim() }.filter { it.isNotBlank() }
    val assignArr = o.arrOrNull("assignments") ?: JsonArray(emptyList())
    val map = LinkedHashMap<String, String>()
    for (i in 0 until assignArr.size) {
        val a = assignArr.objOrNull(i) ?: continue
        val id = a.str("id").trim()
        val cat = a.str("category").trim()
        if (id.isNotBlank() && cat.isNotBlank()) map[id] = cat
    }
    return OrganizeProposal(categories, map)
}

// 套用建议：按名新建/复用分类，逐项归类。返回成功归类的项目数。
internal fun applyOrganizeProposal(ctx: Context, domain: String, proposal: OrganizeProposal): Int {
    val nameToId = HashMap<String, String>()
    LibraryCategoryStore.categories(ctx, domain).forEach { nameToId[it.name.lowercase()] = it.id }
    proposal.categories.forEach { name ->
        if (!nameToId.containsKey(name.lowercase())) {
            val created = LibraryCategoryStore.createCategory(ctx, domain, name)
            if (created != null) nameToId[name.lowercase()] = created.id
        }
    }
    var moved = 0
    proposal.assignments.forEach { (itemId, catName) ->
        val id = nameToId[catName.lowercase()]
        if (id != null) {
            LibraryCategoryStore.setCategory(ctx, domain, itemId, id)
            moved++
        }
    }
    return moved
}
