// 流式 JSON reply 字段提取器：Agent 聊天的 LLM 响应是 JSON envelope（reply/intent/...），
// 流式下发时需要把 "reply" 字符串字段的增量实时抽出来推给客户端（其余字段等完整 JSON 再解析）。
// 纯状态机、零依赖：命中第一个顶层形态的 `"reply"\s*:\s*"` 后进入字符串态，处理转义，
// 每个解码字符经 onDelta 输出，直到字符串闭合；之后忽略所有输入。

function createReplyFieldStreamExtractor(onDelta) {
  const KEY = '"reply"';
  let phase = "seek-key"; // seek-key -> seek-colon -> seek-quote -> in-string -> done
  let window = "";
  let escaped = false;
  let unicodeLeft = 0;
  let unicodeBuf = "";
  let pending = "";

  const flush = () => {
    if (pending && typeof onDelta === "function") {
      const out = pending;
      pending = "";
      onDelta(out);
    }
  };

  const push = (chunk) => {
    const text = String(chunk || "");
    for (let i = 0; i < text.length; i += 1) {
      const ch = text[i];
      if (phase === "done") return;
      if (phase === "seek-key") {
        window = (window + ch).slice(-KEY.length);
        if (window === KEY) phase = "seek-colon";
        continue;
      }
      if (phase === "seek-colon") {
        if (ch === ":") phase = "seek-quote";
        else if (!/\s/.test(ch)) { phase = "seek-key"; window = ""; }
        continue;
      }
      if (phase === "seek-quote") {
        if (ch === '"') phase = "in-string";
        else if (!/\s/.test(ch)) { phase = "seek-key"; window = ""; }
        continue;
      }
      // in-string
      if (unicodeLeft > 0) {
        unicodeBuf += ch;
        unicodeLeft -= 1;
        if (unicodeLeft === 0) {
          const code = Number.parseInt(unicodeBuf, 16);
          if (Number.isFinite(code)) pending += String.fromCharCode(code);
          unicodeBuf = "";
        }
        continue;
      }
      if (escaped) {
        escaped = false;
        if (ch === "n") pending += "\n";
        else if (ch === "t") pending += "\t";
        else if (ch === "r") pending += "\r";
        else if (ch === "u") { unicodeLeft = 4; unicodeBuf = ""; }
        else pending += ch; // \" \\ \/ 及其它
        continue;
      }
      if (ch === "\\") { escaped = true; continue; }
      if (ch === '"') { phase = "done"; flush(); return; }
      pending += ch;
    }
    flush();
  };

  return { push };
}

module.exports = { createReplyFieldStreamExtractor };
