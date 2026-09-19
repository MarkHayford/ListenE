const assert = require("assert");
const { securityHeaders, SECURITY_HEADERS } = require("../src/services/securityHeaders");

const headers = securityHeaders();
assert.strictEqual(headers["X-Content-Type-Options"], "nosniff");
assert.strictEqual(headers["X-Frame-Options"], "DENY");
assert.strictEqual(headers["Referrer-Policy"], "no-referrer");
assert.strictEqual(headers["X-DNS-Prefetch-Control"], "off");

// 返回副本，避免调用方意外修改共享常量
headers["X-Frame-Options"] = "MUTATED";
assert.strictEqual(SECURITY_HEADERS["X-Frame-Options"], "DENY", "securityHeaders() 应返回副本");

console.log("securityHeaders.test.js passed");
