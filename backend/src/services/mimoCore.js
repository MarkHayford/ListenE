// MiMo 文本模型调用核心：底层 HTTP 调用 + JSON 解析 + 瞬时失败重试退避。
// 独立成模块供各服务复用（mimoText / learningWorkspace / mimoPlan 等），避免循环依赖。
const { settings } = require("../config");
const { extractJsonFromContent } = require("./jsonRepair");

function normalizeMessageContent(content) {
  if (Array.isArray(content)) {
    return content.map(item => (typeof item === "string" ? item : item?.text || item?.content || "")).join("\n");
  }
  return String(content || "");
}

// 统一走 jsonRepair 的稳健解析（代码围栏 / 前后赘述 / 尾随逗号 / 漏逗号等都能恢复），
// 与 mimoText / mimoAgentMedia 的解析口径一致；先把可能的多段 content 归一为字符串。
function extractJson(content) {
  return extractJsonFromContent(normalizeMessageContent(content));
}

// 仅这些状态码视为瞬时可重试（限流 / 网关 / 服务暂时不可用）。4xx 业务错误不重试。
const RETRYABLE_MIMO_STATUSES = new Set([408, 425, 429, 500, 502, 503, 504]);

function isRetryableMimoStatus(status) {
  return RETRYABLE_MIMO_STATUSES.has(Number(status));
}

/** 指数退避 + 抖动；rng 默认 Math.random，便于单测注入。 */
function mimoBackoffDelayMs(attempt, baseMs, capMs = 8000, rng = Math.random) {
  const safeBase = Math.max(0, Number(baseMs) || 0);
  const exp = Math.min(capMs, safeBase * 2 ** Math.max(0, attempt));
  const jitter = Math.floor((typeof rng === "function" ? rng() : 0) * safeBase);
  return Math.min(capMs, exp + jitter);
}

/** 解析 Retry-After 响应头（秒数或 HTTP 日期）为毫秒；无效返回 null，最多 60s。 */
function parseRetryAfterMs(headers) {
  const raw = headers && typeof headers.get === "function" ? headers.get("retry-after") : null;
  if (!raw) return null;
  const seconds = Number(raw);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.min(60000, seconds * 1000);
  const dateMs = Date.parse(raw);
  if (Number.isFinite(dateMs)) return Math.min(60000, Math.max(0, dateMs - Date.now()));
  return null;
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));

// 熔断器：连续 N 次「可用性类失败」(网络/超时/重试耗尽的 5xx/429) 后开断；冷却期内所有 MiMo 调用
// 快速失败（不再发请求、不挂死、不无谓烧钱），冷却后放行试探，成功即恢复。业务类 4xx 不计入失败
// （那是请求问题、不是 MiMo 挂了）。纯函数工厂便于单测注入时钟。
function createCircuitBreaker({ failureThreshold = 5, cooldownMs = 30000, now = Date.now, onOpen, onClose } = {}) {
  let consecutiveFailures = 0;
  let opened = false; // 显式标记，避免用 openedAt===0 当哨兵（now() 理论上可能为 0）
  let openedAt = 0;
  function state() {
    if (!opened) return "closed";
    return now() - openedAt < cooldownMs ? "open" : "half-open";
  }
  function canRequest() {
    return state() !== "open";
  }
  function recordSuccess() {
    const wasOpen = opened;
    consecutiveFailures = 0;
    opened = false;
    openedAt = 0;
    if (wasOpen && typeof onClose === "function") onClose();
  }
  function recordFailure() {
    consecutiveFailures += 1;
    if (opened) {
      openedAt = now(); // half-open 试探又失败 → 重新开断、重置冷却
    } else if (consecutiveFailures >= failureThreshold) {
      opened = true;
      openedAt = now();
      if (typeof onOpen === "function") onOpen(consecutiveFailures);
    }
  }
  function snapshot() {
    return { state: state(), consecutiveFailures, openedAt };
  }
  return { canRequest, recordSuccess, recordFailure, snapshot, state };
}

// 熔断状态切换时打日志（可观测性）：MiMo 挂了/恢复了 ops 一眼能看到。
// 惰性 require logger 以避免任何加载顺序/循环依赖问题。
const mimoCircuit = createCircuitBreaker({
  failureThreshold: Math.max(1, Number(process.env.MIMO_CIRCUIT_THRESHOLD || 5)),
  cooldownMs: Math.max(1000, Number(process.env.MIMO_CIRCUIT_COOLDOWN_MS || 30000)),
  onOpen: (n) => {
    try { require("./logger").logger.warn("MiMo circuit opened (failing fast)", { consecutiveFailures: n }); } catch (_) {}
  },
  onClose: () => {
    try { require("./logger").logger.info("MiMo circuit closed (recovered)"); } catch (_) {}
  }
});

async function callMimoText(messages, { temperature = 0.4, maxTokens } = {}) {
  if (!mimoCircuit.canRequest()) {
    throw new Error("MiMo 暂时不可用（服务熔断中），请稍后重试");
  }
  const body = {
    model: settings.mimoTextModel,
    response_format: { type: "json_object" },
    messages,
    temperature
  };
  if (maxTokens) body.max_tokens = maxTokens;

  const url = `${settings.mimoBaseUrl.replace(/\/+$/, "")}/chat/completions`;
  const maxRetries = Math.max(0, Number(settings.mimoMaxRetries) || 0);
  const baseMs = Math.max(0, Number(settings.mimoRetryBaseMs) || 0);
  const timeoutMs = Math.max(5000, Number(settings.mimoTimeoutMs) || 60000);

  let attempt = 0;
  while (true) {
    let response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "api-key": settings.mimoApiKey,
          Authorization: `Bearer ${settings.mimoApiKey}`
        },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(timeoutMs)
      });
    } catch (networkError) {
      if (attempt < maxRetries) {
        await sleep(mimoBackoffDelayMs(attempt, baseMs));
        attempt += 1;
        continue;
      }
      mimoCircuit.recordFailure();
      throw new Error(`MiMo 网络请求失败：${networkError?.message || networkError}`);
    }

    const resText = await response.text();
    if (!response.ok) {
      if (isRetryableMimoStatus(response.status) && attempt < maxRetries) {
        const retryAfter = parseRetryAfterMs(response.headers);
        await sleep(retryAfter != null ? retryAfter : mimoBackoffDelayMs(attempt, baseMs));
        attempt += 1;
        continue;
      }
      // 可用性类错误(5xx/429)重试耗尽 → 计入熔断；业务类 4xx → MiMo 可达，重置熔断。
      if (isRetryableMimoStatus(response.status)) mimoCircuit.recordFailure();
      else mimoCircuit.recordSuccess();
      let detail = `MiMo API error (${response.status})`;
      try {
        const errJson = JSON.parse(resText);
        detail = errJson?.error?.message || errJson?.detail || errJson?.message || detail;
      } catch (_) {
        if (resText) detail = resText.slice(0, 200);
      }
      throw new Error(detail);
    }

    const data = JSON.parse(resText);
    mimoCircuit.recordSuccess();
    return extractJson(data.choices[0].message.content);
  }
}

module.exports = {
  callMimoText,
  extractJson,
  normalizeMessageContent,
  isRetryableMimoStatus,
  mimoBackoffDelayMs,
  parseRetryAfterMs,
  createCircuitBreaker,
  mimoCircuit
};
