const assert = require("assert");
const { __test } = require("../src/services/authMonitor");

const {
  compactText,
  normalizeEmail,
  requestMeta,
  isSessionActive,
  tokenExpiresAt
} = __test;

assert.strictEqual(normalizeEmail("  Judge@ListenE.App  "), "judge@listene.app");
assert.strictEqual(compactText(" a \n b \t c ", 20), "a b c");
assert.strictEqual(compactText("x".repeat(10), 4), "xxxx");

{
  const meta = requestMeta({ headers: { "user-agent": "A".repeat(600) }, ip: "203.0.113.8" });
  assert.strictEqual(meta.ip, "203.0.113.8");
  assert.strictEqual(meta.userAgent.length, 512);
}

{
  const now = Date.now();
  assert.strictEqual(isSessionActive({ revoked_at: 0, expires_at: now + 60_000, last_seen_at: now - 1_000 }, now, 15_000), true);
  assert.strictEqual(isSessionActive({ revoked_at: now, expires_at: now + 60_000, last_seen_at: now }, now, 15_000), false);
  assert.strictEqual(isSessionActive({ revoked_at: 0, expires_at: now - 1, last_seen_at: now }, now, 15_000), false);
  assert.strictEqual(isSessionActive({ revoked_at: 0, expires_at: now + 60_000, last_seen_at: now - 20_000 }, now, 15_000), false);
  assert.ok(tokenExpiresAt(now) > now);
}

console.log("authMonitor.test.js passed");
