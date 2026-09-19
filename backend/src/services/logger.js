// 结构化日志：每条一行 JSON，便于线上采集/检索。info/warn 走 stdout，error/warn 走 stderr。
// formatLogLine 为纯函数，便于单测。
const { settings } = require("../config");

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };

function currentThreshold() {
  return LEVELS[String(settings.logLevel || "info").toLowerCase()] ?? LEVELS.info;
}

function formatLogLine(level, message, fields = {}, now = new Date()) {
  const safeFields = fields && typeof fields === "object" ? fields : {};
  return JSON.stringify({
    ts: now.toISOString(),
    level: String(level || "info"),
    msg: String(message ?? ""),
    ...safeFields
  });
}

function emit(level, message, fields) {
  if ((LEVELS[level] ?? LEVELS.info) < currentThreshold()) return;
  const line = formatLogLine(level, message, fields);
  if (level === "error" || level === "warn") process.stderr.write(`${line}\n`);
  else process.stdout.write(`${line}\n`);
}

const logger = {
  debug: (message, fields) => emit("debug", message, fields),
  info: (message, fields) => emit("info", message, fields),
  warn: (message, fields) => emit("warn", message, fields),
  error: (message, fields) => emit("error", message, fields)
};

// 按响应状态/耗时给「每请求」日志选级别（可观测性）：
//   5xx → error（服务端故障）；超过 slowMs 的慢请求 → warn；其余 → info。
// 4xx（含常态的 401/429）保持 info 以免告警噪声，但仍结构化可检索。slowMs<=0 关闭慢判定。
// 纯函数，便于单测。
function requestLogLevel(status, durationMs, slowMs = 0) {
  const code = Number(status) || 0;
  if (code >= 500) return "error";
  if (slowMs > 0 && Number(durationMs) >= Number(slowMs)) return "warn";
  return "info";
}

module.exports = { logger, formatLogLine, requestLogLevel };
