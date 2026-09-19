const assert = require("assert");

// 防御性设置（与 agentMessageIntent.test.js 一致），保证可安全 require mimoAgentMedia 验证 re-export。
process.env.MIMO_API_KEY = process.env.MIMO_API_KEY || "test-key";
process.env.MIMO_BASE_URL = process.env.MIMO_BASE_URL || "http://mimo.test/v1";

const {
  extractJsonFromContent,
  parseJsonWithLooseRepairs,
  __test
} = require("../src/services/jsonRepair");
const { repairLooseJsonText, isInsideJsonString, isJsonValueEnd, isJsonValueStart } = __test;

// ---- extractJsonFromContent：从“脏”模型输出里稳健抽取 JSON ----
// 纯 JSON
assert.deepStrictEqual(extractJsonFromContent('{"a":1,"b":"x"}'), { a: 1, b: "x" });
// 前后夹散文/噪声：取最外层 { ... }
assert.deepStrictEqual(extractJsonFromContent("好的，结果如下：{\"a\":1} 以上。"), { a: 1 });
// ```json 围栏
assert.deepStrictEqual(extractJsonFromContent("```json\n{\"a\":1}\n```"), { a: 1 });
// 裸 ``` 围栏
assert.deepStrictEqual(extractJsonFromContent("```\n{\"a\":1}\n```"), { a: 1 });
// 嵌套对象：lastIndexOf('}') 取到最外层闭合
assert.deepStrictEqual(extractJsonFromContent('{"a":{"b":2}}'), { a: { b: 2 } });
// 没有 JSON → 抛错
assert.throws(() => extractJsonFromContent("no json here"), /did not return valid JSON/);
assert.throws(() => extractJsonFromContent(""), /did not return valid JSON/);

// ---- parseJsonWithLooseRepairs：合法直通 + 保守修复 ----
// 合法直接通过
assert.deepStrictEqual(parseJsonWithLooseRepairs('{"a":[1,2,3]}'), { a: [1, 2, 3] });
// 尾随逗号（对象/数组）
assert.deepStrictEqual(parseJsonWithLooseRepairs('{"a":1,}'), { a: 1 });
assert.deepStrictEqual(parseJsonWithLooseRepairs('{"a":[1,2,]}'), { a: [1, 2] });
// 值之间漏逗号（有空白分隔）
assert.deepStrictEqual(parseJsonWithLooseRepairs('{"a":1 "b":2}'), { a: 1, b: 2 });
// 数组元素之间漏逗号
assert.deepStrictEqual(parseJsonWithLooseRepairs('{"a":[1 2 3]}'), { a: [1, 2, 3] });
// 相邻 }"key" 漏逗号（无空白，走 adjacent 规则）
assert.deepStrictEqual(parseJsonWithLooseRepairs('{"a":{"x":1}"b":2}'), { a: { x: 1 }, b: 2 });
// 字符串内部的逗号/括号不被误改
assert.deepStrictEqual(parseJsonWithLooseRepairs('{"a":"1,2 }"}'), { a: "1,2 }" });

// ---- 抽取 + 修复联动：围栏里包着待修复 JSON ----
assert.deepStrictEqual(extractJsonFromContent("```json\n{\"a\":1,}\n```"), { a: 1 });

// ---- repairLooseJsonText：返回修复后的字符串（纯函数，幂等于合法输入）----
assert.strictEqual(repairLooseJsonText('{"a":1,}'), '{"a":1}');
assert.strictEqual(repairLooseJsonText('{"a":1}'), '{"a":1}');

// ---- 谓词：isJsonValueEnd / isJsonValueStart ----
for (const c of ["}", "]", "\"", "0", "9", "e", "E", "l", "L"]) assert.ok(isJsonValueEnd(c), `应判为值结尾: ${c}`);
for (const c of ["{", "[", ":", " ", "x"]) assert.ok(!isJsonValueEnd(c), `不应判为值结尾: ${c}`);
for (const c of ["{", "[", "\"", "-", "0", "9", "t", "f", "n"]) assert.ok(isJsonValueStart(c), `应判为值开头: ${c}`);
for (const c of ["}", "]", ":", " ", "x"]) assert.ok(!isJsonValueStart(c), `不应判为值开头: ${c}`);

// ---- isInsideJsonString：字符串内 vs 结构区，转义引号不误判 ----
{
  const s = '{"ab":12}';
  assert.strictEqual(isInsideJsonString(s, s.indexOf("a")), true); // "ab" 内
  assert.strictEqual(isInsideJsonString(s, s.indexOf("1")), false); // 数字 12 在结构区
  const e = '{"a":"x\\"y"}'; // 实际字符串含一个被转义的引号
  assert.strictEqual(isInsideJsonString(e, e.indexOf("y")), true); // 转义引号未结束字符串
}

// ---- re-export 零破坏：mimoAgentMedia 暴露的就是本模块的同一函数 ----
const viaMedia = require("../src/services/mimoAgentMedia").extractJsonFromContent;
assert.strictEqual(viaMedia, extractJsonFromContent, "mimoAgentMedia 应 re-export 同一 extractJsonFromContent");
assert.deepStrictEqual(viaMedia("```json\n{\"ok\":true}\n```"), { ok: true });

console.log("jsonRepair.test.js passed");
