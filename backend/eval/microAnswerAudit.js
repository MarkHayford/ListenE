"use strict";

/**
 * 微元「答案正确性」审计（verifier 抽验是否值得上线的决策实验）
 *
 * 作用：用选型 eval 的多维需求集跑真实 generateMicroCard，再对每张卡的可判分节点
 *   做一次独立 verifier 模型调用（只判「标准答案是否客观正确」，不评风格难度），
 *   输出：坏答案节点/卡片率、verifier 标记明细（供人工终审）、额外成本比。
 *
 * 决策口径（建议）：
 *   - 人工终审后的真实坏答案率 <1%（卡级）→ 不值得全量 verifier；
 *   - 1%~5% → 只对高风险题型抽样 verifier；
 *   - >5% → 值得全量 verifier 或加强生成 prompt。
 *
 * 用法：
 *   MIMO_API_KEY=xxx node eval/microAnswerAudit.js
 *   node eval/microAnswerAudit.js --cases eval/microSelectionCases.json --out eval/micro-answer-audit.json --concurrency 4 --limit 0
 */

const fs = require("fs");
const path = require("path");
const { GRADABLE_MICRO_NODE_TYPES } = require("../src/contract/microCardContract");
const { runPool } = require("./microSelectionEval");

const GRADABLE_SET = new Set(GRADABLE_MICRO_NODE_TYPES);

function gradableNodes(card) {
  const nodes = card && Array.isArray(card.nodes) ? card.nodes : [];
  return nodes
    .map((node, index) => ({ index, node }))
    .filter(({ node }) => GRADABLE_SET.has(String((node && node.type) || "").trim().toLowerCase()));
}

// verifier：独立第二遍模型调用，只判答案键客观正误。
function verifierMessages(need, picked) {
  return [
    {
      role: "system",
      content: [
        "你是严格的英语教研审题员。给你一张 AI 生成的练习卡的可判分节点（JSON）与用户需求。",
        "逐节点检查其标准答案（answer/answers/correct/blanks[].answer/statements[].answer/lines[].answer/pairs 等）是否客观正确：",
        "语法、词汇、拼写、事实、与题干自洽（如 answer 是否真是唯一/最佳正解、干扰项是否误成正解）。",
        "不评价风格、难度、教学取向；拿不准时 ok=true（宁漏勿误报）。",
        '只输出严格 JSON：{"verdicts":[{"index":节点下标,"type":"节点type","ok":true|false,"reason":"不超过40字","correctAnswer":"仅 ok=false 时给正确答案"}]}，',
        "verdicts 必须覆盖给出的每个节点、index 原样返回；不要任何多余文字或代码围栏。"
      ].join("\n")
    },
    {
      role: "user",
      content: JSON.stringify({
        userNeed: String(need || ""),
        nodes: picked.map(({ index, node }) => ({ index, ...node }))
      })
    }
  ];
}

async function main() {
  const args = {
    cases: path.join(__dirname, "microSelectionCases.json"),
    out: path.join(__dirname, "micro-answer-audit.json"),
    concurrency: 4,
    attempts: 2,
    limit: 0
  };
  for (let i = 2; i < process.argv.length; i += 1) {
    const a = process.argv[i];
    if (a === "--cases") args.cases = process.argv[(i += 1)];
    else if (a === "--out") args.out = process.argv[(i += 1)];
    else if (a === "--concurrency") args.concurrency = Math.max(1, Number(process.argv[(i += 1)]) || 4);
    else if (a === "--attempts") args.attempts = Math.max(1, Number(process.argv[(i += 1)]) || 2);
    else if (a === "--limit") args.limit = Math.max(0, Number(process.argv[(i += 1)]) || 0);
  }

  const { settings } = require("../src/config");
  const { generateMicroCard } = require("../src/services/microCardGenerate");
  const { callMimoText } = require("../src/services/mimoCore");
  if (!settings.mimoApiKey) {
    console.error("✗ 未设置 MIMO_API_KEY");
    process.exit(2);
  }

  let cases = JSON.parse(fs.readFileSync(args.cases, "utf8"));
  if (args.limit > 0) cases = cases.slice(0, args.limit);
  console.log(`模型：${settings.mimoTextModel}；用例：${cases.length}；并发：${args.concurrency}`);
  console.log("-".repeat(72));

  let genCalls = 0;
  let verifyCalls = 0;
  const rows = await runPool(
    cases,
    async (c, i) => {
      const id = c.id || `case-${i + 1}`;
      const row = { id, message: c.message };
      try {
        const t0 = Date.now();
        const out = await generateMicroCard(String(c.message || ""), {
          callModel: (messages) => callMimoText(messages, { maxTokens: 4096 }),
          maxAttempts: args.attempts
        });
        row.genMs = Date.now() - t0;
        genCalls += out.attempts;
        row.card = out.card || null;
        row.microOk = out.ok;
        const picked = gradableNodes(out.card);
        row.gradableCount = picked.length;
        if (!picked.length) {
          row.verdicts = [];
          console.log(`- ${id}  无可判分节点`);
          return row;
        }
        const t1 = Date.now();
        const verdictRaw = await callMimoText(verifierMessages(c.message, picked), { temperature: 0, maxTokens: 2048 });
        row.verifyMs = Date.now() - t1;
        verifyCalls += 1;
        const verdicts = verdictRaw && Array.isArray(verdictRaw.verdicts) ? verdictRaw.verdicts : [];
        row.verdicts = verdicts;
        const flagged = verdicts.filter((v) => v && v.ok === false);
        row.flagged = flagged;
        console.log(
          `${flagged.length ? "⚠" : "✓"} ${id}  节点${picked.length}个` +
            (flagged.length ? `  被标记${flagged.length}个: ${flagged.map((f) => `[${f.type}] ${f.reason}`).join("；")}` : "")
        );
      } catch (e) {
        row.error = String((e && e.message) || e);
        console.log(`! ${id}  运行错误：${row.error}`);
      }
      return row;
    },
    args.concurrency
  );

  const audited = rows.filter((r) => !r.error && (r.gradableCount || 0) > 0);
  const flaggedRows = audited.filter((r) => (r.flagged || []).length > 0);
  const totalNodes = audited.reduce((n, r) => n + r.gradableCount, 0);
  const flaggedNodes = audited.reduce((n, r) => n + (r.flagged || []).length, 0);
  const summary = {
    cases: rows.length,
    audited: audited.length,
    errors: rows.filter((r) => r.error).length,
    totalGradableNodes: totalNodes,
    flaggedNodes,
    flaggedCards: flaggedRows.length,
    nodeFlagRate: totalNodes ? Number((flaggedNodes / totalNodes).toFixed(4)) : 0,
    cardFlagRate: audited.length ? Number((flaggedRows.length / audited.length).toFixed(4)) : 0,
    genCalls,
    verifyCalls,
    verifyCostRatio: genCalls ? Number((verifyCalls / genCalls).toFixed(2)) : 0,
    avgGenMs: audited.length ? Math.round(audited.reduce((n, r) => n + (r.genMs || 0), 0) / audited.length) : 0,
    avgVerifyMs: audited.length ? Math.round(audited.reduce((n, r) => n + (r.verifyMs || 0), 0) / audited.length) : 0
  };
  console.log("-".repeat(72));
  console.log(`审计卡片：${summary.audited}；可判分节点：${summary.totalGradableNodes}`);
  console.log(`verifier 标记：节点 ${summary.flaggedNodes}/${summary.totalGradableNodes} = ${(summary.nodeFlagRate * 100).toFixed(1)}%；卡片 ${summary.flaggedCards}/${summary.audited} = ${(summary.cardFlagRate * 100).toFixed(1)}%`);
  console.log(`成本：生成调用 ${summary.genCalls} 次 + 审计调用 ${summary.verifyCalls} 次（+${(summary.verifyCostRatio * 100).toFixed(0)}%）；均耗时 生成 ${summary.avgGenMs}ms / 审计 ${summary.avgVerifyMs}ms`);
  fs.writeFileSync(args.out, JSON.stringify({ model: settings.mimoTextModel, summary, rows }, null, 2));
  console.log(`报告已写入 ${args.out}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
