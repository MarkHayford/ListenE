const crypto = require("crypto");

function normalizeText(text) {
  return String(text || "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, " ")
    .trim()
    .replace(/\s+/g, " ");
}

function compactText(text, max = 240) {
  const value = String(text || "").replace(/\s+/g, " ").trim();
  return value.length > max ? value.slice(0, max - 1) : value;
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function asTextArray(value, max = 24) {
  return asArray(value)
    .map((item) => compactText(typeof item === "string" ? item : item?.text || item?.title || item?.reason || ""))
    .filter(Boolean)
    .slice(0, max);
}

function asObjectArray(value, max = 24) {
  return asArray(value)
    .filter((item) => item && typeof item === "object")
    .slice(0, max);
}

function addUnique(items, additions, max = 30) {
  const out = [];
  const seen = new Set();
  for (const item of [...additions, ...items]) {
    const value = compactText(item, 160);
    const key = normalizeText(value);
    if (!value || seen.has(key)) continue;
    seen.add(key);
    out.push(value);
    if (out.length >= max) break;
  }
  return out;
}

function incrementMap(map, key, amount = 1) {
  const cleanKey = compactText(key || "unknown", 40) || "unknown";
  return { ...(map || {}), [cleanKey]: Number(map?.[cleanKey] || 0) + amount };
}

function recordEvent(type, data = {}) {
  return {
    id: crypto.randomUUID(),
    type,
    createdAt: Date.now(),
    ...data
  };
}

function loadProfile() {
  return {
    totalAnalyses: 0,
    mistakeTypeCounts: {},
    tagCounts: {},
    recentWeakPoints: [],
    recentActions: [],
    recentEvents: [],
    updatedAt: 0
  };
}

function saveProfile(profile) {
  return profile;
}

function rememberProfileEvent(profile, event) {
  const preview = {
    id: event.id,
    type: event.type,
    createdAt: event.createdAt,
    title: event.title || event.sourceTitle || event.actionType || "",
    correct: typeof event.correct === "boolean" ? event.correct : undefined,
    grade: event.grade || undefined
  };
  return [preview, ...(profile.recentEvents || [])].slice(0, 40);
}

function collectAnalysisTags(result, wrongQuestionInsights) {
  const tags = [
    ...asTextArray(result.diagnosisTags, 20),
    ...asTextArray(result.weakPoints, 20),
    ...wrongQuestionInsights.map((item) => item.mistakeType)
  ];
  return tags.map((item) => compactText(item, 40)).filter(Boolean);
}

function recordAnalysis(body = {}) {
  const result = body.result && typeof body.result === "object" ? body.result : body.analysisResult || body;
  const record = body.record && typeof body.record === "object" ? body.record : {};
  const wrongQuestionInsights = asObjectArray(result.wrongQuestionInsights, 40).map((item) => ({
    questionIndex: Number.isFinite(Number(item.questionIndex)) ? Number(item.questionIndex) : -1,
    question: compactText(item.question, 220),
    selectedAnswer: compactText(item.selectedAnswer, 160),
    correctAnswer: compactText(item.correctAnswer, 160),
    mistakeType: compactText(item.mistakeType || "听力错题", 40),
    insight: compactText(item.insight, 260),
    focusSentence: compactText(item.focusSentence, 260),
    startMs: Number.isFinite(Number(item.startMs)) ? Number(item.startMs) : null,
    endMs: Number.isFinite(Number(item.endMs)) ? Number(item.endMs) : null
  }));
  const reviewItems = asObjectArray(result.reviewItems, 40).map((item) => ({
    text: compactText(item.text, 220),
    itemType: compactText(item.itemType || "sentence", 30),
    reason: compactText(item.reason, 160)
  })).filter((item) => item.text);
  const nextActions = asObjectArray(result.nextActions, 20).map((item) => ({
    title: compactText(item.title, 80),
    description: compactText(item.description, 180),
    actionType: compactText(item.actionType || "review", 40)
  })).filter((item) => item.title || item.description);
  const recommendedPlanTasks = asObjectArray(result.recommendedPlanTasks, 20).map((item) => ({
    title: compactText(item.title, 100),
    practiceType: compactText(item.practiceType || "对话听力", 40),
    offsetDays: Number.isFinite(Number(item.offsetDays)) ? Number(item.offsetDays) : 0,
    hour: Number.isFinite(Number(item.hour)) ? Number(item.hour) : 20,
    minute: Number.isFinite(Number(item.minute)) ? Number(item.minute) : 0
  }));
  const event = recordEvent("analysis", {
    recordId: compactText(body.recordId || record.id, 80),
    title: compactText(body.title || record.title || record.scene || record.content?.title || result.title || "AI 听力分析", 120),
    contentType: compactText(body.contentType || record.contentType || "", 30),
    summary: compactText(result.summary, 500),
    weakPoints: asTextArray(result.weakPoints, 20),
    suggestions: asTextArray(result.suggestions, 20),
    diagnosisTags: asTextArray(result.diagnosisTags, 20),
    wrongQuestionInsights,
    reviewItems,
    nextActions,
    recommendedPlanTasks
  });

  const profile = loadProfile();
  let tagCounts = { ...(profile.tagCounts || {}) };
  let mistakeTypeCounts = { ...(profile.mistakeTypeCounts || {}) };
  for (const tag of collectAnalysisTags(result, wrongQuestionInsights)) tagCounts = incrementMap(tagCounts, tag);
  for (const item of wrongQuestionInsights) mistakeTypeCounts = incrementMap(mistakeTypeCounts, item.mistakeType || "听力错题");
  const recentWeakPoints = addUnique(profile.recentWeakPoints || [], [
    ...asTextArray(result.weakPoints, 20),
    ...wrongQuestionInsights.map((item) => item.focusSentence || item.insight)
  ], 30);
  const recentActions = addUnique(profile.recentActions || [], [
    ...nextActions.map((item) => item.title || item.description),
    ...asTextArray(result.suggestions, 20)
  ], 30);

  const nextProfile = {
    ...profile,
    totalAnalyses: Number(profile.totalAnalyses || 0) + 1,
    tagCounts,
    mistakeTypeCounts,
    recentWeakPoints,
    recentActions,
    recentEvents: rememberProfileEvent(profile, event),
    lastAnalysisAt: event.createdAt,
    updatedAt: event.createdAt
  };
  saveProfile(nextProfile);
  return { event, profile: nextProfile };
}

module.exports = {
  recordAnalysis
};
