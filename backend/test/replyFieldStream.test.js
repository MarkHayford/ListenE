const assert = require("assert");
const { createReplyFieldStreamExtractor } = require("../src/services/replyFieldStream");

function collect() {
  const chunks = [];
  const extractor = createReplyFieldStreamExtractor((t) => chunks.push(t));
  return { extractor, text: () => chunks.join("") };
}

// 基本：单块完整 JSON
{
  const { extractor, text } = collect();
  extractor.push('{"reply": "Hello there", "intent": "chat"}');
  assert.strictEqual(text(), "Hello there");
}

// 跨块切割：key、冒号、引号、内容都被任意切开
{
  const { extractor, text } = collect();
  ['{"re', 'ply"', ' :', ' "He', 'llo ', '世界', '"', ', "intent": "chat"}'].forEach((c) => extractor.push(c));
  assert.strictEqual(text(), "Hello 世界");
}

// 转义：\n \t \" \\ 与 \uXXXX（含跨块切开的 unicode）
{
  const { extractor, text } = collect();
  extractor.push('{"reply": "line1\\nline2\\t\\"quoted\\" back\\\\slash \\u4f60');
  extractor.push('\\u597d end", "intent": "chat"}');
  assert.strictEqual(text(), 'line1\nline2\t"quoted" back\\slash 你好 end');
}

// reply 不是第一个字段也能命中
{
  const { extractor, text } = collect();
  extractor.push('{"intent": "chat", "reply": "second field"}');
  assert.strictEqual(text(), "second field");
}

// 字符串闭合后的内容全部忽略（包括后续再出现 "reply"）
{
  const { extractor, text } = collect();
  extractor.push('{"reply": "done", "outputFiles": [{"content": "\\"reply\\": \\"fake\\""}]}');
  assert.strictEqual(text(), "done");
}

// 假 key：字符串值里出现 "reply" 但后面不是冒号 → 重置继续找
{
  const { extractor, text } = collect();
  extractor.push('{"note": "say \\"reply\\" now", "reply": "real"}');
  assert.strictEqual(text(), "real");
}

// 没有 reply 字段 → 无输出且不抛错
{
  const { extractor, text } = collect();
  extractor.push('{"intent": "chat"}');
  assert.strictEqual(text(), "");
}

console.log("replyFieldStream.test.js passed");
