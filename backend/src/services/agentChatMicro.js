"use strict";

// /agent/chat 在「practice_card」意图下原生附带一张微元卡（generateMicroCard 自修复闭环）。
// ②阶段3 步骤4b 起：43 题型 cardSpec 生成已退役删除，practice_card 一律走原生微元，cardSpec 恒为 null。
// 微元生成失败时 graceful 返回（无 cardSpec 兜底），客户端按空卡处理。

const { generateAgentChatReply, isExplicitAgentPracticeCardRequest } = require("./mimoText");
const { generateMicroCardReply } = require("./microCardGenerate");

// 并行优化：消息「像练习卡」时，微元生成与意图分类重叠跑（省去串行的意图分类等待，约 -3~5s）。
// 微元用独立 prompt + 自修复、与意图分类无关 → 卡质量不变；预判错(实际非练习卡)则丢弃并行结果，
// 预判漏(实际是练习卡却没并行)则在确认后补跑一次，保证正确。
async function generateAgentChatReplyWithMicro(body = {}) {
  const message = String((body && (body.message || body.userMessage || body.text)) || "");
  const microPromise = isExplicitAgentPracticeCardRequest(message)
    ? generateMicroCardReply(body).catch(() => null)
    : null;
  const decision = await generateAgentChatReply(body);
  if (!decision || decision.intent !== "practice_card") return decision; // 非练习卡：丢弃并行微元
  const micro = await (microPromise || generateMicroCardReply(body).catch(() => null));
  if (micro && micro.card) {
    decision.microCard = micro.card;
    decision.microReport = micro.report;
    decision.microOk = micro.ok;
  }
  decision.cardSpec = null;
  return decision;
}

module.exports = { generateAgentChatReplyWithMicro };
