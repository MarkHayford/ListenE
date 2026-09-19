// 关键端点的输入长度白名单：在把自由文本转给 MiMo/DB 前先做上限校验，收窄「超长输入」的
// 放大式开销与滥用面。纯函数；超限抛出可被控制器映射为 400 的错误（message 含 too long）。

const INPUT_LIMITS = {
  translateText: 8000, // 翻译原文
  planGoal: 2000, // 学习计划目标描述
  translateContext: 4000 // 翻译上下文（预留，当前 translateText 未使用 context）
};

// 校验字符串长度上限：超限抛错（400 类）；返回去除首尾空白后的字符串，便于直接使用。
function ensureWithinLimit(value, max, field = "input") {
  const text = String(value == null ? "" : value);
  if (text.length > max) {
    throw new Error(`${field} too long (max ${max} chars)`);
  }
  return text;
}

module.exports = { INPUT_LIMITS, ensureWithinLimit };
