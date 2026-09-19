const path = require("path");
const dotenv = require("dotenv");

const baseDir = path.resolve(__dirname, "..");
dotenv.config({ path: path.join(baseDir, ".env") });

const settings = {
  appName: "ListenE Backend",
  port: Number(process.env.PORT || 8001),
  host: process.env.HOST || "127.0.0.1",
  publicBaseUrl: (process.env.PUBLIC_BASE_URL || "http://127.0.0.1:8001/api/v1").replace(/\/+$/, ""),
  corsOrigins: (process.env.CORS_ORIGINS || "*")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean),
  mimoApiKey: process.env.MIMO_API_KEY || "",
  mimoBaseUrl: process.env.MIMO_BASE_URL || "https://api.xiaomimimo.com/v1",
  mimoTextModel: process.env.MIMO_TEXT_MODEL || "mimo-v2.5-pro",
  mimoMultimodalModel: process.env.MIMO_MULTIMODAL_MODEL || "mimo-v2.5",
  mimoAsrModel: process.env.MIMO_ASR_MODEL || "mimo-v2.5-asr",
  mimoAgentTtsModel: process.env.MIMO_AGENT_TTS_MODEL || "mimo-v2.5-tts",
  mimoVoiceDesignModel: process.env.MIMO_VOICE_DESIGN_MODEL || "mimo-v2.5-tts-voicedesign",
  mimoTtsModel: process.env.MIMO_TTS_MODEL || "mimo-v2.5-tts-voicedesign",
  mimoTtsVoice: process.env.MIMO_TTS_VOICE || "Mia",
  mimoTtsFormat: process.env.MIMO_TTS_FORMAT || "wav",
  mimoTtsPath: process.env.MIMO_TTS_PATH || "/chat/completions",
  databaseUrl: process.env.DATABASE_URL || "postgres://listene:change-me@127.0.0.1:5432/listene",
  authTokenSecret: process.env.AUTH_TOKEN_SECRET || "listene-dev-token-secret-change-me",
  authTokenDays: Math.max(1, Math.min(Number(process.env.AUTH_TOKEN_DAYS || 30), 365)),
  mockMode: ["1", "true", "yes", "on"].includes(String(process.env.MOCK_MODE || "false").toLowerCase()),
  audioDir: path.join(baseDir, "storage", "audio"),
  packagesDir: path.join(baseDir, "storage", "packages"),
  chatSessionsDir: path.join(baseDir, "storage", "chat-sessions"),
  chatHistoryMaxMessages: (() => {
    const n = Number(process.env.CHAT_HISTORY_MAX_MESSAGES ?? 24);
    const bounded = Number.isFinite(n) ? n : 24;
    return Math.max(4, Math.min(Math.floor(bounded), 80));
  })(),
  rateLimitWindowMs: Math.max(1000, Number(process.env.RATE_LIMIT_WINDOW_MS || 60000)),
  rateLimitAiMax: Math.max(0, Number(process.env.RATE_LIMIT_AI_MAX ?? 30)),
  rateLimitAuthMax: Math.max(0, Number(process.env.RATE_LIMIT_AUTH_MAX ?? 10)),
  // readiness 探活(/readyz)会打 DB，给一个宽松默认(60/分钟/IP)：正常监控足够，
  // 又能挡住恶意刷探活。设 0 则不限流。
  rateLimitProbeMax: Math.max(0, Number(process.env.RATE_LIMIT_PROBE_MAX ?? 60)),
  mimoMaxRetries: Math.max(0, Math.min(5, Number(process.env.MIMO_MAX_RETRIES ?? 2))),
  mimoRetryBaseMs: Math.max(0, Number(process.env.MIMO_RETRY_BASE_MS ?? 500)),
  // 单次 MiMo 请求超时（毫秒）：避免挂死连接长期占用。文本默认 60s，TTS 默认 120s。
  mimoTimeoutMs: Math.max(5000, Number(process.env.MIMO_TIMEOUT_MS || 180000)),
  mimoTtsTimeoutMs: Math.max(5000, Number(process.env.MIMO_TTS_TIMEOUT_MS || 120000)),
  logLevel: String(process.env.LOG_LEVEL || "info").toLowerCase(),
  // 慢请求阈值（毫秒）：响应耗时 >= 该值的请求按 warn 记录并打 slow 标记，便于告警。0=关闭。
  slowRequestMs: Math.max(0, Number(process.env.SLOW_REQUEST_MS ?? 3000)),
  // 受保护的 /api/v1/metrics 令牌：留空则该端点禁用(404)；设置后需带 Bearer/x-metrics-token 才可拉取。
  metricsToken: String(process.env.METRICS_TOKEN || ""),
  // 微元答案定向抽验：对含语言学判断型答案键(highlight_span/error_correction/proof_paragraph)的卡，
  // 生成成功后异步跑一次 verifier 审计并打 metrics（不拦截响应、零额外延迟）。默认开；设 0/false/off 关。
  microAnswerAuditEnabled: !/^(0|false|no|off)$/i.test(String(process.env.MICRO_ANSWER_AUDIT ?? "1")),
  swaggerEnabled: /^(1|true|yes|on)$/i.test(String(process.env.SWAGGER_ENABLED || ""))
};

const DEFAULT_AUTH_TOKEN_SECRET = "listene-dev-token-secret-change-me";

// Refuse to boot a real deployment that still uses shipped placeholder secrets:
// a default AUTH_TOKEN_SECRET means every login token is signed with a public
// key and can be forged. Skipped under mock/dev/test so local and CI runs are
// unaffected (those boot with MOCK_MODE=true or an explicit secret).
function collectStartupConfigErrors(config = settings, nodeEnv = process.env.NODE_ENV || "") {
  if (config.mockMode || nodeEnv === "development" || nodeEnv === "test") return [];
  const errors = [];
  const secret = String(config.authTokenSecret || "");
  if (!secret || secret === DEFAULT_AUTH_TOKEN_SECRET || /change-me/i.test(secret)) {
    errors.push(
      "AUTH_TOKEN_SECRET 仍是默认/占位值。生产环境必须设置一个强随机密钥，否则登录 token 可被任何人伪造。" +
        "（本地开发可设 MOCK_MODE=true 或 NODE_ENV=development 跳过此检查。）"
    );
  }
  if (/change-me/i.test(String(config.databaseUrl || ""))) {
    errors.push("DATABASE_URL 仍含占位密码 change-me。请配置真实的数据库连接串。");
  }
  // 生产必须显式收敛 CORS 来源：未配置(默认 "*")或含通配 "*" 时，任意网站都能跨源调用本 API。
  // Bearer 鉴权虽不走 Cookie、风险被压低，但通配来源仍会放大 CSRF/数据抓取面，故按“必须白名单”处理。
  const corsOrigins = Array.isArray(config.corsOrigins) ? config.corsOrigins : [];
  if (corsOrigins.length === 0 || corsOrigins.includes("*")) {
    errors.push(
      "CORS_ORIGINS 未配置或为通配 '*'。生产环境必须显式设置来源白名单（逗号分隔，如 " +
        "https://app.example.com），否则任意站点都可跨源调用本 API。" +
        "（本地开发可设 MOCK_MODE=true 或 NODE_ENV=development 跳过此检查。）"
    );
  }
  return errors;
}

module.exports = { settings, collectStartupConfigErrors, DEFAULT_AUTH_TOKEN_SECRET };
