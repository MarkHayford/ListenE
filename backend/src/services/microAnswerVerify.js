"use strict";

// 微元「答案正确性」定向异步抽验。
//
// 背景（eval/micro-answer-audit.json 两轮 74 卡实证）：真实坏答案全部聚在
// 「语言学判断型答案键」——highlight_span（按词性/条件圈词）、error_correction
// （accept 备选句）、proof_paragraph（逐行改错答案）；机械可查的部分（计数一致、
// 选项归属、word_search 可寻等）确定性校验已覆盖、零真问题。且该实验中 verifier
// 在这三类上的标记精确率为 4/4，误报全部来自这里不审的类型。
//
// 策略：只对含上述风险题型的卡，在生成成功返回后异步补一次 verifier 调用
// （不拦截、不加响应延迟），结果进 metrics（micro.answerAudit）+ warn 日志。
// 标记率异常升高时回头修 prompt/校验，而不是串行挡在用户面前。

const { logger } = require("./logger");
const { recordMicroAnswerAudit } = require("./metrics");

// 风险题型：答案键正确性依赖语言学判断、无法确定性校验的那几类。
const ANSWER_RISK_TYPES = Object.freeze(["highlight_span", "error_correction", "proof_paragraph"]);
const RISK_SET = new Set(ANSWER_RISK_TYPES);

function riskNodes(card) {
  const nodes = card && Array.isArray(card.nodes) ? card.nodes : [];
  return nodes
    .map((node, index) => ({ index, node }))
    .filter(({ node }) => RISK_SET.has(String((node && node.type) || "").trim().toLowerCase()));
}

// verifier 提示词：只判答案键客观正误，宁漏勿误报（与审计实验同口径）。
function verifierMessages(userNeed, picked) {
  return [
    {
      role: "system",
      content: [
        "你是严格的英语教研审题员。给你一张 AI 生成的练习卡里的若干节点（JSON）与用户需求。",
        "逐节点检查其标准答案（answer/answers/accept/lines[].answer 等）是否客观正确：",
        "语法、词汇、拼写、与题干条件自洽（如“选出所有动词”的 answers 必须都是动词且无遗漏；accept 里的备选句必须同样正确）。",
        "不评价风格、难度、教学取向；拿不准时 ok=true（宁漏勿误报）。",
        '只输出严格 JSON：{"verdicts":[{"index":节点下标,"type":"节点type","ok":true|false,"reason":"不超过40字"}]}，',
        "verdicts 必须覆盖给出的每个节点、index 原样返回；不要任何多余文字或代码围栏。"
      ].join("\n")
    },
    {
      role: "user",
      content: JSON.stringify({
        userNeed: String(userNeed || ""),
        nodes: picked.map(({ index, node }) => ({ index, ...node }))
      })
    }
  ];
}

// 可等待的审计核心（单测/脚本用）：无风险节点时返回 { audited:false }。
async function auditMicroCardAnswers(card, userNeed, { callModel } = {}) {
  const picked = riskNodes(card);
  if (!picked.length) return { audited: false, flagged: [] };
  const call = callModel || ((messages) => require("./mimoCore").callMimoText(messages, { temperature: 0, maxTokens: 2048 }));
  const raw = await call(verifierMessages(userNeed, picked));
  const verdicts = raw && Array.isArray(raw.verdicts) ? raw.verdicts : [];
  const flagged = verdicts.filter((v) => v && v.ok === false);
  return { audited: true, flagged, verdicts };
}

// 线上入口：fire-and-forget。任何失败只打点/打日志，绝不影响主链路。
function auditMicroCardAnswersAsync(card, userNeed, { callModel } = {}) {
  const picked = riskNodes(card);
  if (!picked.length) return false;
  setImmediate(() => {
    auditMicroCardAnswers(card, userNeed, { callModel })
      .then(({ flagged }) => {
        recordMicroAnswerAudit({ flaggedNodes: flagged.length });
        if (flagged.length) {
          logger.warn("micro answer audit flagged", {
            userNeed: String(userNeed || "").slice(0, 120),
            flagged: flagged.map((f) => ({ index: f.index, type: f.type, reason: String(f.reason || "").slice(0, 80) }))
          });
        }
      })
      .catch((error) => {
        recordMicroAnswerAudit({ error: true });
        logger.warn("micro answer audit failed", { error: String((error && error.message) || error) });
      });
  });
  return true;
}

module.exports = { ANSWER_RISK_TYPES, riskNodes, verifierMessages, auditMicroCardAnswers, auditMicroCardAnswersAsync };
