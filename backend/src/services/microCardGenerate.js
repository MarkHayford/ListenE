"use strict";

// 微元卡实时生成 + 自修复闭环（吸收 Stem 的 AI-in-the-loop 精华，但全在自建链路里）：
//   prompt(由微元清单自动生成) → 模型出 JSON → 校验 → 有错就把校验报告回灌让模型自修 → 重试
//   → 仍不行则 graceful 返回“能渲染的合法微元”（绝不套固定题型/固定兜底卡）。
// callModel 注入式（便于单测）；线上用 callModel = (messages) => callMimoText(messages)（已返回解析后的 JSON）。

const { MICRO_NODE_TYPES } = require("../contract/microCardContract");
const { validateMicroCard, renderReport } = require("../contract/microCardValidate");
const { callMimoText } = require("./mimoCore");
const { clampAbility, cefrToAbility, recommendDifficulty, abilityToCefr } = require("./learnerModel");
const { recordMicroGeneration } = require("./metrics");

// 从统一用户模型的六技能能力取一个总体能力（present 技能的平均），作为无显式 ability/level 时的兜底。
// 主链路 /agent/chat[/stream] 只带 userModel（不带顶层 ability/level），此前难度自适应因此形同虚设；
// 这里把 userModel.abilities 接进来，让自适应真正生效。
function abilityFromUserModel(userModel) {
  const abilities = userModel && typeof userModel === "object" && userModel.abilities && typeof userModel.abilities === "object"
    ? userModel.abilities
    : null;
  if (!abilities) return null;
  const vals = Object.values(abilities).map((v) => Number(v)).filter((v) => Number.isFinite(v));
  if (!vals.length) return null;
  return clampAbility(vals.reduce((a, v) => a + v, 0) / vals.length);
}

// 自适应难度：把请求里的学习者能力(ability 0..100)或水平(level CEFR)换成给生成器的一句难度引导；
// 优先级：显式 ability > 显式 level > 统一用户模型 userModel.abilities 平均。
// 目标难度取“最优挑战”(略低于能力，约 75% 正确率)。无任何能力信息则返回空串（不改变原行为）。
function difficultyHintFromBody(body) {
  const b = body && typeof body === "object" ? body : {};
  let ability = null;
  if (b.ability != null && Number.isFinite(Number(b.ability))) ability = clampAbility(b.ability);
  else if (b.level) ability = cefrToAbility(b.level);
  else ability = abilityFromUserModel(b.userModel);
  if (ability == null) return "";
  const targetBand = abilityToCefr(recommendDifficulty(ability));
  return `学习者当前英语水平约 ${abilityToCefr(ability)}（CEFR）。请把这张卡的题目难度控制在 ${targetBand} 左右——略低于其能力、目标正确率约 75%：据此把控词汇量级、句子长度与语法复杂度，不要过难或过易。题材与题型仍以用户需求为准。`;
}

// 按需裁剪：微元 type → 所属技能维度（无标注 = 通用，任何请求都发）。
// 检测到请求维度时只发「通用 + 相关维度」的微元说明——输入 token 省一半以上、选型更聚焦；
// 检测不到维度则发全量（行为与裁剪前完全一致）。
const MICRO_TYPE_DIMENSIONS = Object.freeze({
  flashcard: ["词汇"],
  pairs_memory: ["词汇"],
  word_search: ["词汇"],
  hangman: ["词汇"],
  odd_one_out: ["词汇"],
  match: ["词汇"],
  word_scramble: ["词汇"],
  categorize: ["词汇", "语法"],
  rank_order: ["词汇", "语法"],
  spelling_bee: ["词汇", "听力"],
  word_formation: ["词汇", "语法"],
  fill_table: ["词汇", "语法"],
  dictation: ["听力", "词汇"],
  audio_choice: ["听力", "口语"],
  listen_fill: ["听力"],
  listen_cloze: ["听力"],
  speak_score: ["口语"],
  roleplay_turn: ["口语"],
  stress_mark: ["口语", "词汇"],
  dialogue_complete: ["口语"],
  tokens: ["语法"],
  cloze_drag: ["语法", "词汇"],
  cloze_select: ["语法", "阅读"],
  open_cloze: ["语法"],
  sentence_transform: ["语法", "写作"],
  sentence_diagram: ["语法"],
  true_false: ["语法", "阅读"],
  error_correction: ["语法", "写作"],
  proof_paragraph: ["语法", "写作"],
  highlight_span: ["语法", "阅读"],
  tfng: ["阅读"],
  match_headings: ["阅读"],
  match_info: ["阅读"],
  reorder_paragraph: ["阅读", "写作"],
  translate: ["写作"],
  writing: ["写作"],
  monologue: ["口语"],
  shadowing: ["口语", "听力"],
  minimal_pair: ["听力", "口语"],
  ipa_read: ["口语", "词汇"],
  sound_link: ["口语", "听力"],
  map_label: ["听力"],
  note_complete: ["听力"],
  match_sentence_endings: ["阅读"],
  summary_complete: ["阅读"],
  short_answer: ["阅读"],
  guided_writing: ["写作"]
});

const MICRO_DIMENSION_PATTERNS = Object.freeze({
  // 单字「听」即视为涉听力：宁可多带一组听力微元（几百 token），不可漏掉正确题型。
  "听力": /听|音频|录音|辨音|dictation|listening|\blisten\b/i,
  "口语": /口语|发音|跟读|朗读|对话|交际|陪练|重音|口试|口头|speaking|\bspeak\b|pronunciation|pronounce|roleplay|dialog/i,
  "阅读": /阅读|短文|文章|段落|雅思|ielts|not\s*given|定位|归段|读理解|reading|passage/i,
  "写作": /写作|作文|写一|邮件|书信|写信|翻译|汉译英|英译中|译成|writing|translat|essay|email/i,
  "词汇": /单词|词汇|生词|背词|记词|词义|辨析|拼写|构词|词族|派生|词形|近义|反义|短语|搭配|vocabulary|vocab|spell|\bwords?\b/i,
  "语法": /语法|时态|语态|从句|句型|改错|填空|完形|介词|冠词|代词|连词|助动词|被动|虚拟|比较级|句子成分|grammar|cloze|tense|passive/i
});

// 确定性维度检测：返回命中的技能维度 Set；一个都没命中 → null（调用方发全量）。
function detectMicroSkillDimensions(message) {
  const text = String(message || "");
  if (!text.trim()) return null;
  const hit = new Set();
  for (const [dimension, pattern] of Object.entries(MICRO_DIMENSION_PATTERNS)) {
    if (pattern.test(text)) hit.add(dimension);
  }
  return hit.size ? hit : null;
}

// 各微元的字段约定行（[type, 原文行]；顺序即输出顺序）。
const MICRO_FIELD_LINES = [
  ["text", "- text:{type,text,role?:title|body|hint}"],
  ["passage", "- passage:{type,text}"],
  ["choice", "- choice:{type,prompt?,options:[至少2项],answer(必须是 options 之一),multi?,explanation?(答错讲解:为何正解对/干扰项为何错)}"],
  ["tokens", "- tokens:{type,text(错句或含错短文),correct(完整正确句)或 errors:[错误片段],explanation?}（点选找错）"],
  ["input", "- input:{type,prompt?,answer?,multiline?,explanation?}"],
  ["order", "- order:{type,prompt?,items:[乱序项 ≥2],answer(正确顺序: a | b | c 或完整句子),explanation?}（排序/连词成句）"],
  ["match", "- match:{type,prompt?,pairs:[{left,right} ≥2],explanation?(答错讲解:配对依据)}（连线匹配：词↔义/英↔中/问↔答；端上把右项打乱成选项池，每个左项选其对应右项判分）"],
  ["categorize", "- categorize:{type,prompt?,categories:[{name,items:[...]} ≥2],explanation?(答错讲解:分类依据)}（归类分桶：如按词性/时态/正误分类；端上每个项选其所属类别判分）"],
  ["flashcard", "- flashcard:{type,prompt?,cards:[{front,back} ≥1]}（翻卡：词↔义/问↔答；点击翻面，纯学习记忆、不判分。词汇记忆优先用它）"],
  ["dictation", "- dictation:{type,text,hint?,explanation?}（听写：端上 TTS 朗读 text，用户打字听写、diff 判分。听力/拼写练习用它，text 写要听写的句子/词）"],
  ["audio_choice", "- audio_choice:{type,audioText,prompt?,options:[≥2],answer,explanation?(答错讲解:辨音要点/最小对立差异)}（听辨：端上 TTS 朗读 audioText，用户选所听到的项，如最小对立对 ship/sheep）"],
  ["speak_score", "- speak_score:{type,text?,prompts?:[...]}（跟读评分：用户录音朗读 text/prompts，AI 评发音与流利度。口语跟读练习用它）"],
  ["cloze_drag", "- cloze_drag:{type,text(用 ___ 标每个空),bank:[词库 ≥2,含正确词+干扰词],answers:[每空正确词,顺序对应 ___],explanation?}（点选填空：从词库点词依次填空，比每空 choice 更有手感）"],
  ["roleplay_turn", "- roleplay_turn:{type,scenario(AI 扮演谁+情境,如「你是咖啡店店员，我来点单」),opening?(AI 开场白/第一句),goal?(用户目标),turns?(建议轮数)}（对话轮：卡内多轮情景对话，用户逐句作答、AI 即时回应并给中文恰当性提示，末尾可评分。口语/情景交际/对话练习用它）"],
  ["ai_hint", "- ai_hint:{type,prompt(问题/题目),hints:[由浅到深的提示 ≥1],answer(最终答案)}（渐进提示：用户卡住时逐条看提示，最后才揭示答案。适合难题/易错题/需脚手架引导的题）"],
  ["highlight_span", "- highlight_span:{type,prompt(任务,如“选出所有动词/所有介词/所有拼写错误”),text(整段文本),answers:[文本中应被选中的词 ≥1],explanation?}（篇章框选：在整段文本里点选所有符合条件的词，全选对才算对。比单句 tokens 找错覆盖更广。answers 每一项都必须一字不差地取自 text（判分按 text 分词定位，text 里不存在的词会被丢弃）；且必须逐词核对确实符合 prompt 的条件——如“选出所有动词”则 answers 只能是动词、一个不多一个不少；answers 必须与 explanation 自洽：explanation 里说“不选/不是”的词绝不能出现在 answers）"],
  ["timed_challenge", "- timed_challenge:{type,prompt(题目),options:[≥2],answer(必须是 options 之一),seconds?(倒计时秒数,默认15)}（限时抢答：带倒计时的选择题，答得又快又对得分越高。适合速度/反应/巩固练习）"],
  ["table", "- table:{type,title?,headers:[列名 ≥1],rows:[[单元格,...] 每行一个数组,≥1 行]}（表格：对比表/词形变化表/语法规则表等结构化展示，纯展示不判分）"],
  ["sentence_diagram", "- sentence_diagram:{type,prompt?,sentence?(整句),labels?:[标签池,如 主语/谓语/宾语/状语],items:[{text:词或短语,label:该成分的正确标签} ≥2],explanation?}（句子成分：把句子切成若干块，给每块选其语法成分标签，全对才算对。语法/句法分析用它）"],
  ["word_scramble", "- word_scramble:{type,word(目标单词/短语),hint?(中文释义/提示),scrambled?:[可选,预打乱的字母/块],explanation?}（字母重组：把打乱的字母按序拼回目标词；拼写/单词记忆练习用它，word 写目标词，建议附 hint 释义）"],
  ["true_false", "- true_false:{type,prompt?,statements:[{text:陈述句,answer:true 或 false} ≥1],explanation?(答错讲解:判断依据)}（判断对错：逐句判断真/假。阅读理解事实核查/概念或语法正误判断用它，answer 用布尔 true/false）"],
  ["fill_table", "- fill_table:{type,title?,headers:[列名 ≥1],rows:[[完整正确行的单元格...] ≥1],blanks:[[行索引,列索引] 要挖空处(0基) ≥1],bank?:[候选词,含正确答案+干扰词],explanation?}（表格填空：给完整表格并指定挖空坐标，用户从词库点词填回。词形变化/搭配/语法表格填空用它，rows 写完整答案、blanks 指出要考的格子）"],
  ["pairs_memory", "- pairs_memory:{type,prompt?,pairs:[{left,right} ≥2]}（记忆翻牌配对：盖牌翻两张找配对(词↔义/英↔中)，专注力记忆小游戏，纯游戏不判分。词汇巩固/趣味复习用它）"],
  ["cloze_select", "- cloze_select:{type,text(用 ___ 标每个空),blanks:[{options:[≥2],answer(须是该空 options 之一)} 顺序对应 ___],explanation?}（完形选择：一段话多个空，每个空各有自己的一组选项，逐空点选。经典完形填空/单空多选用它，比 cloze_drag 的共享词库更适合各空独立干扰项）"],
  ["listen_fill", "- listen_fill:{type,audioText(完整句子,端上 TTS 朗读),text(展示句,用 ___ 标缺词处),bank:[词库 ≥2,含答案+干扰词],answers:[每空正确词,顺序对应 ___],explanation?}（听力填空：端上朗读整句，用户听后把缺词从词库点填。听力/精听练习用它，text 是挖了词的句子、audioText 是完整句）"],
  ["word_formation", "- word_formation:{type,prompt?,items:[{base:原词/词根,target:目标词性或提示(如 名词/形容词/副词),answer:派生词} ≥1],bank?:[候选派生词,含答案+干扰词],explanation?}（词形转换：给原词与目标词性，用户从词库点填正确派生词，如 happy→(名词)→happiness。构词法/词族练习用它）"],
  ["timeline", "- timeline:{type,title?,events:[{time:时间/阶段,title:事件,detail?:描述} ≥2]}（时间线：按时间或阶段竖向展示一串事件，纯展示不判分。时态演变/故事情节/学习路线/历史顺序等用它）"],
  ["stress_mark", "- stress_mark:{type,word?,syllables:[音节 ≥2,如 [\"ba\",\"na\",\"na\"]],stress:重读音节的序号(1基),explanation?}（单词重音：把单词拆成音节，用户点出重读音节。发音/重音练习用它，syllables 按顺序拆分、stress 指第几个音节重读）"],
  ["reorder_paragraph", "- reorder_paragraph:{type,prompt?,sentences:[按正确顺序排列的整句 ≥2,须互不相同],explanation?}（语篇排序：给一组打乱的句子，用户按序点选还原成连贯段落或对话。sentences 请按正确顺序给出、端上自动打乱；训练语篇衔接/逻辑顺序/指代照应，比 order 的连词成句更偏篇章级。适合段落重排/对话排序）"],
  ["odd_one_out", "- odd_one_out:{type,prompt?,items:[一组词/项 ≥3],answer(必须是 items 之一,即那个不同类的),explanation?}（选异类：给一组多为同类、混入一个异类的词/项，用户选出不属于同类的那个。answer 是异类项、可用 explanation 说明同类规律；训练语义归类/词义辨析/排除推理，与 categorize 的分桶不同）"],
  ["rank_order", "- rank_order:{type,prompt?,items:[按正确顺序(低→高)排列的词/项 ≥3,须互不相同],from?(低端刻度标签,如 最冷/least),to?(高端刻度标签,如 最热/most),explanation?}（程度排序：把一组词/项沿某条标尺从低到高排列，如 cold→hot、small→large、never→always。items 请按正确的低→高顺序给出、端上自动打乱；训练比较级/程度副词/量级，from/to 标出标尺两端）"],
  ["spelling_bee", "- spelling_bee:{type,word(要拼写的目标单词/短语 ≥2字母),hint?(中文释义/提示),example?(例句;端上展示时会自动把该词隐成 ___ 作语境),explanation?}（听音拼写：端上 TTS 朗读 word（可整词/逐字母），用户看释义+听发音把单词逐字母拼出、打字作答，精确判分。单词级正字法/拼写训练，区别于 dictation 的整句听写；建议给 hint 释义、可给 example 语境句）"],
  ["sentence_transform", "- sentence_transform:{type,prompt(转换要求,如 改为被动语态/改为一般疑问句/改为间接引语/合并为一句),source(原句),answer(转换后的标准答案),accept?:[其他可接受的正确写法],hint?,explanation?}（句型转换：给原句与改写要求，用户打字写出转换后的句子，端上与 answer/accept 归一比对判分。语法改写/时态语态/主被动/直接间接引语/句型合并等用它；若有多种正确写法请放进 accept 以免误判）"],
  ["open_cloze", "- open_cloze:{type,prompt?(填空要求/提示),text(用 ___ 标每个空),answers:[每空正确词,顺序对应 ___;某空多解用 \"a/b\" 写],explanation?}（开放式填空：不给选项、不给词库，用户逐空「打字」填出正确词，端上逐空归一判分。最适合考语法功能词——介词/冠词/助动词/连词/关系词/代词等；每空答案唯一或可数，多解写成 a/b。区别于 cloze_select(每空给选项)与 cloze_drag/listen_fill(给共享词库)）"],
  ["translate", "- translate:{type,direction?(zh2en 默认=汉译英 / en2zh=英译中),source(要翻译的原句),answer(参考译文),accept?:[其他可接受译文],hint?,explanation?}（翻译：给原句让用户打字译到目标语言，端上与 answer/accept 归一比对判分。优先出汉译英（zh2en，答案是英文、更好判分）、句子简短；因译法多样，请尽量把常见的其他正确译法放进 accept 以免误判）"],
  ["tfng", "- tfng:{type,prompt?(依据/材料说明),statements:[{text:陈述句,answer:\"true\"|\"false\"|\"not_given\"} ≥1],explanation?(答错讲解:尤其点明 false 与 not_given 之别)}（判断三态 True/False/Not Given：逐句判断是否与材料相符——true=材料支持、false=材料矛盾、not_given=材料未提及。雅思/阅读推断题用它，关键在区分 false(被否定) 与 not_given(未涉及)；一般搭配前面的 passage/text 材料一起出）"],
  ["match_headings", "- match_headings:{type,prompt?,paragraphs:[{label:段落标签如 A/B/C,text:段落内容,answer:该段正确标题(须∈headings)} ≥2],headings:[标题库,建议比段落多、含干扰项 ≥2],explanation?(答错讲解:各段主旨概括依据)}（IELTS 段落标题匹配 Matching Headings：给若干段落+一个标题库，用户为每段选最贴切的标题，全对才算对。阅读概括/主旨段落匹配用它；headings 数量建议多于段落、每段 answer 必须来自 headings）"],
  ["match_info", "- match_info:{type,prompt?,options:[段落标签,如 \"A\",\"B\",\"C\" ≥2],statements:[{text:某条信息/说法/细节,answer:其所属段落标签(须∈options)} ≥1],explanation?(答错讲解:各信息定位依据)}（IELTS 信息匹配 Matching Information：给若干段落标签+若干条信息，用户判断每条信息出现在哪个段落，全对才算对；同一段落可被多条信息选中。定位细节/信息归段用它，一般搭配前面的 passage 段落材料一起出，answer 必须来自 options）"],
  ["listen_cloze", "- listen_cloze:{type,audioText(完整句子,端上 TTS 朗读),text(展示句,用 ___ 标缺词),answers:[每空正确词,顺序对应 ___;某空多解用 \"a/b\"],explanation?}（听力填空·打字版：端上朗读整句，用户听后把缺词「打字」填出、逐空判分。听力精听+拼写用它，无词库；区别于 listen_fill(给共享词库、点选) 与 dictation(整句听写)）"],
  ["error_correction", "- error_correction:{type,prompt?(提示/错误类型,如 时态/搭配/冠词),sentence(含一处错误的句子),answer(改正后的整句),accept?:[其他可接受改法],explanation?(错误说明)}（句子改错：给一句含 1 处语法/用词错误的句子，用户打字写出改正后的整句，端上与 answer/accept 归一比对判分。找错改错/短文改错用它；与 tokens(点选找错) 互补——这是产出改正句。若有多种正确改法请放进 accept）"],
  ["dialogue_complete", "- dialogue_complete:{type,prompt?(情景说明),turns:[{speaker:说话人,text:台词} ≥1],options:[候选回应 ≥2],answer(必须是 options 之一),explanation?}（补全对话：给一段情景对话（端上聊天气泡展示），让用户从选项里选出最合适的「下一句回应/缺句」。交际英语/情景反应/语用得体用它，options 放 1 个最佳回应 + 若干干扰项）"],
  ["word_search", "- word_search:{type,prompt?,grid:[[\"C\",\"A\",\"T\"],...每行等长、每格 1 个大写字母,≥2x2],words:[要找的词 ≥1,每词 ≥2 字母],explanation?}（单词找词游戏：在字母网格里找目标词，端上点首字母格+尾字母格连线判定，找齐才算对。趣味词汇复习用它。务必保证每个 words 都真的沿直线(横/竖/斜,正反皆可)藏在 grid 里，其余空格填随机干扰字母；grid 建议 6x6~10x10）"],
  ["hangman", "- hangman:{type,word(目标词 ≥2字母),hint?(中文释义/提示),maxWrong?(允许错误次数,默认6),explanation?}（猜词游戏：给中文释义/提示，用户逐个猜字母，在用完 maxWrong 次错误前拼出整词即赢。趣味拼写/词汇记忆用它，建议给 hint 释义、word 用常见词）"],
  ["proof_paragraph", "- proof_paragraph:{type,prompt?,lines:[{text:该行原文(可能含1处错),answer:改正后的整行(该行无错时与 text 完全相同),note?:错误说明} ≥1],explanation?}（短文改错：把一段短文按行拆开，部分行含 1 处错误(多词/漏词/误用)，用户逐行在原句上直接改正、逐行判分。高考短文改错用它——每行原文放 text；每行都必须给 answer：有错的行放改正后的整行，无错的行 answer 原样复制 text，禁止只给部分行 answer；务必至少 1 行有错，且 prompt 若声称有 N 处错，就必须恰有 N 行的 answer ≠ text）"],
  ["writing", "- writing:{type,prompt(写作任务:题目/体裁/词数/要点要求),reference?(评分参考:要点提纲或范文要点,批改时喂给 AI)}（自由写作：用户在卡内直接输入英文正文，AI 按内容/组织/词汇/语法四维批改评分。作文/邮件/书信/段落写作练习用它；prompt 必须写清题目与词数要求，不要用 input 或题组代替写作输入）"],
  ["monologue", "- monologue:{type,prompt(口语任务,如「描述这张图/谈谈你的周末」),scene?(图片或情景的文字描述),points?:[要点提示]}（看图说话/话题独白：用户对着情景开口说一段并录音，AI 评发音/流利度。口语开放表达/雅思口语 Part2 独白用它，与 speak_score 的照读跟读不同——这是开放作答）"],
  ["shadowing", "- shadowing:{type,text(示范句,端上 TTS 朗读),translation?(中文对照)}（影子跟读：先听示范音再跟着录音复述，AI 评发音/流利度。语音语调模仿/影子跟读法用它，text 是要跟读的整句）"],
  ["minimal_pair", "- minimal_pair:{type,prompt?,audioText(端上朗读的那个词,必须就是 answer),options:[两个近音词,如 ship/sheep],answer(须是 options 之一),ipa?:[与 options 按位对齐的音标],explanation?}（最小对立对听辨：端上朗读 answer 那个词，用户从两个近音词里选听到的哪个。辨音/最小对立对专项用它，比笼统 audio_choice 更聚焦；ipa 按 options 顺序一一对应）"],
  ["ipa_read", "- ipa_read:{type,prompt?,symbol(IPA 音标,如 /i:/),example?(含该音的例词,端上发音),options:[候选词 ≥2],answer(须∈options,即含该音的词),explanation?}（音标认读：认 IPA 符号 + 听例词发音，从候选词里选含该音的词。音标教学/自然拼读用它）"],
  ["sound_link", "- sound_link:{type,text(整句,端上 TTS 朗读),marks?:[句中的连读/弱读片段,如 an_apple],note?(发音要点讲解)}（连读/弱读/语调：播放整句并标出连读弱读片段+讲解，纯学习不判分。语音现象讲解用它）"],
  ["map_label", "- map_label:{type,prompt?,audioText(定位描述,端上 TTS 朗读),layout?(地图/平面图的文字描述),items:[{text:要定位的地点,answer:其位置标签(须∈options)} ≥1],options:[位置标签,如 \"A\",\"B\",\"C\" ≥2],explanation?}（听力位置标注：雅思听力 Part2 风格——听描述把每个地点归到平面图位置标签，全对才算对。audioText 必须把每个地点的位置说清楚）"],
  ["note_complete", "- note_complete:{type,audioText(长句/短文,端上 TTS 朗读),title?(笔记标题),text(含 ___ 空位的笔记提纲),answers:[每空正确词,顺序对应 ___;某空多解用 \"a/b\"],explanation?}（长音频笔记填空：听一段较长材料，把笔记提纲的缺词逐空打字填出。雅思听力笔记/讲座填空用它；text 里 ___ 数须等于 answers 条目数）"],
  ["match_sentence_endings", "- match_sentence_endings:{type,prompt?,stems:[{text:句子开头,answer:其正确句尾(须∈endings)} ≥1],endings:[句尾选项池,建议比句子多、含干扰 ≥2],explanation?}（IELTS 句尾配对 Matching Sentence Endings：为每个句子开头从句尾池里选出正确结尾，全对才算对。阅读句子补全用它，endings 建议多于 stems 含干扰项）"],
  ["summary_complete", "- summary_complete:{type,prompt?,text(含 ___ 的摘要/流程图文字),bank:[词库 ≥2,建议多于空数、含干扰词],answers:[每空正确词(须∈bank),顺序对应 ___],explanation?}（摘要/流程图填空：读原文后从词库选词补全摘要，全对才算对。雅思 Summary/Flow-chart Completion 用它；一般搭配 passage 原文材料一起出，answers 每项必须都在 bank 里）"],
  ["short_answer", "- short_answer:{type,prompt?,questions:[{q:题干,answer:参考答案(简短,如 ≤3 词),accept?:[其他可接受写法]} ≥1],explanation?}（篇章简答：根据材料逐题打字简答，端上与 answer/accept 归一比对判分。雅思 Short-answer questions 用它；必须搭配 passage/text 材料节点一起出，answer 尽量短且唯一、变体放 accept）"],
  ["guided_writing", "- guided_writing:{type,prompt(写作要求),steps?:[{label:阶段名(如 提纲/主体段/成文),hint?:该阶段提示}],reference?(评分参考:要点/范文要点)}（结构化引导写作：给分阶段脚手架（提纲→段落→成文），用户写正文、AI 四维批改。需要写作步骤引导时用它，自由命题作文用 writing）"],
  ["audio", "- audio:{type,src}"],
  ["reveal", "- reveal:{type,label,content}"]
];

// 选型决策指南行（[维度或 null(恒发), 原文行]）。
const MICRO_GUIDE_LINES = [
  ["词汇", "- 词汇·记忆：纯记忆→flashcard；趣味复习→pairs_memory/word_search/hangman；选异类辨词义→odd_one_out；按属性分桶→categorize；程度/量级排序→rank_order。"],
  ["词汇", "- 词汇·拼写：听音拼写单词→spelling_bee；字母重组→word_scramble；构词/派生→word_formation。"],
  ["语法", "- 语法·用法：单句多空各带选项→cloze_select；填功能词(介词/冠词/助动词/连词/关系词)且打字无提示→open_cloze；共享词库点选→cloze_drag；句型/时态/语态改写→sentence_transform；连词成句(单句)→order；正误判断→true_false；句子成分→sentence_diagram；规则对照→table、规则填空→fill_table。"],
  ["语法", "- 语法·纠错：点选找错→tokens；改正整句→error_correction；短文逐行改错→proof_paragraph；整段框选(选出所有 X)→highlight_span。"],
  ["听力", "- 听力：听句点选填词→listen_fill；听句打字填词→listen_cloze；整句听写→dictation；最小对立辨音→minimal_pair(两近音词专项)/audio_choice(一般听辨)；地图位置标注→map_label；长材料笔记填空→note_complete。"],
  ["口语", "- 口语·发音：照读跟读评分→speak_score；影子跟读(先听示范再复述)→shadowing；看图说话/话题独白(开放表达)→monologue；多轮情景对话→roleplay_turn；单词重音→stress_mark；音标认读→ipa_read；连读弱读讲解→sound_link。"],
  ["阅读", "- 阅读(通常先给 passage/text 材料)：段落配标题→match_headings；信息定位归段→match_info；三态判断(对/错/未提及)→tfng；事实判断→true_false；细节单选→choice；篇章排序→reorder_paragraph；篇章框选→highlight_span；句尾配对→match_sentence_endings；摘要/流程图选词填空→summary_complete；简答→short_answer。"],
  ["写作", "- 写作·翻译：自由写作(作文/邮件/书信/正文输入+AI 批改)→writing；分阶段脚手架写作(提纲→段落→成文)→guided_writing；翻译→translate；连词成句→order；语篇重排→reorder_paragraph。"],
  [null, "- 通用增强：需速度/巩固→timed_challenge；难题需脚手架→ai_hint；仅讲解/对比/顺序展示→text/passage/table/timeline/reveal(不判分)。"]
];

// “由组件库生成 prompt”——清单变了，prompt 自动跟着变，避免前后端/prompt 漂移。
// 传入用户消息时按检测到的技能维度裁剪（省 token、选型聚焦）；不传/检测不到 → 全量。
function microCardSchemaPrompt(userMessage = "") {
  const dims = detectMicroSkillDimensions(userMessage);
  const includeType = (type) => {
    if (!dims) return true;
    const owned = MICRO_TYPE_DIMENSIONS[type];
    return !owned || owned.some((d) => dims.has(d));
  };
  const types = MICRO_NODE_TYPES.filter(includeType);
  return [
    "你是实时组卡引擎。只能用下列“微元(primitive)”按用户需求实时拼出一张学习卡片；禁止任何固定题型模板。",
    '只输出严格 JSON：{"title":"...","nodes":[ 微元对象, ... ]}，不要任何多余文字或代码围栏。',
    `可用微元 type（必须取其一）：${types.join(", ")}`,
    "字段约定：",
    ...MICRO_FIELD_LINES.filter(([type]) => includeType(type)).map(([, line]) => line),
    "【选型决策指南】先判断该需求属于哪个技能维度(词汇/语法/听力/口语/阅读/写作/翻译)+具体知识点，再挑最贴合的专用题型；宁可用专用可判分题型，也别笼统套 choice 或停在纯展示：",
    ...MICRO_GUIDE_LINES.filter(([dim]) => !dim || !dims || dims.has(dim)).map(([, line]) => line),
    "【易混消歧】cloze_select(每空各带选项) 对 cloze_drag/listen_fill(共享词库点选) 对 open_cloze/listen_cloze(打字无提示)；tokens(点选找错) 对 error_correction(打字改正整句) 对 proof_paragraph(短文逐行)；odd_one_out(只选1个异类) 对 categorize(所有项分桶)；order(单句连词成句) 对 reorder_paragraph(多句篇章排序)；true_false(对/错) 对 tfng(对/错/未提及、须配材料)；spelling_bee(听音拼1个词) 对 dictation(整句听写)；match(左右配对) 对 match_info(信息归到段落标签) 对 match_headings(为段落选标题) 对 match_sentence_endings(为句子开头选句尾)；summary_complete(跨篇词库摘要填空) 对 cloze_drag(单句词库填空)；short_answer(材料简答,须配材料) 对 input(单空自测)；minimal_pair(两近音词专项辨音) 对 audio_choice(一般听辨)；speak_score(照读) 对 shadowing(听示范再跟读) 对 monologue(开放独白)；writing(自由写作) 对 guided_writing(分阶段脚手架)；table(纯展示、不可作答) 对 fill_table(表格挖空要作答)——凡是要用户填空/作答的表格一律用 fill_table 并给 blanks，绝不要用 table。",
    "【计数一致】凡带填空的题型(cloze_drag/cloze_select/open_cloze/listen_fill/listen_cloze/note_complete/summary_complete)：text 里 ___ 的个数必须与 answers/blanks 的条目数完全相等、且顺序一一对应；少一个多一个都会导致判分错位。",
    "【出题卫生】所有带 options 的题：选项互不重复；题干 prompt 里不得出现正确答案原文（防泄漏）；tfng/match_info/short_answer 必须搭配 passage 或 text 材料节点一起出（否则无据可判）；summary_complete 的 answers 每项必须都在 bank 里、map_label/match_sentence_endings 的每个 answer 必须来自其选项池。整卡控制在 2-8 个微元节点、内容精炼，确保 JSON 完整不被截断。",
    "【必给解析】每个『可判分』微元都必须带 explanation 字段：一句中文解析——说清正解为何正确、干扰项或常见错法为何错（改错/找错类点明错点与依据；填空/完形/翻译/听写/听力填空/摘要填空/词形/拼写/排序等打字或填空题，也要就答案给出为什么这样填/这样译/这样排的简要解析）。这段解析在用户「核对答案」后展示，是练习闭环的关键，一律不得省略或留空。",
    "原则：先按《选型决策指南》为该知识点选『最贴合的专用题型』——能判分就别只做纯展示、有更专用的判分题型就别退回笼统 choice；再按需搭配 passage/text/table 等展示微元与 explanation 讲解；同一需求不同侧重可组合出完全不同的卡。"
  ].join("\n");
}

// 定向修复的前提：所有 error 都能定位到具体节点（path 形如 nodes[i]...）。
// 出现卡级问题（如 nodes 缺失 M000、清洗后为空 M099）则返回 null，走整卡重生成。
function brokenNodeIndices(errors) {
  const indices = new Set();
  for (const err of errors || []) {
    const m = /^nodes\[(\d+)\]/.exec(String((err && err.path) || ""));
    if (!m) return null;
    indices.add(Number(m[1]));
  }
  return indices.size ? [...indices].sort((a, b) => a - b) : null;
}

// 定向修复：只让模型重出非法节点、合格节点原样保留。相比整卡重生成：
// 输出 token 少一个量级、不会把已合格节点"改坏"、二次失败面更小。
// 输出不合规（长度不符/非 JSON）返回 null，调用方回退整卡重生成。
async function repairMicroCardNodes(card, errors, { callModel, systemPrompt }) {
  const indices = brokenNodeIndices(errors);
  if (!indices || !Array.isArray(card && card.nodes)) return null;
  const lines = indices.map((i) => {
    const nodeIssues = (errors || []).filter((e) => String((e && e.path) || "").startsWith(`nodes[${i}]`));
    return [
      `- 节点 ${i}（原样）：${safeJson(card.nodes[i])}`,
      ...nodeIssues.map((e) => `  问题 [${e.code}] ${e.path}: ${e.message}`)
    ].join("\n");
  });
  const messages = [
    { role: "system", content: systemPrompt },
    {
      role: "user",
      content: [
        "下面这张学习卡只有个别微元节点未通过校验，其余节点已合格、请勿改动。",
        `只返回一个严格 JSON 对象 {"nodes":[...]}：nodes 数组按下列顺序逐一给出每个问题节点的修正版（可换更贴合的 type，但字段必须完整、可判分），数组长度必须等于 ${indices.length}；不要返回其它节点，不要任何解释或代码围栏。`,
        "问题节点：",
        ...lines
      ].join("\n")
    }
  ];
  let fixed;
  try {
    fixed = await callModel(messages);
  } catch (_) {
    return null;
  }
  const fixedNodes = fixed && Array.isArray(fixed.nodes) ? fixed.nodes : null;
  if (!fixedNodes || fixedNodes.length !== indices.length) return null;
  const merged = { ...card, nodes: card.nodes.slice() };
  indices.forEach((nodeIndex, k) => { merged.nodes[nodeIndex] = fixedNodes[k]; });
  return merged;
}

async function generateMicroCard(userMessage, { callModel, maxAttempts = 2, onStage, difficultyHint = "" } = {}) {
  if (typeof callModel !== "function") throw new Error("generateMicroCard: callModel is required");
  const attempts = Math.max(1, Number(maxAttempts) || 1);
  const stage = typeof onStage === "function" ? onStage : () => {};
  const hint = String(difficultyHint || "").trim();
  const messages = [
    // 按请求技能维度裁剪 schema（检测不到维度自动回退全量）；修复调用复用同一份 system。
    { role: "system", content: microCardSchemaPrompt(userMessage) },
    ...(hint ? [{ role: "system", content: hint }] : []),
    { role: "user", content: String(userMessage || "") }
  ];
  let lastReport = "";
  let lastSanitized = { title: "", nodes: [] };
  // 定向修复合并出的卡：下一轮直接校验它，不再整卡重生成。
  let pendingCard = null;
  // 每次生成最多做一次定向修复（额外一次小模型调用），把调用上限压在 attempts + 1。
  let repairTried = false;
  // 引擎打点：attempts 分布 / 修复漏斗 / 校验码频次 / 降级形态（/api/v1/metrics 可见）。
  const stats = { repairMerged: false, validatingRepaired: false, repairFixed: false, modelErrors: 0, issueCodes: [] };

  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    let card;
    if (pendingCard) {
      card = pendingCard;
      pendingCard = null;
      stats.validatingRepaired = true;
    } else {
      stats.validatingRepaired = false;
      stage({ stage: "generating", text: attempt === 1 ? "AI 正在组卡…" : "正在优化卡片…", attempt });
      try {
        card = await callModel(messages);
      } catch (error) {
        stats.modelErrors += 1;
        lastReport = `model error: ${(error && error.message) || error}`;
        // 模型返回非法 JSON（如尾随多余内容/多个对象/代码围栏）：若还有重试机会，追加纠正提示再试，
        // 而不是直接放弃返回空卡（这是词汇等长内容卡间歇性空卡/不稳的根因）。
        if (attempt < attempts) {
          messages.push({
            role: "user",
            content: '上次回复不是严格 JSON。请只返回一个严格的 JSON 对象 {"title":"...","nodes":[...]}，' +
              "不要任何多余文字、解释、代码围栏，也不要输出多个对象。"
          });
          continue;
        }
        break;
      }
    }
    stage({ stage: "validating", text: "正在校验卡片…", attempt });
    const result = validateMicroCard(card);
    lastReport = renderReport(result.issues);
    lastSanitized = result.sanitized;
    if (result.ok) {
      if (stats.validatingRepaired) stats.repairFixed = true;
      recordMicro(true, true, attempt, repairTried, stats);
      return { ok: true, card: result.sanitized, attempts: attempt, report: lastReport };
    }
    stats.issueCodes.push(...result.errors.map((e) => e.code));
    if (attempt < attempts && !repairTried && brokenNodeIndices(result.errors)) {
      // 定向修复优先：问题都在个别节点时，只重修坏节点、合格节点零改动。
      repairTried = true;
      stage({ stage: "generating", text: "正在修复卡片…", attempt });
      const repaired = await repairMicroCardNodes(card, result.errors, { callModel, systemPrompt: messages[0].content });
      if (repaired) {
        stats.repairMerged = true;
        pendingCard = repaired;
        continue;
      }
    }
    // 定向修复不可行（卡级问题/修复输出不合规/已用过）：整卡+报告回灌，让模型整卡重出。
    messages.push({ role: "assistant", content: safeJson(card) });
    messages.push({ role: "user", content: `上次输出未通过校验，请只返回修正后的完整 JSON（不要解释）：\n${lastReport}` });
  }

  // 不套固定题型兜底：返回能渲染的合法微元（可能为空 → 交给调用方降级为纯文本回复）。
  const partialCard = lastSanitized.nodes.length ? lastSanitized : null;
  recordMicro(false, Boolean(partialCard), attempts, repairTried, stats);
  return {
    ok: false,
    card: partialCard,
    attempts,
    report: lastReport
  };
}

// 打点失败绝不影响生成主流程。
function recordMicro(ok, hasCard, attempts, repairTried, stats) {
  try {
    recordMicroGeneration({
      ok,
      hasCard,
      attempts,
      repairTried,
      repairMerged: stats.repairMerged,
      repairFixed: stats.repairFixed,
      modelErrors: stats.modelErrors,
      issueCodes: stats.issueCodes
    });
  } catch (_) { /* 指标缺失可容忍 */ }
}

function safeJson(value) {
  try {
    return JSON.stringify(value);
  } catch (_) {
    return String(value);
  }
}

// 线上入口：用真实 MiMo 作为 callModel（callMimoText 已返回解析后的 JSON 对象）。
// onStage：可选阶段回调（SSE 流式进度用），形如 { stage, text, attempt }。
async function generateMicroCardReply(body = {}, { onStage } = {}) {
  const message = String((body && (body.message || body.userMessage || body.text)) || "").trim();
  if (!message) throw new Error("message required");
  const maxAttempts = Math.max(1, Math.min(4, Number(body && body.maxAttempts) || 3));
  const out = await generateMicroCard(message, {
    // max_tokens 上限：不设时长卡（词汇表/完形/找词网格）易被模型默认输出上限截断成
    // 非法 JSON → 白白烧一轮重试。4096 完成 token ≈ 12KB JSON，覆盖最大合法卡仍留余量。
    callModel: (messages) => callMimoText(messages, { maxTokens: 4096 }),
    maxAttempts,
    onStage,
    difficultyHint: difficultyHintFromBody(body)
  });
  // 定向异步答案抽验：只审语言学判断型答案键的卡，响应已返回、零延迟影响。
  if (out && out.card) {
    const { settings } = require("../config");
    if (settings.microAnswerAuditEnabled) {
      require("./microAnswerVerify").auditMicroCardAnswersAsync(out.card, message);
    }
  }
  return out;
}

module.exports = { microCardSchemaPrompt, generateMicroCard, generateMicroCardReply, difficultyHintFromBody, repairMicroCardNodes, detectMicroSkillDimensions };
