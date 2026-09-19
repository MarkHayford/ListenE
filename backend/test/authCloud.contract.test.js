const assert = require("assert");
const { spawn } = require("child_process");
const path = require("path");
require("dotenv").config({ path: path.join(__dirname, "..", ".env") });

const PORT = 18102;
const BASE_URL = `http://127.0.0.1:${PORT}`;

function waitForServer(child) {
  return new Promise((resolve, reject) => {
    let output = "";
    const timeout = setTimeout(() => {
      reject(new Error(`server did not start in time. output:\n${output}`));
    }, 10000);

    function handleData(chunk) {
      output += chunk.toString();
      if (/ListenE Backend running/.test(output)) {
        clearTimeout(timeout);
        resolve(output);
      }
    }

    child.stdout.on("data", handleData);
    child.stderr.on("data", handleData);
    child.on("exit", (code) => {
      clearTimeout(timeout);
      reject(new Error(`server exited before ready with code ${code}. output:\n${output}`));
    });
  });
}

async function requestJson(pathname, options = {}) {
  const response = await fetch(`${BASE_URL}${pathname}`, {
    ...options,
    headers: {
      "content-type": "application/json",
      ...(options.headers || {})
    }
  });
  const body = await response.json();
  return { response, body };
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
      AUTH_TOKEN_SECRET: "test_secret_for_auth_cloud_contract",
      METRICS_TOKEN: "contract_metrics_token",
      DATABASE_URL: process.env.DATABASE_URL || "postgres://debian@%2Fvar%2Frun%2Fpostgresql/listene"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });

  try {
    await waitForServer(child);

    const ready = await requestJson("/readyz");
    assert.strictEqual(ready.response.status, 200);
    assert.strictEqual(ready.body.db, "up");

    const nonce = `${Date.now()}_${Math.random().toString(16).slice(2)}`;
    const email = `contract_${nonce}@example.com`;
    const password = "Contract-Test-Passw0rd";

    const registered = await requestJson("/api/v1/auth/register", {
      method: "POST",
      body: JSON.stringify({ email, password, displayName: "契约测试用户" })
    });
    assert.strictEqual(registered.response.status, 201);
    assert.ok(registered.body.token, "register should return token");
    assert.strictEqual(registered.body.user.email, email);
    assert.ok(!registered.body.user.passwordHash, "user response must not expose password hash");

    const token = registered.body.token;
    const authHeaders = { authorization: `Bearer ${token}` };

    const me = await requestJson("/api/v1/auth/me", { headers: authHeaders });
    assert.strictEqual(me.response.status, 200);
    assert.strictEqual(me.body.user.email, email);

    const unauthorized = await requestJson("/api/v1/auth/me");
    assert.strictEqual(unauthorized.response.status, 401);

    const loggedIn = await requestJson("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password })
    });
    assert.strictEqual(loggedIn.response.status, 200);
    assert.ok(loggedIn.body.token, "login should return token");

    // 滑动续期：用当前有效 token 换新 token，新 token 可用；无 token 则 401。
    const refreshed = await requestJson("/api/v1/auth/refresh", { method: "POST", headers: authHeaders, body: JSON.stringify({}) });
    assert.strictEqual(refreshed.response.status, 200, "refresh with valid token should 200");
    assert.ok(refreshed.body.token, "refresh should return a new token");
    const meRefreshed = await requestJson("/api/v1/auth/me", { headers: { authorization: `Bearer ${refreshed.body.token}` } });
    assert.strictEqual(meRefreshed.response.status, 200, "refreshed token should work");
    assert.strictEqual(meRefreshed.body.user.email, email);
    const refreshNoAuth = await requestJson("/api/v1/auth/refresh", { method: "POST", body: JSON.stringify({}) });
    assert.strictEqual(refreshNoAuth.response.status, 401, "refresh without token should 401");

    const created = await requestJson("/api/v1/agent/workspaces", {
      method: "POST",
      headers: authHeaders,
      body: JSON.stringify({ need: "present perfect grammar" })
    });
    assert.strictEqual(created.response.status, 201);
    assert.strictEqual(created.body.persisted, true);
    assert.strictEqual(created.body.workspace.plan.contentType, "chat");

    const listed = await requestJson("/api/v1/agent/workspaces", { headers: authHeaders });
    assert.strictEqual(listed.response.status, 200);
    assert.ok(listed.body.workspaces.some((workspace) => workspace.id === created.body.workspace.id));

    const messages = [
      { id: 1, role: "user", text: "present perfect grammar" },
      { id: 2, role: "agent", text: "Use have or has plus past participle." }
    ];
    const savedMessages = await requestJson(`/api/v1/agent/workspaces/${created.body.workspace.id}/messages`, {
      method: "PUT",
      headers: authHeaders,
      body: JSON.stringify({ messages })
    });
    assert.strictEqual(savedMessages.response.status, 200);
    assert.strictEqual(savedMessages.body.persisted, true);

    const loadedMessages = await requestJson(`/api/v1/agent/workspaces/${created.body.workspace.id}/messages`, {
      headers: authHeaders
    });
    assert.strictEqual(loadedMessages.response.status, 200);
    assert.deepStrictEqual(loadedMessages.body.messages.map((message) => message.text), messages.map((message) => message.text));

    const analysis = await requestJson("/api/v1/agent/analysis", {
      method: "POST",
      headers: authHeaders,
      body: JSON.stringify({
        workspaceId: created.body.workspace.id,
        recordId: "analysis_memory_record_1",
        title: "AI 错因分析完成",
        result: {
          summary: "User confused already and yet.",
          weakPoints: ["already 和 yet 的区别不稳"]
        }
      })
    });
    assert.strictEqual(analysis.response.status, 200);

    const listedAfterAnalysis = await requestJson("/api/v1/agent/workspaces", { headers: authHeaders });
    assert.strictEqual(listedAfterAnalysis.response.status, 200);
    const workspaceAfterAnalysis = listedAfterAnalysis.body.workspaces.find((workspace) => workspace.id === created.body.workspace.id);
    assert.ok(workspaceAfterAnalysis, "workspace should still exist after analysis");
    assert.ok(
      workspaceAfterAnalysis.memory.some((item) =>
        item.type === "weakness" && /already 和 yet/.test(item.content)
      ),
      "analysis weak points should be written into workspace memory"
    );

    for (const path of ["/api/v1/library/files", "/api/v1/library/cards", "/api/v1/library/plugins"]) {
      const result = await requestJson(path, { headers: authHeaders });
      assert.strictEqual(result.response.status, 200);
      assert.ok(Array.isArray(result.body.items), `${path} should return items array`);
    }

    const session = await requestJson("/api/v1/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password })
    });
    assert.strictEqual(session.response.status, 200);
    const sessionHeaders = { authorization: `Bearer ${session.body.token}` };
    const beforeLogout = await requestJson("/api/v1/auth/me", { headers: sessionHeaders });
    assert.strictEqual(beforeLogout.response.status, 200);
    const unauthorizedAuthMonitor = await requestJson(`/api/v1/metrics/auth/account?email=${encodeURIComponent(email)}`);
    assert.strictEqual(unauthorizedAuthMonitor.response.status, 401);
    const authMonitor = await requestJson(`/api/v1/metrics/auth/account?email=${encodeURIComponent(email)}`, {
      headers: { authorization: "Bearer contract_metrics_token" }
    });
    assert.strictEqual(authMonitor.response.status, 200);
    assert.strictEqual(authMonitor.body.exists, true);
    assert.ok(authMonitor.body.activeSessionCount >= 1, "auth monitor should report an active session");
    assert.ok(authMonitor.body.recentEvents.some((event) => event.eventType === "login" && event.success));
    const loggedOut = await requestJson("/api/v1/auth/logout", { method: "POST", headers: sessionHeaders, body: JSON.stringify({}) });
    assert.strictEqual(loggedOut.response.status, 200);
    const afterLogout = await requestJson("/api/v1/auth/me", { headers: sessionHeaders });
    assert.strictEqual(afterLogout.response.status, 401, "token should be revoked after logout");
    const authMonitorAfterLogout = await requestJson(`/api/v1/metrics/auth/account?email=${encodeURIComponent(email)}`, {
      headers: { authorization: "Bearer contract_metrics_token" }
    });
    assert.strictEqual(authMonitorAfterLogout.response.status, 200);
    assert.strictEqual(authMonitorAfterLogout.body.activeSessionCount, 0);
    assert.ok(authMonitorAfterLogout.body.recentEvents.some((event) => event.eventType === "logout" && event.success));

    console.log("authCloud.contract.test.js passed");
  } finally {
    child.kill("SIGTERM");
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
