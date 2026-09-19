// 端到端契约测试：POST /api/v1/listening/generate/stream 的 SSE 流式错误路径。
//
// 在 MOCK_MODE 下 spawn 真实服务（无真实模型 key），内容生成必然失败——这恰好确定性地
// 覆盖了该端点的「流式错误处理」契约：即便失败，也要以合法 SSE 帧推 error 事件、HTTP 200、
// 正确的流式响应头，且只给通用文案、绝不泄露内部细节（控制器注释承诺的 "不向客户端泄露内部细节"）。
//
// 注：text -> audio -> done 的成功路径依赖真实 MiMo/TTS 输出（非确定性、需真实 key），
// 不在此 hermetic 契约测试内；其行为由安卓侧 instrumented + 真机后端实测覆盖。
// 帧序列化本身另由 test/sse.test.js 单测覆盖。

const assert = require("assert");
const { spawn } = require("child_process");

const PORT = 18102;
const BASE_URL = `http://127.0.0.1:${PORT}`;

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

// 解析 text/event-stream 文本为 [{ event, data }]（data 尽量 JSON.parse）。
function parseSse(text) {
  return text
    .split("\n\n")
    .filter((block) => block.trim().length > 0)
    .map((block) => {
      let event = "message";
      const dataLines = [];
      for (const line of block.split("\n")) {
        if (line.startsWith("event:")) event = line.slice("event:".length).trim();
        else if (line.startsWith("data:")) dataLines.push(line.slice("data:".length).replace(/^ /, ""));
      }
      let data = dataLines.join("\n");
      try { data = JSON.parse(data); } catch { /* 保留原始字符串 */ }
      return { event, data };
    });
}

(async () => {
  const child = spawn(process.execPath, ["dist/main.js"], {
    cwd: __dirname + "/..",
    env: {
      ...process.env,
      HOST: "127.0.0.1",
      PORT: String(PORT),
      MOCK_MODE: "true",
      MIMO_API_KEY: ""
    },
    stdio: ["ignore", "pipe", "pipe"]
  });

  try {
    await waitForServer(child);

    const response = await fetch(`${BASE_URL}/api/v1/listening/generate/stream`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        scene: "campus cafe small talk",
        difficulty: "普通",
        contentType: "dialogue",
        skipAudio: true
      })
    });
    const raw = await response.text();
    const events = parseSse(raw);

    // 即便内容生成失败，也必须是合法的 SSE 流式响应（HTTP 200 + 流式头 + 帧）。
    assert.strictEqual(response.status, 200, "stream endpoint responds 200 even on failure");
    assert.match(
      response.headers.get("content-type") || "",
      /text\/event-stream/,
      "should stream as text/event-stream (hijacked raw response)"
    );
    assert.strictEqual(
      response.headers.get("x-accel-buffering"),
      "no",
      "should disable proxy buffering for streaming"
    );

    // 错误以 SSE error 事件下发，且为通用文案。
    const errorEvent = events.find((e) => e.event === "error");
    assert.ok(errorEvent, "an error event must be emitted when generation fails");
    assert.strictEqual(
      errorEvent.data && errorEvent.data.detail,
      "服务暂时不可用，请稍后重试。",
      "error detail must be the generic client-safe message"
    );

    // 帧结构合法：每个 data 行以 "data:" 开头，事件以空行分隔；并以 \n\n 结尾。
    assert.ok(raw.includes("event: error"), "raw stream contains a well-formed error event line");
    assert.ok(raw.endsWith("\n\n"), "SSE frames terminate with a blank line");

    // 绝不泄露内部细节（如上游 key/异常文案、堆栈）。
    assert.ok(!/Invalid API Key/i.test(raw), "must not leak upstream API key error");
    assert.ok(!/stack|\.ts:\d+|at Object|node:internal/i.test(raw), "must not leak stack traces / internals");

    console.log("sseEndpoint.contract.test.js passed");
  } finally {
    child.kill("SIGTERM");
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
