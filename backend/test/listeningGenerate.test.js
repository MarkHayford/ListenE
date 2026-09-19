const assert = require("assert");
const {
  speechRateHint,
  normalizeVoiceGender,
  parseCountToken,
  firstCountMatch
} = require("../src/services/listeningGenerate");

// speechRateHint: known rates map to descriptive phrases; unknown -> medium.
assert.match(speechRateHint("fast"), /fast pace/);
assert.match(speechRateHint("slow"), /slow pace/);
assert.match(speechRateHint("medium"), /medium pace/);
assert.match(speechRateHint("whatever"), /medium pace/, "unknown rate falls back to medium");
assert.match(speechRateHint(undefined), /medium pace/);

// normalizeVoiceGender: zh/en aliases; default female.
for (const v of ["male", "男", "男声", "M"]) assert.strictEqual(normalizeVoiceGender(v), "male", `${v} -> male`);
for (const v of ["female", "女", "女声", "F"]) assert.strictEqual(normalizeVoiceGender(v), "female", `${v} -> female`);
assert.strictEqual(normalizeVoiceGender(""), "female", "empty defaults to female");
assert.strictEqual(normalizeVoiceGender("nonsense"), "female", "unknown defaults to female");

// parseCountToken: digits, English words, Chinese numerals (incl. 十/十二/二十), else null.
assert.strictEqual(parseCountToken("3"), 3);
assert.strictEqual(parseCountToken("five"), 5);
assert.strictEqual(parseCountToken("三"), 3);
assert.strictEqual(parseCountToken("两"), 2);
assert.strictEqual(parseCountToken("十"), 10);
assert.strictEqual(parseCountToken("十二"), 12);
assert.strictEqual(parseCountToken("二十"), 20);
assert.strictEqual(parseCountToken(""), null);
assert.strictEqual(parseCountToken("abc"), null);

// firstCountMatch: returns first in-range captured count; out-of-range / no-match -> null.
assert.strictEqual(firstCountMatch("give me 5 items", [/(\d+)\s+items/], 1, 12), 5);
assert.strictEqual(firstCountMatch("three items here", [/(one|two|three)\s+items/], 1, 12), 3);
assert.strictEqual(firstCountMatch("99 items", [/(\d+)\s+items/], 1, 12), null, "out of range -> null");
assert.strictEqual(firstCountMatch("no number here", [/(\d+)\s+items/], 1, 12), null);

console.log("listeningGenerate.test.js passed");
