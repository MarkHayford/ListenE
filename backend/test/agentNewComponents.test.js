const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test } = require("../src/services/mimoText");
const { classifyForcedAgentCardPrimary, assessAgentCardQuality } = __test;

// --- classifier picks the new components ---
assert.strictEqual(classifyForcedAgentCardPrimary("来一道七选五，主题是健康饮食").primary, "gap_match");
assert.strictEqual(classifyForcedAgentCardPrimary("做一个选句填空练习").primary, "gap_match");
assert.strictEqual(classifyForcedAgentCardPrimary("来一道图表作文，描述咖啡和茶的消费").primary, "chart_writing");
assert.strictEqual(classifyForcedAgentCardPrimary("chart writing task about population").primary, "chart_writing");
assert.strictEqual(classifyForcedAgentCardPrimary("给我一篇英语阅读理解，配3道选择题").primary, "reading");

// reading carries the requested question count
assert.strictEqual(classifyForcedAgentCardPrimary("给我一篇阅读理解，配3道选择题").count, 3);

// stability-hardened phrasings (caught by the stability sweep)
assert.strictEqual(classifyForcedAgentCardPrimary("给我一个描述图表的写作题").primary, "chart_writing");
assert.strictEqual(classifyForcedAgentCardPrimary("短文改错练习").primary, "error_hunt");
assert.strictEqual(classifyForcedAgentCardPrimary("给我一篇英语改错题").primary, "error_hunt");

// "七选五" must NOT be misrouted to a choice/cloze card
assert.notStrictEqual(classifyForcedAgentCardPrimary("来一道七选五").primary, "cloze");
assert.notStrictEqual(classifyForcedAgentCardPrimary("来一道七选五").primary, "question_set");

// --- quality assessor requires the matching component (no false cloze/question_set demand) ---
function warnings(message, components) {
  return assessAgentCardQuality({ message, cardSpec: { kind: "custom", title: "t", components } }).warnings;
}
const gapCard = [{ type: "header" }, { type: "gap_match", text: "A 【1】 B 【2】", options: ["x", "y", "z"], answer: "A | B" }, { type: "actions" }];
assert.ok(!warnings("来一道七选五", gapCard).some((w) => /expected/.test(w)), "valid gap_match card should not be told to add another component");

const chartCard = [{ type: "header" }, { type: "chart_writing", title: "c", text: "describe", tokens: ["a"], items: ["2020: 1"], answer: "bar" }, { type: "actions" }];
assert.ok(!warnings("来一道图表作文", chartCard).some((w) => /expected/.test(w)), "valid chart_writing card should pass");

const readingCard = [{ type: "header" }, { type: "reading", title: "r", text: "passage", questions: [{ questionText: "q1", options: ["a", "b"], correctAnswer: 0 }, { questionText: "q2", options: ["a", "b"], correctAnswer: 1 }] }, { type: "actions" }];
assert.ok(!warnings("给我一篇阅读理解配2道题", readingCard).some((w) => /expected/.test(w)), "valid reading card should pass");

// a gap_match request answered with cloze should be flagged (drives skeleton enforcement)
const wrongForGap = [{ type: "header" }, { type: "cloze", text: "I ___ it", options: ["did", "do"], answer: "did" }, { type: "actions" }];
assert.ok(warnings("来一道七选五", wrongForGap).some((w) => /expected gap_match/.test(w)), "cloze for a 七选五 request should warn expected gap_match");

console.log("agentNewComponents.test.js passed");
