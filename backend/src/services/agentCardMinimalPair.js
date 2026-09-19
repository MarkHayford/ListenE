// 最小对立对卡（Minimal Pair）：请求识别、配对构建与清洗
// 原内联于 mimoText.js，逐字搬出为独立模块；依赖闭合、无循环依赖。

const { AGENT_MINIMAL_PAIR_INTENT_PATTERN } = require("./agentCardPatterns");
const { placeCorrectAgentQuestionOption } = require("./agentCardReading");
const { compactAgentCardText, sanitizeAgentCardText } = require("./agentCardText");
const { agentCardPairTextLimits, deriveAgentCardPairsFromItems } = require("./cardItemDerivations");
const { isMinimalPairPronunciationHint, neutralizeMinimalPairNoPronunciationTitle } = require("./cardTitles");
const { inferExplicitMinimalPairs, inferExplicitSlashMinimalPairsFromText, uniqueMinimalPairObjects } = require("./explicitConstraints");

const DEFAULT_MINIMAL_PAIR_POOL = [
  { left: "ship", right: "sheep", hint: "/ɪ/ vs /iː/" },
  { left: "full", right: "fool", hint: "/ʊ/ vs /uː/" },
  { left: "light", right: "right", hint: "/l/ vs /r/" },
  { left: "think", right: "sink", hint: "/θ/ vs /s/" },
  { left: "bad", right: "bed", hint: "/æ/ vs /e/" },
  { left: "vest", right: "west", hint: "/v/ vs /w/" },
  { left: "bit", right: "beat", hint: "/ɪ/ vs /iː/" },
  { left: "cot", right: "caught", hint: "/ɒ/ vs /ɔː/" },
  { left: "pen", right: "pan", hint: "/e/ vs /æ/" },
  { left: "sit", right: "seat", hint: "/ɪ/ vs /iː/" }
];
// 默认辨音音对：随机取若干组带音标 hint 的不同音对，避免每次都回退到固定的 ship/sheep 模板。

function pickDefaultMinimalPairs(n = 4) {
  const pool = DEFAULT_MINIMAL_PAIR_POOL.slice();
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [pool[i], pool[j]] = [pool[j], pool[i]];
  }
  return pool.slice(0, Math.max(1, Math.min(n, pool.length))).map((p) => ({ ...p }));
}

function isStrictStaticMinimalPairRequest(text = "") {
  const value = String(text || "").toLowerCase();
  const rejectsQuestionSet = /no\s+(?:questions?|question\s*set|quiz)|without\s+(?:questions?|question\s*set|quiz)/i.test(value);
  const explicitlyWantsQuestionSet = /minimal[-\s]*pair\s+question\s*set|questions?|quiz/i.test(value) && !rejectsQuestionSet;
  if (explicitlyWantsQuestionSet) return false;
  return /minimal[-\s]*pair|sound[-\s]*pair|\/ɪ\/|\/i:\/|\/iː\//i.test(value) &&
    /only\s+(?:static\s+)?minimal[-\s]*pairs?|only\s+minimal[-\s]*pair\s+discrimination|no\s+(?:questions?|question\s*set|quiz)|without\s+(?:questions?|question\s*set|quiz)/i.test(value);
}

function isMinimalPairQuestionSetRequest(text = "") {
  const value = String(text || "").toLowerCase();
  if (isStrictStaticMinimalPairRequest(value)) return false;
  return /minimal[-\s]*pair|sound[-\s]*pair|辨音|音近词|音素/.test(value) &&
    /questions?|question\s*set|quiz|discrimination|选择题|题组|题目|题/.test(value);
}

function isExplicitWeakMinimalPairRequest(message = "") {
  const text = String(message || "").toLowerCase();
  return /ship\s*\/\s*sheep|sheep\s*\/\s*ship|\br\s*(?:和|and|\/)\s*l\b|\bl\s*(?:和|and|\/)\s*r\b/.test(text) &&
    /听不出|听不出来|分不清|搞混|辨音|听辨|minimal|pair|sound/.test(text);
}

function isStaticMinimalPairRequiredScope(scope = {}) {
  const message = scope.messageText || "";
  return scope.allowed instanceof Set &&
    scope.allowed.has("minimal_pair") &&
    !scope.multiQuestionChoice &&
    !scope.multiFillBlankQuestions &&
    !scope.choiceConstraint &&
    (
      isStrictStaticMinimalPairRequest(message) ||
      AGENT_MINIMAL_PAIR_INTENT_PATTERN.test(message) ||
      inferExplicitSlashMinimalPairsFromText(message).length > 0 ||
      /minimal[-\s]*pair|sound[-\s]*pair|\/Éª\/|\/i:\/|\/iË\/|\/ɪ\/|\/iː\//i.test(message)
    );
}

function enforceRequestedMinimalPairCount(component = {}, requestedCount = 0, scope = {}) {
  const currentPairs = [
    ...(Array.isArray(component.pairs) ? normalizeAgentCardPairObjects(component.pairs, "minimal_pair") : []),
    ...deriveAgentCardPairsFromItems("minimal_pair", component.items || [])
  ].map((pair) => ({
    left: compactAgentCardText(pair.left || "", 34),
    right: compactAgentCardText(pair.right || "", 34),
    hint: ""
  })).filter((pair) => pair.left && pair.right);
  const explicitPairs = inferExplicitMinimalPairs(scope.messageText || "");
  const merged = uniqueMinimalPairObjects([...currentPairs, ...explicitPairs]).slice(0, requestedCount);
  if (merged.length < requestedCount) return component;
  return {
    ...component,
    text: "",
    items: [],
    pairs: merged
  };
}

function enforceMinimalPairComponentScope(component = {}, scope = {}) {
  if (component?.type !== "minimal_pair") return component;
  const excluded = scope.excluded instanceof Set ? scope.excluded : new Set(scope.excluded || []);
  const stripHints = scope.suppressExtraSuggestions || excluded.has("pronunciation");
  if (!stripHints) return component;
  const pairs = Array.isArray(component.pairs)
    ? component.pairs.map((pair) => ({
        ...pair,
        hint: ""
      }))
    : component.pairs;
  const items = Array.isArray(pairs) && pairs.length
    ? normalizeAgentCardPairs(pairs)
    : Array.isArray(component.items)
      ? component.items.map(stripMinimalPairHintText).filter(Boolean)
      : component.items;
  const text = isMinimalPairPronunciationHint(component.text || "") ? "" : component.text;
  return {
    ...component,
    title: neutralizeMinimalPairNoPronunciationTitle(component.title || ""),
    text,
    items,
    pairs
  };
}

function stripMinimalPairHintText(value = "") {
  const text = compactAgentCardText(value, 80);
  if (!text) return "";
  const hintMatch = /\s+(?:-|—|–)\s+(.+)$/.exec(text);
  if (!hintMatch) return text;
  const main = text.slice(0, hintMatch.index).trim();
  const hint = hintMatch[1] || "";
  return main && isMinimalPairPronunciationHint(hint) ? main : text;
}

function isFakeMinimalPairPlaybackInstruction(value = "") {
  const text = String(value || "").toLowerCase();
  return (
    /\b(?:tap|click)\b/.test(text) &&
    /\b(?:pair|pairs|word|words|row|item|card)\b/.test(text) &&
    /\b(?:hear|listen|play)\b/.test(text)
  ) || /(?:点击|点按).{0,24}(?:词对|音近词|pair|单词|行|卡片).{0,24}(?:播放|收听|听)/i.test(String(value || ""));
}

function buildMinimalPairQuestionSetComponent(text = "", questionCount = 4, optionCount = 4) {
  const pairs = inferMinimalPairWordPairsFromText(text);
  const bank = pairs.length
    ? pairs
    : [
        ["ship", "sheep"],
        ["bit", "beat"],
        ["full", "fool"],
        ["live", "leave"]
      ];
  const allWords = Array.from(new Set(bank.flat())).filter(Boolean);
  const safeCount = Math.max(1, Math.min(questionCount || bank.length, 8));
  const safeOptionCount = Math.max(2, Math.min(optionCount || 4, 6));
  const questions = Array.from({ length: safeCount }, (_, index) => {
    const pair = bank[index % bank.length];
    const answer = pair[index % 2];
    const pairDistractor = pair.find((word) => word !== answer);
    const otherDistractors = allWords.filter((word) => word !== answer && word !== pairDistractor);
    const fallbackDistractors = ["sit", "seat", "pull", "pool", "fill", "feel"].filter((word) => word !== answer && word !== pairDistractor);
    const rawOptions = [answer, pairDistractor, ...otherDistractors, ...fallbackDistractors]
      .filter(Boolean)
      .filter((word, wordIndex, words) => words.indexOf(word) === wordIndex)
      .slice(0, safeOptionCount);
    const balanced = placeCorrectAgentQuestionOption(rawOptions, 0, index % rawOptions.length);
    return {
      questionText: `Choose the word with the different vowel sound in this pair: ${pair[0]} / ${pair[1]}.`,
      options: balanced.options,
      correctAnswer: balanced.correctAnswer,
      explanation: ""
    };
  });
  return {
    type: "question_set",
    title: "Minimal Pair Discrimination",
    text: `${questions.length} minimal pair questions`,
    questions
  };
}

function agentMinimalPairQuestionLooksSemantic(question = {}) {
  const text = sanitizeAgentCardText(question?.questionText || "");
  return /\b(?:means?|meaning|definition|complete|entire|rhymes?|rhyme|synonym|opposite)\b/i.test(text);
}

function buildRequiredMinimalPairComponent(existing = {}, scope = {}) {
  const existingPairs = [
    ...normalizeAgentCardPairObjects(existing.pairs || [], "minimal_pair"),
    ...deriveAgentCardPairsFromItems("minimal_pair", existing.items || [])
  ];
  const explicitPairs = uniqueMinimalPairObjects([
    ...inferExplicitSlashMinimalPairsFromText(scope.messageText || "").map(([left, right]) => ({ left, right, hint: "" })),
    ...inferExplicitMinimalPairs(scope.messageText || "")
  ]);
  const inferredPairs = uniqueMinimalPairObjects(inferMinimalPairWordPairsFromText(scope.messageText || "")
    .map(([left, right]) => ({ left, right, hint: "" })));
  const fallbackPairs = pickDefaultMinimalPairs(6);
  const requestedCount = Number(scope?.requestedCounts?.minimal_pair || 0);
  const targetCount = Math.max(1, Math.min(
    requestedCount || explicitPairs.length || existingPairs.length || 2,
    8
  ));
  const pairs = uniqueMinimalPairObjects([...explicitPairs, ...existingPairs, ...inferredPairs, ...fallbackPairs])
    .slice(0, targetCount);
  return {
    ...existing,
    type: "minimal_pair",
    title: existing.title || "Minimal Pairs",
    text: "",
    items: [],
    pairs,
    options: [],
    explanation: ""
  };
}

function inferMinimalPairWordPairsFromText(text = "") {
  const value = String(text || "");
  const pairCandidates = [];
  if (/\br\s*(?:和|and|\/)\s*l\b|\bl\s*(?:和|and|\/)\s*r\b|r\s*和\s*l|l\s*和\s*r/i.test(value)) {
    pairCandidates.push(["right", "light"], ["rice", "lice"], ["road", "load"], ["red", "led"]);
  }
  if (/\/ɪ\/|\/iː\/|长短音|ship|sheep/i.test(value)) {
    pairCandidates.push(["ship", "sheep"], ["bit", "beat"], ["sit", "seat"], ["fill", "feel"]);
  }
  const slashPattern = /\b([a-z][a-z'-]{1,24})\s*\/\s*([a-z][a-z'-]{1,24})\b/gi;
  let slashMatch;
  while ((slashMatch = slashPattern.exec(value))) {
    pairCandidates.push([slashMatch[1], slashMatch[2]]);
  }
  const pairContext = /(?:for|pairs?|words?|辨音|音近词|音素|questions?)\s+(.{0,180})/i.exec(value)?.[1] || value;
  const words = pairContext
    .replace(/[/,;，；]/g, " ")
    .split(/\s+/)
    .map((word) => word.replace(/^[^a-z]+|[^a-z]+$/gi, "").toLowerCase())
    .filter((word) => /^[a-z][a-z'-]{1,24}$/.test(word))
    .filter((word) => !new Set([
      "create", "exactly", "minimal", "pair", "pairs", "listening", "discrimination", "questions",
      "question", "set", "only", "examples", "grammar", "vocabulary", "extra", "cards", "audio",
      "for", "and", "with", "each", "a2", "english"
    ]).has(word));
  for (let index = 0; index + 1 < words.length; index += 2) {
    pairCandidates.push([words[index], words[index + 1]]);
  }
  return pairCandidates
    .map(([left, right]) => [compactAgentCardText(left, 34), compactAgentCardText(right, 34)])
    .filter(([left, right]) => left && right && left !== right)
    .filter((pair, index, pairs) => pairs.findIndex((item) => item[0] === pair[0] && item[1] === pair[1]) === index)
    .slice(0, 8);
}

function normalizeAgentCardPairs(value, type = "") {
  if (!Array.isArray(value)) return [];
  const limits = agentCardPairTextLimits(type);
  return value.map((entry) => {
    if (!entry || typeof entry !== "object") return sanitizeAgentCardText(entry).slice(0, 80);
    const left = compactAgentCardText(entry.left || entry.source || entry.from || entry.a, limits.left);
    const right = compactAgentCardText(entry.right || entry.target || entry.to || entry.b, limits.right);
    const hint = compactAgentCardText(entry.hint || entry.text || entry.note, limits.hint);
    const pair = left && right ? `${left} | ${right}` : (left || right);
    const summary = pair && hint ? `${pair} - ${hint}` : (pair || hint);
    return compactAgentCardText(summary, limits.summary);
  }).filter(Boolean);
}

function normalizeAgentCardPairObjects(value, type = "") {
  if (!Array.isArray(value)) return [];
  const limits = agentCardPairTextLimits(type);
  return value.map((entry) => {
    if (!entry || typeof entry !== "object") return null;
    const left = compactAgentCardText(entry.left || entry.source || entry.from || entry.a, limits.left);
    const right = compactAgentCardText(entry.right || entry.target || entry.to || entry.b, limits.right);
    const hint = compactAgentCardText(entry.hint || entry.text || entry.note, limits.hint);
    if (!left && !right && !hint) return null;
    return { left, right, hint };
  }).filter(Boolean).slice(0, 8);
}

module.exports = {
  DEFAULT_MINIMAL_PAIR_POOL,
  pickDefaultMinimalPairs,
  isStrictStaticMinimalPairRequest,
  isMinimalPairQuestionSetRequest,
  isExplicitWeakMinimalPairRequest,
  isStaticMinimalPairRequiredScope,
  enforceRequestedMinimalPairCount,
  enforceMinimalPairComponentScope,
  stripMinimalPairHintText,
  isFakeMinimalPairPlaybackInstruction,
  buildMinimalPairQuestionSetComponent,
  agentMinimalPairQuestionLooksSemantic,
  buildRequiredMinimalPairComponent,
  inferMinimalPairWordPairsFromText,
  normalizeAgentCardPairs,
  normalizeAgentCardPairObjects,
};
