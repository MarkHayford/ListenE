package com.c0d3c.listene

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * org.json → kotlinx.serialization 迁移用的薄工具层。
 *
 * 这些扩展刻意复刻 org.json 的 `opt*` 宽松语义——缺键/类型不符回默认值、字符串与数字互转、
 * JSON null 视作缺失——使各 store 的解析能近乎机械替换，且不改变线上 wire / 落盘 JSON 的行为。
 * 构造侧统一用 [buildJsonObject]/[buildJsonArray]，其 toString() 产出紧凑 JSON，键序按插入顺序，
 * 与原 org.json 输出兼容。
 */

/** 全局解析实例：宽松解析，忽略未知键，匹配 org.json 的容错读取行为。 */
internal val appJson: Json = Json {
    isLenient = true
    ignoreUnknownKeys = true
}

/** 解析任意文本为 JsonObject；非法 JSON 或非对象时回 null（对应 org.json `JSONObject(String)` 抛错被吞）。 */
internal fun parseJsonObjectOrNull(text: String): JsonObject? =
    runCatching { appJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

/** 解析任意文本为 JsonArray；非法或非数组时回 null。 */
internal fun parseJsonArrayOrNull(text: String): JsonArray? =
    runCatching { appJson.parseToJsonElement(text) as? JsonArray }.getOrNull()

// ---- 读取：对齐 org.json optString / optInt / optLong / optDouble / optBoolean ----

internal fun JsonObject.str(key: String, def: String = ""): String =
    (this[key] as? JsonPrimitive)?.contentOrNull ?: def

internal fun JsonObject.int(key: String, def: Int = 0): Int =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: def

internal fun JsonObject.long(key: String, def: Long = 0L): Long =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: def

internal fun JsonObject.double(key: String, def: Double = 0.0): Double =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull() ?: def

internal fun JsonObject.bool(key: String, def: Boolean = false): Boolean =
    (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: def

// ---- 取子结构：对齐 org.json optJSONObject / optJSONArray（类型不符回 null） ----

internal fun JsonObject.objOrNull(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arrOrNull(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonArray.objOrNull(index: Int): JsonObject? = getOrNull(index) as? JsonObject

internal fun JsonArray.arrOrNull(index: Int): JsonArray? = getOrNull(index) as? JsonArray

/** 读数组中第 index 个元素的字符串值，对齐 org.json `JSONArray.optString(int)`。 */
internal fun JsonArray.str(index: Int, def: String = ""): String =
    (getOrNull(index) as? JsonPrimitive)?.contentOrNull ?: def

// ---- 不可变更新：kotlinx JsonObject 不可变，用以下扩展返回「写入/删除某键后的新对象」，
//      替代 org.json `JSONObject.put/remove` 的就地修改（保持键的插入顺序）。 ----

internal fun JsonObject.with(key: String, value: JsonElement): JsonObject =
    JsonObject(toMutableMap().apply { this[key] = value })

internal fun JsonObject.with(key: String, value: String): JsonObject = with(key, JsonPrimitive(value))

internal fun JsonObject.with(key: String, value: Number): JsonObject = with(key, JsonPrimitive(value))

internal fun JsonObject.with(key: String, value: Boolean): JsonObject = with(key, JsonPrimitive(value))

internal fun JsonObject.without(key: String): JsonObject =
    JsonObject(toMutableMap().apply { remove(key) })
