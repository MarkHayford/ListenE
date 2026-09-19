// 按路由的请求体体积上限（字节），收窄「廉价端点收大请求体」的放大式 DoS 面。
// 用法：在解析前按 Content-Length 提前拒绝（返回 413）；Fastify 全局 bodyLimit 仍作硬上限
// 兜底（覆盖没有 Content-Length 的分块请求）。分三档：
//   - auth   ：登录/注册/登出，请求体极小            → 默认 64KB
//   - large  ：多模态/音频/文件类，合法携带 base64 二进制 → 默认 32MB
//   - default：其余 JSON 端点                         → 默认 4MB
const KB = 1024;
const MB = 1024 * 1024;

function envBytes(name, fallback, min) {
  const n = Math.floor(Number(process.env[name]));
  return Number.isFinite(n) && n >= min ? n : fallback;
}

const AUTH_BODY_LIMIT = envBytes("MAX_AUTH_BODY_BYTES", 64 * KB, 4 * KB);
const DEFAULT_BODY_LIMIT = envBytes("MAX_BODY_BYTES", 4 * MB, 64 * KB);
const LARGE_BODY_LIMIT = envBytes("MAX_UPLOAD_BODY_BYTES", 32 * MB, 1 * MB);

// 合法携带大体积 base64 二进制的端点：单附件上限 18MB、base64 膨胀约 1.33x，chat 还可带多个附件，
// library 的 data(JSONB) 可能内嵌文件内容。
const LARGE_BODY_PATHS = new Set([
  "/api/v1/agent/chat",
  "/api/v1/agent/asr",
  "/api/v1/agent/speaking",
  "/api/v1/library/files",
  "/api/v1/library/cards",
  "/api/v1/library/plugins"
]);

// 请求体极小的鉴权端点（邮箱+密码）。/api/v1/auth/me 是 GET，无需在此列出。
const AUTH_BODY_PATHS = new Set([
  "/api/v1/auth/login",
  "/api/v1/auth/register",
  "/api/v1/auth/logout"
]);

// 仅这些方法带请求体；其余（GET/DELETE/OPTIONS…）不限制。
const BODY_METHODS = new Set(["POST", "PUT", "PATCH"]);

function normalizePath(url) {
  return String(url || "").split("?")[0].replace(/\/+$/, "") || "/";
}

/** 该路由允许的最大请求体字节数；无体方法返回 Infinity（不限制）。 */
function bodyLimitForRoute(method, url) {
  if (!BODY_METHODS.has(String(method || "").toUpperCase())) return Infinity;
  const path = normalizePath(url);
  if (AUTH_BODY_PATHS.has(path)) return AUTH_BODY_LIMIT;
  if (LARGE_BODY_PATHS.has(path)) return LARGE_BODY_LIMIT;
  return DEFAULT_BODY_LIMIT;
}

module.exports = {
  bodyLimitForRoute,
  AUTH_BODY_LIMIT,
  DEFAULT_BODY_LIMIT,
  LARGE_BODY_LIMIT,
  AUTH_BODY_PATHS,
  LARGE_BODY_PATHS
};
