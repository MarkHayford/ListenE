package com.c0d3c.listene

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// 微元卡的全部答题过程状态（每节点 index → 用户作答），收敛为单一 Saveable holder。
// 此前这些状态是 20+ 个零散 remember(mutableStateMapOf)——旋转/深色切换/进程回收后
// 用户答到一半的答案全部清空（只有 revealed 幸存）。统一序列化成一个 JSON 字符串进
// SavedState 后，配置变更与进程回收都能原样恢复；也为聊天列表未来 Lazy 化铺路
//（LazyColumn 复用槽位时靠 SaveableStateHolder 保留滚出屏的答题状态）。
internal class MicroAnswerState {
    /** choice/audio_choice/timed_challenge/odd_one_out/dialogue_complete：节点 → 所选项下标。 */
    val choiceSelection = mutableStateMapOf<Int, Int>()
    /** tokens/highlight_span：节点 → 已点选 token 下标集合。 */
    val tokenSelection = mutableStateMapOf<Int, Set<Int>>()
    /** input/dictation/spelling_bee/sentence_transform/translate/error_correction：节点 → 输入文本。 */
    val inputText = mutableStateMapOf<Int, String>()
    /** order：节点 → 已排项的稳定下标序列。 */
    val orderSelection = mutableStateMapOf<Int, List<Int>>()
    /** 连线匹配：节点 → (左项 index → 所选右项值)。 */
    val matchSelection = mutableStateMapOf<Int, Map<Int, String>>()
    /** 归类分桶：节点 → (项值 → 所选类别名)。 */
    val categorizeSelection = mutableStateMapOf<Int, Map<String, String>>()
    /** 点选填空：节点 → (空位 index → 所填词)。 */
    val clozeDragFill = mutableStateMapOf<Int, Map<Int, String>>()
    /** 句子成分：节点 → (块 index → 所选标签)。 */
    val diagramSelection = mutableStateMapOf<Int, Map<Int, String>>()
    /** 字母重组：节点 → 已拼字母的稳定下标序列（对应 microScrambleTiles 的位置）。 */
    val scrambleOrder = mutableStateMapOf<Int, List<Int>>()
    /** 判断对错：节点 → (句 index → 所判真/假)。 */
    val trueFalseSelection = mutableStateMapOf<Int, Map<Int, Boolean>>()
    /** 判断三态：节点 → (陈述 index → "true"|"false"|"not_given")。 */
    val tfngSelection = mutableStateMapOf<Int, Map<Int, String>>()
    /** 表格填空：节点 → (挖空 index → 所填词)。 */
    val fillTableFill = mutableStateMapOf<Int, Map<Int, String>>()
    /** 完形选择：节点 → (空 index → 所选项下标)。 */
    val clozeSelectChoice = mutableStateMapOf<Int, Map<Int, Int>>()
    /** 听力填空（词库点选）：节点 → (空位 index → 所填词)。 */
    val listenFillFill = mutableStateMapOf<Int, Map<Int, String>>()
    /** 词形转换：节点 → (item index → 所填派生词)。 */
    val wordFormFill = mutableStateMapOf<Int, Map<Int, String>>()
    /** 开放式填空：节点 → (空位 index → 所打字词)。 */
    val openClozeFill = mutableStateMapOf<Int, Map<Int, String>>()
    /** 听力填空·打字版：节点 → (空位 index → 所打字词)。 */
    val listenClozeFill = mutableStateMapOf<Int, Map<Int, String>>()
    /** 单词找词：节点 → 已找到的词集合。 */
    val wordSearchFound = mutableStateMapOf<Int, Set<String>>()
    /** 单词找词：节点 → 待定的首个点击格（首/尾两点连线选词）。 */
    val wordSearchAnchor = mutableStateMapOf<Int, Pair<Int, Int>>()
    /** 猜词游戏：节点 → 已猜字母集合（大写）。 */
    val hangmanGuessed = mutableStateMapOf<Int, Set<Char>>()
    /** 短文改错：节点 → (行 index → 用户当前文本)；未编辑的行取原文。 */
    val proofEdits = mutableStateMapOf<Int, Map<Int, String>>()
    /** 单词重音：节点 → 所选音节下标(0基)。 */
    val stressSelection = mutableStateMapOf<Int, Int>()
    /** 语篇排序：节点 → 已排句子的稳定下标序列。 */
    val reorderOrder = mutableStateMapOf<Int, List<Int>>()
    /** 程度排序：节点 → 已排项的稳定下标序列。 */
    val rankOrder = mutableStateMapOf<Int, List<Int>>()
    /** IELTS 段落标题匹配：节点 → (段落 index → 所选标题)。 */
    val matchHeadingsSelection = mutableStateMapOf<Int, Map<Int, String>>()
    /** IELTS 信息匹配：节点 → (信息 index → 所选段落标签)。 */
    val matchInfoSelection = mutableStateMapOf<Int, Map<Int, String>>()
    /** 听力位置标注：节点 → (地点 index → 所选位置标签)。 */
    val mapLabelSelection = mutableStateMapOf<Int, Map<Int, String>>()
    /** 句尾配对：节点 → (句子 index → 所选句尾)。 */
    val sentenceEndingSelection = mutableStateMapOf<Int, Map<Int, String>>()
    /** 长音频笔记填空：节点 → (空位 index → 所打字词)。 */
    val noteCompleteFill = mutableStateMapOf<Int, Map<Int, String>>()
    /** 摘要/流程图填空（词库点选）：节点 → (空位 index → 所填词)。 */
    val summaryFill = mutableStateMapOf<Int, Map<Int, String>>()
    /** 篇章简答：节点 → (题 index → 所打字答案)。 */
    val shortAnswerFill = mutableStateMapOf<Int, Map<Int, String>>()
}

// ---- 编码（保存）----

private fun <V> encMap(map: Map<Int, V>, enc: (V) -> JsonElement): JsonObject =
    JsonObject(map.entries.associate { (k, v) -> k.toString() to enc(v) })

private fun encInts(values: Collection<Int>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

private fun encStrings(values: Collection<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

private fun encIntKeyedString(map: Map<Int, String>): JsonObject =
    JsonObject(map.entries.associate { (k, v) -> k.toString() to JsonPrimitive(v) })

private fun encIntKeyedInt(map: Map<Int, Int>): JsonObject =
    JsonObject(map.entries.associate { (k, v) -> k.toString() to JsonPrimitive(v) })

private fun encIntKeyedBool(map: Map<Int, Boolean>): JsonObject =
    JsonObject(map.entries.associate { (k, v) -> k.toString() to JsonPrimitive(v) })

private fun encStringKeyedString(map: Map<String, String>): JsonObject =
    JsonObject(map.entries.associate { (k, v) -> k to JsonPrimitive(v) })

private fun encodeMicroAnswerState(s: MicroAnswerState): String = JsonObject(
    mapOf(
        "choice" to encMap(s.choiceSelection) { JsonPrimitive(it) },
        "tokens" to encMap(s.tokenSelection) { encInts(it) },
        "input" to encMap(s.inputText) { JsonPrimitive(it) },
        "order" to encMap(s.orderSelection) { encInts(it) },
        "match" to encMap(s.matchSelection) { encIntKeyedString(it) },
        "categorize" to encMap(s.categorizeSelection) { encStringKeyedString(it) },
        "clozeDrag" to encMap(s.clozeDragFill) { encIntKeyedString(it) },
        "diagram" to encMap(s.diagramSelection) { encIntKeyedString(it) },
        "scramble" to encMap(s.scrambleOrder) { encInts(it) },
        "trueFalse" to encMap(s.trueFalseSelection) { encIntKeyedBool(it) },
        "tfng" to encMap(s.tfngSelection) { encIntKeyedString(it) },
        "fillTable" to encMap(s.fillTableFill) { encIntKeyedString(it) },
        "clozeSelect" to encMap(s.clozeSelectChoice) { encIntKeyedInt(it) },
        "listenFill" to encMap(s.listenFillFill) { encIntKeyedString(it) },
        "wordForm" to encMap(s.wordFormFill) { encIntKeyedString(it) },
        "openCloze" to encMap(s.openClozeFill) { encIntKeyedString(it) },
        "listenCloze" to encMap(s.listenClozeFill) { encIntKeyedString(it) },
        "wordSearchFound" to encMap(s.wordSearchFound) { encStrings(it) },
        "wordSearchAnchor" to encMap(s.wordSearchAnchor) { encInts(listOf(it.first, it.second)) },
        "hangman" to encMap(s.hangmanGuessed) { encStrings(it.map { c -> c.toString() }) },
        "proof" to encMap(s.proofEdits) { encIntKeyedString(it) },
        "stress" to encMap(s.stressSelection) { JsonPrimitive(it) },
        "reorder" to encMap(s.reorderOrder) { encInts(it) },
        "rank" to encMap(s.rankOrder) { encInts(it) },
        "matchHeadings" to encMap(s.matchHeadingsSelection) { encIntKeyedString(it) },
        "matchInfo" to encMap(s.matchInfoSelection) { encIntKeyedString(it) },
        "mapLabel" to encMap(s.mapLabelSelection) { encIntKeyedString(it) },
        "sentenceEnding" to encMap(s.sentenceEndingSelection) { encIntKeyedString(it) },
        "noteComplete" to encMap(s.noteCompleteFill) { encIntKeyedString(it) },
        "summary" to encMap(s.summaryFill) { encIntKeyedString(it) },
        "shortAnswer" to encMap(s.shortAnswerFill) { encIntKeyedString(it) }
    )
).toString()

// ---- 解码（恢复）：宽松防御——旧版/异常数据解析失败时回空状态，绝不崩溃 ----

private fun decInt(e: JsonElement): Int? = (e as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

private fun decString(e: JsonElement): String? = (e as? JsonPrimitive)?.contentOrNull

private fun decBool(e: JsonElement): Boolean? = (e as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun decIntList(e: JsonElement): List<Int> = (e as? JsonArray)?.mapNotNull(::decInt) ?: emptyList()

private fun decStringList(e: JsonElement): List<String> = (e as? JsonArray)?.mapNotNull(::decString) ?: emptyList()

private fun <V> decIntKeyedMap(e: JsonElement, dec: (JsonElement) -> V?): Map<Int, V> =
    (e as? JsonObject)?.entries?.mapNotNull { (k, v) ->
        val key = k.toIntOrNull() ?: return@mapNotNull null
        dec(v)?.let { key to it }
    }?.toMap() ?: emptyMap()

private fun decStringKeyedMap(e: JsonElement): Map<String, String> =
    (e as? JsonObject)?.entries?.mapNotNull { (k, v) -> decString(v)?.let { k to it } }?.toMap() ?: emptyMap()

private fun decodeMicroAnswerState(text: String): MicroAnswerState {
    val state = MicroAnswerState()
    val root = parseJsonObjectOrNull(text) ?: return state
    fun <V> fill(key: String, target: SnapshotStateMap<Int, V>, dec: (JsonElement) -> V?) {
        val obj = root.objOrNull(key) ?: return
        obj.entries.forEach { (k, v) ->
            val nodeIndex = k.toIntOrNull() ?: return@forEach
            dec(v)?.let { target[nodeIndex] = it }
        }
    }
    fill("choice", state.choiceSelection, ::decInt)
    fill("tokens", state.tokenSelection) { decIntList(it).toSet() }
    fill("input", state.inputText, ::decString)
    fill("order", state.orderSelection) { decIntList(it) }
    fill("match", state.matchSelection) { decIntKeyedMap(it, ::decString) }
    fill("categorize", state.categorizeSelection) { decStringKeyedMap(it) }
    fill("clozeDrag", state.clozeDragFill) { decIntKeyedMap(it, ::decString) }
    fill("diagram", state.diagramSelection) { decIntKeyedMap(it, ::decString) }
    fill("scramble", state.scrambleOrder) { decIntList(it) }
    fill("trueFalse", state.trueFalseSelection) { decIntKeyedMap(it, ::decBool) }
    fill("tfng", state.tfngSelection) { decIntKeyedMap(it, ::decString) }
    fill("fillTable", state.fillTableFill) { decIntKeyedMap(it, ::decString) }
    fill("clozeSelect", state.clozeSelectChoice) { decIntKeyedMap(it, ::decInt) }
    fill("listenFill", state.listenFillFill) { decIntKeyedMap(it, ::decString) }
    fill("wordForm", state.wordFormFill) { decIntKeyedMap(it, ::decString) }
    fill("openCloze", state.openClozeFill) { decIntKeyedMap(it, ::decString) }
    fill("listenCloze", state.listenClozeFill) { decIntKeyedMap(it, ::decString) }
    fill("wordSearchFound", state.wordSearchFound) { decStringList(it).toSet() }
    fill("wordSearchAnchor", state.wordSearchAnchor) { e ->
        decIntList(e).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }
    fill("hangman", state.hangmanGuessed) { e -> decStringList(e).mapNotNull { s -> s.firstOrNull() }.toSet() }
    fill("proof", state.proofEdits) { decIntKeyedMap(it, ::decString) }
    fill("stress", state.stressSelection, ::decInt)
    fill("reorder", state.reorderOrder) { decIntList(it) }
    fill("rank", state.rankOrder) { decIntList(it) }
    fill("matchHeadings", state.matchHeadingsSelection) { decIntKeyedMap(it, ::decString) }
    fill("matchInfo", state.matchInfoSelection) { decIntKeyedMap(it, ::decString) }
    fill("mapLabel", state.mapLabelSelection) { decIntKeyedMap(it, ::decString) }
    fill("sentenceEnding", state.sentenceEndingSelection) { decIntKeyedMap(it, ::decString) }
    fill("noteComplete", state.noteCompleteFill) { decIntKeyedMap(it, ::decString) }
    fill("summary", state.summaryFill) { decIntKeyedMap(it, ::decString) }
    fill("shortAnswer", state.shortAnswerFill) { decIntKeyedMap(it, ::decString) }
    return state
}

internal val MicroAnswerStateSaver: Saver<MicroAnswerState, String> = Saver(
    save = { encodeMicroAnswerState(it) },
    restore = { decodeMicroAnswerState(it) }
)

@Composable
internal fun rememberMicroAnswerState(instanceKey: String): MicroAnswerState =
    rememberSaveable(instanceKey, saver = MicroAnswerStateSaver) { MicroAnswerState() }
