const assert = require("assert");

process.env.MIMO_API_KEY = "";

const calls = [];
let workspaceData = {
  id: "workspace_memory_1",
  title: "Memory Workspace",
  need: "present perfect practice",
  summary: "",
  status: "active",
  currentStep: "chat",
  createdAt: 100,
  updatedAt: 100,
  plan: { contentType: "chat", steps: [{ id: "chat", title: "Chat", description: "", status: "running" }] },
  linkedRecordIds: [],
  linkedContainerIds: [],
  linkedPlanTaskIds: [],
  events: []
};

const dbPath = require.resolve("../src/services/db");
require.cache[dbPath] = {
  id: dbPath,
  filename: dbPath,
  loaded: true,
  exports: {
    query: async (sql, params = []) => {
      const cleanSql = String(sql).replace(/\s+/g, " ").trim();
      calls.push({ sql: cleanSql, params });
      if (/SELECT data, created_at, updated_at FROM workspaces/.test(cleanSql)) {
        return { rows: [{ data: workspaceData, created_at: 100, updated_at: workspaceData.updatedAt || 100 }] };
      }
      if (/INSERT INTO workspaces/.test(cleanSql)) {
        workspaceData = JSON.parse(params[2]);
        return { rows: [], rowCount: 1 };
      }
      if (/INSERT INTO workspace_messages/.test(cleanSql)) {
        return { rows: [], rowCount: 1 };
      }
      return { rows: [], rowCount: 0 };
    }
  }
};

delete require.cache[require.resolve("../src/services/learningWorkspace")];

const { recordWorkspaceEvent, saveWorkspaceMessages } = require("../src/services/learningWorkspace");
const { __test: mimoTextTest } = require("../src/services/mimoText");

(async () => {
  const messages = Array.from({ length: 30 }, (_, index) => ({
    role: index === 29 || index % 2 === 0 ? "user" : "assistant",
    content: index === 29
      ? "Remember: I prefer British pronunciation practice."
      : `message ${index} about present perfect practice`
  }));

  const saved = await saveWorkspaceMessages("workspace_memory_1", messages, { id: "user_1" });

  assert.strictEqual(saved.persisted, true);
  assert.strictEqual(saved.messages.length, 30);
  assert.match(workspaceData.memorySummary, /present perfect practice/);
  assert.ok(workspaceData.memorySummary.length <= 1500);
  assert.ok(
    workspaceData.memory.some((item) =>
      item.type === "preference" && /British pronunciation practice/i.test(item.content)
    )
  );
  assert.ok(workspaceData.memory.length <= 30);
  assert.ok(calls.some((call) => /INSERT INTO workspace_messages/.test(call.sql)));
  assert.ok(calls.some((call) => /INSERT INTO workspaces/.test(call.sql)));

  const { settings } = require("../src/config");
  const originalApiKey = settings.mimoApiKey;
  settings.mimoApiKey = "memory-summary-test-key";
  const mimoTextPath = require.resolve("../src/services/mimoText");
  const originalMimoTextModule = require.cache[mimoTextPath];
  require.cache[mimoTextPath] = {
    id: mimoTextPath,
    filename: mimoTextPath,
    loaded: true,
    exports: {
      callMimoText: async (messages) => {
        assert.match(String(messages[0]?.content || ""), /工作区级记忆摘要器/);
        assert.match(String(messages[1]?.content || ""), /old long-context message/);
        return { memorySummary: "Compressed summary: long-context practice and British pronunciation preference." };
      }
    }
  };
  delete require.cache[require.resolve("../src/services/learningWorkspace")];
  const { saveWorkspaceMessages: saveWorkspaceMessagesWithModel } = require("../src/services/learningWorkspace");
  const existingMemory = workspaceData.memory;
  workspaceData = {
    ...workspaceData,
    memorySummary: "Old summary.",
    memory: existingMemory
  };
  await saveWorkspaceMessagesWithModel("workspace_memory_1", Array.from({ length: 28 }, (_, index) => ({
    role: index % 2 === 0 ? "user" : "assistant",
    content: `old long-context message ${index}`
  })), { id: "user_1" });
  assert.strictEqual(
    workspaceData.memorySummary,
    "Compressed summary: long-context practice and British pronunciation preference."
  );
  settings.mimoApiKey = originalApiKey;
  if (originalMimoTextModule) require.cache[mimoTextPath] = originalMimoTextModule;
  else delete require.cache[mimoTextPath];
  delete require.cache[require.resolve("../src/services/learningWorkspace")];

  const eventResult = await recordWorkspaceEvent("workspace_memory_1", {
    type: "analysis_completed",
    title: "AI 错因分析完成",
    description: "Numbers and contrast markers were confusing.",
    recordId: "record_1",
    currentStep: "review",
    weakPoints: ["数字听辨容易错", "however 转折信号漏听"]
  }, { id: "user_1" });

  assert.strictEqual(eventResult.persisted, true);
  assert.ok(
    workspaceData.memory.some((item) =>
      item.type === "weakness" && /数字听辨/.test(item.content)
    )
  );
  assert.ok(
    workspaceData.memory.some((item) =>
      item.type === "weakness" && /however/.test(item.content)
    )
  );
  const hint = mimoTextTest.buildAgentWorkspaceMemoryHint({
    memorySummary: workspaceData.memorySummary,
    memory: workspaceData.memory
  });
  assert.match(hint, /本工作区级记忆/);
  assert.match(hint, /British pronunciation practice/);
  assert.match(hint, /数字听辨/);
  assert.ok(!/æœ¬|å·¥|è®°/.test(hint));

  // --- 增量摘要选择 selectWorkspaceSummaryBatch：每条旧消息只摘一次，无新增则不摘（省模型调用）---
  delete require.cache[require.resolve("../src/services/learningWorkspace")];
  const { __test: wsTest } = require("../src/services/learningWorkspace");
  const withIds = Array.from({ length: 30 }, (_, i) => ({ id: i + 1, role: "user", content: `m${i}` }));
  const b1 = wsTest.selectWorkspaceSummaryBatch(0, withIds, 24); // aged-out=id 1..6
  assert.strictEqual(b1.toSummarize.length, 6, "首次：摘要移出尾部的 6 条");
  assert.strictEqual(b1.throughId, 6, "水位推进到 6");
  const b2 = wsTest.selectWorkspaceSummaryBatch(6, withIds, 24);
  assert.strictEqual(b2.toSummarize.length, 0, "无新移出 → 不再摘要（省一次模型调用）");
  assert.strictEqual(b2.throughId, 6);
  const withMore = withIds.concat([{ id: 31, role: "user", content: "m30" }, { id: 32, role: "user", content: "m31" }]);
  const b3 = wsTest.selectWorkspaceSummaryBatch(6, withMore, 24); // aged-out=id 1..8，只 7,8 是新的
  assert.deepStrictEqual(b3.toSummarize.map((m) => m.id), [7, 8], "只摘新移出尾部的 id 7,8");
  assert.strictEqual(b3.throughId, 8);
  assert.strictEqual(wsTest.selectWorkspaceSummaryBatch(0, withIds.slice(0, 10), 24).toSummarize.length, 0, "短会话(<=tail) 不摘");
  const noId = Array.from({ length: 30 }, (_, i) => ({ role: "user", content: `x${i}` }));
  assert.strictEqual(wsTest.selectWorkspaceSummaryBatch(0, noId, 24).toSummarize.length, 6, "无稳定 id → 安全退回整段 aged-out");

  console.log("learningWorkspace.memory.test.js passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
