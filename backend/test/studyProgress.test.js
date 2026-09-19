const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test, SKILLS } = require("../src/services/studyProgress");
const { deriveSkill, normalizeProgressInput, aggregateProgress, dayKey } = __test;

// deriveSkill: component -> skill; explicit valid skill wins
assert.strictEqual(deriveSkill("reading"), "阅读");
assert.strictEqual(deriveSkill("speaking_prompt"), "口语");
assert.strictEqual(deriveSkill("chart_writing"), "写作");
assert.strictEqual(deriveSkill("audio"), "听力");
assert.strictEqual(deriveSkill("vocabulary"), "词汇");
assert.strictEqual(deriveSkill("grammar"), "语法");
assert.strictEqual(deriveSkill("question_set", "阅读"), "阅读", "explicit valid skill overrides mapping");
assert.strictEqual(deriveSkill("unknown_xyz"), "语法", "unknown component falls back to 语法");
assert.strictEqual(deriveSkill("question_set", "瞎写"), "语法", "invalid explicit skill ignored");

// normalizeProgressInput: score from correct/total, clamps, requires componentType
const a = normalizeProgressInput({ componentType: "question_set", total: 4, correct: 3, skill: "阅读" }, 1000);
assert.strictEqual(a.score, 75);
assert.strictEqual(a.skill, "阅读");
const b = normalizeProgressInput({ type: "speaking_prompt", score: 88 }, 1000);
assert.strictEqual(b.score, 88);
assert.strictEqual(b.skill, "口语");
assert.strictEqual(normalizeProgressInput({ total: 1 }, 1000), null, "missing componentType -> null");
assert.strictEqual(normalizeProgressInput({ componentType: "cloze", score: 150 }, 1000).score, 100, "score clamped");

// aggregateProgress: 6 skills always present, streak, weakest, totals
const now = Date.UTC(2026, 5, 19, 4, 0, 0); // a fixed instant
const D = 86400000;
const rows = [
  { skill: "阅读", score: 90, total: 5, correct: 5, created_at: now },
  { skill: "阅读", score: 70, total: 5, correct: 3, created_at: now - D },
  { skill: "写作", score: 40, total: 0, correct: 0, created_at: now - D },
  { skill: "口语", score: 60, total: 0, correct: 0, created_at: now - 2 * D }
];
const agg = aggregateProgress(rows, now);
assert.strictEqual(agg.skills.length, 6, "always 6 skill axes for hexagon");
assert.deepStrictEqual(agg.skills.map((s) => s.skill), SKILLS, "skill order stable");
const reading = agg.skills.find((s) => s.skill === "阅读");
assert.strictEqual(reading.count, 2);
assert.strictEqual(reading.score, 80, "阅读 avg (90+70)/2");
const listening = agg.skills.find((s) => s.skill === "听力");
assert.strictEqual(listening.score, 0, "untouched skill shows 0, still present");
assert.strictEqual(agg.totalSessions, 4);
assert.strictEqual(agg.totalAnswered, 10);
assert.strictEqual(agg.totalCorrect, 8);
assert.strictEqual(agg.streakDays, 3, "today + yesterday + 2-days-ago all active = streak 3");
assert.strictEqual(agg.weakestSkill, "写作", "lowest avg among practiced");
assert.strictEqual(agg.trend.length, 14);
assert.strictEqual(agg.trend[agg.trend.length - 1].count, 1, "today has 1 session in trend");

// streak grace: only yesterday active (not today) still counts
const agg2 = aggregateProgress([{ skill: "阅读", score: 50, total: 1, correct: 0, created_at: now - D }], now);
assert.strictEqual(agg2.streakDays, 1, "yesterday-only still streak 1 (grace for today)");

// empty
const empty = aggregateProgress([], now);
assert.strictEqual(empty.totalSessions, 0);
assert.strictEqual(empty.streakDays, 0);
assert.strictEqual(empty.weakestSkill, null);
assert.strictEqual(empty.skills.length, 6);

console.log("studyProgress.test.js passed");
