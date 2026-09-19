// 轻量级内存滑动窗口限流，适用于单实例部署：保护烧钱的 AI 端点与登录暴力破解。
// 纯逻辑与副作用分离，便于单测。多实例部署应换成共享存储（如 Redis）。

// 可信反向代理跳数（nginx=1）。X-Forwarded-For 是「客户端自带段 + 各代理依次追加段」，
// 真实客户端 IP 在【自右往左】第 TRUSTED_PROXY_HOPS 个位置（最外层可信代理写入的那段）。
// 它左侧的条目都是客户端可任意伪造的，绝不能信——否则攻击者只要不断轮换伪造的 XFF
// 首段，就能为每个请求伪造不同 key、绕过按 IP 的限流（登录暴破 / AI 端点刷量）。
// 非法值回退为 1（而非 0），避免误配成 0 时所有人共用 socket IP(127.0.0.1) 被一起限流。
const TRUSTED_PROXY_HOPS = (() => {
  const raw = process.env.TRUSTED_PROXY_HOPS;
  if (raw == null || raw === "") return 1;
  const n = Math.floor(Number(raw));
  return Number.isFinite(n) && n >= 0 ? n : 1;
})();

const AI_PATHS = new Set([
  "/api/v1/agent/chat",
  "/api/v1/agent/chat/stream",
  "/api/v1/agent/tts",
  "/api/v1/agent/asr",
  "/api/v1/agent/speaking",
  "/api/v1/agent/writing",
  "/api/v1/agent/shadowing",
  "/api/v1/agent/analysis",
  "/api/v1/agent/solve",
  "/api/v1/agent/organize",
  "/api/v1/agent/micro",
  "/api/v1/agent/roleplay/turn",
  "/api/v1/agent/roleplay/feedback",
  "/api/v1/listening/generate",
  "/api/v1/listening/generate/stream",
  "/api/v1/listening/synthesize",
  "/api/v1/listening/package",
  "/api/v1/analyze-mistakes",
  "/api/v1/analyze-practice",
  "/api/v1/plan/generate",
  "/api/v1/translate",
  "/api/v1/vocab/lookup",
  "/api/v1/daily"
]);

/** 按路由归类限流组：auth（登录/注册）、probe（DB 探活）、ai（生成类）、或 null（不限流）。 */
function classifyRateLimitRoute(method, url) {
  const path = String(url || "").split("?")[0].replace(/\/+$/, "") || "/";
  if (path === "/api/v1/auth/login" || path === "/api/v1/auth/register") return "auth";
  if (path === "/readyz") return "probe";
  if (AI_PATHS.has(path)) return "ai";
  return null;
}

/**
 * 取反代透传链中【可信跳】对应的真实客户端 IP（抗 X-Forwarded-For 伪造）。
 * 单跳(nginx)时真实 IP 是 XFF 最右段（nginx 追加的那段），靠左的段一律不信。
 * trustedHops<=0 时忽略 XFF，直接用 socket 来源 IP。
 */
function clientIpFromRequest(request, trustedHops = TRUSTED_PROXY_HOPS) {
  if (trustedHops > 0) {
    const forwarded = request?.headers?.["x-forwarded-for"];
    if (forwarded) {
      const chain = String(forwarded)
        .split(",")
        .map((part) => part.trim())
        .filter(Boolean);
      if (chain.length) {
        const idx = Math.max(0, chain.length - trustedHops);
        return chain[idx];
      }
    }
  }
  return request?.ip || request?.socket?.remoteAddress || "unknown";
}

function pruneAndCount(timestamps, windowMs, now) {
  const cutoff = now - windowMs;
  let drop = 0;
  while (drop < timestamps.length && timestamps[drop] <= cutoff) drop += 1;
  if (drop > 0) timestamps.splice(0, drop);
  return timestamps.length;
}

/** 创建限流器。hit(key, limit, windowMs, now) -> { allowed, remaining, retryAfterMs }。limit<=0 表示不限。 */
function createRateLimiter() {
  const store = new Map();
  let lastSweep = 0;

  function hit(key, limit, windowMs, now = Date.now()) {
    if (!limit || limit <= 0) return { allowed: true, remaining: Infinity, retryAfterMs: 0 };
    let timestamps = store.get(key);
    if (!timestamps) {
      timestamps = [];
      store.set(key, timestamps);
    }
    const count = pruneAndCount(timestamps, windowMs, now);
    if (count >= limit) {
      const retryAfterMs = Math.max(0, timestamps[0] + windowMs - now);
      return { allowed: false, remaining: 0, retryAfterMs };
    }
    timestamps.push(now);
    if (now - lastSweep > windowMs) {
      lastSweep = now;
      for (const [k, ts] of store) {
        if (pruneAndCount(ts, windowMs, now) === 0) store.delete(k);
      }
    }
    return { allowed: true, remaining: limit - count - 1, retryAfterMs: 0 };
  }

  return { hit, store };
}

module.exports = {
  classifyRateLimitRoute,
  clientIpFromRequest,
  pruneAndCount,
  createRateLimiter,
  TRUSTED_PROXY_HOPS
};
