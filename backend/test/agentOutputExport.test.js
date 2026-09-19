const assert = require("assert");
const { createAgentOutputExport } = require("../src/services/agentOutputExport");

// Pure functions under test don't use the injected deps; stub them anyway.
const api = createAgentOutputExport({
  normalizeAgentCardSpec: (x) => x,
  normalizeAgentStringArray: (x) => (Array.isArray(x) ? x : []),
  stripAgentQuestionOptionLabel: (x) => x,
  isStrictRegisterOnlyAgentCardRequest: () => false
});

// sanitizeAgentOutputFormat: aliases + allow-list, default txt.
assert.strictEqual(api.sanitizeAgentOutputFormat("docx"), "docx");
assert.strictEqual(api.sanitizeAgentOutputFormat("word"), "docx");
assert.strictEqual(api.sanitizeAgentOutputFormat("markdown"), "md");
assert.strictEqual(api.sanitizeAgentOutputFormat("report.json"), "json");
assert.strictEqual(api.sanitizeAgentOutputFormat("pdf"), "txt", "unsupported -> txt");
assert.strictEqual(api.sanitizeAgentOutputFormat(""), "txt");

// outputMimeType: explicit mime passthrough, else derived from format.
assert.strictEqual(api.outputMimeType("", "zip"), "application/zip");
assert.strictEqual(api.outputMimeType("", "json"), "application/json");
assert.strictEqual(api.outputMimeType("application/pdf", "txt"), "application/pdf", "explicit mime passthrough");
assert.strictEqual(api.outputMimeType("", "txt"), "text/plain");

// agentExportOptionLetter: index -> A/B/C...
assert.strictEqual(api.agentExportOptionLetter(0), "A");
assert.strictEqual(api.agentExportOptionLetter(2), "C");

// inferAgentOutputFormat: explicit format cues are deterministic.
assert.strictEqual(api.inferAgentOutputFormat("导出 zip 压缩包"), "zip");
assert.strictEqual(api.inferAgentOutputFormat("export as docx"), "docx");
assert.strictEqual(api.inferAgentOutputFormat("markdown 格式"), "md");
assert.strictEqual(api.inferAgentOutputFormat("json 文件"), "json");

// sanitizeAgentOutputFileName: strips path/illegal chars (no traversal), appends extension.
const f1 = api.sanitizeAgentOutputFileName("../../etc/passwd", "txt");
assert.ok(!f1.includes("/") && !f1.includes("\\"), "path separators stripped");
assert.ok(f1.endsWith(".txt"), "extension appended");
const f2 = api.sanitizeAgentOutputFileName('bad:name*?"<>|', "docx");
assert.ok(!/[\\/:*?"<>|]/.test(f2.slice(0, -5)), "illegal chars stripped");
assert.ok(f2.endsWith(".docx"));
assert.strictEqual(api.sanitizeAgentOutputFileName("report.txt", "txt"), "report.txt", "matching extension kept");
assert.strictEqual(api.sanitizeAgentOutputFileName("", "md"), "ListenE-Agent.md", "empty name -> default");

console.log("agentOutputExport.test.js passed");
