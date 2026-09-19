"use strict";

/**
 * ListenE Agent 离线评测脚本（真实模型回归用）
 *
 * 作用：用一组固定的真实用户输入跑真实后端 generateAgentChatReply，
 *   做确定性校验（intent / 组件 / 数量 / 禁用词 / 是否降级）＋ 可选 LLM 裁判打分，
 *   输出每条结果和总体通过率，作为每次改提示词 / schema / 意图逻辑后的回归指南针。
 *
 * 这是你目前最缺的“指南针”：改之前先跑一次拿到基线通过率，改完再跑一次对比，
 *   就能立刻看出是真变好还是把别的用例弄坏了。
 *
 * 用法：
 *   MIMO_API_KEY=xxx node eval/agentEval.js
 *   node eval/agentEval.js --cases eval/agentEvalCases.json
 *   EVAL_JUDGE=1 MIMO_API_KEY=xxx node eval/agentEval.js     # 额外启用 LLM 裁判
 *   node eval/agentEval.js --out eval/report.json
 *
 * 退出码：全部通过=0；有失败=1；运行/配置错误=2
 */

const fs = require("fs");
const path = require("path");

const HELP = `ListenE Agent 评测脚本
用法:
  MIMO_API_KEY=xxx node eval/agentEval.js [--cases <file>] [--out <file>] [--judge]
环境变量:
  MIMO_API_KEY     必填，调用真实模型
  MIMO_BASE_URL    模型 base url（默认读 config）
  MIMO_TEXT_MODEL  文本模型名（默认读 config）
  EVAL_JUDGE=1     等价于 --judge，启用 LLM 裁判打分
`;

function parseArgs(argv) {
  const args = {
    cases: path.join(__dirname, "agentEvalCases.json"),
    out: path.join(__dirname, "agent-eval-report.json"),
    judge: false,
    help: false
  };
  for (let i = 2; i < argv.length; i += 1) {
    const a = argv[i];
    if (a === "--cases") args.cases = argv[(i += 1)];
    else if (a === "--out") args.out = argv[(i += 1)];
    else if (a === "--judge") args.judge = true;
    else if (a === "--help" || a === "-h") args.help = true;
  }
  if (process.env.EVAL_JUDGE === "1") args.judge = true;
  return args;
}

const { settings } = require("../src/config");
const { generateAgentChatReply, __test } = require("../src/services/mimoText");
const { callMimoChatRaw, extractJsonFromContent } = require("../src/services/mimoAgentMedia");

const assessAgentCardQuality = __test && __test.assessAgentCardQuality;
const getAgentCardTelemetry = __test && __test.getAgentCardTelemetry;
const resetAgentCardTelemetry = __test && __test.resetAgentCardTelemetry;

const AUDIO_RE = /hear|listen|play|audio|听|播放|收听/i;
// 按组件类型取对应的计数字段（归一化后组件常带空的 items:[]，不能简单取第一个数组）
const TYPE_COUNT_KEY = { question_set: "questions", minimal_pair: "pairs", word_family: "tokens", ordering: "items", rubric: "criteria", writing_outline: "steps" };

function componentCount(component) {
  if (!component) return 0;
  const key = TYPE_COUNT_KEY[String(component.type || "")];
  if (key) return Array.isArray(component[key]) ? component[key].length : 0;
  for (const k of ["items", "examples", "options", "pairs", "tokens", "steps", "criteria"]) {
    if (Array.isArray(component[k]) && component[k].length) return component[k].length;
  }
  return 0;
}

function componentTypes(cardSpec) {
  return cardSpec && Array.isArray(cardSpec.components)
    ? cardSpec.components.map((c) => String((c && c.type) || ""))
    : [];
}

function checkCase(c, result, telemetry) {
  const reasons = [];
  const notes = [];
  const types = componentTypes(result.cardSpec);

  if (c.expectIntent && result.intent !== c.expectIntent) {
    reasons.push(`intent 期望 ${c.expectIntent}，实际 ${result.intent}`);
  }
  if (c.expectCardSpecNull && result.cardSpec) {
    reasons.push("期望无 cardSpec，但生成了卡片");
  }
  if (Array.isArray(c.mustHaveComponents)) {
    for (const t of c.mustHaveComponents) {
      if (!types.includes(t)) reasons.push(`缺少必需组件 ${t}（实际组件: ${types.join(", ") || "无"}）`);
    }
  }
  if (Array.isArray(c.forbidComponents)) {
    for (const t of c.forbidComponents) {
      if (types.includes(t)) reasons.push(`出现禁用组件 ${t}`);
    }
  }
  if (c.minCounts && result.cardSpec) {
    for (const [t, n] of Object.entries(c.minCounts)) {
      const comp = (result.cardSpec.components || []).find((x) => String((x && x.type) || "") === t);
      const cnt = comp ? componentCount(comp) : 0;
      if (cnt < n) reasons.push(`${t} 数量 ${cnt} < 期望 ${n}`);
    }
  }
  if (c.forbidAudioWords) {
    const blob = `${result.reply || ""} ${JSON.stringify(result.cardSpec || {})}`;
    if (AUDIO_RE.test(blob)) reasons.push("出现 听/播放/audio 等词（用户要求无音频）");
  }
  if (c.expectOutputFile && !(Array.isArray(result.outputFiles) && result.outputFiles.length > 0)) {
    reasons.push("期望输出文件，但 outputFiles 为空");
  }
  // 最终卡片质量是权威信号：检查真正交付给用户的卡片（中途若降级但已被后处理修复，则不应算失败）
  if (result.cardSpec && typeof assessAgentCardQuality === "function") {
    try {
      const q = assessAgentCardQuality({
        message: c.message,
        currentRecordSummary: c.currentRecordSummary || null,
        cardSpec: result.cardSpec
      });
      if (q && Array.isArray(q.warnings) && q.warnings.length) {
        reasons.push(`最终卡片质量不合格：${q.warnings.join("; ")}`);
      }
    } catch (_) {
      // 评测脚本不因质检异常中断
    }
  }

  if (telemetry && telemetry.fallbackCount > 0) notes.push("管线中途发生过降级（以最终卡片质量为准）");
  if (telemetry && telemetry.invalidCardCount > 0) notes.push("卡片初次非法，走了修复链路");

  return { pass: reasons.length === 0, reasons, notes };
}

async function runJudge(c, result) {
  const prompt = [
    "你是英语学习 App 的练习卡片质检员。基于合格标准评估这次 Agent 回复是否合格。",
    `用户输入：${c.message}`,
    `合格标准：${c.rubric || "回复正确；卡片组件与用户需求精确匹配；内容准确；不含用户没要求的多余模块"}`,
    `Agent 结果：${JSON.stringify({
      intent: result.intent,
      reply: result.reply,
      cardSpec: result.cardSpec,
      outputFiles: (result.outputFiles || []).map((f) => f && f.name)
    })}`,
    '只返回 JSON：{"score": 0到1的小数, "verdict": "pass|fail", "reasons": ["简短中文原因"]}'
  ].join("\n");
  try {
    const resp = await callMimoChatRaw(
      [
        { role: "system", content: "你只返回一个 JSON 对象，不要多余文字。" },
        { role: "user", content: prompt }
      ],
      { model: settings.mimoTextModel, temperature: 0, maxTokens: 500, json: true }
    );
    return extractJsonFromContent(resp.choices && resp.choices[0] && resp.choices[0].message && resp.choices[0].message.content);
  } catch (e) {
    return { score: null, verdict: "error", reasons: [String((e && e.message) || e)] };
  }
}

async function main() {
  const args = parseArgs(process.argv);
  if (args.help) {
    console.log(HELP);
    return 0;
  }
  if (!settings.mimoApiKey) {
    console.error("✗ 未设置 MIMO_API_KEY，无法调用真实模型。示例：MIMO_API_KEY=xxx node eval/agentEval.js");
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

  console.log(`模型：${settings.mimoTextModel} @ ${settings.mimoBaseUrl}`);
  console.log(`用例：${cases.length} 条；LLM 裁判：${args.judge ? "开" : "关"}`);
  console.log("-".repeat(64));

  const report = [];
  let passed = 0;

  for (let i = 0; i < cases.length; i += 1) {
    const c = cases[i];
    const id = c.id || `case-${i + 1}`;
    const row = { id, message: c.message };
    try {
      if (typeof resetAgentCardTelemetry === "function") resetAgentCardTelemetry();
      const result = await generateAgentChatReply({
        message: c.message,
        workspaceTitle: c.workspaceTitle || "",
        workspaceNeed: c.workspaceNeed || "",
        currentRecordSummary: c.currentRecordSummary || null,
        recentMessages: c.recentMessages || []
      });
      const telemetry = typeof getAgentCardTelemetry === "function" ? getAgentCardTelemetry() : null;
      const det = checkCase(c, result, telemetry);

      row.intent = result.intent;
      row.deterministic = det;
      row.telemetry = telemetry
        ? { fallback: telemetry.fallbackCount, invalid: telemetry.invalidCardCount, qualityWarn: telemetry.qualityWarningCount }
        : null;
      if (args.judge) row.judge = await runJudge(c, result);

      const ok = det.pass && (!row.judge || row.judge.verdict === "pass");
      row.pass = ok;
      if (ok) passed += 1;

      console.log(`${ok ? "✓" : "✗"} ${id}  [${result.intent}]  ${c.message}`);
      det.reasons.forEach((r) => console.log(`     - ${r}`));
      det.notes.forEach((n) => console.log(`     · ${n}`));
      if (row.judge && row.judge.verdict !== "pass") {
        console.log(`     - 裁判(${row.judge.score}): ${(row.judge.reasons || []).join("; ")}`);
      }
    } catch (e) {
      row.pass = false;
      row.error = String((e && e.message) || e);
      console.log(`✗ ${id}  运行错误：${row.error}`);
    }
    report.push(row);
  }

  console.log("-".repeat(64));
  const rate = ((passed / cases.length) * 100).toFixed(1);
  console.log(`通过率：${passed}/${cases.length} = ${rate}%`);

  try {
    fs.writeFileSync(
      args.out,
      JSON.stringify({ model: settings.mimoTextModel, passed, total: cases.length, rate, cases: report }, null, 2)
    );
    console.log(`报告已写入 ${args.out}`);
  } catch (e) {
    console.error(`（报告写入失败：${(e && e.message) || e}）`);
  }

  return passed === cases.length ? 0 : 1;
}

main()
  .then((code) => process.exit(code))
  .catch((e) => {
    console.error(e);
    process.exit(2);
  });
