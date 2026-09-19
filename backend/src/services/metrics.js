// 轻量内存指标：请求级统计（状态类/慢请求/时延）+ 微元引擎生成质量统计，
// 供受保护的 /api/v1/metrics 拉取。单实例进程内累计（重启清零），零外部依赖；
// 多实例需改共享存储。纯逻辑与状态分离，便于单测。

// 微元引擎打点：没有这些数据，prompt/校验/修复策略的调优都是盲调。
function freshMicroState() {
  return {
    total: 0,
    ok: 0,
    // ok=false 时的两种降级形态：仍有可渲染节点 / 完全空卡（调用方转纯文本回复）
    gracefulPartial: 0,
    empty: 0,
    attemptsSum: 0,
    attemptsMax: 0,
    // 定向修复漏斗：发起 → 输出合规并合并 → 合并后一次通过
    repairTried: 0,
    repairMerged: 0,
    repairFixed: 0,
    modelErrors: 0,
    // 各校验码(error 级)出现频次：定位模型最常犯的字段错误，反哺 prompt
    issueCodes: {},
    // 定向答案抽验（异步 verifier，只审语言学判断型答案键的卡）：标记率高了该回头修 prompt
    answerAudit: { audited: 0, flaggedCards: 0, flaggedNodes: 0, errors: 0 }
  };
}

function freshState() {
  return {
    startedAt: Date.now(),
    total: 0,
    byClass: { info: 0, success: 0, redirect: 0, clientError: 0, serverError: 0 },
    slow: 0,
    sumDurationMs: 0,
    maxDurationMs: 0,
    micro: freshMicroState()
  };
}

let state = freshState();

function classifyStatus(status) {
  const s = Number(status) || 0;
  if (s >= 500) return "serverError";
  if (s >= 400) return "clientError";
  if (s >= 300) return "redirect";
  if (s >= 200) return "success";
  return "info";
}

function recordRequest({ status = 0, durationMs = 0, slow = false } = {}) {
  state.total += 1;
  state.byClass[classifyStatus(status)] += 1;
  if (slow) state.slow += 1;
  const d = Math.max(0, Number(durationMs) || 0);
  state.sumDurationMs += d;
  if (d > state.maxDurationMs) state.maxDurationMs = d;
}

// 一次微元生成流程结束时打点（generateMicroCard 的每个返回路径调用一次）。
function recordMicroGeneration({
  ok = false,
  hasCard = false,
  attempts = 1,
  repairTried = false,
  repairMerged = false,
  repairFixed = false,
  modelErrors = 0,
  issueCodes = []
} = {}) {
  const m = state.micro;
  m.total += 1;
  if (ok) m.ok += 1;
  else if (hasCard) m.gracefulPartial += 1;
  else m.empty += 1;
  const a = Math.max(1, Number(attempts) || 1);
  m.attemptsSum += a;
  if (a > m.attemptsMax) m.attemptsMax = a;
  if (repairTried) m.repairTried += 1;
  if (repairMerged) m.repairMerged += 1;
  if (repairFixed) m.repairFixed += 1;
  m.modelErrors += Math.max(0, Number(modelErrors) || 0);
  for (const code of issueCodes || []) {
    const key = String(code || "").trim();
    if (key) m.issueCodes[key] = (m.issueCodes[key] || 0) + 1;
  }
}

// 一次定向答案抽验结束时打点（verifier 调用失败记 error，不计入 audited）。
function recordMicroAnswerAudit({ flaggedNodes = 0, error = false } = {}) {
  const a = state.micro.answerAudit;
  if (error) {
    a.errors += 1;
    return;
  }
  a.audited += 1;
  const n = Math.max(0, Number(flaggedNodes) || 0);
  if (n > 0) a.flaggedCards += 1;
  a.flaggedNodes += n;
}

function ratio(part, whole) {
  return whole > 0 ? Number((part / whole).toFixed(4)) : 0;
}

function snapshot() {
  const total = state.total;
  const micro = state.micro;
  return {
    uptimeMs: Date.now() - state.startedAt,
    total,
    byClass: { ...state.byClass },
    serverErrorRate: ratio(state.byClass.serverError, total),
    clientErrorRate: ratio(state.byClass.clientError, total),
    slow: state.slow,
    slowRate: ratio(state.slow, total),
    avgDurationMs: total > 0 ? Math.round(state.sumDurationMs / total) : 0,
    maxDurationMs: state.maxDurationMs,
    micro: {
      ...micro,
      issueCodes: { ...micro.issueCodes },
      okRate: ratio(micro.ok, micro.total),
      emptyRate: ratio(micro.empty, micro.total),
      avgAttempts: micro.total > 0 ? Number((micro.attemptsSum / micro.total).toFixed(2)) : 0,
      repairMergeRate: ratio(micro.repairMerged, micro.repairTried),
      repairFixRate: ratio(micro.repairFixed, micro.repairTried),
      answerAudit: {
        ...micro.answerAudit,
        cardFlagRate: ratio(micro.answerAudit.flaggedCards, micro.answerAudit.audited)
      }
    }
  };
}

function reset() {
  state = freshState();
}

module.exports = { recordRequest, recordMicroGeneration, recordMicroAnswerAudit, snapshot, reset, classifyStatus };
