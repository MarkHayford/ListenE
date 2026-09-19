const { settings } = require("../config");
const { buildUserModelHint } = require("./userModel");
const {
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
  agentCardClozeAnswerParts,
  isValidAgentQuestionCorrectAnswer,
  normalizeRawCardToken,
  normalizeCardToken
} = require("./agentCardChoice");
const {
  enforceRequestedVocabularyCount
} = require("./agentCardVocabExtra");
const {
  pickDefaultMinimalPairs,
  isStrictStaticMinimalPairRequest,
  isMinimalPairQuestionSetRequest,
  isExplicitWeakMinimalPairRequest,
  isStaticMinimalPairRequiredScope,
  enforceRequestedMinimalPairCount,
  enforceMinimalPairComponentScope,
  isFakeMinimalPairPlaybackInstruction,
  buildMinimalPairQuestionSetComponent,
  agentMinimalPairQuestionLooksSemantic,
  buildRequiredMinimalPairComponent,
  normalizeAgentCardPairs,
  normalizeAgentCardPairObjects
} = require("./agentCardMinimalPair");
const {
  isExplicitScenarioCardRequest,
  enforceScenarioQuestionSetCurrentRequestScope,
  enforceScenarioQuestionSetScope,
  inferScenarioPromptCount,
  isFinalEdPronunciationContext,
  isThPronunciationContext,
  enforceRequestedScenarioComponentCount,
  enforceScenarioParticipantCountScope,
  shouldConstrainHotelCheckInScenario,
  agentCardListFallbackItems,
  buildFallbackInterviewScenarioComponent
} = require("./agentCardScenario");
const {
  isReadingPassageQuizRequest,
  isSingleReadingChoiceRequest,
  enforceReadingPassageCurrentRequestScope,
  isUsableReadingPassageText,
  readingTextMentionsExcludedPriorTopic,
  readingCardHeadingNeedsCurrentRequestRepair,
  readingCardSubtitleForRequest,
  buildFallbackReadingPassageComponent,
  buildFallbackReadingQuestionSetComponent,
  buildFallbackSingleReadingChoiceComponent,
  placeCorrectAgentQuestionOption,
  inferAgentRequestedOptionCount
} = require("./agentCardReading");
const {
  isShortAnswerOnlyAgentCardRequest,
  isFuzzyShortAnswerPracticeRequest,
  isShortAnswerNegatedRequest,
  buildWritingInputShortAnswerComponent,
  inferWritingTaskDetails,
  isShortAnswerRequiredScope,
  buildRequiredShortAnswerComponent,
  enforceNoAudioShortAnswerComponentScope,
  enforceShortAnswerCurrentRequestScope,
  enforceShortAnswerNoAnswerLeakComponentScope,
  textContainsShortAnswer,
  findAgentCardShortAnswerAnswer,
  visibleShortAnswerTextWithoutAnswerLeak,
  isNoAudioShortAnswerScope,
  shouldSuppressAgentCardAnswerExplanation,
  inferShortAnswerFromMessage
} = require("./agentCardShortAnswer");
const {
  isSentenceBuilderNegatedRequest,
  isSentenceBuilderOnlyAgentCardRequest,
  isSentenceBuilderRequiredScope,
  buildRequiredSentenceBuilderComponent,
  enforceSentenceBuilderNoUsageTextScope
} = require("./agentCardSentenceBuilder");
const {
  normalizeAttachment,
  hasMultimodalAttachments,
  buildUserContentWithAttachments,
  callMimoChatRaw,
  callMimoChatStream,
  extractJsonFromContent
} = require("./mimoAgentMedia");
const { createReplyFieldStreamExtractor } = require("./replyFieldStream");
const {
  fixBasicEnglishArticleText,
  sanitizeAgentCardText,
  compactAgentCardText,
  trimDanglingAgentCardWords,
  compactAgentCardLabel,
  cleanAgentCardDanglingPunctuation,
  escapeRegExp,
  sanitizeAgentCardSource
} = require("./agentCardText");
const {
  FOCUSED_AGENT_CARD_COMPONENT_SPEC,
  AGENT_CARD_COMPONENT_KEYWORDS
} = require("./agentCardSchemas");

const {
  normalizeAgentAnswerLookupText,
  normalizeAgentStringArray,
  cleanAgentQuestionText,
  cleanAgentClozeInstructionLeak,
  stripAgentQuestionOptionLabel,
  stripAgentReplyDecorativeEmoji,
  stripNoAudioPlaybackSentences,
  stripQuestionSetExplanationsForScope,
  stripSentenceBuilderUsageInstruction,
  trimIncompleteAgentCardSentence
} = require("./textNormalize");

const {
  isLongFormCardRequest,
  isWordBankClozePracticeCardRequest,
  isCetWordBankClozeRequest,
  hasAffirmativeListeningMaterialCue,
  isListeningMaterialNegated,
  isFuzzyWritingPracticeRequest,
  isFuzzyReadingPracticeRequest,
  isFuzzySpeakingPracticeRequest,
  isFuzzyScenarioPracticeRequest,
  inferRequestedDialogueParticipantCount,
  inferExplicitScenarioConstraint
} = require("./intent");

const {
  inferExplicitChoiceConstraint,
  inferExplicitClozeConstraint,
  inferExplicitTranslationConstraint,
  inferExplicitListeningCueConstraint,
  uniqueAgentCardTexts,
  inferExplicitMinimalPairs,
  inferExplicitPhraseTargets,
  inferAgentRequestedQuestionCount,
  inferRequestedAgentCardItemCounts,
  agentCardComponentTextMaxLength
} = require("./explicitConstraints");

const {
  normalizeNoAudioShortAnswerCardTitle,
  agentCardTitleForPresentComponents,
  questionSetCardTitleForRequest,
  readingCardTitleForRequest,
  neutralizeNoAudioCueText,
  cetWordBankClozeTitle
} = require("./cardTitles");

const {
  isOrderingCardRequest,
  isOrderingRequiredScope,
  buildRequiredOrderingComponent,
  buildFallbackOrderingComponent,
  agentCardItemMaxLength
} = require("./ordering");

const {
  deriveAgentCardPairsFromItems,
  deriveAgentCardTokensFromItems,
  deriveAgentCardOptionsFromItems,
  deriveAgentCardLabeledObjectsFromItems,
  splitAgentCardPairText
} = require("./cardItemDerivations");

const {
  isAgentPromptOnlyRequest,
  isSingleSpeakingPromptOnlyRequest,
  buildFallbackSpeakingPromptComponent,
  enforceRequestedSpeakingPromptCount,
  enforceSpeakingPromptComponentScope
} = require("./speakingPrompts");

const {
  mergeMistakePatternIntoCorrectionOnlyCard,
  isCorrectionOnlyAgentCardRequest,
  isEditableCorrectionShortAnswerRequest,
  correctionFallbackItems,
  inferAgentRequestedCorrectionCount,
  enforceCorrectionCoreOnlyScope
} = require("./correction");

const {
  enforceExplicitTranslationComponentScope,
  enforceRequestedTranslationComponentCount
} = require("./translation");

const { callMimoText } = require("./mimoCore");
const {
  generateListeningContent,
  speechRateHint,
  normalizeVoiceGender,
  buildVoiceProfileWithDirectives,
  resolveListeningGenerationConstraints,
  enforceListeningGenerationConstraints,
  parseCountToken,
  firstCountMatch
} = require("./listeningGenerate");

const {
  sanitizeAgentMissingQuestionExportReply,
  normalizeAgentOutputFiles,
  forceAgentOutputFormatForRequest,
  useHistoryContentForExport,
  useListeningZipContentForExport,
  shouldUseHistoryContentForExport,
  cleanAgentExportDocumentContent,
  extractAgentQuestionOnlyExportContent,
  shouldUseAnswerKeyQuestionExport,
  buildAgentAnswerKeyQuestionExportContent,
  parseAgentQuestionExportBlocks,
  normalizeAgentQuestionExportAnswerKey,
  normalizeAgentExportOptionLetter,
  inferAgentOutputFileFallbackContent,
  extractAgentExportableContentFromRecentMessages,
  extractAgentQuestionExportContent,
  extractAgentQuestionExportContentFromRecentMessages,
  extractAgentLooseHistoryExportContent,
  isLikelyAgentQuestionExportContent,
  isLikelyAgentExportableContent,
  isLikelyAgentPracticeExportContent,
  isAgentQuestionExportSuggestionText,
  agentExportTextMatchesRequest,
  shouldPreferRecentMessagesForQuestionExport,
  buildAgentQuestionExportContentFromRecord,
  buildAgentListeningZipExportContent,
  inferAgentListeningZipAudioFileName,
  buildAgentQuestionExportContentFromRecordForRequest,
  buildAgentQuestionExportContentFromCardSpecForRequest,
  recordMatchesQuestionExportRequest,
  shouldIncludeQuestionExportExplanations,
  formatAgentExportQuestion,
  inferAgentExportQuestionAnswerIndex,
  normalizeAgentExportAnswer,
  agentExportOptionLetter,
  extractAgentCardExportBlock,
  isAgentOutputFileRequest,
  isAgentQuestionExportRequest,
  isAgentListeningZipExportRequest,
  isAgentHistoryExportRequest,
  normalizeAgentExportRequestText,
  stripNegatedAgentExportObjectTerms,
  hasAgentExportActionCue,
  hasAgentQuestionExportActionCue,
  hasAgentNamedFileExportCue,
  normalizeAgentOutputFile,
  inferAgentOutputFormat,
  sanitizeAgentOutputFormat,
  sanitizeAgentOutputFileName,
  outputMimeType,
} = require("./agentOutputExport").createAgentOutputExport({
  normalizeAgentCardSpec,
  normalizeAgentStringArray,
  stripAgentQuestionOptionLabel,
  isStrictRegisterOnlyAgentCardRequest
});

const AGENT_CARD_COMPONENT_TYPES = AGENT_CARD_COMPONENT_REGISTRY.map((item) => item.type);
const AGENT_CARD_COMPONENT_TYPE_SET = new Set(AGENT_CARD_COMPONENT_TYPES);

// 意图识别正则常量已抽取到 ./agentCardPatterns.js（逐字保持一致）。
const {
  AGENT_VOCABULARY_INTENT_PATTERN,
  AGENT_PHRASE_INTENT_PATTERN,
  AGENT_LISTENING_CUE_INTENT_PATTERN,
  AGENT_MINIMAL_PAIR_INTENT_PATTERN,
  AGENT_WORD_FAMILY_INTENT_PATTERN,
  AGENT_REGISTER_INTENT_PATTERN,
  AGENT_GRAMMAR_INTENT_PATTERN,
  AGENT_TRANSLATION_INTENT_PATTERN,
  AGENT_COMPARE_INTENT_PATTERN,
  AGENT_PRONUNCIATION_INTENT_PATTERN,
  AGENT_CORRECTION_INTENT_PATTERN,
  AGENT_ETHICS_INTENT_PATTERN,
  AGENT_DEBATE_INTENT_PATTERN,
  AGENT_ERROR_HUNT_INTENT_PATTERN,
  AGENT_STORYTELLING_INTENT_PATTERN,
  AGENT_PARAPHRASE_INTENT_PATTERN,
  AGENT_WEAKNESS_PRACTICE_CUE_PATTERN,
  AGENT_UNSPECIFIED_WORD_OR_SENTENCE_PATTERN
} = require("./agentCardPatterns");

const AGENT_CARD_STRUCTURAL_COMPONENTS = new Set([
  "header",
  "chips",
  "summary",
  "progress",
  "suggestion",
  "divider",
  "actions"
]);

const AGENT_CARD_MATERIAL_CORE_ORDER = [
  "header",
  "audio",
  "transcript",
  "question_preview",
  "suggestion",
  "actions"
];

const AGENT_CARD_ANALYSIS_CORE = new Set([
  "header",
  "feedback",
  "correction",
  "mistake_pattern",
  "rubric",
  "suggestion",
  "actions"
]);

const AGENT_CARD_COMPONENT_LABELS = new Map([
  ["audio", "音频"],
  ["question_preview", "题目"],
  ["transcript", "原文"],
  ["sentence_transcript", "逐句点播"],
  ["feedback", "反馈"],
  ["vocabulary", "词汇"],
  ["phrase", "短语"],
  ["grammar", "语法"],
  ["translation", "翻译"],
  ["examples", "例句"],
  ["pronunciation", "发音"],
  ["cloze", "填空"],
  ["short_answer", "输入答案"],
  ["sentence_builder", "组句"],
  ["ordering", "排序"],
  ["question_set", "题组"],
  ["reading", "阅读理解"],
  ["gap_match", "选句填空"],
  ["chart_writing", "图表作文"],
  ["compare", "对比"],
  ["correction", "纠错"],
  ["rubric", "评分标准"],
  ["listening_cue", "听力信号"],
  ["minimal_pair", "音素对照"],
  ["word_family", "词族"],
  ["scenario", "场景表达"],
  ["register", "语气转换"],
  ["speaking_prompt", "口语提示"],
  ["writing_outline", "写作提纲"],
  ["mistake_pattern", "错因模式"],
  ["ethics", "道德思辨"],
  ["debate", "思辨辩论"],
  ["error_hunt", "校对找错"],
  ["storytelling", "故事创作"],
  ["paraphrase", "同义改写"]
]);

// AGENT_CARD_COMPONENT_KEYWORDS（组件关键词目录）已抽取到 ./agentCardSchemas.js。

const AGENT_CARD_REDUNDANT_TRANSCRIPT_PATTERN = /(view|show|open|read)[_\s-]*(transcript|script)|\b(transcript|script)\b|查看.{0,6}(原文|文本)|(打开|显示|展示|阅读|看).{0,6}(原文|文本)|(原文|文本).{0,6}(查看|打开|显示|展示|阅读)|[「《]?(原文|文本)[」》]?卡片|原文卡/i;
const AGENT_CARD_REDUNDANT_QUESTION_PATTERN = /(try|answer|check|open|start|do).{0,12}(questions?|quiz)|\bquestions?\b|\bquiz\b|(开始|打开|练习|完成|核对|查看|做).{0,6}(题目|问题|习题|测验)|(题目|问题|习题|测验).{0,6}(开始|打开|练习|完成|核对|查看|做)/i;
const AGENT_CARD_FORBIDDEN_FALLBACK_TEXT_PATTERN = /AI\s*卡片已重置|稳定练习卡片|坏卡片|card\s*reset|parse\s*failed|修复失败|解析失败|stable\s+practice\s+card/i;

const agentCardTelemetry = {
  invalidCardCount: 0,
  repairAttemptCount: 0,
  repairSuccessCount: 0,
  fallbackCount: 0,
  qualityWarningCount: 0,
  lastQualityWarnings: [],
  lastErrors: []
};

// ----------------- 翻译 / 错题分析 -----------------

const translateText = async (text) => {
  const data = await callMimoText([
    { role: "system", content: "Translate the user text to Chinese. Return ONLY a JSON object with the key 'translation'." },
    { role: "user", content: String(text || "") }
  ], { temperature: 0.2 });
  return data.translation;
};

const {
  buildAnalysisPayload,
  evaluateAnswerSheet,
  normalizeAnalysisResultWithAnswerSheet
} = require("./analysisGrading");

const analyzeMistakes = async (payload) => {
  if (!settings.mimoApiKey) throw new Error("未配置 MIMO_API_KEY");

  const systemPrompt = `你是一位专业的英语听力学习 Agent。
请根据用户的听力练习表现（题目、原文、用户答案、正确答案）生成闭环学习诊断。
输出格式为 JSON，且必须严格包含以下字段：
{
  "summary": "字符串，综合诊断",
  "weakPoints": ["字符串数组，薄弱点列表"],
  "suggestions": ["字符串数组，提升建议"],
  "diagnosisTags": ["字符串数组，最多8个短标签，如 细节定位/主旨理解/推断能力/词汇识别/长句解析/语速适应"],
  "wrongQuestionInsights": [
    {
      "questionIndex": 0,
      "question": "题干",
      "selectedAnswer": "用户选择",
      "correctAnswer": "正确选项",
      "mistakeType": "错误类型短标签",
      "insight": "为什么错，以及下次如何听",
      "focusSentence": "原文中最该复听的一句英文",
      "startMs": 0,
      "endMs": 0,
      "startRatio": 0.0,
      "endRatio": 0.0
    }
  ],
  "nextActions": [
    {"title": "逐句听原文", "description": "下一步建议", "actionType": "relisten"}
  ],
  "reviewItems": [
    {"text": "英文单词或英文句子", "itemType": "word", "reason": "为什么要练"},
    {"text": "英文句子", "itemType": "sentence", "reason": "错题相关信息句"}
  ],
  "recommendedPlanTasks": []
}

要求：
- 只返回 JSON，不要 Markdown。
- 后端已经用程序判定 wrongQuestions，这是唯一可信的错题事实来源。
- wrongQuestionInsights 必须只解释 wrongQuestions 中列出的题号；不得把答错题说成全对。
- wrongQuestionInsights 只针对答错题；如果全对，可以为空。
- 每个 wrongQuestionInsights 必须尽量给出 focusSentence 在整段音频中的复听片段边界：
  - startMs/endMs 是估算毫秒，允许近似；如果无法估算可填 null。
  - startRatio/endRatio 是 focusSentence 在原文中的相对位置，0 到 1，小数即可；必须尽量提供。
  - 片段需要覆盖关键句前后 1-2 秒，适合用户直接复听。
- reviewItems 必须优先来自原文或题目，英文原样输出；它只是候选数据，不代表自动生成词句包。
- nextActions 最多 1 个，只给用户没有明确要求时的下一步建议，优先建议逐句听原文或复听定位。
- 不要默认生成生词生句、间隔复习或计划；recommendedPlanTasks 必须返回空数组。`;

  const slimPayload = buildAnalysisPayload(payload);
  const wrongQuestions = evaluateAnswerSheet(slimPayload);
  const analysisPayload = {
    ...slimPayload,
    grading: {
      totalQuestions: Array.isArray(slimPayload.questions) ? slimPayload.questions.length : 0,
      wrongCount: wrongQuestions.length,
      allCorrect: wrongQuestions.length === 0,
      wrongQuestionIndexes: wrongQuestions.map((item) => item.questionIndex)
    },
    wrongQuestions
  };
  const raw = await callMimoText([
    { role: "system", content: systemPrompt },
    { role: "user", content: JSON.stringify(analysisPayload) }
  ], { temperature: 0.3 });

  return normalizeAnalysisResultWithAnswerSheet(raw, slimPayload);
};

const {
  buildPracticeAnalysisPayload,
  normalizePracticeAnalysisResult
} = require("./practiceAnalyze");

// 练习卡（微元卡）作答分析：判分事实由客户端本地判定（correct 标记），模型只解释错因。
// 与听力 analyzeMistakes 同一闭环风格，但无音频/原文，不产出复听定位字段。
const analyzePracticeSheet = async (payload) => {
  if (!settings.mimoApiKey) throw new Error("未配置 MIMO_API_KEY");
  const payloadNorm = buildPracticeAnalysisPayload(payload);
  if (!payloadNorm.total) throw new Error("作答表为空，无法分析");

  const systemPrompt = `你是一位专业的英语学习 Agent。
用户刚完成一张练习卡（可能包含完形、填空、听写、排序、改错、翻译等多种题型），程序已判分。
请根据作答表生成学习诊断。输出格式为 JSON，且必须严格包含以下字段：
{
  "summary": "字符串，中文综合诊断（结合答对比例与错因共性）",
  "weakPoints": ["字符串数组，薄弱点列表"],
  "suggestions": ["字符串数组，提升建议"],
  "diagnosisTags": ["字符串数组，最多8个短标签，如 时态运用/词形变化/搭配记忆/拼写准确度/语序组织"],
  "wrongQuestionInsights": [
    {
      "questionIndex": 0,
      "question": "题干",
      "mistakeType": "错误类型短标签",
      "insight": "中文解释：为什么错、正确思路是什么、下次如何避免"
    }
  ]
}

要求：
- 只返回 JSON，不要 Markdown。
- wrongItems 是程序判出的唯一可信错题事实；wrongQuestionInsights 必须且只解释其中列出的 questionIndex。
- 全对时 wrongQuestionInsights 为空数组，summary 给出肯定与下一步提升方向。
- insight 结合 userAnswer 与 correctAnswer 的具体差异来讲，不要泛泛而谈。`;

  const raw = await callMimoText([
    { role: "system", content: systemPrompt },
    { role: "user", content: JSON.stringify(payloadNorm) }
  ], { temperature: 0.3 });

  return normalizePracticeAnalysisResult(raw, payloadNorm);
};

// 确定性组件脚手架：用聚焦分类器把“清晰可判的练习请求”映射到唯一主组件 + 数量 + 严格白名单。
// 不依赖 inferAgentCardScope（它对口语/邮件短语等会误判），返回 {primary, extras, count} 或 null。
// best-effort 数量上限：题组类内容重（每题含选项+解析），大数量易截断/降级回退到通用兜底卡，
// 故题组上限设 8；其它轻量组件（词汇/短语等）维持 12。用户要更多时按上限尽力而为，
// 优先保证卡片始终贴主题、可渲染，而不是为凑数硬失败。
function classifyForcedAgentCardPrimary(message = "") {
  const text = String(message || "");
  const lower = text.toLowerCase();
  // 剥离否定片段，避免“不要题组 / 不要音频 / no audio / without quiz”里的关键词触发误判。
  const pos = lower.replace(/(?:不要|不需要|别|没有|无须|无需|去掉|去除|no\s+|without\s+|don'?t\s+(?:want|need|add|include))[^，。,.;；!！?？\n]{0,20}/gi, " ");
  const counts = (() => { try { return inferRequestedAgentCardItemCounts(message) || {}; } catch (_) { return {}; } })();
  const qCount = (() => { try { return inferAgentRequestedQuestionCount(message) || 0; } catch (_) { return 0; } })();
  const pick = (primary, extras = [], count = 0) => ({ primary, extras, count: cappedEnforcedAgentCardCount(primary, count) });

  if (isOrderingCardRequest(pos)) return pick("ordering");
  if (/sentence\s*builder|word\s*order|reorder\s+words?|组句|连词成句|重新排成句子|单词顺序|词语排序|词块排序/i.test(pos)) return pick("sentence_builder");
  if (/dictation|type\s+the\s+sentence|listen\s+and\s+type|short\s*answer|free\s*response|默写|听写|输入答案|短答|打字/i.test(pos)) return pick("short_answer");

  // 语音/口语类优先于题型判断（这类请求常带被否定的“题组/音频”等词）。
  if (/最小对立|minimal\s*pair|辨音|听辨|易混音|长短音|ship\s*\/?\s*sheep|live\s*\/?\s*leave|\/ɪ\/|\/iː\//i.test(pos)) {
    let needsPron = false;
    try { needsPron = /发音|pronunciation|音标|连读|重音/i.test(pos) && !isStrictStaticMinimalPairRequest(message); } catch (_) { needsPron = false; }
    return pick("minimal_pair", needsPron ? ["pronunciation"] : []);
  }
  if (/发音|音标|连读|重音|pronunciation/i.test(pos)) return pick("pronunciation");
  if (/口语|speaking|\boral\b|话题提示|口语提示|speaking\s*prompt/i.test(pos)) return pick("speaking_prompt");
  if (/道德|伦理|品德|价值观|美德|道德两难|道德困境|道德情境|道德思辨|品格教育|moral(?:ity)?|ethics?|ethical\s*dilemma|character\s*education/i.test(pos) || (/思辨/.test(pos) && !AGENT_DEBATE_INTENT_PATTERN.test(pos))) return pick("ethics", [], counts.ethics || 0);
  if (AGENT_ERROR_HUNT_INTENT_PATTERN.test(pos)) return pick("error_hunt", [], 0);
  if (AGENT_DEBATE_INTENT_PATTERN.test(pos)) return pick("debate", [], 0);
  if (AGENT_STORYTELLING_INTENT_PATTERN.test(pos)) return pick("storytelling", [], 0);
  if (AGENT_PARAPHRASE_INTENT_PATTERN.test(pos)) return pick("paraphrase", [], counts.paraphrase || 0);

  // 七选五/选句填空、图表作文：必须在阅读/选择题判断之前（“七选五”含“选”，避免误判为选择题）。
  if (/七选五|选句填空|句子还原|选句还原|还原句子|gap\s*match|seven\s*choose\s*five|seven\s*select\s*five|sentence\s*restoration|sentence\s*insertion/i.test(pos)) return pick("gap_match", [], 0);
  if (/图表作文|看图作文|图表描述|图表写作|图表题|描述图表|图表[^，。!?\n]{0,6}(?:作文|写作|描述)|(?:描述|写一?篇?|看图)[^，。!?\n]{0,6}图表|chart\s*writing|graph\s*writing|chart\s*essay|chart\s*description|task\s*1\s*writing/i.test(pos)) return pick("chart_writing", [], 0);

  const readingLike = /阅读理解|reading\s+comprehension|reading\s+quiz/i.test(pos) || (/短文|passage|文章/i.test(pos) && /题|question|quiz/i.test(pos));
  const clozeLike = /选词填空|word\s*bank|完形|cloze|fill[-\s]*in[-\s]*the[-\s]*blank|填空|挖空/i.test(pos);
  const choiceLike = /选择题|单选|含义选择|meaning\s+choice|multiple\s*choice|mcq|choose|选项|quiz|题组|做题|出题|习题|几道题|道题|道选择/i.test(pos);

  if (readingLike) return pick("reading", [], counts.question_set || qCount);
  if (clozeLike) return pick("cloze", [], counts.cloze || 0);
  if (choiceLike) {
    const multi = (counts.question_set || qCount) >= 2 || /题组|几道|多道|[2-9]\s*道|[2-9]\s*题|multiple\s+questions/i.test(pos);
    const single = !multi && (/一道|1\s*道|一题|1\s*题|单个|a\s+single|one\s+(?:question|choice|mcq)/i.test(pos) || (counts.question_set || qCount) === 1);
    if (single) {
      // 显式写“question set/题组”的单题用 question_set(1)；“含义选择/单词选择题”用 cloze。
      if (/question\s*set|题组/i.test(pos)) return pick("question_set", [], 1);
      return pick("cloze");
    }
    return pick("question_set", [], counts.question_set || qCount);
  }

  if (/评分标准|评分量规|评分细则|评分维度|打分标准|评分表|评分卡|\brubric\b|scoring\s*(?:rubric|guide|criteria|sheet)|grading\s*(?:rubric|criteria)|marking\s*(?:scheme|criteria)/i.test(pos)) return pick("rubric");
  if (/写作|作文|essay|outline|提纲|writing/i.test(pos)) return pick("writing_outline", ["summary", "short_answer"]);
  if (/翻译|translate|英译中|中译英|译成|译文/i.test(pos)) return pick("translation");
  if (/改错|纠错|纠正|改正|correction/i.test(pos)) return pick("correction");
  if (/词族|派生词?|词根|词缀|word\s*family/i.test(pos)) return pick("word_family");
  if (/信号词|连接词|衔接词|听力线索|listening\s*cue|(?:转折|因果).{0,6}(?:词|信号)/i.test(pos)) return pick("listening_cue");
  if (/场景|情景|scenario|situational/i.test(pos)) return pick("scenario");
  if (/语气|正式|非正式|register|语域/i.test(pos)) return pick("register");
  if (/短语|搭配|词组|\bphrases?\b|collocation|idioms?/i.test(pos)) return pick("phrase", [], counts.phrase || 0);
  if (/单词|词汇|生词|vocab/i.test(pos)) return pick("vocabulary", [], counts.vocabulary || 0);
  if (/语法|grammar|时态|被动|虚拟语气|从句|冠词|介词/i.test(pos)) return pick("grammar");
  if (/区别|对比|辨析|difference|\bvs\b/i.test(pos)) return pick("compare");
  return null;
}

// 只对“本就应是单核心组件”的请求类型做确定性强制；复合卡（语法+例句、错因+纠错等）交给原管线，避免回归。
const ENFORCEABLE_FORCED_PRIMARIES = new Set([
  "vocabulary", "phrase", "question_set", "cloze", "short_answer",
  "sentence_builder", "ordering", "speaking_prompt", "minimal_pair", "ethics",
  "debate", "error_hunt", "storytelling", "paraphrase",
  "reading", "gap_match", "chart_writing", "rubric"
]);

// FOCUSED_AGENT_CARD_COMPONENT_SPEC（组件字段规范模板）已抽取到 ./agentCardSchemas.js。

// 长篇/重内容卡片（阅读短文、七选五、图表作文）正文长，token 预算需按“是否要求长文 + 题量”放大，
// 否则正文被 max_tokens 截断 → cardSpec JSON 解析失败 → 回退到短兜底卡（这正是“长阅读变短”的根因）。
const HEAVY_PASSAGE_CARD_PRIMARIES = new Set(["reading", "gap_match", "chart_writing"]);
// base 为原有预算；命中重内容/阅读时抬到“正文足量”的水平，要求“长文”再上探，统一封顶 8000。
function heavyCardMaxTokens(message = "", cls = null, base = 2200) {
  const safeBase = Math.max(0, Number(base) || 0);
  const count = Math.max(1, Number(cls && cls.count) || 1);
  const primary = String((cls && cls.primary) || "");
  const heavy = HEAVY_PASSAGE_CARD_PRIMARIES.has(primary) || isFuzzyReadingPracticeRequest(message);
  if (!heavy) return safeBase;
  const budget = (isLongFormCardRequest(message) ? 5200 : 3600) + count * 420;
  return Math.min(8000, Math.max(safeBase, budget));
}
// 主聊天调用处用：先尽力分类出主组件，再据此给出合适的 max_tokens（非重内容仍回落到 base）。
function agentChatPrimaryMaxTokens(message = "", base = 2200) {
  let cls = null;
  try { cls = classifyForcedAgentCardPrimary(message); } catch (_) { cls = null; }
  return heavyCardMaxTokens(message, cls, base);
}

// 聚焦重生成：用一个极小、单一职责的提示词只生成需要的组件，命中率远高于巨型主提示词。
async function regenerateForcedAgentCardComponents(message = "", cls = null) {
  if (!cls || !cls.primary) return null;
  const types = Array.from(new Set([cls.primary, ...(cls.extras || [])]));
  const specLines = types.map((t) => `- ${t}: ${FOCUSED_AGENT_CARD_COMPONENT_SPEC[t] || `{"type":"${t}"}`}`);
  const countNote = cls.count > 0 ? `\n核心组件 ${cls.primary} 的条目数必须恰好 ${cls.count} 个。` : "";
  const prompt = `为下面这条英语学习请求生成练习卡片组件，只返回 JSON 对象：{"components":[...]}。
必须且只能包含这些组件，按此顺序，每种各一个：${types.join("、")}。
字段规范：
${specLines.join("\n")}
内容要精确贴合用户请求的主题/对象/难度，答案必须正确。${countNote}
用户请求：${message}
只返回 JSON，不要解释，不要 markdown。`;
  let resp;
  try {
    resp = await callMimoChatRaw([
      { role: "system", content: "你是英语练习卡片组件生成器，只返回一个 JSON 对象。" },
      { role: "user", content: prompt }
    ], { model: settings.mimoTextModel, temperature: 0.2, maxTokens: heavyCardMaxTokens(message, cls, Math.min(8000, Math.max(2000, (Number(cls.count) || 1) * 360 + 1200))), json: true });
  } catch (_) {
    return null;
  }
  let parsed;
  try {
    parsed = extractJsonFromContent(resp && resp.choices && resp.choices[0] && resp.choices[0].message && resp.choices[0].message.content);
  } catch (_) {
    return null;
  }
  const comps = Array.isArray(parsed && parsed.components)
    ? parsed.components.filter((c) => c && typeof c === "object" && c.type)
    : [];
  if (!comps.length) return null;
  return {
    schemaVersion: 1,
    kind: "custom",
    title: String(cls.primary),
    components: [{ type: "header" }, ...comps, { type: "actions" }],
    actions: [{ id: "continue_practice", label: "继续练习", prompt: "继续生成下一张练习卡片", primary: true }]
  };
}

// 确定性强制骨架：先按白名单剪掉模型乱加的内容组件；若核心组件缺失/数量不足，再聚焦重生成。
// 重生成结果只做 normalize，不过 enforceAgentCardScope（它对口语/辨音等会误删核心组件）。
async function enforceForcedAgentCardSkeleton(message = "", cardSpec = null) {
  if (!cardSpec || !Array.isArray(cardSpec.components)) return null;
  let cls;
  try {
    cls = classifyForcedAgentCardPrimary(message);
  } catch (_) {
    return null;
  }
  if (!cls || !cls.primary) return null;
  if (!ENFORCEABLE_FORCED_PRIMARIES.has(cls.primary)) return null;
  const whitelist = new Set([cls.primary, ...(cls.extras || [])]);
  const kept = cardSpec.components.filter((c) => {
    const t = String((c && c.type) || "");
    return AGENT_CARD_STRUCTURAL_COMPONENTS.has(t) || whitelist.has(t);
  });
  const pruned = kept.length !== cardSpec.components.length;
  const COUNT_ARRAY_KEY = { question_set: "questions", reading: "questions", vocabulary: "items", phrase: "items", ethics: "items", paraphrase: "items" };
  const primaryComp = kept.find((c) => String((c && c.type) || "") === cls.primary);
  const extrasPresent = (cls.extras || []).every((t) => kept.some((c) => String((c && c.type) || "") === t));
  let sufficient = Boolean(primaryComp) && extrasPresent;
  if (sufficient && cls.count > 0 && COUNT_ARRAY_KEY[cls.primary]) {
    const arr = primaryComp[COUNT_ARRAY_KEY[cls.primary]];
    sufficient = Array.isArray(arr) && arr.length >= cls.count;
  }
  if (sufficient) {
    if (!pruned) return null;
    return { cardSpec: normalizeAgentCardSpec({ ...cardSpec, components: kept }) };
  }
  // 单次重生成不稳，会以两种方式不达标：① 大数量题组只回少量题（请求 8 却只回 3）；
  // ② 主组件+附属组件请求（如 minimal_pair+pronunciation）偶尔漏掉附属组件而整卡判不合格。
  // 故做有界重试：计数类主组件跨多次累积去重条目凑够目标数；需要 extras 时多试几次并优先选“齐全”候选，
  // 从根上消除“某一次抖动”确定性地把卡退回不达标状态。
  const countKey = COUNT_ARRAY_KEY[cls.primary];
  const targetCount = cls.count > 0 && countKey ? cls.count : 0;
  const requiredTypes = [cls.primary, ...(cls.extras || [])];
  const needExtras = (cls.extras || []).length > 0;
  const primaryCompOf = (spec) =>
    spec && Array.isArray(spec.components)
      ? spec.components.find((c) => String((c && c.type) || "") === cls.primary) || null
      : null;
  const primaryItemsOf = (spec) => {
    const comp = primaryCompOf(spec);
    if (!comp) return -1;
    if (!countKey) return 1;
    return Array.isArray(comp[countKey]) ? comp[countKey].length : 0;
  };
  const hasAllRequired = (spec) => {
    const present = new Set(
      Array.isArray(spec && spec.components) ? spec.components.map((c) => String((c && c.type) || "")) : []
    );
    return requiredTypes.every((t) => present.has(t));
  };
  // 候选评分：优先“齐全（含 extras）”，其次主组件条目更多。
  const scoreSpec = (spec) => (hasAllRequired(spec) ? 1e6 : 0) + Math.max(0, primaryItemsOf(spec));
  // 去重键：题组按题干文本，词汇/短语等字符串条目按破折号/冒号前的首段（"word - 释义" 取 word）。
  const itemDedupKey = (item) => {
    if (item && typeof item === "object") {
      const t = item.questionText || item.question || item.text || item.prompt || "";
      return String(t).toLowerCase().replace(/[^\p{L}\p{N} ]/gu, "").replace(/\s+/g, " ").trim();
    }
    if (typeof item === "string") {
      return item.toLowerCase().split(/[-—:：]/)[0].replace(/\s+/g, " ").trim();
    }
    return "";
  };
  // 内容较重的单组件（阅读/七选五/图表作文）单次重生成偶有抖动，给多次重试以稳定命中。
  const heavyPrimary = cls.primary === "reading" || cls.primary === "gap_match" || cls.primary === "chart_writing";
  const maxAttempts = targetCount >= 6 || needExtras ? 4 : targetCount >= 4 ? 3 : (heavyPrimary || cls.primary === "rubric" ? 3 : 1);
  let best = null;
  let bestScore = -1;
  let bestCount = -1;
  let templateSpec = null; // 优先用“齐全”候选当并集载体（保住 extras）
  let templateAnySpec = null; // 兜底载体
  const mergedItems = [];
  const seenKeys = new Set();
  for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
    const forced = await regenerateForcedAgentCardComponents(message, cls);
    if (!forced) continue;
    const normalized = normalizeAgentCardSpec(forced);
    const comp = primaryCompOf(normalized);
    const complete = hasAllRequired(normalized);
    const score = scoreSpec(normalized);
    if (comp && score > bestScore) {
      best = normalized;
      bestScore = score;
      bestCount = primaryItemsOf(normalized);
    }
    if (countKey && targetCount) {
      if (comp && !templateAnySpec) templateAnySpec = normalized;
      if (comp && complete && !templateSpec) templateSpec = normalized;
      const arr = comp && Array.isArray(comp[countKey]) ? comp[countKey] : [];
      for (const it of arr) {
        const k = itemDedupKey(it);
        if (!k || seenKeys.has(k)) continue;
        seenKeys.add(k);
        mergedItems.push(it);
      }
      if ((complete || !needExtras) && mergedItems.length >= targetCount) break;
    } else if (complete) {
      break;
    }
  }
  // 跨多次累积的去重并集若比单次最佳更全，则用并集回填主组件（封顶目标数），从而稳定凑够题数；
  // 载体优先用“齐全”候选，以同时保住 extras。
  const carrier = templateSpec || templateAnySpec;
  if (countKey && targetCount && carrier && mergedItems.length > Math.max(0, bestCount)) {
    const mergedSpec = {
      ...carrier,
      components: carrier.components.map((c) =>
        String((c && c.type) || "") === cls.primary
          ? { ...c, [countKey]: mergedItems.slice(0, targetCount) }
          : c
      )
    };
    const normalizedMerged = normalizeAgentCardSpec(mergedSpec);
    if (scoreSpec(normalizedMerged) >= bestScore) {
      best = normalizedMerged;
      bestScore = scoreSpec(normalizedMerged);
      bestCount = primaryItemsOf(normalizedMerged);
    }
  }
  if (best) return { cardSpec: best };
  if (pruned) return { cardSpec: normalizeAgentCardSpec({ ...cardSpec, components: kept }) };
  return null;
}

function isStudyPlanRequest(message = "") {
  const text = String(message || "").trim();
  if (!text) return false;
  // 纯咨询/提问（怎么做计划、计划是什么）不算生成请求
  if (/^(?:怎么|如何|为什么|什么是|how\b|why\b|what\b)/i.test(text)) return false;
  // 只认「学习规划类名词」：泛化的“计划/安排”会把“不能参加周末计划”“提出补救安排”这类
  // 普通叙述（尤其写作题干）误判成计划生成请求。
  const planNoun = /学习计划|复习计划|背单词计划|刷题计划|训练计划|练习计划|计划表|日程表|日程|学习安排|复习安排|study\s*plan|schedule|routine|提醒我/i;
  if (!planNoun.test(text)) return false;
  const makeVerb = /制定|安排|生成|规划|帮我|给我|做(?:个|一个|一份|份)|来(?:个|一个|一份|份)|建(?:个|一个|一份)|定(?:个|一个|一份)|想要|我要|要(?:个|一个|一份|份)|设定|set\s*up|make|create|generate|build|plan/i;
  return makeVerb.test(text);
}

async function generateAgentStudyPlanItems(message) {
  const prompt = `你是英语学习规划师。根据用户需求制定一份可执行的学习计划，拆成若干"计划项"，每项是到点要提醒用户去做的学习任务。

用户需求：
${String(message || "").trim()}

只输出 JSON（不要 markdown）：
{
  "reply": "一句给用户看的中文回复，说明已安排好计划",
  "items": [
    { "title": "任务标题（简短具体，如：背 30 个四级核心词）", "detail": "可选一句话说明", "dayOffset": 0, "hour": 20, "minute": 0, "recurrence": "none" }
  ]
}

规则：
- dayOffset：相对今天的天数偏移（0=今天，1=明天…）。
- recurrence 只能是 none / daily / weekly。每天固定时间做的任务用 recurrence=daily 给一条即可，不要逐天罗列；每周一次用 weekly。
- 用户指定了时间就用用户的（如"每天晚上8点"→hour=20,minute=0,recurrence=daily）；没指定就给个合理时间（如 20:00）。
- hour 0-23，minute 0-59；title 具体可执行；条数控制在 1-20。`;
  const parsed = await callMimoText([
    { role: "system", content: "你只输出合法 JSON，不要 markdown 代码块。" },
    { role: "user", content: prompt }
  ], { temperature: 0.5, maxTokens: 3000 });
  if (!parsed || typeof parsed !== "object") return { reply: "", items: [] };
  const rawItems = Array.isArray(parsed.items) ? parsed.items : [];
  const items = rawItems.map((t) => ({
    title: String(t?.title || "").trim().slice(0, 120),
    detail: String(t?.detail || "").trim().slice(0, 400),
    dayOffset: Math.min(365, Math.max(0, parseInt(t?.dayOffset, 10) || 0)),
    hour: Math.min(23, Math.max(0, parseInt(t?.hour, 10) || 20)),
    minute: Math.min(59, Math.max(0, parseInt(t?.minute, 10) || 0)),
    recurrence: ["none", "daily", "weekly"].includes(String(t?.recurrence || "none").toLowerCase())
      ? String(t?.recurrence || "none").toLowerCase()
      : "none"
  })).filter((t) => t.title).slice(0, 20);
  return {
    reply: String(parsed.reply || "已为你安排好学习计划，可在「计划表」里查看并调整时间。").trim(),
    items
  };
}

// 单轮「出题即导出」增强辅助：用户一句话同时要求“出题”和“导出为文件”，
// 但没有可用的当前题卡 / 历史题目时，需要先生成题卡再导出。下面两个函数：
// 1) 把导出措辞从请求里剥离，得到纯“出题”请求（用于递归生成题卡）；
// 2) 判断该请求是否属于“全新生成 + 导出”（引用历史/既有题目的交给历史导出路径）。
function deriveAgentQuestionGenerationMessage(message = "") {
  let text = String(message || "");
  text = text.replace(
    /(?:并且|并|然后|接着|顺便|再|，|,|、|;|；|。|\.)?\s*(?:请|麻烦)?\s*(?:帮我|给我)?\s*(?:把(?:它|这些|其|结果|题目?)?|将(?:其|结果|题目?)?)?\s*(?:导出|输出|保存|下载|分享|发送|发给|做成|整理成|打包)\s*(?:成|为|到)?\s*(?:一[份个])?\s*(?:的)?\s*(?:docx|word\s*文档|word|文档|文件|txt|md|markdown|json|csv|html|zip|压缩包)\b/gi,
    " "
  );
  text = text.replace(/\b(?:export|save|download|share|send)\b[^.。;；\n]*?\b(?:docx|word|document|file|txt|md|markdown|json|csv|html|zip)\b/gi, " ");
  text = text.replace(/(?:导出|保存|下载|发送|发给|输出|打包)(?:成|为)?(?:docx|word|文档|文件|txt|md|markdown|json|csv|html|zip|压缩包)?/gi, " ");
  // 清掉剥离后残留的孤立“文档/文件”（如“…选择题docx文档”剥离 docx 后留下的“文档”）。
  text = text.replace(/(?:^|[\s，,、])(?:文档|文件)(?=$|[\s，,、。.])/g, " ");
  return text.replace(/\s+/g, " ").trim();
}

function isFreshQuestionGenerationExportRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (!isAgentQuestionExportRequest(text)) return false;
  // 引用既有/历史题目（“刚才/上一/这套/当前”等）时，应从对话历史导出，不在此新生成。
  if (/刚才|刚刚|上一|上面|上张|上份|前面|之前|这套|这张|这份|这些题|当前|已生成|previous|last|above|earlier|recent|current/i.test(text)) return false;
  const genText = deriveAgentQuestionGenerationMessage(text);
  if (!genText) return false;
  const englishGenerationQuiz = /\b(?:generate|make|create|give\s+me|write|build|design|prepare)\b[^.。\n]*?\b(?:quiz|questions?|mcqs?|multiple[-\s]?choice|test|worksheet|exam)\b/i.test(genText);
  return isFuzzyQuestionPracticeRequest(genText) ||
    isExplicitQuestionSetOnlyCardRequest(genText) ||
    isDialogueQuestionSetRequest(genText) ||
    isWordBankClozePracticeCardRequest(genText) ||
    englishGenerationQuiz;
}

// 练习卡导出小节标签：尽量用 isLikelyAgentExportableContent 能识别的英文小节名。
const AGENT_CARD_EXPORT_SECTION_LABEL = {
  vocabulary: "Vocabulary", phrase: "Phrases", cloze: "Cloze", question_set: "Questions",
  reading: "Reading", gap_match: "Gap Match", chart_writing: "Chart Writing", short_answer: "Short Answer",
  sentence_builder: "Sentence Builder", ordering: "Ordering", speaking_prompt: "Speaking Prompt",
  minimal_pair: "Minimal Pairs", pronunciation: "Pronunciation", grammar: "Grammar", translation: "Translation",
  compare: "Key Differences", writing_outline: "Writing Outline", correction: "Corrections", word_family: "Word Family",
  listening_cue: "Listening Cues", scenario: "Scenario", ethics: "Ethics", debate: "Debate", error_hunt: "Error Hunt",
  storytelling: "Storytelling", paraphrase: "Paraphrase", register: "Register", summary: "Summary"
};

// 把任意练习卡 cardSpec 序列化成“Agent card: …”文本块，供通用历史文本导出路径识别（覆盖全部练习类型）。
function serializeAgentCardSpecToExportText(cardSpec) {
  if (!cardSpec || typeof cardSpec !== "object") return "";
  const comps = Array.isArray(cardSpec.components) ? cardSpec.components : [];
  const title = sanitizeAgentCardText(cardSpec.title || "ListenE 练习卡").trim() || "ListenE 练习卡";
  const lines = [`Agent card: ${title}`];
  let sections = 0;
  for (const comp of comps) {
    if (!comp || typeof comp !== "object") continue;
    const type = String(comp.type || "");
    if (!type || AGENT_CARD_STRUCTURAL_COMPONENTS.has(type)) continue;
    const body = [];
    if (Array.isArray(comp.questions) && comp.questions.length) {
      comp.questions.forEach((q, idx) => {
        const qt = sanitizeAgentCardText((q && (q.questionText || q.question)) || "").trim();
        if (!qt) return;
        body.push(`${idx + 1}. ${qt}`);
        const opts = normalizeAgentStringArray(q.options, 200).map(stripAgentQuestionOptionLabel).slice(0, 8);
        opts.forEach((opt, oi) => body.push(`${agentExportOptionLetter(oi)}. ${opt}`));
        const ai = inferAgentExportQuestionAnswerIndex(q, opts);
        if (ai >= 0 && ai < opts.length) body.push(`Correct answer: ${agentExportOptionLetter(ai)}`);
        else { const at = sanitizeAgentCardText(q.answer || q.correctAnswerText || "").trim(); if (at) body.push(`Correct answer: ${at}`); }
      });
    }
    const text = sanitizeAgentCardText(comp.text || "").trim();
    if (text) body.push(text);
    normalizeAgentStringArray(comp.items, 400).forEach((it) => { const s = sanitizeAgentCardText(it).trim(); if (s) body.push(`- ${s}`); });
    if (Array.isArray(comp.pairs)) comp.pairs.forEach((p) => { if (p && (p.left || p.right)) body.push(`- ${sanitizeAgentCardText(p.left || "")} / ${sanitizeAgentCardText(p.right || "")}${p.hint ? ` (${sanitizeAgentCardText(p.hint)})` : ""}`); });
    if (Array.isArray(comp.tokens) && comp.tokens.length) body.push(`- ${comp.tokens.map((x) => sanitizeAgentCardText(x)).filter(Boolean).join(" / ")}`);
    if (Array.isArray(comp.steps)) comp.steps.forEach((s) => { if (s && (s.label || s.text)) body.push(`- ${sanitizeAgentCardText(s.label || "")}${s.label && s.text ? ": " : ""}${sanitizeAgentCardText(s.text || "")}`); });
    if (Array.isArray(comp.options) && comp.options.length && !(Array.isArray(comp.questions) && comp.questions.length)) {
      const opts = normalizeAgentStringArray(comp.options, 200).map((o) => sanitizeAgentCardText(o)).filter(Boolean);
      if (opts.length) body.push(`Options: ${opts.join(" / ")}`);
    }
    const answer = sanitizeAgentCardText(comp.answer || "").trim();
    if (answer) body.push(`Answer: ${answer}`);
    const explanation = sanitizeAgentCardText(comp.explanation || "").trim();
    if (explanation) body.push(`Explanation: ${explanation}`);
    if (body.length) {
      lines.push("", `${AGENT_CARD_EXPORT_SECTION_LABEL[type] || "Practice"}:`, ...body);
      sections += 1;
    }
  }
  return sections > 0 ? lines.join("\n").trim() : "";
}

// ②阶段3：把「微元卡」序列化成“Agent card: …”文本块（与 cardSpec 版同口径，供通用历史导出路径识别）。
// 退役 cardSpec 后，单轮「出题即导出」由此从微元卡产出导出文件，不再依赖 cardSpec。
function serializeMicroCardToExportText(card) {
  if (!card || typeof card !== "object" || !Array.isArray(card.nodes)) return "";
  const title = sanitizeAgentCardText(card.title || "ListenE 练习卡").trim() || "ListenE 练习卡";
  const lines = [`Agent card: ${title}`];
  let sections = 0;
  let qIndex = 0;
  for (const node of card.nodes) {
    if (!node || typeof node !== "object") continue;
    const type = String(node.type || "").trim().toLowerCase();
    const body = [];
    if (type === "text" || type === "passage") {
      const t = sanitizeAgentCardText(node.text || "").trim();
      if (t) body.push(t);
    } else if (type === "choice") {
      qIndex += 1;
      const prompt = sanitizeAgentCardText(node.prompt || "").trim();
      body.push(`${qIndex}. ${prompt || "选择题"}`);
      const opts = normalizeAgentStringArray(node.options, 200).map((o) => sanitizeAgentCardText(o)).filter(Boolean).slice(0, 8);
      opts.forEach((opt, oi) => body.push(`${agentExportOptionLetter(oi)}. ${opt}`));
      const answer = sanitizeAgentCardText(node.answer || "").trim();
      if (answer) body.push(`Correct answer: ${answer}`);
    } else if (type === "input") {
      const prompt = sanitizeAgentCardText(node.prompt || "").trim();
      if (prompt) body.push(prompt);
      const answer = sanitizeAgentCardText(node.answer || "").trim();
      if (answer) body.push(`Answer: ${answer}`);
    } else if (type === "order") {
      const items = normalizeAgentStringArray(node.items, 200).map((o) => sanitizeAgentCardText(o)).filter(Boolean);
      if (items.length) body.push(`Order: ${items.join(" / ")}`);
      const answer = sanitizeAgentCardText(node.answer || "").trim();
      if (answer) body.push(`Answer: ${answer}`);
      const explanation = sanitizeAgentCardText(node.explanation || "").trim();
      if (explanation) body.push(`Explanation: ${explanation}`);
    } else if (type === "tokens") {
      const t = sanitizeAgentCardText(node.text || "").trim();
      if (t) body.push(t);
      const correct = sanitizeAgentCardText(node.correct || "").trim();
      if (correct) body.push(`Correct: ${correct}`);
      const explanation = sanitizeAgentCardText(node.explanation || "").trim();
      if (explanation) body.push(`Explanation: ${explanation}`);
    } else if (type === "reveal") {
      const content = sanitizeAgentCardText(node.content || "").trim();
      if (content) body.push(`${sanitizeAgentCardText(node.label || "参考").trim() || "参考"}: ${content}`);
    }
    if (body.length) {
      lines.push("", ...body);
      sections += 1;
    }
  }
  return sections > 0 ? lines.join("\n").trim() : "";
}

// 放宽版「单轮生成任意练习 + 导出」识别：剥离导出措辞后仍是某类练习生成请求即成立。
function looksLikeFreshPracticeGeneration(genText = "") {
  const text = String(genText || "");
  if (!text.trim()) return false;
  return isExplicitAgentPracticeCardRequest(text) ||
    isFuzzyStudyComponentPracticeRequest(text) ||
    isFuzzyWritingPracticeRequest(text) ||
    isFuzzyReadingPracticeRequest(text) ||
    isFuzzySpeakingPracticeRequest(text) ||
    isFuzzyScenarioPracticeRequest(text) ||
    isFuzzyInterviewEnglishPracticeRequest(text) ||
    isFuzzyQuestionPracticeRequest(text) ||
    isDialogueQuestionSetRequest(text) ||
    isWordBankClozePracticeCardRequest(text) ||
    isExplicitQuestionSetOnlyCardRequest(text) ||
    isFuzzyEthicsPracticeRequest(text) ||
    isFuzzyParaphrasePracticeRequest(text) ||
    isFuzzyInnovativeModePracticeRequest(text) ||
    AGENT_ERROR_HUNT_INTENT_PATTERN.test(text) ||
    (/\b(?:generate|make|create|give\s+me|write|build|design|prepare)\b/i.test(text) &&
      /\b(?:quiz|questions?|vocabulary|words?|phrases?|grammar|translation|reading|cloze|dialogue|practice|exercise|worksheet|card|debate|story|paraphrase|outline)\b/i.test(text)) ||
    // 通用兜底：生成动词 + 练习类关键词（递归会用真实模型再确认是否产出题卡，故此 gate 稍宽也安全）。
    (/(?:给我|帮我|出|生成|来|做|写|整理|搞|弄)/.test(text) &&
      /练习|题目?|卡片?|测验|词汇|单词|短语|搭配|翻译|语法|时态|发音|阅读|写作|口语|听写|默写|对比|辨析|纠错|改错|填空|完形|七选五|作文|提纲|辩论|改写|故事|场景|情景/.test(text));
}

function isFreshPracticeGenerationExportRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (!isAgentOutputFileRequest(text)) return false;
  // 引用既有/历史内容（“刚才/这套/当前”等）的交给历史导出路径，不在此新生成。
  if (/刚才|刚刚|上一|上面|上张|上份|前面|之前|这套|这张|这份|这些题|当前|已生成|previous|last|above|earlier|recent|current/i.test(text)) return false;
  const genText = deriveAgentQuestionGenerationMessage(text);
  // 剥离后仍是文件/导出请求 => 没成功剥掉导出措辞，递归生成会再次被判成导出，直接放弃。
  if (!genText || isAgentOutputFileRequest(genText)) return false;
  return looksLikeFreshPracticeGeneration(genText);
}

const generateAgentChatReply = async (payload = {}, { onReplyDelta = null } = {}) => {
  if (!settings.mimoApiKey) throw new Error("未配置 MIMO_API_KEY");
  const message = String(payload.message || "").trim();
  const attachments = (Array.isArray(payload.attachments) ? payload.attachments : [])
    .map(normalizeAttachment)
    .filter(Boolean);
  if (!message && attachments.length === 0) throw new Error("message is required");
  // 学习计划请求：走独立的计划生成路径，输出结构化计划项（客户端写入计划表并排通知），
  // 不进入下面的练习卡/听力素材路由，避免相互干扰。
  if (isStudyPlanRequest(message)) {
    const plan = await generateAgentStudyPlanItems(message);
    if (plan.items.length) {
      return {
        reply: plan.reply || "已为你安排好学习计划，可在「计划表」里查看并调整时间。",
        intent: "study_plan",
        practiceNeed: "",
        cardSpec: null,
        outputFiles: [],
        planItems: plan.items
      };
    }
  }
  const recentMessages = Array.isArray(payload.recentMessages) ? payload.recentMessages : [];
  const workspaceTitle = String(payload.workspaceTitle || "").trim();
  const workspaceNeed = String(payload.workspaceNeed || "").trim();
  const workspaceContentType = String(payload.workspaceContentType || payload.contentType || "").trim().toLowerCase();
  const workspaceMemoryHint = buildAgentWorkspaceMemoryHint({
    memorySummary: payload.workspaceMemorySummary || payload.memorySummary,
    memory: payload.workspaceMemory || payload.memory,
    query: message
  });
  // #4B：统一用户模型（跨功能长期画像）作为全局上下文与工作区级记忆一并注入。
  const combinedMemoryHint = [workspaceMemoryHint, buildUserModelHint(payload.userModel)]
    .filter(Boolean)
    .join("\n\n");
  const currentRecordSummary = normalizeAgentCurrentRecordSummary(
    payload.currentRecordSummary || payload.recordSummary || payload.currentRecord
  );
  const multimodal = hasMultimodalAttachments(attachments);
  const outputFileRequested = isAgentOutputFileRequest(message);
  const contextHint = workspaceTitle
    ? `当前用户正在 ListenE 工作区中：${workspaceTitle}。工作区首句/原始需求：${workspaceNeed || "未提供"}。历史 contentType=${workspaceContentType || "未提供"} 只代表首句或最近素材的元数据，不是全局模式。你必须根据用户本轮消息独立决定 intent；同一个工作区里可以先答疑、再生成听力素材、再继续问答或按需组装练习卡片。`
    : "当前用户还没有进入任何工作区；如果本轮需要持久上下文，客户端会自动创建工作区。";
  const recordContextHint = currentRecordSummary
    ? `当前最近素材摘要：${JSON.stringify(currentRecordSummary)}。回答答题、原文、反馈、错因、词汇或复听相关请求时，优先基于这个素材摘要。`
    : "当前没有最近素材摘要。";

  const agentChatMessages = [
    {
      role: "system",
      content: `你是 ListenE，一个移动端英语学习 AI Agent。你的核心界面是聊天；你可以回答英语单词、句子、语法、翻译、表达区别、听力理解方法等问题。
${contextHint}
${combinedMemoryHint}
${recordContextHint}
${multimodal ? "本轮用户附带了图片、音频或视频。你必须先理解附件内容，再结合用户文字作答。附件理解使用 mimo-v2.5；普通文本推理使用 mimo-v2.5-pro。" : "本轮没有多模态附件，普通文本理解和推理使用 mimo-v2.5-pro。"}

重要规则：
- 只返回 JSON：
{
  "reply": "给用户看的聊天回复",
  "intent": "chat | new_listening_practice | practice_card",
  "practiceNeed": "如果要生成新听力素材，写入用户学习需求；否则为空",
  "outputFiles": [
    {
      "name": "文件名，带扩展名",
      "format": "docx | txt | md | json | csv | html",
      "mimeType": "MIME 类型",
      "content": "文件正文。docx 也只放纯文本或 Markdown 风格正文，客户端会生成真正 docx 文件"
    }
  ],
  "cardSpec": null
}
- 用户只是问英语问题时，直接回答，不要要求创建工作区。
- 不要根据工作区首句、标题、历史 contentType 或 currentStep 固定本轮 intent；这些只是上下文和状态，不是全局模式。
- 每条用户消息都独立判断：问语法、翻译、表达区别或学习方法时 intent=chat；明确要求生成听力素材、新素材、音频或听力训练时 intent=new_listening_practice；要求普通题目、答题卡片、逐句听、错因分析、词汇、语法等具体学习控件时 intent=practice_card。
- 用户要求 CET/四级/六级 word bank cloze / 选词填空 / cloze / fill-in-the-blank 练习时，除非明确肯定要求听力/音频素材，否则 intent 必须是 practice_card；明确写 no audio / without audio / 不要音频 / 无音频 / 只要题 时也必须是 practice_card，不能生成听力素材。
- 用户明确要求“生成/整理/发送/导出”为文档、docx、Word、txt、markdown、json、csv、html、zip 等文件时，intent 通常是 chat，并在 outputFiles 返回 1-3 个文件；reply 只简短说明文件已生成。
- outputFiles 支持 docx、txt、md、json、csv、html、zip；不要返回 PDF、PPT、XLSX 的二进制内容。用户要求 docx/Word 时，format 必须是 docx，mimeType 必须是 application/vnd.openxmlformats-officedocument.wordprocessingml.document。用户要求把听力素材导出为 zip 时，format 必须是 zip，content 写入可打包清单，至少包含 transcript.txt、questions.txt，以及 audioUrl 或 audio 文件条目。
- outputFiles.content 必须是可直接写入文件的完整正文；docx 的 content 不要写 base64、XML、ZIP 或二进制，只写文档正文。
- 用户没有明确要求生成文件时，outputFiles 必须为空数组。
- 如果用户上传图片、音频或视频并询问内容，intent 通常是 chat；除非用户明确要求生成听力素材或练习卡片。
- 用户明确要生成听力训练、听力素材、新音频、新素材、换一套听力或再生成听力时，intent 必须是 new_listening_practice；如果已有工作区，客户端会把新素材追加到当前工作区，不要说“不能再生成”。普通题目、只要题、不要音频的请求不属于新听力素材。
- 用户指定音色、音调、口音、语气或语速时，例如“温柔低音女声、英音、慢速”，practiceNeed 必须完整保留这些原始约束，供后续听力生成链路解析。
- 用户指定对话人数、题目数量、难度/CEFR 等级时，例如“2 speakers、2 questions、A1”，practiceNeed 必须完整保留这些原始约束，不能改写丢失。
- 用户在已有工作区中要求某种练习、卡片、答题、逐句听、错因分析、词汇、短语、语法、翻译、例句、发音、填空、组句、表达对比、纠错、评分标准、听力信号词、音素对照、词族、场景表达、语气转换、口语提示、写作提纲、错因模式时，intent 必须是 practice_card。
- 用户只是问听力技巧、英语语法、翻译、表达区别时，intent 必须是 chat。
- cardSpec 已退役：任何情况下都固定输出 "cardSpec": null。练习卡内容由独立的微元引擎按用户原话生成，你绝不要在本 JSON 里生成题目、选项、答案或任何卡片内容。
- intent=practice_card 时 reply 只需一句简短说明（如“好的，练习卡来了”），不要在 reply 里复述题目或答案。
- 用户问什么就只做什么；用户没有要求练习时，只在 reply 中给最多 1 个下一步建议。
- 用户明确写 no audio / without audio / 不要音频 / 无音频 时，reply 里不要写 hear/listen/play/audio/听/播放/收听。
- 如果用户说 no examples / 不要例句，reply 里只写抽象规则、用法差异或关键词，不要写 He said hello / I have finished 这类示例句。
- ListenE 当前没有跟读、复述模式；逐句听只表示点击英文原文、播放对应句子音频，不要写“跟读”“复述”。
- 回答要简洁、可执行，适合手机聊天气泡阅读。
- 解释英语时可以给 1-3 个例句。`
    },
    ...selectRecentMessagesWithinBudget(recentMessages),
    {
      role: "user",
      content: attachments.length
        ? (
            multimodal
              ? buildUserContentWithAttachments(message || "请理解这些附件。", attachments)
              : buildPlainUserContentWithAttachments(message || "请理解这些附件。", attachments)
          )
        : message
    }
  ];
  const agentChatOptions = {
    model: multimodal ? settings.mimoMultimodalModel : settings.mimoTextModel,
    temperature: 0.35,
    maxTokens: outputFileRequested ? 3200 : (multimodal ? 2600 : agentChatPrimaryMaxTokens(message, 2200)),
    json: true
  };
  // 流式：调用方给了 onReplyDelta 时走 stream，把 JSON envelope 里 "reply" 字段的增量实时回调
  //（客户端打字机上屏）；流式任一失败回退阻塞调用，最终解析产物两条路径完全一致。
  let rawResponse;
  if (typeof onReplyDelta === "function") {
    const replyExtractor = createReplyFieldStreamExtractor(onReplyDelta);
    try {
      rawResponse = await callMimoChatStream(agentChatMessages, agentChatOptions, (delta) => replyExtractor.push(delta));
    } catch (_) {
      rawResponse = await callMimoChatRaw(agentChatMessages, agentChatOptions);
    }
  } else {
    rawResponse = await callMimoChatRaw(agentChatMessages, agentChatOptions);
  }
  const rawContent = rawResponse.choices?.[0]?.message?.content;
  let raw;
  try {
    raw = extractJsonFromContent(rawContent);
  } catch (error) {
    try {
      raw = await repairAgentChatJsonResponse({
        rawContent,
        message,
        error: error?.message || String(error),
        workspaceTitle,
        workspaceNeed
      });
    } catch (repairError) {
      raw = fallbackAgentChatResponseFromParseFailure({
        message,
        error: `${error?.message || error}; ${repairError?.message || repairError}`
      });
    }
  }

  const intent = ["chat", "new_listening_practice", "practice_card"].includes(String(raw?.intent || "").trim())
    ? String(raw.intent).trim()
    : "chat";
  const systemPracticeEvent = workspaceTitle && /素材已生成|AI分析已完成/.test(message);
  const paraphrasePracticeRequest = isFuzzyParaphrasePracticeRequest(message);
  const needsCoreObjectClarification = Boolean(fuzzyClarificationReply(message, currentRecordSummary)) &&
    !paraphrasePracticeRequest &&
    !AGENT_ERROR_HUNT_INTENT_PATTERN.test(message);
  const methodAdviceOnlyRequest = isAgentMethodAdviceOnlyRequest(message) && !paraphrasePracticeRequest;
  const currentMaterialQuestionCardRequest = Boolean(currentRecordSummary?.scriptPreview || currentRecordSummary?.script) &&
    isCurrentMaterialQuestionCardRequest(message);
  const forcedPlainTranscriptCardIntent = Boolean(currentRecordSummary?.scriptPreview || currentRecordSummary?.script) &&
    isPlainTranscriptAgentCardRequest(message);
  const forcedNewListeningPracticeIntent = !outputFileRequested &&
    !needsCoreObjectClarification &&
    !methodAdviceOnlyRequest &&
    (isExplicitNewListeningPracticeRequest(message) || isExplicitListeningDialogueMaterialRequest(message));
  const forcedPracticeCardIntent = !outputFileRequested && !needsCoreObjectClarification && !methodAdviceOnlyRequest && (
    isFuzzyWritingPracticeRequest(message) ||
    isFuzzyReadingPracticeRequest(message) ||
    isFuzzySpeakingPracticeRequest(message) ||
    currentMaterialQuestionCardRequest ||
    forcedPlainTranscriptCardIntent ||
    isFuzzyScenarioPracticeRequest(message) ||
    isFuzzyInterviewEnglishPracticeRequest(message) ||
    isFuzzyQuestionPracticeRequest(message) ||
    isFuzzyStudyComponentPracticeRequest(message) ||
    isDialogueQuestionSetRequest(message) ||
    isWordBankClozePracticeCardRequest(message) ||
    isExplicitQuestionSetOnlyCardRequest(message) ||
    isExplicitSingleMeaningChoicePayload(message) ||
    isFuzzyEthicsPracticeRequest(message) ||
    isFuzzyInnovativeModePracticeRequest(message) ||
    isFuzzyParaphrasePracticeRequest(message) ||
    AGENT_ERROR_HUNT_INTENT_PATTERN.test(message) ||
    (intent === "chat" && (systemPracticeEvent || isExplicitAgentPracticeCardRequest(message)))
  );
  const unsupportedLocalSpeechRequest = isUnsupportedLocalSpeechPracticeRequest(message, currentRecordSummary, attachments);
  // 纯答疑（问区别/词义/用法/翻译某句，且无任何练习/卡片线索）应稳定保持 chat，纠正模型偶发误判为 practice_card。
  // 仅在没有任何确定性 practice/listening 触发时生效，确保“给我一张对比卡 / make a compare card”等显式卡片请求仍走 practice_card。
  const plainStudyAnswerOnlyRequest = !outputFileRequested &&
    !forcedPracticeCardIntent &&
    !forcedNewListeningPracticeIntent &&
    isPlainStudyAnswerRequest(message);
  // 用户显式拒绝出题（别出题/不要出题/只要讲解或例句）且是讲解类问题时，强制 chat 并丢弃模型误挂的卡片；
  // 该信号优先级高于 forcedPracticeCardIntent，因为它是用户的明确否定意图。
  const explicitNoCardStudyRequest = !outputFileRequested && isExplicitNoCardStudyRequest(message);
  const finalIntent = explicitNoCardStudyRequest || unsupportedLocalSpeechRequest || needsCoreObjectClarification || methodAdviceOnlyRequest || plainStudyAnswerOnlyRequest
    ? "chat"
    : (outputFileRequested ? "chat" : (forcedNewListeningPracticeIntent ? "new_listening_practice" : (forcedPracticeCardIntent ? "practice_card" : intent)));
  // ②阶段3 步骤4b：43 题型 cardSpec 生成/校验/修复/质检/贴主题增强块已退役删除。
  // practice_card 一律走原生微元（由 /agent/chat 包装层 generateAgentChatReplyWithMicro 附带 microCard）。
  let cardSpec = null;

  const cleanReply = stripAgentReplyDecorativeEmoji(
    String(raw?.reply || "").trim() || "可以，继续把你的英语问题发给我。"
  );
  const outputFiles = normalizeAgentOutputFiles(
    raw?.outputFiles || raw?.files,
    message,
    raw?.reply,
    recentMessages,
    currentRecordSummary,
    raw?.cardSpec || raw?.card
  );
  // 单轮「出题即导出」增强：用户一句话要求“出题 + 导出文件”，但既无当前题卡也无历史题目可取
  // （outputFiles 为空）时，先用剥离导出措辞后的纯出题请求递归生成题卡，再据此导出，
  // 避免直接返回“没有找到可导出的题目”。_skipQuestionExportAutogen 防止递归。
  if (
    payload._skipQuestionExportAutogen !== true &&
    outputFiles.length === 0 &&
    isFreshPracticeGenerationExportRequest(message)
  ) {
    const genMessage = deriveAgentQuestionGenerationMessage(message);
    if (genMessage && genMessage !== message) {
      // ②阶段3 步骤4b：单轮「出题即导出」一律从原生微元卡序列化导出，不再依赖 cardSpec（已退役）。
      {
        try {
          const { generateMicroCardReply } = require("./microCardGenerate");
          const microRes = await generateMicroCardReply({ message: genMessage });
          const microCard = microRes && microRes.card;
          if (microCard && Array.isArray(microCard.nodes) && microCard.nodes.length) {
            const serializedBlock = serializeMicroCardToExportText(microCard);
            if (serializedBlock) {
              const exportFormat = inferAgentOutputFormat(message);
              const autoExportFiles = normalizeAgentOutputFiles(
                [],
                `导出当前练习卡为${exportFormat}`,
                "已根据你的要求生成练习。",
                [{ role: "assistant", content: serializedBlock }],
                currentRecordSummary,
                null
              );
              if (autoExportFiles.length > 0) {
                return {
                  reply: "已根据你的要求生成练习并导出文件。",
                  intent: "practice_card",
                  practiceNeed: "",
                  cardSpec: null,
                  microCard,
                  outputFiles: autoExportFiles
                };
              }
            }
          }
        } catch (_) {
          // 微元导出失败：跳过自动导出（cardSpec 兜底路径已退役删除）。
        }
      }
    }
  }
  return {
    reply: sanitizeAgentMissingQuestionExportReply(
      sanitizeAgentReplyForCetWordBankCloze(
        sanitizeAgentCardReplyForSpec(
          unsupportedLocalSpeechRequest ? unsupportedLocalSpeechReply(message) : sanitizeAgentChatReplyForFuzzyFallback(cleanReply, message, cardSpec),
          cardSpec,
          message
        ),
        message,
        cardSpec
      ),
      message,
      outputFiles
    ),
    intent: finalIntent,
    practiceNeed: normalizeAgentPracticeNeedForListening({
      message,
      practiceNeed: raw?.practiceNeed,
      intent: finalIntent
    }),
    cardSpec,
    outputFiles
  };
};

function isUnsupportedLocalSpeechPracticeRequest() {
  // 已退役：speak_score 微元提供 App 内录音 + AI 发音/流利度评分（assessSpeaking），
  // 跟读/朗读/发音评分类请求现可生成「跟读评分卡」，不再当作「本地不支持」强制走 chat。
  return false;
}

function unsupportedLocalSpeechReply(message = "") {
  const text = String(message || "").toLowerCase();
  if (/score|评分|打分|纠音|纠正|纠错/.test(text)) {
    return "现在不能在没有你的朗读音频时给发音或朗读打分。你可以发送音频，或改成只要发音提示卡。";
  }
  return "当前没有可用于跟读的真实音频素材。可以先生成或发送一段听力素材，再用逐句点播做细听练习。";
}

function normalizeAgentPracticeNeedForListening({ message = "", practiceNeed = "", intent = "chat" } = {}) {
  if (intent !== "new_listening_practice") return "";
  const base = sanitizeAgentCardText(practiceNeed || message).slice(0, 700);
  const requested = resolveListeningGenerationConstraints({ scene: message }, "dialogue");
  const retained = resolveListeningGenerationConstraints({ scene: base }, "dialogue");
  const additions = [];
  if (requested.difficulty && requested.difficulty !== retained.difficulty) {
    additions.push(`难度：${requested.difficulty}`);
  }
  if (requested.speakerCount && requested.speakerCount !== retained.speakerCount) {
    additions.push(`对话人数：${requested.speakerCount}`);
  }
  if (requested.questionCount && requested.questionCount !== retained.questionCount) {
    additions.push(`题目数量：${requested.questionCount}`);
  }
  return [base, ...additions].filter(Boolean).join("；").slice(0, 800);
}

function fallbackAgentChatResponseFromParseFailure({ message = "", error = "" } = {}) {
  const wantsCard = isLikelyAgentPracticeCardRequest(message);
  return {
    reply: wantsCard
      ? "已生成练习卡。"
      : fuzzyClarificationReply(message) || "刚才没有拿到完整回答，请再发一次你的问题。",
    intent: wantsCard ? "practice_card" : "chat",
    practiceNeed: sanitizeAgentCardText(message).slice(0, 240),
    // cardSpec 已退役：解析失败也不再造旧卡兜底；practice_card 意图由包装层附带微元卡。
    cardSpec: null,
    outputFiles: []
  };
}

function isLikelyAgentPracticeCardRequest(message = "") {
  const text = String(message || "");
  return /card|practice|练习|卡片|答题|做题|逐句|一句一句|复听|原文|错因|翻译|译文|例句|发音|填空|完形|组句|连词成句|排序|排顺序|对比|辨析|纠错|改错|评分|音素|场景|口语|写作|提纲|错因|cloze|pronunciation|scenario|speaking|writing|outline|rubric|correction|mistake|sentence\s*builder|word\s*order|ordering|sequencing|translation|compare/i.test(text) ||
    AGENT_VOCABULARY_INTENT_PATTERN.test(text) ||
    AGENT_PHRASE_INTENT_PATTERN.test(text) ||
    AGENT_LISTENING_CUE_INTENT_PATTERN.test(text) ||
    AGENT_MINIMAL_PAIR_INTENT_PATTERN.test(text) ||
    AGENT_WORD_FAMILY_INTENT_PATTERN.test(text) ||
    AGENT_REGISTER_INTENT_PATTERN.test(text) ||
    AGENT_GRAMMAR_INTENT_PATTERN.test(text) ||
    AGENT_TRANSLATION_INTENT_PATTERN.test(text) ||
    AGENT_COMPARE_INTENT_PATTERN.test(text) ||
    AGENT_PRONUNCIATION_INTENT_PATTERN.test(text) ||
    AGENT_CORRECTION_INTENT_PATTERN.test(text);
}

function sanitizeAgentChatReplyForFuzzyFallback(reply = "", message = "", cardSpec = null) {
  const clean = stripForbiddenAgentFallbackText(reply);
  if (cardSpec) {
    if (!clean || isGenericAgentChatReply(clean) || isGenericAgentReadyReply(clean)) {
      return agentCardReadyReply(cardSpec.components || [], message);
    }
    return clean;
  }
  const clarification = fuzzyClarificationReply(message);
  if (clarification && isVagueEnglishPracticeClarificationRequest(message)) return clarification;
  if (clarification && (!clean || isGenericAgentChatReply(clean) || clean === "好的。")) return clarification;
  const methodAdvice = agentMethodAdviceReply(message);
  if (methodAdvice && (!clean || isGenericAgentChatReply(clean) || isGenericAgentReadyReply(clean) || clean === "好的。")) return methodAdvice;
  return clean;
}

function stripForbiddenAgentFallbackText(value = "") {
  return String(value || "")
    .split(/(?<=[。.!?！？])\s*/)
    .map((part) => part.trim())
    .filter(Boolean)
    .filter((part) => !AGENT_CARD_FORBIDDEN_FALLBACK_TEXT_PATTERN.test(part))
    .join(" ")
    .replace(AGENT_CARD_FORBIDDEN_FALLBACK_TEXT_PATTERN, "")
    .replace(/\s+/g, " ")
    .trim();
}

function fuzzyClarificationReply(message = "", currentRecordSummary = null) {
  const text = String(message || "").toLowerCase();
  if (/这个句子|这句|这句话|那个句子|\bsentence\b/.test(text) && !/[A-Za-z]{3,}\s+[A-Za-z]{2,}/.test(message)) {
    return "把那句英文发给我，我可以帮你拆句子、翻译并指出最该练的语法点。";
  }
  if (/这个词|这词|那个词|那个单词/.test(text) && !/[A-Za-z]{2,}/.test(message)) {
    return "把那个英文单词发给我，我可以讲用法、常见搭配，并按你的水平做一张练习卡。";
  }
  if (/长难句|句子结构/.test(text) && !/[A-Za-z]{3,}\s+[A-Za-z]{2,}/.test(message)) {
    return "把那句长难句贴给我，我会先拆主干，再说明从句和修饰成分。";
  }
  if (/这个怎么选|这题怎么选|怎么选|选哪个|哪个选项|答案选啥|答案是什么|帮我看看这题/.test(text) && !/[A-D][).、]\s*\S/i.test(message)) {
    return "把题干和选项一起发给我，我会先判断正确选项，再解释为什么不选其它项。";
  }
  if (/错题|错因|错哪|为啥错|为什么错|错题分析|错题怎么看/.test(text) &&
    !/卡片|卡\b|card/.test(text) &&
    !(currentRecordSummary && Array.isArray(currentRecordSummary.wrongInsights) && currentRecordSummary.wrongInsights.length > 0) &&
    !/[A-D][).、]\s*\S/i.test(message)) {
    return "把错题、你的选择和正确答案发给我，我可以帮你做错因分析并整理成复盘卡。";
  }
  if (/明天.*考试|考试.*救|救我|救命/.test(text) &&
    !/四级|六级|高考|考研|雅思|托福|cet|ielts|toefl|语法|完形|阅读|听力|作文|写作|单词|词汇|短语|翻译|介词|冠词|时态|发音|辨音/.test(text)) {
    return "先告诉我考试类型和最弱题型，比如四级完形、阅读、作文或听力，我再给你安排一张能马上练的卡。";
  }
  if (/提分|score|improve/.test(text) && !hasSpecificAgentPracticeCueForImproveRequest(text)) {
    return "想提分的话，先告诉我考试类型和最弱题型，比如四级完形、阅读、作文或听力，我再给你生成对应练习。";
  }
  if (isVagueEnglishPracticeClarificationRequest(message)) {
    return "\u5148\u544a\u8bc9\u6211\u9898\u578b\u548c\u8584\u5f31\u70b9\uff0c\u6bd4\u5982\u56db\u7ea7\u9605\u8bfb\u3001\u4f5c\u6587\u3001\u542c\u529b\u3001\u5355\u8bcd\u6216\u8bcd\u6c47\uff1b\u5982\u679c\u4e0d\u786e\u5b9a\uff0c\u6211\u53ef\u4ee5\u5148\u6309\u201c\u9605\u8bfb/\u542c\u529b/\u8bcd\u6c47/\u5199\u4f5c\u201d\u5404\u51fa\u4e00\u5c0f\u7ec4\u3002";
  }
  return "";
}

function isVagueEnglishPracticeClarificationRequest(message = "") {
  const text = String(message || "").toLowerCase();
  if (!text.trim()) return false;
  if (AGENT_ETHICS_INTENT_PATTERN.test(message) || AGENT_DEBATE_INTENT_PATTERN.test(message) ||
    AGENT_ERROR_HUNT_INTENT_PATTERN.test(message) || AGENT_STORYTELLING_INTENT_PATTERN.test(message)) return false;
  if (/(?:\u600e\u4e48|\u5982\u4f55|\u65b9\u6cd5|\u6280\u5de7|how\s+to|strategy|approach)/i.test(text)) return false;
  if (/(?:\u56db\u7ea7|\u516d\u7ea7|\u9ad8\u8003|\u8003\u7814|\u96c5\u601d|\u6258\u798f|cet\s*[46]?|ielts|toefl|\u5b8c\u5f62|\u9009\u8bcd|\u586b\u7a7a|\u9605\u8bfb|\u542c\u529b|\u4f5c\u6587|\u5199\u4f5c|\u5355\u8bcd|\u8bcd\u6c47|\u8bcd\u4e49|\u77ed\u8bed|\u642d\u914d|\u8bed\u6cd5|\u7ffb\u8bd1|\u53e3\u8bed|\u53d1\u97f3|\u8fa8\u97f3|\u60c5\u666f|\u573a\u666f|grammar|cloze|reading|listening|writing|essay|vocab|vocabulary|translation|speaking|phrase|collocation|pronunciation|scenario|meaning\s+choice)/i.test(text)) return false;
  const vagueEnglish = /(?:\u6211)?\s*(?:\u60f3|\u8981|\u9700\u8981|\u6253\u7b97)?.{0,6}(?:\u7ec3|\u7ec3\u4e60|\u5b66|practice|learn|study).{0,6}(?:\u82f1\u8bed|\benglish\b)|(?:\u82f1\u8bed|\benglish\b).{0,8}(?:\u7ec3|\u7ec3\u4e60|practice|drill)/i.test(text);
  const vagueQuestion = /(?:\u60f3|\u8981|\u6765|\u7ed9\u6211|\u5e2e\u6211|\u505a|\u5237|\u51fa).{0,10}(?:\u70b9|\u4e9b|\u51e0\u9053|\u51e0\u4e2a)?(?:\u9898|\u9898\u76ee|\u505a\u9898|\u5237\u9898)|(?:questions?|quiz|practice).{0,20}(?:anything|something|not sure|don't know|do not know)/i.test(text);
  const uncertain = /\u4e0d\u77e5\u9053|\u4e0d\u6e05\u695a|\u4e0d\u786e\u5b9a|\u6ca1\u60f3\u597d|\u968f\u4fbf|\u90fd\u884c|\u505a\u4ec0\u4e48|\u7ec3\u4ec0\u4e48|\u54ea\u4e2a|not\s+sure|don't\s+know|do\s+not\s+know|anything|something/i.test(text);
  return vagueEnglish || (vagueQuestion && uncertain);
}

function hasSpecificAgentPracticeCueForImproveRequest(text = "") {
  const value = String(text || "").toLowerCase();
  return /练|练习|题|题目|做题|出题|刷题|卡片|practice|drill|quiz|questions?|card|语法|完形|填空|阅读|听力|作文|写作|单词|词汇|短语|翻译|介词|冠词|时态|发音|辨音|口语|grammar|cloze|reading|listening|writing|essay|vocab|vocabulary|translation|speaking|preposition|article|tense/.test(value);
}

function isAgentMethodAdviceOnlyRequest(message = "") {
  const text = String(message || "").toLowerCase();
  if (!text.trim()) return false;
  const asksHow = /怎么(?:做|写|选|练|提高|提分)|如何(?:做|写|选|练|提高|提分)|讲(?:一下|下)?(?:方法|思路|技巧)|方法|技巧|how\s+(?:to|do|should)|what(?:'s| is)?\s+the\s+(?:method|strategy)|strategy|approach/.test(text);
  if (!asksHow) return false;
  const deniesCardOrExercise = /不要.{0,12}(?:出题|题目|练习|卡片|生成卡|出卡)|别.{0,12}(?:出题|题目|练习|卡片|生成卡|出卡)|先(?:讲|说|解释)|只(?:讲|说|解释)|no\s+(?:quiz|questions?|practice|card)|without\s+(?:quiz|questions?|practice|card)/i.test(text);
  const explicitGenerate = /(?:给我|来|出|生成|做)\s*[0-9一二两三四五六七八九十几]*\s*(?:道|个|套)?\s*(?:题|练习|卡片)|create|generate|make\s+(?:a\s+)?(?:quiz|practice|card)/i.test(text);
  return deniesCardOrExercise || !explicitGenerate;
}

function agentMethodAdviceReply(message = "") {
  const text = String(message || "").toLowerCase();
  if (!isAgentMethodAdviceOnlyRequest(message)) return "";
  if (/选词填空|十五选十|15\s*选\s*10|word\s*bank|cloze|完形/.test(text)) {
    return "选词填空先按词性分组，再看空格前后的搭配和句子成分；最后用上下文逻辑排除意思不通的选项。";
  }
  if (/主旨|main\s+idea|gist|central/.test(text)) {
    return "主旨题先看首尾句和转折句，再归纳全文共同主题；选项如果只说某个细节、过度绝对或偏离全文，就先排除。";
  }
  if (/阅读|reading|定位|推断|infer|inference|detail/.test(text)) {
    return "阅读题先圈题干关键词，再回原文找同义替换；细节题核对原句，推断题只做有依据的一步推理。";
  }
  if (/作文|写作|提纲|essay|writing|outline/.test(text)) {
    return "作文提纲可以按三步写：先明确观点或任务，再列 2-3 个支撑点，最后准备一句总结或行动请求。";
  }
  return "可以先明确题型和薄弱点，再按“定位要求 -> 找关键词 -> 核对规则或上下文 -> 复查干扰项”的顺序处理。";
}

function isExplicitNewListeningPracticeRequest(message = "") {
  const text = String(message || "").toLowerCase();
  if (!text.trim()) return false;
  if (/导出|保存|下载|分享|发送|发给|export|send|save|download|share/i.test(text)) return false;
  if (isAgentMethodAdviceOnlyRequest(message)) return false;
  if (/听力.{0,12}(?:听不懂|听不明白|转折|因果|信号|老漏|听不出|听不出来)|(?:转折|因果).{0,8}(?:听不出|听不出来|老漏)/i.test(text)) return false;
  if (isListeningMaterialNegated(text)) return false;
  const explicitListening = /listening\s+(?:practice|training|material|audio|exercise)|(?:generate|create|make|build|give\s+me|more).{0,40}listening|听力(?:训练|练习|素材|音频|题|套题)|(?:更多|生成|做|给我|来|出|练习|练|想练|想要练|要练|想做).{0,24}(?:听力|音频|素材)|(?:听力|音频|素材).{0,12}(?:练习|训练|更多)|四级听力|六级听力|cet\s*[46]\s+listening/i.test(text);
  if (!explicitListening) return false;
  const materialCue = /material|audio|dialogue|conversation|passage|speakers?|speaker|questions?|training|practice|exercise|more|素材|音频|对话|短文|文章|说话人|人对话|题|套|生成|来|给我|出|做|练习|训练|更多/i.test(text);
  return materialCue;
}

function isExplicitListeningDialogueMaterialRequest(message = "") {
  const text = String(message || "").toLowerCase();
  if (!text.trim()) return false;
  if (/导出|保存|下载|分享|发送|发给|export|send|save|download|share/i.test(text)) return false;
  if (isAgentMethodAdviceOnlyRequest(message) || isListeningMaterialNegated(text)) return false;
  const dialogueCue = /dialogue|conversation|role.?play|对话|三人|两人|两个人|三个人|人.{0,4}(?:场景|对话)|job\s+interview|interview|餐厅点餐|酒店入住|机场值机|面试/.test(text);
  const generationCue = /生成|出|做|来|给我|create|generate|make|build/.test(text);
  const materialCue = /听力|音频|录音|原文|transcript|script|audio|questions?|题目|题|选择题|multiple\s*choice|mcq|场景|scene|scenario/.test(text);
  return dialogueCue && generationCue && materialCue;
}

function isFuzzyInterviewEnglishPracticeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  return /面试英语|面试表达|interview\s+english|interview\s+(?:phrases?|expressions?)/i.test(text) &&
    /背|记|练|练习|想|给我|来|整理|生成|出|some|点/i.test(text);
}

function isFuzzyQuestionPracticeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (/导出|保存|下载|分享|发送|发给|export|send|save|download|share/i.test(text)) return false;
  if (/这个怎么选|这题怎么选|怎么选|选哪个|哪个选项|答案选啥|答案是什么/.test(text) && !/[A-D][).、]\s*\S/i.test(text)) return false;
  if (isExplicitSingleMeaningChoicePayload(text)) return false;
  return /考我|随便考|出题|帮我练一下|练一下|(?:帮我|给我|来|搞|整|弄).{0,8}(?:点|些|几道|几个)?题|刷题|给点题|来[一二两俩三四五六七八九十0-9几]*道|做[一二两俩三四五六七八九十0-9几]*道|几道题|来个能做的题|基础题|同义替换|synonym|单词选择|词汇选择|词义选择|含义选择|意思选择|(?:这个|这|那个|那)?(?:单词|词汇|生词).{0,12}(?:记不住|背不住|总忘).{0,12}(?:考|练)|(?:单词|词汇|生词).{0,8}(?:意思|含义|释义|词义).{0,8}(?:选择|考|练)|(?:意思|含义|释义|词义).{0,8}(?:选择|考|练)|选择题|(?:四级|六级|雅思|托福|cet\s*[46]|ielts|toefl).{0,20}(?:救|考|题|练|练习|快考|考试)|(?:明天|后天|一周|快).{0,12}(?:考试|考).{0,12}(?:救|练)|来点(?:四级|六级|雅思|托福)|cet\s*[46].{0,12}(?:help|exam)|meaning\s+(?:choice|quiz|questions?)/i.test(text);
}

function isCurrentMaterialQuestionCardRequest(message = "") {
  const text = String(message || "").toLowerCase();
  if (/别.{0,4}出题|不要.{0,8}(?:出题|题目|题|quiz|questions?)|不需要.{0,8}(?:出题|题目|题|quiz|questions?)|无(?:题目|题|quiz|questions?)/i.test(text)) return false;
  return /(?:这段|这篇|这份|这个|当前|刚才|上面|previous|current|this|that).{0,12}(?:变成|改成|转成|出|生成|make|turn|convert).{0,12}(?:题|题目|练习|quiz|questions?)|(?:帮我|给我).{0,8}(?:把)?(?:这段|这篇|这份|当前|刚才|上面).{0,12}(?:变成|改成|转成).{0,12}(?:题|题目|练习|quiz|questions?)/i.test(text);
}

function isFuzzyStudyComponentPracticeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (AGENT_UNSPECIFIED_WORD_OR_SENTENCE_PATTERN.test(text) && !/句子不会写|不会写句子|造句不会/.test(text) && !/[A-Za-z]{2,}/.test(text)) return false;
  if (/导出|保存|下载|分享|发送|发给|export|send|save|download|share/i.test(text)) return false;
  if (isFuzzyExamRescueQuestionRequest(text)) return true;
  const hasComponentIntent =
    AGENT_VOCABULARY_INTENT_PATTERN.test(text) ||
    AGENT_PHRASE_INTENT_PATTERN.test(text) ||
    AGENT_LISTENING_CUE_INTENT_PATTERN.test(text) ||
    AGENT_MINIMAL_PAIR_INTENT_PATTERN.test(text) ||
    AGENT_WORD_FAMILY_INTENT_PATTERN.test(text) ||
    AGENT_REGISTER_INTENT_PATTERN.test(text) ||
    AGENT_GRAMMAR_INTENT_PATTERN.test(text) ||
    AGENT_TRANSLATION_INTENT_PATTERN.test(text) ||
    AGENT_COMPARE_INTENT_PATTERN.test(text) ||
    AGENT_PRONUNCIATION_INTENT_PATTERN.test(text) ||
    AGENT_CORRECTION_INTENT_PATTERN.test(text) ||
    isOrderingCardRequest(text) ||
    /完形|word\s*order|word\s+order|单词顺序|连词成句|句子不会写|不会写句子|造句不会|听写|默写|输入答案|短答|主旨|推断|定位|阅读.{0,8}(?:看不明白|看不懂)|四级.{0,12}(?:救|考|题)|老听不懂|总听不懂|听不懂|听不明白|听辨|辨音|听音辨词|听力.{0,10}(?:听不懂|听不明白|转折|因果|信号|老漏|听不出)|(?:转折|因果).{0,8}(?:听不出|听不出来|老漏)/i.test(text);
  if (!hasComponentIntent) return false;
  if (isPlainStudyAnswerRequest(text)) return false;
  return AGENT_WEAKNESS_PRACTICE_CUE_PATTERN.test(text) ||
    hasExplicitStudyComponentPracticeCue(text);
}

function hasExplicitStudyComponentPracticeCue(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (Object.keys(inferRequestedAgentCardItemCounts(text)).length > 0) return true;
  if (/(?:\u7ec3\u7ec3|\u7ec3\u4e00\u7ec3|\u7ec3\u4e00\u4e0b|\u5e2e\u6211\u7ec3|\u7ed9\u6211\u7ec3|\u62ff\u6765\u7ec3|\u7528\u6765\u7ec3|practice\s*(?:this|these|it|them)?|drill\s*(?:this|these|it|them)?)/i.test(text)) return true;
  if (/(?:\u7ea0\u6b63|\u7ea0\u9519|\u6539\u9519|\u6539\u6b63|\u4fee\u6b63).{0,12}(?:\u51e0(?:\u4e2a|\u6761|\u53e5)?|\u4e00\u4e9b|[0-9]+|\u4e24(?:\u4e2a|\u6761|\u53e5)?).{0,8}(?:\u53e5|\u53e5\u5b50|sentences?|items?)?/i.test(text)) return true;
  return /card|cards|卡片|practice|exercise|quiz|question\s*set|questions?|multiple\s*choice|mcq|drill|训练|练习|出题|题组|题目|选择题|习题|考我|刷题|填空题|cloze|sentence\s*builder|ordering|sequencing|short\s*answer|dictation|minimal\s*pair|only\s+(?:vocab|vocabulary|phrases?|translation|grammar|compare|correction|rubric|outline|scenario|register|speaking|pronunciation|minimal\s*pairs?|word\s*family)|只要.{0,12}(?:卡片|练习|题|题组|填空|选择|词汇|短语|翻译|语法|改错|提纲|评分|场景|口语|发音|排序)/i.test(text);
}

function isPlainStudyAnswerRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (hasExplicitStudyComponentPracticeCue(text)) return false;
  return /(?:帮我|给我|请|麻烦)?\s*(?:翻译|translate|翻成|译成|英译中|中译英).{0,80}(?:这句|这句话|句子|sentence|[:：])|(?:帮我|给我|请|麻烦)?\s*(?:讲一下|解释一下|解释|说明|说说|tell\s+me|explain).{0,80}(?:grammar|tense|article|preposition|present\s+perfect|past\s+tense|语法|时态|冠词|介词|用法)|(?:区别是什么|有什么区别|怎么用|如何使用|what(?:'s| is)?\s+the\s+difference|how\s+to\s+use|meaning\s+of|是什么意思)/i.test(text);
}

function isExplicitNoCardStudyRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  const noPracticeMarker = /别出题|别再出题|不要出题|不出题|不用出题|无需出题|别考我|不要考我|别(?:给我)?(?:出|加)题|no\s+(?:quiz|test|exercise|card|practice)|don'?t\s+(?:make|create|give|need)[^.;!?。！？]{0,20}(?:quiz|questions?|cards?|exercises?|practice)|just\s+explain|just\s+(?:give\s+me\s+)?(?:an?\s+|some\s+|two\s+|a\s+few\s+)?examples?/i.test(text);
  if (!noPracticeMarker) return false;
  const studyQuestion = /怎么用|怎么使用|如何使用|如何用|怎么区分|区别|差别|什么意思|啥意思|意思是|是什么|为什么|讲讲|讲一下|讲解|解释|说明一下|how\s+to\s+use|how\s+do\s+(?:i|you)\s+use|what(?:'s| is)\b|difference\s+between|meaning\s+of|when\s+to\s+use/i.test(text);
  return studyQuestion;
}

function isFuzzyExamRescueQuestionRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (/完形|填空|cloze|blank|fill/i.test(text) && !isCetWordBankClozeRequest(text)) return false;
  if (isListeningMaterialNegated(text) || !hasAffirmativeListeningMaterialCue(text)) {
    return /(?:四级|六级|雅思|托福|cet\s*[46]|ielts|toefl).{0,28}(?:救|救命|快考|考试|还有|一周|练|练习|题)|(?:明天|后天|一周|快).{0,14}(?:考试|考).{0,14}(?:救|练|题)|(?:先|来|给我|帮我).{0,12}(?:练|做|来).{0,12}(?:最容易掉分|薄弱|弱项)/i.test(text);
  }
  return false;
}

function isExplicitAgentPracticeCardRequest(message = "") {
  const text = String(message || "").toLowerCase();
  if (!text.trim()) return false;
  if (isPlainStudyAnswerRequest(text)) return false;
  return /card|cards|卡片|练习|practice|exercise|quiz|question\s*set|答题|做题|题目|习题|考我|出题|道题|别出题|逐句|一句一句|复听|each\s+sentence|replay\s+each\s+sentence|点播|原文卡|查看原文|展示.*原文|错因|错题|填空|选词|十五选十|完形|组句|排序|排顺序|连词成句|默写|听写|听辨|辨音|听音辨词|输入答案|短答|词义选择|含义选择|意思选择|cloze|dictation|short\s*answer|sentence\s*builder|word\s*order|ordering|sequencing|reorder/i.test(text) ||
    /(?:make|create|generate|build|give\s+me|show|open|display|整理|生成|给我|做一个|展示|打开).{0,80}(?:compare|translation|grammar|vocab|vocabulary|glossary|terms?|phrase|chunks?|idioms?|pronunciation|minimal\s*pair|word\s*family|scenario|register|speaking|writing|outline|rubric|correction|mistake|listening\s*cue|对比|辨析|翻译|译文|语法|词汇|单词|术语|短语|语块|习语|发音|音素|词族|场景|语气|口语|写作|提纲|评分|纠错|改错|错因|听力信号)/i.test(text);
}

// 道德/伦理练习卡请求：带 ethics/moral/道德 主题 + 出卡/练习类动词时，强制走 practice_card，
// 避免被“讨论/谈谈”这类措辞误判为纯讲解 chat（道德概念问答仍保持 chat）。
function isFuzzyEthicsPracticeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (/导出|保存|下载|分享|发送|发给|export|send|save|download|share/i.test(text)) return false;
  if (!AGENT_ETHICS_INTENT_PATTERN.test(text)) return false;
  return /card|cards|practice|exercise|drill|reflection|卡片|练习|训练|思辨练习|做\s*(?:一)?\s*(?:张|个|道)|出\s*(?:一)?\s*(?:张|个|道)|给我|来\s*(?:一)?\s*(?:张|个)|generate|make|create|build|give\s+me/i.test(text);
}

// 创新训练模式（辩论/校对找错/故事创作）练习卡请求：主题词 + 出卡/练习类动词时强制走 practice_card，
// 避免被“讨论/讲讲”等措辞或“practice in english”模糊判定误导为 chat。
function isFuzzyInnovativeModePracticeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (/导出|保存|下载|分享|发送|发给|export|send|save|download|share/i.test(text)) return false;
  if (!(AGENT_DEBATE_INTENT_PATTERN.test(text) || AGENT_ERROR_HUNT_INTENT_PATTERN.test(text) || AGENT_STORYTELLING_INTENT_PATTERN.test(text))) return false;
  return /card|cards|practice|exercise|drill|卡片|练习|训练|做\s*(?:一)?\s*(?:张|个|道|段)|出\s*(?:一)?\s*(?:张|个|道|段)|给我|来\s*(?:一)?\s*(?:张|个|段)|generate|make|create|build|give\s+me|写|编|讲/i.test(text);
}

// 同义改写练习卡请求：带 paraphrase/同义改写/换种说法 主题 + 出卡/练习/改写类动词时强制走 practice_card，
// 避免被“什么是同义改写”以外的祈使请求误判为纯讲解 chat（概念问答仍保持 chat）。
function isFuzzyParaphrasePracticeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (/导出|保存|下载|分享|发送|发给|export|send|save|download|share/i.test(text)) return false;
  if (!AGENT_PARAPHRASE_INTENT_PATTERN.test(text)) return false;
  if (/^\s*(?:what\s+is|what's|what\s+are|how\s+(?:do|to|can)|什么是|如何|怎么|怎样|为什么)/i.test(text) &&
    !/card|cards|practice|exercise|练习|卡片|给我|make|create|give\s+me/i.test(text)) return false;
  return /card|cards|practice|exercise|drill|卡片|练习|训练|做\s*(?:一)?\s*(?:张|个|道|句)|出\s*(?:一)?\s*(?:张|个|道|句)|给我|来\s*(?:一)?\s*(?:张|个|句)|generate|make|create|build|give\s+me|paraphrase|rephrase|reword|换种说法|换个说法|改写|versions?|版本|sentences?|句子/i.test(text);
}

function isExplicitQuestionSetOnlyCardRequest(message = "") {
  const text = String(message || "").toLowerCase();
  if (!text.trim()) return false;
  const wantsQuestions = /question\s*set|multiple\s*choice|mcq|quiz|questions?|题组|选择题|题目|问题/.test(text);
  if (!wantsQuestions) return false;
  const restrictsToCard = /only\s+(?:question\s*set|quiz|questions?)|only\s+.*(?:no\s+audio|without\s+audio|no\s+transcript|without\s+transcript)|no\s+audio|without\s+audio|no\s+transcript|without\s+transcript|只要.{0,8}(?:题组|题目|问题)|不要.{0,8}(?:音频|原文)|不需要.{0,8}(?:音频|原文)/.test(text);
  const wantsListeningMaterial = /listening\s+(?:material|practice|audio|training)|听力(?:素材|训练|练习)|生成.{0,12}(?:音频|素材)/.test(text);
  return restrictsToCard && !wantsListeningMaterial;
}

async function repairAgentChatJsonResponse({ rawContent = "", message = "", error = "", workspaceTitle = "", workspaceNeed = "" } = {}) {
  const repairPrompt = [
    "请修复 ListenE Agent JSON 响应，返回严格 JSON，不要解释。",
    `用户本轮消息：${String(message || "").slice(0, 300)}`,
    workspaceTitle ? `工作区：${workspaceTitle}` : "",
    workspaceNeed ? `工作区需求：${workspaceNeed}` : "",
    error ? `解析错误：${String(error).slice(0, 240)}` : "",
    "必须返回字段：reply, intent, practiceNeed, cardSpec, outputFiles。",
    "intent 只能是 chat、new_listening_practice、practice_card。",
    "cardSpec 已退役：任何情况下都固定输出 null（练习卡由独立微元引擎生成）。",
    `原始坏响应：${String(rawContent || "").slice(0, 3200)}`
  ].filter(Boolean).join("\n");
  const response = await callMimoChatRaw([
    {
      role: "system",
      content: "你是 ListenE Agent JSON 修复器。你只能把坏 JSON 修复为 ListenE 支持的严格 JSON 响应。"
    },
    {
      role: "user",
      content: repairPrompt
    }
  ], {
    model: settings.mimoTextModel,
    temperature: 0.1,
    maxTokens: 900,
    json: true
  });
  return extractJsonFromContent(response.choices?.[0]?.message?.content);
}

function buildPlainUserContentWithAttachments(message, attachments = []) {
  const sections = [String(message || "")];
  attachments.forEach((attachment) => {
    sections.push([
      `附件：${attachment.name} (${attachment.mimeType})`,
      attachment.textPreview
        ? `文档内容预览：\n${attachment.textPreview}`
        : "这是一个文档附件。当前只能读取文本类文档内容；二进制文档请根据文件名、类型和用户描述处理。"
    ].join("\n"));
  });
  return sections.join("\n\n").trim();
}

// 记忆升级#2：上下文按 token 预算而非固定条数。历史近窗从最新往旧累加估计 token，
// 到预算或条数上限即停（至少留最近 1 条）——短消息能多带上下文、长消息不至于撑爆窗口。
const AGENT_HISTORY_TOKEN_BUDGET = Math.max(200, Number(process.env.AGENT_HISTORY_TOKEN_BUDGET) || 1200);
const AGENT_HISTORY_MAX_MESSAGES = Math.max(2, Number(process.env.AGENT_HISTORY_MAX_MESSAGES) || 16);

// 粗略 token 估计（无需分词器）：CJK 表意/假名按 ~2 token/字，其余按 ~1 token/4 字符；偏保守，够做预算。
function estimateTokens(text) {
  const s = String(text || "");
  if (!s) return 0;
  let cjk = 0;
  for (const ch of s) {
    if (/[\u3400-\u9FFF\uF900-\uFAFF\u3040-\u30FF\uAC00-\uD7AF]/.test(ch)) cjk += 1;
  }
  const other = s.length - cjk;
  return Math.ceil(cjk * 2 + other / 4);
}

function selectRecentMessagesWithinBudget(
  messages,
  tokenBudget = AGENT_HISTORY_TOKEN_BUDGET,
  maxMessages = AGENT_HISTORY_MAX_MESSAGES
) {
  const list = (Array.isArray(messages) ? messages : [])
    .map((item) => ({
      role: item && item.role === "user" ? "user" : "assistant",
      content: String((item && (item.content || item.text || item.message)) || "")
    }))
    .filter((item) => item.content.trim());
  if (list.length === 0) return [];
  const budget = Math.max(0, Number(tokenBudget) || 0);
  const cap = Math.max(1, Number(maxMessages) || 1);
  const picked = [];
  let used = 0;
  for (let i = list.length - 1; i >= 0; i -= 1) {
    if (picked.length >= cap) break;
    const cost = estimateTokens(list[i].content) + 4; // 每条角色标记/分隔的固定开销
    if (picked.length > 0 && used + cost > budget) break; // 至少保留最近 1 条
    picked.push(list[i]);
    used += cost;
  }
  return picked.reverse();
}

// 记忆升级#3：轻量相关性检索（无需向量模型）。分词=拉丁词(≥2)+CJK 单字集合。
function tokenizeForMemoryRelevance(text) {
  const s = String(text || "").toLowerCase();
  const latin = s.match(/[a-z0-9]{2,}/g) || [];
  const cjk = s.match(/[\u4e00-\u9fff]/g) || [];
  return new Set([...latin, ...cjk]);
}

// 记忆条目打分：与本轮消息关键词重合*3 + importance + 类型权重(弱点>偏好>事实)。query 空 → 退回按 importance/类型。
function scoreMemoryEntryRelevance(entry, queryTokens) {
  const importance = Number(entry && entry.importance) || 0;
  const typeBoost = entry && entry.type === "weakness" ? 1.5 : entry && entry.type === "preference" ? 1 : 0;
  if (!queryTokens || queryTokens.size === 0) return importance + typeBoost;
  const entryTokens = tokenizeForMemoryRelevance(entry && entry.content);
  let overlap = 0;
  queryTokens.forEach((t) => { if (entryTokens.has(t)) overlap += 1; });
  return overlap * 3 + importance + typeBoost;
}

function buildAgentWorkspaceMemoryHint({ memorySummary = "", memory = [], query = "" } = {}) {
  const summary = sanitizeAgentCardText(memorySummary).replace(/\s+/g, " ").slice(0, 1500);
  // 只注入与本轮最相关的 top-k 记忆，而非把全部 ≤30 条一股脑塞进 prompt。
  const queryTokens = tokenizeForMemoryRelevance(query);
  const entries = Array.isArray(memory)
    ? memory
      .map((item) => ({
        type: sanitizeAgentCardText(item?.type || "fact").slice(0, 20) || "fact",
        content: sanitizeAgentCardText(item?.content || "").replace(/\s+/g, " ").slice(0, 220),
        importance: Number.isFinite(Number(item?.importance)) ? Number(item.importance) : 0
      }))
      .filter((item) => item.content)
      .map((item) => ({ ...item, score: scoreMemoryEntryRelevance(item, queryTokens) }))
      .sort((a, b) => b.score - a.score)
      .slice(0, 8)
    : [];
  if (!summary && entries.length === 0) {
    return "本工作区暂无长期记忆；不要猜测未提供的历史。";
  }
  const lines = [
    "本工作区级记忆（只适用于当前工作区，不是全局用户画像；若和本轮明确要求冲突，以本轮要求为准）："
  ];
  if (summary) lines.push(`- memorySummary: ${summary}`);
  entries.forEach((item) => {
    lines.push(`- ${item.type}: ${item.content}`);
  });
  return lines.join("\n");
}

function normalizeAgentCurrentRecordSummary(record) {
  if (!record || typeof record !== "object") return null;
  const questions = Array.isArray(record.questions)
    ? record.questions.map((item, index) => ({
      index: Number.isFinite(Number(item?.index)) ? Number(item.index) : index,
      questionText: sanitizeAgentCardText(item?.questionText || item?.question || "").slice(0, 160),
      options: normalizeAgentStringArray(item?.options, 80).slice(0, 4),
      correctAnswer: Number.isFinite(Number(item?.correctAnswer)) ? Number(item.correctAnswer) : undefined,
      explanation: sanitizeAgentCardText(item?.explanation || "").slice(0, 180)
    })).filter((item) => item.questionText).slice(0, 6)
    : [];
  const wrongInsights = Array.isArray(record.wrongInsights || record.wrongQuestionInsights)
    ? (record.wrongInsights || record.wrongQuestionInsights).map((item) => ({
      questionIndex: Number.isFinite(Number(item?.questionIndex)) ? Number(item.questionIndex) : undefined,
      mistakeType: sanitizeAgentCardText(item?.mistakeType || "").slice(0, 40),
      insight: sanitizeAgentCardText(item?.insight || "").slice(0, 180),
      focusSentence: sanitizeAgentCardText(item?.focusSentence || "").slice(0, 180)
    })).filter((item) => item.mistakeType || item.insight || item.focusSentence).slice(0, 6)
    : [];
  return {
    id: sanitizeAgentCardSource(record.id || "") || undefined,
    title: sanitizeAgentCardText(record.title || record.scene || "").slice(0, 80),
    contentType: sanitizeAgentCardText(record.contentType || "").slice(0, 20),
    audioReady: record.audioReady === true || Boolean(record.audioUrl),
    audioUrl: sanitizeAgentCardText(record.audioUrl || record.audio || "").slice(0, 500),
    questionCount: Number.isFinite(Number(record.questionCount)) ? Number(record.questionCount) : questions.length,
    answeredCount: Number.isFinite(Number(record.answeredCount)) ? Number(record.answeredCount) : 0,
    answersRevealed: record.answersRevealed === true,
    scriptPreview: sanitizeAgentCardText(record.scriptPreview || record.script || "").slice(0, 520),
    questions,
    analysisSummary: sanitizeAgentCardText(record.analysisSummary || record.summary || "").slice(0, 220),
    weakPoints: normalizeAgentStringArray(record.weakPoints, 80).slice(0, 6),
    suggestions: normalizeAgentStringArray(record.suggestions, 100).slice(0, 6),
    wrongInsights
  };
}

function normalizeAgentCardSpec(cardSpec) {
  if (!cardSpec || typeof cardSpec !== "object") return null;
  const allowedKinds = new Set(["workspace", "tool_choice", "notice", "custom"]);
  const kind = normalizeAgentCardKind(cardSpec.kind || "custom");
  if (!allowedKinds.has(kind)) return null;
  const components = Array.isArray(cardSpec.components)
    ? cardSpec.components.map(normalizeAgentCardComponent).filter(Boolean).slice(0, 16)
    : [];
  const actions = Array.isArray(cardSpec.actions)
    ? cardSpec.actions.map(normalizeAgentCardAction).filter(Boolean).slice(0, 4)
    : [];
  return {
    schemaVersion: normalizeAgentCardSchemaVersion(cardSpec),
    kind,
    title: compactAgentCardText(cardSpec.title, 40),
    subtitle: compactAgentCardText(cardSpec.subtitle, 80),
    chips: Array.isArray(cardSpec.chips)
      ? cardSpec.chips.map((item) => compactAgentCardText(item, 18)).filter(Boolean).slice(0, 4)
      : [],
    components,
    actions
  };
}

function enforceAgentCardScope(message = "", cardSpec = null) {
  if (!cardSpec || typeof cardSpec !== "object") return cardSpec;
  const scope = inferAgentCardScope(message);
  if (!scope.scoped) return cardSpec;
  return scope.materialReady
    ? enforceMaterialAgentCardScope(cardSpec, scope.allowed)
    : enforceGeneralAgentCardScope(cardSpec, scope.allowed, scope);
}

function inferAgentCardScope(message = "") {
  const text = String(message || "").toLowerCase();
  if (/素材已生成|material generated/.test(text)) {
    return {
      allowed: new Set(AGENT_CARD_MATERIAL_CORE_ORDER),
      scoped: true,
      materialReady: true
    };
  }
  if (/ai\s*分析已完成|ai分析已完成/.test(text)) {
    return {
      allowed: new Set(AGENT_CARD_ANALYSIS_CORE),
      scoped: true,
      materialReady: false
    };
  }
  const cetWordBankCloze = isCetWordBankClozeRequest(text);
  const fuzzyWriting = isFuzzyWritingPracticeRequest(message);
  const fuzzyReading = isFuzzyReadingPracticeRequest(message);
  const fuzzySpeaking = isFuzzySpeakingPracticeRequest(message);
  const fuzzyScenario = isFuzzyScenarioPracticeRequest(message);
  const currentMaterialQuestion = isCurrentMaterialQuestionCardRequest(message);
  const dialogueQuestionSet = isDialogueQuestionSetRequest(message);
  const explicitScenarioRequest = isExplicitScenarioCardRequest(message);
  const orderingRequest = isOrderingCardRequest(text);
  const shortAnswerOnly = isShortAnswerOnlyAgentCardRequest(text) || isFuzzyShortAnswerPracticeRequest(text);
  if (isPlainTranscriptAgentCardRequest(text) || (
    /transcript|原文|文本/.test(text) &&
    isSentenceTranscriptDeniedAgentCardRequest(text) &&
    /no\s+(?:audio|quiz|questions?|question\s*set)|without\s+(?:audio|quiz|questions?|question\s*set)|不要.{0,8}(?:音频|题组|题目(?!预览)|问题|测验|quiz|题(?!目?预览))|不需要.{0,8}(?:音频|题组|题目(?!预览)|问题|测验|quiz|题(?!目?预览))|无(?:音频|题组|题目(?!预览)|问题|测验|题(?!目?预览))/i.test(text)
  )) {
    const allowed = new Set(AGENT_CARD_STRUCTURAL_COMPONENTS);
    allowed.add("transcript");
    return {
      allowed,
      scoped: true,
      materialReady: false,
      strictChoice: false,
      multiQuestionChoice: false,
      singleReadingChoice: false,
      multiFillBlankQuestions: false,
      dialogueQuestionSet: false,
      cetWordBankCloze,
      fuzzyWriting,
      fuzzySpeaking,
      fuzzyScenario: false,
      excluded: inferAgentCardExcludedComponents(text),
      choiceConstraint: null,
      translationConstraint: null,
      listeningCueConstraint: null,
      clozeConstraint: null,
      scenarioConstraint: null,
      readingPassageQuiz: false,
      requestedCounts: inferRequestedAgentCardItemCounts(message),
      promptOnly: false,
      messageText: String(message || ""),
      suppressAudioCueText: true,
      suppressAnswerExplanation: false,
      suppressExtraSuggestions: true
    };
  }
  const explicitStudyComponentIntent =
    AGENT_VOCABULARY_INTENT_PATTERN.test(text) ||
    AGENT_PHRASE_INTENT_PATTERN.test(text) ||
    AGENT_LISTENING_CUE_INTENT_PATTERN.test(text) ||
    AGENT_MINIMAL_PAIR_INTENT_PATTERN.test(text) ||
    AGENT_WORD_FAMILY_INTENT_PATTERN.test(text) ||
    AGENT_REGISTER_INTENT_PATTERN.test(text) ||
    AGENT_GRAMMAR_INTENT_PATTERN.test(text) ||
    AGENT_TRANSLATION_INTENT_PATTERN.test(text) ||
    AGENT_COMPARE_INTENT_PATTERN.test(text) ||
    AGENT_PRONUNCIATION_INTENT_PATTERN.test(text) ||
    AGENT_CORRECTION_INTENT_PATTERN.test(text) ||
    orderingRequest ||
    /完形|word\s*order|word\s+order|单词顺序|连词成句|句子不会写|不会写句子|造句不会|主旨|推断|定位/.test(text);
  const explicitChoiceQuestionIntent = /选择题|题组|question\s*set|multiple\s*choice|mcq|quiz|meaning\s+choice/.test(text) ||
    isNaturalMeaningQuizRequest(message);
  const hasExplicitChoicePayload = /options?|选项/.test(text) && /answer|correct|答案|正确/.test(text);
  const weakListeningCueOnlyRequest = /听力.{0,12}(?:听不懂|听不明白|听不出|听不出来|不行|不太行|老漏)|(?:老|总)?听不懂|(?:老|总)?听不明白|listening.{0,20}(?:weak|poor|not\s+good|struggle|hard|difficult)/i.test(text) &&
    !/题|题目|选择|quiz|questions?|mcq|choice|选项/.test(text);
  const fuzzyQuestion = !shortAnswerOnly && (!explicitStudyComponentIntent || explicitChoiceQuestionIntent || isFuzzyExamRescueQuestionRequest(message)) && (isFuzzyQuestionPracticeRequest(message) || isFuzzyExamRescueQuestionRequest(message) || /来个能做的题|给我来点基础题|基础题|四级.{0,12}(?:救|考)/.test(text) || (!hasExplicitChoicePayload && /meaning\s+choice.{0,24}(?:practice|card)/.test(text)));
  const wantsSentenceTranscript = isSentenceTranscriptAgentCardRequest(text) && !isOptionalExtraSentenceTranscriptMention(text);
  const excludesQuestions = /no\s+(?:quiz|questions?|question\s*set)|without\s+(?:quiz|questions?|question\s*set)|不要.{0,8}(?:题组|题目(?!预览)|问题|测验|quiz|题(?!目?预览))|不需要.{0,8}(?:题组|题目(?!预览)|问题|测验|quiz|题(?!目?预览))|无(?:题组|题目(?!预览)|问题|测验|题(?!目?预览))|别出题|别加.{0,8}(?:题(?!目?预览)|题目(?!预览)|题组|questions?)/i.test(text);
  const singleReadingChoice = isSingleReadingChoiceRequest(text);
  const multiFillBlankQuestions = !cetWordBankCloze && !excludesQuestions && isMultiFillBlankQuestionSetRequest(text);
  const singleInteractiveOnly = isSingleInteractiveOnlyAgentCardRequest(text);
  const choiceOnly = singleReadingChoice || (!singleInteractiveOnly && !multiFillBlankQuestions && isChoiceAnswerCardRequest(text));
  const choiceConstraint = choiceOnly ? inferExplicitChoiceConstraint(message) : null;
  const requestedCounts = inferRequestedAgentCardItemCounts(message);
  const explicitClozeOnly = isExplicitClozeOnlyRequest(message);
  const explicitClozeConstraint = inferExplicitClozeConstraint(message, requestedCounts.cloze);
  const listeningCueConstraint = inferExplicitListeningCueConstraint(message);
  if (
    listeningCueConstraint?.terms?.length &&
    requestedCounts.listening_cue &&
    requestedCounts.listening_cue < listeningCueConstraint.terms.length
  ) {
    requestedCounts.listening_cue = listeningCueConstraint.terms.length;
  }
  const explicitSingleChoicePayload = choiceConstraint && (requestedCounts.question_set || inferAgentRequestedQuestionCount(message) || 1) <= 1;
  const vocabularyQuizRequest = /(?:这个|这|那个|那)?(?:单词|词汇|生词).{0,12}(?:记不住|背不住|总忘).{0,12}(?:考|练)|(?:考我|quiz).{0,12}(?:单词|词汇|生词)/i.test(text);
  const multiQuestionChoice = !orderingRequest && !weakListeningCueOnlyRequest && !explicitSingleChoicePayload && !singleReadingChoice && !wantsSentenceTranscript && !excludesQuestions && !singleInteractiveOnly && !cetWordBankCloze && !isSingleMeaningChoiceClozeRequest(message) && (isNaturalMeaningQuizRequest(message) || isMultiQuestionChoiceCardRequest(text) || fuzzyQuestion || dialogueQuestionSet || currentMaterialQuestion || vocabularyQuizRequest);
  const allowed = new Set(AGENT_CARD_STRUCTURAL_COMPONENTS);
  const add = (...types) => types.forEach((type) => allowed.add(type));
  const readingPassageQuiz = !orderingRequest && !singleReadingChoice && (fuzzyReading || isReadingPassageQuizRequest(text));
  const explicitSingleCloze = isExplicitSingleClozeRequest(message);
  const directQuestionRequest = !excludesQuestions && !readingPassageQuiz && !fuzzyWriting && !fuzzySpeaking && !fuzzyScenario &&
    !multiQuestionChoice && !multiFillBlankQuestions && !cetWordBankCloze &&
    /question|quiz|题目|答题|做题|习题|看题|只看题/.test(text);
  if (directQuestionRequest) add("audio", "question_preview", "feedback");
  if (wantsSentenceTranscript) {
    add("audio", "sentence_transcript");
  } else if (!singleReadingChoice && /transcript|原文|文本/.test(text)) {
    add("transcript");
  }
  if (cetWordBankCloze) add("summary", "cloze");
  if (multiQuestionChoice || multiFillBlankQuestions || readingPassageQuiz) add("question_set");
  if (singleReadingChoice) add("summary", "cloze");
  if (readingPassageQuiz) add("summary");
  else if (choiceOnly && !multiQuestionChoice) add("cloze");
  if (!choiceOnly && !AGENT_ERROR_HUNT_INTENT_PATTERN.test(message) && !/只要.{0,12}(?:改正|纠错|改错|修正|纠正)|仅.{0,12}(?:改正|纠错|改错|修正|纠正)/i.test(message) && /错因|错题|mistake|wrong|correction|纠错|改错|纠正/.test(text)) add("feedback", "correction", "mistake_pattern", "suggestion");
  const listeningCueIntent = weakListeningCueOnlyRequest ||
    AGENT_LISTENING_CUE_INTENT_PATTERN.test(message) ||
    /听力.{0,8}(?:转折|因果|信号|老漏|听不出)|(?:转折|因果).{0,8}(?:听不出|老漏)/.test(text);
  const minimalPairIntent = AGENT_MINIMAL_PAIR_INTENT_PATTERN.test(message) || isExplicitWeakMinimalPairRequest(message);
  const wordFamilyIntent = AGENT_WORD_FAMILY_INTENT_PATTERN.test(message);
  const registerIntent = AGENT_REGISTER_INTENT_PATTERN.test(message);
  const strictRegisterOnly = isStrictRegisterOnlyAgentCardRequest(message);
  const grammarIntent = AGENT_GRAMMAR_INTENT_PATTERN.test(message);
  const phraseIntent = AGENT_PHRASE_INTENT_PATTERN.test(message);
  const vocabIntent = AGENT_VOCABULARY_INTENT_PATTERN.test(message) &&
    !vocabularyQuizRequest &&
    !listeningCueIntent &&
    !minimalPairIntent &&
    !wordFamilyIntent &&
    !registerIntent &&
    !phraseIntent;
  if (!choiceOnly && !multiQuestionChoice && vocabIntent) add("vocabulary", ...(/记不住|背不住|总忘|not\s+good|weak|poor|struggle|生词|关键词|生活词|术语|词表|glossary|terms?|terminology|中文释义|只要.{0,12}(?:单词|词汇|中文意思|关键词|释义)|only\s+vocab|vocab\s+only/i.test(message) ? [] : ["word_family"]));
  if (!choiceOnly && !multiQuestionChoice && phraseIntent) add("phrase", ...(/总忘|记不住|背不住|no\s+examples?|without\s+examples?|不要.{0,8}例句|不需要.{0,8}例句|只要.{0,12}(?:短语|搭配|用法)|only\s+(?:phrases?|collocations?)/i.test(message) ? [] : ["examples"]));
  if (!choiceOnly && !multiQuestionChoice && grammarIntent) {
    const weakGrammarPractice = AGENT_WEAKNESS_PRACTICE_CUE_PATTERN.test(text);
    add("grammar", ...(/只要.{0,12}(?:语法规则|规则卡|规则)|不要.{0,8}(?:填空|改错|例句)|不需要.{0,8}(?:填空|改错|例句)|no\s+(?:cloze|correction|examples?)/i.test(message)
      ? []
      : weakGrammarPractice
        ? ["cloze"]
        : ["examples", "cloze", "correction"]));
  }
  if (!choiceOnly && !multiQuestionChoice && /translation|translate|翻译|翻成|译文|英译中|中译英|译成/.test(text)) add("translation", ...(/no\s+examples?|without\s+examples?|不要.{0,8}例句|不需要.{0,8}例句|只要.{0,12}(?:翻译|译文)|only\s+translation/i.test(message) ? [] : ["examples"]));
  if (!choiceOnly && !multiQuestionChoice && /compare|difference|区别|对比|辨析| vs |make\/do|make\s+do|make.*do|do.*make|搞混/.test(text)) add("compare", "examples");
  if (!choiceOnly && !multiQuestionChoice && /pronunciation|发音|音标|连读|重音|final\s*-?ed|\b-ed\b/.test(text)) add("pronunciation", ...(/不要.{0,8}(?:辨音|题|词对)|不需要.{0,8}(?:辨音|题|词对)|no\s+(?:minimal\s*pairs?|questions?|quiz)|without\s+(?:minimal\s*pairs?|questions?|quiz)|只要.{0,12}(?:发音|提示)|only\s+pronunciation/i.test(message) ? [] : ["minimal_pair", "examples"]));
  if (!choiceOnly && !multiQuestionChoice && listeningCueIntent) {
    const weakCueOnly = AGENT_WEAKNESS_PRACTICE_CUE_PATTERN.test(text);
    add(
      "listening_cue",
      ...(weakCueOnly || /no\s+transcript|without\s+transcript|不要.{0,8}(?:原文|文本|transcript)|只要.{0,12}(?:cue|信号词|连接词|衔接词)/i.test(message) ? [] : ["transcript"]),
      ...(weakCueOnly || /no\s+examples?|without\s+examples?|不要.{0,8}例句|不需要.{0,8}例句/i.test(message) ? [] : ["examples"])
    );
  }
  if (!choiceOnly && !multiQuestionChoice && minimalPairIntent) add("minimal_pair", ...(/no\s+pronunciation|without\s+pronunciation|不要.{0,8}(?:发音|音标|口型|pronunciation)|别.{0,12}(?:讲|解释|发音|音标|口型|pronunciation)|别讲太多|只要.{0,12}(?:pair|词对|音近词)/i.test(message) ? [] : ["pronunciation"]), ...(/no\s+examples?|without\s+examples?|不要.{0,8}例句|不需要.{0,8}例句|别讲太多/i.test(message) ? [] : ["examples"]));
  if (!choiceOnly && !multiQuestionChoice && wordFamilyIntent) add("word_family");
  if (!choiceOnly && !multiQuestionChoice && !multiFillBlankQuestions && !cetWordBankCloze && (/cloze|blank|填空|挖空|完形/.test(text) || explicitSingleCloze || explicitClozeOnly || explicitClozeConstraint?.strict)) {
    const plainClozeOnly = explicitSingleCloze ||
      explicitClozeOnly ||
      /完形/.test(text) ||
      (requestedCounts.cloze || inferAgentRequestedClozeBlankCount(message)) > 1 ||
      /只要.{0,12}(?:填空|完形|cloze)|only\s+(?:cloze|fill[-\s]*in[-\s]*the[-\s]*blank)/i.test(message);
    add("cloze", ...(/不要.{0,8}(?:语法|讲解|解析|例句)|不需要.{0,8}(?:语法|讲解|解析|例句)|no\s+(?:grammar|explanations?|examples?)/i.test(message) || plainClozeOnly ? [] : ["grammar"]));
  }
  if (!choiceOnly && !multiQuestionChoice && !isShortAnswerNegatedRequest(text) && /dictation|typed\s*answer|type\s+the\s+sentence|free\s*response|short\s*answer|text\s*input|input\s*answer|listen\s+and\s+type|type\s+what\s+i\s+hear|默写|输入答案|打字|短答|听写|听到的打出来/.test(text)) add("short_answer");
  if (!choiceOnly && !multiQuestionChoice && orderingRequest) add("ordering");
  if (!choiceOnly && !multiQuestionChoice && !orderingRequest && !isSentenceBuilderNegatedRequest(text) && !explicitClozeOnly && /sentence\s*builder|word\s*order|word\s+order|reorder\s+words?|组句|连词成句|重新排成句子|单词顺序|词语排序|词块排序/.test(text)) add("sentence_builder");
  if ((fuzzyScenario && !dialogueQuestionSet) || explicitScenarioRequest || /scenario|situational|场景|情景/.test(text)) add("scenario", ...(!choiceOnly && !fuzzyScenario && !dialogueQuestionSet ? ["register", "examples"] : []));
  if (multiQuestionChoice && explicitScenarioRequest) add("scenario");
  if (!choiceOnly && registerIntent) add("register", ...(strictRegisterOnly || /no\s+examples?|without\s+examples?|不要.{0,8}例句|不需要.{0,8}例句|只要.{0,12}(?:转换|改写|语气)|only\s+register/i.test(message) ? [] : ["examples"]));
  if (!choiceOnly && !multiQuestionChoice && !dialogueQuestionSet && (fuzzySpeaking || /speaking|\boral\b|口语/.test(text))) add("speaking_prompt", ...(fuzzySpeaking || isAgentPromptOnlyRequest(message) ? [] : ["rubric", "correction"]));
  if (!choiceOnly && !AGENT_STORYTELLING_INTENT_PATTERN.test(message) && (fuzzyWriting || /writing|outline|essay|写作|提纲|作文/.test(text))) {
    const excludesWriting = /no\s+writing|without\s+writing|no\s+outline|without\s+outline|不要.{0,8}(?:写作|提纲|writing|outline)|不需要.{0,8}(?:写作|提纲|writing|outline)|别加.{0,8}(?:写作|提纲|writing|outline)/i.test(message);
    add("summary", "writing_outline", ...(excludesWriting ? [] : ["short_answer"]));
  }
  if (!choiceOnly && !multiQuestionChoice && /rubric|criteria|评分|标准/.test(text)) add("rubric");
  if (!choiceOnly && !multiQuestionChoice && AGENT_CORRECTION_INTENT_PATTERN.test(text)) {
    const terseCorrection = AGENT_WEAKNESS_PRACTICE_CUE_PATTERN.test(text) || /练|练习|帮我|几个|几条|几句/.test(text);
    add("correction", ...(/不要.{0,8}(?:语法|讲解|解析|例句)|不需要.{0,8}(?:语法|讲解|解析|例句)|只要.{0,12}(?:改正|纠错|改错|修正|纠正)|no\s+(?:grammar|explanations?|examples?)/i.test(message) || terseCorrection ? [] : ["grammar"]));
  }
  if (!choiceOnly && !multiQuestionChoice && AGENT_ETHICS_INTENT_PATTERN.test(message)) add("ethics");
  if (!choiceOnly && !multiQuestionChoice && AGENT_DEBATE_INTENT_PATTERN.test(message)) add("debate");
  if (!choiceOnly && !multiQuestionChoice && AGENT_ERROR_HUNT_INTENT_PATTERN.test(message)) add("error_hunt");
  if (!choiceOnly && !multiQuestionChoice && AGENT_STORYTELLING_INTENT_PATTERN.test(message)) add("storytelling");
  if (!choiceOnly && !multiQuestionChoice && AGENT_PARAPHRASE_INTENT_PATTERN.test(message)) add("paraphrase");
  const excluded = inferAgentCardExcludedComponents(text);
  const scenarioConstraint = inferExplicitScenarioConstraint(message);
  const allowsOptionalExtraSuggestions = isOptionalExtraSuggestionAllowed(text);
  excluded.forEach((type) => allowed.delete(type));
  if (excluded.has("speaking_prompt")) {
    if (!/rubric|criteria|评分|标准/i.test(text)) allowed.delete("rubric");
    if (!AGENT_CORRECTION_INTENT_PATTERN.test(message)) allowed.delete("correction");
  }
  if (allowed.has("speaking_prompt") && /only\s+(?:speaking\s*)?prompts?|只要.{0,8}(?:口语提示|prompt|题目)|只给.{0,8}(?:口语提示|prompt|题目)/i.test(text)) {
    ["scenario", "register", "examples", "rubric", "correction", "vocabulary", "grammar"].forEach((type) => allowed.delete(type));
  }
  if (orderingRequest) {
    ["vocabulary", "word_family", "grammar", "cloze", "examples", "question_set", "sentence_builder", "summary"].forEach((type) => allowed.delete(type));
    allowed.add("ordering");
  }
  if (!orderingRequest && !isSentenceBuilderNegatedRequest(text) && !explicitClozeOnly && (isSentenceBuilderOnlyAgentCardRequest(text) || /sentence\s*builder|word\s*order|word\s+order|reorder\s+words?|组句|连词成句|重新排成句子|单词顺序|词语排序|词块排序/.test(text))) {
    ["vocabulary", "word_family", "grammar", "cloze", "examples", "question_set"].forEach((type) => {
      if (type !== "sentence_builder") allowed.delete(type);
    });
    allowed.add("sentence_builder");
  }
  if (wantsSentenceTranscript) {
    allowed.delete("question_set");
    allowed.delete("question_preview");
  }
  if (!multiQuestionChoice && minimalPairIntent && /分不清|听不出|听不出来|易混音|长短音|最小对|ship\s*(?:\/|\s+|和|and)\s*sheep|live\s*(?:\/|\s+|和|and)\s*leave|\br\s*(?:和|and|\/)\s*l\b|\bl\s*(?:和|and|\/)\s*r\b|\/ɪ\/|\/iː\//i.test(message)) {
    ["pronunciation", "examples", "audio", "compare"].forEach((type) => allowed.delete(type));
    allowed.add("minimal_pair");
  }
  if (!multiQuestionChoice && minimalPairIntent && /听辨|辨音|听音辨词|点了能听|ship\s+(?:sheep)|live\s+(?:leave)/i.test(message)) {
    ["pronunciation", "examples", "audio", "question_set"].forEach((type) => allowed.delete(type));
    allowed.add("minimal_pair");
  }
  if ((!minimalPairIntent || excluded.has("minimal_pair")) && !excluded.has("pronunciation") && AGENT_PRONUNCIATION_INTENT_PATTERN.test(message) && /只要.{0,12}(?:口型|提示|发音)|only\s+(?:mouth|pronunciation|tips?)|不要.{0,8}(?:例句|音频)|no\s+(?:examples?|audio)|without\s+(?:examples?|audio)/i.test(message)) {
    ["minimal_pair", "examples", "audio", "question_set"].forEach((type) => allowed.delete(type));
    allowed.add("pronunciation");
  }
  if (!multiQuestionChoice && minimalPairIntent && isStrictStaticMinimalPairRequest(message)) {
    ["pronunciation", "examples", "audio", "compare", "question_set", "cloze", "sentence_builder", "grammar"].forEach((type) => allowed.delete(type));
    allowed.add("minimal_pair");
  }
  if (isCorrectionOnlyAgentCardRequest(message)) {
    ["examples", "cloze", "grammar", "feedback", "mistake_pattern", "suggestion"].forEach((type) => allowed.delete(type));
    if (isEditableCorrectionShortAnswerRequest(message)) {
      ["correction", "question_set"].forEach((type) => allowed.delete(type));
      allowed.add("short_answer");
    } else {
      allowed.add("correction");
    }
  }
  if (!explicitClozeOnly && !excluded.has("translation") && AGENT_TRANSLATION_INTENT_PATTERN.test(message) && /only\s+translation|只要.{0,12}(?:翻译|译文)|no\s+examples?|不要.{0,8}例句/i.test(text)) {
    ["phrase", "vocabulary", "word_family", "examples", "compare", "question_set", "cloze"].forEach((type) => allowed.delete(type));
    allowed.add("translation");
  }
  if (/中译英.{0,12}(?:老卡壳|卡壳|不会|不行|老错)/i.test(message)) {
    ["phrase", "vocabulary", "word_family", "examples", "compare", "question_set", "cloze"].forEach((type) => allowed.delete(type));
    allowed.add("translation");
  }
  if (/改错.{0,12}(?:时态|tense)|(?:时态|tense).{0,12}(?:改错|纠错)/i.test(message)) {
    ["examples", "cloze", "grammar", "feedback", "mistake_pattern", "suggestion"].forEach((type) => allowed.delete(type));
    allowed.add("correction");
  }
  if (grammarIntent && AGENT_WEAKNESS_PRACTICE_CUE_PATTERN.test(text) && !AGENT_CORRECTION_INTENT_PATTERN.test(message)) {
    ["correction", "examples", "feedback", "mistake_pattern", "suggestion"].forEach((type) => allowed.delete(type));
    allowed.add("grammar");
    allowed.add("cloze");
  }
  if (fuzzyQuestion && (fuzzyReading || fuzzySpeaking) && /先来(?:一|1|个)|先练(?:一|1|个)|先做(?:一|1|个)|one\s+(?:first|card|practice)/i.test(message)) {
    ["speaking_prompt", "rubric", "correction", "register", "scenario"].forEach((type) => allowed.delete(type));
    allowed.add("summary");
    allowed.add("question_set");
  }
  if (excluded.has("pronunciation") && !minimalPairIntent) allowed.delete("minimal_pair");
  if (excluded.has("vocabulary") && !wordFamilyIntent) allowed.delete("word_family");
  const preserveExplicitCloze = allowed.has("cloze") && (explicitSingleCloze || explicitClozeOnly || explicitClozeConstraint?.strict);
  if (excluded.has("grammar") && !grammarIntent) {
    if (!preserveExplicitCloze) allowed.delete("cloze");
    if (!AGENT_CORRECTION_INTENT_PATTERN.test(message)) allowed.delete("correction");
  }
  if (excluded.has("grammar") && allowed.has("cloze")) {
    allowed.delete("grammar");
    if (!AGENT_CORRECTION_INTENT_PATTERN.test(message)) allowed.delete("correction");
  }
  if (strictRegisterOnly && allowed.has("register")) {
    ["summary", "writing_outline", "short_answer", "scenario", "examples", "grammar", "rubric", "question_set", "speaking_prompt"].forEach((type) => allowed.delete(type));
    allowed.add("register");
  }
  excluded.forEach((type) => allowed.delete(type));
  return {
    allowed,
    scoped: Array.from(allowed).some((type) => !AGENT_CARD_STRUCTURAL_COMPONENTS.has(type)),
    materialReady: false,
    strictChoice: choiceOnly,
    multiQuestionChoice,
    singleReadingChoice,
    multiFillBlankQuestions,
    dialogueQuestionSet,
    cetWordBankCloze,
    fuzzyWriting,
    fuzzySpeaking,
    fuzzyScenario,
    excluded,
    choiceConstraint: multiFillBlankQuestions ? null : choiceConstraint,
    translationConstraint: inferExplicitTranslationConstraint(message),
    listeningCueConstraint,
    clozeConstraint: excluded.has("cloze") || multiFillBlankQuestions ? null : explicitClozeConstraint,
    scenarioConstraint,
    readingPassageQuiz,
    requestedCounts,
    promptOnly: isSingleSpeakingPromptOnlyRequest(message, requestedCounts),
    messageText: String(message || ""),
    suppressAudioCueText: excluded.has("audio"),
    suppressAnswerExplanation: isAgentNoExplanationRequest(message),
    suppressExtraSuggestions: !allowsOptionalExtraSuggestions && (/no\s+extra\s+(modules?|cards?|content)|without\s+extra\s+(modules?|cards?|content)|(?:do\s+not|don't|dont)\s+(?:add|include|show|generate|create).{0,80}extra\s+(modules?|cards?|content)|only\s+core|核心.*即可|只要.*核心|不要.*额外|不需要.*额外|无额外|别加别的|不要别的|只做阅读|只要题目|只给题目|普通.{0,8}(?:原文|文本)|只(?:看|要|显示).{0,8}(?:原文|文本)|不要.{0,8}(?:逐句|点播|精听)|不需要.{0,8}(?:逐句|点播|精听)|without\s+sentence[_\s-]*(?:transcript|replay)|no\s+sentence[_\s-]*(?:transcript|replay)/.test(text) ||
      /别讲太多|不要.{0,8}讲太多|不用.{0,8}讲太多|少讲|简短点|短一点|简单点/.test(text) ||
      cetWordBankCloze ||
      fuzzyWriting ||
      fuzzyReading ||
      readingPassageQuiz ||
      fuzzySpeaking ||
      dialogueQuestionSet ||
      isFuzzyInterviewEnglishPracticeRequest(message) ||
      fuzzyQuestion ||
      /中译英.{0,12}(?:老卡壳|卡壳|不会|不行|老错)|改错.{0,12}(?:时态|tense)|(?:时态|tense).{0,12}(?:改错|纠错)/i.test(text) ||
      (AGENT_WEAKNESS_PRACTICE_CUE_PATTERN.test(text) && (vocabIntent || phraseIntent || listeningCueIntent || /make\/do|make\s+do|make.*do|do.*make|搞混/.test(text))) ||
      /默写|听写|输入答案|短答|dictation|short\s*answer/.test(text) ||
      /不要.{0,8}题目预览|不需要.{0,8}题目预览|无题目预览|no\s+question\s*preview|without\s+question\s*preview/.test(text) ||
      isPlainTranscriptAgentCardRequest(text) ||
      (minimalPairIntent && /分不清|听不出|听不出来|易混音|长短音|ship\s*\/\s*sheep|\br\s*(?:和|and|\/)\s*l\b|\bl\s*(?:和|and|\/)\s*r\b|\/ɪ\/|\/iː\//i.test(message)) ||
      orderingRequest ||
      (!orderingRequest && /sentence\s*builder|word\s*order|word\s+order|reorder\s+words?|组句|连词成句|重新排成句子|单词顺序|词语排序|词块排序/.test(text)) ||
      /chunks?|idioms?|短语|搭配|语块|习语/.test(text) && /no\s+examples?|without\s+examples?|meaning\s+only|不要.{0,8}例句|不需要.{0,8}例句|只要/.test(text) ||
      /只要|只给|只显示|only\s+(?:vocab|vocabulary|phrases?|collocations?|chunks?|idioms?|listening_cue|cues?|signal\s*words?|minimal\s*pairs?|pairs?|word\s*family|register|grammar|translation|sentence\s*builder|ordering|cloze)|不要.{0,8}(?:例句|发音|音频|原文|语法|评分|单词解释)|别加|别放/.test(text) ||
      /只要.*(?:scenario|场景|对话|情景)|only\s+scenario/.test(text))
  };
}

function isOptionalExtraSuggestionAllowed(text = "") {
  const value = String(text || "").toLowerCase();
  if (/(?:\u540e\u9762|\u540e\u7eed|\u4ee5\u540e|\u9700\u8981\u65f6|later|afterwards|follow[-\s]*up).{0,24}(?:\u53ef\u4ee5|\u518d|\u7ee7\u7eed)?.{0,12}(?:\u7ec3|\u751f\u6210|\u505a|\u52a0|\u770b|practice|train|generate|add)|(?:\u53ef\u4ee5|\u80fd|\u9700\u8981\u65f6).{0,12}\u518d.{0,12}(?:\u7ec3|\u751f\u6210|\u505a|\u52a0|\u770b|practice|train|generate|add)/i.test(value)) return true;
  if (/no\s+(?:extra\s+)?(?:suggestions?|modules?|cards?|content)|without\s+(?:extra\s+)?(?:suggestions?|modules?|cards?|content)|(?:do\s+not|don't|dont)\s+(?:add|include|show|generate|create).{0,80}(?:suggestions?|extra\s+(?:modules?|cards?|content))|不要.{0,8}(?:建议|推荐|额外|别的)|不需要.{0,8}(?:建议|推荐|额外|别的)|无(?:建议|额外)/.test(value)) return false;
  return /(?:额外|可选|后续|需要时|extra|optional).{0,20}(?:建议|suggestions?)|(?:建议|suggestions?).{0,20}(?:额外|可选|后续|需要时|extra|optional)/i.test(value);
}

function isAgentNoExplanationRequest(message = "") {
  const text = String(message || "").toLowerCase();
  return /no\s+(?:explanations?|rationale|analysis)|without\s+(?:explanations?|rationale|analysis)|不要.{0,10}(?:解析|解释|讲解|答案解析)|不需要.{0,10}(?:解析|解释|讲解|答案解析)|无(?:解析|解释|讲解)|只(?:要|显示).{0,10}(?:题目|题干|选项)/i.test(text);
}

function isSingleInteractiveOnlyAgentCardRequest(text = "") {
  return isShortAnswerOnlyAgentCardRequest(text) || isSentenceBuilderOnlyAgentCardRequest(text);
}

function isMultiFillBlankQuestionSetRequest(text = "") {
  const value = String(text || "").toLowerCase();
  if (/no\s+(?:cloze|fill(?:\s+in)?\s+blanks?|blank)|without\s+(?:cloze|fill(?:\s+in)?\s+blanks?|blank)|不要.{0,8}(?:cloze|blank|填空|空格)|不需要.{0,8}(?:cloze|blank|填空|空格)|无(?:填空|空格)/i.test(value)) return false;
  if (!/(?:fill(?:-|\s*)in(?:-|\s*)the(?:-|\s*)blank|fill(?:-|\s*)in(?:-|\s*)blank|cloze|blank|填空|选词填空|word\s*bank)/i.test(value)) return false;
  if (isCetWordBankClozeRequest(value)) return false;
  if (!/(?:questions?|题|题目|练习题|习题|题组|question\s*set)/i.test(value)) return false;
  const requestedCount = inferAgentRequestedQuestionCount(value) ||
    inferRequestedAgentCardItemCounts(value).question_set ||
    0;
  return requestedCount >= 2;
}

function isExplicitSingleClozeRequest(message = "") {
  const text = String(message || "").toLowerCase();
  return /___|_{2,}|填空卡|填空题|cloze/.test(text) &&
    /options?|选项/.test(text) &&
    /answer|correct\s+answer|答案|正确答案/.test(text);
}

function isExplicitClozeOnlyRequest(message = "") {
  const text = String(message || "").toLowerCase();
  const hasClozeCue = /cloze|blank|fill[-\s]*in[-\s]*the[-\s]*blank|填空|挖空|空格|多空填空|填空卡|填空练习/.test(text);
  if (!hasClozeCue) return false;
  return /only\s+(?:one\s+)?(?:cloze|fill[-\s]*in[-\s]*the[-\s]*blank)|只要.{0,12}(?:cloze|填空|挖空|填空卡|多空填空)|(?:挖空答案|答案依次是|选项是|句子是).{0,220}(?:选项|答案|cloze|填空)/i.test(text);
}

function isOptionalExtraSentenceTranscriptMention(text = "") {
  const value = String(text || "").toLowerCase();
  if (/(?:\u540e\u9762|\u540e\u7eed|\u4ee5\u540e|\u9700\u8981\u65f6|later|afterwards|follow[-\s]*up).{0,24}(?:\u7cbe\u542c|\u9010\u53e5|\u590d\u542c|sentence[_\s-]*transcript|sentence[_\s-]*by[_\s-]*sentence)|(?:\u53ef\u4ee5|\u80fd|\u9700\u8981\u65f6).{0,12}\u518d.{0,12}(?:\u7cbe\u542c|\u9010\u53e5|\u590d\u542c|sentence[_\s-]*transcript|sentence[_\s-]*by[_\s-]*sentence)/i.test(value)) return true;
  return /(?:额外|可选|建议|suggestion|optional|extra).{0,16}(?:逐句|一句一句|复听|精听|sentence[_\s-]*transcript|sentence[_\s-]*by[_\s-]*sentence)|(?:逐句|一句一句|复听|精听|sentence[_\s-]*transcript|sentence[_\s-]*by[_\s-]*sentence).{0,16}(?:额外|可选|建议|suggestion|optional|extra)/i.test(value);
}

function enforceMaterialAgentCardScope(cardSpec, allowed) {
  const byType = new Map();
  (cardSpec.components || []).forEach((component) => {
    if (!byType.has(component.type)) byType.set(component.type, component);
  });
  const demoted = (cardSpec.components || [])
    .filter((component) => !allowed.has(component.type) && !AGENT_CARD_STRUCTURAL_COMPONENTS.has(component.type))
    .map(agentCardComponentLabel)
    .filter(Boolean);
  const presentComponents = new Set(
    AGENT_CARD_MATERIAL_CORE_ORDER.filter((type) => type !== "suggestion" && type !== "actions")
  );
  const suggestion = mergeAgentCardSuggestion(byType.get("suggestion"), Array.from(new Set(demoted)), presentComponents);
  const components = AGENT_CARD_MATERIAL_CORE_ORDER.map((type) => {
    if (type === "suggestion") return suggestion;
    if (type === "actions") return byType.get(type) || { type };
    return withRequiredMaterialSource(byType.get(type) || defaultAgentCardCoreComponent(type));
  }).filter(Boolean);
  return {
    ...cardSpec,
    components,
    actions: []
  };
}

function withRequiredMaterialSource(component = {}) {
  const sourceByType = {
    audio: "latestMaterial.audio",
    transcript: "latestMaterial.script",
    sentence_transcript: "latestMaterial.script",
    question_preview: "latestMaterial.questions"
  };
  const expectedSource = sourceByType[component.type];
  if (!expectedSource) return component;
  const next = {
    ...component,
    source: expectedSource
  };
  if (component.type === "transcript" || component.type === "question_preview") {
    next.text = "";
    next.items = [];
  }
  return next;
}

function enforceGeneralAgentCardScope(cardSpec, allowed, scope = {}) {
  const kept = [];
  const demoted = [];
  let existingSuggestion = null;
  let hasActionsComponent = false;
  (cardSpec.components || []).forEach((component) => {
    if (component.type === "suggestion") {
      if (scope.suppressExtraSuggestions) return;
      existingSuggestion = component;
    } else if (component.type === "actions") {
      hasActionsComponent = true;
    } else if (allowed.has(component.type)) {
      if (shouldDropStrictStructuralAgentCardComponent(component, scope)) return;
      kept.push(component);
    } else if (!AGENT_CARD_STRUCTURAL_COMPONENTS.has(component.type)) {
      demoted.push(agentCardComponentLabel(component));
    }
  });
  if (!kept.some((component) => component.type === "header")) kept.unshift({ type: "header" });
  let compacted = kept.map((component) =>
    enforceExplicitChoiceComponentScope(
      compactAgentCardComponentForScope(component, scope),
      scope
    )
  );
  compacted = ensureRequiredAgentCardComponents(compacted, scope);
  compacted = enforceReadingPassageCurrentRequestScope(compacted, scope);
  compacted = enforceScenarioQuestionSetCurrentRequestScope(compacted, scope);
  compacted = mergeMistakePatternIntoCorrectionOnlyCard(compacted, scope);
  compacted = enforceWritingOutlineCurrentRequestScope(compacted, scope);
  compacted = compacted.map(removeRedundantAgentCardItemsForTypedPayload);
  const presentComponents = new Set(compacted.map((component) => component.type));
  const suggestion = mergeAgentCardSuggestion(
    existingSuggestion,
    scope.suppressExtraSuggestions ? [] : Array.from(new Set(demoted.filter(Boolean))),
    presentComponents,
    scope
  );
  if (suggestion) compacted.push(suggestion);
  const actions = filterAgentCardActions(cardSpec.actions || [], allowed, presentComponents, scope);
  if (actions.length || (hasActionsComponent && shouldKeepEmptyActionsComponent(compacted, scope)) || shouldAddEmptyActionsComponent(compacted, scope)) compacted.push({ type: "actions" });
  const shouldNeutralizeNoAudioShortAnswer = isNoAudioShortAnswerScope(scope, presentComponents);
  const shortAnswerAnswer = findAgentCardShortAnswerAnswer(compacted);
  const hasSentenceBuilder = presentComponents.has("sentence_builder");
  const repairReadingHeading = scope.readingPassageQuiz && readingCardHeadingNeedsCurrentRequestRepair(cardSpec, scope);
  const writingTopic = inferWritingOutlineTopic(scope.messageText || "");
  const scopedTitle = scope.choiceConstraint?.target
    ? `Meaning: ${scope.choiceConstraint.target}`
    : scope.readingPassageQuiz
      ? readingCardTitleForRequest(scope.messageText || "")
      : writingTopic
        ? writingTopic.title
        : scope.cetWordBankCloze && presentComponents.has("cloze")
          ? cetWordBankClozeTitle(
              (compacted.find((component) => component?.type === "cloze")?.options || []).length || 15,
              agentCardClozeAnswerParts(compacted.find((component) => component?.type === "cloze")?.answer || "").length ||
                inferRequestedAgentCardItemCounts(scope.messageText || "").cloze ||
                inferAgentRequestedClozeBlankCount(scope.messageText || "") ||
                10
            )
        : presentComponents.has("question_set") && /question\s*set/i.test(cardSpec.title || "")
          ? questionSetCardTitleForRequest(scope.messageText || "")
        : shouldNeutralizeNoAudioShortAnswer
          ? normalizeNoAudioShortAnswerCardTitle(cardSpec.title)
          : scope.suppressAudioCueText
            ? neutralizeNoAudioCueText(cardSpec.title, cardSpec.title)
            : cardSpec.title;
  const baseSubtitle = scope.choiceConstraint?.target
    ? ""
    : scope.readingPassageQuiz
      ? readingCardSubtitleForRequest(scope.messageText || "")
      : writingTopic
        ? ""
        : scope.cetWordBankCloze && presentComponents.has("cloze")
          ? ""
        : hasSentenceBuilder
          ? stripSentenceBuilderUsageInstruction(cardSpec.subtitle || "")
          : shouldNeutralizeNoAudioShortAnswer
            ? neutralizeNoAudioCueText(cardSpec.subtitle, "")
            : scope.suppressAudioCueText
              ? neutralizeNoAudioCueText(cardSpec.subtitle, "")
              : cardSpec.subtitle;
  const heading = sanitizeAgentCardHeadingForScope(
    scopedTitle,
    baseSubtitle,
    compacted,
    scope
  );
  return {
    ...cardSpec,
    title: visibleShortAnswerTextWithoutAnswerLeak(heading.title, shortAnswerAnswer, "Short Answer Practice"),
    subtitle: visibleShortAnswerTextWithoutAnswerLeak(heading.subtitle, shortAnswerAnswer, ""),
    chips: scope.suppressExtraSuggestions ? [] : filterAgentCardChips(cardSpec.chips || [], scope)
      .map((chip) => scope.suppressAudioCueText ? neutralizeNoAudioCueText(chip, "") : chip)
      .filter(Boolean)
      .filter((chip) => !textContainsShortAnswer(chip, shortAnswerAnswer)),
    components: dedupeAgentCardComponents(compacted).map(removeRedundantAgentCardItemsForTypedPayload),
    actions
  };
}

function enforceWritingOutlineCurrentRequestScope(components = [], scope = {}) {
  const topic = inferWritingOutlineTopic(scope?.messageText || "");
  if (!topic) return components;
  return components.map((component) => {
    if (component?.type !== "writing_outline") return component;
    const requestedCount = scope?.requestedCounts?.writing_outline || 3;
    const steps = Array.isArray(component.steps) ? component.steps : [];
    const weak = steps.length < requestedCount || steps.some((step) => isGenericWritingOutlineStep(step));
    if (!weak && writingOutlineStepsMatchTopic(steps, topic)) {
      return {
        ...component,
        title: "Writing Outline",
        text: "",
        items: [],
        steps: steps.slice(0, requestedCount)
      };
    }
    return {
      ...component,
      title: "Writing Outline",
      text: "",
      items: [],
      steps: topic.steps.slice(0, requestedCount)
    };
  });
}

function inferWritingOutlineTopic(message = "") {
  const teacherAbsenceEmailTopic = inferTeacherAbsenceEmailWritingTopic(message);
  if (teacherAbsenceEmailTopic) return teacherAbsenceEmailTopic;
  const text = String(message || "").toLowerCase();
  if (!/writing|outline|essay|写作|提纲/.test(text)) return null;
  if (/online\s+shopping/.test(text) && /advantage|benefit|优点|好处/.test(text)) {
    return {
      title: "Online Shopping Advantages Outline",
      keywords: ["online shopping", "advantage", "convenient", "choice", "compare"],
      steps: [
        { label: "Claim", text: "State that online shopping has clear advantages for daily life." },
        { label: "Reason 1", text: "Explain how it saves time and makes buying things more convenient." },
        { label: "Reason 2", text: "Compare the wider choice and easier price checking with shopping in one local store." }
      ]
    };
  }
  return null;
}

function inferTeacherAbsenceEmailWritingTopic(message = "") {
  const text = String(message || "").toLowerCase();
  if (
    !/email/.test(text) ||
    !/teacher/.test(text) ||
    !/miss(?:ed)?\s+class|absen[ct]|yesterday/.test(text) ||
    !/homework/.test(text)
  ) {
    return null;
  }
  return {
    title: "Teacher Absence Email",
    keywords: ["teacher", "missed class", "yesterday", "homework"],
    steps: [
      { label: "Greeting", text: "Open politely to your teacher and state why you are writing." },
      { label: "Reason", text: "Explain why you missed class yesterday and include a brief apology." },
      { label: "Homework", text: "Ask what homework you should complete and close politely." }
    ]
  };
}

function isGenericWritingOutlineStep(step = {}) {
  const text = `${step?.label || ""} ${step?.text || ""}`.toLowerCase();
  return /state your main idea clearly|add one reason|one example|introduce the purpose|key details|logical order|clear next step/.test(text);
}

function writingOutlineStepsMatchTopic(steps = [], topic = {}) {
  const text = steps.map((step) => `${step?.label || ""} ${step?.text || ""}`).join(" ").toLowerCase();
  const keywords = Array.isArray(topic.keywords) ? topic.keywords : [];
  return keywords.filter((keyword) => text.includes(keyword.toLowerCase())).length >= 2;
}

function sanitizeAgentCardHeadingForScope(title = "", subtitle = "", components = [], scope = {}) {
  const present = new Set((components || []).map((component) => component?.type).filter(Boolean));
  const scopeAllowed = scope?.allowed instanceof Set ? scope.allowed : new Set(scope?.allowed || []);
  const blockedPatterns = disallowedAgentCardHeadingPatterns(scopeAllowed, present, scope);
  const headingText = `${title || ""} ${subtitle || ""}`;
  if (!blockedPatterns.some((pattern) => pattern.test(headingText))) {
    return { title, subtitle };
  }
  const fallbackTitle = agentCardTitleForPresentComponents(present);
  return { title: fallbackTitle, subtitle: "" };
}

function disallowedAgentCardHeadingPatterns(allowed = new Set(), present = new Set(), scope = {}) {
  const excluded = scope?.excluded instanceof Set ? scope.excluded : new Set(scope?.excluded || []);
  const patterns = [];
  AGENT_CARD_COMPONENT_KEYWORDS.forEach(([type, pattern]) => {
    if (!AGENT_CARD_STRUCTURAL_COMPONENTS.has(type) && !allowed.has(type) && !present.has(type)) {
      patterns.push(pattern);
    }
    if (excluded.has(type)) patterns.push(pattern);
  });
  if (scope?.suppressExtraSuggestions) {
    AGENT_CARD_COMPONENT_KEYWORDS.forEach(([type, pattern]) => {
      if (!AGENT_CARD_STRUCTURAL_COMPONENTS.has(type) && !present.has(type)) patterns.push(pattern);
    });
  }
  return patterns;
}

function shouldDropStrictStructuralAgentCardComponent(component = {}, scope = {}) {
  if (!scope?.suppressExtraSuggestions) return false;
  if (!AGENT_CARD_STRUCTURAL_COMPONENTS.has(component.type)) return false;
  if (component.type === "summary" && scope?.readingPassageQuiz) return false;
  return component.type !== "header" && component.type !== "actions";
}

function shouldAddEmptyActionsComponent(components = [], scope = {}) {
  if (agentCardActionsExplicitlyExcluded(scope)) return false;
  if (!scope?.suppressExtraSuggestions) return false;
  return false;
}

function shouldKeepEmptyActionsComponent(components = [], scope = {}) {
  if (agentCardActionsExplicitlyExcluded(scope)) return false;
  if (scope?.strictChoice || agentCardNeedsLocalAnswerActionsComponent(components)) return false;
  if (scope?.suppressExtraSuggestions) return false;
  return true;
}

function agentCardActionsExplicitlyExcluded(scope = {}) {
  const excluded = scope?.excluded instanceof Set ? scope.excluded : new Set(scope?.excluded || []);
  return excluded.has("actions");
}

function agentCardNeedsLocalAnswerActionsComponent(components = []) {
  const localAnswerTypes = new Set([
    "question_set",
    "cloze",
    "short_answer",
    "sentence_builder",
    "ordering"
  ]);
  return components.some((component) => localAnswerTypes.has(component?.type));
}

function ensureRequiredAgentCardComponents(components = [], scope = {}) {
  const next = [...components];
  if (scope.cetWordBankCloze && scope.allowed instanceof Set && scope.allowed.has("cloze")) {
    const existingCloze = next.find((component) => component?.type === "cloze");
    const cloze = buildCetWordBankClozeComponent(scope.messageText || "", existingCloze);
    const summary = buildCetWordBankClozeSummaryComponent(scope.messageText || "");
    return [
      ...next.filter((component) => !["summary", "question_set", "cloze", "grammar", "examples", "vocabulary"].includes(component?.type)),
      summary,
      cloze
    ];
  }
  if (
    scope.allowed instanceof Set &&
    scope.allowed.has("writing_outline") &&
    (scope.fuzzyWriting || shouldIncludeWritingInputComponent(scope.messageText || ""))
  ) {
    const existingSummary = next.find((component) => component?.type === "summary");
    const existingOutline = next.find((component) => component?.type === "writing_outline");
    const summary = isUsableWritingSummary(existingSummary)
      ? existingSummary
      : buildFallbackWritingSummaryComponent(scope.messageText || "");
    const outline = buildFallbackWritingOutlineComponent(scope.messageText || "", existingOutline);
    const writingInput = shouldIncludeWritingInputComponent(scope.messageText || "")
      ? buildWritingInputShortAnswerComponent(scope.messageText || "", next.find((component) => component?.type === "short_answer"))
      : null;
    return [
      ...next.filter((component) => !["summary", "writing_outline", "short_answer", "register", "rubric", "examples", "question_preview", "feedback", "progress", "audio"].includes(component?.type)),
      summary,
      outline,
      writingInput
    ].filter(Boolean);
  }
  if (
    shouldIncludeWritingInputComponent(scope.messageText || "") &&
    scope.allowed instanceof Set &&
    scope.allowed.has("writing_outline") &&
    scope.allowed.has("short_answer")
  ) {
    const shortAnswer = buildWritingInputShortAnswerComponent(
      scope.messageText || "",
      next.find((component) => component?.type === "short_answer")
    );
    return [
      ...next.filter((component) => component?.type !== "short_answer"),
      shortAnswer
    ];
  }
  if (scope.fuzzySpeaking && !(scope.requestedCounts?.speaking_prompt && scope.requestedCounts.speaking_prompt > 1) && scope.allowed instanceof Set && scope.allowed.has("speaking_prompt")) {
    const speakingPrompt = buildFallbackSpeakingPromptComponent(
      scope.messageText || "",
      next.find((component) => component?.type === "speaking_prompt")
    );
    return [
      ...next.filter((component) => !["speaking_prompt", "rubric", "correction", "examples", "question_preview", "feedback", "progress", "audio"].includes(component?.type)),
      speakingPrompt
    ];
  }
  if (scope.fuzzyScenario && !scope.dialogueQuestionSet && scope.allowed instanceof Set && scope.allowed.has("scenario") && !shouldConstrainHotelCheckInScenario(scope.messageText || "")) {
    const scenario = enforceRequestedScenarioComponentCount(
      next.find((component) => component?.type === "scenario") || { type: "scenario", title: "Scenario", items: [] },
      scope.requestedCounts?.scenario || scope.scenarioConstraint?.promptCount || inferScenarioPromptCount(scope.messageText || "") || 0,
      scope
    );
    return [
      ...next.filter((component) => !["scenario", "register", "examples", "vocabulary", "grammar", "audio", "transcript"].includes(component?.type)),
      scenario
    ];
  }
  if (scope.singleReadingChoice && scope.allowed instanceof Set && scope.allowed.has("cloze")) {
    const existingSummary = next.find((component) => component?.type === "summary");
    const summary = isUsableReadingPassageText(existingSummary?.text || "", scope.messageText || "")
      ? existingSummary
      : buildFallbackReadingPassageComponent(scope.messageText || "");
    const cloze = buildFallbackSingleReadingChoiceComponent(scope.messageText || "");
    return [
      ...next.filter((component) => !["summary", "question_set", "cloze", "vocabulary", "grammar", "examples"].includes(component?.type)),
      summary,
      cloze
    ];
  }
  if (scope.choiceConstraint && !scope.multiQuestionChoice) {
    const cloze = enforceExplicitChoiceComponentScope(
      next.find((component) => component?.type === "cloze") || { type: "cloze" },
      scope
    );
    return [
      ...next.filter((component) => component?.type !== "question_set" && component?.type !== "cloze"),
      cloze
    ];
  }
  if (scope.clozeConstraint?.strict && scope.allowed instanceof Set && scope.allowed.has("cloze")) {
    const cloze = enforceExplicitClozeComponentScope(
      next.find((component) => component?.type === "cloze") || { type: "cloze" },
      scope
    );
    return [
      ...next.filter((component) => component?.type !== "question_set" && component?.type !== "cloze"),
      cloze
    ];
  }
  if (isShortAnswerRequiredScope(scope)) {
    const shortAnswer = buildRequiredShortAnswerComponent(
      next.find((component) => component?.type === "short_answer") || {},
      scope
    );
    return [
      ...next.filter((component) => !["question_set", "cloze", "short_answer", "sentence_builder", "grammar"].includes(component?.type)),
      shortAnswer
    ];
  }
  if (isSentenceBuilderRequiredScope(scope)) {
    const sentenceBuilder = buildRequiredSentenceBuilderComponent(
      next.find((component) => component?.type === "sentence_builder") || {},
      scope
    );
    return [
      ...next.filter((component) => component?.type !== "question_set" && component?.type !== "cloze" && component?.type !== "sentence_builder" && component?.type !== "grammar"),
      sentenceBuilder
    ];
  }
  if (isStaticMinimalPairRequiredScope(scope)) {
    const minimalPair = buildRequiredMinimalPairComponent(
      next.find((component) => component?.type === "minimal_pair") || {},
      scope
    );
    return [
      ...next.filter((component) => !["question_set", "cloze", "sentence_builder", "grammar", "pronunciation", "compare", "audio", "examples", "minimal_pair"].includes(component?.type)),
      minimalPair
    ];
  }
  if (isOrderingRequiredScope(scope)) {
    const ordering = buildRequiredOrderingComponent(
      next.find((component) => component?.type === "ordering") || {},
      scope
    );
    return [
      ...next.filter((component) => !["summary", "question_set", "cloze", "sentence_builder", "grammar", "ordering"].includes(component?.type)),
      ordering
    ];
  }
  if (isRegisterRequiredScope(scope)) {
    const register = enforceRegisterComponentCount(
      next.find((component) => component?.type === "register") || { type: "register", title: "Register", items: [] },
      scope
    );
    return [
      next.find((component) => component?.type === "header") || { type: "header" },
      register
    ];
  }
  if (scope.multiFillBlankQuestions && scope.allowed instanceof Set && scope.allowed.has("question_set")) {
    const questionSet = enforceRequestedAgentCardComponentCount(
      next.find((component) => component?.type === "question_set") || buildFallbackQuestionSetComponent(scope.messageText || ""),
      scope
    );
    return [
      ...next.filter((component) => component?.type !== "question_set" && component?.type !== "cloze" && component?.type !== "grammar"),
      questionSet
    ];
  }
  if (
    scope.allowed instanceof Set &&
    scope.allowed.has("transcript") &&
    !scope.allowed.has("sentence_transcript") &&
    !next.some((component) => component?.type === "transcript")
  ) {
    next.push(defaultAgentCardCoreComponent("transcript"));
  }
  if (scope.multiQuestionChoice) {
    const expectedCount = (scope.requestedCounts && scope.requestedCounts.question_set) ||
      inferAgentRequestedQuestionCount(scope.messageText || "");
    const expectedOptionCount = inferExpectedAgentQuestionOptionCount(scope.messageText || "");
    const questionSetIndex = next.findIndex((component) => component?.type === "question_set");
    const questionCount = questionSetIndex >= 0 && Array.isArray(next[questionSetIndex].questions)
      ? next[questionSetIndex].questions.length
      : 0;
    const wrongOptionCount = expectedOptionCount && questionSetIndex >= 0 &&
      Array.isArray(next[questionSetIndex].questions) &&
      next[questionSetIndex].questions.some((question) => !Array.isArray(question?.options) || question.options.length !== expectedOptionCount);
    const wrongMinimalPairQuestionFocus = questionSetIndex >= 0 &&
      isMinimalPairQuestionSetRequest(scope.messageText || "") &&
      Array.isArray(next[questionSetIndex].questions) &&
      next[questionSetIndex].questions.some(agentMinimalPairQuestionLooksSemantic);
    const needsQuestionSet = questionSetIndex < 0 ||
      questionCount < 1 ||
      (expectedCount && questionCount !== expectedCount) ||
      wrongOptionCount ||
      wrongMinimalPairQuestionFocus;
    if (needsQuestionSet) {
      const fallback = buildFallbackQuestionSetComponent(scope.messageText || "");
      if (questionSetIndex >= 0) next[questionSetIndex] = fallback;
      else next.push(fallback);
    }
  }
  if (scope.dialogueQuestionSet && scope.allowed instanceof Set && scope.allowed.has("scenario")) {
    const scenarioIndex = next.findIndex((component) => component?.type === "scenario");
    const participantCount = scope.scenarioConstraint?.participantCount ||
      inferRequestedDialogueParticipantCount(scope.messageText || "") ||
      2;
    const promptCount = Math.max(
      participantCount,
      scope.requestedCounts?.scenario || 0,
      scope.scenarioConstraint?.promptCount || 0
    );
    const fallbackScenario = enforceScenarioParticipantCountScope(
      enforceRequestedScenarioComponentCount(
        { type: "scenario", title: "Scenario", items: [] },
        promptCount,
        scope
      ),
      scope
    );
    if (scenarioIndex >= 0) {
      const currentScenario = enforceScenarioParticipantCountScope(next[scenarioIndex], scope);
      const currentCount = Array.isArray(currentScenario.items) ? currentScenario.items.length : 0;
      next[scenarioIndex] = currentCount >= participantCount ? currentScenario : fallbackScenario;
    } else {
      next.push(fallbackScenario);
    }
  }
  if (
    scope.allowed instanceof Set &&
    scope.allowed.has("scenario") &&
    !next.some((component) => component?.type === "scenario")
  ) {
    next.push(enforceRequestedScenarioComponentCount({ type: "scenario", title: "Scenario", items: [] }, scope.requestedCounts?.scenario || 0, scope));
  }
  if (
    scope.allowed instanceof Set &&
    scope.allowed.has("cloze") &&
    scope.clozeConstraint?.strict &&
    !next.some((component) => component?.type === "cloze")
  ) {
    next.push(enforceExplicitClozeComponentScope({ type: "cloze" }, scope));
  }
  const fallbackMissingTypes = [
    "vocabulary",
    "phrase",
    "listening_cue",
    "minimal_pair",
    "word_family",
    "register",
    "grammar",
    "cloze",
    "ordering",
    "translation",
    "compare",
    "pronunciation",
    "correction"
  ];
  fallbackMissingTypes.forEach((type) => {
    if (!(scope.allowed instanceof Set) || !scope.allowed.has(type)) return;
    if (scope.excluded instanceof Set && scope.excluded.has(type)) return;
    if (next.some((component) => component?.type === type)) return;
    next.push(buildFallbackAgentCardComponentByType(type, scope));
  });
  return scope.suppressAudioCueText
    ? next.map((component) => enforceNoAudioComponentCueScope(component, scope))
    : next;
}

function buildFallbackAgentCardComponentByType(type = "", scope = {}) {
  const message = scope?.messageText || "";
  const requestedCount = scope?.requestedCounts?.[type] || 0;
  if (type === "vocabulary") {
    return enforceRequestedVocabularyCount({ type, title: "Vocabulary", items: [] }, requestedCount || 3, scope);
  }
  if (type === "phrase") {
    return enforceRequestedListComponentCount({ type, title: "Phrases", items: [] }, requestedCount || 3, scope);
  }
  if (type === "listening_cue") {
    return enforceRequestedListComponentCount({ type, title: "Listening Cues", items: [] }, requestedCount || 3, scope);
  }
  if (type === "minimal_pair") {
    return enforceRequestedMinimalPairCount({ type, title: "Minimal Pairs", pairs: [] }, requestedCount || inferExplicitMinimalPairs(message).length || 2, scope);
  }
  if (type === "word_family") {
    return enforceRequestedWordFamilyTokenCount({ type, title: "Word Family", tokens: [] }, requestedCount || 4, scope);
  }
  if (type === "register") {
    return enforceRegisterComponentCount({ type, title: "Register", items: [] }, {
      ...scope,
      requestedCounts: { ...(scope.requestedCounts || {}), register: requestedCount || inferAgentRequestedRegisterCount(message) || 2 }
    });
  }
  if (type === "grammar") {
    return {
      type,
      title: "Grammar",
      text: grammarFallbackText(message),
      items: []
    };
  }
  if (type === "cloze") {
    return buildFallbackClozeComponent(message, requestedCount, scope);
  }
  if (type === "ordering") {
    return buildFallbackOrderingComponent(message, scope);
  }
  if (type === "translation") {
    return enforceRequestedTranslationComponentCount({ type, title: "Translation", items: [] }, requestedCount || 1, scope);
  }
  if (type === "compare") {
    return enforceRequestedListComponentCount({ type, title: "Compare", items: [] }, requestedCount || 2, scope);
  }
  if (type === "pronunciation") {
    return enforceRequestedPronunciationComponentCount({ type, title: "Pronunciation", items: [] }, requestedCount || 2, scope);
  }
  if (type === "correction") {
    return enforceRequestedListComponentCount({
      type,
      title: "Correction",
      items: correctionFallbackItems(message)
    }, requestedCount || inferAgentRequestedCorrectionCount(message) || 2, scope);
  }
  return { type, title: agentCardComponentLabel({ type }) };
}

function grammarFallbackText(message = "") {
  const text = String(message || "").toLowerCase();
  if (/present\s*perfect|现在完成/.test(text)) return "Present perfect uses have or has plus the past participle.";
  if (/tense|时态/.test(text)) return "Choose the verb tense that matches the time signal and meaning.";
  if (/article|冠词/.test(text)) return "Choose a, an, the, or no article according to the noun and context.";
  if (/preposition|介词/.test(text)) return "Choose the preposition that matches the time, place, or verb pattern.";
  return "Focus on the target grammar rule and keep the answer concise.";
}

function buildFallbackClozeComponent(message = "", requestedCount = 0, scope = {}) {
  const inferredCount = requestedCount ||
    (scope.requestedCounts && scope.requestedCounts.cloze) ||
    inferRequestedAgentCardItemCounts(message).cloze ||
    inferAgentRequestedClozeBlankCount(message) ||
    1;
  const safeCount = Math.max(1, Math.min(Number(inferredCount) || 1, 6));
  return enforceRequestedClozeBlankCount({
    type: "cloze",
    title: "Cloze Practice",
    text: fallbackGenericClozeText(safeCount, message),
    options: fallbackGenericClozeOptions(message),
    answer: fallbackGenericClozeAnswers(safeCount, message).join(" | "),
    explanation: ""
  }, safeCount, {
    ...scope,
    messageText: scope.messageText || message,
    requestedCounts: { ...(scope.requestedCounts || {}), cloze: safeCount }
  });
}

function fallbackGenericClozeAnswers(requestedCount = 1, message = "") {
  const text = String(message || "").toLowerCase();
  if (/article|冠词|a\/an\/the/.test(text)) return ["a", "the", "an", "the", "a", "the"].slice(0, requestedCount);
  if (/preposition|介词|in\/on\/at/.test(text)) return ["in", "on", "at", "in", "on", "at"].slice(0, requestedCount);
  if (/tense|时态/.test(text)) return ["went", "is studying", "has finished", "will visit", "did"].slice(0, requestedCount);
  if (/完形|cloze|blank|填空/.test(text)) return ["morning", "bus", "late", "school", "teacher", "home"].slice(0, requestedCount);
  return ["have", "go", "make", "take", "do", "get"].slice(0, requestedCount);
}

function fallbackGenericClozeOptions(message = "") {
  const text = String(message || "").toLowerCase();
  if (/article|冠词|a\/an\/the/.test(text)) return ["a", "an", "the", "Ø"];
  if (/preposition|介词|in\/on\/at/.test(text)) return ["in", "on", "at", "by", "for", "with"];
  if (/tense|时态/.test(text)) return ["go", "went", "is studying", "has finished", "will visit", "did"];
  if (/完形|cloze|blank|填空/.test(text)) return ["morning", "bus", "late", "school", "teacher", "home", "quickly", "because"];
  return ["have", "has", "go", "went", "make", "take", "do", "get"];
}

function fallbackGenericClozeText(requestedCount = 1, message = "") {
  const lower = String(message || "").toLowerCase();
  const answers = fallbackGenericClozeAnswers(requestedCount, message);
  if (/preposition|介词|in\/on\/at/.test(lower)) {
    return "She arrived ___ the station at eight.";
  }
  if (/article|冠词|a\/an\/the/.test(lower)) {
    return "I saw ___ old man near the station. He was carrying ___ umbrella.";
  }
  if (/tense|时态/.test(lower)) {
    return "Yesterday, she ___ to school early. Now she ___ for a test.";
  }
  if (answers.length >= 3 && /完形|cloze|blank|填空/.test(lower)) {
    return "Every ___, I take the ___ to school. Today I was ___, so I walked faster.";
  }
  return answers.map((_, index) => `Blank ${index + 1}: ___`).join(" ");
}

function isUsableWritingSummary(component = {}) {
  if (!component || component.type !== "summary") return false;
  const text = sanitizeAgentCardText(component.text || component.value || "");
  if (text.length < 12) return false;
  if (/\b(?:continue|send me|ask me|question set|practice card)\b|继续把|继续发|题组/i.test(text)) return false;
  return /writing|essay|composition|email|作文|写作|提纲|题目|topic|prompt/i.test(`${component.title || ""} ${text}`);
}

function buildFallbackWritingSummaryComponent(message = "") {
  const topic = inferWritingTopicText(message);
  return {
    type: "summary",
    title: "写作任务",
    text: topic
  };
}

function buildFallbackWritingOutlineComponent(message = "", existing = {}) {
  const requestedCount = inferRequestedAgentCardItemCounts(message).writing_outline ||
    inferWritingOutlineStepCount(message) ||
    3;
  const safeCount = Math.max(1, Math.min(requestedCount || 3, 6));
  const topic = inferWritingOutlineTopic(message);
  const existingSteps = Array.isArray(existing?.steps)
    ? existing.steps
        .map((step) => ({
          label: compactAgentCardText(step?.label || "", 48),
          text: compactAgentCardText(step?.text || "", 180)
        }))
        .filter((step) => step.label || step.text)
    : [];
  const fallbackSteps = topic?.steps?.length
    ? topic.steps
    : agentCardWritingOutlineFallbackSteps(message);
  const orderedSteps = topic && !writingOutlineStepsMatchTopic(existingSteps, topic)
    ? [...fallbackSteps, ...existingSteps]
    : [...existingSteps, ...fallbackSteps];
  const steps = uniqueWritingOutlineSteps(orderedSteps, safeCount);
  return {
    ...(existing || {}),
    type: "writing_outline",
    title: "Writing Outline",
    text: "",
    items: [],
    steps
  };
}

function shouldIncludeWritingInputComponent(message = "") {
  const text = String(message || "");
  if (/只要.{0,8}(?:提纲|outline)|only\s+(?:(?:the|writing|essay)\s+)?outline|outline.{0,24}only|不要.{0,8}(?:输入|写作框|作文框)|不需要.{0,8}(?:输入|写作框|作文框)|no\s+(?:input|writing\s+box|draft\s+box)|without\s+(?:input|writing\s+box|draft\s+box)/i.test(text)) {
    return false;
  }
  return /写作|提纲|作文|essay|writing|outline|写作框|输入作文|直接输入作文|写作文|作文输入|writing\s+box|write\s+box|text\s+box|input\s+(?:box|essay|draft)|draft\s+box/i.test(text);
}

function uniqueWritingOutlineSteps(steps = [], requestedCount = 3) {
  const seen = new Set();
  const clean = steps
    .map((step, index) => ({
      label: compactAgentCardText(step?.label || `Step ${index + 1}`, 48),
      text: compactAgentCardText(step?.text || "", 180)
    }))
    .filter((step) => step.text)
    .filter((step) => {
      const key = normalizeAgentAnswerLookupText(`${step.label} ${step.text}`);
      if (!key || seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  const defaults = agentCardWritingOutlineFallbackSteps("")
    .filter((step) => !seen.has(normalizeAgentAnswerLookupText(`${step.label} ${step.text}`)));
  return [...clean, ...defaults].slice(0, requestedCount);
}

function inferWritingOutlineStepCount(message = "") {
  const text = String(message || "").toLowerCase();
  const numberToken = "([0-9]{1,2}|[一二两兩俩倆三四五六七八九十]{1,3}|one|two|three|four|five|six)";
  const patterns = [
    new RegExp(`${numberToken}\\s*(?:个|個|条|條)?\\s*(?:points?|steps?|提纲|步骤|点|结构)`, "i"),
    new RegExp(`(?:points?|steps?|提纲|步骤|点|结构)\\D{0,8}${numberToken}`, "i")
  ];
  return firstCountMatch(text, patterns, 1, 6);
}

function inferWritingTopicText(message = "") {
  const raw = sanitizeAgentCardText(message);
  const lower = raw.toLowerCase();
  const task = inferWritingTaskDetails(raw);
  if (task) {
    const details = task.requirements.length
      ? ` Include ${formatEnglishList(task.requirements)}.`
      : "";
    return `Writing task: Write a ${task.wordRange ? `${task.wordRange} ` : ""}${task.genre} ${task.recipient ? `to ${task.recipient} ` : ""}${task.topic}.`.replace(/\s+\./g, ".") + details;
  }
  if (inferTeacherAbsenceEmailWritingTopic(raw)) {
    const wordRange = /(\d{2,3})\s*[-â€“—]\s*(\d{2,3})\s*words?/i.exec(raw) ||
      /(\d{2,3})\s*-\s*(\d{2,3})\s*word/i.exec(raw);
    const range = wordRange ? `${wordRange[1]}-${wordRange[2]} word ` : "";
    return `Writing task: Write a ${range}email to your teacher explaining why you missed class yesterday and asking for the homework.`;
  }
  if (/online\s+shopping|网购/.test(lower)) {
    return "作文题目：Online shopping。请围绕网购的便利、选择和风险控制组织写作。";
  }
  const quoted = /["“'‘](.{2,120})["”'’]/.exec(raw);
  if (quoted?.[1]) return `作文题目：${compactAgentCardText(quoted[1], 120)}`;
  const topicMatch = /(?:主题|topic|about|关于)\s*[:：]?\s*([^，。,.!?；;]{2,80})/i.exec(raw);
  if (topicMatch?.[1]) return `作文题目：${compactAgentCardText(topicMatch[1], 100)}`;
  return /email|邮件/i.test(raw)
    ? "写作任务：写一封结构清楚、语气合适的英文邮件。"
    : "写作任务：根据题目写一段结构清楚的英文短文。";
}

function formatEnglishList(items = []) {
  if (items.length <= 1) return items[0] || "";
  if (items.length === 2) return `${items[0]} and ${items[1]}`;
  return `${items.slice(0, -1).join(", ")}, and ${items[items.length - 1]}`;
}

function inferExpectedAgentQuestionOptionCount(text = "") {
  const explicit = inferAgentRequestedOptionCount(text);
  if (explicit) return explicit;
  const value = String(text || "").toLowerCase();
  return /multiple\s*choice|mcq|choice\s+questions?|quiz|minimal\s+pair.*(?:questions?|discrimination)|discrimination\s+questions?|选择题|题组/.test(value)
    ? 4
    : 0;
}

function enforceExplicitClozeComponentScope(component = {}, scope = {}) {
  const constraint = scope.clozeConstraint || {};
  const answerParts = Array.isArray(constraint.answerParts)
    ? constraint.answerParts.map((item) => compactAgentCardText(item, 80)).filter(Boolean)
    : [];
  const requestedCount = Math.max(
    answerParts.length,
    countAgentCardClozeBlanks(constraint.sourceSentence || component.text || ""),
    scope.requestedCounts?.cloze || 0,
    1
  );
  const options = normalizeScopedAgentCardClozeOptions(component.options || [], answerParts, {
    ...scope,
    requestedCounts: {
      ...(scope.requestedCounts || {}),
      cloze: requestedCount
    }
  });
  const componentText = String(component.text || "");
  const sourceText = countAgentCardClozeBlanks(componentText) > 0
    ? componentText
    : (constraint.sourceSentence || componentText);
  const text = sourceText
    ? compactAgentCardText(sourceText, agentCardComponentTextMaxLength("cloze"))
    : fallbackRequestedClozeText(answerParts, requestedCount, scope);
  const answer = answerParts.length
    ? answerParts.slice(0, requestedCount).join(" | ")
    : compactAgentCardText(component.answer || component.correctAnswer || options[0] || "", 120);
  const scoped = {
    ...component,
    type: "cloze",
    title: component.title || "Cloze Practice",
    text: text || "Choose the best option for the blank.",
    items: [],
    questions: [],
    options,
    answer,
    explanation: component.explanation || ""
  };
  delete scoped.examples;
  if (shouldSuppressAgentCardAnswerExplanation(scoped, scope)) delete scoped.explanation;
  return enforceRequestedClozeBlankCount(scoped, requestedCount, {
    ...scope,
    requestedCounts: {
      ...(scope.requestedCounts || {}),
      cloze: requestedCount
    }
  });
}

function compactAgentCardComponentForScope(component = {}, scope = {}) {
  if (!component || typeof component !== "object") return component;
  const constrainedComponent = enforceMinimalPairComponentScope(
    enforceSpeakingPromptComponentScope(
      enforceExplicitListeningCueComponentScope(component, scope),
      scope
    ),
    scope
  );
  const scopedComponent = shouldSuppressAgentCardAnswerExplanation(constrainedComponent, scope)
    ? omitAgentCardExplanation(constrainedComponent)
    : constrainedComponent;
  const dedupedTypedComponent = removeRedundantAgentCardItemsForTypedPayload(scopedComponent);
  const translationScopedComponent = enforceExplicitTranslationComponentScope(dedupedTypedComponent, scope);
  const noInlineExamplesComponent = stripAgentCardInlineExamplesForScope(translationScopedComponent, scope);
  const noDanglingEllipsisComponent = repairAgentCardDanglingEllipsisForScope(noInlineExamplesComponent, scope);
  const expressionOnlyComponent = enforceExpressionOnlyDetailScope(noDanglingEllipsisComponent, scope);
  const correctionCoreComponent = enforceCorrectionCoreOnlyScope(expressionOnlyComponent, scope);
  const currentShortAnswerComponent = enforceShortAnswerCurrentRequestScope(correctionCoreComponent, scope);
  const noAnswerLeakComponent = enforceShortAnswerNoAnswerLeakComponentScope(currentShortAnswerComponent);
  const noInstructionLeakComponent = enforceSentenceBuilderNoUsageTextScope(noAnswerLeakComponent);
  const noAudioCueComponent = enforceNoAudioComponentCueScope(
    enforceNoAudioShortAnswerComponentScope(noInstructionLeakComponent, scope),
    scope
  );
  const compactQuestionComponent = stripQuestionSetExplanationsForScope(noAudioCueComponent, scope);
  const countAdjustedComponent = enforceScenarioQuestionSetScope(
    enforceRequestedAgentCardComponentCount(compactQuestionComponent, scope),
    scope
  );
  const scenarioParticipantComponent = enforceScenarioParticipantCountScope(countAdjustedComponent, scope);
  const registerCountComponent = enforceRegisterComponentCount(scenarioParticipantComponent, scope);
  if (!scope.suppressExtraSuggestions) return cleanAgentCardComponentDanglingPunctuation(registerCountComponent);
  const limitByType = {
    compare: 2,
    translation: 2,
    examples: 2,
    vocabulary: 3,
    phrase: 3,
    grammar: 2,
    pronunciation: 2,
    listening_cue: 3,
    minimal_pair: 2,
    word_family: 6,
    scenario: 2,
    register: 2,
    correction: 2,
    mistake_pattern: 2,
    writing_outline: 3
  };
  const requestedLimit = (scope.requestedCounts && scope.requestedCounts[registerCountComponent.type]) ||
    (registerCountComponent.type === "register" ? inferAgentRequestedRegisterCount(scope?.messageText || "") : null);
  const participantLimit = registerCountComponent.type === "scenario"
    ? Number(scope?.scenarioConstraint?.participantCount || inferRequestedDialogueParticipantCount(scope?.messageText || "") || 0)
    : 0;
  const limit = Math.max(requestedLimit || limitByType[registerCountComponent.type] || 0, participantLimit || 0);
  if (!limit) return registerCountComponent;
  return {
    ...registerCountComponent,
    items: Array.isArray(registerCountComponent.items) ? registerCountComponent.items.slice(0, limit).map(cleanAgentCardDanglingPunctuation) : registerCountComponent.items,
    pairs: Array.isArray(registerCountComponent.pairs) ? registerCountComponent.pairs.slice(0, limit) : registerCountComponent.pairs,
    tokens: Array.isArray(registerCountComponent.tokens) ? registerCountComponent.tokens.slice(0, limit) : registerCountComponent.tokens,
    steps: Array.isArray(registerCountComponent.steps) ? registerCountComponent.steps.slice(0, limit) : registerCountComponent.steps,
    examples: Array.isArray(registerCountComponent.examples) ? registerCountComponent.examples.slice(0, limit) : registerCountComponent.examples
  };
}

function cleanAgentCardComponentDanglingPunctuation(component = {}) {
  if (!component || typeof component !== "object") return component;
  const cleanItem = component.type === "register" ? cleanAgentCardRegisterItem : cleanAgentCardDanglingPunctuation;
  return {
    ...component,
    items: Array.isArray(component.items) ? component.items.map(cleanItem).filter(Boolean) : component.items
  };
}

function enforceRegisterComponentCount(component = {}, scope = {}) {
  if (component?.type !== "register") return component;
  const requestedCount = scope?.requestedCounts?.register || inferAgentRequestedRegisterCount(scope?.messageText || "");
  if (!requestedCount || requestedCount <= 0) return component;
  const currentItems = Array.isArray(component.items) ? component.items.map(cleanAgentCardRegisterItem).filter(Boolean) : [];
  if (currentItems.length >= requestedCount) return { ...component, items: currentItems.slice(0, requestedCount) };
  const context = `${scope?.messageText || ""} ${component.title || ""} ${component.text || ""} ${currentItems.join(" ")}`.toLowerCase();
  const fallbackItems = registerFallbackItemsForRequest(scope?.messageText || "", currentItems)
    .concat(agentCardListFallbackItems("register", context));
  return {
    ...component,
    items: uniqueAgentCardTexts([...currentItems, ...fallbackItems], 220).slice(0, requestedCount)
  };
}

function cleanAgentCardRegisterItem(value = "") {
  const text = cleanAgentCardDanglingPunctuation(value);
  if (!text) return "";
  return text
    .replace(/\bat your earliest$/i, "at your earliest convenience.")
    .replace(/\bat your convenience$/i, "at your convenience.")
    .trim();
}

function isStrictRegisterOnlyAgentCardRequest(message = "") {
  const text = String(message || "");
  const registerCue = /register|casual\s*(?:->|to)\s*formal|formal\s+(?:english|version|tone)|tone\s*(?:shift|conversion)|语气|正式|礼貌|改写|转换/i.test(text);
  if (!registerCue) return false;
  return /only\s+register|only\s+(?:casual\s*(?:->|to)\s*formal|formal\s+(?:versions?|conversions?))|只要.{0,24}(?:casual\s*(?:->|to)\s*formal|转换|改写|正式|礼貌)|不要.{0,16}(?:场景|对话|例句|语法|评分|题组|题目)|不需要.{0,16}(?:场景|对话|例句|语法|评分|题组|题目)|no\s+(?:scenario|dialogue|examples?|grammar|rubric|questions?|question\s*set)|without\s+(?:scenario|dialogue|examples?|grammar|rubric|questions?|question\s*set)/i.test(text);
}

function isRegisterRequiredScope(scope = {}) {
  return scope.allowed instanceof Set &&
    scope.allowed.has("register") &&
    !scope.multiQuestionChoice &&
    isStrictRegisterOnlyAgentCardRequest(scope.messageText || "");
}

function registerFallbackItemsForRequest(message = "", existingItems = []) {
  const existingCasual = new Set((existingItems || []).map(registerCasualLookupFromItem).filter(Boolean));
  return extractRegisterCasualSentences(message)
    .filter((sentence) => !existingCasual.has(registerCasualLookup(sentence)))
    .map((sentence) => `casual: ${sentence} | formal: ${formalRegisterRewrite(sentence)}`)
    .filter(Boolean);
}

function extractRegisterCasualSentences(message = "") {
  const text = String(message || "").replace(/\s+/g, " ").trim();
  const source = /\u628a\s*(.+?)\s*\u6539\u6210/i.exec(text)?.[1] ||
    /(?:convert|rewrite|change)\s+(.+?)\s+(?:into|to)\s+(?:formal|polite)/i.exec(text)?.[1] ||
    /\u628a\s*(.+?)\s*(?:\u53ea\u8981|\u4e0d\u8981|\u4e0d\u9700\u8981|\u65e0|$)/i.exec(text)?.[1] ||
    "";
  const candidate = (source || text)
    .replace(/^(?:B1|A2|A1|C1|C2)\s*/i, "")
    .replace(/^(?:\u90ae\u4ef6)?\u8bed\u6c14\u8f6c\u6362[:：]?\s*/i, "");
  return uniqueAgentCardTexts(
    [
      ...candidate
      .split(/\s*(?:\u548c|\u4ee5\u53ca|,|\uFF0C|;|\uFF1B|\||\/)\s*/i)
      .map((item) => compactAgentCardText(item, 140).replace(/^把\s*/, "").trim())
      .filter((item) => /\b(?:can|could|i|we|you|send|need|want|tell|let|fix|check)\b/i.test(item))
      .map((item) => item.replace(/\s*(?:改成|更正式|正式|礼貌).*/i, "").trim())
      .filter((item) => item.split(/\s+/).length >= 4),
      ...Array.from(text.matchAll(/\b(?:Can you [^?.!]+[?.!]?|I need [^?.!]+[?.!]?|Tell me [^?.!]+[?.!]?|Let me know [^?.!]+[?.!]?)\b/gi))
        .map((match) => compactAgentCardText(match[0], 140).trim())
        .filter((item) => item.split(/\s+/).length >= 4)
    ],
    140
  ).slice(0, 6);
}

function registerCasualLookupFromItem(item = "") {
  const match = /casual\s*[:：]\s*(.+?)(?:\s*\|\s*formal\s*[:：]|$)/i.exec(String(item || ""));
  return match ? registerCasualLookup(match[1]) : "";
}

function registerCasualLookup(value = "") {
  return normalizeAgentAnswerLookupText(value).replace(/[?!.]+$/g, "");
}

function formalRegisterRewrite(sentence = "") {
  const text = compactAgentCardText(sentence, 140).replace(/[?!.]+$/g, "").trim();
  const lower = text.toLowerCase();
  if (/^can you send me (.+)$/i.test(text)) {
    return text.replace(/^can you send me (.+)$/i, "Could you please send me $1?").replace(/\?+$/g, "?");
  }
  if (/^i need your feedback now$/i.test(text)) {
    return "I would appreciate your feedback at your earliest convenience.";
  }
  if (/^i need (.+) now$/i.test(text)) {
    return text.replace(/^i need (.+) now$/i, "I would appreciate it if you could provide $1 at your earliest convenience.");
  }
  if (/^tell me (.+)$/i.test(text)) {
    return text.replace(/^tell me (.+)$/i, "Please let me know $1.");
  }
  if (/^can you (.+)$/i.test(text)) {
    return text.replace(/^can you (.+)$/i, "Could you please $1?");
  }
  if (lower.endsWith("?")) return `Could you please ${text.replace(/\?+$/g, "")}?`;
  return `I would appreciate it if you could ${text.charAt(0).toLowerCase()}${text.slice(1)}.`;
}

function inferAgentRequestedRegisterCount(message = "") {
  const text = String(message || "").toLowerCase();
  const numberToken = "([0-9]{1,2}|[一二两兩俩倆三四五六七八九十]{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)";
  const patterns = [
    new RegExp(`${numberToken}\\s+(?:casual\\s+)?(?:workplace\\s+)?requests?\\s+into\\s+formal`, "i"),
    new RegExp(`${numberToken}\\s+(?:casual\\s+to\\s+formal\\s+)?(?:[a-z0-9+-]+\\s+){0,6}(?:register\\s*)?(?:pairs?|conversions?|versions?)\\b`, "i")
  ];
  return firstCountMatch(text, patterns, 1, 12);
}

function removeRedundantAgentCardItemsForTypedPayload(component = {}) {
  if (component?.type === "rubric" && Array.isArray(component.criteria) && component.criteria.length > 0) {
    return {
      ...component,
      items: []
    };
  }
  if (!component || typeof component !== "object" || !Array.isArray(component.items) || !component.items.length) {
    return component;
  }
  const typedTypes = new Set([
    "minimal_pair",
    "word_family",
    "writing_outline",
    "rubric"
  ]);
  if (!typedTypes.has(component.type)) return component;
  const hasTypedPayload =
    (Array.isArray(component.pairs) && component.pairs.length > 0) ||
    (Array.isArray(component.tokens) && component.tokens.length > 0) ||
    (Array.isArray(component.steps) && component.steps.length > 0) ||
    (Array.isArray(component.criteria) && component.criteria.length > 0);
  if (!hasTypedPayload) return component;
  return {
    ...component,
    items: []
  };
}

function enforceNoAudioComponentCueScope(component = {}, scope = {}) {
  if (!scope?.suppressAudioCueText || !component || typeof component !== "object") return component;
  return {
    ...component,
    title: neutralizeNoAudioCueText(component.title || "", component.title || ""),
    text: neutralizeNoAudioCueText(component.text || "", ""),
    items: Array.isArray(component.items)
      ? component.items.map((item) => neutralizeNoAudioCueText(item, "")).filter(Boolean)
      : component.items,
    pairs: Array.isArray(component.pairs)
      ? component.pairs.map((pair) => ({
          ...pair,
          left: neutralizeNoAudioCueText(pair?.left || "", pair?.left || ""),
          right: neutralizeNoAudioCueText(pair?.right || "", pair?.right || ""),
          hint: neutralizeNoAudioCueText(pair?.hint || "", "")
        })).filter((pair) => pair.left || pair.right || pair.hint)
      : component.pairs,
    questions: Array.isArray(component.questions)
      ? component.questions.map((question) => ({
          ...question,
          questionText: neutralizeNoAudioQuestionText(question?.questionText || question?.question || ""),
          question: neutralizeNoAudioQuestionText(question?.question || question?.questionText || ""),
          explanation: neutralizeNoAudioCueText(question?.explanation || "", "")
        })).filter((question) => question.questionText || question.question)
      : component.questions,
    explanation: neutralizeNoAudioCueText(component.explanation || "", component.explanation || "")
  };
}

function neutralizeNoAudioQuestionText(value = "") {
  const original = String(value || "").trim();
  if (!original) return "";
  const target = /\/[^/\n]{1,40}\//.exec(original)?.[0] || "";
  if (/\bwhich\s+word\s+(?:do\s+)?you\s+hear\b|\byou\s+hear\b/i.test(original)) {
    return target ? `Which word matches ${target}?` : "Which word matches the target?";
  }
  return neutralizeNoAudioCueText(original, "Choose the best answer.");
}

function enforceRequestedAgentCardComponentCount(component = {}, scope = {}) {
  const requestedCount = scope.requestedCounts && scope.requestedCounts[component.type];
  if (!requestedCount || requestedCount <= 0) return component;
  if (component.type === "cloze") {
    return enforceRequestedClozeBlankCount(component, requestedCount, scope);
  }
  if (component.type === "vocabulary") {
    return enforceRequestedVocabularyCount(component, requestedCount, scope);
  }
  if (component.type === "word_family") {
    return enforceRequestedWordFamilyTokenCount(component, requestedCount, scope);
  }
  if (component.type === "speaking_prompt") {
    return enforceRequestedSpeakingPromptCount(component, requestedCount, scope);
  }
  if (component.type === "writing_outline") {
    return enforceRequestedWritingOutlineStepCount(component, requestedCount, scope);
  }
  if (component.type === "minimal_pair") {
    return enforceRequestedMinimalPairCount(component, requestedCount, scope);
  }
  if (component.type === "register") {
    return enforceRegisterComponentCount(component, scope);
  }
  if (AGENT_CARD_COUNT_FILLABLE_TYPES.has(component.type)) {
    return enforceRequestedListComponentCount(component, requestedCount, scope);
  }
  return component;
}

function enforceRequestedWritingOutlineStepCount(component = {}, requestedCount = 0, scope = {}) {
  const currentSteps = Array.isArray(component.steps) ? component.steps : [];
  const usableSteps = currentSteps
    .map((step) => ({
      label: compactAgentCardText(step?.label || "", 48),
      text: compactAgentCardText(step?.text || "", 160)
    }))
    .filter((step) => step.label || step.text);
  if (usableSteps.length >= requestedCount) {
    return {
      ...component,
      items: [],
      steps: usableSteps.slice(0, requestedCount)
    };
  }
  const context = [
    scope.messageText,
    component.title,
    component.text,
    ...(Array.isArray(component.items) ? component.items : [])
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
  const fallbackSteps = agentCardWritingOutlineFallbackSteps(context);
  const merged = [...usableSteps, ...fallbackSteps].slice(0, requestedCount);
  if (merged.length !== requestedCount) return component;
  return {
    ...component,
    text: "",
    items: [],
    steps: merged
  };
}

function agentCardWritingOutlineFallbackSteps(context = "") {
  if (/email|booking|hotel|reservation|change|modify|酒店|预订|預訂|更改/.test(context)) {
    return [
      { label: "Request", text: "State that you want to change your hotel booking." },
      { label: "Details", text: "Give the booking dates, name, and the change you need." },
      { label: "Close", text: "Ask for confirmation and say thank you." }
    ];
  }
  if (/essay|opinion|argument|议论文|观点/.test(context)) {
    return [
      { label: "Claim", text: "State your main opinion clearly." },
      { label: "Support", text: "Give one reason and one relevant detail." },
      { label: "Close", text: "Restate the idea in a short final sentence." }
    ];
  }
  return [
    { label: "Opening", text: "Introduce the purpose clearly." },
    { label: "Body", text: "Add the key details in a logical order." },
    { label: "Closing", text: "End with a clear next step." }
  ];
}

const AGENT_CARD_COUNT_FILLABLE_TYPES = new Set([
  "phrase",
  "translation",
  "pronunciation",
  "listening_cue",
  "scenario",
  "register",
  "compare",
  "correction",
  "mistake_pattern"
]);

function enforceRequestedClozeBlankCount(component = {}, requestedCount = 0, scope = {}) {
  const text = cleanAgentClozeInstructionLeak(String(component.text || ""));
  if (!text || !requestedCount) return component;
  const explicitAnswerParts = Array.isArray(scope?.clozeConstraint?.answerParts)
    ? scope.clozeConstraint.answerParts.map((item) => compactAgentCardText(item, 80)).filter(Boolean)
    : [];
  const componentAnswerParts = splitAgentCardClozeAnswerParts(component.answer || component.correctAnswer || "", requestedCount);
  const answerParts = explicitAnswerParts.length >= requestedCount
    ? explicitAnswerParts.slice(0, requestedCount)
    : componentAnswerParts;
  if (!answerParts.length) return component;
  const options = normalizeScopedAgentCardClozeOptions(component.options, answerParts, scope);
  const sourceFallbackBeforeRepair = clozeTextFromSourceSentence(
    scope?.clozeConstraint?.sourceSentence || "",
    answerParts,
    requestedCount
  );
  if (sourceFallbackBeforeRepair && shouldPreferSourceSentenceClozeText(text)) {
    return {
      ...component,
      text: sourceFallbackBeforeRepair,
      answer: answerParts.slice(0, requestedCount).join(" | "),
      options
    };
  }
  const blankCountBeforeRepair = countAgentCardClozeBlanks(text);
  if (blankCountBeforeRepair === requestedCount) {
    const sourceFallback = isGenericAgentCardClozeBlankText(text)
      ? clozeTextFromSourceSentence(scope?.clozeConstraint?.sourceSentence || "", answerParts, requestedCount)
      : "";
    if (sourceFallback) {
      return {
        ...component,
        text: sourceFallback,
        answer: answerParts.slice(0, requestedCount).join(" | "),
        options
      };
    }
    if (!shouldReplaceExplicitAgentCardCloze(component, componentAnswerParts, answerParts, scope)) {
      if (
        text !== String(component.text || "") ||
        !sameAgentCardTextSet(component.options || [], options)
      ) {
        return {
          ...component,
          text,
          answer: answerParts.slice(0, requestedCount).join(" | "),
          options
        };
      }
      return component;
    }
    const fallbackText = fallbackRequestedClozeText(answerParts, requestedCount, scope);
    return {
      ...component,
      text: fallbackText || compactAgentCardText(text, agentCardComponentTextMaxLength("cloze")),
      answer: answerParts.slice(0, requestedCount).join(" | "),
      options
    };
  }
  let repairedText = text;
  let blankCount = blankCountBeforeRepair;
  for (const answer of answerParts) {
    if (blankCount >= requestedCount) break;
    const pattern = new RegExp(`\\b${escapeRegExp(answer).replace(/\\ /g, "\\s+")}\\b`, "i");
    if (!pattern.test(repairedText)) continue;
    repairedText = repairedText.replace(pattern, "___");
    blankCount = countAgentCardClozeBlanks(repairedText);
  }
  if (blankCount !== requestedCount) {
    const fallbackText = fallbackRequestedClozeText(answerParts, requestedCount, scope);
    if (!fallbackText) return component;
    return {
      ...component,
      text: fallbackText,
      answer: answerParts.slice(0, requestedCount).join(" | "),
      options
    };
  }
  return {
    ...component,
    text: compactAgentCardText(repairedText, agentCardComponentTextMaxLength("cloze")),
    answer: answerParts.slice(0, requestedCount).join(" | "),
    options
  };
}

function shouldPreferSourceSentenceClozeText(value = "") {
  const text = String(value || "").trim();
  if (!text) return false;
  return isGenericAgentCardClozeBlankText(text) || !/[A-Za-z]{2,}/.test(text.replace(/Blank\s*\d+/gi, ""));
}

function normalizeScopedAgentCardClozeOptions(options = [], answerParts = [], scope = {}) {
  const requestedOptionCount = inferAgentRequestedOptionCount(scope?.messageText || "") || 0;
  const cleanOptions = cleanAgentCardClozeOptionCandidates(options, answerParts, scope);
  if (scope?.clozeConstraint?.strict !== true) {
    return expandAgentCardClozeOptions(
      normalizeAgentCardClozeOptions(cleanOptions, answerParts),
      answerParts,
      requestedOptionCount,
      scope
    );
  }
  const explicitOptions = Array.isArray(scope?.clozeConstraint?.options)
    ? scope.clozeConstraint.options.map((item) => compactAgentCardText(item, 80)).filter(Boolean)
    : [];
  if (explicitOptions.length || answerParts.length) {
    const strictBase = explicitOptions.length
      ? [...explicitOptions, ...answerParts]
      : [...answerParts, ...cleanOptions];
    return expandAgentCardClozeOptions(
      uniqueAgentCardTexts(strictBase, 80),
      answerParts,
      requestedOptionCount,
      scope
    );
  }
  return expandAgentCardClozeOptions(
    normalizeAgentCardClozeOptions(cleanOptions, answerParts),
    answerParts,
    requestedOptionCount,
    scope
  );
}

function cleanAgentCardClozeOptionCandidates(options = [], answerParts = [], scope = {}) {
  const sourceText = String(scope?.clozeConstraint?.sourceSentence || "");
  const sourceTokens = new Set(
    sourceText
      .toLowerCase()
      .replace(/_{2,}/g, " ")
      .match(/[a-z][a-z']{1,}/g) || []
  );
  const answerLookup = new Set((answerParts || []).map((item) => normalizeAgentAnswerLookupText(item)));
  return (Array.isArray(options) ? options : [])
    .map((item) => compactAgentCardText(item, 80))
    .filter(Boolean)
    .filter((item) => {
      const normalized = normalizeAgentAnswerLookupText(item);
      if (answerLookup.has(normalized)) return true;
      if (/^(?:text|sentence|prompt|题干|文本)\s*[:：]/i.test(item)) return false;
      if (/_{2,}/.test(item)) return false;
      const allWords = item.toLowerCase().match(/[a-z][a-z']*/g) || [];
      const words = item.toLowerCase().match(/[a-z][a-z']{1,}/g) || [];
      const contentWords = allWords.filter((word) => !/^(?:a|an|the|to|at|in|on|and|or|of|for|with)$/.test(word));
      if (allWords.length >= 2 && contentWords.length <= 1) return false;
      if (words.length >= 2 && words.every((word) => sourceTokens.has(word))) return false;
      return true;
    });
}

function expandAgentCardClozeOptions(options = [], answerParts = [], requestedCount = 0, scope = {}) {
  const safeRequested = Number(requestedCount || 0);
  const maxCount = safeRequested ? Math.max(2, Math.min(safeRequested, 8)) : 0;
  const current = uniqueAgentCardTexts(options, 80);
  if (!maxCount || current.length >= maxCount) return current;
  return uniqueAgentCardTexts([
    ...current,
    ...fallbackAgentCardClozeDistractors(scope?.messageText || "", answerParts)
  ], 80).slice(0, maxCount);
}

function fallbackAgentCardClozeDistractors(message = "", answerParts = []) {
  const text = String(message || "").toLowerCase();
  const answers = new Set((answerParts || []).map((item) => normalizeAgentAnswerLookupText(item)).filter(Boolean));
  const pool = /daily|routine|morning|breakfast|work|school|日常|早上/.test(text)
    ? ["get dressed", "eat breakfast", "brush my teeth", "study", "cook", "sleep", "read"]
    : ["go", "make", "take", "get", "do", "have", "play", "read"];
  return pool.filter((item) => !answers.has(normalizeAgentAnswerLookupText(item)));
}

function shouldReplaceExplicitAgentCardCloze(component = {}, componentAnswerParts = [], answerParts = [], scope = {}) {
  if (scope?.clozeConstraint?.strict !== true) return false;
  if (!Array.isArray(scope?.clozeConstraint?.answerParts) || scope.clozeConstraint.answerParts.length < answerParts.length) {
    return false;
  }
  const componentOptions = normalizeAgentCardClozeOptions(component.options, []);
  const expectedOptions = normalizeScopedAgentCardClozeOptions([], answerParts, scope);
  return !sameAgentCardTextList(componentAnswerParts, answerParts) ||
    !sameAgentCardTextSet(componentOptions, expectedOptions);
}

function fallbackRequestedClozeText(answerParts = [], requestedCount = 0, scope = {}) {
  const sourceFallback = clozeTextFromSourceSentence(scope?.clozeConstraint?.sourceSentence || "", answerParts, requestedCount);
  if (sourceFallback) return sourceFallback;
  const answers = answerParts.map((item) => String(item || "").trim().toLowerCase()).filter(Boolean);
  if (requestedCount === 4 && ["decide", "decision", "decisive", "decisively"].every((item) => answers.includes(item))) {
    return "Please ___ soon. Your final ___ must be clear. A ___ leader acts calmly. She answered ___ after checking the facts.";
  }
  if (answerParts.length >= requestedCount) {
    return answerParts.slice(0, requestedCount).map((_, index) => `Blank ${index + 1}: ___`).join(" ");
  }
  return "";
}

function isGenericAgentCardClozeBlankText(value = "") {
  const text = String(value || "").trim();
  if (!text) return false;
  return /^(?:Blank\s*\d+\s*[:：]?\s*_{2,}\s*)+$/i.test(text) ||
    /^(?:\d+[\.)、]\s*_{2,}\s*)+$/i.test(text);
}

function clozeTextFromSourceSentence(sourceSentence = "", answerParts = [], requestedCount = 0) {
  let text = compactAgentCardText(sourceSentence, agentCardComponentTextMaxLength("cloze"));
  if (!text || !Array.isArray(answerParts) || answerParts.length < requestedCount) return "";
  let blankCount = countAgentCardClozeBlanks(text);
  for (const answer of answerParts.slice(0, requestedCount)) {
    if (blankCount >= requestedCount) break;
    const pattern = new RegExp(`\\b${escapeRegExp(answer).replace(/\\ /g, "\\s+")}\\b`, "i");
    if (!pattern.test(text)) return "";
    text = text.replace(pattern, "___");
    blankCount = countAgentCardClozeBlanks(text);
  }
  return blankCount === requestedCount ? text : "";
}

function normalizeAgentCardClozeOptions(options = [], answerParts = []) {
  return uniqueAgentCardTexts([
    ...(Array.isArray(options) ? options : []),
    ...answerParts
  ], 80);
}

function sameAgentCardTextList(left = [], right = []) {
  if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length) return false;
  return left.every((item, index) => normalizeAgentAnswerLookupText(item) === normalizeAgentAnswerLookupText(right[index]));
}

function sameAgentCardTextSet(left = [], right = []) {
  if (!Array.isArray(left) || !Array.isArray(right) || left.length !== right.length) return false;
  const leftSet = new Set(left.map(normalizeAgentAnswerLookupText).filter(Boolean));
  const rightSet = new Set(right.map(normalizeAgentAnswerLookupText).filter(Boolean));
  if (leftSet.size !== rightSet.size) return false;
  return Array.from(leftSet).every((item) => rightSet.has(item));
}

function splitAgentCardClozeAnswerParts(answer = "", requestedCount = 0) {
  const parts = String(answer || "")
    .split(/\s*(?:\||\/|,|;|、|，|；|\n|→|->)\s*/)
    .map((item) => compactAgentCardText(item, 80))
    .filter(Boolean);
  if (parts.length === 1 && requestedCount > 1) {
    return parts[0].split(/\s+/).map((item) => item.trim()).filter(Boolean);
  }
  return parts;
}

function enforceRequestedWordFamilyTokenCount(component = {}, requestedCount = 0, scope = {}) {
  const tokens = Array.isArray(component.tokens) ? component.tokens : [];
  if (tokens.length >= requestedCount) return component;
  const itemTokens = deriveAgentCardTokensFromItems("word_family", component.items || []);
  const context = [
    scope.messageText,
    component.title,
    component.text,
    ...tokens,
    ...itemTokens
  ].filter(Boolean).join(" ").toLowerCase();
  const merged = uniqueAgentCardTexts([
    ...tokens,
    ...itemTokens,
    ...agentCardWordFamilyFallbackTokens(context)
  ], 80).slice(0, requestedCount);
  if (merged.length <= tokens.length) return component;
  return {
    ...component,
    tokens: merged,
    items: [merged.join(" | ")]
  };
}

function agentCardWordFamilyFallbackTokens(context = "") {
  if (/decide|decision|decisive|decisively|decider/.test(context)) {
    return ["decide", "decision", "decisive", "decisively", "decider"];
  }
  if (/create|creation|creative|creatively|creator/.test(context)) {
    return ["create", "creation", "creative", "creatively", "creator"];
  }
  if (/act|action|active|actively|activity/.test(context)) {
    return ["act", "action", "active", "actively", "activity"];
  }
  if (/inform|information|informative|informed|informally/.test(context)) {
    return ["inform", "information", "informative", "informed", "informant"];
  }
  return [];
}

function enforceRequestedListComponentCount(component = {}, requestedCount = 0, scope = {}) {
  if (component.type === "scenario") {
    return enforceRequestedScenarioComponentCount(component, requestedCount, scope);
  }
  if (component.type === "translation") {
    return enforceRequestedTranslationComponentCount(component, requestedCount, scope);
  }
  if (component.type === "pronunciation") {
    return enforceRequestedPronunciationComponentCount(component, requestedCount, scope);
  }
  const currentItems = Array.isArray(component.items) ? component.items : [];
  const currentPairs = Array.isArray(component.pairs) ? component.pairs : [];
  const currentCount = currentPairs.length || currentItems.length;
  if (currentCount >= requestedCount) return component;
  const context = [
    scope.messageText,
    component.title,
    component.text,
    ...currentItems,
    ...currentPairs.flatMap((pair) => [pair?.left, pair?.right, pair?.hint])
  ].filter(Boolean).join(" ").toLowerCase();
  const fallbackItems = agentCardListFallbackItems(component.type, context);
  const mergedItems = uniqueAgentCardTexts([...currentItems, ...fallbackItems], 180)
    .slice(0, requestedCount);
  if (mergedItems.length <= currentCount) return component;
  return {
    ...component,
    title: shouldConstrainHotelCheckInScenario(context) ? "Hotel Check-in" : component.title,
    items: mergedItems
  };
}

function enforceRequestedPronunciationComponentCount(component = {}, requestedCount = 0, scope = {}) {
  const textTip = cleanAgentCardPronunciationTipText(component.text || "");
  const rawItems = Array.isArray(component.items)
    ? uniqueAgentCardPronunciationTipTexts(component.items.map((item) => cleanAgentCardPronunciationTipText(item)).filter(Boolean), 180)
    : [];
  const context = [
    scope?.messageText || "",
    component.title || "",
    textTip,
    ...rawItems
  ].join(" ").toLowerCase();
  const currentItems = uniqueAgentCardPronunciationTipTexts(
    rawItems.filter((item) => !isOffTopicAgentCardPronunciationTip(item, context)),
    180
  );
  const candidateTextTip = textTip && !isOffTopicAgentCardPronunciationTip(textTip, context) ? textTip : "";
  if (currentItems.length >= requestedCount) {
    return {
      ...component,
      title: trimDanglingAgentCardWords(component.title || ""),
      text: "",
      items: currentItems.slice(0, requestedCount)
    };
  }
  const mergedItems = uniqueAgentCardPronunciationTipTexts([
    ...(candidateTextTip ? [candidateTextTip] : []),
    ...currentItems,
    ...agentCardListFallbackItems("pronunciation", context)
  ], 180).slice(0, requestedCount);
  if (mergedItems.length < requestedCount) return component;
  return {
    ...component,
    title: trimDanglingAgentCardWords(component.title || ""),
    text: "",
    items: mergedItems
  };
}

function uniqueAgentCardPronunciationTipTexts(items = [], maxLength = 180) {
  const result = [];
  (items || []).forEach((item) => {
    const text = cleanAgentCardPronunciationTipText(item);
    if (!text) return;
    const existingIndex = result.findIndex((existing) => agentCardPronunciationTipCovers(existing, text) || agentCardPronunciationTipCovers(text, existing));
    if (existingIndex >= 0) {
      if (agentCardPronunciationTipCovers(text, result[existingIndex])) result[existingIndex] = text;
      return;
    }
    result.push(text);
  });
  return result.map((item) => compactAgentCardText(item, maxLength)).filter(Boolean);
}

function agentCardPronunciationTipCovers(container = "", candidate = "") {
  const containerKey = normalizeAgentCardPronunciationTipText(container);
  const candidateKey = normalizeAgentCardPronunciationTipText(candidate);
  if (!containerKey || !candidateKey) return false;
  if (containerKey === candidateKey) return true;
  return candidateKey.length >= 36 && containerKey.length > candidateKey.length && containerKey.includes(candidateKey);
}

function normalizeAgentCardPronunciationTipText(value = "") {
  return String(value || "")
    .toLowerCase()
    .replace(/['"“”‘’]/g, "")
    .replace(/[^a-z0-9θð]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function cleanAgentCardPronunciationTipText(value = "") {
  const text = trimDanglingAgentCardWords(compactAgentCardText(value, 180));
  const cleaned = text
    .replace(/\bto\s+(?:create|make|produce|form|build|show|use|practice|say)$/i, "")
    .replace(/\b(?:so|because|when|while|if|that|which|who|where|after|before)$/i, "")
    .replace(/[\s,;，。、；:：|/-]+$/g, "")
    .trim();
  if (!cleaned || cleaned === text) return cleaned || text;
  return /[A-Za-z0-9)]$/.test(cleaned) ? `${cleaned}.` : cleaned;
}

function isOffTopicAgentCardPronunciationTip(value = "", context = "") {
  if (isFinalEdPronunciationContext(context)) {
    return !/(?:\/t\/|\/d\/|\/ɪd\/|\b(?:voiceless|voiced|t|d|id)\b|final\s*-?ed|\bed\b|ended|wanted|played|worked)/i.test(String(value || "")) ||
      /\b(?:stress|unstressed|syllable|pitch)\b/i.test(String(value || ""));
  }
  if (!isThPronunciationContext(context)) return false;
  return !/(?:\bth\b|θ|ð|tongue|teeth|tooth|air|vocal|voice|voiced|unvoiced|vibrat|mouth|lips|breath|between|cords?)/i.test(String(value || ""));
}

const AGENT_CARD_DANGLING_ELLIPSIS_SCAN_TYPES = new Set([
  "vocabulary",
  "phrase",
  "grammar",
  "translation",
  "examples",
  "pronunciation",
  "cloze",
  "short_answer",
  "sentence_builder",
  "question_set",
  "compare",
  "correction",
  "rubric",
  "listening_cue",
  "minimal_pair",
  "word_family",
  "scenario",
  "register",
  "speaking_prompt",
  "writing_outline",
  "mistake_pattern"
]);

const AGENT_CARD_EXPRESSION_ONLY_DETAIL_TYPES = new Set([
  "scenario"
]);

function stripAgentCardInlineExamplesForScope(component = {}, scope = {}) {
  const excluded = scope.excluded instanceof Set ? scope.excluded : new Set(scope.excluded || []);
  if (!excluded.has("examples") || !AGENT_CARD_INLINE_EXAMPLE_SCAN_TYPES.has(component?.type)) return component;
  return {
    ...component,
    text: stripAgentCardInlineExampleText(component.text || "", component.type),
    items: Array.isArray(component.items)
      ? (component.type === "rubric" && Array.isArray(component.criteria) && component.criteria.length
          ? []
          : component.items.map((item) => stripAgentCardInlineExampleText(item, component.type)).filter((item) => item && !isPronunciationExampleOnlyText(item, component.type)))
      : component.items,
    pairs: Array.isArray(component.pairs)
      ? component.pairs.map((pair) => ({
          ...pair,
          left: stripAgentCardInlineExampleText(pair?.left || "", component.type),
          right: stripAgentCardInlineExampleText(pair?.right || "", component.type),
          hint: stripAgentCardInlineExampleText(pair?.hint || "", component.type)
        })).filter((pair) => pair.left || pair.right || pair.hint)
      : component.pairs,
    criteria: Array.isArray(component.criteria)
      ? component.criteria.map((criterion) => ({
          ...criterion,
          label: stripAgentCardInlineExampleText(criterion?.label || "", component.type),
          text: stripAgentCardInlineExampleText(criterion?.text || "", component.type)
        })).filter((criterion) => criterion.label || criterion.text)
      : component.criteria,
    steps: Array.isArray(component.steps)
      ? component.steps.map((step) => ({
          ...step,
          label: stripAgentCardInlineExampleText(step?.label || "", component.type),
          text: stripAgentCardInlineExampleText(step?.text || "", component.type)
        })).filter((step) => step.label || step.text)
      : component.steps
  };
}

function stripAgentCardInlineExampleText(value = "", componentType = "") {
  let text = compactAgentCardText(value, 220);
  if (!text) return "";
  const egPattern = "e\\s*\\.\\s*g\\s*\\.?\\s*,?";
  text = text.replace(new RegExp(`\\s*\\((?:as\\s+in|like\\s+in|${egPattern}|for example)[^)]+\\)[.;!?。！？]?`, "ig"), "");
  text = text.replace(/\s*\((?:i|you|he|she|we|they|it|this|that)\s+[a-z]{2,}\b[^)]*\)[.;!?。！？]?/ig, "");
  text = text.replace(/\s*\((?:[a-z]+(?:\s*,\s*[a-z]+)+)\)/ig, "");
  if (componentType === "compare") {
    text = text.replace(/\s*\((?:[a-z]+\s+[a-z][^,)]*(?:\s*,\s*[a-z]+\s+[a-z][^,)]*)+)\)/ig, "");
  }
  text = text.replace(new RegExp(`\\s*(?:as\\s+in|like\\s+in|${egPattern}|for example|example:)\\s+["'“”]?[a-z][^.;!?。！？]*(?:[.;!?。！？]|$)`, "ig"), "");
  text = text.replace(/\s*(?:(?:found|used|seen)\s+in\s+)?words?\s+like\s+["'“”]?[a-z][^.;!?。！？]*(?:[.;!?。！？]|$)/ig, "");
  text = text.replace(/\s*(?:found|used|seen)\s+in\s+["'“”]?[a-z][^.;!?。！？]*(?:[.;!?。！？]|$)/ig, "");
  text = text.replace(/\s*(?:think|words?|try)[:：]\s+["'“”]?[a-z][^.;!?。！？]*(?:[.;!?。！？]|$)/ig, "");
  text = text.replace(/\s*(?:→|->)\s*(?:for example,?\s*)?(?:i|you|he|she|we|they|it|this|that)\b[^.;!?。！？]*(?:[.;!?。！？]|$)/ig, "");
  if (componentType === "compare") {
    text = text.replace(/(:\s*)(?:i|you|he|she|we|they|it|this|that)\b[\s\S]*$/i, "$1");
  }
  if (componentType === "mistake_pattern") {
    text = text.replace(/:\s*(?:i|you|he|she|we|they|it|this|that)\b[\s\S]*$/i, "");
  }
  text = text.replace(new RegExp(`\\s*(?:${egPattern}|for example|example:|例如|例句[:：]?)\\s*[^.;!?。！？]*(?:[.;!?。！？]|$)`, "ig"), "");
  text = text.replace(/\s*\([^)]*$/g, "");
  return compactAgentCardText(text.replace(/\s+([,.;:])/g, "$1"), 220);
}

function isPronunciationExampleOnlyText(value = "", componentType = "") {
  if (componentType !== "pronunciation") return false;
  const text = String(value || "").toLowerCase();
  if (!/\/[a-zθðɪiːʃʒæɑɔʊəɛɜɒʌ]+\/|voiced|voiceless|sound/.test(text)) return false;
  const semanticCue = /\b(?:tongue|teeth|tooth|air|blow|vibration|vibrate|throat|mouth|lips|jaw|place|position|cords?|release|block|breathe)\b/.test(text);
  if (semanticCue) return false;
  const afterDash = text.split(/[-—:]/).pop() || text;
  const examples = afterDash.split(/[,，、\s]+/).map((item) => item.trim()).filter(Boolean);
  return examples.length >= 2;
}

function repairAgentCardDanglingEllipsisForScope(component = {}, scope = {}) {
  if (!AGENT_CARD_DANGLING_ELLIPSIS_SCAN_TYPES.has(component?.type)) return component;
  const pairs = Array.isArray(component.pairs)
    ? component.pairs.map((pair) => ({
        ...pair,
        left: repairAgentCardDanglingEllipsisText(pair?.left || ""),
        right: repairAgentCardDanglingEllipsisText(pair?.right || ""),
        hint: repairAgentCardDanglingEllipsisText(pair?.hint || "")
      }))
    : component.pairs;
  const items = Array.isArray(pairs) && pairs.length
    ? normalizeAgentCardPairSummariesWithoutTruncation(pairs)
    : Array.isArray(component.items)
      ? component.items.map(repairAgentCardDanglingEllipsisText).filter(Boolean)
      : component.items;
  return {
    ...component,
    title: repairAgentCardDanglingEllipsisText(component.title || ""),
    text: repairAgentCardDanglingEllipsisText(component.text || ""),
    items,
    pairs,
    tokens: Array.isArray(component.tokens)
      ? component.tokens.map(repairAgentCardDanglingEllipsisText).filter(Boolean)
      : component.tokens,
    steps: Array.isArray(component.steps)
      ? component.steps.map(repairAgentCardLabeledEllipsis).filter((item) => item.label || item.text)
      : component.steps,
    criteria: Array.isArray(component.criteria)
      ? component.criteria.map(repairAgentCardLabeledEllipsis).filter((item) => item.label || item.text)
      : component.criteria,
    options: Array.isArray(component.options)
      ? component.options.map(repairAgentCardDanglingEllipsisText).filter(Boolean)
      : component.options,
    examples: Array.isArray(component.examples)
      ? component.examples.map(repairAgentCardDanglingEllipsisText).filter(Boolean)
      : component.examples,
    questions: Array.isArray(component.questions)
      ? component.questions.map(repairAgentCardQuestionEllipsis)
      : component.questions,
    explanation: repairAgentCardDanglingEllipsisText(component.explanation || "")
  };
}

function repairAgentCardLabeledEllipsis(item = {}) {
  return {
    ...item,
    label: repairAgentCardDanglingEllipsisText(item?.label || ""),
    text: repairAgentCardDanglingEllipsisText(item?.text || "")
  };
}

function repairAgentCardQuestionEllipsis(item = {}) {
  return {
    ...item,
    questionText: repairAgentCardDanglingEllipsisText(item?.questionText || item?.question || ""),
    options: Array.isArray(item.options)
      ? item.options.map(repairAgentCardDanglingEllipsisText).filter(Boolean)
      : item.options,
    explanation: repairAgentCardDanglingEllipsisText(item?.explanation || "")
  };
}

function repairAgentCardDanglingEllipsisText(value = "") {
  let text = sanitizeAgentCardText(value).replace(/\s+/g, " ").trim();
  if (!/\.{3,}\s*$/.test(text)) return text;
  text = text
    .replace(/\bunder\s+the\s+name\.{3,}\s*$/i, "under the name [your name].")
    .replace(/\bunder\.{3,}\s*$/i, "under [your name].")
    .replace(/\bname\.{3,}\s*$/i, "name [your name].");
  if (/\.{3,}\s*$/.test(text)) {
    text = trimIncompleteAgentCardSentence(
      text.replace(/\.{3,}\s*$/, "").trim()
    );
  }
  return compactAgentCardTextWithoutEllipsis(text, 600);
}

function compactAgentCardTextWithoutEllipsis(value = "", maxLength = 600) {
  const text = sanitizeAgentCardText(value).replace(/\s+/g, " ").trim();
  if (text.length <= maxLength) return text;
  const limit = Math.max(1, maxLength);
  const head = text.slice(0, limit + 1);
  const cutAt = Math.max(
    head.lastIndexOf(" "),
    head.lastIndexOf("，"),
    head.lastIndexOf("。"),
    head.lastIndexOf("、"),
    head.lastIndexOf(";"),
    head.lastIndexOf("；"),
    head.lastIndexOf(","),
    head.lastIndexOf(" - "),
    head.lastIndexOf(" — ")
  );
  const minUseful = Math.max(6, Math.floor(limit * 0.58));
  const compacted = (cutAt >= minUseful ? head.slice(0, cutAt) : text.slice(0, limit))
    .replace(/[\s,;，。、；:：|/-]+$/g, "")
    .trim();
  return trimIncompleteAgentCardSentence(compacted);
}

function enforceExpressionOnlyDetailScope(component = {}, scope = {}) {
  if (!AGENT_CARD_EXPRESSION_ONLY_DETAIL_TYPES.has(component?.type)) return component;
  if (!isAgentExpressionOnlyScope(scope)) return component;
  const pairs = Array.isArray(component.pairs)
    ? component.pairs.map((pair) => {
        if (!shouldStripAgentCardExpressionDetail(pair?.left || "", pair?.right || pair?.hint || "")) {
          return pair;
        }
        return {
          ...pair,
          right: "",
          hint: ""
        };
      }).filter((pair) => pair.left || pair.right || pair.hint)
    : component.pairs;
  const items = Array.isArray(pairs) && pairs.length
    ? normalizeAgentCardPairSummariesWithoutTruncation(pairs)
    : Array.isArray(component.items)
      ? component.items.map(stripAgentCardExpressionOnlyRow).filter(Boolean)
      : component.items;
  return {
    ...component,
    text: "",
    items,
    pairs
  };
}

function isAgentExpressionOnlyScope(scope = {}) {
  const text = String(scope.messageText || "");
  return scope.suppressExtraSuggestions && /expression|表达/.test(text);
}

function stripAgentCardExpressionOnlyRow(value = "") {
  const row = compactAgentCardText(value, 220);
  const pair = splitAgentCardPairText(row);
  if (shouldStripAgentCardExpressionDetail(pair.left, pair.right)) return pair.left;
  return row;
}

function shouldStripAgentCardExpressionDetail(expression = "", detail = "") {
  if (!looksLikeAgentCardExpression(expression) || !looksLikeAgentCardExpressionDetail(detail)) return false;
  return true;
}

function looksLikeAgentCardExpression(value = "") {
  const text = compactAgentCardText(value, 160);
  if (!text) return false;
  if (/[?!.]$/.test(text)) return true;
  return /^(?:could|would|may|can|do|does|did|is|are|am|i|we|you|please|let's|id|i'd|ill|i'll)\b/i.test(text) &&
    text.split(/\s+/).length >= 4;
}

function looksLikeAgentCardExpressionDetail(value = "") {
  const text = compactAgentCardText(value, 160);
  if (!text) return false;
  return /^(?:confirm|request|ask|use|tell|explain|show|clarify|politely|express|say|mention|check|introduce|describe|practice|focus)\b/i.test(text);
}

function normalizeAgentCardPairSummariesWithoutTruncation(value) {
  if (!Array.isArray(value)) return [];
  return value.map((entry) => {
    if (!entry || typeof entry !== "object") return compactAgentCardText(entry, 120);
    const left = compactAgentCardText(entry.left || entry.source || entry.from || entry.a, 80);
    const right = compactAgentCardText(entry.right || entry.target || entry.to || entry.b, 120);
    const hint = compactAgentCardText(entry.hint || entry.text || entry.note, 80);
    const pair = left && right ? `${left} | ${right}` : (left || right);
    return pair && hint ? `${pair} - ${hint}` : (pair || hint);
  }).filter(Boolean);
}

function enforceExplicitListeningCueComponentScope(component = {}, scope = {}) {
  if (component?.type !== "listening_cue" || !scope.listeningCueConstraint?.terms?.length) return component;
  const terms = scope.listeningCueConstraint.terms;
  return {
    ...component,
    items: terms.map((term) => scopedListeningCueItem(term, scope)).filter(Boolean)
  };
}

function scopedListeningCueItem(term = "", scope = {}) {
  const cleanTerm = compactAgentCardText(term, 60);
  if (!cleanTerm) return "";
  if (!shouldIncludeListeningCueFunctionHint(scope?.messageText || "")) return cleanTerm;
  const hint = listeningCueFunctionHint(cleanTerm);
  return hint ? compactAgentCardText(`${cleanTerm} - ${hint}`, 90) : cleanTerm;
}

function shouldIncludeListeningCueFunctionHint(message = "") {
  return /功能|作用|提示|用法|含义|meaning|function|usage|hint/i.test(String(message || ""));
}

function listeningCueFunctionHint(term = "") {
  const key = normalizeAgentAnswerLookupText(term);
  const hints = {
    however: "contrast / 转折",
    therefore: "result / 结果",
    meanwhile: "time shift / 同时或转场",
    because: "reason / 原因",
    although: "concession / 让步",
    "on the other hand": "contrast / 另一方面",
    "as a result": "result / 结果",
    "even though": "concession / 让步",
    but: "contrast / 转折",
    so: "result / 结果",
    thus: "result / 结果",
    hence: "result / 结果"
  };
  return hints[key] || "";
}

function omitAgentCardExplanation(component = {}) {
  const next = { ...component };
  delete next.explanation;
  return next;
}

function filterAgentCardChips(chips = [], scope = {}) {
  const cleaned = (Array.isArray(chips) ? chips : [])
    .map((item) => compactAgentCardText(item, 18))
    .filter(Boolean)
    .filter((item) => !/(最近素材|current\s*material|latest\s*material|素材)/i.test(item));
  if (!scope.strictChoice) return cleaned.slice(0, 4);
  return cleaned
    .filter((item) => /(choice|choose|meaning|vocab|vocabulary|word|选择|含义|词汇|单词)/i.test(item))
    .slice(0, 3);
}

function defaultAgentCardCoreComponent(type) {
  if (type === "audio") return { type, source: "latestMaterial.audio" };
  if (type === "transcript") return { type, source: "latestMaterial.script" };
  if (type === "sentence_transcript") return { type, source: "latestMaterial.script" };
  if (type === "question_preview") return { type, source: "latestMaterial.questions" };
  return { type };
}

function mergeAgentCardSuggestion(existing, demoted = [], presentComponents = new Set(), scope = {}) {
  const messageSuggestion = fallbackOptionalExtraSuggestionComponent(scope?.messageText || "", presentComponents);
  const extraItems = [
    ...(Array.isArray(messageSuggestion?.items) ? messageSuggestion.items : []),
    ...demoted
      .filter(Boolean)
      .map((label) => `可选建议：需要时再生成「${label}」卡片`)
  ];
  if (!existing && extraItems.length === 0) return null;
  const base = existing || messageSuggestion || { type: "suggestion", title: "可选建议" };
  const filteredText = isRedundantTranscriptSuggestion(base.text || "", presentComponents) ||
    isRedundantQuestionSuggestion(base.text || "", presentComponents)
    ? ""
    : base.text || "";
  const filteredItems = Array.from(new Set([...(Array.isArray(base.items) ? base.items : []), ...extraItems]))
    .filter((item) => !isRedundantTranscriptSuggestion(item, presentComponents))
    .filter((item) => !isRedundantQuestionSuggestion(item, presentComponents))
    .filter((item) => !isExplicitlyExcludedAgentCardText(item, scope))
    .slice(0, 8);
  const finalText = isExplicitlyExcludedAgentCardText(filteredText, scope) ? "" : filteredText;
  if (!finalText && filteredItems.length === 0 && !base.source) return null;
  return {
    ...base,
    title: base.title || "可选建议",
    text: finalText,
    items: filteredItems
  };
}

function isRedundantTranscriptSuggestion(text = "", presentComponents = new Set()) {
  return (presentComponents.has("transcript") || presentComponents.has("sentence_transcript")) &&
    AGENT_CARD_REDUNDANT_TRANSCRIPT_PATTERN.test(String(text || ""));
}

function isRedundantQuestionSuggestion(text = "", presentComponents = new Set()) {
  return (presentComponents.has("question_preview") || presentComponents.has("question_set")) &&
    AGENT_CARD_REDUNDANT_QUESTION_PATTERN.test(String(text || ""));
}

function agentCardComponentLabel(component = {}) {
  return compactAgentCardText(component.title || AGENT_CARD_COMPONENT_LABELS.get(component.type) || component.type || "", 40);
}

function filterAgentCardActions(actions = [], allowed, presentComponents = new Set(), scope = {}) {
  if (agentCardActionsExplicitlyExcluded(scope)) return [];
  if (scope.strictChoice) return [];
  if (scope.suppressExtraSuggestions) return [];
  if (presentComponents.has("sentence_builder") || presentComponents.has("ordering") || presentComponents.has("short_answer")) return [];
  return actions.filter((action) => {
    const text = `${action.id || ""} ${action.label || ""} ${action.prompt || ""}`.toLowerCase();
    if (isExplicitlyExcludedAgentCardText(text, scope)) return false;
    if (isRedundantTranscriptSuggestion(text, presentComponents)) return false;
    if (isRedundantQuestionSuggestion(text, presentComponents)) return false;
    if (/(training|intensive|review|word_sentence|训练|复盘|逐句精听|开始答题)/i.test(text)) return false;
    return AGENT_CARD_COMPONENT_KEYWORDS
      .filter(([type]) => !allowed.has(type) && !AGENT_CARD_STRUCTURAL_COMPONENTS.has(type))
      .every(([, pattern]) => !pattern.test(text));
  }).slice(0, 3);
}

function sanitizeAgentCardReplyForSpec(reply = "", cardSpec = null, message = "") {
  let text = stripAgentReplyDecorativeEmoji(String(reply || "").trim());
  if (!text || !cardSpec) return text;
  const components = Array.isArray(cardSpec.components) ? cardSpec.components : [];
  const scopedReply = sanitizeAgentCardReplyAgainstCurrentScope(text, components, message);
  if (scopedReply !== text) text = scopedReply;
  // best-effort 封顶后实际题数可能少于用户请求数；若 reply 文案写死的题数与真实 question_set 题数不符，改用不带数字的题组文案，避免“已生成12道”但实际 8 题的文案矛盾。
  const questionSetComponent = components.find((component) => component?.type === "question_set");
  if (questionSetComponent && Array.isArray(questionSetComponent.questions)) {
    const actualQuestionCount = questionSetComponent.questions.length;
    const claimedCountMatch = text.match(/(\d+)\s*(?:道|题|問題|问题|questions?)/i);
    if (actualQuestionCount > 0 && claimedCountMatch) {
      const claimedCount = Number(claimedCountMatch[1]);
      if (Number.isFinite(claimedCount) && claimedCount !== actualQuestionCount) {
        return agentCardReadyReply(components, message);
      }
    }
  }
  if (cardSpec.title === "稳定练习卡片" && isGenericAgentChatReply(text)) {
    return agentCardReadyReply(components, message);
  }
  if (isContradictoryAgentCardClarificationReply(text, components)) {
    return agentCardReadyReply(components, message);
  }
  if (isNoAudioAgentCardRequest(message)) {
    const stripped = stripNoAudioPlaybackSentences(text);
    text = components.some((component) => component?.type === "short_answer")
      ? neutralizeNoAudioCueText(stripped || text, "Short answer card ready.")
      : stripped || text;
  }
  if (isReadingPassageQuizRequest(message) && readingTextMentionsExcludedPriorTopic(text, message)) {
    text = "Reading comprehension card ready.";
  }
  if (!hasRenderableAgentCardActions(cardSpec)) {
    text = stripUnavailableActionSentences(text) || agentCardReadyReply(components, message);
  }
  const hasMinimalPair = components.some((component) => component?.type === "minimal_pair");
  if (components.some((component) => component?.type === "sentence_builder" || component?.type === "ordering")) {
    return agentCardReadyReply(components, message);
  }
  if (components.some((component) => component?.type === "translation")) {
    return agentCardReadyReply(components, message);
  }
  if (
    components.some((component) => component?.type === "scenario") &&
    /only\s+scenario|no\s+extra|without\s+extra|不要.{0,8}额外|不需要.{0,8}额外|无额外/i.test(message)
  ) {
    return "已生成场景练习卡。";
  }
  if (!hasMinimalPair) return text;
  const parts = text.match(/[^.!?。！？]+[.!?。！？]?/g) || [text];
  const cleaned = parts
    .map((part) => part.trim())
    .filter(Boolean)
    .filter((part) => !isFakeMinimalPairPlaybackInstruction(part))
    .join(" ")
    .trim();
  return cleaned || "Minimal pair card ready.";
}

function sanitizeAgentCardReplyAgainstCurrentScope(reply = "", components = [], message = "") {
  const text = String(reply || "").trim();
  if (!text) return text;
  const scope = inferAgentCardScope(message);
  if (!scope.scoped) return text;
  const present = new Set((components || []).map((component) => component?.type).filter(Boolean));
  const blockedPatterns = disallowedAgentCardHeadingPatterns(scope.allowed, present, scope);
  const mentionsMultipleCards = /\b(?:two|three|multiple|several)\b.{0,40}\bcards?\b|两张.{0,8}卡|多张.{0,8}卡/i.test(text);
  if (!mentionsMultipleCards && !blockedPatterns.some((pattern) => pattern.test(text))) return text;
  return agentCardReadyReply(components, message);
}

function hasRenderableAgentCardActions(cardSpec = {}) {
  return Array.isArray(cardSpec.actions) && cardSpec.actions.length > 0;
}

function stripUnavailableActionSentences(value = "") {
  const parts = String(value || "").match(/[^.!?。！？]+[.!?。！？]?/g) || [String(value || "")];
  return parts
    .map((part) => part.trim())
    .filter(Boolean)
    .filter((part) => !isUnavailableActionInstruction(part))
    .join(" ")
    .trim();
}

function isUnavailableActionInstruction(value = "") {
  const text = String(value || "");
  return /\b(?:tap|click|press)\b.{0,80}\b(?:next|another|button|prompt|card|topic)\b/i.test(text) ||
    /\b(?:next)\s+[A-Z]?[A-Za-z]*(?:\s+[A-Z]?[A-Za-z]*){0,3}\b/i.test(text) && /\b(?:tap|click|press|button|another|topic)\b/i.test(text) ||
    /(?:点击|点按|按下).{0,40}(?:按钮|下一|下一个|再来|继续|卡片|主题)/i.test(text);
}

function isGenericAgentChatReply(value = "") {
  return /继续把你的英语问题发给我|send me your english question|ask me your english question|继续发给我/i.test(String(value || ""));
}

function isContradictoryAgentCardClarificationReply(value = "", components = []) {
  const text = String(value || "").trim();
  if (!text) return false;
  const hasPracticeComponent = (components || []).some((component) =>
    ["question_set", "cloze", "short_answer", "sentence_builder", "ordering", "correction", "phrase", "vocabulary", "grammar"].includes(component?.type)
  );
  if (!hasPracticeComponent) return false;
  return /(?:请|麻烦|把|发|贴|提供|send|paste|provide).{0,24}(?:句子|单词|题干|选项|内容|材料|text|sentence|word|question|content).{0,24}(?:给我|给我吧|发给我|贴给我|send|paste|provide)?[。.!！]?$/i.test(text) ||
    /(?:需要|想要).{0,12}(?:纠正|练习|生成|做).{0,24}(?:请|把|发|贴|提供)/i.test(text);
}

function isGenericAgentReadyReply(value = "") {
  const text = String(value || "").trim();
  return /^(?:好的|可以|ok|okay|done|ready|question\s*set\s*ready|cloze\s*ready)[。.!！]*$/i.test(text) ||
    /^(?:here\s+is|here\s+are).{0,80}(?:card|question\s*set|quiz|practice)/i.test(text);
}

function agentCardReadyReply(components = [], message = "") {
  const types = new Set(components.map((component) => component?.type).filter(Boolean));
  if (types.has("summary") && types.has("question_set")) return "已生成阅读理解练习卡。";
  if (types.has("question_set")) return "已生成题组练习卡。";
  if (types.has("cloze")) {
    if (isCetWordBankClozeRequest(message)) {
      const cloze = components.find((component) => component?.type === "cloze") || {};
      const optionCount = Array.isArray(cloze.options) ? cloze.options.length : 15;
      const blankCount = agentCardClozeAnswerParts(cloze.answer || "").length ||
        inferRequestedAgentCardItemCounts(message).cloze ||
        inferAgentRequestedClozeBlankCount(message) ||
        10;
      return `已生成${cetWordBankClozeTitle(optionCount, blankCount)}练习卡。`;
    }
    return isChoiceAnswerCardRequest(message) ? "已生成选择练习卡。" : "已生成填空练习卡。";
  }
  if (types.has("short_answer")) return "已生成输入答案练习卡。";
  if (types.has("sentence_builder")) return "已生成组句练习卡。";
  if (types.has("ordering")) return "已生成排序练习卡。";
  if (types.has("translation")) return "已生成翻译输入练习卡。";
  return "已生成练习卡。";
}

function sanitizeAgentReplyForCetWordBankCloze(reply = "", message = "", cardSpec = null) {
  if (!cardSpec || !isCetWordBankClozeRequest(message)) return reply;
  const components = Array.isArray(cardSpec.components) ? cardSpec.components : [];
  const cloze = components.find((component) => component?.type === "cloze");
  if (!cloze) return reply;
  const optionCount = Array.isArray(cloze.options) ? cloze.options.length : 15;
  const blankCount = agentCardClozeAnswerParts(cloze.answer || "").length ||
    inferAgentRequestedClozeBlankCount(message) ||
    10;
  const title = cetWordBankClozeTitle(optionCount, blankCount);
  return `已生成${title}练习卡。`;
}

function isExplicitlyExcludedAgentCardText(text = "", scope = {}) {
  const excluded = scope.excluded instanceof Set ? scope.excluded : new Set(scope.excluded || []);
  if (!excluded.size) return false;
  const value = String(text || "");
  return AGENT_CARD_COMPONENT_KEYWORDS
    .filter(([type]) => excluded.has(type))
    .some(([, pattern]) => pattern.test(value));
}

function dedupeAgentCardComponents(components = []) {
  const seen = new Set();
  return components.filter((component) => {
    if (!component?.type) return false;
    if (seen.has(component.type)) return false;
    seen.add(component.type);
    return true;
  });
}

function validateAgentCardSpec(rawCardSpec, normalizedCardSpec) {
  const errors = [];
  if (!rawCardSpec || typeof rawCardSpec !== "object") {
    errors.push("cardSpec missing");
    return { ok: false, errors };
  }
  const rawKind = normalizeRawCardToken(rawCardSpec.kind || "custom");
  if (OLD_AGENT_CARD_TOKENS.has(rawKind)) errors.push(`forbidden kind: ${rawKind}`);
  if (!normalizedCardSpec) errors.push("cardSpec cannot be normalized");
  if (normalizedCardSpec && normalizedCardSpec.components.length === 0) errors.push("cardSpec.components is empty after normalization");
  if (Array.isArray(rawCardSpec.components)) {
    rawCardSpec.components.forEach((component) => {
      const rawType = typeof component === "string"
        ? normalizeRawCardToken(component)
        : normalizeRawCardToken(component?.type || component?.name);
      if (OLD_AGENT_CARD_TOKENS.has(rawType)) errors.push(`forbidden component: ${rawType}`);
      if (rawType && !OLD_AGENT_CARD_TOKENS.has(rawType) && !AGENT_CARD_COMPONENT_ALIAS_MAP.has(rawType)) {
        errors.push(`unknown component: ${rawType}`);
      }
    });
  }
  if (Array.isArray(rawCardSpec.actions)) {
    rawCardSpec.actions.forEach((action) => {
      const rawId = normalizeRawCardToken(action?.id || action?.name || action?.type || "");
      const prompt = sanitizeAgentCardText(action?.prompt || action?.message || action?.text || "");
      if (OLD_AGENT_CARD_TOKENS.has(rawId)) errors.push(`forbidden action: ${rawId}`);
      if (!prompt) errors.push("action prompt missing");
      if (prompt && /(training|intensive|review|word_sentence|训练|复盘|逐句精听|开始答题)/i.test(prompt)) {
        errors.push("action prompt contains old local route wording");
      }
    });
  }
  return { ok: errors.length === 0, errors: Array.from(new Set(errors)) };
}

function fallbackOptionalExtraSuggestionComponent(message = "", present = new Set()) {
  if (!isOptionalExtraSuggestionAllowed(message)) return null;
  const text = String(message || "");
  const items = [];
  if (!present.has("sentence_transcript") && /精听|逐句|一句一句|sentence[_\s-]*transcript|sentence[_\s-]*by[_\s-]*sentence/i.test(text)) {
    items.push("可选建议：需要时再生成「逐句点播」卡片");
  }
  if (!present.has("vocabulary") && /(?:\u8bcd\u6c47|\u5355\u8bcd|vocab|vocabulary)/i.test(text)) {
    items.push("\u53ef\u9009\u5efa\u8bae\uff1a\u9700\u8981\u65f6\u518d\u751f\u6210\u300c\u8bcd\u6c47\u8bad\u7ec3\u300d\u5361\u7247");
  }
  if (!present.has("transcript") && /精读|原文|文本|transcript|script/i.test(text)) {
    items.push("可选建议：需要时再生成「原文」卡片");
  }
  if (!items.length) return null;
  return {
    type: "suggestion",
    title: "可选建议",
    items: Array.from(new Set(items)).slice(0, 4)
  };
}

function buildFallbackQuestionSetComponent(text = "") {
  const questionCount = inferAgentRequestedQuestionCount(text) || 3;
  const optionCount = inferAgentRequestedOptionCount(text) || 4;
  if (isReadingPassageQuizRequest(text)) {
    return buildFallbackReadingQuestionSetComponent(text);
  }
  if (/同义替换|synonym/i.test(text)) {
    return buildSynonymReplacementQuestionSetComponent(questionCount, optionCount);
  }
  if (isMinimalPairQuestionSetRequest(text)) {
    return buildMinimalPairQuestionSetComponent(text, questionCount, optionCount);
  }
  if (isMultiFillBlankQuestionSetRequest(text)) {
    return buildFillBlankQuestionSetComponent(text, questionCount, optionCount);
  }
  const topic = /travel|trip|airport|hotel|ticket|luggage|journey|transport|station/.test(text)
    ? "travel"
    : /office|work|job|salary|colleague|deadline|meeting|workplace/.test(text)
      ? "office"
      : /school|class|teacher|homework|subject/.test(text)
      ? "school"
      : "English";
  const baseQuestions = topic === "travel"
    ? [
        {
          questionText: "What does 'journey' mean?",
          answer: "A trip from one place to another",
          distractors: ["A bag for clothes", "A room in a hotel", "A paper for a bus"]
        },
        {
          questionText: "What does 'ticket' mean?",
          answer: "A pass that lets you travel",
          distractors: ["A person who drives", "A place to sleep", "A small travel bag"]
        },
        {
          questionText: "What does 'luggage' mean?",
          answer: "Bags you take when you travel",
          distractors: ["Food for a trip", "A train time", "A hotel worker"]
        },
        {
          questionText: "What does 'arrive' mean?",
          answer: "Get to a place",
          distractors: ["Leave a place", "Buy a ticket", "Pack a bag"]
        },
        {
          questionText: "What does 'book a room' mean?",
          answer: "Reserve a place to stay",
          distractors: ["Read in a hotel", "Clean a room", "Carry a suitcase"]
        },
        {
          questionText: "What does 'station' mean?",
          answer: "A place where buses or trains stop",
          distractors: ["A travel document", "A hotel breakfast", "A bag check"]
        }
      ]
    : topic === "office"
    ? [
        {
          questionText: "What does 'salary' mean?",
          answer: "Regular pay from work",
          distractors: ["A type of job", "A work schedule", "An office building"]
        },
        {
          questionText: "What does 'colleague' mean?",
          answer: "A person you work with",
          distractors: ["A customer", "A desk", "A lunch break"]
        },
        {
          questionText: "What does 'deadline' mean?",
          answer: "The last day or time to finish something",
          distractors: ["A meeting room", "A type of email", "A salary increase"]
        },
        {
          questionText: "What does 'schedule' mean?",
          answer: "A list of planned times",
          distractors: ["A phone call", "A type of chair", "A report folder"]
        },
        {
          questionText: "What does 'meeting' mean?",
          answer: "When people come together to discuss something",
          distractors: ["A greeting between friends", "A lunch order", "A copy machine"]
        },
        {
          questionText: "What does 'file' mean?",
          answer: "A collection of documents or data",
          distractors: ["A tool for cutting metal", "A type of chair", "A phone call"]
        },
        {
          questionText: "What does 'manager' mean?",
          answer: "A person who leads a team",
          distractors: ["A meeting note", "A work break", "An email address"]
        },
        {
          questionText: "What does 'overtime' mean?",
          answer: "Extra time worked after normal hours",
          distractors: ["A short vacation", "A new desk", "A morning meeting"]
        }
      ]
    : topic === "school"
    ? [
        {
          questionText: "What does 'homework' mean?",
          answer: "Work you do at home for school",
          distractors: ["A book you read for fun", "A game you play outside", "A room for sleeping"]
        },
        {
          questionText: "What is a 'teacher'?",
          answer: "A person who helps students learn",
          distractors: ["A place where students sit", "A book for a class", "A short school break"]
        },
        {
          questionText: "What does 'subject' mean at school?",
          answer: "An area you study, like math or English",
          distractors: ["A school bag", "A lunch time activity", "A bus stop"]
        },
        {
          questionText: "What is a 'classroom'?",
          answer: "A room where students learn",
          distractors: ["A place to buy food", "A sports field", "A bedroom"]
        }
      ]
    : [
        {
          questionText: "What does 'careful' mean?",
          answer: "Doing something with attention",
          distractors: ["Very loud", "Very late", "Very expensive"]
        },
        {
          questionText: "What does 'borrow' mean?",
          answer: "Use something and give it back",
          distractors: ["Keep something forever", "Break something", "Sell something"]
        },
        {
          questionText: "What does 'early' mean?",
          answer: "Before the expected time",
          distractors: ["After a long time", "With no sound", "For a high price"]
        }
      ];
  const questions = Array.from({ length: Math.max(1, Math.min(questionCount, 8)) }, (_, index) => {
    const source = baseQuestions[index % baseQuestions.length];
    const options = [source.answer, ...source.distractors]
      .slice(0, Math.max(2, Math.min(optionCount, 6)));
    const balanced = placeCorrectAgentQuestionOption(options, 0, index % options.length);
    return {
      questionText: source.questionText,
      options: balanced.options,
      correctAnswer: balanced.correctAnswer,
      explanation: ""
    };
  });
  return {
    type: "question_set",
    title: topic === "travel"
      ? "A2 Travel Vocabulary Quiz"
      : topic === "office"
        ? "A2 Office Vocabulary Quiz"
        : topic === "school"
        ? "A2 School Vocabulary Quiz"
        : "A2 Meaning Quiz",
    text: `${questions.length} meaning questions`,
    questions
  };
}

function buildSynonymReplacementQuestionSetComponent(questionCount = 3, optionCount = 4) {
  const baseQuestions = [
    {
      questionText: "Choose the closest synonym for 'quick'.",
      answer: "fast",
      distractors: ["late", "quiet", "heavy"]
    },
    {
      questionText: "Which phrase can replace 'because of'?",
      answer: "due to",
      distractors: ["instead of", "next to", "far from"]
    },
    {
      questionText: "Choose a similar meaning for 'important'.",
      answer: "essential",
      distractors: ["simple", "empty", "local"]
    },
    {
      questionText: "Which word is closest to 'improve'?",
      answer: "get better",
      distractors: ["give up", "slow down", "move away"]
    }
  ];
  const questions = Array.from({ length: Math.max(2, Math.min(questionCount, 8)) }, (_, index) => {
    const source = baseQuestions[index % baseQuestions.length];
    const options = [source.answer, ...source.distractors].slice(0, Math.max(2, Math.min(optionCount, 6)));
    const balanced = placeCorrectAgentQuestionOption(options, 0, index % options.length);
    return {
      questionText: source.questionText,
      options: balanced.options,
      correctAnswer: balanced.correctAnswer,
      explanation: ""
    };
  });
  return {
    type: "question_set",
    title: "同义替换题",
    text: `${questions.length} synonym replacement questions`,
    questions
  };
}

function buildFillBlankQuestionSetComponent(text = "", questionCount = 3, optionCount = 4) {
  const topic = /daily\s+routine|routine|morning|作息|日常/.test(String(text || "").toLowerCase())
    ? "routine"
    : "general";
  const baseQuestions = topic === "routine"
    ? [
        {
          questionText: "Every morning, I ___ at seven.",
          answer: "wake up",
          distractors: ["sleep up", "make up", "stand on"]
        },
        {
          questionText: "After breakfast, I ___ my teeth.",
          answer: "brush",
          distractors: ["wash", "cook", "open"]
        },
        {
          questionText: "I usually ___ to school by bus.",
          answer: "go",
          distractors: ["make", "take", "do"]
        },
        {
          questionText: "In the evening, I ___ my homework.",
          answer: "do",
          distractors: ["go", "have", "make"]
        },
        {
          questionText: "Before bed, I ___ a short shower.",
          answer: "take",
          distractors: ["go", "brush", "eat"]
        }
      ]
    : [
        {
          questionText: "I ___ English every day.",
          answer: "study",
          distractors: ["sleep", "drink", "open"]
        },
        {
          questionText: "She ___ to work by train.",
          answer: "goes",
          distractors: ["go", "going", "gone"]
        },
        {
          questionText: "They ___ dinner at six.",
          answer: "eat",
          distractors: ["read", "walk", "write"]
        }
      ];
  const questions = Array.from({ length: Math.max(1, Math.min(questionCount, 8)) }, (_, index) => {
    const source = baseQuestions[index % baseQuestions.length];
    const options = [source.answer, ...source.distractors].slice(0, Math.max(2, Math.min(optionCount, 6)));
    const balanced = placeCorrectAgentQuestionOption(options, 0, index % options.length);
    return {
      questionText: source.questionText,
      options: balanced.options,
      correctAnswer: balanced.correctAnswer,
      explanation: ""
    };
  });
  return {
    type: "question_set",
    title: topic === "routine" ? "A2 Daily Routine Fill-in-the-Blank" : "Fill-in-the-Blank Questions",
    text: `${questions.length} fill-in-the-blank questions`,
    questions
  };
}

function buildCetWordBankClozeSummaryComponent(text = "") {
  return {
    type: "summary",
    title: "材料",
    text: cetWordBankClozePassageText(text)
  };
}

function buildCetWordBankClozeComponent(text = "", existing = {}) {
  const bank = cetWordBankClozeWordBank(text);
  const answers = cetWordBankClozeAnswers(text);
  const choiceCounts = inferWordBankClozeChoiceCounts(text);
  const requestedBlankCount = inferRequestedAgentCardItemCounts(text).cloze ||
    inferAgentRequestedClozeBlankCount(text) ||
    10;
  const safeBlankCount = Math.max(1, Math.min(requestedBlankCount, 10));
  const optionCount = Math.max(safeBlankCount, Math.min(choiceCounts?.optionCount || bank.length, bank.length));
  return {
    ...(existing || {}),
    type: "cloze",
    title: cetWordBankClozeTitle(optionCount, safeBlankCount),
    text: cetWordBankClozeQuestionText(text, safeBlankCount),
    items: [],
    questions: [],
    options: bank.slice(0, optionCount),
    answer: answers.slice(0, safeBlankCount).join(" | "),
    explanation: ""
  };
}

// 贴主题翻译：让模型直接产出 N 组贴合主题的中英句对，校验非空+数量，多次重试，全失败返回 null（保留原内容/兜底）。
function inferAgentRequestedClozeBlankCount(text = "") {
  const value = String(text || "").toLowerCase();
  const numberToken = "([0-9]{1,2}|[一二两俩三四五六七八九十]{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)";
  const wordBankChoiceCount = inferWordBankClozeChoiceCounts(value);
  if (wordBankChoiceCount?.blankCount) return wordBankChoiceCount.blankCount;
  const patterns = [
    new RegExp(`${numberToken}\\s*(?:个|個)?\\s*(?:空|空格|blanks?)`, "i"),
    new RegExp(`(?:空|空格|blanks?)\\D{0,8}${numberToken}`, "i"),
    new RegExp(`(?:^|[^a-z0-9])${numberToken}\\s*(?:个|個|道)?\\s*(?:cet\\s*[46]|四级|六级)?\\s*(?:选词填空|选填|word\\s*bank\\s*cloze|cloze)`, "i")
  ];
  for (const pattern of patterns) {
    const match = pattern.exec(value);
    const count = parseCountToken(match?.[1] || "");
    if (count && count >= 1 && count <= 12) return count;
  }
  return 0;
}

function cetWordBankClozePassageText(text = "") {
  if (/shopping|网购|online/.test(String(text || "").toLowerCase())) {
    return "Online shopping has changed the way people buy daily products. It is convenient, but customers still need to compare prices, check quality, and protect personal information.";
  }
  return "Many students want to improve their English in a steady way. A useful method is to build a clear routine, read short passages every day, learn new words in context, and check answers carefully after practice.";
}

function cetWordBankClozeQuestionText(text = "", blankCount = 10) {
  const shopping = /shopping|网购|online/.test(String(text || "").toLowerCase());
  const safeBlankCount = Math.max(1, Math.min(Number(blankCount) || 10, 10));
  if (shopping) {
    return buildExactClozeText([
      "Online shopping is now a ___ part of daily life.",
      "It gives customers a ___ way to buy products.",
      "Careful choices are still ___.",
      "Before paying, shoppers should ___ prices.",
      "They also need to protect their ___ information.",
      "A ___ account can reduce risk.",
      "Checking ___ dates helps avoid problems.",
      "Product ___ matters when comparing items.",
      "Safe ___ habits protect money.",
      "Clear ___ rules make buying easier."
    ], safeBlankCount);
  }
  return buildExactClozeText([
    "A steady English routine can make learning more ___.",
    "Students should set a ___ goal.",
    "They can guess meaning from ___.",
    "They should check each ___ carefully.",
    "This helps them become more ___.",
    "Regular practice makes progress more ___.",
    "Small steps can create ___ progress over time.",
    "The method is especially ___ for exam preparation.",
    "A ___ study plan keeps learning active.",
    "A ___ habit is easier to maintain."
  ], safeBlankCount);
}

function buildExactClozeText(blankSentences = [], blankCount = 10) {
  const safeBlankCount = Math.max(1, Math.min(Number(blankCount) || 10, blankSentences.length || 10));
  const visible = blankSentences.slice(0, safeBlankCount);
  return visible.join(" ");
}

function cetWordBankClozeAnswers(text = "") {
  if (/shopping|网购|online/.test(String(text || "").toLowerCase())) {
    return ["common", "convenient", "essential", "compare", "personal", "secure", "delivery", "quality", "payment", "return"];
  }
  return ["effective", "realistic", "context", "answer", "accurate", "visible", "valuable", "useful", "regular", "steady"];
}

function cetWordBankClozeWordBank(text = "") {
  const answers = cetWordBankClozeAnswers(text);
  const distractors = /shopping|网购|online/.test(String(text || "").toLowerCase())
    ? ["random", "heavy", "silent", "borrowed", "narrow"]
    : ["distant", "random", "silent", "heavy", "borrowed"];
  return uniqueAgentCardTexts([...answers, ...distractors], 80).slice(0, 15);
}

function getAgentCardTelemetry() {
  return {
    invalidCardCount: agentCardTelemetry.invalidCardCount,
    repairAttemptCount: agentCardTelemetry.repairAttemptCount,
    repairSuccessCount: agentCardTelemetry.repairSuccessCount,
    fallbackCount: agentCardTelemetry.fallbackCount,
    qualityWarningCount: agentCardTelemetry.qualityWarningCount,
    lastQualityWarnings: agentCardTelemetry.lastQualityWarnings.slice(),
    lastErrors: agentCardTelemetry.lastErrors.slice()
  };
}

function resetAgentCardTelemetry() {
  agentCardTelemetry.invalidCardCount = 0;
  agentCardTelemetry.repairAttemptCount = 0;
  agentCardTelemetry.repairSuccessCount = 0;
  agentCardTelemetry.fallbackCount = 0;
  agentCardTelemetry.qualityWarningCount = 0;
  agentCardTelemetry.lastQualityWarnings = [];
  agentCardTelemetry.lastErrors = [];
}

function normalizeAgentCardSchemaVersion(cardSpec = {}) {
  const raw = cardSpec.schemaVersion ?? cardSpec.schema_version ?? 1;
  const value = Number.parseInt(raw, 10);
  return Number.isFinite(value) && value > 0 ? Math.min(value, 1) : 1;
}

function normalizeAgentCardComponent(item) {
  if (typeof item === "string") {
    const type = normalizeCardToken(item);
    return AGENT_CARD_COMPONENT_TYPE_SET.has(type) ? { type } : null;
  }
  if (!item || typeof item !== "object") return null;
  const type = normalizeCardToken(item.type || item.name);
  if (!AGENT_CARD_COMPONENT_TYPE_SET.has(type)) return null;
  const action = sanitizeAgentActionToken(item.action || "");
  const component = {
    type,
    title: compactAgentCardText(item.title, 24),
    text: compactAgentCardText(item.text || item.description, agentCardComponentTextMaxLength(type)),
    items: normalizeAgentCardComponentItems(item, type).slice(0, 8),
    value: Number.isFinite(Number(item.value)) ? Math.max(0, Math.min(1, Number(item.value))) : undefined,
    action: action || undefined,
    source: sanitizeAgentCardSource(item.source || item.bind || item.binding),
    state: normalizeAgentCardState(item.state || item.status),
    primary: item.primary === true
  };
  const pairs = normalizeAgentCardPairObjects(item.pairs, type);
  const tokens = normalizeAgentStringArray(item.tokens, 32).slice(0, 12);
  const steps = normalizeAgentCardLabeledObjects(item.steps);
  const criteria = normalizeAgentCardLabeledObjects(item.criteria);
  const options = normalizeAgentStringArray(item.options, type === "gap_match" ? 200 : 120).slice(0, 8);
  const examples = normalizeAgentStringArray(item.examples, 180).slice(0, 8);
  const questions = normalizeAgentCardQuestionObjects(item.questions).slice(0, 10);
  const derivedPairs = pairs.length ? pairs : deriveAgentCardPairsFromItems(type, component.items);
  const itemDerivedTokens = deriveAgentCardTokensFromItems(type, component.items);
  const derivedTokens = tokens.length
    ? uniqueAgentCardTexts([...tokens, ...itemDerivedTokens], 32).slice(0, 12)
    : itemDerivedTokens;
  const derivedSteps = steps.length ? steps : deriveAgentCardLabeledObjectsFromItems(type, component.items, "steps");
  const derivedCriteria = criteria.length ? criteria : deriveAgentCardLabeledObjectsFromItems(type, component.items, "criteria");
  const derivedOptions = options.length ? options : deriveAgentCardOptionsFromItems(type, component);
  if (derivedPairs.length) component.pairs = derivedPairs;
  if (derivedTokens.length) component.tokens = derivedTokens;
  if (derivedSteps.length) component.steps = derivedSteps;
  if (derivedCriteria.length) component.criteria = derivedCriteria;
  if (derivedOptions.length) component.options = derivedOptions;
  if (examples.length) component.examples = examples;
  if (questions.length) component.questions = questions;
  const answer = normalizeAgentCardAnswer(item, component.options || [], type);
  const explanation = compactAgentCardText(item.explanation || item.reason || item.hint, 180);
  if (answer) component.answer = answer;
  if (explanation) component.explanation = explanation;
  return component;
}

function normalizeAgentCardQuestionObjects(value) {
  if (!Array.isArray(value)) return [];
  const questions = value.map((entry) => {
    if (!entry || typeof entry !== "object") return null;
    const questionText = cleanAgentQuestionText(
      entry.questionText || entry.question || entry.text || entry.prompt,
      180
    );
    const options = normalizeAgentStringArray(entry.options, 140).slice(0, 6);
    if (!questionText || options.length < 2) return null;
    const rawAnswer = entry.answer ?? entry.correctAnswer ?? entry.correct_answer ?? entry.correct;
    const answer = compactAgentCardText(rawAnswer === undefined || rawAnswer === null ? "" : String(rawAnswer), 140);
    const correctAnswer = normalizeAgentQuestionAnswerIndex(answer, options);
    return {
      questionText,
      options,
      correctAnswer,
      explanation: compactAgentCardText(entry.explanation || entry.reason || entry.hint, 180),
      tag: normalizeAgentQuestionTag(entry.tag || entry.type || entry.questionType || entry.category || entry.skill)
    };
  }).filter(Boolean);
  return balanceAgentQuestionCorrectAnswerPositions(questions);
}

// 阅读/题目的题型标签：把常见英文/中文题型归一为简短中文标签，未知则保留压缩后的原值。
function normalizeAgentQuestionTag(value) {
  const raw = String(value || "").trim().toLowerCase();
  if (!raw) return "";
  if (/main[\s_-]*idea|main[\s_-]*point|gist|theme|topic|主旨|大意|中心/.test(raw)) return "主旨";
  if (/title|heading|标题/.test(raw)) return "标题";
  if (/infer|inference|imply|implication|deduce|推断|推理|推测/.test(raw)) return "推断";
  if (/vocab|vocabulary|word[\s_-]*meaning|meaning|guess.*word|词义|猜词|词汇/.test(raw)) return "词义";
  if (/attitude|tone|opinion|态度|语气|观点/.test(raw)) return "态度";
  if (/purpose|intention|目的|意图/.test(raw)) return "目的";
  if (/structure|organization|结构|结构题/.test(raw)) return "结构";
  if (/detail|specific|fact|细节|事实/.test(raw)) return "细节";
  return compactAgentCardText(String(value || ""), 12);
}

function balanceAgentQuestionCorrectAnswerPositions(questions = []) {
  if (!Array.isArray(questions) || questions.length < 2) return questions;
  return questions.map((question, index) => {
    const options = Array.isArray(question.options)
      ? question.options.map(stripAgentQuestionOptionLabel)
      : [];
    const currentIndex = isValidAgentQuestionCorrectAnswer(question.correctAnswer, options)
      ? Number(question.correctAnswer)
      : null;
    if (currentIndex === null) {
      return {
        ...question,
        options,
        correctAnswer: null
      };
    }
    if (!options.length || currentIndex < 0 || currentIndex >= options.length) {
      return {
        ...question,
        options
      };
    }
    const targetIndex = Math.min(index % options.length, options.length - 1);
    const reordered = placeCorrectAgentQuestionOption(options, currentIndex, targetIndex);
    return {
      ...question,
      options: reordered.options,
      correctAnswer: reordered.correctAnswer
    };
  });
}

function normalizeAgentQuestionAnswerIndex(answer = "", options = []) {
  const raw = String(answer || "").trim();
  if (!raw) return null;
  const directNumber = Number.parseInt(raw, 10);
  if (/^\d+$/.test(raw) && Number.isInteger(directNumber)) {
    if (directNumber >= 0 && directNumber < options.length) return directNumber;
    if (directNumber - 1 >= 0 && directNumber - 1 < options.length) return directNumber - 1;
  }
  const labelIndex = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(raw.toUpperCase());
  if (labelIndex >= 0 && labelIndex < options.length) return labelIndex;
  const normalized = normalizeAgentAnswerLookupText(raw);
  const optionIndex = options.findIndex((option) => normalizeAgentAnswerLookupText(option) === normalized);
  return optionIndex >= 0 ? optionIndex : null;
}

function normalizeAgentCardAnswer(item = {}, options = [], type = "") {
  const value = item.answer ?? item.correctAnswer ?? item.correct_answer ?? item.correct;
  const maxLength = type === "ordering" ? agentCardComponentTextMaxLength("ordering") : 120;
  const raw = compactAgentCardText(value === undefined || value === null ? "" : String(value), maxLength);
  if (!raw) return "";
  const directNumber = Number.parseInt(raw, 10);
  if (/^\d+$/.test(raw) && Number.isInteger(directNumber) && directNumber >= 0 && directNumber < options.length) {
    return options[directNumber];
  }
  const labelIndex = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(raw.toUpperCase());
  if (labelIndex >= 0 && labelIndex < options.length) return options[labelIndex];
  return raw;
}

function normalizeAgentCardComponentItems(item = {}, type = "") {
  const itemMaxLength = agentCardItemMaxLength(type);
  const explicitItems = normalizeAgentStringArray(item.items, itemMaxLength);
  if (explicitItems.length) return explicitItems;
  if (hasExplicitTypedAgentCardPayload(item)) return [];
  const typedCandidates = [
    normalizeAgentCardPairs(item.pairs, type),
    normalizeAgentCardTokens(item.tokens),
    normalizeAgentCardLabeledRows(item.steps),
    normalizeAgentCardLabeledRows(item.criteria),
    normalizeAgentStringArray(item.options, itemMaxLength),
    normalizeAgentStringArray(item.examples, itemMaxLength)
  ];
  return typedCandidates.find((items) => items.length) || [];
}

function hasExplicitTypedAgentCardPayload(item = {}) {
  return [
    item.pairs,
    item.tokens,
    item.steps,
    item.criteria,
    item.options,
    item.examples,
    item.questions
  ].some((value) => Array.isArray(value) && value.length > 0);
}

function normalizeAgentCardTokens(value) {
  const tokens = normalizeAgentStringArray(value, 32);
  return tokens.length ? [tokens.join(" | ").slice(0, 80)] : [];
}

function normalizeAgentCardLabeledRows(value) {
  if (!Array.isArray(value)) return [];
  return value.map((entry) => {
    if (!entry || typeof entry !== "object") return compactAgentCardText(entry, 180);
    const label = compactAgentCardText(entry.label || entry.title || entry.name || entry.key, 28);
    const text = compactAgentCardText(entry.text || entry.description || entry.value, 160);
    if (label && text) return compactAgentCardText(`${label} - ${text}`, 190);
    return compactAgentCardText(label || text, 180);
  }).filter(Boolean);
}

function normalizeAgentCardLabeledObjects(value) {
  if (!Array.isArray(value)) return [];
  return value.map((entry) => {
    if (!entry || typeof entry !== "object") return null;
    const label = compactAgentCardText(entry.label || entry.title || entry.name || entry.key, 28);
    const text = compactAgentCardText(entry.text || entry.description || entry.value, 180);
    if (!label && !text) return null;
    return { label, text };
  }).filter(Boolean).slice(0, 8);
}

function normalizeAgentCardAction(item = {}) {
  if (!item || typeof item !== "object") return null;
  const id = sanitizeAgentActionToken(item.id || item.name || item.type || "");
  const label = compactAgentCardLabel(item.label || "", 24);
  const prompt = compactAgentCardText(item.prompt || item.message || item.text || "", 260);
  if (!id || !prompt) return null;
  return {
    id,
    label: label || compactAgentCardLabel(prompt, 18),
    prompt: prompt || label,
    primary: item.primary === true,
    requiresMaterial: item.requiresMaterial === true
  };
}

function normalizeAgentCardKind(value) {
  const token = normalizeCardToken(value);
  if (OLD_AGENT_CARD_TOKENS.has(token)) return "";
  if (token === "toolchoice") return "tool_choice";
  if (token === "practice" || token === "card" || token === "ai_card") return "custom";
  return token || "custom";
}

function sanitizeAgentActionToken(value) {
  const token = String(value || "")
    .trim()
    .toLowerCase()
    .replace(/[-\s]+/g, "_")
    .replace(/[^a-z0-9_]/g, "")
    .slice(0, 40);
  if (OLD_AGENT_CARD_TOKENS.has(token)) return "";
  return token;
}

function normalizeAgentCardState(value) {
  const token = normalizeRawCardToken(value);
  return ["loading", "empty", "error", "disabled", "ready", "selected", "revealed"].includes(token)
    ? token
    : undefined;
}

// 纯文本/格式化工具（sanitizeAgentCardText / compactAgentCardText / trimDanglingAgentCardWords /
// compactAgentCardLabel / cleanAgentCardDanglingPunctuation / fixBasicEnglishArticleText / escapeRegExp）
// 已抽取到 ./agentCardText.js，统一在文件顶部 require 引入。

module.exports = {
  callMimoText,
  generateListeningContent,
  translateText,
  analyzeMistakes,
  analyzePracticeSheet,
  generateAgentChatReply,
  isExplicitAgentPracticeCardRequest,
  speechRateHint,
  normalizeVoiceGender,
  __test: {
    buildAnalysisPayload,
    evaluateAnswerSheet,
    normalizeAnalysisResultWithAnswerSheet,
    buildVoiceProfileWithDirectives,
    resolveListeningGenerationConstraints,
    enforceListeningGenerationConstraints,
    inferAgentCardScope,
    enforceRequestedAgentCardComponentCount,
    normalizeAgentCardSpec,
    enforceAgentCardScope,
    validateAgentCardSpec,
    assessAgentCardQuality,
    inferRequestedAgentCardItemCounts,
    sanitizeAgentOutputFormat,
    outputMimeType,
    isAgentOutputFileRequest,
    isAgentListeningZipExportRequest,
    buildAgentListeningZipExportContent,
    buildAgentWorkspaceMemoryHint,
    estimateTokens,
    selectRecentMessagesWithinBudget,
    tokenizeForMemoryRelevance,
    scoreMemoryEntryRelevance,
    getAgentCardTelemetry,
    resetAgentCardTelemetry,
    sanitizeAgentCardReplyForSpec,
    agentCardReadyReply,
    enforceForcedAgentCardSkeleton,
    classifyForcedAgentCardPrimary,
    isExplicitNewListeningPracticeRequest,
    isExplicitListeningDialogueMaterialRequest,
    isAgentMethodAdviceOnlyRequest,
    isStudyPlanRequest,
    deriveAgentQuestionGenerationMessage,
    isFreshQuestionGenerationExportRequest,
    isFreshPracticeGenerationExportRequest,
    serializeAgentCardSpecToExportText,
    normalizeAgentOutputFiles,
    isAgentQuestionExportRequest,
    agentCardComponentTypes: AGENT_CARD_COMPONENT_TYPES.slice()
  }
};
