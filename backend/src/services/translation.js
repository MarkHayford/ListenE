// 翻译(translation)卡片：显式翻译范围裁剪、按术语构建配对、组件配对抽取、指令泄漏判定、
// 兜底译文、按请求数量补齐、以及调用 MiMo 生成主题化翻译内容并套用。原内联于 mimoText.js。

const { settings } = require("../config");
const { compactAgentCardText, sanitizeAgentCardText } = require("./agentCardText");
const { splitAgentCardPairText } = require("./cardItemDerivations");
const { inferKnownTranslationTerms, uniqueAgentCardTexts } = require("./explicitConstraints");
const { callMimoText } = require("./mimoCore");
const { normalizeAgentAnswerLookupText } = require("./textNormalize");

function enforceExplicitTranslationComponentScope(component = {}, scope = {}) {
  if (component?.type !== "translation") return component;
  const terms = Array.isArray(scope?.translationConstraint?.terms)
    ? scope.translationConstraint.terms.map((item) => compactAgentCardText(item, 120)).filter(Boolean)
    : [];
  const source = compactAgentCardText(scope?.translationConstraint?.source || terms[0] || "", 160);
  if (!source) return component;
  if (terms.length > 1) {
    return buildTranslationComponentForTerms(component, terms);
  }
  const items = Array.isArray(component.items) ? component.items : [];
  const firstPair = splitAgentCardPairText(items[0] || "");
  const fallbackTarget = explicitTranslationFallbackTarget(source);
  const target = compactAgentCardText(fallbackTarget || firstPair.right || component.text || "", 160);
  const item = target ? `${source} | ${target}` : source;
  return {
    ...component,
    items: [item],
    pairs: [{ left: source, right: target, hint: "" }]
  };
}

function buildTranslationComponentForTerms(component = {}, terms = []) {
  const existingPairs = translationPairsFromComponent(component);
  const pairsBySource = new Map(existingPairs
    .filter((pair) => pair.left)
    .map((pair) => [normalizeAgentAnswerLookupText(pair.left), pair]));
  const pairs = terms.map((term) => {
    const source = compactAgentCardText(term, 120);
    const existing = pairsBySource.get(normalizeAgentAnswerLookupText(source));
    const fallbackRight = explicitTranslationFallbackTarget(source);
    const right = compactAgentCardText(fallbackRight || existing?.right || "", 140);
    return { left: source, right, hint: "" };
  }).filter((pair) => pair.left);
  return {
    ...component,
    text: "",
    items: pairs.map((pair) => pair.right ? `${pair.left} | ${pair.right}` : pair.left),
    pairs
  };
}

function translationPairsFromComponent(component = {}) {
  const pairs = [];
  if (Array.isArray(component.pairs)) {
    component.pairs.forEach((pair) => {
      const left = compactAgentCardText(pair?.left || "", 120);
      const right = compactAgentCardText(pair?.right || "", 140);
      if (left || right) pairs.push({ left, right, hint: "" });
    });
  }
  if (Array.isArray(component.items)) {
    component.items.forEach((item) => {
      const pair = splitAgentCardPairText(item);
      const left = compactAgentCardText(pair.left, 120);
      const right = compactAgentCardText(pair.right, 140);
      if (left || right) pairs.push({ left, right, hint: "" });
    });
  }
  return pairs;
}

function isAgentTranslationInstructionLeak(value = "") {
  const text = normalizeAgentAnswerLookupText(value);
  return /(?:^(?:create|make|generate)\b|translationedge|translation\s+practice\s+card|only\s+translation|no\s+examples|no\s+grammar|no\s+actions|extra\s+cards)/i.test(text);
}

function explicitTranslationFallbackTarget(source = "") {
  const text = normalizeAgentAnswerLookupText(source);
  if (text === "boarding pass") return "登机牌";
  if (text === "window seat") return "靠窗座位";
  if (text === "security check") return "安检";
  if (text === "check-in counter") return "值机柜台";
  if (text === "passport control") return "护照检查";
  if (text === "departure gate") return "登机口";
  if (text === "carry-on bag") return "随身行李";
  if (text === "seat belt") return "安全带";
  if (text === "customs form") return "海关申报表";
  if (text === "baggage claim") return "行李提取处";
  if (text === "i missed the bus") return "我错过了公交车。";
  if (text === "i missed the train") return "我错过了火车。";
  if (text === "i missed the last train") return "\u6211\u9519\u8fc7\u4e86\u672b\u73ed\u8f66\u3002";
  if (text === "i missed the flight") return "我错过了航班。";
  if (text === "could you help me?") return "你能帮我吗？";
  if (text === "could you change my ticket?") return "你能帮我改签车票吗？";
  if (text === "could you help me change my ticket?") return "\u4f60\u80fd\u5e2e\u6211\u6539\u7b7e\u8f66\u7968\u5417\uff1f";
  if (text === "i usually make breakfast at seven") return "我通常七点做早餐。";
  if (text === "i take the bus to work") return "我坐公交车去上班。";
  if (text === "i eat breakfast at seven every morning") return "我每天早上七点吃早餐。";
  if (text === "i usually go to school by bus") return "我通常坐公交车去学校。";
  if (text === "i do my homework after dinner") return "我晚饭后做作业。";
  if (text === "我已经完成了作业。") return "I have already finished my homework.";
  if (text === "你能帮我改一下这句话吗？") return "Could you help me revise this sentence?";
  if (text === "这个问题比我想的更复杂。") return "This problem is more complicated than I expected.";
  if (text === "我们需要在周五之前做出决定。") return "We need to make a decision before Friday.";
  return "";
}

function enforceRequestedTranslationComponentCount(component = {}, requestedCount = 0, scope = {}) {
  const explicitTerms = Array.isArray(scope?.translationConstraint?.terms)
    ? scope.translationConstraint.terms
        .map((item) => compactAgentCardText(item, 120))
        .filter((item) => item && !isAgentTranslationInstructionLeak(item))
    : [];
  if (explicitTerms.length >= requestedCount) {
    return buildTranslationComponentForTerms(component, explicitTerms.slice(0, requestedCount));
  }
  const pairs = translationPairsFromComponent(component);
  const currentTerms = pairs
    .filter((pair) => !(isAgentTranslationInstructionLeak(pair.left) && !pair.right))
    .map((pair) => pair.left)
    .filter(Boolean);
  const fallbackTerms = agentCardTranslationFallbackTerms(`${scope?.messageText || ""} ${component.title || ""} ${component.text || ""}`)
    .filter((item) => !isAgentTranslationInstructionLeak(item));
  const terms = uniqueAgentCardTexts([
    ...explicitTerms,
    ...currentTerms,
    ...fallbackTerms
  ], 120).slice(0, requestedCount);
  if (terms.length < requestedCount) return component;
  return buildTranslationComponentForTerms(component, terms);
}

function agentCardTranslationFallbackTerms(context = "") {
  const rawContext = sanitizeAgentCardText(context).trim();
  const terms = inferKnownTranslationTerms(context);
  const hasRealKnownTerms = terms.length &&
    !terms.some(isAgentTranslationInstructionLeak) &&
    !terms.some((term) => normalizeAgentAnswerLookupText(term) === normalizeAgentAnswerLookupText(rawContext));
  if (hasRealKnownTerms) return terms;
  if (/chinese|中文|汉语|漢語|中译英|汉译英|中翻英|译成英|natural\s+english|自然英语/i.test(context)) {
    return [
      "我已经完成了作业。",
      "你能帮我改一下这句话吗？",
      "这个问题比我想的更复杂。",
      "我们需要在周五之前做出决定。"
    ];
  }
  return [
    "boarding pass",
    "window seat",
    "security check"
  ];
}

module.exports = {
  enforceExplicitTranslationComponentScope,
  buildTranslationComponentForTerms,
  translationPairsFromComponent,
  isAgentTranslationInstructionLeak,
  explicitTranslationFallbackTarget,
  enforceRequestedTranslationComponentCount,
  agentCardTranslationFallbackTerms
};
