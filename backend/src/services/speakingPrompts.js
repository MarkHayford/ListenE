// 口语提示(speaking_prompt)卡片：提示文本推断/生成、按请求数量补齐、主题/角色匹配校验、
// 主题与场景兜底、通用度判定与编号提示抽取。一组内聚的纯逻辑。原内联于 mimoText.js（1 万行）。

const { compactAgentCardText, sanitizeAgentCardText } = require("./agentCardText");
const { agentCardComponentTextMaxLength, inferExplicitSpeakingPromptOnlyText, inferExplicitSpeakingPromptTopicInfo, speakingPromptFromTopic, uniqueAgentCardTexts } = require("./explicitConstraints");
const { inferRequestedDialogueParticipantCount } = require("./intent");
const { normalizeAgentStringArray } = require("./textNormalize");

function isAgentPromptOnlyRequest(message = "") {
  const text = String(message || "").toLowerCase();
  return /only\s+the\s+prompt|prompt\s+only|只要.{0,8}(?:提示内容|题目文本|题目|prompt)|只(?:给|显示).{0,8}(?:提示内容|题目文本|题目|prompt)|别加别的|不要别的/i.test(text);
}

function isSingleSpeakingPromptOnlyRequest(message = "", requestedCounts = {}) {
  const count = requestedCounts?.speaking_prompt || 0;
  if (count > 1) return false;
  const text = String(message || "");
  return isAgentPromptOnlyRequest(text) ||
    (/speaking\s+prompt|\u53e3\u8bed\u63d0\u793a/i.test(text) &&
      /(?:only|just|one|\u4e00\u4e2a|\u53ea\u8981|\u53ea\u7ed9)/i.test(text) &&
      /no\s+(?:rubric|correction|examples?|questions?|question\s*set)|without\s+(?:rubric|correction|examples?|questions?|question\s*set)|\u4e0d\u8981.{0,16}(?:\u8bc4\u5206|\u7ea0\u9519|\u4f8b\u53e5|\u9898\u7ec4|\u9898\u76ee)/i.test(text));
}

function buildFallbackSpeakingPromptComponent(message = "", existing = {}) {
  const promptOnly = isAgentPromptOnlyRequest(message);
  const existingText = compactAgentCardText(existing?.text || "", 220);
  const topicPrompt = inferSpeakingPromptText(message);
  return {
    ...(existing || {}),
    type: "speaking_prompt",
    title: "Speaking Prompt",
    text: existingText && !/continue|send me|继续/i.test(existingText) ? existingText : topicPrompt,
    items: promptOnly ? [] : normalizeAgentStringArray(existing?.items || [], 160),
    options: []
  };
}

function inferSpeakingPromptText(message = "") {
  const raw = sanitizeAgentCardText(message);
  const lower = raw.toLowerCase();
  const quoted = /["“'‘](.{2,120})["”'’]/.exec(raw);
  if (quoted?.[1]) return compactAgentCardText(quoted[1], 180);
  const topicMatch = /(?:关于|about|topic)\s*[:：]?\s*([^，。,.!?；;]{2,80})/i.exec(raw);
  const topic = topicMatch?.[1]
    ? compactAgentCardText(topicMatch[1], 80)
    : /城市|city/.test(lower)
      ? "your city"
      : /校园|school|campus/.test(lower)
        ? "school life"
        : /工作|job|career|面试/.test(lower)
          ? "a job interview"
          : "a familiar daily topic";
  return `Talk about ${topic} in clear English.`;
}

function enforceRequestedSpeakingPromptCount(component = {}, requestedCount = 0, scope = {}) {
  if (scope?.promptOnly || !requestedCount) return component;
  const numberedPrompts = extractAgentCardNumberedPrompts(component.text || "")
    .filter((item) => isAcceptableAgentCardSpeakingPrompt(item, scope));
  if (numberedPrompts.length >= requestedCount) {
    return {
      ...component,
      text: "",
      items: numberedPrompts.slice(0, requestedCount),
      options: []
    };
  }
  const scopedFallback = agentCardSpeakingPromptScopedFallbackItems(scope);
  if (scopedFallback.length >= requestedCount) {
    return {
      ...component,
      text: "",
      items: scopedFallback.slice(0, requestedCount),
      options: []
    };
  }
  const existingPrompts = uniqueAgentCardTexts([
    ...(Array.isArray(component.items) ? component.items : []),
    ...(Array.isArray(component.options) ? component.options : [])
  ], 180).filter((item) => isAcceptableAgentCardSpeakingPrompt(item, scope));
  if (existingPrompts.length >= requestedCount) {
    return {
      ...component,
      text: component.text && existingPrompts.length === 0 ? component.text : "",
      items: existingPrompts.slice(0, requestedCount),
      options: []
    };
  }
  const singleTextPrompt = numberedPrompts.length || !isAcceptableAgentCardSpeakingPrompt(component.text || "", scope)
    ? []
    : [component.text || ""];
  const context = [
    scope.messageText,
    component.title,
    component.text,
    ...existingPrompts,
    ...numberedPrompts
  ].filter(Boolean).join(" ").toLowerCase();
  const merged = uniqueAgentCardTexts([
    ...existingPrompts,
    ...numberedPrompts,
    ...singleTextPrompt,
    ...filterAgentCardSpeakingPromptFallbackItems(
      agentCardSpeakingPromptFallbackItems(context),
      [...existingPrompts, ...numberedPrompts, ...singleTextPrompt]
    )
  ], 180)
    .slice(0, requestedCount);
  if (merged.length !== requestedCount) return component;
  return {
    ...component,
    text: "",
    items: merged,
    options: []
  };
}

function agentCardSpeakingPromptScopedFallbackItems(scope = {}) {
  const message = String(scope?.messageText || "").toLowerCase();
  if (
    inferRequestedDialogueParticipantCount(message) === 3 &&
    /restaurant|waiter|waitress|host|menu|table|dining|餐厅|饭店|服务员/.test(message)
  ) {
    return [
      "Waiter: Greet two customers and ask what they would like to drink.",
      "Customer 1: Ask for the menu and order one simple dish.",
      "Customer 2: Ask for the bill, thank the waiter, and say goodbye."
    ];
  }
  const topicScope = inferSpeakingPromptCurrentTopicScope(scope);
  if (!topicScope) return [];
  const profileFallback = agentCardSpeakingPromptTopicFallbackItems(topicScope);
  if (profileFallback.length) return profileFallback;
  const prompt = speakingPromptFromTopic(topicScope.topic);
  return [
    prompt,
    `Talk about one specific detail related to ${topicScope.topic}.`,
    `Explain why ${topicScope.topic} is important or interesting to you.`
  ].map((item) => compactAgentCardText(item, 180)).filter(Boolean);
}

function inferSpeakingPromptCurrentTopicScope(scope = {}) {
  const info = inferExplicitSpeakingPromptTopicInfo(scope?.messageText || "");
  if (!info?.topic) return null;
  const message = String(scope?.messageText || "").toLowerCase();
  if (
    info.source === "for" &&
    (
      inferRequestedDialogueParticipantCount(message) ||
      /dialogue|conversation|role.?play|scenario|situational|对话|角色|场景|情景/.test(message)
    )
  ) {
    return null;
  }
  const topic = compactAgentCardText(info.topic, 120);
  const profile = inferSpeakingPromptTopicProfile(topic);
  return { topic, profile };
}

function inferSpeakingPromptTopicProfile(topic = "") {
  const text = String(topic || "").toLowerCase();
  if (/favou?rite.{0,24}cit(?:y|ies)|cit(?:y|ies).{0,24}favou?rite|\bcit(?:y|ies)\b/.test(text)) return "favorite_city";
  if (/hotel/.test(text) && /booking|reservation|change|modify|reschedule|date|room/.test(text)) return "hotel_booking_change";
  if (/travel|trip|train|flight|airport|hotel|booking|luggage|delay|cancel|problem/.test(text)) return "travel_problem";
  return "generic";
}

function agentCardSpeakingPromptTopicFallbackItems(topicScope = {}) {
  const topic = topicScope.topic || "";
  if (topicScope.profile === "favorite_city") {
    return [
      "Describe your favorite city and explain why you like it.",
      "Talk about two places you would show a visitor in that city.",
      "Explain what makes the city special for you."
    ];
  }
  if (topicScope.profile === "hotel_booking_change") {
    return [
      "Call the hotel, explain that you need to change your booking, and ask what options are available.",
      "Tell the hotel your new date or room need, then confirm the updated booking details.",
      "Politely ask about any extra cost for changing the booking."
    ];
  }
  if (topicScope.profile === "travel_problem") {
    return [
      "Describe a time when you missed a train or flight and explain how you solved the problem.",
      "Talk about a lost luggage or hotel booking problem and say what help you asked for.",
      "Explain how you changed your plan after a travel delay or cancellation."
    ];
  }
  return topic ? [
    speakingPromptFromTopic(topic),
    `Talk about one specific detail related to ${topic}.`,
    `Explain why ${topic} is important or interesting to you.`
  ].map((item) => compactAgentCardText(item, 180)).filter(Boolean) : [];
}

function filterAgentCardSpeakingPromptFallbackItems(fallbacks = [], existing = []) {
  const existingKeys = new Set(existing.map((item) => agentCardSpeakingPromptIntentKey(item)).filter(Boolean));
  return fallbacks.filter((item) => {
    const text = String(item || "").toLowerCase();
    if (isGenericAgentCardSpeakingPrompt(text)) return false;
    const key = agentCardSpeakingPromptIntentKey(text);
    if (key && existingKeys.has(key)) return false;
    return true;
  });
}

function isAcceptableAgentCardSpeakingPrompt(value = "", scope = {}) {
  if (isGenericAgentCardSpeakingPrompt(value)) return false;
  if (isAgentCardSpeakingPromptTopicMismatch(value, scope)) return false;
  return !isAgentCardSpeakingPromptRoleMismatch(value, scope);
}

function isAgentCardSpeakingPromptTopicMismatch(value = "", scope = {}) {
  const topicScope = inferSpeakingPromptCurrentTopicScope(scope);
  if (!topicScope) return false;
  const text = String(value || "").toLowerCase();
  if (topicScope.profile === "favorite_city") {
    return !/(?:city|cities|place|favorite|favourite|visit|visitor|live|like|special|neighbou?rhood|transport|food|people|area)/.test(text) ||
      /(?:train|flight|airport|luggage|hotel|booking|delay|cancell?ation|cancelled|canceled)/.test(text);
  }
  if (topicScope.profile === "hotel_booking_change") {
    return !/(?:hotel|booking|reservation|room|date|change|changing|modify|reschedule|confirm|updated|extra cost|options)/.test(text) ||
      /(?:train|flight|airport|luggage|missed|delay|cancell?ation|cancelled|canceled)/.test(text);
  }
  if (topicScope.profile === "travel_problem") {
    return !/(?:travel|trip|train|flight|airport|hotel|booking|luggage|delay|delayed|cancel|cancelled|canceled|problem|missed|lost)/.test(text);
  }
  const topicTokens = meaningfulSpeakingPromptTopicTokens(topicScope.topic);
  if (!topicTokens.length) return false;
  return !topicTokens.some((token) => text.includes(token));
}

function meaningfulSpeakingPromptTopicTokens(topic = "") {
  const stopWords = new Set([
    "about", "describe", "describing", "talk", "talking", "explain", "explaining",
    "your", "you", "the", "and", "or", "for", "with", "only", "prompt", "prompts",
    "speaking", "oral", "card", "topic", "favorite", "favourite", "time", "thing"
  ]);
  return String(topic || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .split(/\s+/)
    .map((item) => item.trim())
    .filter((item) => item.length >= 4 && !stopWords.has(item))
    .slice(0, 8);
}

function isGenericAgentCardSpeakingPrompt(value = "") {
  return /state your answer|add one detail|explains? your idea|finish with a short closing|one simple sentence/i.test(String(value || ""));
}

function isAgentCardSpeakingPromptRoleMismatch(value = "", scope = {}) {
  const text = String(value || "").toLowerCase();
  const message = String(scope?.messageText || "").toLowerCase();
  if (inferRequestedDialogueParticipantCount(message) !== 3) return false;
  if (!/restaurant|waiter|waitress|host|menu|table|dining|餐厅|饭店|服务员/.test(message)) return false;
  return /(?:three|3)\s+(?:customers?|friends?|people|guests?|diners?)/.test(text) &&
    /\b(?:waiter|waitress|host|server)\b/.test(text);
}

function agentCardSpeakingPromptIntentKey(value = "") {
  const text = String(value || "").toLowerCase();
  if (/table for (?:two|three|four|[0-9])|confirm a table|seat(?:ing)?/.test(text)) return "table";
  if (/menu|recommend/.test(text)) return "menu";
  if (/order|dish|drink|meal|latte|coffee/.test(text)) return "order";
  if (/bill|check|pay|thank|goodbye/.test(text)) return "close";
  if (/price|cost|how much/.test(text)) return "price";
  if (/follow-up|follow up|size|temperature/.test(text)) return "followup";
  if (/greet|hello|hi|welcome/.test(text)) return "greet";
  if (/missed?.{0,24}(train|flight)|(train|flight).{0,24}missed?/.test(text)) return "missed-travel";
  if (/lost.{0,24}(luggage|bag|booking|reservation)|(luggage|bag|booking|reservation).{0,24}lost/.test(text)) return "lost-travel";
  if (/delay|delayed|cancel|cancelled|canceled|cancellation/.test(text)) return "changed-travel";
  return "";
}

function agentCardSpeakingPromptFallbackItems(context = "") {
  if (/restaurant|waiter|waitress|host|menu|table for three|dining|餐厅|饭店|服务员/.test(context)) {
    return [
      "Waiter: Greet two customers and ask what they would like to drink.",
      "Customer 1: Ask for the menu and order one simple dish.",
      "Customer 2: Ask for the bill, thank the waiter, and say goodbye."
    ];
  }
  if (/caf[eé]|barista|coffee|latte|drink|ordering|点单|咖啡/.test(context)) {
    return [
      "Customer: Greet the barista and order one drink.",
      "Barista: Ask one simple follow-up question about size or temperature.",
      "Customer: Answer the question, ask the price, and say thank you."
    ];
  }
  if (/interview|job|work|office|职场|面试/.test(context)) {
    return [
      "Speaker A: Ask a clear beginner-level question.",
      "Speaker B: Answer in one or two simple sentences.",
      "Speaker A: Ask one follow-up question or close politely."
    ];
  }
  if (/travel|trip|train|flight|airport|hotel|booking|luggage|delay|cancel|旅行|旅游|航班|酒店|行李/.test(context)) {
    return [
      "Describe a time when you missed a train or flight and explain how you solved the problem.",
      "Talk about a lost luggage or hotel booking problem and say what help you asked for.",
      "Explain how you changed your plan after a travel delay or cancellation."
    ];
  }
  return [
    "State your answer in one simple sentence.",
    "Add one detail that explains your idea.",
    "Finish with a short closing sentence."
  ];
}

function extractAgentCardNumberedPrompts(value = "") {
  const text = sanitizeAgentCardText(value).replace(/\s+/g, " ").trim();
  if (!text) return [];
  const prompts = [];
  const pattern = /(?:^|\s)(?:[0-9]{1,2}|[一二两兩三四五六七八九十]{1,3})[\.)、]\s*(.*?)(?=\s+(?:[0-9]{1,2}|[一二两兩三四五六七八九十]{1,3})[\.)、]\s*|$)/g;
  let match;
  while ((match = pattern.exec(text)) !== null) {
    const prompt = compactAgentCardText(match[1], 180);
    if (prompt) prompts.push(prompt);
  }
  return prompts;
}

function enforceSpeakingPromptComponentScope(component = {}, scope = {}) {
  if (component?.type !== "speaking_prompt" || !scope.promptOnly) return component;
  const explicitPrompt = inferExplicitSpeakingPromptOnlyText(scope.messageText || "");
  const currentText = compactAgentCardText(component.text || "", agentCardComponentTextMaxLength("speaking_prompt"));
  const text = explicitPrompt
    ? explicitPrompt
    : currentText;
  return {
    ...component,
    text,
    items: [],
    options: []
  };
}

module.exports = {
  isAgentPromptOnlyRequest,
  isSingleSpeakingPromptOnlyRequest,
  buildFallbackSpeakingPromptComponent,
  inferSpeakingPromptText,
  enforceRequestedSpeakingPromptCount,
  agentCardSpeakingPromptScopedFallbackItems,
  inferSpeakingPromptCurrentTopicScope,
  inferSpeakingPromptTopicProfile,
  agentCardSpeakingPromptTopicFallbackItems,
  filterAgentCardSpeakingPromptFallbackItems,
  isAcceptableAgentCardSpeakingPrompt,
  isAgentCardSpeakingPromptTopicMismatch,
  meaningfulSpeakingPromptTopicTokens,
  isGenericAgentCardSpeakingPrompt,
  isAgentCardSpeakingPromptRoleMismatch,
  agentCardSpeakingPromptIntentKey,
  agentCardSpeakingPromptFallbackItems,
  extractAgentCardNumberedPrompts,
  enforceSpeakingPromptComponentScope,
};
