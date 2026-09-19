// 改错(correction)卡片：改错意图判定、错因条目解析/格式化、按请求数量补齐、核心范围裁剪、
// 源句推断与短答清洗。一组内聚的纯逻辑。原内联于 mimoText.js（1 万行）。

const { AGENT_CORRECTION_INTENT_PATTERN } = require("./agentCardPatterns");
const { compactAgentCardText } = require("./agentCardText");
const { uniqueAgentCardTexts } = require("./explicitConstraints");
const { firstCountMatch } = require("./listeningGenerate");
const { stripCorrectionGrammarExplanation } = require("./textNormalize");

function mergeMistakePatternIntoCorrectionOnlyCard(components = [], scope = {}) {
  if (!isCorrectionOnlyScope(scope)) return components;
  const correctionIndex = components.findIndex((component) => component?.type === "correction");
  const mistakeComponents = components.filter((component) => component?.type === "mistake_pattern");
  const feedbackComponents = components.filter((component) => component?.type === "feedback");
  if (correctionIndex < 0) return components;
  const correction = components[correctionIndex];
  const explicitMistakeItems = [...mistakeComponents, ...feedbackComponents]
    .flatMap((component) => [
      ...(Array.isArray(component.items) ? component.items : []),
      component.text || ""
    ])
    .map(formatCorrectionMistakeItem)
    .filter(Boolean);
  const mistakeItems = explicitMistakeItems.length
    ? explicitMistakeItems
    : inferCorrectionMistakeItems(correction, scope).map(formatCorrectionMistakeItem).filter(Boolean);
  if (!mistakeItems.length) return components.filter((component) => component?.type !== "mistake_pattern");
  const mergedCorrection = {
    ...correction,
    items: uniqueAgentCardTexts([
      ...(Array.isArray(correction.items) ? correction.items : []),
      ...mistakeItems
    ], 220)
  };
  return components
    .map((component, index) => index === correctionIndex ? mergedCorrection : component)
    .filter((component) => component?.type !== "mistake_pattern" && component?.type !== "feedback");
}

function isCorrectionOnlyScope(scope = {}) {
  const text = String(scope?.messageText || "").toLowerCase();
  return /(?:only|just)\s+correction(?:\s+card)?|correction\s+(?:only|card\s+only)|只要.{0,12}(?:纠错|改错|修正)|仅.{0,12}(?:纠错|改错|修正)/i.test(text);
}

function isCorrectionOnlyAgentCardRequest(message = "") {
  const text = String(message || "").toLowerCase();
  return /(?:only|just)\s+(?:correction|rewrite|error\s*fix)(?:\s+card)?|(?:correction|rewrite|error\s*fix)\s+(?:only|card\s+only)|只要.{0,12}(?:改正|纠错|改错|修正)|仅.{0,12}(?:改正|纠错|改错|修正)/i.test(text);
}

function isEditableCorrectionShortAnswerRequest(message = "") {
  const text = String(message || "").toLowerCase();
  if (!AGENT_CORRECTION_INTENT_PATTERN.test(text)) return false;
  return /可\s*输入|输入答案|打字|typed\s*answer|text\s*input|input\s*answer|short\s*answer|rewrite(?:\s+it)?\s+(?:yourself|as\s+an\s+answer)|type\s+(?:the\s+)?correct(?:ed)?\s+sentence/i.test(text);
}

function formatCorrectionMistakeItem(value = "") {
  const clean = compactAgentCardText(value, 200);
  if (!clean) return "";
  return /^(?:mistake|error|错误|错因)\s*[:：]/i.test(clean) ? clean : `Mistake: ${clean}`;
}

function inferCorrectionMistakeItems(correction = {}, scope = {}) {
  const message = String(scope?.messageText || "");
  if (!/mistake|error|错误|错因/i.test(message)) return [];
  const items = Array.isArray(correction.items) ? correction.items : [];
  return uniqueAgentCardTexts(items.map(inferCorrectionMistakeFromItem).filter(Boolean), 200);
}

function inferCorrectionMistakeFromItem(value = "") {
  const text = compactAgentCardText(value, 220);
  if (!text) return "";
  const [originalRaw, correctedRaw] = text.split(/\s*(?:→|->|=>)\s*/);
  const original = compactAgentCardText(originalRaw || "", 180);
  const corrected = compactAgentCardText(correctedRaw || "", 180);
  if (!original || !corrected) return "";
  if (/\bgo\b/i.test(original) && /\bwent\b/i.test(corrected) && /\byesterday\b/i.test(original)) {
    return "Used base form 'go' instead of past tense 'went' for a past event ('yesterday').";
  }
  if (/\bgo\b/i.test(original) && /\bwent\b/i.test(corrected)) {
    return "Used base form 'go' instead of past tense 'went' for a past event.";
  }
  if (/\bdont\b|\bdon't\b/i.test(original) && /\bdoesn['’]?t\b|\bdoes not\b/i.test(corrected)) {
    return "Used 'don't' instead of \"doesn't\" for a third-person singular subject.";
  }
  return "";
}

function correctionFallbackItems(message = "") {
  const text = String(message || "").toLowerCase();
  if (/tense|时态/.test(text)) {
    return [
      "I go to school yesterday. -> I went to school yesterday.",
      "She don't finish it. -> She didn't finish it."
    ];
  }
  return [];
}

function inferAgentRequestedCorrectionCount(message = "") {
  const text = String(message || "").toLowerCase();
  const numberToken = "([0-9]{1,2}|[一二两兩俩倆三四五六七八九十]{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)";
  const patterns = [
    new RegExp(`(?:改错|纠错|改正|修正)\\s*${numberToken}\\s*(?:个|個|条|條)?\\s*(?:句|句子)?`, "i"),
    new RegExp(`${numberToken}\\s*(?:个|個|条|條)?\\s*(?:sentences?|items?|句|句子).{0,12}(?:correction|correct|rewrite|改错|纠错|改正|修正)`, "i"),
    new RegExp(`${numberToken}\\s+(?:corrections?|rewrites?|error\\s*corrections?)\\b`, "i")
  ];
  return firstCountMatch(text, patterns, 1, 12);
}

function enforceCorrectionCoreOnlyScope(component = {}, scope = {}) {
  if (component?.type !== "correction") return component;
  const excluded = scope.excluded instanceof Set ? scope.excluded : new Set(scope.excluded || []);
  if (!scope.suppressExtraSuggestions && !excluded.has("grammar")) return component;
  return {
    ...component,
    text: "",
    items: Array.isArray(component.items)
      ? component.items.map((item) => stripCorrectionGrammarExplanation(item)).filter(Boolean)
      : component.items
  };
}

function inferCorrectionSourceSentenceFromMessage(text = "") {
  const patterns = [
    /(?:把|将)\s+(.+?)\s+(?:改(?:成|为)?正确|改正|修正|纠正)/i,
    /(?:correct|fix|rewrite)\s+(?:this\s+sentence\s*[:：]?\s*)?(.+?)(?=\s+(?:as|into|with|using|only|no\s+|without|不要|不需要|$))/i
  ];
  for (const pattern of patterns) {
    const match = pattern.exec(text);
    const sentence = cleanExplicitShortAnswer(match?.[1] || "");
    if (sentence) return sentence;
  }
  return "";
}

function cleanExplicitShortAnswer(value = "") {
  return compactAgentCardText(value, 220)
    .replace(/[。！？；;]+$/g, "")
    .trim();
}

module.exports = {
  mergeMistakePatternIntoCorrectionOnlyCard,
  isCorrectionOnlyScope,
  isCorrectionOnlyAgentCardRequest,
  isEditableCorrectionShortAnswerRequest,
  formatCorrectionMistakeItem,
  inferCorrectionMistakeItems,
  inferCorrectionMistakeFromItem,
  correctionFallbackItems,
  inferAgentRequestedCorrectionCount,
  enforceCorrectionCoreOnlyScope,
  inferCorrectionSourceSentenceFromMessage,
  cleanExplicitShortAnswer,
};
