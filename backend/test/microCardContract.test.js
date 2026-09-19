const assert = require("assert");
const {
  MICRO_NODE_TYPES,
  GRADABLE_MICRO_NODE_TYPES,
  isKnownMicroNode,
  sanitizeMicroCard,
  EXAMPLE_CLOZE,
  EXAMPLE_CORRECTION
} = require("../src/contract/microCardContract");

// 契约本身：非空、无重复。
assert.ok(MICRO_NODE_TYPES.length > 0, "微元契约不应为空");
assert.strictEqual(new Set(MICRO_NODE_TYPES).size, MICRO_NODE_TYPES.length, "微元契约不应重复");
GRADABLE_MICRO_NODE_TYPES.forEach((type) => assert.ok(isKnownMicroNode(type), `可判分微元应在契约内: ${type}`));

// 大小写/空白归一。
assert.strictEqual(isKnownMicroNode(" Tokens "), true);
assert.strictEqual(isKnownMicroNode("unknown_widget"), false);

// 两个示例都是“合法微元组合”，清洗后一项不丢。
[EXAMPLE_CLOZE, EXAMPLE_CORRECTION].forEach((card) => {
  const clean = sanitizeMicroCard(card);
  assert.strictEqual(clean.nodes.length, card.nodes.length, "合法微元不应被清掉");
  clean.nodes.forEach((node) => assert.ok(isKnownMicroNode(node.type)));
});

// 关键证据：完形 = passage + choice 组合；改错 = tokens 微元 —— 都不是“固定题型”。
assert.deepStrictEqual(EXAMPLE_CLOZE.nodes.map((n) => n.type), ["text", "passage", "choice", "choice"]);
assert.ok(EXAMPLE_CORRECTION.nodes.some((n) => n.type === "tokens"));

// graceful：未知/空微元被丢弃，但仍返回“能渲染多少渲染多少”的卡（不套固定兜底）。
const dirty = sanitizeMicroCard({
  title: "脏数据",
  nodes: [
    { type: "text", text: "保留" },
    { type: "unknown_widget", foo: 1 },
    null,
    "not-an-object",
    { type: "choice", options: ["a", "b"], answer: "a" }
  ]
});
assert.strictEqual(dirty.nodes.length, 2, "未知/空微元应被丢弃");
assert.deepStrictEqual(dirty.nodes.map((n) => n.type), ["text", "choice"]);

// 新增 11 个题型：全部在契约内（可被识别、不会被清洗丢弃）。
[
  "monologue", "shadowing", "minimal_pair", "ipa_read", "sound_link", "map_label",
  "note_complete", "match_sentence_endings", "summary_complete", "short_answer", "guided_writing"
].forEach((type) => assert.ok(isKnownMicroNode(type), `新题型应在契约内: ${type}`));

// 可判分的新题型（听辨/认读/填空/配对/简答）进入 GRADABLE 列表——端上走统一判分。
["minimal_pair", "ipa_read", "map_label", "note_complete", "match_sentence_endings", "summary_complete", "short_answer"]
  .forEach((type) => assert.ok(GRADABLE_MICRO_NODE_TYPES.includes(type), `可判分新题型应在 GRADABLE: ${type}`));

// 自带评分流程/纯展示的新题型不进 GRADABLE（口语独白/跟读、连读讲解、引导写作走各自流程）。
["monologue", "shadowing", "sound_link", "guided_writing"]
  .forEach((type) => assert.ok(!GRADABLE_MICRO_NODE_TYPES.includes(type), `非统一判分新题型不应在 GRADABLE: ${type}`));

console.log("microCardContract.test.js passed");
