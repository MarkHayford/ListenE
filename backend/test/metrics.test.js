const assert = require("assert");
const { recordRequest, recordMicroGeneration, snapshot, reset, classifyStatus } = require("../src/services/metrics");

// ---- classifyStatus ----
assert.strictEqual(classifyStatus(100), "info");
assert.strictEqual(classifyStatus(200), "success");
assert.strictEqual(classifyStatus(301), "redirect");
assert.strictEqual(classifyStatus(404), "clientError");
assert.strictEqual(classifyStatus(503), "serverError");
assert.strictEqual(classifyStatus("x"), "info");

// ---- 累计 + 快照 ----
reset();
recordRequest({ status: 200, durationMs: 10 });
recordRequest({ status: 200, durationMs: 10 });
recordRequest({ status: 200, durationMs: 10 });
recordRequest({ status: 500, durationMs: 100, slow: true });
recordRequest({ status: 404, durationMs: 5 });
{
  const s = snapshot();
  assert.strictEqual(s.total, 5);
  assert.strictEqual(s.byClass.success, 3);
  assert.strictEqual(s.byClass.serverError, 1);
  assert.strictEqual(s.byClass.clientError, 1);
  assert.strictEqual(s.slow, 1);
  assert.strictEqual(s.serverErrorRate, 0.2);
  assert.strictEqual(s.clientErrorRate, 0.2);
  assert.strictEqual(s.slowRate, 0.2);
  assert.strictEqual(s.avgDurationMs, 27); // round((10*3+100+5)/5)=27
  assert.strictEqual(s.maxDurationMs, 100);
  assert.ok(s.uptimeMs >= 0);
}

// ---- 微元引擎打点 ----
reset();
// 一次通过
recordMicroGeneration({ ok: true, hasCard: true, attempts: 1 });
// 定向修复闭环成功（第 2 轮通过）
recordMicroGeneration({ ok: true, hasCard: true, attempts: 2, repairTried: true, repairMerged: true, repairFixed: true, issueCodes: ["M012"] });
// 修复输出不合规 → 整卡重生成仍失败 → graceful 部分卡
recordMicroGeneration({ ok: false, hasCard: true, attempts: 3, repairTried: true, modelErrors: 1, issueCodes: ["M010", "M010", "M501"] });
// 全坏空卡
recordMicroGeneration({ ok: false, hasCard: false, attempts: 2, issueCodes: ["M099"] });
{
  const m = snapshot().micro;
  assert.strictEqual(m.total, 4);
  assert.strictEqual(m.ok, 2);
  assert.strictEqual(m.gracefulPartial, 1);
  assert.strictEqual(m.empty, 1);
  assert.strictEqual(m.okRate, 0.5);
  assert.strictEqual(m.emptyRate, 0.25);
  assert.strictEqual(m.attemptsSum, 8);
  assert.strictEqual(m.attemptsMax, 3);
  assert.strictEqual(m.avgAttempts, 2);
  assert.strictEqual(m.repairTried, 2);
  assert.strictEqual(m.repairMerged, 1);
  assert.strictEqual(m.repairFixed, 1);
  assert.strictEqual(m.repairMergeRate, 0.5);
  assert.strictEqual(m.repairFixRate, 0.5);
  assert.strictEqual(m.modelErrors, 1);
  assert.deepStrictEqual(m.issueCodes, { M012: 1, M010: 2, M501: 1, M099: 1 });
}

// ---- 生成主流程真实打点：mock callModel 走一遍先坏后修复的闭环 ----
reset();
{
  const { generateMicroCard } = require("../src/services/microCardGenerate");
  (async () => {
    let n = 0;
    const bad = { nodes: [{ type: "choice", options: ["a", "b"], answer: "c" }] };
    const fixed = { nodes: [{ type: "choice", options: ["a", "b"], answer: "a" }] };
    await generateMicroCard("出一道选择题", { callModel: async () => (n++ === 0 ? bad : fixed), maxAttempts: 3 });
    const m = snapshot().micro;
    assert.strictEqual(m.total, 1);
    assert.strictEqual(m.ok, 1);
    assert.strictEqual(m.repairTried, 1);
    assert.strictEqual(m.repairMerged, 1);
    assert.strictEqual(m.repairFixed, 1);
    assert.ok(m.issueCodes.M012 >= 1, "校验码 M012 应被计数");

    // ---- reset 清零（放在异步块内，避免与上面的打点竞态）----
    reset();
    const s = snapshot();
    assert.strictEqual(s.total, 0);
    assert.strictEqual(s.serverErrorRate, 0);
    assert.strictEqual(s.avgDurationMs, 0);
    assert.strictEqual(s.maxDurationMs, 0);
    assert.strictEqual(s.micro.total, 0);
    assert.strictEqual(s.micro.okRate, 0);

    console.log("metrics.test.js passed");
  })().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}
