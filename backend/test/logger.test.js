const assert = require("assert");
const { formatLogLine, requestLogLevel } = require("../src/services/logger");

const now = new Date("2026-01-01T00:00:00.000Z");

// 结构化单行 JSON + 字段合并
const line = formatLogLine("info", "request", { requestId: "abc", status: 200, durationMs: 12 }, now);
assert.ok(!line.includes("\n"), "日志应为单行");
const obj = JSON.parse(line);
assert.strictEqual(obj.ts, "2026-01-01T00:00:00.000Z");
assert.strictEqual(obj.level, "info");
assert.strictEqual(obj.msg, "request");
assert.strictEqual(obj.requestId, "abc");
assert.strictEqual(obj.status, 200);
assert.strictEqual(obj.durationMs, 12);

// 非对象 fields 安全忽略
const obj2 = JSON.parse(formatLogLine("error", "boom", null, now));
assert.strictEqual(obj2.level, "error");
assert.strictEqual(obj2.msg, "boom");

// msg 缺省安全为空串
assert.strictEqual(JSON.parse(formatLogLine("warn", undefined, {}, now)).msg, "");

// 请求日志分级：5xx=error；慢请求=warn；其余(含 4xx)=info
assert.strictEqual(requestLogLevel(200, 10, 3000), "info");
assert.strictEqual(requestLogLevel(204, 0, 3000), "info");
assert.strictEqual(requestLogLevel(301, 10, 3000), "info");
assert.strictEqual(requestLogLevel(400, 10, 3000), "info");
assert.strictEqual(requestLogLevel(401, 10, 3000), "info");
assert.strictEqual(requestLogLevel(429, 10, 3000), "info");
assert.strictEqual(requestLogLevel(500, 10, 3000), "error");
assert.strictEqual(requestLogLevel(503, 10, 3000), "error");
// 慢请求（2xx 但 >= 阈值）→ warn；边界：恰好等于阈值算慢，差 1ms 不算
assert.strictEqual(requestLogLevel(200, 3000, 3000), "warn");
assert.strictEqual(requestLogLevel(200, 5000, 3000), "warn");
assert.strictEqual(requestLogLevel(200, 2999, 3000), "info");
// 5xx 即使慢也保持 error（优先级最高）
assert.strictEqual(requestLogLevel(500, 9999, 3000), "error");
// slowMs<=0 关闭慢判定
assert.strictEqual(requestLogLevel(200, 999999, 0), "info");

console.log("logger.test.js passed");
