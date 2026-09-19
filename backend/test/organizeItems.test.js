const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { settings } = require("../src/config");
const { organizeItems, __test } = require("../src/services/mimoOrganize");
const { normalizeItems, normalizeNames, normalizeResult } = __test;

// --- normalizeItems: dedup by id, drop empty id, keep title/summary ---
const items = normalizeItems([
  { id: "a", title: "  雅思  听力 ", summary: "地图题" },
  { id: "a", title: "dup id dropped" },
  { itemId: "b", title: "alias id key" },
  { id: "", title: "empty id dropped" },
  { title: "no id dropped" }
]);
assert.strictEqual(items.length, 2, "dedup + drop empty ids");
assert.strictEqual(items[0].id, "a");
assert.strictEqual(items[0].title, "雅思 听力", "whitespace collapsed");
assert.strictEqual(items[1].id, "b", "itemId alias -> id");

// --- normalizeNames: case-insensitive dedup + cap ---
assert.deepStrictEqual(normalizeNames(["语法", " 语法 ", "词汇"], 8), ["语法", "词汇"]);
assert.strictEqual(normalizeNames(["a", "b", "c", "d"], 2).length, 2, "capped");
assert.deepStrictEqual(normalizeNames("nope", 8), [], "non-array -> []");
assert.deepStrictEqual(normalizeNames([{ name: "对象名" }], 8), ["对象名"], "object name key");

// --- normalizeResult: valid ids/categories only, unused categories dropped ---
const validItems = [{ id: "a" }, { id: "b" }, { id: "c" }];
const res = normalizeResult(
  {
    categories: ["雅思听力", "语法", "没人用的分类"],
    assignments: [
      { id: "a", category: "雅思听力" },
      { id: "b", category: "语法" },
      { id: "c", category: "不存在的类" }, // category not in list -> dropped
      { id: "zzz", category: "雅思听力" }, // invalid id -> dropped
      { id: "a", category: "语法" } // dup id -> dropped
    ]
  },
  validItems,
  8
);
assert.deepStrictEqual(res.categories, ["雅思听力", "语法"], "unused category '没人用的分类' dropped");
assert.strictEqual(res.assignments.length, 2, "invalid/dup assignments dropped");
assert.deepStrictEqual(res.assignments[0], { id: "a", category: "雅思听力" });

// empty / no valid assignment -> throws
assert.throws(() => normalizeResult({ categories: [], assignments: [] }, validItems, 8), /整理失败/);

// --- organizeItems: guards + injected callModel (no network) ---
(async () => {
  await assert.rejects(() => organizeItems({ items: [] }), /没有可整理的项目/);

  let capturedModel = null;
  const call = async (messages, opts) => {
    capturedModel = opts.model;
    return {
      choices: [
        {
          message: {
            content: JSON.stringify({
              categories: ["雅思听力", "语法"],
              assignments: [
                { id: "a", category: "雅思听力" },
                { id: "b", category: "语法" }
              ]
            })
          }
        }
      ]
    };
  };
  const out = await organizeItems({
    items: [
      { id: "a", title: "雅思听力 Section 1" },
      { id: "b", title: "定语从句" }
    ],
    callModel: call
  });
  assert.strictEqual(capturedModel, settings.mimoTextModel, "uses text model");
  assert.deepStrictEqual(out.categories, ["雅思听力", "语法"]);
  assert.strictEqual(out.assignments.length, 2);

  console.log("organizeItems.test.js passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
