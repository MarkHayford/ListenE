const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { DOMAINS, __test } = require("../src/services/userCategories");
const { normalizeCategories, normalizeDomain } = __test;

// --- normalizeDomain: dedup cat ids, drop empty, assignments only to valid cats ---
const dom = normalizeDomain({
  categories: [
    { id: "c1", name: " 语法 " },
    { id: "c1", name: "dup id dropped" },
    { id: "", name: "empty id dropped" },
    { id: "c2", name: "词汇" }
  ],
  assignments: {
    a: "c1",
    b: "c2",
    c: "cX", // unknown cat -> dropped
    "": "c1" // empty item -> dropped
  }
});
assert.strictEqual(dom.categories.length, 2, "dedup + drop empty cat ids");
assert.strictEqual(dom.categories[0].name, "语法", "whitespace collapsed");
assert.deepStrictEqual(dom.assignments, { a: "c1", b: "c2" }, "only assignments to valid cats kept");

// --- normalizeCategories: always the three domains, empty by default ---
const all = normalizeCategories({ cards: { categories: [{ id: "x", name: "X" }], assignments: { i: "x" } } });
assert.deepStrictEqual(Object.keys(all).sort(), [...DOMAINS].sort(), "exactly the known domains");
assert.strictEqual(all.cards.categories.length, 1);
assert.deepStrictEqual(all.workspace, { categories: [], assignments: {} }, "missing domain -> empty");
assert.deepStrictEqual(normalizeCategories(null).files, { categories: [], assignments: {} }, "null -> empty domains");

console.log("userCategories.test.js passed");
