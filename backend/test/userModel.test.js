const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test } = require("../src/services/userModel");
const { normalizeUserModel, mergeUserModel, buildUserModelHint, normalizeAbilities, strList } = __test;

// normalizeAbilities: 仅六技能、钳制 0..100、丢弃未知/非数值
const ab = normalizeAbilities({ "听力": 70, "语法": 120, "口语": -5, "无效技能": 50, "阅读": "x" });
assert.strictEqual(ab["听力"], 70);
assert.strictEqual(ab["语法"], 100, "clamp 上限");
assert.strictEqual(ab["口语"], 0, "clamp 下限");
assert.ok(!("无效技能" in ab), "未知技能丢弃");
assert.ok(!("阅读" in ab), "非数值丢弃");

// strList: 去重(大小写不敏感)、trim、上限
assert.deepStrictEqual(strList(["  a ", "A", "b", ""], 12), ["a", "b"]);
assert.strictEqual(strList(Array.from({ length: 20 }, (_, i) => `x${i}`), 12).length, 12);

// normalizeUserModel: 安全默认
const empty = normalizeUserModel(null);
assert.deepStrictEqual(empty.abilities, {});
assert.deepStrictEqual(empty.preferences, []);
assert.deepStrictEqual(empty.weaknesses, []);
assert.strictEqual(empty.notes, "");

// merge: 能力新值优先；列表去重合并；notes 给则覆盖、不给则保留
const base = { abilities: { "听力": 50, "语法": 40 }, preferences: ["英式发音"], weaknesses: ["时态"], notes: "old" };
const merged = mergeUserModel(base, { abilities: { "语法": 60, "口语": 55 }, weaknesses: ["时态", "介词"], preferences: [] });
assert.strictEqual(merged.abilities["听力"], 50, "保留旧能力");
assert.strictEqual(merged.abilities["语法"], 60, "新值覆盖");
assert.strictEqual(merged.abilities["口语"], 55, "新增能力");
assert.deepStrictEqual(merged.weaknesses, ["时态", "介词"], "弱点去重合并");
assert.deepStrictEqual(merged.preferences, ["英式发音"], "偏好保留");
assert.strictEqual(merged.notes, "old", "patch 无 notes 时保留");
assert.strictEqual(mergeUserModel(base, { notes: "new note" }).notes, "new note", "patch 有 notes 时覆盖");

// buildUserModelHint: 空 → 空串；有值 → 含水平/弱点/偏好/备注
assert.strictEqual(buildUserModelHint(null), "");
const hint = buildUserModelHint({
  abilities: { "听力": 65, "语法": 55 },
  weaknesses: ["虚拟语气"],
  preferences: ["英式发音"],
  notes: "喜欢科技话题"
});
assert.match(hint, /全局用户模型/);
assert.match(hint, /CEFR/);
assert.match(hint, /虚拟语气/);
assert.match(hint, /英式发音/);
assert.match(hint, /喜欢科技话题/);

console.log("userModel.test.js passed");
