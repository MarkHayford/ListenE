// 造句卡（Sentence Builder）：请求识别/否定判定/必需范围与兜底构建/词表清洗/答案覆盖校验/去用法说明范围裁剪。
// 原内联于 mimoText.js，逐字搬出为独立模块；仅依赖更底层服务模块，无循环依赖。

const { compactAgentCardText } = require("./agentCardText");
const { uniqueAgentCardTexts } = require("./explicitConstraints");
const { isOrderingCardRequest } = require("./ordering");
const { normalizeAgentStringArray, stripSentenceBuilderUsageInstruction } = require("./textNormalize");

function isSentenceBuilderNegatedRequest(text = "") {
  const value = String(text || "").toLowerCase();
  return /no\s+sentence\s*builder|without\s+sentence\s*builder|not\s+sentence\s*builder|no\s+word\s*order|without\s+word\s*order|no\s+reorder(?:ing)?\s+words?|without\s+reorder(?:ing)?\s+words?|不要.{0,8}(?:组句|连词成句|重新排成句子|单词顺序|词语排序|词块排序|sentence\s*builder|word\s*order)|不需要.{0,8}(?:组句|连词成句|重新排成句子|单词顺序|词语排序|词块排序|sentence\s*builder|word\s*order)|无(?:组句|连词成句|单词顺序|词语排序|词块排序)/i.test(value);
}

function isSentenceBuilderOnlyAgentCardRequest(text = "") {
  const value = String(text || "").toLowerCase();
  if (isOrderingCardRequest(value)) return false;
  return /sentence\s*builder|word\s*order|reorder\s+words?|组句|连词成句|单词顺序|词语排序|词块排序/i.test(value) &&
    /only\s+(?:one\s+)?(?:sentence\s*builder|word\s*order|reorder)|only\s+.*(?:sentence\s*builder|word\s*order|reorder)|no\s+multiple\s*choice|without\s+multiple\s*choice|no\s+mcq|without\s+mcq|no\s+cloze|without\s+cloze|不要.{0,8}(?:选择题|多选|单选|填空)|不需要.{0,8}(?:选择题|多选|单选|填空)/i.test(value);
}

function isSentenceBuilderRequiredScope(scope = {}) {
  return scope.allowed instanceof Set &&
    scope.allowed.has("sentence_builder") &&
    !scope.multiQuestionChoice &&
    !scope.multiFillBlankQuestions &&
    !scope.choiceConstraint &&
    !scope.clozeConstraint?.strict &&
    !isOrderingCardRequest(scope.messageText || "") &&
    /sentence\s*builder|word\s*order|reorder\s+words?|组句|连词成句|单词顺序|词语排序|词块排序/i.test(scope.messageText || "");
}

function buildRequiredSentenceBuilderComponent(existing = {}, scope = {}) {
  const request = inferSentenceBuilderRequest(scope?.messageText || "");
  const fallback = fallbackSentenceBuilderRequest(scope?.messageText || "");
  const answer = request.answer || existing.answer || existing.correctAnswer || fallback.answer || "";
  const existingItems = normalizeAgentStringArray(existing.items || existing.tokens, 80);
  const existingItemsUsable = existingItems.length >= 2 && (!answer || sentenceBuilderWordsCoverAnswer(existingItems, answer));
  const items = request.words.length ? request.words : (existingItemsUsable ? existingItems : fallback.words);
  return {
    ...existing,
    type: "sentence_builder",
    title: existing.title || "Sentence Builder",
    text: "",
    items: items.length ? items : answer.split(/\s+/).filter(Boolean),
    tokens: [],
    answer,
    correctAnswer: answer,
    explanation: ""
  };
}

function fallbackSentenceBuilderRequest(message = "") {
  const text = String(message || "").toLowerCase();
  if (/question|where|did|疑问|问句/.test(text)) {
    return {
      words: ["where", "did", "you", "go"],
      answer: "Where did you go?"
    };
  }
  return {
    words: ["already", "I", "have", "finished", "it"],
    answer: "I have already finished it."
  };
}

function inferSentenceBuilderRequest(message = "") {
  const text = String(message || "").replace(/\s+/g, " ").trim();
  const wordsMatch = /\bwords?\s*(?::|：|\bis\b\s*[:：]?|\bare\b\s*[:：]?)\s*(.+?)(?=\s*[;；,，。]?\s*(?:correct\s+sentence|answer|\u7b54\u6848|\u6b63\u786e\u7b54\u6848|only|no|without|with\s+no|\u4e0d\u8981|\u4e0d\u9700\u8981|\u65e0)\b|$)/i.exec(text) ||
    /(?:\u8fde\u8bcd\u6210\u53e5|\u7ec4\u53e5|\u6392\u5e8f\u9898?)\s*(?:\u662f|\u4e3a)?\s*[:：]\s*(.+?)(?=\s*[;；,，。]?\s*(?:\u7b54\u6848|answer|\u6b63\u786e\u7b54\u6848|\u6b63\u786e\u53e5\u5b50|only|no|without|\u4e0d\u8981|\u4e0d\u9700\u8981|\u65e0|$))/i.exec(text) ||
    /\u8bcd\u5757\s*(?:\u662f|\u4e3a)?\s*[:：]?\s*(.+?)(?=\s*[;；,，。]?\s*(?:\u7b54\u6848|\u6b63\u786e\u7b54\u6848|answer|\u6b63\u786e\u53e5\u5b50|correct\s+sentence|only|no|without|\u4e0d\u8981|\u4e0d\u9700\u8981|\u65e0|$))/i.exec(text);
  const answerMatch = /\b(?:correct\s+sentences?|answer)\b\s*(?:[:：]|\bis\b\s*[:：]?)?\s*(.+?)(?=\s+(?:only|no|without|with\s+no|不要|不需要|无)\b|$)/i.exec(text) ||
    /(?:\u7b54\u6848|\u6b63\u786e\u7b54\u6848|\u6b63\u786e\u53e5\u5b50)\s*(?:\u662f|\u4e3a)?\s*[:：]?\s*(.+?)(?=\s+(?:only|no|without|with\s+no)\b|\s*(?:\u53ea\u8981|\u4e0d\u8981|\u4e0d\u9700\u8981|\u65e0)|$)/i.exec(text);
  const words = wordsMatch?.[1]
    ? uniqueAgentCardTexts(wordsMatch[1].split(/\s*(?:\/|\||,|，|、|;|；)\s*/).map(cleanSentenceBuilderWord), 80)
    : [];
  const answer = answerMatch?.[1] ? cleanSentenceBuilderAnswer(answerMatch[1]) : "";
  return { words, answer };
}

function sentenceBuilderWordsCoverAnswer(words = [], answer = "") {
  const normalizedWords = normalizeSentenceBuilderWords(words);
  const normalizedAnswer = normalizeSentenceBuilderWords(String(answer || "").replace(/[.!?。！？]+$/g, "").split(/\s+/));
  if (normalizedWords.length < 2 || !normalizedAnswer.length) return false;
  const counts = new Map();
  normalizedWords.forEach((word) => counts.set(word, (counts.get(word) || 0) + 1));
  return normalizedAnswer.every((word) => {
    const count = counts.get(word) || 0;
    if (count <= 0) return false;
    counts.set(word, count - 1);
    return true;
  });
}

function normalizeSentenceBuilderWords(words = []) {
  return normalizeAgentStringArray(words, 80)
    .flatMap((word) => String(word || "").split(/\s+/))
    .map((word) => word.replace(/^[^a-z0-9]+|[^a-z0-9]+$/gi, "").toLowerCase())
    .filter(Boolean);
}

function cleanSentenceBuilderWord(value = "") {
  return compactAgentCardText(value, 80)
    .replace(/^(?:\u662f|\u4e3a)\s*[:：]\s*/, "")
    .replace(/^[.?!。？！,，;；:："'“”‘’()[\]{}]+|[.?!。？！,，;；:："'“”‘’()[\]{}]+$/g, "")
    .trim();
}

function cleanSentenceBuilderAnswer(value = "") {
  return compactAgentCardText(value, 220)
    .replace(/^(?:\u662f|\u4e3a)\s*[:：]\s*/, "")
    .replace(/^[.?!。？！,，;；:："'“”‘’()[\]{}]+/, "")
    .trim();
}

function enforceSentenceBuilderNoUsageTextScope(component = {}) {
  if (component?.type !== "sentence_builder") return component;
  return {
    ...component,
    text: stripSentenceBuilderUsageInstruction(component.text || "")
  };
}

module.exports = {
  isSentenceBuilderNegatedRequest,
  isSentenceBuilderOnlyAgentCardRequest,
  isSentenceBuilderRequiredScope,
  buildRequiredSentenceBuilderComponent,
  fallbackSentenceBuilderRequest,
  inferSentenceBuilderRequest,
  sentenceBuilderWordsCoverAnswer,
  normalizeSentenceBuilderWords,
  cleanSentenceBuilderWord,
  cleanSentenceBuilderAnswer,
  enforceSentenceBuilderNoUsageTextScope,
};
