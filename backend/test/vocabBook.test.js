const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test } = require("../src/services/vocabBook");
const { normalizeWordInput, nextDue, clampBox, wordId, wordFromRow, SRS_INTERVALS, MAX_BOX } = __test;

const now = 1_700_000_000_000;

// clampBox + nextDue
assert.strictEqual(clampBox(-1), 0);
assert.strictEqual(clampBox(99), MAX_BOX);
assert.strictEqual(nextDue(0, now), now + SRS_INTERVALS[0]);
assert.strictEqual(nextDue(99, now), now + SRS_INTERVALS[MAX_BOX]);

// wordId stable + case-insensitive
assert.strictEqual(wordId("Hello"), wordId(" hello "), "id is trimmed + lowercased");
assert.notStrictEqual(wordId("hello"), wordId("world"));
assert.ok(wordId("hello").startsWith("vw_"));

// normalizeWordInput
const w = normalizeWordInput({ word: "  Resilient ", phonetic: "/rɪˈzɪliənt/", meaning: "adj. 有韧性的", example: "She is resilient. (她很有韧性。)" }, now);
assert.strictEqual(w.word, "Resilient");
assert.strictEqual(w.id, wordId("Resilient"));
assert.strictEqual(w.meaning, "adj. 有韧性的");
assert.strictEqual(normalizeWordInput({ phonetic: "x" }, now), null, "missing word -> null");

// wordFromRow due flag
const row = { id: "vw_x", word: "go", phonetic: "/ɡoʊ/", meaning: "去", example: "Let's go.", box: 1, due_at: now - 5, times_reviewed: 2, created_at: now, updated_at: now };
const dto = wordFromRow(row, now);
assert.strictEqual(dto.due, true);
assert.strictEqual(dto.box, 1);
assert.strictEqual(wordFromRow({ ...row, due_at: now + 999 }, now).due, false);

console.log("vocabBook.test.js passed");
