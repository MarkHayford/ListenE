const assert = require("assert");
const {
  bodyLimitForRoute,
  AUTH_BODY_LIMIT,
  DEFAULT_BODY_LIMIT,
  LARGE_BODY_LIMIT
} = require("../src/services/bodyLimits");

// 三档默认值合理且严格递增：auth < default < large
assert.ok(AUTH_BODY_LIMIT < DEFAULT_BODY_LIMIT, "auth 档应小于 default 档");
assert.ok(DEFAULT_BODY_LIMIT < LARGE_BODY_LIMIT, "default 档应小于 large 档");

// auth 档：登录/注册/登出 → 收紧到 AUTH_BODY_LIMIT
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/auth/login"), AUTH_BODY_LIMIT);
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/auth/register"), AUTH_BODY_LIMIT);
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/auth/logout"), AUTH_BODY_LIMIT);

// large 档：多模态/音频/文件类 → LARGE_BODY_LIMIT
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/agent/chat"), LARGE_BODY_LIMIT);
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/agent/asr"), LARGE_BODY_LIMIT);
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/agent/speaking"), LARGE_BODY_LIMIT);
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/library/files"), LARGE_BODY_LIMIT);

// default 档：其余 JSON 端点
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/agent/writing"), DEFAULT_BODY_LIMIT);
assert.strictEqual(bodyLimitForRoute("PUT", "/api/v1/agent/workspaces/abc/messages"), DEFAULT_BODY_LIMIT);
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/listening/package"), DEFAULT_BODY_LIMIT);
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/translate"), DEFAULT_BODY_LIMIT);

// 归一化：尾斜杠 / query string 不影响分类
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/auth/login/"), AUTH_BODY_LIMIT);
assert.strictEqual(bodyLimitForRoute("POST", "/api/v1/auth/login?next=1"), AUTH_BODY_LIMIT);
assert.strictEqual(bodyLimitForRoute("post", "/api/v1/agent/chat"), LARGE_BODY_LIMIT); // 方法大小写不敏感

// 无请求体的方法不限制（GET/DELETE/OPTIONS）
assert.strictEqual(bodyLimitForRoute("GET", "/api/v1/auth/me"), Infinity);
assert.strictEqual(bodyLimitForRoute("GET", "/api/v1/agent/workspaces"), Infinity);
assert.strictEqual(bodyLimitForRoute("DELETE", "/api/v1/library/files/x"), Infinity);
assert.strictEqual(bodyLimitForRoute("OPTIONS", "/api/v1/auth/login"), Infinity);

console.log("bodyLimits.test.js passed");
