const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { settings } = require("../src/config");
const { solveQuestion, SOLVE_SKILLS, __test } = require("../src/services/mimoSolve");
const { normalizeSolveResult, normalizeSolveVocab, clampSolveSkill, strList } = __test;

// --- clampSolveSkill: only the six canonical skills survive ---
assert.strictEqual(clampSolveSkill("语法"), "语法");
assert.strictEqual(clampSolveSkill(" 阅读 "), "阅读");
assert.strictEqual(clampSolveSkill("grammar"), "", "unknown skill -> empty");
assert.strictEqual(clampSolveSkill(null), "");
assert.deepStrictEqual(SOLVE_SKILLS, ["听力", "词汇", "语法", "阅读", "写作", "口语"]);

// --- strList: trims, collapses whitespace, drops blanks, caps count ---
assert.deepStrictEqual(strList(["  a  b ", "", "c"], 4), ["a b", "c"]);
assert.strictEqual(strList(["1", "2", "3", "4", "5"], 3).length, 3, "capped to 3");
assert.deepStrictEqual(strList("nope", 3), [], "non-array -> []");

// --- normalizeSolveVocab: keeps word+meaning, alias keys, caps 6 ---
const vocab = normalizeSolveVocab([
  { word: "diligent", phonetic: "/ˈdɪlɪdʒənt/", meaning: "勤奋的", example: "She is diligent." },
  { term: "aliasWord", definition: "别名键释义" },
  { word: "nomeaning" },
  { word: "", meaning: "空词应过滤" },
  ...Array.from({ length: 8 }, (_, i) => ({ word: `w${i}`, meaning: `m${i}` }))
]);
assert.strictEqual(vocab[0].word, "diligent");
assert.strictEqual(vocab[1].word, "aliasWord", "term alias -> word");
assert.strictEqual(vocab[1].meaning, "别名键释义", "definition alias -> meaning");
assert.ok(!vocab.some((v) => v.word === "nomeaning"), "word without meaning filtered");
assert.strictEqual(vocab.length, 6, "vocab capped at 6");

// --- normalizeSolveResult: full object clamps/caps + alias keys ---
const full = normalizeSolveResult({
  question: "Choose the correct answer: She ___ to school.",
  type: "语法单选",
  skill: "语法",
  keyPoints: ["第三人称单数", "一般现在时", "主谓一致", "动词变化", "多余考点"],
  analysis: ["第1步", "第2步", "第3步", "第4步", "第5步", "第6步", "第7步"],
  finalAnswer: "goes",
  traps: ["别漏 -es", "注意人称", "时态一致", "多余项"],
  words: [{ word: "school", meaning: "学校" }],
  comment: "做得不错！"
});
assert.strictEqual(full.questionText, "Choose the correct answer: She ___ to school.", "question alias -> questionText");
assert.strictEqual(full.questionType, "语法单选");
assert.strictEqual(full.skill, "语法");
assert.strictEqual(full.points.length, 4, "points capped at 4");
assert.strictEqual(full.steps.length, 6, "steps capped at 6");
assert.strictEqual(full.answer, "goes", "finalAnswer alias -> answer");
assert.strictEqual(full.pitfalls.length, 3, "pitfalls capped at 3");
assert.strictEqual(full.vocab[0].word, "school");

// invalid skill dropped to empty; empty object -> safe defaults, no throw
assert.strictEqual(normalizeSolveResult({ skill: "listening" }).skill, "");
const empty = normalizeSolveResult(null);
assert.strictEqual(empty.questionText, "");
assert.strictEqual(empty.answer, "");
assert.deepStrictEqual(empty.points, []);
assert.deepStrictEqual(empty.vocab, []);

// --- solveQuestion: model selection + parsing + guards (injected callModel, no network) ---
(async () => {
  // no message and no attachments -> 题目为空
  await assert.rejects(() => solveQuestion({}), /题目为空/);

  // text-only question -> text model, parses JSON out of choices content
  let capturedModel = null;
  const textCall = async (messages, opts) => {
    capturedModel = opts.model;
    return { choices: [{ message: { content: JSON.stringify({ questionText: "q", answer: "A", skill: "词汇" }) } }] };
  };
  const textRes = await solveQuestion({ message: "这题选什么", callModel: textCall });
  assert.strictEqual(capturedModel, settings.mimoTextModel, "no attachment -> text model");
  assert.strictEqual(textRes.answer, "A");
  assert.strictEqual(textRes.skill, "词汇");

  // image attachment -> multimodal model
  let mmModel = null;
  const mmCall = async (messages, opts) => {
    mmModel = opts.model;
    return { choices: [{ message: { content: JSON.stringify({ questionText: "读题", steps: ["先看图"], answer: "B" }) } }] };
  };
  const imgB64 = Buffer.from("fake-image-bytes").toString("base64");
  const mmRes = await solveQuestion({
    message: "",
    attachments: [{ name: "q.png", mimeType: "image/png", base64: imgB64 }],
    callModel: mmCall
  });
  assert.strictEqual(mmModel, settings.mimoMultimodalModel, "image attachment -> multimodal model");
  assert.strictEqual(mmRes.answer, "B");

  // model returns nothing useful -> 解题失败
  const emptyCall = async () => ({ choices: [{ message: { content: "{}" } }] });
  await assert.rejects(() => solveQuestion({ message: "x", callModel: emptyCall }), /解题失败/);

  console.log("solveQuestion.test.js passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
