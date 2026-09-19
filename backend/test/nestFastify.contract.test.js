const assert = require("assert");
const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");

const PORT = 18101;
const BASE_URL = `http://127.0.0.1:${PORT}`;
const audioName = `__route_contract_${process.pid}.txt`;
const audioPath = path.join(__dirname, "..", "storage", "audio", audioName);
fs.mkdirSync(path.dirname(audioPath), { recursive: true });
fs.writeFileSync(audioPath, "versioned-audio-route", "utf8");

function waitForServer(child) {
  return new Promise((resolve, reject) => {
    let output = "";
    const timeout = setTimeout(() => {
      reject(new Error(`server did not start in time. output:\n${output}`));
    }, 8000);

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
      SWAGGER_ENABLED: "true"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });

  try {
    await waitForServer(child);

    const health = await requestJson("/healthz");
    assert.strictEqual(health.response.status, 200);
    assert.strictEqual(health.body.status, "ok");

    for (const path of ["/api/health", "/api/health/ready", "/api/v1/health", "/api/v1/health/ready"]) {
      const retiredHealth = await requestJson(path);
      assert.strictEqual(retiredHealth.response.status, 404, `${path} must not remain available`);
    }

    const docs = await requestJson("/api/v1/docs-json");
    assert.strictEqual(docs.response.status, 200, "swagger spec should be served when enabled");
    assert.ok(docs.body.openapi || docs.body.swagger, "swagger spec should be valid OpenAPI");
    const documentedPaths = Object.keys(docs.body.paths || {});
    const unexpectedPaths = documentedPaths.filter((path) => (
      path !== "/healthz" && path !== "/readyz" && !path.startsWith("/api/v1/")
    ));
    assert.deepStrictEqual(unexpectedPaths, [], "all documented non-probe routes must be versioned under /api/v1");
    for (const expected of [
      "/healthz",
      "/readyz",
      "/api/v1/auth/login",
      "/api/v1/listening/generate",
      "/api/v1/agent/chat",
      "/api/v1/library/files",
      "/api/v1/plan/generate",
      "/api/v1/plans",
      "/api/v1/progress/summary",
      "/api/v1/review",
      "/api/v1/vocab",
      "/api/v1/daily",
      "/api/v1/metrics"
    ]) {
      assert.ok(documentedPaths.includes(expected), `swagger should document ${expected}`);
    }

    const versionedAudio = await fetch(`${BASE_URL}/api/v1/audio/${audioName}`);
    assert.strictEqual(versionedAudio.status, 200, "versioned static audio route should serve existing files");
    assert.strictEqual(await versionedAudio.text(), "versioned-audio-route");
    const retiredAudio = await fetch(`${BASE_URL}/audio/${audioName}`);
    assert.strictEqual(retiredAudio.status, 404, "retired unversioned static audio alias must not serve files");

    const retiredBusinessRoute = await requestJson("/api/agent/workspaces");
    assert.strictEqual(retiredBusinessRoute.response.status, 404, "unversioned internal API path must not remain available");

    const created = await requestJson("/api/v1/agent/workspaces", {
      method: "POST",
      body: JSON.stringify({ need: "present perfect grammar" })
    });
    assert.strictEqual(created.response.status, 201);
    assert.strictEqual(created.body.workspace.plan.contentType, "chat");
    assert.strictEqual(created.body.workspace.currentStep, "chat");
    assert.ok(!/听力训练|素材|题目/.test(created.body.workspace.title));
    assert.strictEqual(created.response.headers.get("x-content-type-options"), "nosniff", "should set nosniff security header");
    assert.strictEqual(created.response.headers.get("x-frame-options"), "DENY", "should set X-Frame-Options security header");
    assert.ok(created.response.headers.get("x-request-id"), "should set X-Request-Id header");

    console.log("nestFastify.contract.test.js passed");
  } finally {
    child.kill("SIGTERM");
    try { fs.unlinkSync(audioPath); } catch (_) {}
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
