const assert = require("assert");
const {
  isParagraphOrderingReadingRequest,
  isOrderingCardRequest,
  isOrderingRequiredScope,
  buildRequiredOrderingComponent,
  buildFallbackOrderingComponent,
  inferOrderingRequest,
  extractOrderingPayload,
  splitOrderingParts,
  cleanOrderingPart,
  orderOrderingItemsBySequenceMarkers,
  deterministicOrderingFallbackItems,
  defaultOrderingAnswerParts,
  defaultOrderingItems,
  agentCardOrderingAnswerParts,
  agentCardOrderingAnswerMatchesItems,
  countNormalizedValues,
  agentCardItemMaxLength
} = require("../src/services/ordering");

// ---- agentCardItemMaxLength：类型→上限 ----
assert.strictEqual(agentCardItemMaxLength("ordering"), 260);
assert.strictEqual(agentCardItemMaxLength("minimal_pair"), 80);
assert.strictEqual(agentCardItemMaxLength("sentence_builder"), 80);
assert.strictEqual(agentCardItemMaxLength("pronunciation"), 180);
assert.strictEqual(agentCardItemMaxLength("register"), 240);
assert.strictEqual(agentCardItemMaxLength("other"), 120);

// ---- isOrderingCardRequest / isParagraphOrderingReadingRequest ----
assert.strictEqual(isOrderingCardRequest("logical ordering of sentences"), true);
assert.strictEqual(isOrderingCardRequest("段落排序"), true);
assert.strictEqual(isOrderingCardRequest("no ordering please"), false); // 显式否定
assert.strictEqual(isOrderingCardRequest("sentence builder word order"), false); // 连词成句≠排序卡
assert.strictEqual(isOrderingCardRequest("ordering coffee at a cafe"), false); // 点单≠排序卡
assert.strictEqual(isOrderingCardRequest("hello"), false);
assert.strictEqual(isParagraphOrderingReadingRequest("段落排序"), true);
assert.strictEqual(isParagraphOrderingReadingRequest("hello"), false);

// ---- isOrderingRequiredScope ----
assert.strictEqual(isOrderingRequiredScope({ allowed: new Set(["ordering"]), messageText: "段落排序" }), true);
assert.strictEqual(isOrderingRequiredScope({ allowed: new Set(["cloze"]), messageText: "段落排序" }), false);
assert.strictEqual(isOrderingRequiredScope({}), false);

// ---- splitOrderingParts ----
assert.deepStrictEqual(
  splitOrderingParts("First step | Second step | Third step"),
  ["First step", "Second step", "Third step"]
);
assert.strictEqual(splitOrderingParts("").length, 0);
assert.strictEqual(splitOrderingParts("First we go. Then we eat. Finally we sleep.").length, 3);

// ---- cleanOrderingPart：去序号/前缀 ----
assert.strictEqual(cleanOrderingPart("1. First step"), "First step");
assert.strictEqual(cleanOrderingPart("items: hello world"), "hello world");

// ---- orderOrderingItemsBySequenceMarkers ----
assert.deepStrictEqual(
  orderOrderingItemsBySequenceMarkers(["Finally we sleep", "First we wake", "Then we eat"]),
  ["First we wake", "Then we eat", "Finally we sleep"]
);
assert.deepStrictEqual(orderOrderingItemsBySequenceMarkers(["First", "random", "Finally"]), []); // 有项无序号线索→放弃
assert.deepStrictEqual(orderOrderingItemsBySequenceMarkers(["only one"]), []);

// ---- deterministicOrderingFallbackItems：把正解末项移到首位(打乱) ----
assert.deepStrictEqual(deterministicOrderingFallbackItems(["a", "b", "c"]), ["c", "a", "b"]);
assert.deepStrictEqual(deterministicOrderingFallbackItems(["x"]), ["x"]);

// ---- 默认兜底 ----
assert.strictEqual(defaultOrderingAnswerParts().length, 3);
assert.strictEqual(defaultOrderingAnswerParts()[0], "First, we booked a room.");
assert.strictEqual(defaultOrderingItems().length, 3);
assert.strictEqual(defaultOrderingItems()[0], "Finally, we checked out.");

// ---- agentCardOrderingAnswerParts ----
assert.deepStrictEqual(agentCardOrderingAnswerParts("A | B | C"), ["A", "B", "C"]);
assert.strictEqual(agentCardOrderingAnswerParts("First. Then. Finally.").length, 3);
assert.deepStrictEqual(agentCardOrderingAnswerParts(""), []);

// ---- agentCardOrderingAnswerMatchesItems ----
assert.strictEqual(agentCardOrderingAnswerMatchesItems("First. | Then.", ["First.", "Then."]), true);
assert.strictEqual(agentCardOrderingAnswerMatchesItems("First. | Other.", ["First.", "Then."]), false);
assert.strictEqual(agentCardOrderingAnswerMatchesItems("only one", ["only one"]), false); // <2 项

// ---- countNormalizedValues ----
{
  const m = countNormalizedValues(["a", "a", "b"]);
  assert.strictEqual(m.get("a"), 2);
  assert.strictEqual(m.get("b"), 1);
  assert.strictEqual(m.size, 2);
}

// ---- extractOrderingPayload ----
assert.deepStrictEqual(extractOrderingPayload("answer: A | B", /(?:answer)\s*[:：]?\s*(.+?)$/i), ["A", "B"]);
assert.deepStrictEqual(extractOrderingPayload("nothing here", /(?:answer)\s*[:：]\s*(.+)/i), []);

// ---- inferOrderingRequest（集成）----
{
  const loose = inferOrderingRequest("hello");
  assert.strictEqual(loose.explicit, false);
  assert.ok(Array.isArray(loose.items) && loose.items.length >= 2);
  const explicit = inferOrderingRequest("items: First we wake. Then we eat. Finally we sleep.");
  assert.strictEqual(explicit.explicit, true);
  assert.strictEqual(explicit.items.length, 3);
}

// ---- buildFallbackOrderingComponent（集成）----
{
  const comp = buildFallbackOrderingComponent("hello");
  assert.strictEqual(comp.type, "ordering");
  assert.strictEqual(comp.title, "Ordering");
  assert.ok(Array.isArray(comp.items) && comp.items.length >= 2);
  assert.strictEqual(typeof comp.answer, "string");
}

console.log("ordering.test.js passed");
