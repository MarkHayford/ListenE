const assert = require("assert");
const fs = require("fs");
const path = require("path");

process.env.MIMO_API_KEY = "";

const backendRoot = path.resolve(__dirname, "..");
const forbiddenUserDataPaths = [
  path.join(backendRoot, "storage", "agent", "workspaces.json"),
  path.join(backendRoot, "storage", "agent", "events.json"),
  path.join(backendRoot, "storage", "agent", "profile.json"),
  path.join(backendRoot, "storage", "history", "records.json")
];
const before = new Map(
  forbiddenUserDataPaths.map((file) => [file, fs.existsSync(file) ? fs.readFileSync(file, "utf8") : null])
);

const {
  listWorkspaces,
  createWorkspace,
  updateWorkspace,
  deleteWorkspace,
  recordWorkspaceEvent,
  __test
} = require("../src/services/learningWorkspace");

(async () => {
  assert.deepStrictEqual(await listWorkspaces(), []);

  const created = await createWorkspace({ need: "practice travel listening slowly" });
  assert.strictEqual(created.persisted, false);
  assert.ok(created.workspace.id);
  assert.strictEqual(created.workspace.currentStep, "material");
  assert.strictEqual(created.workspace.memorySummary, "");
  assert.deepStrictEqual(created.workspace.memory, []);
  assert.deepStrictEqual(await listWorkspaces(), []);

  const questionWorkspace = await createWorkspace({ need: "present perfect grammar" });
  assert.strictEqual(questionWorkspace.persisted, false);
  assert.ok(questionWorkspace.workspace.id);
  assert.strictEqual(questionWorkspace.workspace.currentStep, "chat");
  assert.strictEqual(questionWorkspace.workspace.plan.contentType, "chat");
  assert.ok(!/听力训练|素材|题目/.test(questionWorkspace.workspace.title));
  assert.deepStrictEqual(await listWorkspaces(), []);

  const updated = await updateWorkspace(created.workspace.id, { currentStep: "analysis", status: "active" });
  assert.strictEqual(updated.persisted, false);
  assert.strictEqual(updated.workspace.id, created.workspace.id);
  assert.strictEqual(updated.workspace.currentStep, "analysis");
  assert.deepStrictEqual(await listWorkspaces(), []);

  const eventResult = await recordWorkspaceEvent(created.workspace.id, {
    type: "analysis_complete",
    title: "AI analysis complete",
    recordId: "local_record_1",
    currentStep: "review"
  });
  assert.strictEqual(eventResult.persisted, false);
  assert.strictEqual(eventResult.workspace.currentStep, "review");
  assert.ok(eventResult.workspace.memory.some((item) => item.type === "weakness" && /AI analysis complete/i.test(item.content)));
  assert.deepStrictEqual(await listWorkspaces(), []);

  const explicitMemory = __test.extractExplicitMemoryEntriesFromMessages([
    { role: "user", text: "Remember: I prefer British pronunciation practice." }
  ], 1000);
  assert.strictEqual(explicitMemory[0].type, "preference");
  assert.match(explicitMemory[0].content, /British pronunciation/);

  const summary = __test.summarizeWorkspaceMessages("", [
    { role: "user", content: "First I asked about present perfect." },
    { role: "assistant", content: "We practiced already and yet." }
  ]);
  assert.match(summary, /present perfect/);
  assert.match(summary, /already and yet/);

  const deleted = await deleteWorkspace(created.workspace.id);
  assert.deepStrictEqual(deleted, {
    deleted: true,
    workspaceId: created.workspace.id,
    persisted: false
  });
  assert.deepStrictEqual(await listWorkspaces(), []);

  for (const file of forbiddenUserDataPaths) {
    const previous = before.get(file);
    if (previous == null) {
      assert.strictEqual(fs.existsSync(file), false, `${file} should not be created`);
    } else {
      assert.strictEqual(fs.readFileSync(file, "utf8"), previous, `${file} should not be modified`);
    }
  }

  console.log("learningWorkspace.delete.test.js passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
