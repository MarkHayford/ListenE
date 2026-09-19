const assert = require("assert");
const { INPUT_LIMITS, ensureWithinLimit } = require("../src/services/inputLimits");

// ---- 限额常量 ----
assert.strictEqual(INPUT_LIMITS.translateText, 8000);
assert.strictEqual(INPUT_LIMITS.planGoal, 2000);
assert.strictEqual(INPUT_LIMITS.translateContext, 4000);

// ---- ensureWithinLimit：边界内返回字符串 ----
assert.strictEqual(ensureWithinLimit("hello", 10, "x"), "hello");
assert.strictEqual(ensureWithinLimit("hello", 5, "x"), "hello"); // 恰好等于上限放行
assert.strictEqual(ensureWithinLimit(null, 10), "");
assert.strictEqual(ensureWithinLimit(undefined, 10), "");
assert.strictEqual(ensureWithinLimit(12345, 10, "n"), "12345"); // 非字符串转字符串

// ---- 超限抛错（含 field 与上限，便于映射 400）----
assert.throws(() => ensureWithinLimit("hello!", 5, "text"), /text too long \(max 5 chars\)/);
assert.throws(() => ensureWithinLimit("a".repeat(2001), INPUT_LIMITS.planGoal, "goal"), /goal too long \(max 2000 chars\)/);

console.log("inputLimits.test.js passed");
