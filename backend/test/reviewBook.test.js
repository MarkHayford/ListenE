const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test } = require("../src/services/reviewBook");
const { normalizeItemInput, nextDue, initialDueAt, clampBox, stableId, itemFromRow, SRS_INTERVALS, MAX_BOX } = __test;

// clampBox bounds
assert.strictEqual(clampBox(-3), 0);
assert.strictEqual(clampBox(99), MAX_BOX);
assert.strictEqual(clampBox(2), 2);
assert.strictEqual(clampBox("x"), 0);

// nextDue uses the box interval
const now = 1_000_000_000_000;
assert.strictEqual(nextDue(0, now), now + SRS_INTERVALS[0]);
assert.strictEqual(nextDue(MAX_BOX, now), now + SRS_INTERVALS[MAX_BOX]);
assert.strictEqual(nextDue(999, now), now + SRS_INTERVALS[MAX_BOX], "out-of-range box clamps");

// a freshly collected wrong item is immediately reviewable (due_at = now),
// not delayed by the box-0 interval, so the review session is usable right away.
assert.strictEqual(initialDueAt(now), now, "new wrong item is immediately due");
assert.ok(initialDueAt(now) < nextDue(0, now), "immediate due is earlier than box-0 interval");
assert.ok(itemFromRow({ id: "rv_x", options: "[]", box: 0, due_at: initialDueAt(now) }, now).due, "immediately-due new item shows as due");

// stableId deterministic + content-sensitive
const id1 = stableId("question_set", "What does X mean?", "correct");
const id2 = stableId("question_set", "What does X mean?", "correct");
const id3 = stableId("question_set", "What does X mean?", "different");
assert.strictEqual(id1, id2, "same content -> same id");
assert.notStrictEqual(id1, id3, "different answer -> different id");
assert.ok(id1.startsWith("rv_"));

// normalizeItemInput: derives skill, caps options, requires prompt+answer
const item = normalizeItemInput({
  componentType: "reading",
  questionText: "Main idea?",
  options: ["A", "B", "C", "D", "E", "F", "G", "H", "I"],
  correctAnswer: "B",
  explanation: "because"
}, now);
assert.strictEqual(item.componentType, "reading");
assert.strictEqual(item.skill, "阅读", "skill derived from component when not provided");
assert.strictEqual(item.prompt, "Main idea?");
assert.strictEqual(item.answer, "B");
assert.strictEqual(item.options.length, 8, "options capped at 8");
assert.ok(item.id.startsWith("rv_"));

// explicit skill respected; missing prompt/answer rejected
assert.strictEqual(normalizeItemInput({ componentType: "cloze", prompt: "x ___", answer: "y", skill: "语法" }, now).skill, "语法");
assert.strictEqual(normalizeItemInput({ componentType: "cloze", prompt: "only prompt" }, now), null, "missing answer -> null");
assert.strictEqual(normalizeItemInput({ answer: "only answer" }, now), null, "missing prompt -> null");

// itemFromRow: parses options json + due flag
const row = {
  id: "rv_abc", kind: "mcq", component_type: "question_set", skill: "语法",
  prompt: "p", options: '["A","B"]', answer: "A", explanation: "e",
  box: 2, due_at: now - 1000, times_wrong: 3, times_reviewed: 1, created_at: now, updated_at: now
};
const dto = itemFromRow(row, now);
assert.deepStrictEqual(dto.options, ["A", "B"], "options json string parsed");
assert.strictEqual(dto.due, true, "due_at in past -> due");
assert.strictEqual(dto.box, 2);
const future = itemFromRow({ ...row, due_at: now + 10_000 }, now);
assert.strictEqual(future.due, false, "future due_at -> not due");

console.log("reviewBook.test.js passed");
