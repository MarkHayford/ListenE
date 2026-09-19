"use strict";

/**
 * 微元「选型准确率」评测（真实模型回归用）
 *
 * 作用：用一组带标注的「用户需求 → 期望题型集」跑真实 generateMicroCard，
 *   看模型为每个知识点选出的「主导可判分题型」是否落在期望集合里，
 *   输出主导命中率 / 宽松命中率(期望题型出现即可) / 禁用题型触发率 + 混淆表，
 *   作为每次改《选型决策指南》/字段说明后的量化指南针。
 *
 * 打分口径：
 *   - 主导题型 = 卡片里出现最多的可判分微元类型（并列取先出现者）。
 *   - primaryHit  = 主导题型 ∈ 该用例 expect（严格：选对了最合适的专用题型）。
 *   - softHit     = expect 里任一题型在卡片里出现过（宽松：至少想到了对的题型）。
 *   - forbidHit   = 出现了该用例 forbid 的题型（明显选错，如该用专用题型却退回 choice）。
 *
 * 用法：
 *   MIMO_API_KEY=xxx node eval/microSelectionEval.js
 *   node eval/microSelectionEval.js --cases eval/microSelectionCases.json --out eval/micro-selection-report.json
 *   node eval/microSelectionEval.js --concurrency 6 --attempts 2 --limit 10 --min-rate 0.8
 *
 * 退出码：主导命中率 ≥ min-rate 且无运行错误 = 0；低于阈值 = 1；配置/读取错误 = 2。
 */

const { GRADABLE_MICRO_NODE_TYPES } = require("../src/contract/microCardContract");

// 「主导题型」评估的候选集合 = 可判分题型 ∪ 其它「一张卡的核心交互」题型（自带流程/展示记忆类），
// 排除 text/passage/audio/reveal/table/timeline/progress/chart 等纯脚手架/装饰微元。
const EXTRA_PRIMARY_TYPES = ["flashcard", "pairs_memory", "roleplay_turn", "speak_score", "ai_hint", "timed_challenge", "writing"];
const PRIMARY_SET = new Set([...GRADABLE_MICRO_NODE_TYPES, ...EXTRA_PRIMARY_TYPES]);

// —— 纯函数打分（供离线单测复用，不触网）——

function primaryTypesOf(card) {
  const nodes = card && Array.isArray(card.nodes) ? card.nodes : [];
  return nodes
    .map((n) => String((n && n.type) || "").trim().toLowerCase())
    .filter((t) => PRIMARY_SET.has(t));
}

// 主导题型：出现次数最多的核心交互微元；并列取先出现者；只有脚手架/装饰 → null。
function dominantPrimaryType(card) {
  const g = primaryTypesOf(card);
  if (!g.length) return null;
  const counts = new Map();
  for (const t of g) counts.set(t, (counts.get(t) || 0) + 1);
  const seen = [...new Set(g)];
  let best = seen[0];
  for (const t of seen) if (counts.get(t) > counts.get(best)) best = t;
  return best;
}

function scoreCase(c, card) {
  const present = primaryTypesOf(card);
  const presentSet = new Set(present);
  const primary = dominantPrimaryType(card);
  const expect = (Array.isArray(c.expect) ? c.expect : []).map((t) => String(t).toLowerCase());
  const forbid = (Array.isArray(c.forbid) ? c.forbid : []).map((t) => String(t).toLowerCase());
  return {
    primary,
    present,
    primaryHit: primary != null && expect.includes(primary),
    softHit: expect.some((t) => presentSet.has(t)),
    forbidHit: forbid.some((t) => presentSet.has(t))
  };
}

function summarize(rows) {
  const total = rows.length || 1;
  const primaryHits = rows.filter((r) => r.score && r.score.primaryHit).length;
  const softHits = rows.filter((r) => r.score && r.score.softHit).length;
  const forbidHits = rows.filter((r) => r.score && r.score.forbidHit).length;
  const errors = rows.filter((r) => r.error).length;
  const confusion = rows
    .filter((r) => r.score && !r.score.primaryHit)
    .map((r) => ({ id: r.id, message: r.message, expect: r.expect, got: r.score.primary, present: r.score.present, error: r.error || null }));
  return {
    total: rows.length,
    primaryHits,
    softHits,
    forbidHits,
    errors,
    primaryRate: Number((primaryHits / total).toFixed(4)),
    softRate: Number((softHits / total).toFixed(4)),
    forbidRate: Number((forbidHits / total).toFixed(4)),
    confusion
  };
}

// —— 并发池 ——
async function runPool(items, worker, concurrency) {
  const results = new Array(items.length);
  let next = 0;
  const size = Math.max(1, Math.min(concurrency || 1, items.length || 1));
  await Promise.all(
    Array.from({ length: size }, async () => {
      while (true) {
        const i = next++;
        if (i >= items.length) break;
        results[i] = await worker(items[i], i);
      }
    })
  );
  return results;
}

module.exports = { primaryTypesOf, dominantPrimaryType, scoreCase, summarize, runPool };

// —— 命令行入口（require 时不执行，仅直接运行时触网）——
if (require.main === module) {
  const fs = require("fs");
  const path = require("path");

  const HELP = `微元选型准确率评测
用法:
  MIMO_API_KEY=xxx node eval/microSelectionEval.js [--cases <file>] [--out <file>] [--concurrency N] [--attempts N] [--limit N] [--min-rate 0.8]
环境变量:
  MIMO_API_KEY  必填，调用真实模型
`;

  function parseArgs(argv) {
    const args = {
      cases: path.join(__dirname, "microSelectionCases.json"),
      out: path.join(__dirname, "micro-selection-report.json"),
      concurrency: 4,
      attempts: 2,
      limit: 0,
      minRate: 0.75,
      help: false
    };
    for (let i = 2; i < argv.length; i += 1) {
      const a = argv[i];
      if (a === "--cases") args.cases = argv[(i += 1)];
      else if (a === "--out") args.out = argv[(i += 1)];
      else if (a === "--concurrency") args.concurrency = Math.max(1, Number(argv[(i += 1)]) || 4);
      else if (a === "--attempts") args.attempts = Math.max(1, Number(argv[(i += 1)]) || 2);
      else if (a === "--limit") args.limit = Math.max(0, Number(argv[(i += 1)]) || 0);
      else if (a === "--min-rate") args.minRate = Number(argv[(i += 1)]);
      else if (a === "--help" || a === "-h") args.help = true;
    }
    return args;
  }

  async function main() {
    const args = parseArgs(process.argv);
    if (args.help) {
      console.log(HELP);
      return 0;
    }
    const { settings } = require("../src/config");
    const { generateMicroCard } = require("../src/services/microCardGenerate");
    const { callMimoText } = require("../src/services/mimoCore");

    if (!settings.mimoApiKey) {
      console.error("✗ 未设置 MIMO_API_KEY，无法调用真实模型。示例：MIMO_API_KEY=xxx node eval/microSelectionEval.js");
      return 2;
    }
    let cases;
    try {
      cases = JSON.parse(fs.readFileSync(args.cases, "utf8"));
    } catch (e) {
      console.error(`✗ 读取用例失败 ${args.cases}: ${(e && e.message) || e}`);
      return 2;
    }
    if (!Array.isArray(cases) || cases.length === 0) {
      console.error("✗ 用例为空");
      return 2;
    }
    if (args.limit > 0) cases = cases.slice(0, args.limit);

    console.log(`模型：${settings.mimoTextModel} @ ${settings.mimoBaseUrl}`);
    console.log(`用例：${cases.length} 条；并发：${args.concurrency}；每例重试上限：${args.attempts}`);
    console.log("-".repeat(72));

    const rows = await runPool(
      cases,
      async (c, i) => {
        const id = c.id || `case-${i + 1}`;
        const row = { id, message: c.message, expect: (c.expect || []).map((t) => String(t).toLowerCase()) };
        try {
          const out = await generateMicroCard(String(c.message || ""), {
            callModel: (messages) => callMimoText(messages),
            maxAttempts: args.attempts
          });
          row.card = out.card || null;
          row.score = scoreCase(c, out.card);
        } catch (e) {
          row.error = String((e && e.message) || e);
          row.score = scoreCase(c, null);
        }
        const mark = row.error ? "!" : row.score.primaryHit ? "✓" : row.score.softHit ? "~" : "✗";
        console.log(
          `${mark} ${id}  期望[${row.expect.join("/")}]  得到[${row.score.primary || "无判分"}]` +
            (row.score.forbidHit ? "  ⚠禁用" : "") +
            (row.error ? `  运行错误：${row.error}` : "")
        );
        return row;
      },
      args.concurrency
    );

    const sum = summarize(rows);
    console.log("-".repeat(72));
    console.log(`主导命中率 primaryRate：${sum.primaryHits}/${sum.total} = ${(sum.primaryRate * 100).toFixed(1)}%`);
    console.log(`宽松命中率 softRate    ：${sum.softHits}/${sum.total} = ${(sum.softRate * 100).toFixed(1)}%`);
    console.log(`禁用触发率 forbidRate  ：${sum.forbidHits}/${sum.total} = ${(sum.forbidRate * 100).toFixed(1)}%`);
    if (sum.errors) console.log(`运行错误：${sum.errors} 例`);
    if (sum.confusion.length) {
      console.log("未命中主导题型（混淆）:");
      sum.confusion.forEach((x) => console.log(`  - ${x.id}: 期望[${(x.expect || []).join("/")}] 得到[${x.got || "无判分"}]${x.error ? ` (错误:${x.error})` : ""}`));
    }

    try {
      fs.writeFileSync(args.out, JSON.stringify({ model: settings.mimoTextModel, summary: { ...sum, confusion: undefined }, cases: rows }, null, 2));
      console.log(`报告已写入 ${args.out}`);
    } catch (e) {
      console.error(`（报告写入失败：${(e && e.message) || e}）`);
    }
    return Number.isFinite(args.minRate) && sum.primaryRate < args.minRate ? 1 : 0;
  }

  main()
    .then((code) => process.exit(code))
    .catch((e) => {
      console.error(e);
      process.exit(2);
    });
}
