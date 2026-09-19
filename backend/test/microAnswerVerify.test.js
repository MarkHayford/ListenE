const assert = require("assert");
const { ANSWER_RISK_TYPES, riskNodes, verifierMessages, auditMicroCardAnswers, auditMicroCardAnswersAsync } = require("../src/services/microAnswerVerify");
const { recordMicroAnswerAudit, snapshot, reset } = require("../src/services/metrics");

(async () => {
  // ---- 风险节点筛选：只挑语言学判断型答案键的题型，index 保持原卡下标 ----
  {
    const card = {
      nodes: [
        { type: "text", text: "标题" },
        { type: "highlight_span", prompt: "选出所有动词", text: "She reads.", answers: ["reads"] },
        { type: "choice", prompt: "选一个", options: ["a", "b"], answer: "a" },
        { type: "error_correction", sentence: "She go home.", answer: "She goes home." },
        { type: "proof_paragraph", lines: [{ text: "a", answer: "b" }] }
      ]
    };
    const picked = riskNodes(card);
    assert.deepStrictEqual(picked.map((p) => p.index), [1, 3, 4]);
    assert.deepStrictEqual(ANSWER_RISK_TYPES.slice().sort(), ["error_correction", "highlight_span", "proof_paragraph"]);
    assert.strictEqual(riskNodes({ nodes: [{ type: "choice", options: ["a", "b"], answer: "a" }] }).length, 0);
  }

  // ---- verifier 提示词：带用户需求 + 原样 index 的节点 JSON，system 钉死输出契约 ----
  {
    const card = { nodes: [{ type: "highlight_span", prompt: "p", text: "t", answers: ["t"] }] };
    const msgs = verifierMessages("圈动词", riskNodes(card));
    assert.strictEqual(msgs.length, 2);
    assert.ok(/verdicts/.test(msgs[0].content) && /宁漏勿误报/.test(msgs[0].content));
    const payload = JSON.parse(msgs[1].content);
    assert.strictEqual(payload.userNeed, "圈动词");
    assert.strictEqual(payload.nodes[0].index, 0);
    assert.strictEqual(payload.nodes[0].type, "highlight_span");
  }

  // ---- 可等待核心：无风险节点直接跳过（不调用模型）；有风险节点则解析 verdicts/flagged ----
  {
    let called = 0;
    const out = await auditMicroCardAnswers({ nodes: [{ type: "choice", options: ["a", "b"], answer: "a" }] }, "x", {
      callModel: async () => { called += 1; return { verdicts: [] }; }
    });
    assert.strictEqual(out.audited, false);
    assert.strictEqual(called, 0);
  }
  {
    const card = { nodes: [{ type: "highlight_span", prompt: "选动词", text: "She reads.", answers: ["reads", "She"] }] };
    const out = await auditMicroCardAnswers(card, "圈动词", {
      callModel: async () => ({ verdicts: [{ index: 0, type: "highlight_span", ok: false, reason: "She 不是动词" }] })
    });
    assert.strictEqual(out.audited, true);
    assert.strictEqual(out.flagged.length, 1);
    assert.strictEqual(out.flagged[0].reason, "She 不是动词");
  }

  // ---- metrics 打点：audited/flaggedCards/flaggedNodes/errors 与 cardFlagRate ----
  {
    reset();
    recordMicroAnswerAudit({ flaggedNodes: 0 });
    recordMicroAnswerAudit({ flaggedNodes: 2 });
    recordMicroAnswerAudit({ error: true });
    const s = snapshot().micro.answerAudit;
    assert.strictEqual(s.audited, 2);
    assert.strictEqual(s.flaggedCards, 1);
    assert.strictEqual(s.flaggedNodes, 2);
    assert.strictEqual(s.errors, 1);
    assert.strictEqual(s.cardFlagRate, 0.5);
  }

  // ---- 异步入口：fire-and-forget 落 metrics；verifier 抛错只计 error、不外抛 ----
  {
    reset();
    const card = { nodes: [{ type: "error_correction", sentence: "She go.", answer: "She goes." }] };
    const started = auditMicroCardAnswersAsync(card, "改错", {
      callModel: async () => ({ verdicts: [{ index: 0, type: "error_correction", ok: false, reason: "accept 有错句" }] })
    });
    assert.strictEqual(started, true);
    assert.strictEqual(auditMicroCardAnswersAsync({ nodes: [{ type: "text", text: "t" }] }, "x"), false);
    const failing = auditMicroCardAnswersAsync(card, "改错", {
      callModel: async () => { throw new Error("boom"); }
    });
    assert.strictEqual(failing, true);
    await new Promise((r) => setTimeout(r, 20));
    const s = snapshot().micro.answerAudit;
    assert.strictEqual(s.audited, 1);
    assert.strictEqual(s.flaggedCards, 1);
    assert.strictEqual(s.errors, 1);
  }

  reset();
  console.log("microAnswerVerify tests passed");
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
