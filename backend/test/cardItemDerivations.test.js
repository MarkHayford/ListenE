const assert = require("assert");
const {
  inferScenarioParticipantCountFromItems,
  agentCardPairTextLimits,
  deriveAgentCardPairsFromItems,
  deriveAgentCardTokensFromItems,
  deriveAgentCardOptionsFromItems,
  deriveAgentCardLabeledObjectsFromItems,
  splitAgentCardRowHint,
  splitAgentCardPairText,
  splitAgentCardTokensText
} = require("../src/services/cardItemDerivations");

// ---- agentCardPairTextLimits ----
assert.deepStrictEqual(agentCardPairTextLimits("phrase"), { left: 120, right: 140, hint: 100, summary: 180 });
assert.deepStrictEqual(agentCardPairTextLimits("translation"), { left: 120, right: 140, hint: 100, summary: 180 });
assert.deepStrictEqual(agentCardPairTextLimits("other"), { left: 34, right: 34, hint: 42, summary: 80 });

// ---- splitAgentCardPairText ----
assert.deepStrictEqual(splitAgentCardPairText("hello -> 你好"), { left: "hello", right: "你好" });
assert.deepStrictEqual(splitAgentCardPairText("ship : sheep"), { left: "ship", right: "sheep" });
assert.deepStrictEqual(splitAgentCardPairText("a | b"), { left: "a", right: "b" });
assert.deepStrictEqual(splitAgentCardPairText("noseparator"), { left: "noseparator", right: "" });

// ---- splitAgentCardRowHint ----
assert.deepStrictEqual(splitAgentCardRowHint("ship vs sheep - both vowels"), { main: "ship vs sheep", hint: "both vowels" });
assert.deepStrictEqual(splitAgentCardRowHint("no hint here"), { main: "no hint here", hint: "" });

// ---- splitAgentCardTokensText ----
assert.deepStrictEqual(splitAgentCardTokensText("decide | decision | decisive"), ["decide", "decision", "decisive"]);
assert.deepStrictEqual(splitAgentCardTokensText("a, b, c"), ["a", "b", "c"]);
assert.deepStrictEqual(splitAgentCardTokensText("single"), ["single"]);

// ---- deriveAgentCardTokensFromItems ----
assert.deepStrictEqual(
  deriveAgentCardTokensFromItems("word_family", ["decide | decision | decisive"]),
  ["decide", "decision", "decisive"]
);
assert.deepStrictEqual(deriveAgentCardTokensFromItems("vocabulary", ["a | b"]), []); // 非 word_family
assert.deepStrictEqual(deriveAgentCardTokensFromItems("word_family", ["single"]), []); // 无 >1 token 的项

// ---- deriveAgentCardOptionsFromItems ----
assert.deepStrictEqual(
  deriveAgentCardOptionsFromItems("cloze", { items: ["sentence with blank", "optionA", "optionB"] }),
  ["optionA", "optionB"] // cloze 且无 text → 跳过首项(句子)
);
assert.deepStrictEqual(
  deriveAgentCardOptionsFromItems("cloze", { text: "x", items: ["a", "b"] }),
  ["a", "b"] // 有 text → 全量
);
assert.deepStrictEqual(deriveAgentCardOptionsFromItems("speaking_prompt", { items: ["p1", "p2"] }), ["p1", "p2"]);
assert.deepStrictEqual(deriveAgentCardOptionsFromItems("vocabulary", { items: ["a"] }), []); // 不适用类型

// ---- deriveAgentCardPairsFromItems ----
assert.deepStrictEqual(
  deriveAgentCardPairsFromItems("translation", ["hello -> 你好", "bye -> 再见"]),
  [{ left: "hello", right: "你好", hint: "" }, { left: "bye", right: "再见", hint: "" }]
);
assert.deepStrictEqual(
  deriveAgentCardPairsFromItems("minimal_pair", ["ship -> sheep - long vowel"]),
  [{ left: "ship", right: "sheep", hint: "long vowel" }]
);
assert.deepStrictEqual(deriveAgentCardPairsFromItems("vocabulary", ["a -> b"]), []); // 不适用类型

// ---- deriveAgentCardLabeledObjectsFromItems ----
assert.deepStrictEqual(
  deriveAgentCardLabeledObjectsFromItems("rubric", ["Grammar: correct tenses"], "criteria"),
  [{ label: "Grammar", text: "correct tenses" }]
);
assert.deepStrictEqual(
  deriveAgentCardLabeledObjectsFromItems("writing_outline", ["Intro: hook the reader"], "steps"),
  [{ label: "Intro", text: "hook the reader" }]
);
assert.deepStrictEqual(deriveAgentCardLabeledObjectsFromItems("rubric", ["A: B"], "steps"), []); // field/type 不匹配

// ---- inferScenarioParticipantCountFromItems：按「标签:」去重计数 ----
assert.strictEqual(inferScenarioParticipantCountFromItems({ items: ["Alice: hi", "Bob: hey", "Alice: bye"] }), 2);
assert.strictEqual(inferScenarioParticipantCountFromItems({ items: ["no label here"] }), null);
assert.strictEqual(inferScenarioParticipantCountFromItems({}), null);

console.log("cardItemDerivations.test.js passed");
