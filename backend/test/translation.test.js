const assert = require("assert");
const {
  enforceExplicitTranslationComponentScope,
  buildTranslationComponentForTerms,
  translationPairsFromComponent,
  isAgentTranslationInstructionLeak,
  explicitTranslationFallbackTarget,
  enforceRequestedTranslationComponentCount,
  agentCardTranslationFallbackTerms
} = require("../src/services/translation");

// ---- isAgentTranslationInstructionLeak ----
assert.strictEqual(isAgentTranslationInstructionLeak("create a translation card"), true);
assert.strictEqual(isAgentTranslationInstructionLeak("only translation no examples"), true);
assert.strictEqual(isAgentTranslationInstructionLeak("boarding pass"), false);

// ---- explicitTranslationFallbackTarget（词库直查）----
assert.strictEqual(explicitTranslationFallbackTarget("boarding pass"), "登机牌");
assert.strictEqual(explicitTranslationFallbackTarget("window seat"), "靠窗座位");
assert.strictEqual(explicitTranslationFallbackTarget("我已经完成了作业。"), "I have already finished my homework.");
assert.strictEqual(explicitTranslationFallbackTarget("unknown phrase"), "");

// ---- translationPairsFromComponent ----
assert.deepStrictEqual(
  translationPairsFromComponent({ pairs: [{ left: "hello", right: "你好" }] }),
  [{ left: "hello", right: "你好", hint: "" }]
);
assert.deepStrictEqual(
  translationPairsFromComponent({ items: ["bye | 再见"] }),
  [{ left: "bye", right: "再见", hint: "" }]
);
assert.strictEqual(translationPairsFromComponent({ pairs: [{ left: "a", right: "b" }], items: ["c | d"] }).length, 2);
assert.deepStrictEqual(translationPairsFromComponent({}), []);

// ---- buildTranslationComponentForTerms ----
{
  const c = buildTranslationComponentForTerms({}, ["boarding pass", "window seat"]);
  assert.strictEqual(c.pairs.length, 2);
  assert.deepStrictEqual(c.pairs[0], { left: "boarding pass", right: "登机牌", hint: "" });
  assert.strictEqual(c.text, "");
  assert.deepStrictEqual(c.items, ["boarding pass | 登机牌", "window seat | 靠窗座位"]);
  const u = buildTranslationComponentForTerms({}, ["unknown term"]);
  assert.strictEqual(u.pairs[0].left, "unknown term");
  assert.strictEqual(u.pairs[0].right, "");
  assert.deepStrictEqual(u.items, ["unknown term"]);
}

// ---- enforceExplicitTranslationComponentScope ----
{
  const multi = enforceExplicitTranslationComponentScope(
    { type: "translation", items: [] },
    { translationConstraint: { terms: ["boarding pass", "window seat"], source: "boarding pass" } }
  );
  assert.strictEqual(multi.pairs.length, 2);
  const single = enforceExplicitTranslationComponentScope(
    { type: "translation", items: ["x | y"] },
    { translationConstraint: { source: "boarding pass" } }
  );
  assert.deepStrictEqual(single.pairs[0], { left: "boarding pass", right: "登机牌", hint: "" });
  assert.strictEqual(enforceExplicitTranslationComponentScope({ type: "cloze" }, {}).type, "cloze"); // 非 translation 原样
}

// ---- agentCardTranslationFallbackTerms ----
{
  const zh = agentCardTranslationFallbackTerms("translate into chinese: please");
  assert.strictEqual(zh.length, 4);
  assert.ok(zh.includes("我已经完成了作业。"));
  assert.ok(agentCardTranslationFallbackTerms("boarding pass and window seat").includes("boarding pass"));
  const def = agentCardTranslationFallbackTerms("random words only");
  assert.strictEqual(def.length, 3);
  assert.strictEqual(def[0], "boarding pass");
}

// ---- enforceRequestedTranslationComponentCount ----
{
  const explicit = enforceRequestedTranslationComponentCount(
    { type: "translation", items: [] }, 2, { translationConstraint: { terms: ["boarding pass", "window seat"] } }
  );
  assert.strictEqual(explicit.pairs.length, 2);
  const viaFallback = enforceRequestedTranslationComponentCount(
    { type: "translation", items: [] }, 3, { messageText: "translate into chinese" }
  );
  assert.strictEqual(viaFallback.pairs.length, 3);
}

console.log("translation.test.js passed");
