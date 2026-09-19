const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test } = require("../src/services/mimoText");
const { isStudyPlanRequest } = __test;

// 计划生成请求 -> true
assert.strictEqual(isStudyPlanRequest("给我安排个两周背单词计划，每天晚上8点"), true);
assert.strictEqual(isStudyPlanRequest("帮我制定一个四级复习计划"), true);
assert.strictEqual(isStudyPlanRequest("做个学习计划吧"), true);
assert.strictEqual(isStudyPlanRequest("我想要一份听力训练计划"), true);
assert.strictEqual(isStudyPlanRequest("make me a study plan for IELTS"), true);
assert.strictEqual(isStudyPlanRequest("每天晚上8点提醒我背单词，安排一下"), true);

// 咨询/非生成 -> false
assert.strictEqual(isStudyPlanRequest("怎么制定学习计划"), false);
assert.strictEqual(isStudyPlanRequest("这个计划怎么改"), false);
assert.strictEqual(isStudyPlanRequest("给我一道单词选择题"), false);
assert.strictEqual(isStudyPlanRequest("生成一段听力素材"), false);
assert.strictEqual(isStudyPlanRequest(""), false);

console.log("studyPlanIntent.test.js passed");
