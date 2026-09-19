package com.c0d3c.listene

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// 相册式分类管理（P1，本地·按账号隔离）：为「工作区/卡片库/文件库」三个域各存一份
//   ① 自定义分类清单(有序)  ② 项目 id → 分类 id 的归属映射
// 归属存本地(不写进项目/后端)，保证零后端改动、切号隔离；跨设备同步留待后续阶段。
object LibraryCategoryStore {
    private const val PREFS = "library_categories_v1"

    // 三个可分类的域。
    const val DOMAIN_WORKSPACE = "workspace"
    const val DOMAIN_CARDS = "cards"
    const val DOMAIN_FILES = "files"

    // 特殊筛选值（非真实分类 id）。
    const val ALL = "__all__"
    const val UNCATEGORIZED = "__uncat__"

    data class Category(val id: String, val name: String)

    // 任一分类/归属写入后发信号，供跨组件(如工作区页与其操作菜单在不同 composable)刷新。
    val changes: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)
    private fun signal() { changes.tryEmit(Unit) }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS), Context.MODE_PRIVATE)

    private fun catsKey(domain: String) = "cats_$domain"
    private fun assignKey(domain: String) = "assign_$domain"

    // —— 分类清单 ——
    fun categories(ctx: Context, domain: String): List<Category> {
        val raw = prefs(ctx).getString(catsKey(domain), null) ?: return emptyList()
        val arr = parseJsonArrayOrNull(raw) ?: JsonArray(emptyList())
        return List(arr.size) { i ->
            val o = arr.objOrNull(i)
            Category(id = o?.str("id") ?: "", name = o?.str("name") ?: "")
        }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    }

    private fun saveCategories(ctx: Context, domain: String, list: List<Category>) {
        val json = buildJsonArray {
            list.forEach { c -> addJsonObject { put("id", c.id); put("name", c.name) } }
        }.toString()
        prefs(ctx).edit().putString(catsKey(domain), json).apply()
        signal()
    }

    fun createCategory(ctx: Context, domain: String, name: String): Category? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        val list = categories(ctx, domain)
        if (list.any { it.name.equals(clean, ignoreCase = true) }) return list.first { it.name.equals(clean, ignoreCase = true) }
        val cat = Category(id = "cat_" + System.currentTimeMillis().toString(36) + "_" + (clean.hashCode().toUInt().toString(16)), name = clean)
        saveCategories(ctx, domain, list + cat)
        return cat
    }

    fun renameCategory(ctx: Context, domain: String, id: String, name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        saveCategories(ctx, domain, categories(ctx, domain).map { if (it.id == id) it.copy(name = clean) else it })
    }

    // 删除分类：清单里移除，且把归到它的项目退回「未分类」。
    fun deleteCategory(ctx: Context, domain: String, id: String) {
        saveCategories(ctx, domain, categories(ctx, domain).filterNot { it.id == id })
        val next = assignments(ctx, domain).filterValues { it != id }
        saveAssignments(ctx, domain, next)
    }

    fun reorderCategories(ctx: Context, domain: String, orderedIds: List<String>) {
        val byId = categories(ctx, domain).associateBy { it.id }
        saveCategories(ctx, domain, orderedIds.mapNotNull { byId[it] })
    }

    // —— 归属映射 itemId -> categoryId ——
    fun assignments(ctx: Context, domain: String): Map<String, String> {
        val raw = prefs(ctx).getString(assignKey(domain), null) ?: return emptyMap()
        val obj = parseJsonObjectOrNull(raw) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in obj.keys) {
            val v = obj.str(k)
            if (v.isNotBlank()) out[k] = v
        }
        return out
    }

    private fun saveAssignments(ctx: Context, domain: String, map: Map<String, String>) {
        val json = buildJsonObject { map.forEach { (k, v) -> put(k, v) } }.toString()
        prefs(ctx).edit().putString(assignKey(domain), json).apply()
        signal()
    }

    fun categoryOf(ctx: Context, domain: String, itemId: String): String? =
        assignments(ctx, domain)[itemId]

    // categoryId 为 null / 空 / UNCATEGORIZED → 退回未分类（移除归属）。
    fun setCategory(ctx: Context, domain: String, itemId: String, categoryId: String?) {
        if (itemId.isBlank()) return
        val map = assignments(ctx, domain).toMutableMap()
        if (categoryId.isNullOrBlank() || categoryId == UNCATEGORIZED) map.remove(itemId) else map[itemId] = categoryId
        saveAssignments(ctx, domain, map)
    }

    // 某分类下的项目数（用于 chips 计数）。
    fun countIn(ctx: Context, domain: String, categoryId: String): Int {
        val assign = assignments(ctx, domain)
        return assign.values.count { it == categoryId }
    }

    private val ALL_DOMAINS = listOf(DOMAIN_WORKSPACE, DOMAIN_CARDS, DOMAIN_FILES)

    // 本地是否有任何分类/归属（用于云端为空时判断是否用本地播种）。
    fun hasAnyData(ctx: Context): Boolean =
        ALL_DOMAINS.any { categories(ctx, it).isNotEmpty() || assignments(ctx, it).isNotEmpty() }

    // 序列化当前账号全部域的分类+归属，供上传云端（格式与后端一致：{domain:{categories,assignments}}）。
    fun serializeAll(ctx: Context): String = buildJsonObject {
        ALL_DOMAINS.forEach { dom ->
            putJsonObject(dom) {
                putJsonArray("categories") {
                    categories(ctx, dom).forEach { c -> addJsonObject { put("id", c.id); put("name", c.name) } }
                }
                putJsonObject("assignments") {
                    assignments(ctx, dom).forEach { (k, v) -> put(k, v) }
                }
            }
        }
    }.toString()

    // 用云端下发的整块数据覆盖本地（各域分类清单+归属）。
    fun replaceAllFromJson(ctx: Context, json: String) {
        val root = parseJsonObjectOrNull(json) ?: return
        ALL_DOMAINS.forEach { dom ->
            val d = root.objOrNull(dom) ?: return@forEach
            val catsArr = d.arrOrNull("categories") ?: JsonArray(emptyList())
            val cats = List(catsArr.size) { i ->
                val o = catsArr.objOrNull(i)
                Category(id = o?.str("id") ?: "", name = o?.str("name") ?: "")
            }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
            saveCategories(ctx, dom, cats)
            val aObj = d.objOrNull("assignments")
            val map = LinkedHashMap<String, String>()
            if (aObj != null) for (k in aObj.keys) {
                val v = aObj.str(k)
                if (v.isNotBlank()) map[k] = v
            }
            saveAssignments(ctx, dom, map)
        }
    }
}
