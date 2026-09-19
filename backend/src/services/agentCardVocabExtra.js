// 词汇卡补充逻辑（Vocab）：词汇相关请求/构建辅助
// 原内联于 mimoText.js，逐字搬出为独立模块；依赖闭合、无循环依赖。

const { compactAgentCardText } = require("./agentCardText");
const { normalizeAgentAnswerLookupText } = require("./textNormalize");

function enforceRequestedVocabularyCount(component = {}, requestedCount = 0, scope = {}) {
  const items = Array.isArray(component.items) ? component.items : [];
  if (items.length >= requestedCount) return component;
  const context = [
    scope.messageText,
    component.title,
    component.text,
    ...items
  ].filter(Boolean).join(" ").toLowerCase();
  const seen = new Set();
  const merged = [];
  [...items, ...agentCardVocabularyFallbackItems(context)].forEach((item) => {
    const text = compactAgentCardText(item, 120);
    const key = normalizeAgentCardVocabularyHead(text);
    if (!text || !key || seen.has(key)) return;
    seen.add(key);
    merged.push(text);
  });
  if (merged.length <= items.length) return component;
  return {
    ...component,
    items: merged.slice(0, requestedCount)
  };
}

function agentCardVocabularyFallbackItems(context = "") {
  if (/easy|basic|beginner|simple|a1|a2|简单|基础|初级|入门|背不住|记不住/.test(context)) {
    return [
      "easy - simple; not difficult",
      "daily - happening every day",
      "need - must have something",
      "start - begin doing something",
      "finish - complete something",
      "help - make something easier",
      "change - become different",
      "answer - reply to a question"
    ];
  }
  if (/airport|travel|trip|hotel|flight|旅游|机场|旅行|航班/.test(context)) {
    return [
      "itinerary - a planned travel schedule",
      "accommodation - a place to stay",
      "departure - the time when a trip starts",
      "destination - the place someone is going to",
      "reservation - an arrangement to keep a seat or room",
      "luggage - bags carried while traveling",
      "boarding - getting onto a plane or train",
      "customs - checks for goods entering a country"
    ];
  }
  if (/business|work|meeting|client|presentation|商务|工作|会议|客户/.test(context)) {
    return [
      "deadline - the latest time something must be finished",
      "negotiate - discuss to reach an agreement",
      "proposal - a suggested plan",
      "budget - a plan for spending money",
      "strategy - a plan for achieving a goal",
      "priority - something more important than other tasks",
      "outcome - the result of an action",
      "collaborate - work together"
    ];
  }
  if (/academic|b2|reading|essay|university|学术|阅读|论文|大学/.test(context)) {
    return [
      "analyze - examine something carefully",
      "evidence - facts or information that support an idea",
      "significant - important or noticeable",
      "approach - a method or way of doing something",
      "establish - create or show something clearly",
      "coherent - logical and easy to understand",
      "relevant - closely connected to the topic",
      "substantial - large in amount or importance",
      "implicit - suggested but not directly stated",
      "perspective - a way of viewing something",
      "demonstrate - show clearly with evidence",
      "interpret - explain the meaning of something"
    ];
  }
  return [
    "accurate - correct and exact",
    "compare - look at similarities and differences",
    "require - need something",
    "improve - make something better",
    "identify - recognize or name something",
    "process - a series of actions",
    "benefit - an advantage",
    "respond - answer or react"
  ];
}

function normalizeAgentCardVocabularyHead(item = "") {
  return normalizeAgentAnswerLookupText(String(item || "").split(/\s*(?:-|—|–|\||:|：)\s*/)[0] || item);
}

module.exports = {
  enforceRequestedVocabularyCount,
  agentCardVocabularyFallbackItems,
  normalizeAgentCardVocabularyHead,
};
