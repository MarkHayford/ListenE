const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

// 替代已退役的 agentFuzzyQuality.test.js / agentMessageIntent.test.js（它们断言
// generateAgentChatReply 返回结构化 cardSpec——该 43 题型确定性生成管线已在
// 7ac9ac1 退役，practice_card 一律由 /agent/chat 包装层的 microCard 承载）。
// 本套件聚焦仍然存活的行为：
//   1. intent 路由（确定性分类器强制 + 模型 intent 透传/兜底）；
//   2. 退役不变量：generateAgentChatReply 的 cardSpec 恒为 null；
//   3. 仍在导出面上的卡片工具函数（normalize/scope/validate/count/telemetry）。
// 说明：normalizeAgentCardSpec、assessAgentCardQuality、classifyForcedAgentCardPrimary、
// inferRequestedAgentCardItemCounts 已有其它套件覆盖（agentOutputExport /
// agentNewComponents / mimoText.analysis / explicitConstraints），这里只做冒烟。

const { generateAgentChatReply, __test } = require("../src/services/mimoText");

const modelCardSpec = () => ({
  schemaVersion: 1,
  kind: "custom",
  title: "词汇强化",
  components: [{ type: "vocabulary", items: ["hesitate - 犹豫"] }],
  actions: [{ id: "continue_ai", label: "继续", prompt: "继续生成下一张词汇组件卡片", primary: true }]
});

(async () => {
  const originalFetch = global.fetch;
  global.fetch = async (_url, options = {}) => {
    const body = JSON.parse(String(options.body || "{}"));
    const userContent = String(body.messages?.[body.messages.length - 1]?.content || "");
    const content = userContent.includes("plain translation chat regression")
      ? {
          reply: "I have finished it. 可以翻译为：我已经完成了它。",
          intent: "chat",
          practiceNeed: "",
          outputFiles: [],
          cardSpec: null
        }
      : userContent.includes("plain grammar chat regression")
      ? {
          reply: "Present perfect uses have or has plus the past participle.",
          intent: "chat",
          practiceNeed: "",
          outputFiles: [],
          cardSpec: null
        }
      : userContent.includes("invalid model intent regression")
      ? {
          reply: "ok",
          intent: "banana",
          practiceNeed: "",
          outputFiles: [],
          cardSpec: null
        }
      : userContent.includes("model practice card passthrough regression")
      ? {
          reply: "ok",
          intent: "practice_card",
          practiceNeed: "",
          outputFiles: [],
          cardSpec: modelCardSpec()
        }
      : userContent.includes("别出题")
      ? {
          reply: "make 表示制造，do 表示执行。",
          intent: "practice_card",
          practiceNeed: "",
          outputFiles: [],
          cardSpec: modelCardSpec()
        }
      : /帮我出一道写作题|给我一篇阅读理解题|来一道口语练习题|来一个场景练习卡|fill in the blank cloze/.test(userContent)
      ? {
          // 故意返回 chat + 非空 cardSpec：证明确定性分类器强制 practice_card，
          // 且退役后的 cardSpec 恒被丢弃为 null。
          reply: "好的。",
          intent: "chat",
          practiceNeed: "",
          outputFiles: [],
          cardSpec: modelCardSpec()
        }
      : {
          reply: "我会在当前工作区追加一套雅思听力训练素材。",
          intent: "new_listening_practice",
          practiceTool: "training",
          practiceNeed: "生成一套雅思听力训练",
          outputFiles: [],
          cardSpec: null
        };
    return {
      ok: true,
      status: 200,
      text: async () => JSON.stringify({
        choices: [{ message: { content: JSON.stringify(content) } }]
      })
    };
  };

  try {
    // ---- 显式新听力素材请求 → new_listening_practice；practiceTool 不透传 ----
    const listeningResult = await generateAgentChatReply({
      message: "生成一套雅思听力训练",
      workspaceTitle: "现在完成时",
      workspaceNeed: "present perfect grammar",
      workspaceContentType: "chat",
      recentMessages: [
        { role: "user", content: "present perfect grammar" },
        { role: "assistant", content: "现在完成时表示过去动作和现在有关。" }
      ]
    });
    assert.strictEqual(listeningResult.intent, "new_listening_practice");
    assert.strictEqual(Object.prototype.hasOwnProperty.call(listeningResult, "practiceTool"), false);
    assert.strictEqual(listeningResult.practiceNeed, "生成一套雅思听力训练");
    assert.strictEqual(listeningResult.cardSpec, null);

    // ---- 纯答疑（翻译/语法讲解）稳定保持 chat ----
    const plainTranslationReply = await generateAgentChatReply({
      message: "plain translation chat regression 帮我翻译这句话：I have finished it.",
      workspaceTitle: "English Q&A",
      workspaceNeed: "translation help",
      workspaceContentType: "chat",
      recentMessages: []
    });
    assert.strictEqual(plainTranslationReply.intent, "chat");
    assert.strictEqual(plainTranslationReply.cardSpec, null);

    const plainGrammarReply = await generateAgentChatReply({
      message: "plain grammar chat regression 给我讲一下 present perfect 怎么用",
      workspaceTitle: "English Q&A",
      workspaceNeed: "grammar help",
      workspaceContentType: "chat",
      recentMessages: []
    });
    assert.strictEqual(plainGrammarReply.intent, "chat");
    assert.strictEqual(plainGrammarReply.cardSpec, null);

    // ---- 模糊练习请求：即使模型答 chat + 附带 cardSpec，也强制 practice_card 且 cardSpec 为 null ----
    for (const message of [
      "帮我出一道写作题",
      "给我一篇阅读理解题",
      "来一道口语练习题",
      // 注意不能用「场景对话练习」：对话词会命中 isExplicitListeningDialogueMaterialRequest，
      // 路由优先走 new_listening_practice。
      "来一个场景练习卡",
      "fill in the blank cloze"
    ]) {
      const forcedResult = await generateAgentChatReply({
        message,
        workspaceTitle: "练习",
        workspaceNeed: "practice",
        workspaceContentType: "chat",
        recentMessages: []
      });
      assert.strictEqual(forcedResult.intent, "practice_card", `forced practice_card for: ${message}`);
      assert.strictEqual(forcedResult.cardSpec, null, `cardSpec must stay null for: ${message}`);
    }

    // ---- 模型 intent 非法值 → 兜底 chat ----
    const invalidIntentResult = await generateAgentChatReply({
      message: "invalid model intent regression hello there",
      workspaceTitle: "English Q&A",
      workspaceNeed: "chat",
      workspaceContentType: "chat",
      recentMessages: []
    });
    assert.strictEqual(invalidIntentResult.intent, "chat");
    assert.strictEqual(invalidIntentResult.cardSpec, null);

    // ---- 模型 practice_card 透传（无确定性触发）→ practice_card，cardSpec 仍为 null ----
    const passthroughResult = await generateAgentChatReply({
      message: "model practice card passthrough regression hello there",
      workspaceTitle: "English Q&A",
      workspaceNeed: "chat",
      workspaceContentType: "chat",
      recentMessages: []
    });
    assert.strictEqual(passthroughResult.intent, "practice_card");
    assert.strictEqual(passthroughResult.cardSpec, null);

    // ---- 用户显式拒绝出题 + 讲解类问题 → 强制 chat，丢弃模型误挂的卡片 ----
    const noCardResult = await generateAgentChatReply({
      message: "别出题，给我讲一下 make 和 do 的区别",
      workspaceTitle: "English Q&A",
      workspaceNeed: "chat",
      workspaceContentType: "chat",
      recentMessages: []
    });
    assert.strictEqual(noCardResult.intent, "chat");
    assert.strictEqual(noCardResult.cardSpec, null);
  } finally {
    global.fetch = originalFetch;
  }

  // ================= 保留工具函数（无其它套件覆盖的部分） =================

  // ---- agentCardComponentTypes：新组件在册、退役 token 不在册 ----
  assert.ok(__test.agentCardComponentTypes.includes("minimal_pair"));
  assert.ok(__test.agentCardComponentTypes.includes("sentence_transcript"));
  assert.ok(__test.agentCardComponentTypes.includes("ordering"));
  assert.ok(!__test.agentCardComponentTypes.includes("training"));
  assert.ok(!__test.agentCardComponentTypes.includes("intensive"));

  // ---- normalizeAgentCardSpec 冒烟：未知 kind → null；退役 kind 被收敛为 custom
  //（退役 token 的拦截由 validateAgentCardSpec 基于原始值负责）----
  assert.strictEqual(__test.normalizeAgentCardSpec(null), null);
  assert.strictEqual(__test.normalizeAgentCardSpec({ kind: "bogus_kind", components: [] }), null);
  assert.strictEqual(__test.normalizeAgentCardSpec({ kind: "training", components: [] }).kind, "custom");
  const normalizedVocab = __test.normalizeAgentCardSpec(modelCardSpec());
  assert.ok(normalizedVocab);
  assert.strictEqual(normalizedVocab.kind, "custom");
  assert.strictEqual(normalizedVocab.components[0].type, "vocabulary");

  // ---- validateAgentCardSpec：缺失 / 退役 token / 合法规格 ----
  const missingValidation = __test.validateAgentCardSpec(null, null);
  assert.strictEqual(missingValidation.ok, false);
  assert.deepStrictEqual(missingValidation.errors, ["cardSpec missing"]);

  const forbiddenValidation = __test.validateAgentCardSpec(
    {
      kind: "training",
      components: [{ type: "training" }],
      actions: [{ id: "training", prompt: "" }]
    },
    null
  );
  assert.strictEqual(forbiddenValidation.ok, false);
  assert.ok(forbiddenValidation.errors.includes("forbidden kind: training"));
  assert.ok(forbiddenValidation.errors.includes("cardSpec cannot be normalized"));
  assert.ok(forbiddenValidation.errors.includes("forbidden component: training"));
  assert.ok(forbiddenValidation.errors.includes("forbidden action: training"));
  assert.ok(forbiddenValidation.errors.includes("action prompt missing"));

  const validRaw = modelCardSpec();
  const validValidation = __test.validateAgentCardSpec(validRaw, __test.normalizeAgentCardSpec(validRaw));
  assert.strictEqual(validValidation.ok, true);
  assert.deepStrictEqual(validValidation.errors, []);

  const oldWordingValidation = __test.validateAgentCardSpec(
    {
      kind: "custom",
      components: [{ type: "vocabulary", items: ["a - b"] }],
      actions: [{ id: "continue_ai", prompt: "开始逐句精听训练" }]
    },
    __test.normalizeAgentCardSpec(validRaw)
  );
  assert.strictEqual(oldWordingValidation.ok, false);
  assert.ok(oldWordingValidation.errors.includes("action prompt contains old local route wording"));

  // ---- inferAgentCardScope：素材/分析系统事件的确定性 scope ----
  const materialScope = __test.inferAgentCardScope("素材已生成");
  assert.strictEqual(materialScope.scoped, true);
  assert.strictEqual(materialScope.materialReady, true);
  assert.ok(materialScope.allowed.has("audio"));
  assert.ok(materialScope.allowed.has("transcript"));

  const analysisScope = __test.inferAgentCardScope("AI分析已完成");
  assert.strictEqual(analysisScope.scoped, true);
  assert.strictEqual(analysisScope.materialReady, false);
  assert.ok(analysisScope.allowed.has("feedback"));
  assert.ok(analysisScope.allowed.has("mistake_pattern"));

  // ---- enforceAgentCardScope：null 透传；素材事件收敛到核心组件序 ----
  assert.strictEqual(__test.enforceAgentCardScope("素材已生成", null), null);
  const scopedMaterialCard = __test.enforceAgentCardScope("素材已生成", {
    schemaVersion: 1,
    kind: "custom",
    title: "素材",
    components: [
      { type: "header" },
      { type: "vocabulary", items: ["hesitate - 犹豫"] },
      { type: "audio" }
    ],
    actions: []
  });
  const scopedTypes = scopedMaterialCard.components.map((component) => component.type);
  assert.ok(scopedTypes.includes("header"));
  assert.ok(scopedTypes.includes("audio"));
  assert.ok(!scopedTypes.includes("vocabulary"), "越权组件应被降级出核心组件序");

  // ---- enforceRequestedAgentCardComponentCount：无请求数量恒等；writing_outline 截断 ----
  const untouchedComponent = { type: "vocabulary", items: ["a - b", "c - d"] };
  assert.strictEqual(
    __test.enforceRequestedAgentCardComponentCount(untouchedComponent, { requestedCounts: {} }),
    untouchedComponent
  );

  const outlineComponent = {
    type: "writing_outline",
    steps: [
      { label: "Opening", text: "Introduce the purpose clearly." },
      { label: "Body", text: "Add the key details in a logical order." },
      { label: "Closing", text: "End with a clear next step." }
    ]
  };
  const trimmedOutline = __test.enforceRequestedAgentCardComponentCount(outlineComponent, {
    requestedCounts: { writing_outline: 2 },
    messageText: "写作提纲只要两步"
  });
  assert.strictEqual(trimmedOutline.steps.length, 2);
  assert.strictEqual(trimmedOutline.steps[0].label, "Opening");
  assert.deepStrictEqual(trimmedOutline.items, []);

  const satisfiedList = { type: "phrase", items: ["take off - 起飞", "put up with - 忍受"] };
  assert.strictEqual(
    __test.enforceRequestedAgentCardComponentCount(satisfiedList, {
      requestedCounts: { phrase: 2 },
      messageText: "两个短语"
    }),
    satisfiedList
  );

  // ---- telemetry：reset 后归零（修复/兜底管线已退役，只保证导出契约） ----
  __test.resetAgentCardTelemetry();
  const telemetry = __test.getAgentCardTelemetry();
  assert.strictEqual(telemetry.invalidCardCount, 0);
  assert.strictEqual(telemetry.repairAttemptCount, 0);
  assert.strictEqual(telemetry.repairSuccessCount, 0);
  assert.strictEqual(telemetry.fallbackCount, 0);
  assert.strictEqual(telemetry.qualityWarningCount, 0);
  assert.deepStrictEqual(telemetry.lastQualityWarnings, []);
  assert.deepStrictEqual(telemetry.lastErrors, []);

  console.log("agentIntentRouting.test.js passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
