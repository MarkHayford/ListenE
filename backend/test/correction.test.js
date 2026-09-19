const assert = require("assert");
const {
  mergeMistakePatternIntoCorrectionOnlyCard,
  isCorrectionOnlyScope,
  isCorrectionOnlyAgentCardRequest,
  isEditableCorrectionShortAnswerRequest,
  formatCorrectionMistakeItem,
  inferCorrectionMistakeItems,
  inferCorrectionMistakeFromItem,
  correctionFallbackItems,
  inferAgentRequestedCorrectionCount,
  enforceCorrectionCoreOnlyScope,
  inferCorrectionSourceSentenceFromMessage,
  cleanExplicitShortAnswer
} = require("../src/services/correction");

// ---- isCorrectionOnlyScope ----
assert.strictEqual(isCorrectionOnlyScope({ messageText: "only correction card" }), true);
assert.strictEqual(isCorrectionOnlyScope({ messageText: "只要纠错" }), true);
assert.strictEqual(isCorrectionOnlyScope({ messageText: "hello" }), false);

// ---- isCorrectionOnlyAgentCardRequest ----
assert.strictEqual(isCorrectionOnlyAgentCardRequest("just correction please"), true);
assert.strictEqual(isCorrectionOnlyAgentCardRequest("仅改错"), true);
assert.strictEqual(isCorrectionOnlyAgentCardRequest("hello"), false);

// ---- isEditableCorrectionShortAnswerRequest（需改错意图 + 可输入线索）----
assert.strictEqual(isEditableCorrectionShortAnswerRequest("改错并输入答案"), true);
assert.strictEqual(isEditableCorrectionShortAnswerRequest("correct this and type the corrected sentence"), true);
assert.strictEqual(isEditableCorrectionShortAnswerRequest("correct this sentence"), false); // 有意图无可输入线索
assert.strictEqual(isEditableCorrectionShortAnswerRequest("hello"), false); // 无改错意图

// ---- formatCorrectionMistakeItem ----
assert.strictEqual(formatCorrectionMistakeItem("subject-verb agreement"), "Mistake: subject-verb agreement");
assert.strictEqual(formatCorrectionMistakeItem("Mistake: tense error"), "Mistake: tense error");
assert.strictEqual(formatCorrectionMistakeItem(""), "");

// ---- inferCorrectionMistakeFromItem ----
assert.strictEqual(
  inferCorrectionMistakeFromItem("I go to school yesterday -> I went to school yesterday"),
  "Used base form 'go' instead of past tense 'went' for a past event ('yesterday')."
);
assert.strictEqual(
  inferCorrectionMistakeFromItem("I go -> I went"),
  "Used base form 'go' instead of past tense 'went' for a past event."
);
{
  const r = inferCorrectionMistakeFromItem("She dont care -> She doesn't care");
  assert.ok(/doesn't/.test(r) && /third-person/.test(r));
}
assert.strictEqual(inferCorrectionMistakeFromItem("no arrow here"), ""); // 无 -> 分隔
assert.strictEqual(inferCorrectionMistakeFromItem("a -> b"), ""); // 无已知错型

// ---- correctionFallbackItems ----
{
  const fb = correctionFallbackItems("fix tense errors");
  assert.strictEqual(fb.length, 2);
  assert.ok(fb[0].includes("->"));
  assert.deepStrictEqual(correctionFallbackItems("hello"), []);
}

// ---- inferAgentRequestedCorrectionCount ----
assert.strictEqual(inferAgentRequestedCorrectionCount("改错3句"), 3);
assert.strictEqual(inferAgentRequestedCorrectionCount("give me 3 corrections"), 3);
assert.strictEqual(inferAgentRequestedCorrectionCount("hello"), null);

// ---- enforceCorrectionCoreOnlyScope ----
{
  const stripped = enforceCorrectionCoreOnlyScope(
    { type: "correction", text: "explanation", items: ["wrong -> right (use past tense)"] },
    { suppressExtraSuggestions: true }
  );
  assert.strictEqual(stripped.text, "");
  // 非 correction 原样
  assert.strictEqual(enforceCorrectionCoreOnlyScope({ type: "cloze", text: "x" }, { suppressExtraSuggestions: true }).text, "x");
  // 不抑制且不排除 grammar → 原样
  assert.strictEqual(enforceCorrectionCoreOnlyScope({ type: "correction", text: "x", items: ["a"] }, {}).text, "x");
}

// ---- inferCorrectionSourceSentenceFromMessage ----
assert.strictEqual(inferCorrectionSourceSentenceFromMessage("correct this sentence: I goes home only"), "I goes home");
assert.strictEqual(inferCorrectionSourceSentenceFromMessage("把 我吃饭 改正"), "我吃饭");
assert.strictEqual(inferCorrectionSourceSentenceFromMessage("nothing"), "");

// ---- cleanExplicitShortAnswer ----
assert.strictEqual(cleanExplicitShortAnswer("Hello world。"), "Hello world");
assert.strictEqual(cleanExplicitShortAnswer("test;"), "test");

// ---- mergeMistakePatternIntoCorrectionOnlyCard ----
{
  const merged = mergeMistakePatternIntoCorrectionOnlyCard(
    [
      { type: "correction", items: ["A -> B"] },
      { type: "mistake_pattern", items: ["subject error"], text: "" }
    ],
    { messageText: "only correction card" }
  );
  assert.strictEqual(merged.length, 1);
  assert.strictEqual(merged[0].type, "correction");
  assert.ok(merged[0].items.includes("Mistake: subject error"));
  // 非 correction-only scope → 原样返回
  const comps = [{ type: "correction", items: [] }, { type: "mistake_pattern", items: ["x"] }];
  assert.strictEqual(mergeMistakePatternIntoCorrectionOnlyCard(comps, { messageText: "hello" }).length, 2);
}

// ---- inferCorrectionMistakeItems ----
{
  const items = inferCorrectionMistakeItems({ items: ["I go yesterday -> I went yesterday"] }, { messageText: "show mistakes" });
  assert.strictEqual(items.length, 1);
  assert.ok(/go/.test(items[0]));
  assert.deepStrictEqual(inferCorrectionMistakeItems({ items: ["x -> y"] }, { messageText: "hello" }), []);
}

console.log("correction.test.js passed");
