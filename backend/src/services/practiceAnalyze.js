// 练习卡（微元卡）作答分析：客户端在「核对答案」时已本地判分，上传作答表
// { title, total, correct, items:[{type,prompt,userAnswer,correctAnswer,correct,explanation?}] }。
// 这里做纯归一化 + 模型输出对齐（无模型调用，便于单测）：判分事实以客户端 correct 标记为准，
// 模型只负责解释错因；wrongQuestionInsights 强制对齐到程序判定的错题集合。

const MAX_ITEMS = 30;

function compact(text, max) {
  return String(text || "").replace(/\s+/g, " ").trim().slice(0, max);
}

function buildPracticeAnalysisPayload(payload) {
  const rawItems = Array.isArray(payload?.items) ? payload.items : [];
  const items = rawItems
    .filter((item) => item && typeof item === "object")
    .slice(0, MAX_ITEMS)
    .map((item, index) => ({
      questionIndex: index,
      type: compact(item.type, 40).toLowerCase(),
      prompt: compact(item.prompt, 600),
      userAnswer: compact(item.userAnswer, 400),
      correctAnswer: compact(item.correctAnswer, 400),
      correct: item.correct === true,
      explanation: compact(item.explanation, 600)
    }))
    .filter((item) => item.prompt && item.correctAnswer);
  const total = items.length;
  const correctCount = items.filter((item) => item.correct).length;
  return {
    title: compact(payload?.title, 120) || "练习卡",
    total,
    correct: correctCount,
    items,
    wrongItems: items.filter((item) => !item.correct)
  };
}

function fallbackInsight(item) {
  return {
    questionIndex: item.questionIndex,
    question: item.prompt,
    selectedAnswer: item.userAnswer || "未作答",
    correctAnswer: item.correctAnswer,
    mistakeType: item.userAnswer ? "理解偏差" : "未作答",
    insight: item.explanation || "对照正确答案找出差异点，再做一遍同类题巩固。"
  };
}

// 模型输出对齐：只保留程序判定错题的解释；缺失的错题补兜底；防止模型把答错说成全对/编造题号。
function normalizePracticeAnalysisResult(raw, payloadNorm) {
  const source = raw && typeof raw === "object" ? raw : {};
  const byIndex = new Map();
  (Array.isArray(source.wrongQuestionInsights) ? source.wrongQuestionInsights : []).forEach((insight) => {
    const idx = Number.parseInt(insight?.questionIndex, 10);
    if (Number.isInteger(idx)) byIndex.set(idx, insight);
  });
  const insights = payloadNorm.wrongItems.map((item) => {
    const ai = byIndex.get(item.questionIndex);
    const fallback = fallbackInsight(item);
    if (!ai) return fallback;
    return {
      questionIndex: item.questionIndex,
      question: compact(ai.question, 200) || fallback.question,
      selectedAnswer: fallback.selectedAnswer,
      correctAnswer: fallback.correctAnswer,
      mistakeType: compact(ai.mistakeType, 24) || fallback.mistakeType,
      insight: compact(ai.insight, 400) || fallback.insight
    };
  });
  const strList = (value, maxItems, maxLen) => (Array.isArray(value) ? value : [])
    .map((entry) => compact(entry, maxLen))
    .filter(Boolean)
    .slice(0, maxItems);
  const allCorrect = payloadNorm.total > 0 && payloadNorm.correct === payloadNorm.total;
  const fallbackSummary = allCorrect
    ? `全部答对（${payloadNorm.correct}/${payloadNorm.total}），可以提升难度继续练。`
    : `答对 ${payloadNorm.correct}/${payloadNorm.total}，重点看下面的错因说明。`;
  return {
    summary: compact(source.summary, 500) || fallbackSummary,
    weakPoints: strList(source.weakPoints, 6, 80),
    suggestions: strList(source.suggestions, 6, 120),
    diagnosisTags: strList(source.diagnosisTags, 8, 16),
    wrongQuestionInsights: insights,
    nextActions: [],
    reviewItems: [],
    recommendedPlanTasks: []
  };
}

module.exports = {
  buildPracticeAnalysisPayload,
  normalizePracticeAnalysisResult,
  __test: { buildPracticeAnalysisPayload, normalizePracticeAnalysisResult, fallbackInsight }
};
