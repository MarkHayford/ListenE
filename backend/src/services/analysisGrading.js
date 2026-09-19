// 听力错题的确定性判分 + AI 分析归一化（无模型调用，纯逻辑，便于单测与回归）。
// 权威事实来源只有 questions[].correctAnswer 与 selectedAnswers；模型输出仅作解释，
// 由 normalizeAnalysisResultWithAnswerSheet 强制对齐到程序判出的错题集。

function buildAnalysisPayload(payload) {
  const script = String(payload?.script || "");
  const maxScriptChars = 10000;
  return {
    title: payload?.title || "Practice",
    script: script.length > maxScriptChars ? `${script.slice(0, maxScriptChars)}\n...[truncated]` : script,
    questions: Array.isArray(payload?.questions) ? payload.questions : [],
    selectedAnswers: Array.isArray(payload?.selectedAnswers) ? payload.selectedAnswers : []
  };
}

function normalizeQuestionForAnalysis(question, index) {
  const options = Array.isArray(question?.options)
    ? question.options.map((item) => String(item || "").trim())
    : [];
  return {
    questionIndex: index,
    questionText: String(question?.questionText || question?.question || "").trim(),
    options,
    // 复用 coerceAnswerIndex：数字下标、字母(A/B/C)、选项原文都能解析，避免字符串答案被误判为 NaN。
    correctAnswer: coerceAnswerIndex({ options }, question?.correctAnswer),
    explanation: String(question?.explanation || "").trim()
  };
}

function optionLabel(index) {
  return "ABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(index) || String(index + 1);
}

function answerText(question, answerIndex, fallback = "未作答") {
  if (!Number.isInteger(answerIndex) || answerIndex < 0) return fallback;
  return String(question.options[answerIndex] || `选项 ${optionLabel(answerIndex)}`).trim();
}

function normalizeAnswerLookupText(text) {
  return String(text || "")
    .toLowerCase()
    .replace(/^[a-d][.)、\s-]+/i, "")
    .replace(/\s+/g, " ")
    .trim();
}

function coerceAnswerIndex(question, rawAnswer) {
  if (rawAnswer && typeof rawAnswer === "object") {
    return coerceAnswerIndex(question, rawAnswer.selectedAnswer ?? rawAnswer.answer ?? rawAnswer.optionIndex ?? rawAnswer.value);
  }
  if (typeof rawAnswer === "number" && Number.isFinite(rawAnswer)) return Math.trunc(rawAnswer);
  const answerTextValue = String(rawAnswer ?? "").trim();
  if (!answerTextValue) return null;
  if (/^-?\d+$/.test(answerTextValue)) return Number.parseInt(answerTextValue, 10);
  const labelIndex = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(answerTextValue.toUpperCase());
  if (labelIndex >= 0) return labelIndex;
  const normalized = normalizeAnswerLookupText(answerTextValue);
  const optionIndex = question.options.findIndex((option) => normalizeAnswerLookupText(option) === normalized);
  return optionIndex >= 0 ? optionIndex : null;
}

function buildSelectedAnswerMap(selectedAnswers, questions) {
  const map = new Map();
  if (Array.isArray(selectedAnswers)) {
    selectedAnswers.forEach((item, position) => {
      const questionIndex = item && typeof item === "object"
        ? Number.parseInt(item.questionIndex ?? item.index ?? position, 10)
        : position;
      const question = questions[questionIndex];
      if (!question) return;
      const answerIndex = coerceAnswerIndex(question, item && typeof item === "object" ? item.selectedAnswer : item);
      if (answerIndex !== null) map.set(questionIndex, answerIndex);
    });
    return map;
  }
  if (selectedAnswers && typeof selectedAnswers === "object") {
    Object.entries(selectedAnswers).forEach(([key, value]) => {
      const questionIndex = Number.parseInt(key, 10);
      const question = questions[questionIndex];
      if (!question) return;
      const answerIndex = coerceAnswerIndex(question, value);
      if (answerIndex !== null) map.set(questionIndex, answerIndex);
    });
  }
  return map;
}

function inferMistakeTypeFromQuestion(question) {
  const text = `${question.questionText} ${question.explanation}`.toLowerCase();
  if (/main idea|gist|purpose|主旨|中心|意图/.test(text)) return "主旨理解";
  if (/infer|imply|suggest|推断|暗示/.test(text)) return "推断能力";
  if (/why|because|reason|cause|原因|因果/.test(text)) return "因果关系";
  if (/where|when|who|what|which|how much|time|number|date|地点|时间|人物|细节|数字/.test(text)) return "细节定位";
  return "理解偏差";
}

function splitScriptSentences(script) {
  return String(script || "")
    .replace(/\r/g, "\n")
    .split(/(?<=[.!?])\s+|\n+/)
    .map((item) => item.replace(/^[^:：]{1,40}[:：]\s*/, "").trim())
    .filter((item) => item.length > 8)
    .slice(0, 80);
}

function keywordSet(text) {
  const stopwords = new Set([
    "about", "after", "before", "because", "could", "would", "should", "there", "their",
    "which", "where", "when", "what", "with", "from", "this", "that", "they", "were",
    "will", "have", "does", "why", "did", "the", "and", "for", "was", "are", "his",
    "her", "she", "him", "you", "your"
  ]);
  return new Set(
    String(text || "")
      .toLowerCase()
      .match(/[a-z]{3,}/g)
      ?.filter((word) => !stopwords.has(word)) || []
  );
}

function findFocusSentence(script, wrongQuestion) {
  const sentences = splitScriptSentences(script);
  if (!sentences.length) return "";
  const targetWords = keywordSet(`${wrongQuestion.questionText} ${wrongQuestion.correctAnswer} ${wrongQuestion.explanation}`);
  let best = "";
  let bestScore = 0;
  sentences.forEach((sentence) => {
    const words = keywordSet(sentence);
    let score = 0;
    targetWords.forEach((word) => {
      if (words.has(word)) score += 1;
    });
    if (score > bestScore) {
      bestScore = score;
      best = sentence;
    }
  });
  return best || sentences[0] || "";
}

function evaluateAnswerSheet(payload) {
  const questions = (Array.isArray(payload?.questions) ? payload.questions : [])
    .map(normalizeQuestionForAnalysis);
  const selectedMap = buildSelectedAnswerMap(payload?.selectedAnswers, questions);
  return questions.map((question) => {
    const selectedAnswerIndex = selectedMap.has(question.questionIndex)
      ? selectedMap.get(question.questionIndex)
      : null;
    const correctAnswerIndex = Number.isInteger(question.correctAnswer) ? question.correctAnswer : null;
    if (selectedAnswerIndex === correctAnswerIndex) return null;
    return {
      questionIndex: question.questionIndex,
      question: question.questionText,
      questionText: question.questionText,
      options: question.options,
      selectedAnswerIndex,
      correctAnswerIndex,
      selectedAnswer: answerText(question, selectedAnswerIndex),
      correctAnswer: answerText(question, correctAnswerIndex, "未知"),
      explanation: question.explanation,
      mistakeType: inferMistakeTypeFromQuestion(question),
      unanswered: selectedAnswerIndex === null
    };
  }).filter(Boolean);
}

function buildFallbackWrongInsight(wrongQuestion, script) {
  const focusSentence = findFocusSentence(script, wrongQuestion);
  const boundary = normalizeSegmentBoundary({ focusSentence }, script);
  return {
    questionIndex: wrongQuestion.questionIndex,
    question: wrongQuestion.question,
    selectedAnswer: wrongQuestion.selectedAnswer,
    correctAnswer: wrongQuestion.correctAnswer,
    mistakeType: wrongQuestion.mistakeType,
    insight: wrongQuestion.explanation || "这道题对应的信息没有准确定位。复听时先找题干关键词，再核对正确选项出现的位置。",
    focusSentence,
    startMs: boundary.startMs,
    endMs: boundary.endMs,
    startRatio: boundary.startRatio,
    endRatio: boundary.endRatio
  };
}

function mergeWrongInsight(wrongQuestion, aiInsight, script) {
  const fallback = buildFallbackWrongInsight(wrongQuestion, script);
  const focusSentence = String(aiInsight?.focusSentence || fallback.focusSentence || "").trim();
  const boundary = normalizeSegmentBoundary({
    ...aiInsight,
    focusSentence
  }, script);
  return {
    questionIndex: wrongQuestion.questionIndex,
    question: wrongQuestion.question,
    selectedAnswer: wrongQuestion.selectedAnswer,
    correctAnswer: wrongQuestion.correctAnswer,
    mistakeType: String(aiInsight?.mistakeType || fallback.mistakeType || "理解偏差").trim(),
    insight: String(aiInsight?.insight || fallback.insight).trim(),
    focusSentence,
    startMs: boundary.startMs,
    endMs: boundary.endMs,
    startRatio: boundary.startRatio,
    endRatio: boundary.endRatio
  };
}

function summaryContradictsWrongSheet(summary) {
  return /全对|全部正确|完全正确|没有错题|无错题|all\s+correct|no\s+wrong/i.test(String(summary || ""));
}

function summaryClaimsWrong(summary) {
  return /错题|答错|错误|wrong|mistake|incorrect/i.test(String(summary || ""));
}

function fallbackWeakPoints(wrongQuestions) {
  return Array.from(new Set(wrongQuestions.map((item) => item.mistakeType))).slice(0, 5);
}

function fallbackReviewItems(wrongQuestions) {
  const items = [];
  wrongQuestions.forEach((item) => {
    if (item.correctAnswer && item.correctAnswer !== "未知") {
      items.push({ text: item.correctAnswer, itemType: "sentence", reason: "错题正确选项对应的信息点" });
    }
  });
  return items.slice(0, 12);
}

function fallbackNextActions(wrongQuestions) {
  if (!wrongQuestions.length) {
    return [
      { title: "逐句听原文", description: "保持不看原文复听一遍，再点击原文句子核对关键信息。", actionType: "relisten" }
    ];
  }
  return [
    { title: "逐句听原文", description: `先复听 ${wrongQuestions.length} 道错题对应的信息句。`, actionType: "relisten" }
  ];
}

function normalizeAnalysisResultWithAnswerSheet(raw, payload) {
  const slimPayload = buildAnalysisPayload(payload);
  const wrongQuestions = evaluateAnswerSheet(slimPayload);
  const normalized = normalizeAnalysisResult(raw, slimPayload.script);
  const wrongIndexSet = new Set(wrongQuestions.map((item) => item.questionIndex));
  const aiInsightByIndex = new Map(
    normalized.wrongQuestionInsights
      .filter((item) => wrongIndexSet.has(item.questionIndex))
      .map((item) => [item.questionIndex, item])
  );
  const wrongQuestionInsights = wrongQuestions.map((wrongQuestion) =>
    mergeWrongInsight(wrongQuestion, aiInsightByIndex.get(wrongQuestion.questionIndex), slimPayload.script)
  );
  let summary = normalized.summary;
  if (wrongQuestions.length > 0 && summaryContradictsWrongSheet(summary)) {
    summary = `本次共有 ${wrongQuestions.length} 道错题，AI 已按实际答题结果生成错因复盘。`;
  } else if (wrongQuestions.length === 0 && (normalized.wrongQuestionInsights.length > 0 || summaryClaimsWrong(summary))) {
    summary = "本次答题没有发现错题，可以继续逐句听原文巩固。";
  }
  const fallbackTags = fallbackWeakPoints(wrongQuestions);
  return {
    ...normalized,
    summary,
    wrongQuestionInsights,
    weakPoints: normalized.weakPoints.length ? normalized.weakPoints : fallbackTags,
    suggestions: normalized.suggestions.length
      ? normalized.suggestions
      : ["先复听错题对应的信息句，再只听关键词和转折/因果信号。"],
    diagnosisTags: Array.from(new Set([
      ...normalized.diagnosisTags,
      ...fallbackTags
    ].filter(Boolean))).slice(0, 8),
    nextActions: normalized.nextActions.length ? normalized.nextActions : fallbackNextActions(wrongQuestions),
    reviewItems: normalized.reviewItems.length ? normalized.reviewItems : fallbackReviewItems(wrongQuestions),
    recommendedPlanTasks: []
  };
}

function normalizeLocatorText(text) {
  return String(text || "")
    .toLowerCase()
    .replace(/^[^:：]{1,40}[:：]\s*/gm, "")
    .replace(/[\u2018\u2019]/g, "'")
    .replace(/[\u201c\u201d]/g, '"')
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function locateTextRatio(script, focusSentence) {
  const rawScript = String(script || "");
  const rawFocus = String(focusSentence || "").trim();
  if (!rawScript || !rawFocus) return null;

  const normalizedFocus = normalizeLocatorText(rawFocus);
  if (!normalizedFocus) return null;
  const normalizedScript = normalizeLocatorText(rawScript);
  const normalizedIndex = normalizedScript.indexOf(normalizedFocus);
  if (normalizedIndex < 0) return null;

  const startRatio = normalizedIndex / Math.max(normalizedScript.length, 1);
  const sentenceRatio = normalizedFocus.length / Math.max(normalizedScript.length, 1);
  const endRatio = Math.min(1, startRatio + Math.max(sentenceRatio, 0.035));
  return {
    startRatio: Number(Math.max(0, startRatio - 0.012).toFixed(4)),
    endRatio: Number(Math.min(1, endRatio + 0.018).toFixed(4))
  };
}

function normalizeSegmentBoundary(item, script) {
  const rawStartMs = Number.parseInt(item?.startMs, 10);
  const rawEndMs = Number.parseInt(item?.endMs, 10);
  const hasMs = Number.isFinite(rawStartMs) && Number.isFinite(rawEndMs) && rawEndMs > rawStartMs;
  const rawStartRatio = Number.parseFloat(item?.startRatio);
  const rawEndRatio = Number.parseFloat(item?.endRatio);
  const hasRatio = Number.isFinite(rawStartRatio) && Number.isFinite(rawEndRatio) && rawEndRatio > rawStartRatio;
  const located = locateTextRatio(script, item?.focusSentence || item?.question || "");
  const startRatio = hasRatio ? rawStartRatio : located?.startRatio;
  const endRatio = hasRatio ? rawEndRatio : located?.endRatio;
  return {
    startMs: hasMs ? Math.max(0, rawStartMs) : null,
    endMs: hasMs ? Math.max(rawStartMs + 500, rawEndMs) : null,
    startRatio: Number.isFinite(startRatio) ? Math.min(1, Math.max(0, startRatio)) : null,
    endRatio: Number.isFinite(endRatio) ? Math.min(1, Math.max(0, endRatio)) : null
  };
}

function normalizeAnalysisResult(raw, script = "") {
  const summary = String(raw?.summary || raw?.Summary || "").trim();
  const stringList = (items) => Array.isArray(items)
    ? items.map((item) => String(item).trim()).filter(Boolean)
    : [];
  const weakPoints = stringList(raw?.weakPoints);
  const suggestions = stringList(raw?.suggestions);
  const diagnosisTags = stringList(raw?.diagnosisTags).slice(0, 8);
  const wrongQuestionInsights = Array.isArray(raw?.wrongQuestionInsights)
    ? raw.wrongQuestionInsights.map((item) => {
      const boundary = normalizeSegmentBoundary(item, script);
      return {
        questionIndex: Number.parseInt(item?.questionIndex, 10),
        question: String(item?.question || "").trim(),
        selectedAnswer: String(item?.selectedAnswer || "").trim(),
        correctAnswer: String(item?.correctAnswer || "").trim(),
        mistakeType: String(item?.mistakeType || "理解偏差").trim(),
        insight: String(item?.insight || "").trim(),
        focusSentence: String(item?.focusSentence || "").trim(),
        startMs: boundary.startMs,
        endMs: boundary.endMs,
        startRatio: boundary.startRatio,
        endRatio: boundary.endRatio
      };
    }).filter((item) => item.question || item.insight || item.focusSentence)
    : [];
  const nextActions = Array.isArray(raw?.nextActions)
    ? raw.nextActions.map((item) => ({
      title: String(item?.title || "").trim(),
      description: String(item?.description || "").trim(),
      actionType: String(item?.actionType || "review").trim()
    })).filter((item) => {
      const blocked = new Set(["plan", "word_sentence"]);
      return (item.title || item.description) && !blocked.has(item.actionType);
    }).slice(0, 1)
    : [];
  const reviewItems = Array.isArray(raw?.reviewItems)
    ? raw.reviewItems.map((item) => ({
      text: String(item?.text || "").trim(),
      itemType: String(item?.itemType || "sentence").trim(),
      reason: String(item?.reason || "").trim()
    })).filter((item) => item.text).slice(0, 16)
    : [];
  const recommendedPlanTasks = [];
  if (!summary) throw new Error("AI 分析结果缺少 summary 字段");
  return {
    summary,
    weakPoints,
    suggestions,
    diagnosisTags,
    wrongQuestionInsights,
    nextActions,
    reviewItems,
    recommendedPlanTasks
  };
}

module.exports = {
  buildAnalysisPayload,
  evaluateAnswerSheet,
  normalizeAnalysisResult,
  normalizeAnalysisResultWithAnswerSheet
};
