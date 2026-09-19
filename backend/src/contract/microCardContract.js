"use strict";

// 「微元卡」spike —— 验证“无固定题型 + AI 实时组合微元”的方向（A2UI 风格的扁平 primitive 列表）。
// 一张卡 = { title?, nodes: [ 微元, ... ] }。AI 按用户需求把这些微元拼成卡，端上按注册表渲染；
// 未知微元直接忽略（graceful 降级），不套任何“固定题型/固定兜底卡”。
// 注意：这是与现有 43 题型管线完全隔离的试验件，不参与线上 cardSpec 生成。

// 微元类型（保持精简；每种就是一个可复用的原子能力）。
const MICRO_NODE_TYPES = Object.freeze([
  "text",     // { type:"text", text, role?: "title"|"body"|"hint" }
  "passage",  // { type:"passage", text }  整段文本（可含 ___ 占位）
  "choice",   // { type:"choice", prompt?, options:[...], answer, multi?:bool, explanation?(答错讲解) }
  "tokens",   // { type:"tokens", text, correct?, errors?:[片段], explanation? } 点选找错
  "input",    // { type:"input", prompt?, answer?, multiline?:bool } 输入并自测
  "order",    // { type:"order", prompt?, items:[乱序项 ≥2], answer(正确顺序: a | b | c 或完整句子), explanation? } 点选排序/连词成句
  "audio",    // { type:"audio", src }
  "reveal",   // { type:"reveal", label, content } 折叠揭示
  "match",    // { type:"match", prompt?, pairs:[{left,right} ≥2], explanation?(答错讲解) } 连线匹配（右项打乱成选项池，每个左项选其对应右项）
  "categorize", // { type:"categorize", prompt?, categories:[{name, items:[...]} ≥2], explanation?(答错讲解) } 归类分桶（每项选其所属类别）
  "flashcard", // { type:"flashcard", prompt?, cards:[{front, back} ≥1] } 翻卡（点击翻面；词↔义/问↔答，纯学习展示不判分）
  "dictation", // { type:"dictation", text, hint? } 听写（端上 TTS 朗读 text，用户打字听写，diff 判分）
  "audio_choice", // { type:"audio_choice", audioText, prompt?, options:[≥2], answer, explanation?(答错讲解) } 听辨（端上 TTS 朗读 audioText，选所听到的项）
  "speak_score", // { type:"speak_score", text? , prompts?:[...] } 跟读评分（用户录音朗读，AI 评发音/流利度；自带评分流程、不走统一判分）
  "cloze_drag", // { type:"cloze_drag", text(含 ___ 空), bank:[词库 ≥2], answers:[每空正确词，顺序对应 ___] } 点选填空（从词库点词依次填入空位）
  "roleplay_turn", // { type:"roleplay_turn", scenario(AI 扮演谁+情境), opening?(AI 开场白), goal?(用户目标), turns?(建议轮数) } 对话轮（卡内多轮情景对话：用户逐句作答→AI 即时回应+恰当性提示，末尾可评分；AI 原生交互、自带流程、不走统一判分）
  "ai_hint", // { type:"ai_hint", prompt(问题/题目), hints:[由浅到深的提示 ≥1], answer(最终答案) } 渐进提示（卡住时逐条看提示，最后才揭示答案；脚手架引导、非统一判分）
  "highlight_span", // { type:"highlight_span", prompt(任务,如“选出所有动词”), text(整段文本), answers:[文本中应选中的词 ≥1], explanation? } 篇章框选（在文本里点选所有符合条件的词，全选对才算对，可判分）
  "timed_challenge", // { type:"timed_challenge", prompt(题目), options:[≥2], answer(options 之一), seconds?(倒计时,默认15) } 限时抢答（带倒计时的选择题，又快又对得分越高；自带计时/计分，不走统一判分）
  "table", // { type:"table", title?, headers:[列名 ≥1], rows:[[单元格,...] ≥1] } 表格（对比表/词形变化表/语法表等结构化展示，纯展示不判分）
  "sentence_diagram", // { type:"sentence_diagram", prompt?, sentence?, labels?:[标签池], items:[{text,label} ≥2] } 句子成分（给句中每块选其语法成分标签，全对才算对，可判分）
  "word_scramble", // { type:"word_scramble", word(目标词/短语), hint?(中文释义/提示), scrambled?:[预打乱的字母/块;缺省端上按 word 字母自动打乱] } 字母重组（把打乱的字母按序拼回目标词，拼对才算对，可判分）
  "true_false", // { type:"true_false", prompt?, statements:[{text(陈述句), answer(true|false)} ≥1], explanation?(答错讲解) } 判断对错（逐句判断真/假，全部判对才算对，可判分）
  "fill_table", // { type:"fill_table", title?, headers:[列名 ≥1], rows:[[完整正确行的单元格,...] ≥1], blanks:[[行,列] 要挖空的坐标(0基) ≥1], bank?:[候选词;缺省用挖空答案] } 表格填空（表格挖空后从词库点词填回，全填对才算对，可判分）
  "pairs_memory", // { type:"pairs_memory", prompt?, pairs:[{left,right} ≥2] } 记忆翻牌配对（盖牌翻两张找配对，自带步数/计分的记忆小游戏，纯游戏不判分）
  "cloze_select", // { type:"cloze_select", text(用 ___ 标每个空), blanks:[{options:[≥2], answer(须∈options)} 顺序对应 ___] } 完形选择（篇章多空、每空各有独立选项，全选对才算对，可判分）
  "listen_fill", // { type:"listen_fill", audioText(完整句子,朗读用), text(展示句,用 ___ 标空), bank:[词库 ≥2], answers:[每空正确词,顺序对应 ___] } 听力填空（听句子把缺词从词库点填，全对才算对，可判分）
  "word_formation", // { type:"word_formation", prompt?, items:[{base(原词/词根), target(目标词性/提示), answer(派生词)} ≥1], bank?:[候选派生词;缺省用各 answer] } 词形转换（给原词+目标词性，从词库点填派生词，全对才算对，可判分）
  "timeline", // { type:"timeline", title?, events:[{time(时间/阶段), title(事件), detail?(描述)} ≥2] } 时间线（按时间/阶段竖向展示事件，纯展示不判分）
  "stress_mark", // { type:"stress_mark", word?, syllables:[音节 ≥2], stress(重读音节序号,1基) } 单词重音（在拆分音节里点出重读音节，选对才算对，可判分）
  "reorder_paragraph", // { type:"reorder_paragraph", prompt?, sentences:[按正确顺序的整句 ≥2,须互不相同] } 语篇排序（把打乱的句子排成连贯段落/对话，全序对才算对，可判分）
  "odd_one_out", // { type:"odd_one_out", prompt?, items:[一组词/项 ≥3], answer(须∈items,那个异类), explanation? } 选异类（从一组里选出不同类的那个，选对才算对，可判分）
  "rank_order", // { type:"rank_order", prompt?, items:[按正确顺序(低→高)的项 ≥3,须互不相同], from?(低端刻度), to?(高端刻度), explanation? } 程度排序（按标尺从低到高排列，全序对才算对，可判分）
  "spelling_bee", // { type:"spelling_bee", word(要拼写的目标单词/短语 ≥2字母), hint?(中文释义/提示), example?(例句,展示时自动隐去该词作语境) } 听音拼写（听发音+看释义把单词逐字母拼出并打字，拼对才算对，可判分）
  "sentence_transform", // { type:"sentence_transform", prompt(转换要求,如「改为被动语态」), source(原句), answer(转换后标准答案), accept?:[其他可接受答案], hint? } 句型转换（按要求把原句改写，打字作答，与标准/可接受答案归一比对，可判分）
  "open_cloze", // { type:"open_cloze", prompt?(填空要求/提示), text(用 ___ 标每个空), answers:[每空答案,顺序对应 ___;可用 "a/b" 列多个可接受写法] } 开放式填空（无选项/无词库，逐空打字填出正确词，全对才算对，可判分）
  "translate", // { type:"translate", direction?("zh2en"默认|"en2zh"), source(要翻译的原句), answer(参考译文), accept?:[其他可接受译文], hint? } 翻译（把 source 译到目标语言，打字作答，与参考/可接受译文归一比对，可判分）
  "tfng", // { type:"tfng", prompt?(依据/材料说明), statements:[{text(陈述), answer("true"|"false"|"not_given")} ≥1], explanation?(答错讲解) } 判断三态（对/错/未提及：逐句判断是否与材料相符，全部判对才算对，可判分）
  "match_headings", // { type:"match_headings", prompt?(任务说明), paragraphs:[{label(A/B/C..), text(段落内容), answer(该段正确标题,须∈headings)} ≥2], headings:[标题库,建议多于段落数含干扰项 ≥2], explanation?(答错讲解) } IELTS 段落标题匹配（为每段从标题库点选最合适标题，全对才算对，可判分）
  "match_info", // { type:"match_info", prompt?(任务说明), options:[段落标签,如 "A"/"B"/"C" ≥2], statements:[{text(某条信息/说法), answer(其所属段落标签,须∈options)} ≥1], explanation?(答错讲解) } IELTS 信息匹配（判断每条信息出现在哪个段落，全对才算对；同一段落可被多条选中，可判分）
  "listen_cloze", // { type:"listen_cloze", audioText(完整句/短文,端上 TTS 朗读), text(展示句,用 ___ 标缺词), answers:[每空答案,顺序对应 ___;可用 "a/b" 列多个可接受写法] } 听力填空·打字版（听句子把缺词逐空打字填出，无词库，全对才算对，可判分）
  "error_correction", // { type:"error_correction", prompt?(提示/错误类型), sentence(含一处错误的句子), answer(改正后的整句), accept?:[其他可接受改法], explanation?(错误说明) } 句子改错（找出并改正句中错误，打字作答，与标准/可接受答案归一比对，可判分）
  "dialogue_complete", // { type:"dialogue_complete", prompt?(情景说明), turns:[{speaker, text} ≥1], options:[候选回应 ≥2], answer(须∈options), explanation? } 补全对话（给情景对话，从选项选出最合适的回应/缺句，选对才算对，可判分）
  "word_search", // { type:"word_search", prompt?, grid:[[单个大写字母,...] 每行等长 ≥2x2], words:[要找的词 ≥1,每词须能在 grid 里沿直线(8方向)找到] } 单词找词（字母网格里找目标词，点首/尾字母格连线判定，找齐才算对，可判分）
  "hangman", // { type:"hangman", word(目标词 ≥2字母), hint?(释义/提示), maxWrong?(允许错误次数,默认6) } 猜词游戏（逐个猜字母，用完错误次数前拼出全词才算对，可判分）
  "proof_paragraph", // { type:"proof_paragraph", prompt?, lines:[{text(该行原文,可能含1处错), answer?(改正后的该行;无错则省略/同 text), note?(错误说明)} ≥1,至少1行有错] } 短文改错（逐行改正错误，全部行正确才算对，可判分）
  "writing", // { type:"writing", prompt(写作任务:题目/词数/要点要求), reference?(评分参考:要点提纲/范文要点,批改时喂给 AI) } 自由写作（卡内输入正文，AI 按内容/组织/词汇/语法四维批改评分；自带评分流程、不走统一判分）
  "monologue", // { type:"monologue", prompt(口语任务), scene?(图片/情景的文字描述), points?:[要点提示] } 看图说话/话题独白（用户录音开放作答、AI 评分；自带流程、不走统一判分）
  "shadowing", // { type:"shadowing", text(示范句,端上 TTS 朗读), translation?(中文对照) } 影子跟读（先听示范再跟读录音、AI 评发音/流利度；自带流程、不走统一判分）
  "minimal_pair", // { type:"minimal_pair", prompt?, audioText(所朗读的词,即 answer 那个词), options:[近音词 ≥2], answer(须∈options), ipa?:[与 options 按位对齐的音标], explanation? } 最小对立对听辨（听音从近音词里选，选对才算对，可判分）
  "ipa_read", // { type:"ipa_read", prompt?, symbol(IPA 音标,如 /iː/), example?(例词,端上 TTS 发音), options:[候选词 ≥2], answer(须∈options,含该音的词), explanation? } 音标认读（认 IPA 选含该音的词，选对才算对，可判分）
  "sound_link", // { type:"sound_link", text(整句,端上 TTS 朗读), marks?:[连读/弱读片段], note?(发音要点讲解) } 连读/弱读/语调（展示+听，纯学习不判分）
  "map_label", // { type:"map_label", prompt?, audioText(朗读的定位描述), layout?(地图/平面的文字描述), items:[{text(地点), answer(其位置标签,须∈options)} ≥1], options:[位置标签 ≥2] } 听力位置标注（雅思 Part2 风格，全对才算对，可判分）
  "note_complete", // { type:"note_complete", audioText(长句/短文,端上 TTS 朗读), title?(笔记标题), text(含 ___ 空位的笔记提纲), answers:[每空答案,顺序对应 ___;可用 "a/b"] } 长音频笔记填空（听后逐空打字，全对才算对，可判分）
  "match_sentence_endings", // { type:"match_sentence_endings", prompt?, stems:[{text(句子开头), answer(其正确句尾,须∈endings)} ≥1], endings:[句尾选项池,建议多于句子、含干扰 ≥2] } IELTS 句尾配对（全对才算对，可判分）
  "summary_complete", // { type:"summary_complete", prompt?, text(含 ___ 的摘要/流程图), bank:[词库 ≥2,建议多于空数含干扰], answers:[每空答案(须∈bank),顺序对应 ___] } 摘要/流程图填空（跨篇选词，全对才算对，可判分）
  "short_answer", // { type:"short_answer", prompt?, questions:[{q(题干), answer(参考答案), accept?:[其他可接受写法]} ≥1] } 篇章简答（逐题打字简答、归一比对，全对才算对，可判分）
  "guided_writing" // { type:"guided_writing", prompt(写作要求), steps?:[{label(阶段名), hint?(该阶段提示)}], reference?(评分参考) } 结构化引导写作（提纲→段落→成文脚手架 + AI 四维评分；自带流程、不走统一判分）
]);
const MICRO_NODE_TYPE_SET = new Set(MICRO_NODE_TYPES);

// 可作答/判分的微元（端上挂统一“核对答案”）。
const GRADABLE_MICRO_NODE_TYPES = Object.freeze(["choice", "tokens", "input", "order", "match", "categorize", "dictation", "audio_choice", "cloze_drag", "highlight_span", "sentence_diagram", "word_scramble", "true_false", "fill_table", "cloze_select", "listen_fill", "word_formation", "stress_mark", "reorder_paragraph", "odd_one_out", "rank_order", "spelling_bee", "sentence_transform", "open_cloze", "translate", "tfng", "listen_cloze", "error_correction", "dialogue_complete", "word_search", "hangman", "proof_paragraph", "match_headings", "match_info", "minimal_pair", "ipa_read", "map_label", "note_complete", "match_sentence_endings", "summary_complete", "short_answer"]);

function isKnownMicroNode(type) {
  return MICRO_NODE_TYPE_SET.has(String(type || "").trim().toLowerCase());
}

// 校验 + graceful 清洗：丢弃未知/空微元，仍返回“能渲染多少渲染多少”的卡，绝不套固定兜底模板。
function sanitizeMicroCard(card) {
  const src = card && typeof card === "object" ? card : {};
  const nodes = Array.isArray(src.nodes) ? src.nodes : [];
  const kept = nodes
    .filter((node) => node && typeof node === "object" && isKnownMicroNode(node.type))
    .map((node) => ({ ...node, type: String(node.type).trim().toLowerCase() }));
  return { title: typeof src.title === "string" ? src.title : "", nodes: kept };
}

// 两个“AI 实时组合”示例：证明 完形 / 句子改错 都只是微元组合，而不是某个写死的题型。
const EXAMPLE_CLOZE = {
  title: "完形（微元组合）",
  nodes: [
    { type: "text", role: "hint", text: "选出每空最合适的词。" },
    { type: "passage", text: "I ___ to school yesterday and ___ my homework." },
    { type: "choice", prompt: "空1", options: ["go", "went", "gone"], answer: "went" },
    { type: "choice", prompt: "空2", options: ["do", "did", "done"], answer: "did" }
  ]
};

const EXAMPLE_CORRECTION = {
  title: "句子改错（微元组合）",
  nodes: [
    { type: "text", role: "hint", text: "点出有错的词，再核对。" },
    { type: "tokens", text: "She go to school yesterday.", correct: "She went to school yesterday.", explanation: "过去时间状语用一般过去时 went" }
  ]
};

module.exports = {
  MICRO_NODE_TYPES,
  MICRO_NODE_TYPE_SET,
  GRADABLE_MICRO_NODE_TYPES,
  isKnownMicroNode,
  sanitizeMicroCard,
  EXAMPLE_CLOZE,
  EXAMPLE_CORRECTION
};
