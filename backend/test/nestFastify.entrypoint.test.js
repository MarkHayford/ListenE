const assert = require("assert");
const fs = require("fs");
const path = require("path");

const rootDir = path.join(__dirname, "..");
const packageJson = require(path.join(rootDir, "package.json"));

assert.strictEqual(packageJson.main, "dist/main.js");
assert.strictEqual(packageJson.scripts.start, "node dist/main.js");

const runtimeDeps = {
  ...(packageJson.dependencies || {}),
  ...(packageJson.devDependencies || {})
};

assert.ok(runtimeDeps["@nestjs/platform-fastify"], "Nest Fastify platform dependency is required");
assert.ok(runtimeDeps.fastify, "Fastify dependency is required");
assert.ok(!runtimeDeps.express, "Express must not be a direct dependency");
assert.ok(!runtimeDeps.cors, "Express CORS middleware must not be a direct dependency");
assert.ok(!runtimeDeps["@nestjs/platform-express"], "Nest Express platform must not be configured");
assert.ok(!fs.existsSync(path.join(rootDir, "server.js")), "service must start dist/main.js directly; server.js shim is not allowed");

// 部署：已迁移到 Linux + systemd（codecloud-listene.service，单元文件在仓库外的
// /etc/systemd/system/），直接 `node dist/main.js`。历史的 Windows NSSM 脚本 deploy.ps1
// 已随迁移移除，因此这里不再要求它存在。
assert.ok(
  !fs.existsSync(path.join(rootDir, "ecosystem.config.cjs")),
  "PM2 ecosystem config is residual; deployment uses systemd (node dist/main.js)"
);

console.log("nestFastify.entrypoint.test.js passed");
