const assert = require("assert");
const {
  classifyRateLimitRoute,
  clientIpFromRequest,
  createRateLimiter
} = require("../src/services/rateLimit");

// 滑动窗口：窗口内放行 limit 次，超出拦截，窗口滑过后恢复
{
  const rl = createRateLimiter();
  const now = 1_000_000;
  assert.strictEqual(rl.hit("k", 3, 1000, now).allowed, true);
  assert.strictEqual(rl.hit("k", 3, 1000, now + 100).allowed, true);
  assert.strictEqual(rl.hit("k", 3, 1000, now + 200).allowed, true);
  const blocked = rl.hit("k", 3, 1000, now + 300);
  assert.strictEqual(blocked.allowed, false);
  assert.ok(blocked.retryAfterMs > 0 && blocked.retryAfterMs <= 1000);
  // 第一条（now）滑出窗口后应再次放行
  assert.strictEqual(rl.hit("k", 3, 1000, now + 1100).allowed, true);
}

// limit<=0 表示不限流
{
  const rl = createRateLimiter();
  for (let i = 0; i < 100; i += 1) {
    assert.strictEqual(rl.hit("x", 0, 1000, 1000 + i).allowed, true);
  }
}

// 不同 key 互相隔离
{
  const rl = createRateLimiter();
  const now = 5000;
  assert.strictEqual(rl.hit("a", 1, 1000, now).allowed, true);
  assert.strictEqual(rl.hit("a", 1, 1000, now + 10).allowed, false);
  assert.strictEqual(rl.hit("b", 1, 1000, now + 10).allowed, true);
}

// 路由分类
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/auth/login"), "auth");
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/auth/register"), "auth");
assert.strictEqual(classifyRateLimitRoute("GET", "/api/v1/auth/me"), null);
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/agent/chat"), "ai");
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/agent/tts"), "ai");
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/listening/generate?skipAudio=true"), "ai");
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/analyze-mistakes"), "ai");
// 昂贵的 LLM/TTS 端点（含 SSE 流式与多模态解题）也须限流，防止未授权的成本放大/DoS
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/agent/solve"), "ai");
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/agent/organize"), "ai");
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/agent/micro"), "ai");
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/agent/chat/stream"), "ai");
assert.strictEqual(classifyRateLimitRoute("POST", "/api/v1/listening/generate/stream"), "ai");
assert.strictEqual(classifyRateLimitRoute("GET", "/api/v1/agent/workspaces"), null);
assert.strictEqual(classifyRateLimitRoute("GET", "/api/v1/history"), null);
// readiness 探活打 DB → probe 档限流；liveness 是内存响应 → 不限流
assert.strictEqual(classifyRateLimitRoute("GET", "/readyz"), "probe");
assert.strictEqual(classifyRateLimitRoute("GET", "/healthz"), null);
assert.strictEqual(classifyRateLimitRoute("GET", "/api/health/ready"), null, "retired probe path must not remain classified");

// 取客户端 IP：信任最外层可信代理写入的真实客户端段（抗 X-Forwarded-For 伪造）
// 单跳(nginx=1)：真实 IP 是 XFF 最右段（nginx 追加的），左侧均为客户端可伪造段
assert.strictEqual(clientIpFromRequest({ headers: { "x-forwarded-for": "5.6.7.8" } }, 1), "5.6.7.8");
assert.strictEqual(clientIpFromRequest({ headers: { "x-forwarded-for": "9.9.9.9, 5.6.7.8" } }, 1), "5.6.7.8");
// 攻击者伪造多段 XFF：只取 nginx 追加的最右段，伪造段全部忽略 → 无法靠轮换 XFF 绕过限流
assert.strictEqual(clientIpFromRequest({ headers: { "x-forwarded-for": "1.1.1.1, 2.2.2.2, 5.6.7.8" } }, 1), "5.6.7.8");
// 两跳(如 CDN+nginx=2)：链为 [伪造段..., 真实客户端(CDN追加), CDN_IP(nginx追加)]，
// 真实 IP 在自右往左第 2 段；伪造首段 1.1.1.1 被忽略
assert.strictEqual(clientIpFromRequest({ headers: { "x-forwarded-for": "1.1.1.1, 5.6.7.8, 6.6.6.6" } }, 2), "5.6.7.8");
// 跳数超过链长 → 钳到最左段，不越界
assert.strictEqual(clientIpFromRequest({ headers: { "x-forwarded-for": "5.6.7.8" } }, 3), "5.6.7.8");
// hops=0：忽略 XFF，直接用 socket 来源 IP（即便带了 XFF 也不信）
assert.strictEqual(clientIpFromRequest({ ip: "9.9.9.9", headers: { "x-forwarded-for": "1.2.3.4" } }, 0), "9.9.9.9");
// 无 XFF 时回退 socket / unknown
assert.strictEqual(clientIpFromRequest({ ip: "9.9.9.9", headers: {} }), "9.9.9.9");
assert.strictEqual(clientIpFromRequest({ headers: {} }), "unknown");

// 端到端：攻击者每次轮换伪造的 XFF 首段，仍被同一真实 IP(nginx 追加的尾段)限流
{
  const rl = createRateLimiter();
  const realTail = "203.0.113.9";
  const key = (xff) => `auth:${clientIpFromRequest({ headers: { "x-forwarded-for": xff } }, 1)}`;
  const now = 2_000_000;
  assert.strictEqual(rl.hit(key(`1.1.1.1, ${realTail}`), 2, 1000, now).allowed, true);
  assert.strictEqual(rl.hit(key(`2.2.2.2, ${realTail}`), 2, 1000, now + 10).allowed, true);
  // 第三次即便伪造了全新首段，也命中同一 key 被拦截
  assert.strictEqual(rl.hit(key(`3.3.3.3, ${realTail}`), 2, 1000, now + 20).allowed, false);
}

console.log("rateLimit.test.js passed");
