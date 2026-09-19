const assert = require("assert");

process.env.MIMO_API_KEY = "";

const { __test } = require("../src/services/learningWorkspace");

assert.strictEqual(__test.isListeningPracticeNeed("CET4 word bank cloze practice"), false);
assert.strictEqual(__test.isListeningPracticeNeed("我想要练习英语四级的选词填空"), false);
assert.strictEqual(__test.isListeningPracticeNeed("generate a CET4 listening practice"), true);
assert.strictEqual(__test.isListeningPracticeNeed("生成一套英语四级听力训练"), true);

console.log("learningWorkspace.intent.test.js passed");
