const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test } = require("../src/services/dailyChallenge");
const { normalizeQuestions, resolveAnswerIndex, publicQuestion, pickDailyTypes, gradeAnswer, DAILY_TYPES } = __test;

// resolveAnswerIndex: text / letter / number
const opts = ["apple", "banana", "cherry", "date"];
assert.strictEqual(resolveAnswerIndex("banana", opts), 1, "text match");
assert.strictEqual(resolveAnswerIndex("C", opts), 2, "letter");
assert.strictEqual(resolveAnswerIndex("4", opts), 3, "1-based number");
assert.strictEqual(resolveAnswerIndex("nope", opts), -1, "no match -> -1 (no silent default to A)");

// normalizeQuestions: caps options at 4, resolves answer, defaults skill, drops invalid
const qs = normalizeQuestions([
  { questionText: "What does 'big' mean?", options: ["大", "小", "快", "慢", "extra"], answer: "大", explanation: "释义", skill: "词汇" },
  { questionText: "bad - only one option", options: ["x"], answer: "x" },
  { question: "alias key + letter answer", options: ["a", "b", "c", "d"], correctAnswer: "B", skill: "不存在" }
]);
assert.strictEqual(qs.length, 2, "invalid (1 option) dropped");
assert.strictEqual(qs[0].options.length, 4, "options capped at 4");
assert.strictEqual(qs[0].correctAnswer, 0, "'大' -> index 0");
assert.strictEqual(qs[0].skill, "词汇");
assert.strictEqual(qs[1].questionText, "alias key + letter answer", "question alias");
assert.strictEqual(qs[1].correctAnswer, 1, "letter B -> 1");
assert.strictEqual(qs[1].skill, "语法", "invalid skill -> default 语法");

// publicQuestion hides answer + explanation
const pub = publicQuestion(qs[0]);
assert.strictEqual(pub.correctAnswer, undefined, "answer hidden in public form");
assert.strictEqual(pub.explanation, undefined, "explanation hidden");
assert.ok(Array.isArray(pub.options) && pub.options.length === 4);
assert.strictEqual(pub.skill, "词汇");
assert.strictEqual(pub.type, "mcq", "type defaults to mcq");

// --- multi-type normalization ---
const mixed = normalizeQuestions([
  { type: "cloze", text: "I have ___ finished it.", options: ["already", "yet", "ever", "still"], answer: "already", skill: "语法" },
  { type: "sentence_builder", tokens: ["it", "I", "finished", "have"], answer: "I have finished it", skill: "语法" },
  { type: "ordering", items: ["Then we ate.", "First we cooked.", "Finally we cleaned."], answer: ["First we cooked.", "Then we ate.", "Finally we cleaned."], skill: "阅读" },
  { type: "short_answer", questionText: "Past tense of 'go'?", answer: "went", skill: "语法" },
  { type: "translation", source: "我每天学习英语。", answer: "I study English every day.", skill: "写作" },
  { type: "cloze", text: "no blank here", options: ["a", "b"], answer: "a" }
]);
assert.strictEqual(mixed.length, 5, "cloze without a blank is dropped");
const byType = Object.fromEntries(mixed.map((q) => [q.type, q]));

// cloze: stores correct index into the word bank + revealed answer word
assert.strictEqual(byType.cloze.type, "cloze");
assert.strictEqual(byType.cloze.correctAnswer, 0, "cloze correct word index resolved");
assert.strictEqual(byType.cloze.answer, "already");

// sentence_builder: tokens are a permutation of the answer words
assert.ok(Array.isArray(byType.sentence_builder.tokens) && byType.sentence_builder.tokens.length === 4);
assert.deepStrictEqual(
  byType.sentence_builder.tokens.slice().sort(),
  ["I", "finished", "have", "it"].sort(),
  "builder tokens are the answer words"
);

// ordering: items are a permutation of the answer sentences
assert.strictEqual(byType.ordering.answer.length, 3);
assert.deepStrictEqual(byType.ordering.items.slice().sort(), byType.ordering.answer.slice().sort());

// publicQuestion hides answers per type
const pubCloze = publicQuestion(byType.cloze);
assert.strictEqual(pubCloze.correctAnswer, undefined);
assert.strictEqual(pubCloze.answer, undefined);
assert.ok(Array.isArray(pubCloze.options));
const pubBuilder = publicQuestion(byType.sentence_builder);
assert.strictEqual(pubBuilder.answer, undefined);
assert.ok(Array.isArray(pubBuilder.tokens));
const pubOrdering = publicQuestion(byType.ordering);
assert.strictEqual(pubOrdering.answer, undefined);
assert.ok(Array.isArray(pubOrdering.items));
const pubShort = publicQuestion(byType.short_answer);
assert.strictEqual(pubShort.answer, undefined);
assert.strictEqual(pubShort.questionText, "Past tense of 'go'?");

// --- gradeAnswer per type ---
assert.ok(gradeAnswer(byType.cloze, { choice: 0 }).correct, "cloze correct choice");
assert.ok(!gradeAnswer(byType.cloze, { choice: 1 }).correct, "cloze wrong choice");
assert.ok(gradeAnswer(byType.cloze, 0).correct, "cloze legacy numeric pick");
assert.ok(gradeAnswer({ type: "mcq", correctAnswer: 2 }, { choice: 2 }).correct, "mcq correct");
assert.ok(!gradeAnswer({ type: "mcq", correctAnswer: 2 }, {}).correct, "mcq unanswered wrong");
assert.ok(gradeAnswer(byType.sentence_builder, { order: ["I", "have", "finished", "it"] }).correct, "builder correct order");
assert.ok(!gradeAnswer(byType.sentence_builder, { order: ["have", "I", "finished", "it"] }).correct, "builder wrong order");
assert.ok(gradeAnswer(byType.ordering, { order: byType.ordering.answer }).correct, "ordering correct");
assert.ok(!gradeAnswer(byType.ordering, { order: byType.ordering.answer.slice().reverse() }).correct, "ordering wrong");
assert.ok(gradeAnswer(byType.short_answer, { text: "Went." }).correct, "short answer normalized match");
assert.ok(gradeAnswer(byType.translation, { text: "i study english every day" }).correct, "translation normalized match");
assert.ok(!gradeAnswer(byType.translation, { text: "" }).correct, "empty translation wrong");

// --- lenient short_answer / translation via accept list ---
const lenient = normalizeQuestions([
  { type: "short_answer", questionText: "A synonym of 'happy'?", answer: "glad", accept: ["joyful", "pleased"], skill: "词汇" },
  { type: "translation", source: "我同意。", answer: "I agree.", accept: ["I agree with you.", "Agreed."], skill: "写作" }
]);
const sa = lenient.find((q) => q.type === "short_answer");
const tr = lenient.find((q) => q.type === "translation");
assert.ok(Array.isArray(sa.accept) && sa.accept.includes("glad"), "accept list includes the primary answer");
assert.ok(gradeAnswer(sa, { text: "Joyful!" }).correct, "short answer accepts a variant (normalized)");
assert.ok(gradeAnswer(sa, { text: "glad" }).correct, "short answer accepts the primary answer");
assert.ok(!gradeAnswer(sa, { text: "sad" }).correct, "short answer rejects a wrong answer");
assert.ok(gradeAnswer(tr, { text: "agreed" }).correct, "translation accepts a variant");
assert.ok(gradeAnswer(tr, { text: "I agree" }).correct, "translation accepts the primary (normalized)");
assert.ok(!gradeAnswer(tr, { text: "I disagree" }).correct, "translation rejects a wrong answer");
// accept/answer must stay hidden in the public form
assert.strictEqual(publicQuestion(sa).accept, undefined, "accept hidden in public form");
assert.strictEqual(publicQuestion(sa).answer, undefined, "short answer hidden in public form");
assert.strictEqual(publicQuestion(tr).answer, undefined, "translation answer hidden in public form");

// --- pickDailyTypes: 5 valid types from the pool, deterministic with injected rng ---
const picked = pickDailyTypes(() => 0, 5);
assert.strictEqual(picked.length, 5);
assert.ok(picked.every((t) => DAILY_TYPES.includes(t)), "picked types are from the pool");
let seed = 0.123;
const rng = () => (seed = (seed * 9301 + 49297) % 233280 / 233280);
const picked2 = pickDailyTypes(rng, 5);
assert.strictEqual(new Set(picked2).size, 5, "5 distinct types selected from a 6-type pool");

console.log("dailyChallenge.test.js passed");
