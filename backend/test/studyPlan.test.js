const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test } = require("../src/services/studyPlan");
const { normalizePlanInput, normalizeRecurrence, normalizeScheduledAt, planFromRow } = __test;

// recurrence allow-list, default none
assert.strictEqual(normalizeRecurrence("daily"), "daily");
assert.strictEqual(normalizeRecurrence("WEEKLY"), "weekly");
assert.strictEqual(normalizeRecurrence("none"), "none");
assert.strictEqual(normalizeRecurrence("bogus"), "none");
assert.strictEqual(normalizeRecurrence(undefined), "none");

// scheduledAt: positive epoch only, else 0
assert.strictEqual(normalizeScheduledAt(1700000000000), 1700000000000);
assert.strictEqual(normalizeScheduledAt("1700000000000"), 1700000000000);
assert.strictEqual(normalizeScheduledAt("abc"), 0);
assert.strictEqual(normalizeScheduledAt(-5), 0);
assert.strictEqual(normalizeScheduledAt(0), 0);

// normalizePlanInput: title required; trims; aliases for detail/scheduledAt; auto id
const p = normalizePlanInput({ title: "  背单词 list 1  ", detail: "复习 20 个词", scheduledAt: 1700000000000, recurrence: "daily" }, 123);
assert.strictEqual(p.title, "背单词 list 1");
assert.strictEqual(p.detail, "复习 20 个词");
assert.strictEqual(p.scheduledAt, 1700000000000);
assert.strictEqual(p.recurrence, "daily");
assert.ok(p.id.startsWith("plan_"), "auto id has plan_ prefix");

// alias fields: content/time
const p2 = normalizePlanInput({ title: "听力训练", content: "BBC 6 分钟", time: 1700000005000 }, 1);
assert.strictEqual(p2.detail, "BBC 6 分钟");
assert.strictEqual(p2.scheduledAt, 1700000005000);
assert.strictEqual(p2.recurrence, "none");

// no title -> null (rejected)
assert.strictEqual(normalizePlanInput({ title: "" }, 1), null);
assert.strictEqual(normalizePlanInput({ detail: "x" }, 1), null);
assert.strictEqual(normalizePlanInput({}, 1), null);

// explicit id preserved
assert.strictEqual(normalizePlanInput({ id: "plan_fixed", title: "x" }, 1).id, "plan_fixed");

// planFromRow maps snake_case row -> camelCase dto
const dto = planFromRow({ id: "plan_1", title: "t", detail: "d", scheduled_at: "42", recurrence: "weekly", created_at: "1", updated_at: "2" });
assert.deepStrictEqual(dto, { id: "plan_1", title: "t", detail: "d", scheduledAt: 42, recurrence: "weekly", createdAt: 1, updatedAt: 2 });

console.log("studyPlan.test.js passed");
