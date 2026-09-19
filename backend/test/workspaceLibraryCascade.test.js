const assert = require("assert");

process.env.MIMO_API_KEY = "";

const calls = [];
const dbPath = require.resolve("../src/services/db");
require.cache[dbPath] = {
  id: dbPath,
  filename: dbPath,
  loaded: true,
  exports: {
    query: async (sql, params = []) => {
      calls.push({ sql: String(sql).replace(/\s+/g, " ").trim(), params });
      return { rows: [], rowCount: /user_library_items/.test(sql) ? 2 : 1 };
    }
  }
};

delete require.cache[require.resolve("../src/services/userLibrary")];
delete require.cache[require.resolve("../src/services/learningWorkspace")];

const { deleteWorkspace } = require("../src/services/learningWorkspace");

(async () => {
  const result = await deleteWorkspace("workspace_1", { id: "user_1" });

  assert.strictEqual(result.deleted, true);
  assert.strictEqual(result.workspaceId, "workspace_1");
  assert.strictEqual(result.persisted, true);
  assert.strictEqual(result.libraryDeleted, 2);
  assert.strictEqual(calls.length, 2);

  assert.match(calls[0].sql, /DELETE FROM user_library_items/);
  assert.match(calls[0].sql, /kind IN \('file', 'card'\)/);
  assert.match(calls[0].sql, /data->>'workspaceId' = \$2/);
  assert.deepStrictEqual(calls[0].params, ["user_1", "workspace_1"]);

  assert.match(calls[1].sql, /DELETE FROM workspaces/);
  assert.deepStrictEqual(calls[1].params, ["user_1", "workspace_1"]);

  console.log("workspaceLibraryCascade.test.js passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
