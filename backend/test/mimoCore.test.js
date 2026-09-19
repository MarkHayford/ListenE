const assert = require("assert");
const {
  isRetryableMimoStatus,
  mimoBackoffDelayMs,
  parseRetryAfterMs,
  extractJson
} = require("../src/services/mimoCore");

// extractJson 现在统一走 jsonRepair 稳健解析：代码围栏 / 前后赘述 / 尾随逗号 / 漏逗号均可恢复。
assert.deepStrictEqual(extractJson('```json\n{"a":1,}\n```'), { a: 1 });
assert.deepStrictEqual(extractJson('前缀 {"a":1 "b":2} 后缀'), { a: 1, b: 2 });
assert.deepStrictEqual(extractJson([{ text: '{"ok":' }, { text: "true}" }]), { ok: true });
assert.throws(() => extractJson("no json here"), /did not return valid JSON/);

// 可重试状态码：限流 / 网关 / 暂时不可用
[408, 425, 429, 500, 502, 503, 504].forEach((s) => {
  assert.strictEqual(isRetryableMimoStatus(s), true, `${s} 应可重试`);
});
// 业务/客户端错误不重试
[200, 201, 400, 401, 403, 404, 409, 422].forEach((s) => {
  assert.strictEqual(isRetryableMimoStatus(s), false, `${s} 不应重试`);
});

// 指数退避（无抖动：rng=()=>0）
assert.strictEqual(mimoBackoffDelayMs(0, 500, 8000, () => 0), 500);
assert.strictEqual(mimoBackoffDelayMs(1, 500, 8000, () => 0), 1000);
assert.strictEqual(mimoBackoffDelayMs(2, 500, 8000, () => 0), 2000);
assert.strictEqual(mimoBackoffDelayMs(5, 500, 8000, () => 0), 8000); // 500*32=16000 → 封顶 8000

// 抖动：base + floor(rng*base)
assert.strictEqual(mimoBackoffDelayMs(0, 500, 8000, () => 0.5), 750);
assert.strictEqual(mimoBackoffDelayMs(0, 0, 8000, () => 0.9), 0);

// Retry-After 解析（秒数 / HTTP 日期 / 无效）
const hdr = (v) => ({ get: (k) => (String(k).toLowerCase() === "retry-after" ? v : null) });
assert.strictEqual(parseRetryAfterMs(hdr("2")), 2000);
assert.strictEqual(parseRetryAfterMs(hdr("0")), 0);
assert.strictEqual(parseRetryAfterMs(hdr(null)), null);
assert.strictEqual(parseRetryAfterMs(hdr("not-a-number")), null);
assert.strictEqual(parseRetryAfterMs(hdr("9999")), 60000); // 9999s → 封顶 60000ms
assert.strictEqual(parseRetryAfterMs({}), null);

console.log("mimoCore.test.js passed");
