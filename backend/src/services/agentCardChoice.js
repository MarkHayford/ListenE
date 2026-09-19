// 选择题卡（Choice）：请求识别、必需范围与构建、选项推断与清洗
// 原内联于 mimoText.js，逐字搬出为独立模块；依赖闭合、无循环依赖。

const { isMinimalPairQuestionSetRequest, isStrictStaticMinimalPairRequest } = require("./agentCardMinimalPair");
const { AGENT_DEBATE_INTENT_PATTERN, AGENT_ERROR_HUNT_INTENT_PATTERN, AGENT_ETHICS_INTENT_PATTERN, AGENT_GRAMMAR_INTENT_PATTERN, AGENT_LISTENING_CUE_INTENT_PATTERN, AGENT_MINIMAL_PAIR_INTENT_PATTERN, AGENT_PARAPHRASE_INTENT_PATTERN, AGENT_REGISTER_INTENT_PATTERN, AGENT_STORYTELLING_INTENT_PATTERN, AGENT_VOCABULARY_INTENT_PATTERN, AGENT_WORD_FAMILY_INTENT_PATTERN } = require("./agentCardPatterns");
const { isSentenceBuilderNegatedRequest } = require("./agentCardSentenceBuilder");
const { isShortAnswerNegatedRequest, shouldSuppressAgentCardAnswerExplanation } = require("./agentCardShortAnswer");
const { sanitizeAgentCardText } = require("./agentCardText");
const { inferAgentRequestedQuestionCount, inferRequestedAgentCardItemCounts } = require("./explicitConstraints");
const { parseCountToken } = require("./listeningGenerate");
const { agentCardOrderingAnswerMatchesItems, isOrderingCardRequest } = require("./ordering");
const { normalizeAgentAnswerLookupText } = require("./textNormalize");

const OLD_AGENT_CARD_TOKENS = new Set(["training", "intensive", "review", "word_sentence"]);

const AGENT_CARD_COMPONENT_REGISTRY = [
  { type: "header", aliases: [] },
  { type: "chips", aliases: [] },
  { type: "summary", aliases: [] },
  { type: "progress", aliases: [] },
  { type: "audio", aliases: ["audio_player", "audioplayer"] },
  { type: "question_preview", aliases: ["questionpreview", "questions"] },
  { type: "transcript", aliases: ["transcript_text", "transcripttext", "script", "plain_transcript", "plaintranscript"] },
  { type: "sentence_transcript", aliases: ["sentencetranscript", "sentence_transcript_player", "sentenceplayer", "intensive_transcript", "intensivetranscript"] },
  { type: "feedback", aliases: [] },
  { type: "suggestion", aliases: ["suggestions"] },
  { type: "divider", aliases: [] },
  { type: "actions", aliases: [] },
  { type: "vocabulary", aliases: ["word", "words"] },
  { type: "phrase", aliases: ["phrases", "collocation", "collocations"] },
  { type: "grammar", aliases: ["grammar_tip", "grammartip"] },
  { type: "translation", aliases: ["translation_pair", "translationpair", "bilingual"] },
  { type: "examples", aliases: ["example", "example_sentences", "examplesentences"] },
  { type: "pronunciation", aliases: ["pronunciation_tip", "pronunciationtip", "phonics"] },
  { type: "cloze", aliases: ["blank", "fill_blank", "fillblank"] },
  { type: "short_answer", aliases: ["shortanswer", "dictation", "free_response", "freeresponse", "typed_answer", "typedanswer", "text_input", "textinput", "input_answer", "inputanswer"] },
  { type: "sentence_builder", aliases: ["sentencebuilder", "sentence_order", "sentenceorder", "reorder"] },
  { type: "ordering", aliases: ["sequence", "sequencing", "paragraph_order", "paragraphorder", "paragraph_ordering", "logic_order", "logical_order", "sentence_sequence", "sentence_sequencing", "sequence_order"] },
  { type: "question_set", aliases: ["questionset", "quiz", "quiz_questions", "quizquestions", "multiple_choice", "multiplechoice"] },
  { type: "reading", aliases: ["reading_passage", "readingpassage", "passage", "comprehension", "reading_comprehension", "readingcomprehension", "reading_quiz", "reading_card"] },
  { type: "gap_match", aliases: ["gapmatch", "seven_choose_five", "sevenchoosefive", "seven_select_five", "sentence_restoration", "sentencerestoration", "gap_fill_sentences", "gapfillsentences", "choose_sentence", "sentence_gap", "passage_gap", "sentence_insertion"] },
  { type: "chart_writing", aliases: ["chartwriting", "chart_essay", "chartessay", "graph_writing", "graphwriting", "data_writing", "chart_description", "graph_description", "task1_writing", "visual_writing", "chart_task"] },
  { type: "compare", aliases: ["contrast"] },
  { type: "correction", aliases: ["rewrite", "error_fix", "errorfix"] },
  { type: "rubric", aliases: ["criteria"] },
  { type: "listening_cue", aliases: ["listeningcue", "signal_words", "signalwords", "discourse_marker", "discoursemarker", "discourse_markers", "discoursemarkers"] },
  { type: "minimal_pair", aliases: ["minimalpair", "phoneme_pair", "phonemepair", "sound_pair", "soundpair"] },
  { type: "word_family", aliases: ["wordfamily", "derivation", "morphology"] },
  { type: "scenario", aliases: ["situation", "situational_expression", "situationalexpression", "context_expression", "contextexpression"] },
  { type: "register", aliases: ["register_shift", "registershift", "tone_shift", "toneshift", "style_shift", "styleshift"] },
  { type: "speaking_prompt", aliases: ["speakingprompt", "oral_prompt", "oralprompt", "speaking_task", "speakingtask", "oral_task", "oraltask"] },
  { type: "writing_outline", aliases: ["writingoutline", "essay_outline", "essayoutline", "outline"] },
  { type: "mistake_pattern", aliases: ["mistakepattern", "error_pattern", "errorpattern", "mistake_type", "mistaketype"] },
  { type: "ethics", aliases: ["moral", "morality", "moral_dilemma", "moraldilemma", "ethical_dilemma", "ethicaldilemma", "ethics_scenario", "ethicsscenario", "character_education", "charactereducation", "value_education", "valueeducation"] },
  { type: "debate", aliases: ["debate_prompt", "debateprompt", "argument", "argumentation", "for_against", "foragainst", "pros_cons", "proscons", "pro_con", "stance"] },
  { type: "error_hunt", aliases: ["errorhunt", "error_hunting", "proofread", "proofreading", "find_errors", "finderrors", "spot_errors", "spoterrors", "editing", "proofreading_passage"] },
  { type: "storytelling", aliases: ["story", "story_prompt", "storyprompt", "story_builder", "storybuilder", "narration", "narrative", "creative_writing", "creativewriting"] },
  { type: "paraphrase", aliases: ["paraphrasing", "rephrase", "rephrasing", "reword", "rewording", "same_meaning", "samemeaning", "synonym_rewrite", "synonymrewrite", "restate", "restatement"] }
];

const AGENT_CARD_COMPONENT_ALIAS_MAP = new Map(
  AGENT_CARD_COMPONENT_REGISTRY.flatMap((item) => [
    [item.type, item.type],
    ...item.aliases.map((alias) => [alias, item.type])
  ])
);

function cappedEnforcedAgentCardCount(type, n) {
  const c = Number(n) || 0;
  if (c <= 0) return 0;
  const max = (String(type) === "question_set") ? 8 : 12;
  return Math.min(c, max);
}

function isNaturalMeaningQuizRequest(message = "") {
  const text = String(message || "");
  if (/(?:\u8bcd\u4e49|\u542b\u4e49|\u610f\u601d|\u91ca\u4e49).{0,8}(?:\u9009\u62e9|\u9009\u62e9\u9898|\u8003|\u7ec3)|(?:\u5355\u8bcd|\u8bcd\u6c47|\u751f\u8bcd).{0,12}(?:\u610f\u601d|\u542b\u4e49|\u91ca\u4e49|\u8bcd\u4e49).{0,10}(?:\u9009\u62e9|\u9009\u62e9\u9898|\u8003|\u7ec3)/i.test(text)) return true;
  if (!text.trim()) return false;
  if (/options?|选项/i.test(text) && /answer|correct|答案|正确/i.test(text) && /(?:one|1|一个|一道).{0,24}(?:meaning\s+choice|choice\s+card|选择)/i.test(text)) return false;
  return /词义选择|含义选择|意思选择|(?:单词|词汇|生词).{0,10}(?:意思|含义|释义|词义).{0,10}(?:选择|考|练)|(?:意思|含义|释义|词义).{0,10}(?:选择|考|练).{0,10}(?:单词|词汇|生词)|考我.{0,10}(?:单词|词汇|生词).{0,10}(?:意思|含义|释义|词义)|meaning\s+quiz|quiz.{0,16}meaning/i.test(text);
}

// 单题「一道…含义选择题」应按单选 cloze 承载，与 assessAgentCardQuality 的判定保持一致，
// 避免 scope 误判为多题 question_set 而与质检冲突导致最终卡片不合格。

function isSingleMeaningChoiceClozeRequest(message = "") {
  if (!isNaturalMeaningQuizRequest(message)) return false;
  const text = String(message || "");
  if (/question\s*set|题组/i.test(text)) return false;
  return inferAgentRequestedQuestionCount(message) === 1 ||
    /一道|一题|1\s*道|1\s*题|单个|a\s+single|single\s+(?:question|choice)|one\s+(?:question|choice|mcq)/i.test(text);
}

function isExplicitSingleMeaningChoicePayload(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  return /meaning\s+choice|词义选择|含义选择|意思选择/i.test(text) &&
    /options?/i.test(text) &&
    /answer|correct|答案|正确/i.test(text) &&
    !/\b(?:questions?|quiz|items?)\b|[二两三四五六七八九十2-9]\s*(?:道|个|题)|\b(?:two|three|four|five|six|seven|eight|nine|ten)\b/i.test(text);
}

function isChoiceAnswerCardRequest(text = "") {
  const value = String(text || "").toLowerCase();
  return /(choice|choose|multiple\s*choice|选项|选择|选择题|含义|meaning|closest\s+meaning)/.test(value) &&
    /(answer|correct|答案|正确|options?|选项)/.test(value);
}

function isMultiQuestionChoiceCardRequest(text = "") {
  const value = String(text || "").toLowerCase();
  if (isExplicitClozeChoiceCardRequest(value)) return false;
  if (/no\s+(?:quiz|questions?|question\s*set)|without\s+(?:quiz|questions?|question\s*set)|不要.{0,8}(?:题组|题目(?!预览)|问题|测验|quiz|题(?!目?预览))|不需要.{0,8}(?:题组|题目(?!预览)|问题|测验|quiz|题(?!目?预览))|无(?:题组|题目(?!预览)|问题|测验|题(?!目?预览))|别出题|别加.{0,8}(?:题(?!目?预览)|题目(?!预览)|题组|questions?)/.test(value)) return false;
  if (/(?:only|just)\s+one\s+choice|one\s+(?:meaning\s+)?choice\s+card|only\s+choice\b|只(?:要|做).{0,8}(?:一个|一道)?.{0,8}选择/.test(value)) return false;
  if (/(?:question\s*set|题组)/i.test(value) && inferAgentRequestedQuestionCount(value) >= 2) return true;
  const numberWords = String.raw`\b(?:one|two|three|four|five|six|seven|eight|nine)\b`;
  const hasCountedQuestions = new RegExp(String.raw`(?:\b[1-9]\b|${numberWords}|十|一|二|两|三|四|五|六|七|八|九)(?:\s+[a-z0-9+-]+){0,8}\s+(?:questions?|题|题目|道|个)`, "i").test(value) ||
    new RegExp(String.raw`(?:questions?|题目|题|quiz|题组|选择题).{0,24}(?:\b[1-9]\b|${numberWords}|十|一|二|两|三|四|五|六|七|八|九)`, "i").test(value) ||
    new RegExp(String.raw`(?:\b[1-9]\b|${numberWords}|十|一|二|两|三|四|五|六|七|八|九)\s*(?:道|个|條|条)?\s*(?:普通)?题组`, "i").test(value);
  const hasChoiceQuizIntent = isChoiceAnswerCardRequest(value) ||
    /multiple\s*choice|mcq|quiz|meaning\s+questions?|discrimination\s+questions?|辨音题|选择题|题组/i.test(value) ||
    isMinimalPairQuestionSetRequest(value);
  return hasCountedQuestions && (hasChoiceQuizIntent || isDialogueQuestionSetRequest(value));
}

function isDialogueQuestionSetRequest(text = "") {
  const value = String(text || "").toLowerCase();
  if (!/dialogue|conversation|role.?play|customer\s+service|interview|对话|角色|客服|面试/.test(value)) return false;
  if (!/questions?|comprehension|quiz|题|题目|问题|选择题|理解/.test(value)) return false;
  const count = inferAgentRequestedQuestionCount(value) ||
    inferRequestedAgentCardItemCounts(value).question_set ||
    0;
  return count >= 1;
}

function isExplicitClozeChoiceCardRequest(text = "") {
  const value = String(text || "").toLowerCase();
  return /cloze|blank|fill|填空|空格/.test(value) &&
    /options?|选项/.test(value) &&
    /answer|correct\s+answer|答案|正确答案/.test(value);
}

function isPlainTranscriptAgentCardRequest(text = "") {
  const value = String(text || "").toLowerCase();
  return /plain\s+transcript|regular\s+transcript|simple\s+transcript|普通.{0,8}(原文|文本)|仅.{0,8}(原文|文本)|只(?:要|显示|给).{0,12}(原文|文本)|(?:看|看看|查看|显示|给我|打开|展示|读).{0,12}(?:普通)?(?:原文|文本)|^(?:原文|文本)$|(?:原文|文本).{0,8}(?:给我看|看看|查看|打开|展示)|(?:看|查看|显示|给我).{0,12}(?:普通)?(?:原文|文本).{0,16}(?:不要|不需要|无|非).{0,8}(?:逐句|精听|点播)/.test(value);
}

function isSentenceTranscriptDeniedAgentCardRequest(text = "") {
  const value = String(text || "").toLowerCase();
  return /no\s+sentence[_\s-]*transcript|without\s+sentence[_\s-]*transcript|no\s+sentence[_\s-]*by[_\s-]*sentence|without\s+sentence[_\s-]*by[_\s-]*sentence|不要.{0,8}(逐句|精听|点播)|不需要.{0,8}(逐句|精听|点播)|无逐句|非逐句/.test(value);
}

function isSentenceTranscriptAgentCardRequest(text = "") {
  const value = String(text || "").toLowerCase();
  if (isPlainTranscriptAgentCardRequest(value) || isSentenceTranscriptDeniedAgentCardRequest(value)) return false;
  return /sentence[_\s-]*transcript|sentence[_\s-]*by[_\s-]*sentence|replay\s+each\s+sentence|each\s+sentence|逐句听|逐句|一句一句|一遍一句|复听|精听|点播/.test(value);
}

function inferAgentCardExcludedComponents(text = "") {
  const value = String(text || "").toLowerCase();
  const excluded = new Set();
  const excludes = (pattern) => pattern.test(value);
  if (isSentenceBuilderNegatedRequest(value)) excluded.add("sentence_builder");
  if (isShortAnswerNegatedRequest(value)) excluded.add("short_answer");
  if (excludes(/no\s+examples?|without\s+examples?|不要.{0,8}例句|不需要.{0,8}例句|无例句|别(?:加|放|给|带|显示).{0,8}例句/)) excluded.add("examples");
  if (excludes(/只要.{0,12}(?:pair|pairs|词对|音近词)|only\s+(?:pairs?|minimal\s*pairs?)/)) excluded.add("examples");
  if (excludes(/no\s+actions?|without\s+actions?|不要.{0,8}(?:操作|动作|按钮|actions?)|不需要.{0,8}(?:操作|动作|按钮|actions?)|无(?:操作|动作|按钮)/)) excluded.add("actions");
  if (excludes(/no\s+(?:quiz|questions?|question\s*set)|without\s+(?:quiz|questions?|question\s*set)|不要.{0,8}(?:题组|题目(?!预览)|问题|测验|quiz)|不需要.{0,8}(?:题组|题目(?!预览)|问题|测验|quiz)|无(?:题组|题目(?!预览)|问题|测验)|别出题|别加.{0,8}(?:题(?!目?预览)|题目(?!预览)|题组|questions?)/)) excluded.add("question_set");
  if (isNoAudioAgentCardRequest(value)) excluded.add("audio");
  if (excludes(/no\s+transcript|without\s+transcript|do\s+not\s+(?:add|include|show|use|generate).{0,36}transcript|don't\s+(?:add|include|show|use|generate).{0,36}transcript|不要.{0,8}(原文|文本|transcript)|不需要.{0,8}(原文|文本|transcript)|无原文|别(?:放|加|显示).{0,8}(原文|文本|transcript)/)) excluded.add("transcript");
  if (excludes(/no\s+suggestions?|without\s+suggestions?|do\s+not\s+(?:add|include|show|use|generate).{0,36}suggestions?|don't\s+(?:add|include|show|use|generate).{0,36}suggestions?|不要.{0,8}(建议|推荐)|不需要.{0,8}(建议|推荐)|无建议/)) excluded.add("suggestion");
  if (excludes(/no\s+grammar|without\s+grammar|不要.{0,8}(?:语法|grammar|讲解|解析)|不需要.{0,8}(?:语法|grammar|讲解|解析)|无(?:语法|讲解|解析)|别(?:加|放|给|带|显示).{0,8}(?:语法|grammar|讲解|解析)/)) excluded.add("grammar");
  if (excludes(/no\s+cloze|without\s+cloze|no\s+fill(?:\s+in)?\s+blanks?|without\s+fill(?:\s+in)?\s+blanks?|不要.{0,8}(填空|空格|cloze)|不需要.{0,8}(填空|空格|cloze)|无填空/)) excluded.add("cloze");
  if (excludes(/no\s+pronunciation|without\s+pronunciation|no\s+phonetics?|without\s+phonetics?|不要(?:额外|附加|再)?(?:的)?(?:发音|音标|口型|pronunciation)|不需要(?:额外|附加|再)?(?:的)?(?:发音|音标|口型|pronunciation)|无发音|别.{0,8}(?:解释)?口型/)) excluded.add("pronunciation");
  if (excludes(/no\s+vocab(?:ulary)?|without\s+vocab(?:ulary)?|不要.{0,8}(词汇|单词|vocab(?:ulary)?|单词解释)|不需要.{0,8}(词汇|单词|vocab(?:ulary)?|单词解释)/)) excluded.add("vocabulary");
  if (excludes(/只要.{0,12}(?:词族|派生词|派生|word\s*family|related\s+forms)|only\s+word\s*family/)) excluded.add("vocabulary");
  if (excludes(/no\s+phrases?|without\s+phrases?|不要.{0,8}短语|不需要.{0,8}短语/)) excluded.add("phrase");
  if (excludes(/no\s+translation|without\s+translation|不要.{0,8}翻译|不需要.{0,8}翻译/)) excluded.add("translation");
  if (excludes(/no\s+rubric|without\s+rubric|不要.{0,8}评分|不需要.{0,8}评分/)) excluded.add("rubric");
  if (excludes(/no\s+correction|without\s+correction|不要.{0,8}(纠错|改错|修正|解析)|不需要.{0,8}(纠错|改错|修正|解析)|无(?:纠错|解析)|别(?:加|放|给|带|显示).{0,8}(纠错|改错|修正|解析)/)) excluded.add("correction");
  if (excludes(/no\s+register|without\s+register|不要.{0,8}(语气|正式|非正式|register)|不需要.{0,8}(语气|正式|非正式|register)/)) excluded.add("register");
  if (excludes(/no\s+scenario|without\s+scenario|不要.{0,8}(场景|情景|scenario)|不需要.{0,8}(场景|情景|scenario)/)) excluded.add("scenario");
  if (excludes(/no\s+speaking|without\s+speaking|no\s+oral|without\s+oral|不要.{0,8}(口语|oral|speaking)|不需要.{0,8}(口语|oral|speaking)|别加.{0,8}(口语|oral|speaking)/)) excluded.add("speaking_prompt");
  if (excludes(/no\s+writing|without\s+writing|no\s+outline|without\s+outline|不要.{0,8}(写作|提纲|writing|outline)|不需要.{0,8}(写作|提纲|writing|outline)|别加.{0,8}(写作|提纲|writing|outline)/)) excluded.add("writing_outline");
  if (excludes(/no\s+compare|without\s+compare|no\s+difference|without\s+difference|不要.{0,8}(对比|辨析|区别|compare)|不需要.{0,8}(对比|辨析|区别|compare)/)) excluded.add("compare");
  if (excludes(/no\s+listening\s*cues?|without\s+listening\s*cues?|no\s+signal\s*words?|without\s+signal\s*words?|不要.{0,8}(听力信号|信号词|连接词|衔接词)|不需要.{0,8}(听力信号|信号词|连接词|衔接词)/)) excluded.add("listening_cue");
  if (excludes(/no\s+minimal\s*pairs?|without\s+minimal\s*pairs?|不要.{0,8}(音素|辨音|音近词|词对|minimal\s*pairs?)|不需要.{0,8}(音素|辨音|音近词|词对|minimal\s*pairs?)/)) excluded.add("minimal_pair");
  if (excludes(/no\s+word\s*family|without\s+word\s*family|不要.{0,8}(词族|派生|word\s*family)|不需要.{0,8}(词族|派生|word\s*family)/)) excluded.add("word_family");
  if (excludes(/no\s+mistake\s*patterns?|without\s+mistake\s*patterns?|不要.{0,8}(错因模式|错因)|不需要.{0,8}(错因模式|错因)/)) excluded.add("mistake_pattern");
  if (
    !/不(?:是|要).{0,8}(?:词语排序|词块排序|连词成句|组句)|别.{0,8}(?:词语排序|词块排序|连词成句|组句)/.test(value) &&
    excludes(/no\s+(?:ordering|sequencing|sequence)|without\s+(?:ordering|sequencing|sequence)|不要.{0,8}(?:ordering|sequencing|sequence|段落排序|篇章排序|逻辑排序|整句排序)|不需要.{0,8}(?:ordering|sequencing|sequence|段落排序|篇章排序|逻辑排序|整句排序)|别(?:加|放|给|带|显示|出)?.{0,8}(?:ordering|sequencing|sequence|段落排序|篇章排序|逻辑排序|整句排序)/)
  ) {
    excluded.add("ordering");
  }
  return excluded;
}

function enforceExplicitChoiceComponentScope(component = {}, scope = {}) {
  const choiceConstraint = scope.choiceConstraint;
  if (!choiceConstraint || component?.type !== "cloze") return component;
  const target = choiceConstraint.target;
  const scoped = {
    ...component,
    title: "Select the meaning",
    text: `${target} means:`,
    items: choiceConstraint.options,
    options: choiceConstraint.options,
    answer: choiceConstraint.answer,
    explanation: `${target} = ${choiceConstraint.answer}.`
  };
  if (shouldSuppressAgentCardAnswerExplanation(scoped, scope)) delete scoped.explanation;
  return scoped;
}

function countAgentCardClozeBlanks(text = "") {
  return (String(text || "").match(/_{2,}(?:\d+|[A-Za-z])_{2,}|_{2,}|\[\s*blank\s*\]/gi) || []).length;
}

const AGENT_CARD_INLINE_EXAMPLE_SCAN_TYPES = new Set([
  "compare",
  "grammar",
  "pronunciation",
  "listening_cue",
  "rubric",
  "writing_outline",
  "mistake_pattern"
]);

function isNoAudioAgentCardRequest(message = "") {
  return /no\s+audio|without\s+audio|不要.{0,8}音频|不需要.{0,8}音频|无音频/i.test(String(message || ""));
}

function inferWordBankClozeChoiceCounts(text = "") {
  const value = String(text || "");
  const token = "([0-9]{1,2}|[一二两俩三四五六七八九十]{1,3})";
  const match = new RegExp(`${token}\\s*选\\s*${token}`, "i").exec(value);
  if (!match) return null;
  const optionCount = parseCountToken(match[1]);
  const blankCount = parseCountToken(match[2]);
  if (!optionCount || !blankCount) return null;
  return {
    optionCount,
    blankCount
  };
}

function assessAgentCardQuality({ message = "", currentRecordSummary = null, cardSpec = null } = {}) {
  const warnings = [];
  if (!cardSpec || typeof cardSpec !== "object") {
    warnings.push("card missing");
    return agentCardQualityResult(warnings);
  }
  const components = Array.isArray(cardSpec.components) ? cardSpec.components : [];
  const types = new Set(components.map((item) => normalizeCardToken(item?.type || item?.name)).filter(Boolean));
  const text = String(message || "").toLowerCase();
  const hasRecordScript = Boolean(currentRecordSummary?.scriptPreview || currentRecordSummary?.script);
  const hasRecordQuestions = Array.isArray(currentRecordSummary?.questions) && currentRecordSummary.questions.length > 0;
  const hasWrongInsights = Array.isArray(currentRecordSummary?.wrongInsights || currentRecordSummary?.wrongQuestionInsights) &&
    (currentRecordSummary.wrongInsights || currentRecordSummary.wrongQuestionInsights).length > 0;
  const choiceOnly = isChoiceAnswerCardRequest(text);
  // 单题含义/单词选择应按单选(cloze)判定，不能当多题(question_set，≥2题)，否则单选必被误判为缺 question_set。
  // 但显式写“题组/question set”的单题仍按 question_set 处理（保留既有精细测试行为）。
  const singleChoiceCueForQuality = inferAgentRequestedQuestionCount(message) === 1 ||
    /一道|一题|1\s*道|1\s*题|单个|a\s+single|single\s+(?:question|choice)|one\s+(?:question|choice|mcq)/i.test(text);
  const explicitQuestionSetForQuality = /question\s*set|题组/i.test(text);
  const multiQuestionChoice = (isNaturalMeaningQuizRequest(message) || isMultiQuestionChoiceCardRequest(text)) &&
    !(singleChoiceCueForQuality && !explicitQuestionSetForQuality);
  const orderingRequest = isOrderingCardRequest(text);
  const requestedCounts = inferRequestedAgentCardItemCounts(message);
  const excluded = inferAgentCardExcludedComponents(message);
  const requireAllowedComponent = (type) => {
    if (!excluded.has(type)) requireComponent(types, warnings, type);
  };
  const requireAllowedSource = (type, source) => {
    if (!excluded.has(type)) requireSource(components, warnings, type, source);
  };

  // 新题型须在 ordering/选择题判定之前（“七选五”含“选”等会误触发选择题要求）。
  const gapMatchReq = /七选五|选句填空|句子还原|选句还原|还原句子|gap\s*match|seven\s*choose\s*five|sentence\s*restoration|sentence\s*insertion/i.test(message);
  const chartWritingReq = /图表作文|看图作文|图表描述|图表写作|图表题|描述图表|图表[^，。!?\n]{0,6}(?:作文|写作|描述)|(?:描述|写一?篇?|看图)[^，。!?\n]{0,6}图表|chart\s*writing|graph\s*writing|chart\s*essay/i.test(message);
  const readingReq = /阅读理解|reading\s*comprehension|reading\s*quiz/i.test(text) ||
    (/短文|passage|文章/i.test(text) && /题|question|quiz/i.test(text));
  if (gapMatchReq) {
    requireAllowedComponent("gap_match");
  } else if (chartWritingReq) {
    requireAllowedComponent("chart_writing");
  } else if (readingReq) {
    requireAllowedComponent("reading");
  } else if (orderingRequest) {
    requireAllowedComponent("ordering");
  } else if (multiQuestionChoice) {
    requireAllowedComponent("question_set");
  } else if (choiceOnly) {
    requireAllowedComponent("cloze");
  }
  if (!excluded.has("minimal_pair") && (AGENT_MINIMAL_PAIR_INTENT_PATTERN.test(message) || /ship|sheep|live|leave/.test(text))) {
    requireAllowedComponent("minimal_pair");
  }
  if (!choiceOnly && !multiQuestionChoice && AGENT_VOCABULARY_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("vocabulary");
  }
  if (!multiQuestionChoice && AGENT_WORD_FAMILY_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("word_family");
  }
  if (AGENT_GRAMMAR_INTENT_PATTERN.test(message) || /present\s*perfect|past\s*tense/.test(text)) {
    requireAllowedComponent("grammar");
  }
  if (!choiceOnly && /cloze|blank|fill.{0,8}blank|填空/.test(text)) {
    requireAllowedComponent("cloze");
  }
  if (!choiceOnly && !excluded.has("short_answer") && /dictation|typed\s*answer|type\s+the\s+sentence|free\s*response|short\s*answer|text\s*input|input\s*answer|默写|输入答案|打字|短答|听写/.test(text)) {
    requireAllowedComponent("short_answer");
  }
  if (/speaking\s*prompt|speaking|\boral\b|口语/.test(text)) {
    requireAllowedComponent("speaking_prompt");
  }
  if (/rubric|criteria|评分|标准/.test(text)) {
    requireAllowedComponent("rubric");
  }
  if (/writing|outline|essay|写作|提纲/.test(text) && !AGENT_STORYTELLING_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("writing_outline");
  }
  if (!excluded.has("translation") && /translation|translate|翻译/.test(text)) {
    requireAllowedComponent("translation");
  }
  if (/compare|difference|区别|对比|辨析/.test(text)) {
    requireAllowedComponent("compare");
  }
  if (/scenario|situational|场景|情景/.test(text)) {
    requireAllowedComponent("scenario");
  }
  if (AGENT_REGISTER_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("register");
  }
  if (AGENT_ETHICS_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("ethics");
  }
  if (AGENT_DEBATE_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("debate");
  }
  if (AGENT_ERROR_HUNT_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("error_hunt");
  }
  if (AGENT_STORYTELLING_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("storytelling");
  }
  if (AGENT_PARAPHRASE_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("paraphrase");
  }
  if (!orderingRequest && !excluded.has("sentence_builder") && /sentence\s*builder|reorder\s+words?|word\s*order|组句|连词成句|单词顺序|词语排序|词块排序/.test(text)) {
    requireAllowedComponent("sentence_builder");
  }
  if (AGENT_LISTENING_CUE_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("listening_cue");
  }
  if (/pronunciation|发音|音标|连读|重音/.test(text)) {
    if (!isStrictStaticMinimalPairRequest(message)) requireAllowedComponent("pronunciation");
  }
  if (/correction|correct|rewrite|纠错|改错/.test(text)) {
    requireAllowedComponent("correction");
  }
  const wantsSentenceTranscript = isSentenceTranscriptAgentCardRequest(text);
  if (wantsSentenceTranscript) {
    requireAllowedComponent("sentence_transcript");
    if (hasRecordScript) requireAllowedSource("sentence_transcript", "latestMaterial.script");
  } else if (!excluded.has("transcript") && /原文|transcript|文本/.test(text)) {
    requireAllowedComponent("transcript");
    if (hasRecordScript) requireAllowedSource("transcript", "latestMaterial.script");
  }
  if (!wantsSentenceTranscript && !excluded.has("question_set") && !multiQuestionChoice && /答题|做题|题目|question|quiz/.test(text)) {
    requireAllowedComponent("question_preview");
    if (hasRecordQuestions) requireAllowedSource("question_preview", "latestMaterial.questions");
  }
  if (/错因|错题|wrong|mistake|correction/.test(text) && !AGENT_ERROR_HUNT_INTENT_PATTERN.test(message)) {
    requireAllowedComponent("mistake_pattern");
    if (hasWrongInsights) {
      requireAllowedSource("mistake_pattern", "analysis.wrongInsights");
      requireAllowedSource("correction", "analysis.wrongInsights");
    }
  }

  components.forEach((component) => {
    const type = normalizeCardToken(component?.type || component?.name);
    const expectedCount = cappedEnforcedAgentCardCount(type, requestedCounts[type]);
    if (expectedCount && expectedCount > 0) {
      const actualCount = countAgentCardComponentItemsForQuality(component, type);
      // best-effort：只在数量不足时告警（多于请求数不算问题），且目标已按 cappedEnforcedAgentCardCount 封顶。
      if (actualCount < expectedCount) {
        warnings.push(`count mismatch: ${type} expected ${expectedCount} got ${actualCount}`);
      }
    }
    if (type === "minimal_pair" && !Array.isArray(component.pairs)) {
      warnings.push("typed payload missing: minimal_pair.pairs");
    }
    if (type === "minimal_pair" && Array.isArray(component.pairs) && component.pairs.some((pair) => !pair?.left || !pair?.right)) {
      warnings.push("typed payload weak: minimal_pair pair side missing");
    }
    if (type === "word_family" && !Array.isArray(component.tokens)) {
      warnings.push("typed payload missing: word_family.tokens");
    }
    if (type === "word_family" && Array.isArray(component.tokens) && component.tokens.length < 2) {
      warnings.push("typed payload weak: word_family.tokens too short");
    }
    if (type === "writing_outline" && !Array.isArray(component.steps)) {
      warnings.push("typed payload missing: writing_outline.steps");
    }
    if (type === "writing_outline" && Array.isArray(component.steps) && component.steps.some((step) => !step?.text)) {
      warnings.push("typed payload weak: writing_outline.steps text missing");
    }
    if (type === "rubric" && !Array.isArray(component.criteria)) {
      warnings.push("typed payload missing: rubric.criteria");
    }
    if (type === "rubric" && Array.isArray(component.criteria) && component.criteria.some((criterion) => !criterion?.text)) {
      warnings.push("typed payload weak: rubric.criteria text missing");
    }
    if (type === "cloze" && !Array.isArray(component.options)) {
      warnings.push("typed payload missing: cloze.options");
    }
    if (type === "cloze" && Array.isArray(component.options) && component.options.length < 2) {
      warnings.push("typed payload weak: cloze.options too short");
    }
    if (type === "cloze" && !component.answer) {
      warnings.push("typed payload missing: cloze.answer");
    }
    if (type === "cloze" && component.answer && !agentCardClozeAnswerMatchesOptions(component.answer, component.options || [])) {
      warnings.push("typed payload weak: cloze.answer must match options; for multi-blank use option answers joined by |");
    }
    if (type === "question_set" && !Array.isArray(component.questions)) {
      warnings.push("typed payload missing: question_set.questions");
    }
    if (type === "question_set" && Array.isArray(component.questions) && component.questions.length < 2) {
      warnings.push("typed payload weak: question_set.questions too short");
    }
    if (type === "question_set" && Array.isArray(component.questions) && component.questions.some((question) => {
      const options = Array.isArray(question?.options) ? question.options : [];
      return !question?.questionText || options.length < 2 || !isValidAgentQuestionCorrectAnswer(question.correctAnswer, options);
    })) {
      warnings.push("typed payload weak: question_set question invalid");
    }
    if (type === "sentence_builder" && (!Array.isArray(component.items) || component.items.length < 2)) {
      warnings.push("typed payload weak: sentence_builder.items too short");
    }
    if (type === "sentence_builder" && !component.answer) {
      warnings.push("typed payload missing: sentence_builder.answer");
    }
    if (type === "ordering" && (!Array.isArray(component.items) || component.items.length < 2)) {
      warnings.push("typed payload weak: ordering.items too short");
    }
    if (type === "ordering" && !component.answer) {
      warnings.push("typed payload missing: ordering.answer");
    }
    if (type === "ordering" && component.answer && !agentCardOrderingAnswerMatchesItems(component.answer, component.items || [])) {
      warnings.push("typed payload weak: ordering.answer must match items in correct order using |");
    }
    if (type === "short_answer" && !component.text) {
      warnings.push("typed payload missing: short_answer.text");
    }
    if (type === "short_answer" && !component.answer) {
      warnings.push("typed payload missing: short_answer.answer");
    }
    if (type === "ethics" && !component.text && !(Array.isArray(component.items) && component.items.length)) {
      warnings.push("typed payload weak: ethics needs a scenario text or reflection items");
    }
    if ((type === "debate" || type === "error_hunt" || type === "storytelling" || type === "paraphrase") &&
      !component.text && !(Array.isArray(component.items) && component.items.length)) {
      warnings.push(`typed payload weak: ${type} needs text or items`);
    }
    if (excluded.has("examples") && agentCardComponentHasInlineExample(component, type)) {
      warnings.push("no examples requested but example content present");
    }
  });

  return agentCardQualityResult(warnings);
}

function agentCardComponentHasInlineExample(component = {}, type = "") {
  if (!AGENT_CARD_INLINE_EXAMPLE_SCAN_TYPES.has(type)) return false;
  const values = [
    component.text,
    ...(Array.isArray(component.items) ? component.items : []),
    ...(Array.isArray(component.pairs)
      ? component.pairs.flatMap((pair) => [pair?.left, pair?.right, pair?.hint])
      : [])
  ];
  return values.some(agentCardTextLooksLikeInlineExample);
}

function agentCardTextLooksLikeInlineExample(value = "") {
  const text = sanitizeAgentCardText(value);
  if (!text) return false;
  return /\b(?:e\.g\.|for example|example:)\b|(?:例如|例句[:：]?)/i.test(text) ||
    /(?:→|->)\s*(?:i|you|he|she|we|they|it|this|that)\b[^.;!?。！？]*(?:[.;!?。！？]|$)/i.test(text);
}

function countAgentCardComponentItemsForQuality(component = {}, type = "") {
  if (type === "question_set" || type === "reading") return Array.isArray(component.questions) ? component.questions.length : 0;
  if (type === "cloze") return countAgentCardClozeBlanks(component.text || "") || agentCardClozeAnswerParts(component.answer || "").length;
  if (type === "ordering") return Array.isArray(component.items) ? component.items.length : 0;
  if (type === "minimal_pair") return Array.isArray(component.pairs) ? component.pairs.length : 0;
  if (type === "word_family") return Array.isArray(component.tokens) ? component.tokens.length : 0;
  if (type === "rubric") return Array.isArray(component.criteria) ? component.criteria.length : 0;
  if (type === "writing_outline") return Array.isArray(component.steps) ? component.steps.length : 0;
  if (Array.isArray(component.items) && component.items.length) return component.items.length;
  if (Array.isArray(component.examples) && component.examples.length) return component.examples.length;
  if (Array.isArray(component.options) && component.options.length) return component.options.length;
  if (Array.isArray(component.pairs) && component.pairs.length) return component.pairs.length;
  if (Array.isArray(component.tokens) && component.tokens.length) return component.tokens.length;
  if (Array.isArray(component.steps) && component.steps.length) return component.steps.length;
  if (Array.isArray(component.criteria) && component.criteria.length) return component.criteria.length;
  return 0;
}

function requireComponent(types, warnings, type) {
  if (!types.has(type)) warnings.push(`component mismatch: expected ${type}`);
}

function requireSource(components, warnings, type, expectedSource) {
  const hasSource = components.some((component) =>
    normalizeCardToken(component?.type || component?.name) === type &&
    String(component?.source || "") === expectedSource
  );
  if (!hasSource) warnings.push(`source missing: ${expectedSource}`);
}

function agentCardQualityResult(warnings = []) {
  const uniqueWarnings = Array.from(new Set(warnings.filter(Boolean)));
  return {
    score: Math.max(0, 100 - uniqueWarnings.length * 25),
    warnings: uniqueWarnings
  };
}

function agentCardAnswerMatchesOptions(answer = "", options = []) {
  const normalizedAnswer = normalizeAgentAnswerLookupText(answer);
  if (!normalizedAnswer) return false;
  return (Array.isArray(options) ? options : [])
    .some((option) => normalizeAgentAnswerLookupText(option) === normalizedAnswer);
}

function agentCardClozeAnswerMatchesOptions(answer = "", options = []) {
  const answers = agentCardClozeAnswerParts(answer);
  if (!answers.length) return false;
  return answers.every((item) => agentCardAnswerMatchesOptions(item, options));
}

function agentCardClozeAnswerParts(answer = "") {
  const parts = String(answer || "")
    .split(/\s*(?:\||\/|,|;|、|，|；|\n|→|->)\s*/)
    .map((item) => item.trim())
    .filter(Boolean);
  return parts.length ? parts : [String(answer || "").trim()].filter(Boolean);
}

function isValidAgentQuestionCorrectAnswer(value, options = []) {
  if (value === null || value === undefined || value === "") return false;
  const index = Number(value);
  return Number.isInteger(index) && index >= 0 && index < options.length;
}

function normalizeRawCardToken(value) {
  return String(value || "").trim().toLowerCase().replace(/[-\s]+/g, "_");
}

function normalizeCardToken(value) {
  const token = normalizeRawCardToken(value);
  if (OLD_AGENT_CARD_TOKENS.has(token)) return "";
  return AGENT_CARD_COMPONENT_ALIAS_MAP.get(token) || token;
}

module.exports = {
  OLD_AGENT_CARD_TOKENS,
  AGENT_CARD_COMPONENT_REGISTRY,
  AGENT_CARD_COMPONENT_ALIAS_MAP,
  cappedEnforcedAgentCardCount,
  isNaturalMeaningQuizRequest,
  isSingleMeaningChoiceClozeRequest,
  isExplicitSingleMeaningChoicePayload,
  isChoiceAnswerCardRequest,
  isMultiQuestionChoiceCardRequest,
  isDialogueQuestionSetRequest,
  isExplicitClozeChoiceCardRequest,
  isPlainTranscriptAgentCardRequest,
  isSentenceTranscriptDeniedAgentCardRequest,
  isSentenceTranscriptAgentCardRequest,
  inferAgentCardExcludedComponents,
  enforceExplicitChoiceComponentScope,
  countAgentCardClozeBlanks,
  AGENT_CARD_INLINE_EXAMPLE_SCAN_TYPES,
  isNoAudioAgentCardRequest,
  inferWordBankClozeChoiceCounts,
  assessAgentCardQuality,
  agentCardComponentHasInlineExample,
  agentCardTextLooksLikeInlineExample,
  countAgentCardComponentItemsForQuality,
  requireComponent,
  requireSource,
  agentCardQualityResult,
  agentCardAnswerMatchesOptions,
  agentCardClozeAnswerMatchesOptions,
  agentCardClozeAnswerParts,
  isValidAgentQuestionCorrectAnswer,
  normalizeRawCardToken,
  normalizeCardToken,
};
