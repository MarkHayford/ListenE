// 统一安全响应头：补足 API 的基础安全基线（防 MIME 嗅探、点击劫持、Referrer 泄漏、DNS 预取）。
// HSTS 不在此设置：仅在 HTTPS 下有意义，应由生产反向代理统一注入。
const SECURITY_HEADERS = {
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  "X-DNS-Prefetch-Control": "off"
};

function securityHeaders() {
  return { ...SECURITY_HEADERS };
}

module.exports = { SECURITY_HEADERS, securityHeaders };
