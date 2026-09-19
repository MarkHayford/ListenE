const assert = require("assert");
const {
  normalizeAgentAnswerLookupText,
  normalizeAgentStringArray,
  normalizeShortAnswerLeakText,
  cleanAgentQuestionText,
  cleanAgentClozeInstructionLeak,
  stripAgentQuestionOptionLabel,
  stripAgentReplyDecorativeEmoji,
  stripNoAudioPlaybackSentences,
  stripCorrectionGrammarExplanation,
  stripQuestionSetExplanationsForScope,
  stripSentenceBuilderUsageInstruction,
  trimIncompleteAgentCardSentence
} = require("../src/services/textNormalize");

// 全部应是函数
for (const [n, f] of Object.entries({
  normalizeAgentAnswerLookupText, normalizeAgentStringArray, normalizeShortAnswerLeakText,
  cleanAgentQuestionText, cleanAgentClozeInstructionLeak, stripAgentQuestionOptionLabel,
  stripAgentReplyDecorativeEmoji, stripNoAudioPlaybackSentences, stripCorrectionGrammarExplanation,
  stripQuestionSetExplanationsForScope, stripSentenceBuilderUsageInstruction, trimIncompleteAgentCardSentence
})) assert.strictEqual(typeof f, "function", `${n} 应为函数`);

// ---- normalizeAgentAnswerLookupText：trim + 小写 + 折叠空白 ----
assert.strictEqual(normalizeAgentAnswerLookupText("  Hello   World "), "hello world");
assert.strictEqual(normalizeAgentAnswerLookupText("ABC"), "abc");
assert.strictEqual(normalizeAgentAnswerLookupText(""), "");
assert.strictEqual(normalizeAgentAnswerLookupText(null), "");

// ---- normalizeAgentStringArray：非数组→[]；逐项 compact + 去空 ----
assert.deepStrictEqual(normalizeAgentStringArray("nope"), []);
assert.deepStrictEqual(normalizeAgentStringArray(42), []);
assert.deepStrictEqual(normalizeAgentStringArray(["apple", "   ", "banana"]), ["apple", "banana"]);

// ---- normalizeShortAnswerLeakText：小写 + 非字母数字→空格 + 折叠 ----
assert.strictEqual(normalizeShortAnswerLeakText("Hello, World!"), "hello world");
assert.strictEqual(normalizeShortAnswerLeakText("A1-b2__c3"), "a1 b2 c3");
assert.strictEqual(normalizeShortAnswerLeakText(""), "");

// ---- cleanAgentQuestionText：去掉行首题号编号 ----
{
  const r = cleanAgentQuestionText("1) What is it?");
  assert.ok(!/^\s*\d/.test(r), "应去掉行首数字编号");
  assert.ok(r.includes("What is it?"), "应保留题干");
}

// ---- cleanAgentClozeInstructionLeak：去掉尾部「N blanks ...」指令泄漏 ----
assert.strictEqual(cleanAgentClozeInstructionLeak("Fill the gap. exactly 3 blanks here"), "Fill the gap.");
assert.strictEqual(cleanAgentClozeInstructionLeak("Just a plain sentence."), "Just a plain sentence.");

// ---- stripAgentQuestionOptionLabel：去掉选项字母前缀 ----
{
  const r = stripAgentQuestionOptionLabel("A. Apple pie");
  assert.ok(!/^\s*[A-Da-d][.)、:：]/.test(r), "应去掉选项字母前缀");
  assert.ok(r.includes("Apple pie"));
}

// ---- stripAgentReplyDecorativeEmoji：去装饰 emoji + 折叠空白 ----
assert.strictEqual(stripAgentReplyDecorativeEmoji("Hi ✅ there 🎯"), "Hi there");
assert.strictEqual(stripAgentReplyDecorativeEmoji("📄 Notes 📎"), "Notes");

// ---- stripNoAudioPlaybackSentences：剔除提及音频/播放/听的句子 ----
assert.strictEqual(
  stripNoAudioPlaybackSentences("Read the text. Listen to the audio. Answer now."),
  "Read the text. Answer now."
);
assert.strictEqual(stripNoAudioPlaybackSentences("点击播放音频。先读题。"), "先读题。");

// ---- stripCorrectionGrammarExplanation：去掉句尾语法注释括号 ----
assert.strictEqual(
  stripCorrectionGrammarExplanation("She go to school (use present tense)"),
  "She go to school"
);

// ---- stripQuestionSetExplanationsForScope：按 scope 抹掉 question_set 解析 ----
{
  const comp = { type: "question_set", questions: [{ q: "x", explanation: "because reasons" }] };
  const suppressed = stripQuestionSetExplanationsForScope(comp, { suppressAnswerExplanation: true });
  assert.strictEqual(suppressed.questions[0].explanation, "");
  assert.strictEqual(comp.questions[0].explanation, "because reasons"); // 不改原对象
  // scope 不抑制时原样返回
  assert.strictEqual(stripQuestionSetExplanationsForScope(comp, {}), comp);
  // 非 question_set 原样返回
  const other = { type: "summary" };
  assert.strictEqual(stripQuestionSetExplanationsForScope(other, { suppressAnswerExplanation: true }), other);
}

// ---- stripSentenceBuilderUsageInstruction：是「拖词造句」指令则清空，否则保留 ----
assert.strictEqual(stripSentenceBuilderUsageInstruction("Drag the words to form a sentence"), "");
{
  const r = stripSentenceBuilderUsageInstruction("The cat sat on the mat.");
  assert.ok(r.includes("cat sat on the mat"), "正常句子应保留");
}

// ---- trimIncompleteAgentCardSentence：截到最后一个完整句末；否则修剪悬挂词 ----
assert.strictEqual(
  trimIncompleteAgentCardSentence("This is a complete sentence. And more incomplete"),
  "This is a complete sentence."
);
assert.strictEqual(trimIncompleteAgentCardSentence("hello world and"), "hello world");
assert.strictEqual(trimIncompleteAgentCardSentence(""), "");

console.log("textNormalize.test.js passed");
