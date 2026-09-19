// 轻量 SSE(Server-Sent Events) 帧工具：把 (event, data) 序列化为合法的 text/event-stream 帧。
// 纯函数、零依赖，便于单测。data 为对象时按 JSON 序列化；含换行的字符串按规范逐行补 "data:" 前缀。

const SSE_HEADERS = {
  "Content-Type": "text/event-stream; charset=utf-8",
  "Cache-Control": "no-cache, no-transform",
  Connection: "keep-alive",
  // 关闭 Nginx 等反代对流式响应的缓冲，确保「文本先到」能即时下发而非攒批。
  "X-Accel-Buffering": "no"
};

function sseFrame(event, data) {
  const lines = [];
  if (event) lines.push(`event: ${String(event)}`);
  const payload = typeof data === "string" ? data : JSON.stringify(data ?? null);
  for (const line of String(payload).split("\n")) {
    lines.push(`data: ${line}`);
  }
  return `${lines.join("\n")}\n\n`;
}

// SSE 注释帧（": ..." 开头）：规范合法、被所有客户端解析器忽略，专用于连接保活。
function sseCommentFrame(text = "ping") {
  return `: ${String(text)}\n\n`;
}

// 心跳保活：长模型调用期间事件流可静默 20-60s，移动网络 NAT / 反向代理
// （如 Nginx proxy_read_timeout 默认 60s）会掐断"空闲"连接，客户端只能整体回退重跑。
// 定时写注释帧让链路始终有字节流动。返回 stop 函数；socket 写失败时自动停止。
function startSseHeartbeat(raw, intervalMs = 15000) {
  const timer = setInterval(() => {
    try {
      raw.write(sseCommentFrame());
    } catch (_) {
      clearInterval(timer);
    }
  }, Math.max(1, Number(intervalMs) || 15000));
  if (typeof timer.unref === "function") timer.unref();
  return () => clearInterval(timer);
}

module.exports = { SSE_HEADERS, sseFrame, sseCommentFrame, startSseHeartbeat };
