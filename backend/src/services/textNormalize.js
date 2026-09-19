// 从 LLM 文本/卡片字段里做保守清洗与规范化的纯函数集合（无副作用、零模块状态）。
// 历史上内联在 mimoText.js（1 万行），现抽成独立模块——大文件拆分的又一刀(A: 文本清洗 helper)。
// 仅依赖 agentCardText 的两个文本压缩工具；mimoText 改为从这里 require 这些函数，调用方零感知。

const { compactAgentCardText, trimDanglingAgentCardWords } = require("./agentCardText");

function normalizeAgentAnswerLookupText(value = "") {
  return String(value || "").trim().toLowerCase().replace(/\s+/g, " ");
}

function normalizeAgentStringArray(value, maxLength = 80) {
  if (!Array.isArray(value)) return [];
  return value
    .map((entry) => compactAgentCardText(entry, maxLength))
    .filter(Boolean);
}

function normalizeShortAnswerLeakText(value = "") {
  return String(value || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function cleanAgentQuestionText(value = "", maxLength = 180) {
  return compactAgentCardText(String(value || "").replace(/^\s*\d{1,2}[\).、]\s+/, ""), maxLength);
}

function cleanAgentClozeInstructionLeak(value = "") {
  return String(value || "")
    .replace(/\s+(?:exactly\s+)?[0-9一二两三四五六七八九十]+\s+(?:\w+\s+){0,2}blanks?\b[\s\S]*$/i, "")
    .trim();
}

function stripAgentQuestionOptionLabel(value = "") {
  return compactAgentCardText(String(value || "").replace(/^\s*[A-Da-d][\.)、:：]\s+/, ""), 140);
}

function stripAgentReplyDecorativeEmoji(value = "") {
  return String(value || "")
    .replace(/[📄📎✅🎧🎯✨]/gu, "")
    .replace(/\s+/g, " ")
    .trim();
}

function stripNoAudioPlaybackSentences(value = "") {
  const parts = String(value || "").match(/[^.!?。！？]+[.!?。！？]?/g) || [String(value || "")];
  return parts
    .map((part) => part.trim())
    .filter(Boolean)
    .filter((part) => !/\b(?:hear|listen|play|audio|repeat)\b|(?:tap|click)\s+to\b|(?:听写|听到|听见|播放|收听|音频|跟读|复述)/i.test(part))
    .join(" ")
    .trim();
}

function stripCorrectionGrammarExplanation(value = "") {
  return compactAgentCardText(value, 220)
    .replace(/\s*\((?:use|uses|using|because|for|to|when|with|add|remove|change|choose|match|keep|make)\b[^)]*\)\s*$/i, "")
    .replace(/\s*\([^)]*(?:tense|subject|verb|singular|plural|auxiliary|article|preposition|grammar|form|past|present|future)[^)]*\)\s*$/i, "")
    .trim();
}

function stripQuestionSetExplanationsForScope(component = {}, scope = {}) {
  if (!(scope?.suppressExtraSuggestions || scope?.suppressAnswerExplanation) || component?.type !== "question_set" || !Array.isArray(component.questions)) return component;
  return {
    ...component,
    questions: component.questions.map((question) => ({
      ...question,
      explanation: ""
    }))
  };
}

function stripSentenceBuilderUsageInstruction(value = "") {
  const text = compactAgentCardText(value, 220);
  if (!text) return "";
  return /\b(?:drag|tap|click|arrange|reorder|build|form|put)\b/i.test(text) &&
    /\b(?:word|words|sentence)\b/i.test(text)
    ? ""
    : text;
}

function trimIncompleteAgentCardSentence(value = "") {
  const text = String(value || "").trim();
  if (!text) return "";
  const endings = Array.from(text.matchAll(/[.!?。！？](?=\s|$)/g))
    .filter((match) => !/\d/.test(text.charAt((match.index ?? 0) - 1)));
  const last = endings.length ? endings[endings.length - 1].index : -1;
  if (last >= Math.max(12, Math.floor(text.length * 0.45)) && last < text.length - 1) {
    return text.slice(0, last + 1).trim();
  }
  return trimDanglingAgentCardWords(text)
    .replace(/[\s,;，。、；:：|/-]+$/g, "")
    .trim();
}

module.exports = {
  normalizeAgentAnswerLookupText,
  normalizeAgentStringArray,
  normalizeShortAnswerLeakText,
  cleanAgentQuestionText,
  cleanAgentClozeInstructionLeak,
  stripAgentQuestionOptionLabel,
  stripAgentReplyDecorativeEmoji,
  stripNoAudioPlaybackSentences,
  stripCorrectionGrammarExplanation,
  stripQuestionSetExplanationsForScope,
  stripSentenceBuilderUsageInstruction,
  trimIncompleteAgentCardSentence,
};
