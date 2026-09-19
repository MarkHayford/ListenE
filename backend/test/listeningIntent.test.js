const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test } = require("../src/services/mimoText");
const {
  isExplicitNewListeningPracticeRequest,
  isAgentMethodAdviceOnlyRequest
} = __test;

// Regression: "练习…听力" word order (practice verb BEFORE 听力) must route to new listening.
// Bug report: 「我想练习面试场景的英语听力 简单一些」误判为场景练习卡，而非听力素材。
assert.strictEqual(
  isExplicitNewListeningPracticeRequest("我想练习面试场景的英语听力 简单一些"),
  true,
  "练习面试场景的英语听力 应识别为听力素材请求"
);
assert.strictEqual(isExplicitNewListeningPracticeRequest("我想练习听力"), true);
assert.strictEqual(isExplicitNewListeningPracticeRequest("练习一下日常对话的英语听力"), true);

// Still works for the original word orders.
assert.strictEqual(isExplicitNewListeningPracticeRequest("生成一段听力素材"), true);
assert.strictEqual(isExplicitNewListeningPracticeRequest("给我来段听力"), true);
assert.strictEqual(isExplicitNewListeningPracticeRequest("再来一套四级听力"), true);

// Must NOT over-trigger on advice / negation / non-listening practice.
assert.strictEqual(
  isAgentMethodAdviceOnlyRequest("怎么练习听力"),
  true,
  "怎么练习听力 属于方法咨询"
);
assert.strictEqual(
  isExplicitNewListeningPracticeRequest("怎么练习听力"),
  false,
  "方法咨询不应强制听力生成"
);
assert.strictEqual(
  isExplicitNewListeningPracticeRequest("听力练习有什么技巧"),
  false,
  "听力技巧咨询不应强制听力生成"
);
assert.strictEqual(
  isExplicitNewListeningPracticeRequest("不要听力，只要题目"),
  false,
  "明确拒绝听力时不应强制听力生成"
);
assert.strictEqual(
  isExplicitNewListeningPracticeRequest("给我一道单词含义选择题"),
  false,
  "纯题目请求不是听力素材"
);

console.log("listeningIntent.test.js passed");
