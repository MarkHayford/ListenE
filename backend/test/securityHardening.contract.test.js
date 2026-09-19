// 端到端契约测试（真 PG + 真 HTTP）：覆盖三项加固——
//  1) BOLA/IDOR 跨用户隔离：用户 B 看不到/改不动/删不了用户 A 的对象；
//  2) 关键端点输入长度校验：超长 plan goal / translate text 返回 400；
//  3) /api/v1/metrics 受保护：无/错令牌 401，正确 Bearer 令牌 200。
const assert = require("assert");
const { spawn } = require("child_process");
const path = require("path");
require("dotenv").config({ path: path.join(__dirname, "..", ".env") });

const PORT = 18103;
const BASE_URL = `http://127.0.0.1:${PORT}`;
const METRICS_TOKEN = "test_metrics_token_for_contract_xyz";

function waitForServer(child) {
  return new Promise((resolve, reject) => {
    let output = "";
    const timeout = setTimeout(() => reject(new Error(`server did not start in time. output:\n${output}`)), 10000);
    function handleData(chunk) {
      output += chunk.toString();
      if (/ListenE Backend running/.test(output)) { clearTimeout(timeout); resolve(output); }
    }
    child.stdout.on("data", handleData);
    child.stderr.on("data", handleData);
    child.on("exit", (code) => { clearTimeout(timeout); reject(new Error(`server exited before ready with code ${code}. output:\n${output}`)); });
  });
}

async function requestRaw(pathname, options = {}) {
  const response = await fetch(`${BASE_URL}${pathname}`, {
    ...options,
    headers: { "content-type": "application/json", ...(options.headers || {}) }
  });
  let body = null;
  const text = await response.text();
  try { body = text ? JSON.parse(text) : null; } catch { body = text; }
  return { response, body, text };
}

async function registerUser(tag) {
  const nonce = `${Date.now()}_${Math.random().toString(16).slice(2)}`;
  const email = `bola_${tag}_${nonce}@example.com`;
  const password = "Contract-Test-Passw0rd";
  const res = await requestRaw("/api/v1/auth/register", {
    method: "POST",
    body: JSON.stringify({ email, password, displayName: `bola-${tag}` })
  });
  assert.strictEqual(res.response.status, 201, `register ${tag} should 201`);
  return { token: res.body.token, headers: { authorization: `Bearer ${res.body.token}` } };
}

(async () => {
  const child = spawn(process.execPath, ["dist/main.js"], {
    cwd: __dirname + "/..",
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(PORT),
      MOCK_MODE: "true",
      MIMO_API_KEY: "",
      METRICS_TOKEN,
      AUTH_TOKEN_SECRET: "test_secret_for_security_hardening_contract",
      DATABASE_URL: process.env.DATABASE_URL || "postgres://debian@%2Fvar%2Frun%2Fpostgresql/listene"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });

  try {
    await waitForServer(child);

    const A = await registerUser("alice");
    const B = await registerUser("bob");

    // ---- A 创建对象 ----
    const wsCreated = await requestRaw("/api/v1/agent/workspaces", {
      method: "POST", headers: A.headers, body: JSON.stringify({ need: "present perfect grammar" })
    });
    assert.strictEqual(wsCreated.response.status, 201);
    const wsId = wsCreated.body.workspace.id;

    const SECRET = "ALICE_SECRET_MSG_e2e";
    const saved = await requestRaw(`/api/v1/agent/workspaces/${wsId}/messages`, {
      method: "PUT", headers: A.headers,
      body: JSON.stringify({ messages: [{ id: 1, role: "user", text: SECRET }] })
    });
    assert.strictEqual(saved.response.status, 200);

    const fileCreated = await requestRaw("/api/v1/library/files", {
      method: "POST", headers: A.headers,
      body: JSON.stringify({ title: "Alice Secret File", summary: "private", data: {} })
    });
    assert.strictEqual(fileCreated.response.status, 201);
    const fileId = fileCreated.body.item.id;

    // ---- BOLA：B 看不到 A 的对象 ----
    const bWorkspaces = await requestRaw("/api/v1/agent/workspaces", { headers: B.headers });
    assert.strictEqual(bWorkspaces.response.status, 200);
    assert.ok(!bWorkspaces.body.workspaces.some((w) => w.id === wsId), "B 不应看到 A 的 workspace");

    const bFiles = await requestRaw("/api/v1/library/files", { headers: B.headers });
    assert.strictEqual(bFiles.response.status, 200);
    assert.ok(!bFiles.body.items.some((it) => it.id === fileId), "B 不应看到 A 的 library 文件");

    // ---- BOLA：B 读不到 A 的 workspace 消息（不得返回 A 的密文）----
    const bReadMsgs = await requestRaw(`/api/v1/agent/workspaces/${wsId}/messages`, { headers: B.headers });
    assert.ok(!(bReadMsgs.response.status === 200 && String(bReadMsgs.text).includes(SECRET)), "B 不应读到 A 的消息内容");

    // ---- BOLA：B 改不动 A 的消息（尝试后 A 的数据仍是原样）----
    await requestRaw(`/api/v1/agent/workspaces/${wsId}/messages`, {
      method: "PUT", headers: B.headers, body: JSON.stringify({ messages: [{ id: 9, role: "user", text: "HACKED_BY_BOB" }] })
    });
    const aReadAfter = await requestRaw(`/api/v1/agent/workspaces/${wsId}/messages`, { headers: A.headers });
    assert.strictEqual(aReadAfter.response.status, 200);
    assert.ok(aReadAfter.text.includes(SECRET) && !aReadAfter.text.includes("HACKED_BY_BOB"), "A 的消息不应被 B 覆盖");

    // ---- BOLA：B 删不了 A 的 workspace（尝试后 A 仍能看到）----
    await requestRaw(`/api/v1/agent/workspaces/${wsId}`, { method: "DELETE", headers: B.headers });
    const aWorkspacesAfter = await requestRaw("/api/v1/agent/workspaces", { headers: A.headers });
    assert.ok(aWorkspacesAfter.body.workspaces.some((w) => w.id === wsId), "B 删除不应影响 A 的 workspace");

    // ---- BOLA：B 删不了 A 的 library 文件 ----
    await requestRaw(`/api/v1/library/files/${fileId}`, { method: "DELETE", headers: B.headers });
    const aFilesAfter = await requestRaw("/api/v1/library/files", { headers: A.headers });
    assert.ok(aFilesAfter.body.items.some((it) => it.id === fileId), "B 删除不应影响 A 的 library 文件");

    // ---- 输入长度校验：超长 goal / text → 400 ----
    const bigGoal = await requestRaw("/api/v1/plan/generate", { method: "POST", body: JSON.stringify({ goal: "x".repeat(5000) }) });
    assert.strictEqual(bigGoal.response.status, 400, "超长 plan goal 应 400");
    const bigText = await requestRaw("/api/v1/translate", { method: "POST", body: JSON.stringify({ text: "x".repeat(9000) }) });
    assert.strictEqual(bigText.response.status, 400, "超长 translate text 应 400");

    // ---- /api/v1/metrics 受保护 ----
    const noToken = await requestRaw("/api/v1/metrics");
    assert.strictEqual(noToken.response.status, 401, "无令牌应 401");
    const wrongToken = await requestRaw("/api/v1/metrics", { headers: { authorization: "Bearer wrong" } });
    assert.strictEqual(wrongToken.response.status, 401, "错误令牌应 401");
    const okMetrics = await requestRaw("/api/v1/metrics", { headers: { authorization: `Bearer ${METRICS_TOKEN}` } });
    assert.strictEqual(okMetrics.response.status, 200, "正确令牌应 200");
    assert.ok(typeof okMetrics.body.total === "number" && okMetrics.body.byClass && typeof okMetrics.body.serverErrorRate === "number", "metrics 快照字段齐全");

    console.log("securityHardening.contract.test.js passed");
  } finally {
    child.kill("SIGTERM");
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
