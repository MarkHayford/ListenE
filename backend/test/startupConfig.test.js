const assert = require("assert");
const { collectStartupConfigErrors, DEFAULT_AUTH_TOKEN_SECRET } = require("../src/config");

const strongSecret = "f3a9c1e87b4d2f60a1c9e5d4b8273106f3a9c1e87b4d2f60a1c9e5d4b8273106";
const realDb = "postgres://listene:s3cr3t@db.internal:5432/listene";
const validCors = ["https://app.example.com"]; // 生产合法来源白名单，隔离出被测的单一维度

// Production-like (mock off): default/placeholder secrets must be rejected.
const prodDefaults = collectStartupConfigErrors(
  { mockMode: false, authTokenSecret: DEFAULT_AUTH_TOKEN_SECRET, databaseUrl: "postgres://listene:change-me@127.0.0.1:5432/listene", corsOrigins: validCors },
  ""
);
assert.strictEqual(prodDefaults.length, 2, "default secret + change-me DB must produce two startup errors");
assert.ok(prodDefaults.some((m) => /AUTH_TOKEN_SECRET/.test(m)), "should flag default AUTH_TOKEN_SECRET");
assert.ok(prodDefaults.some((m) => /DATABASE_URL/.test(m)), "should flag change-me DATABASE_URL");

// A literal "change-me" secret is also rejected.
const changeMeSecret = collectStartupConfigErrors(
  { mockMode: false, authTokenSecret: "change-me", databaseUrl: realDb, corsOrigins: validCors },
  ""
);
assert.strictEqual(changeMeSecret.length, 1, "change-me secret must be rejected even with a real DB");
assert.ok(/AUTH_TOKEN_SECRET/.test(changeMeSecret[0]));

// Empty secret is rejected.
assert.strictEqual(
  collectStartupConfigErrors({ mockMode: false, authTokenSecret: "", databaseUrl: realDb, corsOrigins: validCors }, "").length,
  1,
  "empty AUTH_TOKEN_SECRET must be rejected"
);

// Properly configured production boots clean.
assert.deepStrictEqual(
  collectStartupConfigErrors({ mockMode: false, authTokenSecret: strongSecret, databaseUrl: realDb, corsOrigins: validCors }, ""),
  [],
  "strong secret + real DB + explicit CORS allowlist should produce no startup errors"
);

// CORS 加固：生产禁止未配置(默认 "*")或通配来源，避免任意站点跨源调用。
const goodBase = { mockMode: false, authTokenSecret: strongSecret, databaseUrl: realDb };
const corsWildcard = collectStartupConfigErrors({ ...goodBase, corsOrigins: ["*"] }, "");
assert.strictEqual(corsWildcard.length, 1, "通配 CORS 应产生一条启动错误");
assert.ok(/CORS_ORIGINS/.test(corsWildcard[0]), "should flag wildcard CORS_ORIGINS");
const corsMissing = collectStartupConfigErrors({ ...goodBase, corsOrigins: [] }, "");
assert.strictEqual(corsMissing.length, 1, "未配置 CORS 应产生一条启动错误");
assert.ok(/CORS_ORIGINS/.test(corsMissing[0]), "空 CORS 也应被 flag");
assert.deepStrictEqual(
  collectStartupConfigErrors({ ...goodBase, corsOrigins: ["https://a.example.com", "https://b.example.com"] }, ""),
  [],
  "显式来源白名单应干净启动"
);

// Escape hatches must not break local/CI runs even with placeholder secrets AND wildcard CORS.
const placeholders = { authTokenSecret: DEFAULT_AUTH_TOKEN_SECRET, databaseUrl: "postgres://listene:change-me@127.0.0.1:5432/listene", corsOrigins: ["*"] };
assert.deepStrictEqual(
  collectStartupConfigErrors({ ...placeholders, mockMode: true }, ""),
  [],
  "MOCK_MODE=true must skip the guard"
);
assert.deepStrictEqual(
  collectStartupConfigErrors({ ...placeholders, mockMode: false }, "development"),
  [],
  "NODE_ENV=development must skip the guard"
);
assert.deepStrictEqual(
  collectStartupConfigErrors({ ...placeholders, mockMode: false }, "test"),
  [],
  "NODE_ENV=test must skip the guard"
);

console.log("startupConfig.test.js passed");
