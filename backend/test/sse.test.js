const assert = require("assert");
const { sseFrame, SSE_HEADERS, sseCommentFrame, startSseHeartbeat } = require("../src/services/sse");

// event + JSON 对象
assert.strictEqual(sseFrame("text", { a: 1 }), 'event: text\ndata: {"a":1}\n\n');

// 无 event：仅 data 行
assert.strictEqual(sseFrame("", "hello"), "data: hello\n\n");

// 含换行的字符串：逐行补 data: 前缀（SSE 规范）
assert.strictEqual(sseFrame("x", "a\nb"), "event: x\ndata: a\ndata: b\n\n");

// null / undefined → "null"
assert.strictEqual(sseFrame("e", null), "event: e\ndata: null\n\n");
assert.strictEqual(sseFrame("e"), "event: e\ndata: null\n\n");

// 帧以空行结尾（事件分隔）
assert.ok(sseFrame("done", { id: "x" }).endsWith("\n\n"));

// 流式头：text/event-stream + 关缓存 + 关反代缓冲
assert.strictEqual(SSE_HEADERS["Content-Type"], "text/event-stream; charset=utf-8");
assert.ok(/no-cache/.test(SSE_HEADERS["Cache-Control"]));
assert.strictEqual(SSE_HEADERS["X-Accel-Buffering"], "no");

// 注释帧：": " 前缀 + 空行结尾（合法 SSE、被客户端解析器忽略）
assert.strictEqual(sseCommentFrame(), ": ping\n\n");
assert.strictEqual(sseCommentFrame("keepalive"), ": keepalive\n\n");

// 心跳：定时写注释帧；stop 后不再写；write 抛错时自动停止
(async () => {
  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  const writes = [];
  const raw = { write: (chunk) => writes.push(chunk) };
  const stop = startSseHeartbeat(raw, 10);
  await sleep(60);
  assert.ok(writes.length >= 2, `heartbeat should tick repeatedly (got ${writes.length})`);
  assert.ok(writes.every((w) => w === ": ping\n\n"), "heartbeat writes comment frames");
  stop();
  const afterStop = writes.length;
  await sleep(40);
  assert.strictEqual(writes.length, afterStop, "no writes after stop");

  // socket 已关闭（write 抛错）→ 心跳自动停止，不再重复抛
  let attempts = 0;
  const broken = { write: () => { attempts += 1; throw new Error("closed"); } };
  startSseHeartbeat(broken, 10);
  await sleep(60);
  assert.strictEqual(attempts, 1, "heartbeat self-stops after first failed write");

  console.log("sse.test.js passed");
})();
