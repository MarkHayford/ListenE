"use strict";

// 纯文本/格式化工具：从 mimoText.js 抽出的零依赖叶子函数（仅依赖彼此与正则，不引用任何模块常量或业务逻辑）。
// 行为与原实现保持一致，便于 mimoText.js 复用并降低单文件体量。

function fixBasicEnglishArticleText(value = "") {
  return String(value || "")
    .replace(/\bA\s+(?=(?:office|email|hour|honest|honor|umbrella|apple|orange|old|early|urgent|interesting|important|easy|English|American)\b)/g, "An ")
    .replace(/\ba\s+(?=(?:office|email|hour|honest|honor|umbrella|apple|orange|old|early|urgent|interesting|important|easy|english|american)\b)/g, "an ");
}

function sanitizeAgentCardText(value) {
  return fixBasicEnglishArticleText(String(value || "")
    .replace(/听写/g, "精听")
    .replace(/跟读纠错/g, "句子定位")
    .replace(/跟读/g, "细听")
    .replace(/复述/g, "细听")
    .trim());
}

function compactAgentCardText(value, maxLength = 80) {
  const text = sanitizeAgentCardText(value).replace(/\s+/g, " ").trim();
  if (text.length <= maxLength) return text;
  const suffix = "...";
  const limit = Math.max(1, maxLength - suffix.length);
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
    .replace(/\b(and|or|to|of|for|with|the|a|an)$/i, "")
    .trim();
  const trimmed = compacted.length > limit
    ? compacted.slice(0, limit).replace(/[\s,;，。、；:：|/-]+$/g, "").trim()
    : compacted;
  return `${trimmed || text.slice(0, limit).trim()}${suffix}`;
}

function trimDanglingAgentCardWords(value) {
  return sanitizeAgentCardText(value)
    .replace(/\s+/g, " ")
    .replace(/\s*(?:&|\/|\+|-|—|–)\s*$/g, "")
    .replace(/\s*(?:—|–|-)\.{1,3}\s*$/g, "")
    .replace(/\.{2}\s*$/g, ".")
    .replace(/\bvs\.?$/i, "")
    .replace(/\b(and|or|to|of|for|with|the|a|an)$/i, "")
    .trim();
}

function compactAgentCardLabel(value, maxLength = 24) {
  return trimDanglingAgentCardWords(compactAgentCardText(value, maxLength));
}

function cleanAgentCardDanglingPunctuation(value = "") {
  return trimDanglingAgentCardWords(value)
    .replace(/\s*(?:—|–|-)\.{1,3}\s*$/g, "")
    .replace(/\.{2,}\s*$/g, ".")
    .trim();
}

function escapeRegExp(value = "") {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function sanitizeAgentCardSource(value) {
  const source = String(value || "").trim().slice(0, 80);
  return /^[A-Za-z0-9_.:-]+$/.test(source) ? source : undefined;
}

module.exports = {
  fixBasicEnglishArticleText,
  sanitizeAgentCardText,
  compactAgentCardText,
  trimDanglingAgentCardWords,
  compactAgentCardLabel,
  cleanAgentCardDanglingPunctuation,
  escapeRegExp,
  sanitizeAgentCardSource
};
