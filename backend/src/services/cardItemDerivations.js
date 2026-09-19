// 从「条目数组」派生卡片各结构：选项/词块/配对/带标签对象，配套的配对文本切分与字数上限，
// 以及从对话条目推断说话人数。一组内聚的纯函数。历史上内联在 mimoText.js（1 万行），现抽出。

const { compactAgentCardText, sanitizeAgentCardText } = require("./agentCardText");
const { normalizeAgentAnswerLookupText } = require("./textNormalize");

function inferScenarioParticipantCountFromItems(scenario = {}) {
  const items = Array.isArray(scenario.items) ? scenario.items : [];
  const labels = new Set();
  items.forEach((item) => {
    const match = /^\s*([^:：-]{2,40})\s*[:：-]/.exec(String(item || ""));
    if (match?.[1]) labels.add(normalizeAgentAnswerLookupText(match[1]));
  });
  return labels.size || null;
}

function agentCardPairTextLimits(type = "") {
  if (["phrase", "scenario", "register", "translation", "compare"].includes(type)) {
    return { left: 120, right: 140, hint: 100, summary: 180 };
  }
  return { left: 34, right: 34, hint: 42, summary: 80 };
}

function deriveAgentCardPairsFromItems(type, items = []) {
  if (type === "translation") {
    return items.map((item) => {
      const pair = splitAgentCardPairText(item);
      const left = compactAgentCardText(pair.left, agentCardPairTextLimits(type).left);
      const right = compactAgentCardText(pair.right, agentCardPairTextLimits(type).right);
      if (!left || !right) return null;
      return { left, right, hint: "" };
    }).filter(Boolean).slice(0, 8);
  }
  if (type !== "minimal_pair") return [];
  return items.map((item) => {
    const row = sanitizeAgentCardText(item);
    const { main, hint } = splitAgentCardRowHint(row);
    const pair = splitAgentCardPairText(main);
    if (!pair.left && !pair.right && !hint) return null;
    return {
      left: compactAgentCardText(pair.left, 34),
      right: compactAgentCardText(pair.right, 34),
      hint: compactAgentCardText(hint, 42)
    };
  }).filter(Boolean).slice(0, 8);
}

function deriveAgentCardTokensFromItems(type, items = []) {
  if (type !== "word_family") return [];
  const firstUseful = items
    .map((item) => splitAgentCardTokensText(item))
    .find((tokens) => tokens.length > 1);
  return firstUseful ? firstUseful.slice(0, 12) : [];
}

function deriveAgentCardOptionsFromItems(type, component = {}) {
  if (!["cloze", "speaking_prompt"].includes(type)) return [];
  const items = Array.isArray(component.items) ? component.items : [];
  const optionItems = type === "cloze" && !component.text ? items.slice(1) : items;
  return optionItems.map((item) => compactAgentCardText(item, 120)).filter(Boolean).slice(0, 8);
}

function deriveAgentCardLabeledObjectsFromItems(type, items = [], field = "") {
  if (field === "steps" && type !== "writing_outline") return [];
  if (field === "criteria" && type !== "rubric") return [];
  return items.map((item) => {
    const pair = splitAgentCardPairText(item);
    if (!pair.left && !pair.right) return null;
    return {
      label: compactAgentCardText(pair.left, 28),
      text: compactAgentCardText(pair.right, 180)
    };
  }).filter((item) => item && (item.label || item.text)).slice(0, 8);
}

function splitAgentCardRowHint(row) {
  const text = sanitizeAgentCardText(row);
  const separators = [" — ", " – ", " - "];
  for (const separator of separators) {
    const index = text.indexOf(separator);
    if (index > 0) {
      return {
        main: text.slice(0, index).trim(),
        hint: text.slice(index + separator.length).trim()
      };
    }
  }
  return { main: text, hint: "" };
}

function splitAgentCardPairText(row) {
  const text = sanitizeAgentCardText(row);
  const separators = [" -> ", " → ", " | ", " - ", "：", ":"];
  for (const separator of separators) {
    const index = text.indexOf(separator);
    if (index > 0) {
      return {
        left: text.slice(0, index).trim(),
        right: text.slice(index + separator.length).trim()
      };
    }
  }
  return { left: text, right: "" };
}

function splitAgentCardTokensText(row) {
  return sanitizeAgentCardText(row)
    .split(/\s*(?:\||、|,|，|\/)\s*/)
    .map((item) => compactAgentCardText(item, 32))
    .filter(Boolean);
}

module.exports = {
  inferScenarioParticipantCountFromItems,
  agentCardPairTextLimits,
  deriveAgentCardPairsFromItems,
  deriveAgentCardTokensFromItems,
  deriveAgentCardOptionsFromItems,
  deriveAgentCardLabeledObjectsFromItems,
  splitAgentCardRowHint,
  splitAgentCardPairText,
  splitAgentCardTokensText,
};
