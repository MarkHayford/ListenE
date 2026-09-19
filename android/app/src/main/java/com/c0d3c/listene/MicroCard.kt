package com.c0d3c.listene

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// 「微元卡」spike —— 与现有 43 题型管线完全隔离，验证“无固定题型 + AI 实时组合微元”的方向。
// 一张卡 = 扁平的微元(primitive)列表，AI 按需组合；端上按类型渲染，未知微元忽略（graceful）。
// 契约与后端 backends/listene/src/contract/microCardContract.js 对应。

internal sealed interface MicroNode {
    data class Text(val text: String, val role: String) : MicroNode
    data class Passage(val text: String) : MicroNode
    data class Choice(
        val prompt: String,
        val options: List<String>,
        val answer: String,
        val multi: Boolean,
        val explanation: String = ""
    ) : MicroNode
    data class Tokens(
        val text: String,
        val correct: String,
        val explanation: String,
        val errors: List<String>
    ) : MicroNode
    data class Input(val prompt: String, val answer: String, val multiline: Boolean, val explanation: String = "") : MicroNode
    data class Order(val prompt: String, val items: List<String>, val answer: String, val explanation: String) : MicroNode
    data class Audio(val src: String) : MicroNode
    data class Reveal(val label: String, val content: String) : MicroNode
    // 连线匹配：每个左项从「右项打乱而成的选项池」里选其对应右项；全部选对才算对。
    data class Match(val prompt: String, val pairs: List<MicroMatchPair>, val explanation: String = "") : MicroNode
    // 归类分桶：每个项从「类别名池」里选其所属类别；全部归对才算对。
    data class Categorize(val prompt: String, val categories: List<MicroCategory>, val explanation: String = "") : MicroNode
    // 翻卡：点击翻面（正面词/问 ↔ 背面义/答），纯学习记忆、不判分。
    data class Flashcard(val prompt: String, val cards: List<MicroFlashItem>) : MicroNode
    // 听写：端上 TTS 朗读 text，用户打字听写，diff 判分（text 即答案）。
    data class Dictation(val text: String, val hint: String, val explanation: String = "") : MicroNode
    // 听辨：端上 TTS 朗读 audioText，用户从 options 选所听到的项。
    data class AudioChoice(val audioText: String, val prompt: String, val options: List<String>, val answer: String, val explanation: String = "") : MicroNode
    // 跟读评分：用户录音朗读 text/prompts，AI 评发音/流利度（自带评分流程，不走统一判分）。
    data class SpeakScore(val text: String, val prompts: List<String>) : MicroNode
    // 点选填空：text 含 ___ 空位，从 bank 词库点词依次填入；每空对应 answers[i]。
    data class ClozeDrag(val text: String, val bank: List<String>, val answers: List<String>, val explanation: String = "") : MicroNode
    // 对话轮：卡内多轮情景对话，用户逐句作答 → AI 即时回应 + 中文恰当性提示；末尾可评分。
    // 自带对话/评分流程（复用 roleplay 后端 /agent/roleplay/turn|feedback），AI 原生交互、不走统一判分。
    data class RoleplayTurn(
        val scenario: String,
        val opening: String,
        val goal: String,
        val turns: Int
    ) : MicroNode
    // 渐进提示：prompt 为问题，hints 由浅到深逐条揭示，answer 最后揭示。脚手架引导、非统一判分。
    data class AiHint(
        val prompt: String,
        val hints: List<String>,
        val answer: String
    ) : MicroNode
    // 篇章框选：在 text 里点选所有符合 prompt 条件的词；答对 = 所选 token 位置集恰好等于 answers 目标集。可判分。
    data class HighlightSpan(
        val prompt: String,
        val text: String,
        val answers: List<String>,
        val explanation: String
    ) : MicroNode
    // 限时抢答：带倒计时的选择题；点选项即作答，又快又对得分越高，超时判未答。自带计时/计分，不走统一判分。
    data class TimedChallenge(
        val prompt: String,
        val options: List<String>,
        val answer: String,
        val seconds: Int
    ) : MicroNode
    // 表格：headers 列名 + rows 每行单元格数组；对比表/词形变化表/语法表等结构化展示，纯展示不判分。
    data class Table(
        val title: String,
        val headers: List<String>,
        val rows: List<List<String>>
    ) : MicroNode
    // 句子成分：把句子切成若干块(items)，给每块从 labels 池选其语法成分标签；全对才算对。可判分。
    data class SentenceDiagram(
        val prompt: String,
        val sentence: String,
        val labels: List<String>,
        val items: List<MicroDiagramItem>,
        val explanation: String = ""
    ) : MicroNode
    // 字母重组：把打乱的字母/块(tiles)按序拼回 word；scrambled 缺省时端上按 word 字母自动打乱。拼对才算对，可判分。
    data class WordScramble(
        val word: String,
        val hint: String,
        val scrambled: List<String>,
        val explanation: String = ""
    ) : MicroNode
    // 判断对错：逐句判断真/假；每句选 对/错，全部判对才算对。可判分。
    data class TrueFalse(val prompt: String, val statements: List<MicroTrueFalseItem>, val explanation: String = "") : MicroNode
    // 判断三态：逐句判断 对/错/未提及（true=材料支持, false=矛盾, not_given=未提及），全部判对才算对。可判分。
    data class Tfng(val prompt: String, val statements: List<MicroTfngItem>, val explanation: String = "") : MicroNode
    // 表格填空：rows 为完整正确表；blanks 指出要挖空的单元格坐标(0基)，用户从 bank 点词填回。全填对才算对。可判分。
    data class FillTable(
        val title: String,
        val headers: List<String>,
        val rows: List<List<String>>,
        val blanks: List<MicroCellCoord>,
        val bank: List<String>,
        val explanation: String = ""
    ) : MicroNode
    // 记忆翻牌配对：每个 pair 生成一对牌（左/右），盖牌打乱，翻两张找配对、成功锁定、失败盖回。自带步数/进度，纯游戏不判分。
    data class PairsMemory(val prompt: String, val pairs: List<MicroMatchPair>) : MicroNode
    // 完形选择：text 含 ___ 空位，每个空各有独立 options；每空选对才算对，全对才判对。可判分。
    data class ClozeSelect(val text: String, val blanks: List<MicroClozeSelectBlank>, val explanation: String = "") : MicroNode
    // 听力填空：端上 TTS 朗读 audioText；text 含 ___ 空位，从 bank 点词填缺词，判分对照 answers。可判分。
    data class ListenFill(
        val audioText: String,
        val text: String,
        val bank: List<String>,
        val answers: List<String>,
        val explanation: String = ""
    ) : MicroNode
    // 词形转换：每个 item 给原词 base + 目标词性 target，从 bank 点填其派生词 answer；全部填对才算对。可判分。
    data class WordFormation(
        val prompt: String,
        val items: List<MicroWordFormItem>,
        val bank: List<String>,
        val explanation: String = ""
    ) : MicroNode
    // 时间线：按时间/阶段竖向展示一串事件（time/title/detail）。纯展示、不判分。
    data class Timeline(val title: String, val events: List<MicroTimelineEvent>) : MicroNode
    // 单词重音：把 word 拆成 syllables 音节，用户点出重读音节；stress 为重读音节序号(1基)。选对才算对，可判分。
    data class StressMark(val word: String, val syllables: List<String>, val stress: Int, val explanation: String = "") : MicroNode
    // 语篇排序：sentences 为「正确顺序」的整句列表，端上打乱展示；用户按序点选还原成连贯段落/对话。全序对才算对，可判分。
    data class ReorderParagraph(val prompt: String, val sentences: List<String>, val explanation: String = "") : MicroNode
    // 选异类：items 为一组词/项（多为同类，混入一个异类 answer），用户点出不属于同类的那个。选对才算对，可判分。
    data class OddOneOut(val prompt: String, val items: List<String>, val answer: String, val explanation: String) : MicroNode
    // 程度排序：items 为「按标尺从低到高的正确顺序」的词/项，端上打乱展示；用户按序排列，from/to 为标尺两端刻度。全序对才算对，可判分。
    data class RankOrder(val prompt: String, val items: List<String>, val from: String, val to: String, val explanation: String) : MicroNode
    // 听音拼写：端上 TTS 朗读 word（可逐字母），用户看释义 hint + 听发音把单词打字拼出；example 为语境例句（展示时自动隐去该词）。精确判分（单词级正字法）。
    data class SpellingBee(val word: String, val hint: String, val example: String, val explanation: String = "") : MicroNode
    // 句型转换：给转换要求 prompt + 原句 source，用户打字写出改写后的句子；与 answer 及 accept(可接受写法) 归一比对。可判分。
    data class SentenceTransform(val prompt: String, val source: String, val answer: String, val accept: List<String>, val hint: String, val explanation: String = "") : MicroNode
    // 开放式填空（无选项/无词库）：text 用 ___ 标每个空，用户逐空「打字」填出正确词；answers 为每空答案（可用 a/b 列多个可接受写法）。全对才算对，可判分。
    data class OpenCloze(val prompt: String, val text: String, val answers: List<String>, val explanation: String = "") : MicroNode
    // 翻译：把 source 译到目标语言（direction: zh2en 汉译英默认 / en2zh 英译中），用户打字作答；与 answer + accept 归一比对。可判分。
    data class Translate(val direction: String, val source: String, val answer: String, val accept: List<String>, val hint: String, val explanation: String = "") : MicroNode
    // 听力填空·打字版：端上 TTS 朗读 audioText，用户在 text 的 ___ 处逐空「打字」填缺词（无词库）；answers 每空答案(可 a/b)。全对才算对，可判分。
    data class ListenCloze(val audioText: String, val text: String, val answers: List<String>, val explanation: String = "") : MicroNode
    // 句子改错：给含 1 处错误的 sentence + 可选提示 prompt，用户打字写出改正后的整句；与 answer + accept 归一比对，reveal 给 explanation 错误说明。可判分。
    data class ErrorCorrection(val prompt: String, val sentence: String, val answer: String, val accept: List<String>, val explanation: String) : MicroNode
    // 补全对话：turns 为情景对话（聊天气泡展示），用户从 options 选出最合适的「回应/缺句」。选对才算对，可判分。
    data class DialogueComplete(val prompt: String, val turns: List<MicroDialogueTurn>, val options: List<String>, val answer: String, val explanation: String) : MicroNode
    // 单词找词：grid 为字母网格（每行等长、每格 1 字母），words 为要找的词；用户点首/尾字母格连线判定，找齐才算对。可判分。
    data class WordSearch(val prompt: String, val grid: List<List<String>>, val words: List<String>, val explanation: String = "") : MicroNode
    // 猜词游戏：逐个猜字母，在用完 maxWrong 次错误前拼出 word 即赢；hint 为中文释义。可判分。
    data class Hangman(val word: String, val hint: String, val maxWrong: Int, val explanation: String = "") : MicroNode
    // 短文改错：lines 为按行拆开的短文，部分行含 1 处错误；用户在每行原文上直接改正，逐行归一判分。全部行正确才算对。可判分。
    data class ProofParagraph(val prompt: String, val lines: List<MicroProofLine>, val explanation: String = "") : MicroNode
    // 进度（材料卡）：value ∈ [0,1]，label 为右侧文字（如「3 / 5」「答对 2 / 5」「60%」）。纯展示，不计分。
    data class Progress(val label: String, val value: Float) : MicroNode
    // 逐句点播（材料运行时）：audioUrl + 预构建的段落/句子分组（含每句音频时间片）。
    // 由客户端按 record 构建（含 ExoPlayer 片段播放），不参与 AI/JSON 解析。
    data class SentenceTranscript(
        val audioUrl: String,
        val groups: List<AgentParagraphSentenceGroup>
    ) : MicroNode
    // 题目控件（材料运行时）：作答/核对/AI 分析。题目与作答状态来自运行时 record，
    // 持久化与 AI 复盘经 CompositionLocal 注入的 viewModel/回调完成，故节点本身只带占位文案。
    data class QuestionPreview(val fallbackText: String) : MicroNode
    // 反馈控件（材料运行时）：按 record 答题/批改状态显示对错统计、AI 复盘结果或「交给 AI 分析」。
    // record 与 AI 复盘回调经 CompositionLocal 注入，节点只带占位文案。
    data class Feedback(val fallbackText: String) : MicroNode
    // 运行时富控件托管（口语录音+评分 / 写作草稿 / 跟读 / AI建议 / 短答输入）：直接承载既有组件 spec，
    // 图表（柱/折线/饼）：seriesValues 按「类目 → 各系列值」对齐 AgentChartModel。
    data class Chart(
        val title: String,
        val chartType: String,
        val categories: List<String>,
        val seriesNames: List<String>,
        val seriesValues: List<List<Float>>
    ) : MicroNode
    // 写作（图表作文等）：prompt = 写作要求，reference = 评分参考（如图表数据摘要），交给 AI 四维评分。
    data class Writing(val prompt: String, val reference: String) : MicroNode
    // IELTS 段落标题匹配：为每个段落从 headings 里选最合适的标题（headings 常多于段落，含干扰项）。全对才算对，可判分。
    data class MatchHeadings(
        val prompt: String,
        val paragraphs: List<MicroHeadingParagraph>,
        val headings: List<String>,
        val explanation: String = ""
    ) : MicroNode
    // IELTS 信息匹配：判断每条信息出现在哪个段落（从 options 段落标签里选一个；同段落可被多条信息选中）。全对才算对，可判分。
    data class MatchInfo(
        val prompt: String,
        val options: List<String>,
        val statements: List<MicroMatchInfoItem>,
        val explanation: String = ""
    ) : MicroNode
    // 看图说话 / 话题独白：prompt 为口语任务，scene 为图片/情景的文字描述，points 为要点提示；
    // 用户录音开放作答、AI 评分（复用口语评分组件，自带流程，不走统一判分）。
    data class Monologue(val prompt: String, val scene: String, val points: List<String>) : MicroNode
    // 影子跟读：先 🔊 播放 text（示范音），用户跟着录音朗读、AI 评发音/流利度；translation 为可选中文对照。
    // 复用口语评分组件，自带流程，不走统一判分。
    data class Shadowing(val text: String, val translation: String) : MicroNode
    // 最小对立对听辨：🔊 播放 audioText（answer 那个词），用户从两个近音词 options 里选听到的哪个；
    // ipa 为每个选项的音标（可选，与 options 对齐）。选对才算对，可判分。
    data class MinimalPair(val prompt: String, val audioText: String, val options: List<String>, val answer: String, val ipa: List<String>, val explanation: String) : MicroNode
    // 音标认读：展示 IPA 音标 symbol + 🔊 例词发音（example），用户从 options 里选含该音的词（answer）；选对才算对，可判分。
    data class IpaRead(val prompt: String, val symbol: String, val example: String, val options: List<String>, val answer: String, val explanation: String) : MicroNode
    // 连读/弱读/语调：🔊 播放 text，展示句中的连读/弱读片段 marks（在句中高亮），note 讲解发音要点。展示 + 听，纯学习不判分。
    data class SoundLink(val text: String, val marks: List<String>, val note: String) : MicroNode
    // 听力位置标注（雅思 Part2 风格）：🔊 播放 audioText，layout 文字描述地图/平面，items 为要定位的地点（各从 options 位置标签里选一个）。全对才算对，可判分。
    data class MapLabel(val prompt: String, val audioText: String, val layout: String, val items: List<MicroLabelItem>, val options: List<String>, val explanation: String = "") : MicroNode
    // 长音频笔记填空：🔊 播放 audioText，title 为笔记标题，text 为含 ___ 空位的笔记提纲，逐空「打字」填缺词；answers 每空答案(可 a/b)。全对才算对，可判分。
    data class NoteComplete(val audioText: String, val title: String, val text: String, val answers: List<String>, val explanation: String = "") : MicroNode
    // 句尾配对：stems 为句子开头（各配一个正确句尾 answer），endings 为句尾选项池（常多于句子、含干扰）。全对才算对，可判分。
    data class MatchSentenceEndings(val prompt: String, val stems: List<MicroSentenceStem>, val endings: List<String>, val explanation: String = "") : MicroNode
    // 摘要/流程图填空（跨篇选词）：text 为含 ___ 的摘要，bank 为词库，逐空从词库点词填入；answers 每空答案。全对才算对，可判分。
    data class SummaryComplete(val prompt: String, val text: String, val bank: List<String>, val answers: List<String>, val explanation: String = "") : MicroNode
    // 篇章简答：questions 逐题（q 题干 + answer 参考答案 + accept 可接受写法），用户逐题「打字」简答；与 answer/accept 归一比对，全部对才算对，可判分。
    data class ShortAnswer(val prompt: String, val questions: List<MicroShortAnswerItem>, val explanation: String = "") : MicroNode
    // 结构化引导写作（提纲→段落→成文）：prompt 为写作要求，steps 为分阶段脚手架（label 阶段名 + hint 提示），
    // reference 为评分参考；正文写作区交给 AI 四维评分（复用写作组件，自带流程，不走统一判分）。
    data class GuidedWriting(val prompt: String, val steps: List<MicroWritingStep>, val reference: String) : MicroNode
}

// Chart 微元 → 图表模型（复用既有 AgentChartView 渲染，纯数据转换）。
internal fun MicroNode.Chart.toChartModel(): AgentChartModel =
    AgentChartModel(
        type = chartType,
        categories = categories,
        seriesNames = seriesNames,
        seriesValues = seriesValues
    )

internal data class MicroMatchPair(val left: String, val right: String)

// IELTS matching headings 的一个段落：label(A/B/C)+正文，answer 为该段正确标题（须为 headings 之一）。
internal data class MicroHeadingParagraph(val label: String, val text: String, val answer: String)

// IELTS matching information 的一条信息：text+answer（answer 为其所属段落标签，须为 options 之一）。
internal data class MicroMatchInfoItem(val text: String, val answer: String)

internal data class MicroCategory(val name: String, val items: List<String>)

internal data class MicroFlashItem(val front: String, val back: String)

internal data class MicroDiagramItem(val text: String, val label: String)

internal data class MicroTrueFalseItem(val text: String, val answer: Boolean)

internal data class MicroTfngItem(val text: String, val answer: String) // answer ∈ "true" | "false" | "not_given"

internal data class MicroDialogueTurn(val speaker: String, val text: String)

internal data class MicroProofLine(val text: String, val answer: String, val note: String)

internal data class MicroCellCoord(val row: Int, val col: Int)

internal data class MicroClozeSelectBlank(val options: List<String>, val answer: String)

internal data class MicroWordFormItem(val base: String, val target: String, val answer: String)

internal data class MicroTimelineEvent(val time: String, val title: String, val detail: String)

// 听力位置标注的一项：text 为要定位的地点，answer 为其正确位置标签（须为 options 之一）。
internal data class MicroLabelItem(val text: String, val answer: String)

// 句尾配对的一个句子：text 为句子开头，answer 为其正确句尾（须为 endings 之一）。
internal data class MicroSentenceStem(val text: String, val answer: String)

// 篇章简答的一题：q 题干，answer 参考答案，accept 可接受写法（归一容错）。
internal data class MicroShortAnswerItem(val q: String, val answer: String, val accept: List<String>)

// 结构化引导写作的一个阶段：label 阶段名（如「提纲」「主体段」），hint 该阶段提示。
internal data class MicroWritingStep(val label: String, val hint: String)

internal data class MicroCard(val title: String, val nodes: List<MicroNode>)

internal object MicroCardParser {
    fun parse(text: String): MicroCard? = parse(parseJsonObjectOrNull(text))

    fun parse(obj: JsonObject?): MicroCard? {
        if (obj == null) return null
        val arr = obj.arrOrNull("nodes") ?: return MicroCard(obj.str("title"), emptyList())
        val nodes = (0 until arr.size).mapNotNull { index -> parseNode(arr.objOrNull(index)) }
        return MicroCard(obj.str("title"), nodes)
    }

    private fun parseNode(obj: JsonObject?): MicroNode? {
        if (obj == null) return null
        return when (obj.str("type").trim().lowercase()) {
            "text" -> MicroNode.Text(obj.str("text"), obj.str("role", "body").lowercase())
            "passage" -> MicroNode.Passage(obj.str("text"))
            "choice" -> MicroNode.Choice(
                prompt = obj.str("prompt"),
                options = strList(obj.arrOrNull("options")),
                answer = obj.str("answer"),
                multi = obj.bool("multi"),
                explanation = obj.str("explanation")
            )
            "tokens" -> MicroNode.Tokens(
                text = obj.str("text"),
                correct = obj.str("correct"),
                explanation = obj.str("explanation"),
                errors = strList(obj.arrOrNull("errors"))
            )
            "input" -> MicroNode.Input(obj.str("prompt"), obj.str("answer"), obj.bool("multiline"), explanation = obj.str("explanation"))
            "order" -> MicroNode.Order(
                prompt = obj.str("prompt"),
                items = strList(obj.arrOrNull("items")),
                answer = obj.str("answer"),
                explanation = obj.str("explanation")
            )
            "audio" -> MicroNode.Audio(obj.str("src"))
            "reveal" -> MicroNode.Reveal(obj.str("label"), obj.str("content"))
            "match" -> MicroNode.Match(prompt = obj.str("prompt"), pairs = matchPairs(obj.arrOrNull("pairs")), explanation = obj.str("explanation"))
            "categorize" -> MicroNode.Categorize(prompt = obj.str("prompt"), categories = categoryList(obj.arrOrNull("categories")), explanation = obj.str("explanation"))
            "flashcard" -> MicroNode.Flashcard(prompt = obj.str("prompt"), cards = flashItems(obj.arrOrNull("cards")))
            "dictation" -> MicroNode.Dictation(text = obj.str("text"), hint = obj.str("hint"), explanation = obj.str("explanation"))
            "audio_choice" -> MicroNode.AudioChoice(
                audioText = obj.str("audioText"),
                prompt = obj.str("prompt"),
                options = strList(obj.arrOrNull("options")),
                answer = obj.str("answer"),
                explanation = obj.str("explanation")
            )
            "speak_score" -> MicroNode.SpeakScore(text = obj.str("text"), prompts = strList(obj.arrOrNull("prompts")))
            "cloze_drag" -> MicroNode.ClozeDrag(
                text = obj.str("text"),
                bank = strList(obj.arrOrNull("bank")),
                answers = alignedStrList(obj.arrOrNull("answers")),
                explanation = obj.str("explanation")
            )
            "roleplay_turn" -> MicroNode.RoleplayTurn(
                scenario = obj.str("scenario"),
                opening = obj.str("opening"),
                goal = obj.str("goal"),
                turns = obj.str("turns").toIntOrNull() ?: 0
            )
            "ai_hint" -> MicroNode.AiHint(
                prompt = obj.str("prompt"),
                hints = strList(obj.arrOrNull("hints")),
                answer = obj.str("answer")
            )
            "highlight_span" -> MicroNode.HighlightSpan(
                prompt = obj.str("prompt"),
                text = obj.str("text"),
                answers = strList(obj.arrOrNull("answers")),
                explanation = obj.str("explanation")
            )
            "timed_challenge" -> MicroNode.TimedChallenge(
                prompt = obj.str("prompt"),
                options = strList(obj.arrOrNull("options")),
                answer = obj.str("answer"),
                seconds = (obj.str("seconds").toIntOrNull() ?: 15).coerceIn(5, 120)
            )
            "table" -> MicroNode.Table(
                title = obj.str("title"),
                headers = strList(obj.arrOrNull("headers")),
                rows = tableRows(obj.arrOrNull("rows"))
            )
            "sentence_diagram" -> MicroNode.SentenceDiagram(
                prompt = obj.str("prompt"),
                sentence = obj.str("sentence"),
                labels = strList(obj.arrOrNull("labels")),
                items = diagramItems(obj.arrOrNull("items")),
                explanation = obj.str("explanation")
            )
            "word_scramble" -> MicroNode.WordScramble(
                word = obj.str("word"),
                hint = obj.str("hint"),
                scrambled = strList(obj.arrOrNull("scrambled")),
                explanation = obj.str("explanation")
            )
            "true_false" -> MicroNode.TrueFalse(
                prompt = obj.str("prompt"),
                statements = trueFalseItems(obj.arrOrNull("statements")),
                explanation = obj.str("explanation")
            )
            "tfng" -> MicroNode.Tfng(
                prompt = obj.str("prompt"),
                statements = tfngItems(obj.arrOrNull("statements")),
                explanation = obj.str("explanation")
            )
            "match_headings" -> MicroNode.MatchHeadings(
                prompt = obj.str("prompt"),
                paragraphs = headingParagraphs(obj.arrOrNull("paragraphs")),
                headings = strList(obj.arrOrNull("headings")),
                explanation = obj.str("explanation")
            )
            "match_info" -> MicroNode.MatchInfo(
                prompt = obj.str("prompt"),
                options = strList(obj.arrOrNull("options")),
                statements = matchInfoItems(obj.arrOrNull("statements")),
                explanation = obj.str("explanation")
            )
            "fill_table" -> MicroNode.FillTable(
                title = obj.str("title"),
                headers = strList(obj.arrOrNull("headers")),
                rows = tableRows(obj.arrOrNull("rows")),
                blanks = cellCoords(obj.arrOrNull("blanks")),
                bank = strList(obj.arrOrNull("bank")),
                explanation = obj.str("explanation")
            )
            "pairs_memory" -> MicroNode.PairsMemory(
                prompt = obj.str("prompt"),
                pairs = matchPairs(obj.arrOrNull("pairs"))
            )
            "cloze_select" -> MicroNode.ClozeSelect(
                text = obj.str("text"),
                blanks = clozeSelectBlanks(obj.arrOrNull("blanks")),
                explanation = obj.str("explanation")
            )
            "listen_fill" -> MicroNode.ListenFill(
                audioText = obj.str("audioText"),
                text = obj.str("text"),
                bank = strList(obj.arrOrNull("bank")),
                answers = alignedStrList(obj.arrOrNull("answers")),
                explanation = obj.str("explanation")
            )
            "word_formation" -> MicroNode.WordFormation(
                prompt = obj.str("prompt"),
                items = wordFormItems(obj.arrOrNull("items")),
                bank = strList(obj.arrOrNull("bank")),
                explanation = obj.str("explanation")
            )
            "timeline" -> MicroNode.Timeline(
                title = obj.str("title"),
                events = timelineEvents(obj.arrOrNull("events"))
            )
            "stress_mark" -> MicroNode.StressMark(
                word = obj.str("word"),
                syllables = strList(obj.arrOrNull("syllables")),
                stress = obj.int("stress"),
                explanation = obj.str("explanation")
            )
            "reorder_paragraph" -> MicroNode.ReorderParagraph(
                prompt = obj.str("prompt"),
                sentences = strList(obj.arrOrNull("sentences")),
                explanation = obj.str("explanation")
            )
            "odd_one_out" -> MicroNode.OddOneOut(
                prompt = obj.str("prompt"),
                items = strList(obj.arrOrNull("items")),
                answer = obj.str("answer"),
                explanation = obj.str("explanation")
            )
            "rank_order" -> MicroNode.RankOrder(
                prompt = obj.str("prompt"),
                items = strList(obj.arrOrNull("items")),
                from = obj.str("from"),
                to = obj.str("to"),
                explanation = obj.str("explanation")
            )
            "spelling_bee" -> MicroNode.SpellingBee(
                word = obj.str("word"),
                hint = obj.str("hint"),
                example = obj.str("example"),
                explanation = obj.str("explanation")
            )
            "sentence_transform" -> MicroNode.SentenceTransform(
                prompt = obj.str("prompt"),
                source = obj.str("source"),
                answer = obj.str("answer"),
                accept = strList(obj.arrOrNull("accept")),
                hint = obj.str("hint"),
                explanation = obj.str("explanation")
            )
            "open_cloze" -> MicroNode.OpenCloze(
                prompt = obj.str("prompt"),
                text = obj.str("text"),
                answers = alignedStrList(obj.arrOrNull("answers")),
                explanation = obj.str("explanation")
            )
            "translate" -> MicroNode.Translate(
                direction = obj.str("direction"),
                source = obj.str("source"),
                answer = obj.str("answer"),
                accept = strList(obj.arrOrNull("accept")),
                hint = obj.str("hint"),
                explanation = obj.str("explanation")
            )
            "listen_cloze" -> MicroNode.ListenCloze(
                audioText = obj.str("audioText"),
                text = obj.str("text"),
                answers = alignedStrList(obj.arrOrNull("answers")),
                explanation = obj.str("explanation")
            )
            "error_correction" -> MicroNode.ErrorCorrection(
                prompt = obj.str("prompt"),
                sentence = obj.str("sentence"),
                answer = obj.str("answer"),
                accept = strList(obj.arrOrNull("accept")),
                explanation = obj.str("explanation")
            )
            "dialogue_complete" -> MicroNode.DialogueComplete(
                prompt = obj.str("prompt"),
                turns = dialogueTurns(obj.arrOrNull("turns")),
                options = strList(obj.arrOrNull("options")),
                answer = obj.str("answer"),
                explanation = obj.str("explanation")
            )
            "word_search" -> MicroNode.WordSearch(
                prompt = obj.str("prompt"),
                grid = stringGrid(obj.arrOrNull("grid")),
                words = strList(obj.arrOrNull("words")),
                explanation = obj.str("explanation")
            )
            "hangman" -> MicroNode.Hangman(
                word = obj.str("word"),
                hint = obj.str("hint"),
                maxWrong = (obj.str("maxWrong").toIntOrNull() ?: 6).coerceIn(1, 26),
                explanation = obj.str("explanation")
            )
            "proof_paragraph" -> MicroNode.ProofParagraph(
                prompt = obj.str("prompt"),
                lines = proofLines(obj.arrOrNull("lines")),
                explanation = obj.str("explanation")
            )
            "progress" -> MicroNode.Progress(
                label = obj.str("label"),
                value = (obj.str("value").toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
            )
            "chart" -> parseChartNode(obj)
            "writing" -> MicroNode.Writing(
                prompt = obj.str("prompt"),
                reference = obj.str("reference")
            )
            "monologue" -> MicroNode.Monologue(
                prompt = obj.str("prompt"),
                scene = obj.str("scene"),
                points = strList(obj.arrOrNull("points"))
            )
            "shadowing" -> MicroNode.Shadowing(text = obj.str("text"), translation = obj.str("translation"))
            "minimal_pair" -> MicroNode.MinimalPair(
                prompt = obj.str("prompt"),
                audioText = obj.str("audioText"),
                options = strList(obj.arrOrNull("options")),
                answer = obj.str("answer"),
                ipa = alignedStrList(obj.arrOrNull("ipa")),
                explanation = obj.str("explanation")
            )
            "ipa_read" -> MicroNode.IpaRead(
                prompt = obj.str("prompt"),
                symbol = obj.str("symbol"),
                example = obj.str("example"),
                options = strList(obj.arrOrNull("options")),
                answer = obj.str("answer"),
                explanation = obj.str("explanation")
            )
            "sound_link" -> MicroNode.SoundLink(
                text = obj.str("text"),
                marks = strList(obj.arrOrNull("marks")),
                note = obj.str("note")
            )
            "map_label" -> MicroNode.MapLabel(
                prompt = obj.str("prompt"),
                audioText = obj.str("audioText"),
                layout = obj.str("layout"),
                items = labelItems(obj.arrOrNull("items")),
                options = strList(obj.arrOrNull("options")),
                explanation = obj.str("explanation")
            )
            "note_complete" -> MicroNode.NoteComplete(
                audioText = obj.str("audioText"),
                title = obj.str("title"),
                text = obj.str("text"),
                answers = alignedStrList(obj.arrOrNull("answers")),
                explanation = obj.str("explanation")
            )
            "match_sentence_endings" -> MicroNode.MatchSentenceEndings(
                prompt = obj.str("prompt"),
                stems = sentenceStems(obj.arrOrNull("stems")),
                endings = strList(obj.arrOrNull("endings")),
                explanation = obj.str("explanation")
            )
            "summary_complete" -> MicroNode.SummaryComplete(
                prompt = obj.str("prompt"),
                text = obj.str("text"),
                bank = strList(obj.arrOrNull("bank")),
                answers = alignedStrList(obj.arrOrNull("answers")),
                explanation = obj.str("explanation")
            )
            "short_answer" -> MicroNode.ShortAnswer(
                prompt = obj.str("prompt"),
                questions = shortAnswerItems(obj.arrOrNull("questions")),
                explanation = obj.str("explanation")
            )
            "guided_writing" -> MicroNode.GuidedWriting(
                prompt = obj.str("prompt"),
                steps = writingSteps(obj.arrOrNull("steps")),
                reference = obj.str("reference")
            )
            else -> null // 未知微元忽略（graceful 降级，不套固定题型）
        }
    }

    // chart 微元 JSON 形如 {"chartType":"bar","title":"..","categories":[..],"series":[{"name":"..","values":[..]}]}
    // series 为「按系列」组织（每系列一组按类目的值），渲染模型需「按类目」组织，这里转置。
    private fun parseChartNode(obj: JsonObject): MicroNode.Chart {
        val categories = strList(obj.arrOrNull("categories"))
        val seriesArr = obj.arrOrNull("series")
        val seriesNames = mutableListOf<String>()
        val perSeries = mutableListOf<List<Float>>()
        if (seriesArr != null) {
            for (i in 0 until seriesArr.size) {
                val s = seriesArr.objOrNull(i) ?: continue
                seriesNames += s.str("name").ifBlank { "系列${seriesNames.size + 1}" }
                perSeries += floatList(s.arrOrNull("values"))
            }
        }
        val rowCount = if (categories.isNotEmpty()) categories.size else (perSeries.maxOfOrNull { it.size } ?: 0)
        val seriesValues = (0 until rowCount).map { row -> perSeries.map { it.getOrNull(row) ?: 0f } }
        return MicroNode.Chart(
            title = obj.str("title"),
            chartType = agentNormalizeChartType(obj.str("chartType")),
            categories = categories,
            seriesNames = seriesNames,
            seriesValues = seriesValues
        )
    }

    private fun strList(arr: JsonArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.size).map { arr.str(it) }.filter { it.isNotBlank() }
    }

    // 按位对齐的字符串列表：保留空白项占位（只 trim），供「顺序对应 ___/options」的数组用
    //（answers/ipa 等）。若像 strList 那样丢弃空项，会让后续「空位↔答案」整体左移错配。
    private fun alignedStrList(arr: JsonArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.size).map { arr.str(it).trim() }
    }

    private fun matchPairs(arr: JsonArray?): List<MicroMatchPair> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { index ->
            val o = arr.objOrNull(index) ?: return@mapNotNull null
            val left = o.str("left").trim()
            val right = o.str("right").trim()
            if (left.isBlank() || right.isBlank()) null else MicroMatchPair(left, right)
        }
    }

    private fun categoryList(arr: JsonArray?): List<MicroCategory> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { index ->
            val o = arr.objOrNull(index) ?: return@mapNotNull null
            val name = o.str("name").trim()
            val items = strList(o.arrOrNull("items"))
            if (name.isBlank() || items.isEmpty()) null else MicroCategory(name, items)
        }
    }

    private fun flashItems(arr: JsonArray?): List<MicroFlashItem> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { index ->
            val o = arr.objOrNull(index) ?: return@mapNotNull null
            val front = o.str("front").trim()
            val back = o.str("back").trim()
            if (front.isBlank() || back.isBlank()) null else MicroFlashItem(front, back)
        }
    }

    private fun floatList(arr: JsonArray?): List<Float> {
        if (arr == null) return emptyList()
        return (0 until arr.size).map { arr.str(it).toFloatOrNull() ?: 0f }
    }

    // 表格行：rows 为「数组的数组」，每行内是若干单元格字符串；丢弃整行全空的行。
    private fun tableRows(arr: JsonArray?): List<List<String>> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { i ->
            val row = arr.arrOrNull(i) ?: return@mapNotNull null
            (0 until row.size).map { row.str(it) }
        }.filter { cells -> cells.any { it.isNotBlank() } }
    }

    // 字母网格：grid 为「数组的数组」，每行内是若干单字母；保留所有行/格（供 word_search 用）。
    private fun stringGrid(arr: JsonArray?): List<List<String>> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { i ->
            val row = arr.arrOrNull(i) ?: return@mapNotNull null
            (0 until row.size).map { row.str(it).trim() }
        }
    }

    // 句子成分块：items 为 [{text,label}]，丢弃缺 text 或 label 的块。
    private fun diagramItems(arr: JsonArray?): List<MicroDiagramItem> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { index ->
            val o = arr.objOrNull(index) ?: return@mapNotNull null
            val text = o.str("text").trim()
            val label = o.str("label").trim()
            if (text.isBlank() || label.isBlank()) null else MicroDiagramItem(text, label)
        }
    }

    // 判断题：statements 为 [{text,answer}]，answer 宽松解析真/假；丢弃缺 text 的项。
    private fun trueFalseItems(arr: JsonArray?): List<MicroTrueFalseItem> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { index ->
            val o = arr.objOrNull(index) ?: return@mapNotNull null
            val text = o.str("text").trim()
            if (text.isBlank()) null else MicroTrueFalseItem(text, parseTrueFalse(o))
        }
    }

    // answer 宽松判真：布尔 true / "true" 直判；再认可常见真值写法（对/正确/yes/T/1/√）。其余视为 false。
    private fun parseTrueFalse(o: JsonObject): Boolean {
        if (o.bool("answer")) return true
        val raw = o.str("answer").trim().lowercase()
        return raw in setOf("true", "t", "yes", "y", "1", "对", "正确", "是", "√")
    }

    // 判断三态：statements 为 [{text,answer}]，answer 宽松解析为 true/false/not_given；丢弃缺 text 的项。
    private fun tfngItems(arr: JsonArray?): List<MicroTfngItem> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { index ->
            val o = arr.objOrNull(index) ?: return@mapNotNull null
            val text = o.str("text").trim()
            if (text.isBlank()) null else MicroTfngItem(text, parseTfng(o))
        }
    }

    // answer 宽松三态：对/正确/yes/T/1/√/true→true；错/错误/no/F/0/×→false；含 not given / 未提及 / ng 等→not_given；缺省 not_given。
    private fun parseTfng(o: JsonObject): String {
        val raw = o.str("answer").trim().lowercase()
        return when {
            raw in setOf("true", "t", "yes", "y", "1", "对", "正确", "是", "√") -> "true"
            raw in setOf("false", "f", "no", "n", "0", "错", "错误", "否", "×", "x") -> "false"
            raw.contains("not") && raw.contains("given") -> "not_given"
            raw in setOf("ng", "na", "n/a", "not_given", "notgiven", "not given", "未提及", "未说明", "没提到", "未提到", "无法判断", "未涉及") -> "not_given"
            o.bool("answer") -> "true"
            else -> "not_given"
        }
    }

    // IELTS 段落：paragraphs 为 [{label,text,answer}]，answer 为该段正确标题；丢弃缺 text 的项。
    private fun headingParagraphs(arr: JsonArray?): List<MicroHeadingParagraph> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { index ->
            val o = arr.objOrNull(index) ?: return@mapNotNull null
            val text = o.str("text").trim()
            if (text.isBlank()) null else MicroHeadingParagraph(o.str("label").trim(), text, o.str("answer").trim())
        }
    }

    // IELTS 信息匹配：statements 为 [{text,answer}]，answer 为该信息所属段落标签；丢弃缺 text 的项。
    private fun matchInfoItems(arr: JsonArray?): List<MicroMatchInfoItem> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { index ->
            val o = arr.objOrNull(index) ?: return@mapNotNull null
            val text = o.str("text").trim()
            if (text.isBlank()) null else MicroMatchInfoItem(text, o.str("answer").trim())
        }
    }

    // 对话轮：turns 为 [{speaker,text}]，丢弃缺 text 的项。
    private fun dialogueTurns(arr: JsonArray?): List<MicroDialogueTurn> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { index ->
            val o = arr.objOrNull(index) ?: return@mapNotNull null
            val text = o.str("text").trim()
            if (text.isBlank()) null else MicroDialogueTurn(o.str("speaker").trim(), text)
        }
    }

    // 短文改错行：lines 为 [{text,answer?,note?}]，丢弃缺 text 的行。
    private fun proofLines(arr: JsonArray?): List<MicroProofLine> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { index ->
            val o = arr.objOrNull(index) ?: return@mapNotNull null
            val text = o.str("text").trim()
            if (text.isBlank()) null else MicroProofLine(text, o.str("answer").trim(), o.str("note").trim())
        }
    }

    // 单元格坐标：blanks 形如 [[行,列], ...]（0 基）；丢弃非法/负数坐标。
    private fun cellCoords(arr: JsonArray?): List<MicroCellCoord> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { i ->
            val pair = arr.arrOrNull(i) ?: return@mapNotNull null
            if (pair.size < 2) return@mapNotNull null
            val r = pair.str(0).toIntOrNull() ?: return@mapNotNull null
            val c = pair.str(1).toIntOrNull() ?: return@mapNotNull null
            if (r < 0 || c < 0) null else MicroCellCoord(r, c)
        }
    }

    // 完形选择空位：blanks 为 [{options:[...],answer}]，顺序对应 text 里的 ___；保留占位（含空 options）以对齐空位序。
    private fun clozeSelectBlanks(arr: JsonArray?): List<MicroClozeSelectBlank> {
        if (arr == null) return emptyList()
        return (0 until arr.size).map { i ->
            val o = arr.objOrNull(i)
            if (o == null) MicroClozeSelectBlank(emptyList(), "")
            else MicroClozeSelectBlank(options = strList(o.arrOrNull("options")), answer = o.str("answer"))
        }
    }

    // 词形转换项：items 为 [{base,target?,answer}]，丢弃缺 base 或 answer 的项。
    private fun wordFormItems(arr: JsonArray?): List<MicroWordFormItem> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { i ->
            val o = arr.objOrNull(i) ?: return@mapNotNull null
            val base = o.str("base").trim()
            val answer = o.str("answer").trim()
            if (base.isBlank() || answer.isBlank()) null else MicroWordFormItem(base, o.str("target").trim(), answer)
        }
    }

    // 时间线事件：events 为 [{time?,title,detail?}]，丢弃缺 title 的项。
    private fun timelineEvents(arr: JsonArray?): List<MicroTimelineEvent> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { i ->
            val o = arr.objOrNull(i) ?: return@mapNotNull null
            val title = o.str("title").trim()
            if (title.isBlank()) null else MicroTimelineEvent(o.str("time").trim(), title, o.str("detail").trim())
        }
    }

    // 位置标注项：items 为 [{text,answer}]，answer 为其位置标签；丢弃缺 text 的项。
    private fun labelItems(arr: JsonArray?): List<MicroLabelItem> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { i ->
            val o = arr.objOrNull(i) ?: return@mapNotNull null
            val text = o.str("text").trim()
            if (text.isBlank()) null else MicroLabelItem(text, o.str("answer").trim())
        }
    }

    // 句尾配对句子：stems 为 [{text,answer}]，answer 为其正确句尾；丢弃缺 text 的项。
    private fun sentenceStems(arr: JsonArray?): List<MicroSentenceStem> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { i ->
            val o = arr.objOrNull(i) ?: return@mapNotNull null
            val text = o.str("text").trim()
            if (text.isBlank()) null else MicroSentenceStem(text, o.str("answer").trim())
        }
    }

    // 篇章简答题：questions 为 [{q,answer,accept?}]，丢弃缺 q 或 answer 的题。
    private fun shortAnswerItems(arr: JsonArray?): List<MicroShortAnswerItem> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { i ->
            val o = arr.objOrNull(i) ?: return@mapNotNull null
            val q = o.str("q").trim()
            val answer = o.str("answer").trim()
            if (q.isBlank() || answer.isBlank()) null else MicroShortAnswerItem(q, answer, strList(o.arrOrNull("accept")))
        }
    }

    // 引导写作阶段：steps 为 [{label,hint?}]，丢弃缺 label 的阶段。
    private fun writingSteps(arr: JsonArray?): List<MicroWritingStep> {
        if (arr == null) return emptyList()
        return (0 until arr.size).mapNotNull { i ->
            val o = arr.objOrNull(i) ?: return@mapNotNull null
            val label = o.str("label").trim()
            if (label.isBlank()) null else MicroWritingStep(label, o.str("hint").trim())
        }
    }
}

// ---- 纯判分助手（无 Compose，可单测）----

internal fun microChoiceCorrect(selectedIndex: Int?, options: List<String>, answer: String): Boolean {
    if (selectedIndex == null || selectedIndex !in options.indices) return false
    val picked = options[selectedIndex].trim()
    return picked.equals(answer.trim(), ignoreCase = true) ||
        normalizeAgentShortAnswer(picked).equals(normalizeAgentShortAnswer(answer), ignoreCase = true)
}

internal fun microInputCorrect(text: String, answer: String): Boolean =
    answer.isNotBlank() &&
        normalizeAgentShortAnswer(text).equals(normalizeAgentShortAnswer(answer), ignoreCase = true)

// 排序/连词成句判分：answer 用 "a | b | c" 则按序逐项比对；否则把所选拼接后与完整句子归一化比对。
internal fun normalizeAgentOrderToken(value: String): String =
    normalizeAgentShortAnswer(value).replace(Regex("[\\s\\p{P}]+"), "").lowercase()

internal fun microOrderCorrect(selected: List<String>, answer: String): Boolean {
    if (selected.isEmpty() || answer.isBlank()) return false
    val parts = answer.split("|").map { it.trim() }.filter { it.isNotBlank() }
    return if (parts.size >= 2) {
        selected.size == parts.size && selected.indices.all { normalizeAgentOrderToken(selected[it]) == normalizeAgentOrderToken(parts[it]) }
    } else {
        normalizeAgentOrderToken(selected.joinToString(" ")) == normalizeAgentOrderToken(answer)
    }
}

// 连线匹配判分：每个左项所选右项需与其正确右项归一化相等；全部对才算对。
internal fun microMatchCorrect(selected: Map<Int, String>, pairs: List<MicroMatchPair>): Boolean {
    if (pairs.isEmpty()) return false
    return pairs.indices.all { i ->
        val sel = selected[i]
        sel != null && normalizeAgentShortAnswer(sel).equals(normalizeAgentShortAnswer(pairs[i].right), ignoreCase = true)
    }
}

// 归类判分：每个项（按值）所选类别需与其所属类别归一化相等；全部对才算对。
internal fun microCategorizeCorrect(selected: Map<String, String>, categories: List<MicroCategory>): Boolean {
    val items = categories.flatMap { c -> c.items.map { it to c.name } }
    if (items.size < 2) return false
    return items.all { (item, cat) ->
        val sel = selected[item]
        sel != null && normalizeAgentShortAnswer(sel).equals(normalizeAgentShortAnswer(cat), ignoreCase = true)
    }
}

// 句子成分判分：每块（按下标）所选标签需与其正确 label 归一化相等；全部对才算对。
internal fun microDiagramCorrect(selected: Map<Int, String>, items: List<MicroDiagramItem>): Boolean {
    if (items.isEmpty()) return false
    return items.indices.all { i ->
        val sel = selected[i]
        sel != null && normalizeAgentShortAnswer(sel).equals(normalizeAgentShortAnswer(items[i].label), ignoreCase = true)
    }
}

// 点选填空判分：每个空位所填词需与 answers[i] 归一化相等；全部填对才算对。
internal fun microClozeDragCorrect(filled: Map<Int, String>, answers: List<String>): Boolean {
    if (answers.isEmpty()) return false
    return answers.indices.all { i ->
        val f = filled[i]
        f != null && normalizeAgentShortAnswer(f).equals(normalizeAgentShortAnswer(answers[i]), ignoreCase = true)
    }
}

// 点选找错的“正确错误位置”：优先用 errors 片段定位，否则用 错句↔正确句 词级 diff（复用改错卡逻辑）。
internal fun microTokensErrorIndices(node: MicroNode.Tokens): Set<Int> {
    val tokens = agentSplitErrorTokens(node.text)
    if (node.errors.isNotEmpty()) {
        val located = sortedSetOf<Int>()
        node.errors.forEach { located.addAll(agentLocatePhraseTokenIndices(tokens, it)) }
        if (located.isNotEmpty()) return located
    }
    return if (node.correct.isNotBlank()) {
        agentFindErrorIndicesByDiff(tokens, agentSplitErrorTokens(node.correct))
    } else {
        emptySet()
    }
}

// 篇章框选目标位置：answers 里每个词在 text 分词后的位置并集（复用改错卡的短语定位）。
internal fun microHighlightTargetIndices(node: MicroNode.HighlightSpan): Set<Int> {
    val tokens = agentSplitErrorTokens(node.text)
    val located = sortedSetOf<Int>()
    node.answers.forEach { located.addAll(agentLocatePhraseTokenIndices(tokens, it)) }
    return located
}

// 篇章框选判分：所选 token 位置集需恰好等于目标位置集（全选对、且不多选）。
internal fun microHighlightCorrect(selected: Set<Int>, node: MicroNode.HighlightSpan): Boolean {
    val target = microHighlightTargetIndices(node)
    return target.isNotEmpty() && selected == target
}

// 字母重组的字母/块池：优先用 AI 给的 scrambled；否则按 word 拆成单字母（丢弃空白）。位置下标稳定，供选择态引用。
internal fun microScrambleTiles(node: MicroNode.WordScramble): List<String> {
    val fromScrambled = node.scrambled.map { it.trim() }.filter { it.isNotEmpty() }
    if (fromScrambled.isNotEmpty()) return fromScrambled
    return node.word.filter { !it.isWhitespace() }.map { it.toString() }
}

// 字母重组判分：把已拼字母顺序拼接后与 word 归一化比对（忽略大小写/空格/标点）。重复字母也能正确判定。
internal fun microWordScrambleCorrect(assembled: List<String>, word: String): Boolean {
    if (word.isBlank() || assembled.isEmpty()) return false
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
    val built = norm(assembled.joinToString(""))
    return built.isNotEmpty() && built == norm(word)
}

// 判断对错判分：每句（按下标）所选真/假需与其 answer 相等；全部判对才算对（未判的句子视为未完成→错）。
internal fun microTrueFalseCorrect(selected: Map<Int, Boolean>, statements: List<MicroTrueFalseItem>): Boolean {
    if (statements.isEmpty()) return false
    return statements.indices.all { i -> selected[i] == statements[i].answer }
}

// 判断三态判分：每句所选(true/false/not_given)需与其 answer 相等；全部判对才算对（未判视为未完成→错）。
internal fun microTfngCorrect(selected: Map<Int, String>, statements: List<MicroTfngItem>): Boolean {
    if (statements.isEmpty()) return false
    return statements.indices.all { i -> selected[i] == statements[i].answer }
}

// IELTS 段落标题匹配判分：每个段落所选标题须等于其正确标题（trim 比较），全对才算对。
internal fun microMatchHeadingsCorrect(selected: Map<Int, String>, paragraphs: List<MicroHeadingParagraph>): Boolean {
    if (paragraphs.isEmpty()) return false
    return paragraphs.withIndex().all { (i, p) -> p.answer.isNotBlank() && selected[i]?.trim() == p.answer.trim() }
}

// IELTS 信息匹配判分：每条信息所选段落标签须等于其正确段落，全对才算对。
internal fun microMatchInfoCorrect(selected: Map<Int, String>, statements: List<MicroMatchInfoItem>): Boolean {
    if (statements.isEmpty()) return false
    return statements.withIndex().all { (i, s) -> s.answer.isNotBlank() && selected[i]?.trim() == s.answer.trim() }
}

// 能力反馈闭环：可判分微元 → 六技能维度（与 LearnerModelStore.SKILLS 对齐）。
// 泛用题型（choice/input 等哪个维度都可能）返回 null、不参与投票。
internal fun microNodeSkill(node: MicroNode): String? = when (node) {
    is MicroNode.Dictation, is MicroNode.AudioChoice, is MicroNode.ListenFill, is MicroNode.ListenCloze,
        is MicroNode.MinimalPair, is MicroNode.MapLabel, is MicroNode.NoteComplete -> "听力"
    is MicroNode.Match, is MicroNode.Categorize, is MicroNode.WordScramble, is MicroNode.WordFormation,
        is MicroNode.OddOneOut, is MicroNode.RankOrder, is MicroNode.SpellingBee,
        is MicroNode.WordSearch, is MicroNode.Hangman -> "词汇"
    is MicroNode.Tokens, is MicroNode.Order, is MicroNode.ClozeDrag, is MicroNode.ClozeSelect,
        is MicroNode.OpenCloze, is MicroNode.FillTable, is MicroNode.HighlightSpan, is MicroNode.SentenceDiagram,
        is MicroNode.TrueFalse, is MicroNode.SentenceTransform, is MicroNode.ErrorCorrection,
        is MicroNode.ProofParagraph -> "语法"
    is MicroNode.Tfng, is MicroNode.MatchHeadings, is MicroNode.MatchInfo, is MicroNode.ReorderParagraph,
        is MicroNode.MatchSentenceEndings, is MicroNode.SummaryComplete, is MicroNode.ShortAnswer -> "阅读"
    is MicroNode.Translate, is MicroNode.GuidedWriting -> "写作"
    is MicroNode.StressMark, is MicroNode.DialogueComplete,
        is MicroNode.Monologue, is MicroNode.Shadowing, is MicroNode.IpaRead, is MicroNode.SoundLink -> "口语"
    else -> null
}

// 整卡主导技能：全部节点按技能多数票（泛用/展示节点弃权；口语跟读、引导写作等
// 自带流程节点也计入维度倾向）；全部弃权 → null（不更新能力）。
internal fun microCardDominantSkill(nodes: List<MicroNode>): String? =
    nodes.mapNotNull { microNodeSkill(it) }
        .groupingBy { it }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

// 错因回灌：一个"答错的可判分微元"抽成的错题本条目。
internal data class MicroReviewInfo(
    val componentType: String,
    val prompt: String,
    val options: List<String>,
    val answer: String,
    val explanation: String
)

// 把一个可判分微元抽成错题本条目（prompt/options/answer/explanation）。覆盖全部可判分题型：
// 多空/排序/表格等降解成「题面 + 文字正解」（与题库导出同口径），复习时看答案自评「记得/忘了」；
// 单空且有选项池的题型（完形单空/重音等）保留 options 走点选复习。prompt 或 answer 为空则丢弃。
internal fun microNodeReviewInfo(node: MicroNode): MicroReviewInfo? {
    fun make(type: String, prompt: String, answer: String, options: List<String> = emptyList(), explanation: String = ""): MicroReviewInfo? {
        val p = prompt.trim()
        val a = answer.trim()
        if (p.isBlank() || a.isBlank()) return null
        return MicroReviewInfo(type, p, options, a, explanation.trim())
    }
    return when (node) {
        is MicroNode.Choice -> make("choice", node.prompt.ifBlank { "选择题" }, node.answer, node.options, node.explanation)
        is MicroNode.AudioChoice -> make("audio_choice", node.prompt.ifBlank { "听辨：${node.audioText}" }, node.answer, node.options, node.explanation)
        is MicroNode.TimedChallenge -> make("timed_challenge", node.prompt.ifBlank { "限时抢答" }, node.answer, node.options)
        is MicroNode.DialogueComplete -> make("dialogue_complete", node.prompt.ifBlank { "补全对话" }, node.answer, node.options, node.explanation)
        is MicroNode.OddOneOut -> make("odd_one_out", node.prompt.ifBlank { "选出不同类的一项" }, node.answer, node.items, node.explanation)
        is MicroNode.Input -> make("input", node.prompt.ifBlank { "作答" }, node.answer, explanation = node.explanation)
        is MicroNode.Dictation -> make("dictation", "听写", node.text, explanation = node.explanation)
        is MicroNode.SpellingBee -> make("spelling_bee", node.hint.ifBlank { "拼写单词" }, node.word, explanation = node.explanation)
        is MicroNode.WordScramble -> make("word_scramble", node.hint.ifBlank { "字母重组" }, node.word, explanation = node.explanation)
        is MicroNode.Hangman -> make("hangman", node.hint.ifBlank { "猜词" }, node.word, explanation = node.explanation)
        is MicroNode.SentenceTransform -> make("sentence_transform", node.prompt.ifBlank { "句型转换：${node.source}" }, node.answer, explanation = node.hint)
        is MicroNode.ErrorCorrection -> make("error_correction", node.prompt.ifBlank { "改错：${node.sentence}" }, node.answer, explanation = node.explanation)
        is MicroNode.Translate -> make("translate", node.source.ifBlank { "翻译" }.let { "翻译：$it" }, node.answer, explanation = node.hint)
        is MicroNode.TrueFalse -> make(
            "true_false",
            node.prompt.ifBlank { "判断对错" },
            node.statements.joinToString("；") { "${it.text}=${if (it.answer) "对" else "错"}" },
            explanation = node.explanation
        )
        is MicroNode.Tfng -> make(
            "tfng",
            node.prompt.ifBlank { "判断三态(对/错/未提及)" },
            node.statements.joinToString("；") { s ->
                val label = when (s.answer) { "true" -> "正确"; "false" -> "错误"; else -> "未提及" }
                "${s.text}=$label"
            },
            explanation = node.explanation
        )
        is MicroNode.MatchHeadings -> make(
            "match_headings",
            node.prompt.ifBlank { "段落标题匹配" },
            node.paragraphs.joinToString("；") { "${it.label.ifBlank { "?" }}→${it.answer}" },
            explanation = node.explanation
        )
        is MicroNode.MatchInfo -> make(
            "match_info",
            node.prompt.ifBlank { "信息匹配" },
            node.statements.joinToString("；") { "${it.text}→${it.answer}" },
            explanation = node.explanation
        )
        is MicroNode.MinimalPair -> make("minimal_pair", node.prompt.ifBlank { "最小对立对：听到的是哪个？" }, node.answer, node.options, node.explanation)
        is MicroNode.IpaRead -> make("ipa_read", node.prompt.ifBlank { "音标认读：含 ${node.symbol} 音的词" }, node.answer, node.options, node.explanation)
        is MicroNode.MapLabel -> make(
            "map_label",
            node.prompt.ifBlank { "位置标注" },
            node.items.joinToString("；") { "${it.text}→${it.answer}" }
        )
        is MicroNode.NoteComplete -> make(
            "note_complete",
            listOf(node.title.trim(), node.text.trim()).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "笔记填空" },
            node.answers.joinToString(" | ")
        )
        is MicroNode.MatchSentenceEndings -> make(
            "match_sentence_endings",
            node.prompt.ifBlank { "句尾配对" },
            node.stems.joinToString("；") { "${it.text}→${it.answer}" }
        )
        is MicroNode.SummaryComplete -> make(
            "summary_complete",
            listOf(node.prompt.trim(), node.text.trim()).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "摘要填空" },
            node.answers.joinToString(" | ")
        )
        is MicroNode.ShortAnswer -> make(
            "short_answer",
            node.prompt.ifBlank { "篇章简答" },
            node.questions.joinToString("；") { "${it.q}→${it.answer}" }
        )
        is MicroNode.ClozeSelect -> make(
            "cloze_select",
            node.text,
            node.blanks.mapNotNull { it.answer.trim().ifBlank { null } }.joinToString(" | "),
            options = if (node.blanks.size == 1) node.blanks.first().options else emptyList(),
            explanation = node.explanation
        )
        is MicroNode.ClozeDrag -> make(
            "cloze_drag",
            node.text,
            node.answers.mapNotNull { it.trim().ifBlank { null } }.joinToString(" | "),
            options = if (node.answers.size == 1) node.bank else emptyList(),
            explanation = node.explanation
        )
        is MicroNode.OpenCloze -> make(
            "open_cloze",
            listOf(node.prompt.trim(), node.text.trim()).filter { it.isNotBlank() }.joinToString("\n").ifBlank { "完形填空" },
            node.answers.mapNotNull { it.trim().ifBlank { null } }.joinToString(" | "),
            explanation = node.explanation
        )
        is MicroNode.ListenFill -> make(
            "listen_fill",
            "听力填空：${node.text}",
            node.answers.mapNotNull { it.trim().ifBlank { null } }.joinToString(" | "),
            options = if (node.answers.size == 1) node.bank else emptyList(),
            explanation = node.explanation
        )
        is MicroNode.ListenCloze -> make(
            "listen_cloze",
            "听力填空（打字）：${node.text}",
            node.answers.mapNotNull { it.trim().ifBlank { null } }.joinToString(" | "),
            explanation = node.explanation
        )
        is MicroNode.FillTable -> make(
            "fill_table",
            (node.title.ifBlank { "表格填空" } + "\n" + node.headers.joinToString(" | ")).trim(),
            node.blanks.mapNotNull { microFillTableExpected(node, it).trim().ifBlank { null } }.joinToString("；"),
            explanation = node.explanation
        )
        is MicroNode.WordFormation -> make(
            "word_formation",
            node.prompt.ifBlank { "词形转换" },
            node.items.joinToString("；") { "${it.base}(${it.target}) → ${it.answer}" },
            explanation = node.explanation
        )
        is MicroNode.Tokens -> make("tokens", "点选找错：${node.text}", node.correct, explanation = node.explanation)
        is MicroNode.Order -> make(
            "order",
            node.prompt.ifBlank { "连词成句：${node.items.joinToString(" / ")}" },
            node.answer,
            explanation = node.explanation
        )
        is MicroNode.Match -> make(
            "match",
            node.prompt.ifBlank { "连线配对" },
            node.pairs.filter { it.left.isNotBlank() && it.right.isNotBlank() }.joinToString("；") { "${it.left} → ${it.right}" },
            explanation = node.explanation
        )
        is MicroNode.Categorize -> make(
            "categorize",
            node.prompt.ifBlank { "归类" },
            node.categories.joinToString("；") { "${it.name}: ${it.items.joinToString("、")}" },
            explanation = node.explanation
        )
        is MicroNode.HighlightSpan -> make(
            "highlight_span",
            "${node.prompt.ifBlank { "篇章框选" }}\n${node.text}",
            node.answers.joinToString("、"),
            explanation = node.explanation
        )
        is MicroNode.SentenceDiagram -> make(
            "sentence_diagram",
            node.prompt.ifBlank { "句子成分：${node.sentence}" },
            node.items.joinToString("；") { "${it.text}=${it.label}" },
            explanation = node.explanation
        )
        is MicroNode.StressMark -> make(
            "stress_mark",
            "单词重音：${node.word}（点出重读音节）",
            node.syllables.getOrNull(node.stress - 1).orEmpty(),
            options = node.syllables,
            explanation = node.explanation
        )
        is MicroNode.ReorderParagraph -> make(
            "reorder_paragraph",
            node.prompt.ifBlank { "语篇排序" },
            node.sentences.joinToString(" | "),
            explanation = node.explanation
        )
        is MicroNode.RankOrder -> make(
            "rank_order",
            node.prompt.ifBlank { "程度排序（${node.from} → ${node.to}）" },
            node.items.joinToString(" | "),
            explanation = node.explanation
        )
        is MicroNode.WordSearch -> make(
            "word_search",
            node.prompt.ifBlank { "单词找词" },
            node.words.joinToString("、"),
            explanation = node.explanation
        )
        is MicroNode.ProofParagraph -> make(
            "proof_paragraph",
            node.prompt.ifBlank { "短文改错" } + "\n" + node.lines.joinToString("\n") { it.text },
            node.lines
                .filter { it.answer.isNotBlank() && it.answer.trim() != it.text.trim() }
                .joinToString("；") { "${it.text} → ${it.answer}" },
            explanation = node.explanation
        )
        else -> null
    }
}

// 答错讲解：为「选择/判断/匹配」类可判分微元生成「参考答案」展示文本（多行）；这些题型渲染器只给绿/红、无文字正解。
// 其它题型返回 null（它们要么已内联讲解、要么正解即其作答本身，无需重复展示）。
internal fun microRevealAnswerText(node: MicroNode): String? = when (node) {
    is MicroNode.Choice -> node.answer.trim().ifBlank { null }
    is MicroNode.AudioChoice -> node.answer.trim().ifBlank { null }
    is MicroNode.TrueFalse -> node.statements
        .filter { it.text.isNotBlank() }
        .joinToString("\n") { "· ${it.text} —— ${if (it.answer) "对" else "错"}" }
        .ifBlank { null }
    is MicroNode.Tfng -> node.statements
        .filter { it.text.isNotBlank() }
        .joinToString("\n") { s ->
            val label = when (s.answer) { "true" -> "正确"; "false" -> "错误"; else -> "未提及" }
            "· ${s.text} —— $label"
        }
        .ifBlank { null }
    is MicroNode.Match -> node.pairs
        .filter { it.left.isNotBlank() && it.right.isNotBlank() }
        .joinToString("\n") { "· ${it.left} → ${it.right}" }
        .ifBlank { null }
    is MicroNode.Categorize -> node.categories
        .filter { it.name.isNotBlank() }
        .joinToString("\n") { "· ${it.name}：${it.items.joinToString("、")}" }
        .ifBlank { null }
    is MicroNode.MatchHeadings -> node.paragraphs
        .filter { it.answer.isNotBlank() }
        .joinToString("\n") { "· ${it.label.ifBlank { "?" }} → ${it.answer}" }
        .ifBlank { null }
    is MicroNode.MatchInfo -> node.statements
        .filter { it.text.isNotBlank() && it.answer.isNotBlank() }
        .joinToString("\n") { "· ${it.text} → ${it.answer}" }
        .ifBlank { null }
    is MicroNode.MinimalPair -> node.answer.trim().ifBlank { null }
    is MicroNode.IpaRead -> node.answer.trim().ifBlank { null }
    is MicroNode.MapLabel -> node.items
        .filter { it.text.isNotBlank() && it.answer.isNotBlank() }
        .joinToString("\n") { "· ${it.text} → ${it.answer}" }
        .ifBlank { null }
    is MicroNode.MatchSentenceEndings -> node.stems
        .filter { it.text.isNotBlank() && it.answer.isNotBlank() }
        .joinToString("\n") { "· ${it.text} → ${it.answer}" }
        .ifBlank { null }
    else -> null
}

// 模型给的答错讲解：取各可判分题型的 explanation（模型未给则为空，交给兜底）。现所有可判分题型都带此字段。
internal fun microRevealExplanation(node: MicroNode): String = when (node) {
    is MicroNode.Choice -> node.explanation
    is MicroNode.AudioChoice -> node.explanation
    is MicroNode.TrueFalse -> node.explanation
    is MicroNode.Tfng -> node.explanation
    is MicroNode.Match -> node.explanation
    is MicroNode.Categorize -> node.explanation
    is MicroNode.MatchHeadings -> node.explanation
    is MicroNode.MatchInfo -> node.explanation
    is MicroNode.MinimalPair -> node.explanation
    is MicroNode.IpaRead -> node.explanation
    is MicroNode.Tokens -> node.explanation
    is MicroNode.Order -> node.explanation
    is MicroNode.OddOneOut -> node.explanation
    is MicroNode.RankOrder -> node.explanation
    is MicroNode.ErrorCorrection -> node.explanation
    is MicroNode.DialogueComplete -> node.explanation
    is MicroNode.HighlightSpan -> node.explanation
    is MicroNode.Input -> node.explanation
    is MicroNode.Dictation -> node.explanation
    is MicroNode.ClozeDrag -> node.explanation
    is MicroNode.SentenceDiagram -> node.explanation
    is MicroNode.WordScramble -> node.explanation
    is MicroNode.FillTable -> node.explanation
    is MicroNode.ClozeSelect -> node.explanation
    is MicroNode.ListenFill -> node.explanation
    is MicroNode.WordFormation -> node.explanation
    is MicroNode.StressMark -> node.explanation
    is MicroNode.ReorderParagraph -> node.explanation
    is MicroNode.SpellingBee -> node.explanation
    is MicroNode.SentenceTransform -> node.explanation
    is MicroNode.OpenCloze -> node.explanation
    is MicroNode.Translate -> node.explanation
    is MicroNode.ListenCloze -> node.explanation
    is MicroNode.WordSearch -> node.explanation
    is MicroNode.Hangman -> node.explanation
    is MicroNode.ProofParagraph -> node.explanation
    is MicroNode.MapLabel -> node.explanation
    is MicroNode.NoteComplete -> node.explanation
    is MicroNode.MatchSentenceEndings -> node.explanation
    is MicroNode.SummaryComplete -> node.explanation
    is MicroNode.ShortAnswer -> node.explanation
    else -> ""
}.trim()

// 「核对答案」后展示的解析：模型给了 explanation 就用它；否则对可判分题型给出按题型定制的通用解析兜底，
// 保证每张练习卡核对后都有解析（修复「生成练习卡片后核对答案没有解析」）。非可判分微元返回空（不展示）。
internal fun microRevealExplanationText(node: MicroNode): String {
    val provided = microRevealExplanation(node)
    if (provided.isNotBlank()) return provided
    return microRevealFallbackExplanation(node)
}

// 按题型定制的解析兜底（模型没给 explanation 或该题型无此字段时用）：给一句可操作的中文核对指引。
internal fun microRevealFallbackExplanation(node: MicroNode): String = when (node) {
    is MicroNode.Choice, is MicroNode.AudioChoice, is MicroNode.MinimalPair,
    is MicroNode.IpaRead, is MicroNode.DialogueComplete, is MicroNode.OddOneOut ->
        "对照绿色高亮的正确项：先抓题干/听音要求，再逐一排除与之不符的干扰项。"
    is MicroNode.TrueFalse ->
        "逐句回到材料或常识核对：与事实一致判「对」，相悖判「错」。"
    is MicroNode.Tfng ->
        "true=材料支持、false=材料相悖、not_given=材料未提及；关键在区分「被否定(false)」与「未涉及(not_given)」。"
    is MicroNode.Match, is MicroNode.Categorize, is MicroNode.MatchHeadings,
    is MicroNode.MatchInfo, is MicroNode.MatchSentenceEndings, is MicroNode.MapLabel ->
        "对照正确配对：抓住每一项的关键特征（词义/主旨/定位线索）再归位。"
    is MicroNode.Order, is MicroNode.ReorderParagraph, is MicroNode.RankOrder ->
        "对照正确顺序：顺着时间、逻辑或程度线索，检查前后衔接与指代。"
    is MicroNode.Tokens, is MicroNode.ErrorCorrection, is MicroNode.ProofParagraph ->
        "对照正确句：常见错点在时态、搭配、单复数、冠词与词序，逐处比对。"
    is MicroNode.ClozeDrag, is MicroNode.ClozeSelect, is MicroNode.OpenCloze,
    is MicroNode.ListenFill, is MicroNode.ListenCloze, is MicroNode.NoteComplete,
    is MicroNode.SummaryComplete, is MicroNode.FillTable, is MicroNode.WordFormation ->
        "对照参考答案：看空格前后的搭配、词性与时态，判断该填哪个词及其正确词形。"
    is MicroNode.SpellingBee, is MicroNode.WordScramble, is MicroNode.WordSearch,
    is MicroNode.Hangman ->
        "对照正确拼写：逐字母核对，留意易错字母与词形变化。"
    is MicroNode.Dictation ->
        "对照原句逐词核对听写，注意连读、弱读处容易漏听或拼错的词。"
    is MicroNode.SentenceTransform ->
        "对照标准答案：确认按要求完成时态/语态/句式转换，且原意保持不变。"
    is MicroNode.Translate ->
        "对照参考译文：注意时态、词序与地道表达；意思对等的其它译法同样正确。"
    is MicroNode.SentenceDiagram ->
        "对照正确成分标注：先定位主语和谓语，再判断宾语、状语等其余成分。"
    is MicroNode.StressMark ->
        "对照正确重读音节：按构词与词性规律确定多音节词的重音位置。"
    is MicroNode.HighlightSpan ->
        "对照高亮的正确词：逐一核对每个词是否真的符合题干条件，不漏选不多选。"
    is MicroNode.Input, is MicroNode.ShortAnswer ->
        "对照参考答案：核对关键信息是否齐全、表达是否准确到位。"
    else -> ""
}.trim()

// 单词找词判分：found（用户已找到的词）须覆盖全部 words（归一：仅字母数字、忽略大小写）。
internal fun microWordSearchCorrect(found: Set<String>, words: List<String>): Boolean {
    if (words.isEmpty()) return false
    fun norm(s: String) = s.filter { it.isLetterOrDigit() }.uppercase()
    val f = found.map { norm(it) }.toSet()
    return words.all { norm(it) in f }
}

// 两格是否成直线（横/竖/斜），是则返回沿线全部格坐标（含首尾）；否则 null。
internal fun microWordSearchLineCells(r1: Int, c1: Int, r2: Int, c2: Int): List<Pair<Int, Int>>? {
    if (r1 == r2 && c1 == c2) return null
    val dr = r2 - r1
    val dc = c2 - c1
    val adr = if (dr < 0) -dr else dr
    val adc = if (dc < 0) -dc else dc
    if (!(dr == 0 || dc == 0 || adr == adc)) return null
    val stepR = if (dr > 0) 1 else if (dr < 0) -1 else 0
    val stepC = if (dc > 0) 1 else if (dc < 0) -1 else 0
    val len = (if (adr > adc) adr else adc) + 1
    return (0 until len).map { (r1 + it * stepR) to (c1 + it * stepC) }
}

// 在 grid 里沿 8 方向找 word，返回首个匹配路径（格坐标）；找不到返回 null。用于高亮已找到/揭示的词。
internal fun microWordSearchPath(grid: List<List<String>>, word: String): List<Pair<Int, Int>>? {
    val target = word.filter { it.isLetterOrDigit() }.uppercase()
    if (target.length < 2 || grid.isEmpty()) return null
    val dirs = listOf(0 to 1, 1 to 0, 1 to 1, 1 to -1, 0 to -1, -1 to 0, -1 to -1, -1 to 1)
    for (r in grid.indices) {
        for (c in grid[r].indices) {
            for ((dr, dc) in dirs) {
                val cells = ArrayList<Pair<Int, Int>>(target.length)
                val sb = StringBuilder()
                var ok = true
                for (k in target.indices) {
                    val cell = grid.getOrNull(r + k * dr)?.getOrNull(c + k * dc)
                    if (cell.isNullOrEmpty()) { ok = false; break }
                    sb.append(cell.uppercase())
                    cells.add((r + k * dr) to (c + k * dc))
                }
                if (ok && sb.toString() == target) return cells
            }
        }
    }
    return null
}

// 猜词判分：word 的所有字母都已被猜中（guessed 含之）且错误数 < maxWrong（生命未耗尽）才算赢。
internal fun microHangmanCorrect(guessed: Set<Char>, word: String, maxWrong: Int): Boolean {
    val letters = word.uppercase().filter { it.isLetter() }.toSet()
    if (letters.isEmpty()) return false
    val g = guessed.map { it.uppercaseChar() }.toSet()
    val wrong = g.count { it !in letters }
    return wrong < maxWrong.coerceAtLeast(1) && letters.all { it in g }
}

// 短文改错判分：每行「用户当前文本(未改则为原文)」需与该行正确版(answer 缺省则同 text)归一相等；全部行对才算对。复用统一文本判分。
internal fun microProofParagraphCorrect(edits: Map<Int, String>, lines: List<MicroProofLine>): Boolean {
    if (lines.isEmpty()) return false
    return lines.indices.all { i ->
        val correct = lines[i].answer.ifBlank { lines[i].text }
        microTypedAnswerCorrect(edits[i] ?: lines[i].text, correct, emptyList())
    }
}

// 表格填空的某挖空正确答案：rows[coord] 即完整表中该格的正确值。
internal fun microFillTableExpected(node: MicroNode.FillTable, coord: MicroCellCoord): String =
    node.rows.getOrNull(coord.row)?.getOrNull(coord.col).orEmpty()

// 表格填空判分：每个挖空所填词需与该格完整答案归一化相等；全部填对才算对（未填视为未完成→错）。
internal fun microFillTableCorrect(filled: Map<Int, String>, node: MicroNode.FillTable): Boolean {
    if (node.blanks.isEmpty()) return false
    return node.blanks.indices.all { i ->
        val expected = microFillTableExpected(node, node.blanks[i])
        val f = filled[i]
        expected.isNotBlank() && f != null &&
            normalizeAgentShortAnswer(f).equals(normalizeAgentShortAnswer(expected), ignoreCase = true)
    }
}

// 完形选择判分：每个空所选项需与该空 answer 归一化相等；全部选对才算对（未选视为未完成→错）。
internal fun microClozeSelectCorrect(selected: Map<Int, Int>, blanks: List<MicroClozeSelectBlank>): Boolean {
    val gradable = blanks.filter { it.options.size >= 2 && it.answer.isNotBlank() }
    if (gradable.isEmpty()) return false
    return blanks.indices.all { i ->
        val b = blanks[i]
        if (b.options.size < 2 || b.answer.isBlank()) return@all true // 非可判空位跳过
        val sel = selected[i]?.let { b.options.getOrNull(it) }
        sel != null && normalizeAgentShortAnswer(sel).equals(normalizeAgentShortAnswer(b.answer), ignoreCase = true)
    }
}

// 词形转换判分：每个 item 所填词需与其 answer 归一化相等；全部填对才算对（未填视为未完成→错）。
internal fun microWordFormationCorrect(filled: Map<Int, String>, items: List<MicroWordFormItem>): Boolean {
    if (items.isEmpty()) return false
    return items.indices.all { i ->
        val f = filled[i]
        f != null && normalizeAgentShortAnswer(f).equals(normalizeAgentShortAnswer(items[i].answer), ignoreCase = true)
    }
}

// 单词重音判分：所选音节下标(0基)需等于重读音节 stress-1；stress 越界或未选 → 错。
internal fun microStressMarkCorrect(selectedIndex: Int?, node: MicroNode.StressMark): Boolean {
    val target = node.stress - 1
    if (node.syllables.size < 2 || target !in node.syllables.indices) return false
    return selectedIndex != null && selectedIndex == target
}

// 语篇排序判分：所排句子（ordered）需与正确顺序 sentences 逐句归一化相等；句数不符或未排满 → 错。
internal fun microReorderParagraphCorrect(ordered: List<String>, sentences: List<String>): Boolean {
    if (sentences.size < 2 || ordered.size != sentences.size) return false
    return sentences.indices.all { i ->
        normalizeAgentShortAnswer(ordered[i]).equals(normalizeAgentShortAnswer(sentences[i]), ignoreCase = true)
    }
}

// 程度排序判分：所排项（ordered）需与正确的低→高顺序 items 逐项归一化相等；项数不符或未排满 → 错（≥3 项）。
internal fun microRankOrderCorrect(ordered: List<String>, items: List<String>): Boolean {
    if (items.size < 3 || ordered.size != items.size) return false
    return items.indices.all { i ->
        normalizeAgentShortAnswer(ordered[i]).equals(normalizeAgentShortAnswer(items[i]), ignoreCase = true)
    }
}

// 听音拼写判分：把用户打字与目标 word 用同一套拼写归一（小写 + 去非字母数字，与字母重组一致）后比对；空 → 错。
internal fun microSpellingCorrect(typed: String, word: String): Boolean {
    if (word.isBlank() || typed.isBlank()) return false
    fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")
    val target = norm(word)
    return target.isNotEmpty() && norm(typed) == target
}

// 文本作答统一判分：把用户答案与 answer + accept 逐一归一（展开缩写→小写→压空格→去中英文首尾/句末标点）后比对，命中任一即对；空 → 错。
// sentence_transform / error_correction / translate 共用（英文用例不含中文标点，行为与原先一致）。
internal fun microTypedAnswerCorrect(typed: String, answer: String, accept: List<String>): Boolean {
    if (typed.isBlank()) return false
    fun norm(s: String): String =
        normalizeAgentShortAnswer(s).lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', '!', '?', ',', ';', ':', '"', '。', '！', '？', '，', '；', '：', '、')
    val t = norm(typed)
    if (t.isEmpty()) return false
    return (listOf(answer) + accept).any { it.isNotBlank() && norm(it) == t }
}

// 句型转换判分：复用统一文本作答判分（answer + accept 归一比对）。
internal fun microSentenceTransformCorrect(typed: String, answer: String, accept: List<String>): Boolean =
    microTypedAnswerCorrect(typed, answer, accept)

// 开放式填空判分：逐空把用户所填与该空答案归一比对；答案可用 "a/b" 列多个可接受写法，命中任一即该空对；全部空对才算对；未填/空 → 错。
internal fun microOpenClozeCorrect(filled: Map<Int, String>, answers: List<String>): Boolean {
    if (answers.isEmpty()) return false
    return answers.indices.all { i ->
        val expected = answers[i]
        val f = filled[i]
        if (expected.isBlank() || f.isNullOrBlank()) return@all false
        val got = normalizeAgentShortAnswer(f.trim())
        expected.split('/', '|', '／', '｜')
            .map { normalizeAgentShortAnswer(it.trim()) }
            .any { it.isNotEmpty() && it.equals(got, ignoreCase = true) }
    }
}

// 翻译判分：复用统一文本作答判分；译法多样，主要靠 accept 兜住常见正确写法。
internal fun microTranslateCorrect(typed: String, answer: String, accept: List<String>): Boolean =
    microTypedAnswerCorrect(typed, answer, accept)

// 听力位置标注判分：每个地点所选位置标签须与其正确位置归一相等（与句尾配对同口径，忽略大小写等），
// 全对才算对（未选视为未完成→错）。
internal fun microMapLabelCorrect(selected: Map<Int, String>, items: List<MicroLabelItem>): Boolean {
    if (items.isEmpty()) return false
    return items.withIndex().all { (i, item) ->
        val sel = selected[i]
        item.answer.isNotBlank() && sel != null &&
            normalizeAgentShortAnswer(sel).equals(normalizeAgentShortAnswer(item.answer), ignoreCase = true)
    }
}

// 句尾配对判分：每个句子开头所选句尾须与其正确句尾归一相等，全对才算对（未选视为未完成→错）。
internal fun microSentenceEndingsCorrect(selected: Map<Int, String>, stems: List<MicroSentenceStem>): Boolean {
    if (stems.isEmpty()) return false
    return stems.withIndex().all { (i, stem) ->
        val sel = selected[i]
        stem.answer.isNotBlank() && sel != null &&
            normalizeAgentShortAnswer(sel).equals(normalizeAgentShortAnswer(stem.answer), ignoreCase = true)
    }
}

// 篇章简答判分：逐题把用户所答与该题 answer + accept 归一比对（复用统一文本作答判分），全部答对才算对（未答/空→错）。
internal fun microShortAnswerCorrect(filled: Map<Int, String>, questions: List<MicroShortAnswerItem>): Boolean {
    if (questions.isEmpty()) return false
    return questions.indices.all { i ->
        microTypedAnswerCorrect(filled[i].orEmpty(), questions[i].answer, questions[i].accept)
    }
}
