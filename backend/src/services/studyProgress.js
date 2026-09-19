// 学习进度（云端、按用户）：每完成一张练习卡记录一条结果，按 6 大技能聚合用于六边形雷达图。
const { query } = require("./db");

// 六边形的 6 个技能轴（固定顺序，保证雷达图稳定）。
const SKILLS = ["听力", "词汇", "语法", "阅读", "写作", "口语"];
const SKILL_SET = new Set(SKILLS);

// 组件类型 -> 技能。客户端也可显式传 skill（如阅读卡里的题组应记为「阅读」）。
const COMPONENT_SKILL = {
  audio: "听力", transcript: "听力", sentence_transcript: "听力", listening_cue: "听力", question_preview: "听力",
  vocabulary: "词汇", phrase: "词汇", word_family: "词汇", pronunciation: "词汇", minimal_pair: "词汇", compare: "词汇",
  grammar: "语法", cloze: "语法", sentence_builder: "语法", correction: "语法", error_hunt: "语法",
  mistake_pattern: "语法", paraphrase: "语法", rubric: "语法", debate: "语法", ethics: "语法", question_set: "语法",
  reading: "阅读", gap_match: "阅读", ordering: "阅读",
  writing_outline: "写作", short_answer: "写作", chart_writing: "写作", storytelling: "写作", translation: "写作",
  speaking_prompt: "口语", scenario: "口语", register: "口语"
};

const DAY_MS = 86400000;
const TZ_OFFSET_MS = 8 * 3600 * 1000; // 以东八区为「自然日」边界，让连续打卡符合用户直觉。

function dayKey(ts) {
  return Math.floor((Number(ts || 0) + TZ_OFFSET_MS) / DAY_MS);
}

function clampInt(value, min, max) {
  const n = Math.round(Number(value));
  if (!Number.isFinite(n)) return min;
  return Math.max(min, Math.min(max, n));
}

function deriveSkill(componentType, providedSkill) {
  const provided = String(providedSkill || "").trim();
  if (SKILL_SET.has(provided)) return provided;
  return COMPONENT_SKILL[String(componentType || "").trim().toLowerCase()] || "语法";
}

function normalizeProgressInput(item, now) {
  const componentType = String(item?.componentType || item?.component_type || item?.type || "").trim().toLowerCase().slice(0, 40);
  if (!componentType) return null;
  const total = clampInt(item?.total, 0, 100000);
  const correct = clampInt(item?.correct, 0, total || 100000);
  let score;
  if (item?.score !== undefined && item?.score !== null) {
    score = clampInt(item.score, 0, 100);
  } else if (total > 0) {
    score = clampInt((correct / total) * 100, 0, 100);
  } else {
    score = 0;
  }
  return {
    componentType,
    skill: deriveSkill(componentType, item?.skill),
    title: String(item?.title || "").replace(/\s+/g, " ").trim().slice(0, 120),
    score,
    total,
    correct,
    createdAt: now
  };
}

// 纯聚合（与 DB 解耦，便于单测）：把进度行汇总成六边形/趋势/连续打卡。
function aggregateProgress(rows = [], now = Date.now()) {
  const bySkill = new Map(SKILLS.map((s) => [s, { count: 0, sum: 0 }]));
  const activeDays = new Set();
  let totalAnswered = 0;
  let totalCorrect = 0;
  const todayKey = dayKey(now);
  const trendDays = 14;
  const trend = new Map();
  for (let i = trendDays - 1; i >= 0; i -= 1) {
    trend.set(todayKey - i, { count: 0, sum: 0 });
  }

  for (const row of rows) {
    const skill = SKILL_SET.has(row.skill) ? row.skill : "语法";
    const score = clampInt(row.score, 0, 100);
    const bucket = bySkill.get(skill);
    bucket.count += 1;
    bucket.sum += score;
    totalAnswered += clampInt(row.total, 0, 100000);
    totalCorrect += clampInt(row.correct, 0, 100000);
    const dk = dayKey(row.createdAt ?? row.created_at);
    activeDays.add(dk);
    if (trend.has(dk)) {
      const t = trend.get(dk);
      t.count += 1;
      t.sum += score;
    }
  }

  const skills = SKILLS.map((s) => {
    const b = bySkill.get(s);
    return { skill: s, count: b.count, score: b.count ? Math.round(b.sum / b.count) : 0 };
  });

  // 连续打卡：从今天（或昨天，宽限当天还没练）向前数连续有活动的天数。
  let streak = 0;
  let cursor = activeDays.has(todayKey) ? todayKey : (activeDays.has(todayKey - 1) ? todayKey - 1 : null);
  while (cursor !== null && activeDays.has(cursor)) {
    streak += 1;
    cursor -= 1;
  }

  const practiced = skills.filter((s) => s.count > 0);
  const weakestSkill = practiced.length
    ? practiced.reduce((a, b) => (b.score < a.score ? b : a)).skill
    : null;

  const trendArr = [...trend.entries()].map(([dk, t]) => ({
    dayKey: dk,
    date: new Date(dk * DAY_MS - TZ_OFFSET_MS).toISOString().slice(0, 10),
    count: t.count,
    avgScore: t.count ? Math.round(t.sum / t.count) : 0
  }));

  return {
    skills,
    totalSessions: rows.length,
    totalAnswered,
    totalCorrect,
    todaySessions: trend.get(todayKey)?.count || 0,
    streakDays: streak,
    weakestSkill,
    trend: trendArr
  };
}

async function recordProgress(user, item = {}) {
  if (!user) throw new Error("unauthorized");
  const now = Date.now();
  const p = normalizeProgressInput(item, now);
  if (!p) throw new Error("componentType is required");
  await query(
    `INSERT INTO study_progress (user_id, component_type, skill, title, score, total, correct, created_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
    [user.id, p.componentType, p.skill, p.title, p.score, p.total, p.correct, p.createdAt]
  );
  return { recorded: true, skill: p.skill, score: p.score, persisted: true };
}

async function summarizeProgress(user) {
  if (!user) return { ...aggregateProgress([]), persisted: false };
  const result = await query(
    `SELECT component_type, skill, score, total, correct, created_at
     FROM study_progress
     WHERE user_id = $1
     ORDER BY created_at DESC
     LIMIT 3000`,
    [user.id]
  );
  return { ...aggregateProgress(result.rows), persisted: true };
}

module.exports = {
  SKILLS,
  deriveSkill,
  recordProgress,
  summarizeProgress,
  __test: { deriveSkill, normalizeProgressInput, aggregateProgress, dayKey }
};
