// 排序/排列练习卡片（段落排序、句子排序、逻辑排序）的意图识别、组件构建、序号解析与
// 兜底项。一组内聚的纯逻辑。历史上内联在 mimoText.js（1 万行），现抽成独立模块。

const { compactAgentCardText, sanitizeAgentCardText } = require("./agentCardText");
const { uniqueAgentCardTexts } = require("./explicitConstraints");
const { normalizeAgentAnswerLookupText, normalizeAgentStringArray } = require("./textNormalize");

function isParagraphOrderingReadingRequest(text = "") {
  return isOrderingCardRequest(text);
}

function isOrderingCardRequest(text = "") {
  const value = String(text || "").toLowerCase();
  if (!value.trim()) return false;
  if (/no\s+(?:logical\s+)?ordering|without\s+(?:logical\s+)?ordering|no\s+sequenc(?:e|ing)|without\s+sequenc(?:e|ing)/i.test(value)) return false;
  const serviceOrdering = /\border(?:ing)?\b.{0,40}(?:coffee|latte|drink|food|meal|restaurant|cafe|caf[eé]|barista|takeout|to\s+go)|(?:coffee|latte|drink|food|meal|restaurant|cafe|caf[eé]|barista|takeout|to\s+go).{0,40}\border(?:ing)?\b/i.test(value);
  const sequenceContext = /paragraph|sentence|logical|logic|sequence|sequencing|correct\s+order|events?|timeline|reorder\s+(?:sentences?|paragraphs?)|段落|句子|短文|篇章|逻辑|事件|时间|发展|顺序/.test(value);
  if (serviceOrdering && !sequenceContext) return false;
  const explicitOrdering = /ordering|sequenc(?:e|ing)|paragraph\s*order|logical\s*order|logic\s*order|sentence\s*sequenc(?:e|ing)|sequence\s*order/i.test(value);
  const chineseOrdering = /段落排序|篇章排序|逻辑排序|句子排序|排顺序|正确顺序|按(?:事件|逻辑|时间|发展).{0,8}顺序|排序.{0,8}(?:段落|句子|短文|篇章)|(?:段落|句子|短文|篇章).{0,8}排序/.test(value);
  const explicitNotWordOrder = /no\s+sentence\s*builder|not\s+sentence\s*builder|不是.{0,8}(?:sentence\s*builder|word\s*order|词语排序|词块排序|连词成句|组句)|非.{0,8}(?:词语排序|词块排序|连词成句|组句)|不要.{0,8}(?:词语排序|词块排序|连词成句|组句)|别.{0,8}(?:词语排序|词块排序|连词成句|组句)/.test(value);
  const wordOrderOnly = /word\s*order|reorder\s+words?|sentence\s*builder|组句|连词成句|单词顺序|词语排序|词块排序/.test(value) && !explicitNotWordOrder;
  return (explicitOrdering || chineseOrdering) && !wordOrderOnly;
}

function isOrderingRequiredScope(scope = {}) {
  return scope.allowed instanceof Set &&
    scope.allowed.has("ordering") &&
    !scope.multiQuestionChoice &&
    !scope.multiFillBlankQuestions &&
    !scope.choiceConstraint &&
    !scope.clozeConstraint?.strict &&
    isOrderingCardRequest(scope.messageText || "");
}

function buildRequiredOrderingComponent(existing = {}, scope = {}) {
  const request = inferOrderingRequest(scope?.messageText || "");
  const existingItems = normalizeAgentStringArray(existing.items, agentCardItemMaxLength("ordering"));
  const existingAnswerParts = agentCardOrderingAnswerParts(existing.answer || existing.correctAnswer || "");
  const answerParts = request.explicit && request.answerParts.length >= 2
    ? request.answerParts
    : (existingAnswerParts.length >= 2 ? existingAnswerParts : request.answerParts);
  const items = request.explicit && request.items.length >= 2
    ? request.items
    : existingItems.length >= 2
    ? existingItems
    : deterministicOrderingFallbackItems(answerParts.length >= 2 ? answerParts : request.items);
  const finalAnswerParts = answerParts.length >= 2 ? answerParts : orderOrderingItemsBySequenceMarkers(items);
  const answer = (finalAnswerParts.length >= 2 ? finalAnswerParts : items).join(" | ");
  return {
    ...existing,
    type: "ordering",
    title: "Ordering",
    text: "Put the sentences or paragraphs in the correct order.",
    items,
    tokens: [],
    answer,
    correctAnswer: answer,
    explanation: existing.explanation || "First/Then/Finally 等顺序线索可以帮助判断先后。"
  };
}

function buildFallbackOrderingComponent(message = "", scope = {}) {
  return buildRequiredOrderingComponent({}, {
    ...(scope || {}),
    messageText: scope?.messageText || message,
    allowed: scope?.allowed instanceof Set ? scope.allowed : new Set(["ordering"])
  });
}

function inferOrderingRequest(message = "") {
  const text = String(message || "").replace(/\s+/g, " ").trim();
  const explicitChineseItems = extractOrderingPayload(text, /(?:\u4e71\u5e8f(?:\u53e5\u5b50|\u53e5|\u6bb5\u843d|\u9879)?|\u5f85\u6392\u5e8f(?:\u53e5\u5b50|\u53e5|\u6bb5\u843d|\u9879)?|\u53e5\u5b50|\u6bb5\u843d)\s*(?:\u662f|\u4e3a|[:：])\s*(.+?)(?=\s*(?:\u6b63\u786e\u987a\u5e8f|\u7b54\u6848|correct\s+order|answer)\s*(?:\u662f|\u4e3a|[:：])|\s+(?:only|no|without)\b|$)/i);
  const explicitChineseAnswer = extractOrderingPayload(text, /(?:\u6b63\u786e\u987a\u5e8f|\u7b54\u6848)\s*(?:\u662f|\u4e3a|[:：])\s*(.+?)(?=\s*(?:\u53ea\u8981|\u4e0d\u8981|\u4e0d\u9700\u8981|\u65e0|\u522b|only\b|no\s+|without\b|$))/i);
  if (explicitChineseItems.length >= 2 || explicitChineseAnswer.length >= 2) {
    const answerParts = explicitChineseAnswer.length >= 2 ? explicitChineseAnswer : orderOrderingItemsBySequenceMarkers(explicitChineseItems);
    return {
      items: explicitChineseItems.length >= 2 ? explicitChineseItems : deterministicOrderingFallbackItems(answerParts),
      answerParts: answerParts.length >= 2 ? answerParts : explicitChineseItems,
      explicit: true
    };
  }
  const explicitItems = extractOrderingPayload(text, /(?:items?|sentences?|paragraphs?|句子|段落)\s*(?::|：)?\s*(.+?)(?=\s+(?:answer|correct\s+order|答案|正确顺序|only|no|without|不要|不需要|无)\b|$)/i);
  const explicitAnswer = extractOrderingPayload(text, /(?:answer|correct\s+order|答案|正确顺序)\s*(?::|：)?\s*(.+?)(?=\s+(?:only|no|without|不要|不需要|无)\b|$)/i);
  const putInOrderItems = extractOrderingPayload(text, /\bput\s+(?:these\s+)?(?:[0-9]{1,2}\s+)?(?:events?|sentences?|paragraphs?|items?)\s+in\s+order\s*[:：]?\s*(.+?)(?=\s+(?:answer|correct\s+order|only|no|without|不要|不需要|无)\b|$)/i);
  if (putInOrderItems.length >= 2 && explicitAnswer.length < 2) {
    return {
      items: deterministicOrderingFallbackItems(putInOrderItems),
      answerParts: putInOrderItems,
      explicit: true
    };
  }
  if (explicitItems.length >= 2 || explicitAnswer.length >= 2) {
    const answerParts = explicitAnswer.length >= 2 ? explicitAnswer : orderOrderingItemsBySequenceMarkers(explicitItems);
    return {
      items: explicitItems.length >= 2 ? explicitItems : deterministicOrderingFallbackItems(answerParts),
      answerParts: answerParts.length >= 2 ? answerParts : explicitItems,
      explicit: true
    };
  }
  const tail = text
    .replace(/^.*?(?:按事件发展顺序排列|按逻辑顺序排列|按事件顺序排(?:一下|列)?|按逻辑顺序排(?:一下|列)?|paragraph\s*ordering|logical\s*ordering|ordering|sequence|段落排序|篇章排序|逻辑排序|句子排序|排序)[:：]?\s*/i, "")
    .replace(/\b(?:only|no|without|不要|不需要|无)\b[\s\S]*$/i, "")
    .trim();
  const looseItems = splitOrderingParts(tail)
    .filter((item) => item.split(/\s+/).length >= 2 || /[。！？.!?]$/.test(item))
    .slice(0, 8);
  const answerParts = orderOrderingItemsBySequenceMarkers(looseItems);
  const fallback = defaultOrderingAnswerParts();
  return {
    items: looseItems.length >= 2 ? looseItems : defaultOrderingItems(),
    answerParts: answerParts.length >= 2 ? answerParts : (looseItems.length >= 2 ? looseItems : fallback),
    explicit: looseItems.length >= 2
  };
}

function extractOrderingPayload(text = "", pattern) {
  const match = pattern.exec(text);
  return match?.[1] ? splitOrderingParts(match[1]) : [];
}

function splitOrderingParts(raw = "") {
  const value = sanitizeAgentCardText(raw).replace(/\s+/g, " ").trim();
  if (!value) return [];
  const delimited = value.split(/\s*(?:\||；|;)\s*/)
    .map(cleanOrderingPart)
    .filter(Boolean);
  if (delimited.length >= 2) return uniqueAgentCardTexts(delimited, agentCardItemMaxLength("ordering")).slice(0, 8);
  const sentences = Array.from(value.matchAll(/[^.!?。！？]+[.!?。！？]/g))
    .map((match) => cleanOrderingPart(match[0]))
    .filter(Boolean);
  return uniqueAgentCardTexts(sentences, agentCardItemMaxLength("ordering")).slice(0, 8);
}

function cleanOrderingPart(value = "") {
  return compactAgentCardText(value, agentCardItemMaxLength("ordering"))
    .replace(/^(?:card\s+)?(?:items?|sentences?|paragraphs?|answer|correct\s+order)\s*[:：-]?\s*/i, "")
    .replace(/^(?:\u4e71\u5e8f(?:\u53e5\u5b50|\u53e5|\u6bb5\u843d|\u9879)?|\u5f85\u6392\u5e8f(?:\u53e5\u5b50|\u53e5|\u6bb5\u843d|\u9879)?|\u53e5\u5b50|\u6bb5\u843d|\u6b63\u786e\u987a\u5e8f|\u7b54\u6848)\s*(?:\u662f|\u4e3a|[:：-])?\s*/i, "")
    .replace(/^[:：\s-]+/, "")
    .replace(/^[\dA-Z]\s*[.)、:：-]\s*/i, "")
    .trim();
}

function orderOrderingItemsBySequenceMarkers(items = []) {
  if (!Array.isArray(items) || items.length < 2) return [];
  const ranked = items.map((item, index) => {
    const text = String(item || "");
    let rank = null;
    if (/\bfirst\b|首先|第一|起初/i.test(text)) rank = 0;
    else if (/\bthen\b|\bnext\b|之后|然后|接着/i.test(text)) rank = 1;
    else if (/\bfinally\b|\blastly\b|最后|最终/i.test(text)) rank = 2;
    return rank === null ? null : { rank, index, item };
  });
  if (ranked.some((entry) => !entry)) return [];
  return ranked.sort((a, b) => a.rank - b.rank || a.index - b.index).map((entry) => entry.item);
}

function deterministicOrderingFallbackItems(answerParts = []) {
  const clean = uniqueAgentCardTexts((answerParts || []).filter(Boolean), agentCardItemMaxLength("ordering")).slice(0, 8);
  if (clean.length <= 1) return clean;
  return [clean[clean.length - 1], ...clean.slice(0, -1)];
}

function defaultOrderingAnswerParts() {
  return [
    "First, we booked a room.",
    "Then, we showed our passports.",
    "Finally, we checked out."
  ];
}

function defaultOrderingItems() {
  return [
    "Finally, we checked out.",
    "First, we booked a room.",
    "Then, we showed our passports."
  ];
}

function agentCardOrderingAnswerParts(answer = "") {
  const raw = String(answer || "").trim();
  if (!raw) return [];
  const pipeParts = raw.split(/\s*\|\s*/)
    .map((item) => item.trim())
    .filter(Boolean);
  if (pipeParts.length >= 2) return pipeParts;
  return Array.from(raw.matchAll(/[^.!?。！？]+[.!?。！？]/g))
    .map((match) => match[0].trim())
    .filter(Boolean);
}

function agentCardOrderingAnswerMatchesItems(answer = "", items = []) {
  const cleanItems = Array.isArray(items)
    ? items.map(normalizeAgentAnswerLookupText).filter(Boolean)
    : [];
  const answerParts = agentCardOrderingAnswerParts(answer);
  if (cleanItems.length < 2 || answerParts.length !== cleanItems.length) return false;
  const itemCounts = countNormalizedValues(cleanItems);
  const answerCounts = countNormalizedValues(answerParts.map(normalizeAgentAnswerLookupText));
  if (itemCounts.size !== answerCounts.size) return false;
  for (const [key, count] of itemCounts.entries()) {
    if (answerCounts.get(key) !== count) return false;
  }
  return true;
}

function countNormalizedValues(values = []) {
  const counts = new Map();
  values.forEach((value) => counts.set(value, (counts.get(value) || 0) + 1));
  return counts;
}

function agentCardItemMaxLength(type = "") {
  if (type === "ordering") return 260;
  if (["minimal_pair", "word_family", "sentence_builder"].includes(type)) return 80;
  if (type === "pronunciation") return 180;
  if (type === "register") return 240;
  return 120;
}

module.exports = {
  isParagraphOrderingReadingRequest,
  isOrderingCardRequest,
  isOrderingRequiredScope,
  buildRequiredOrderingComponent,
  buildFallbackOrderingComponent,
  inferOrderingRequest,
  extractOrderingPayload,
  splitOrderingParts,
  cleanOrderingPart,
  orderOrderingItemsBySequenceMarkers,
  deterministicOrderingFallbackItems,
  defaultOrderingAnswerParts,
  defaultOrderingItems,
  agentCardOrderingAnswerParts,
  agentCardOrderingAnswerMatchesItems,
  countNormalizedValues,
  agentCardItemMaxLength,
};
