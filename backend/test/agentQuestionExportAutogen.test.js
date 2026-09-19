"use strict";
// 单轮「出题即导出」增强 + 「选择题」导出过滤修复的确定性回归测试。
// 纯函数 / 真实依赖，不调用模型，可纳入 npm test。
const assert = require("assert");
const { __test } = require("../src/services/mimoText");
const { createAgentOutputExport } = require("../src/services/agentOutputExport");

const {
  deriveAgentQuestionGenerationMessage,
  isFreshQuestionGenerationExportRequest,
  normalizeAgentOutputFiles
} = __test;

// ---- 1. deriveAgentQuestionGenerationMessage：剥离导出措辞，保留出题部分 ----
{
  const g1 = deriveAgentQuestionGenerationMessage("出3道关于environment的英语选择题并导出成docx文档");
  assert.ok(/选择题/.test(g1), "保留出题意图");
  assert.ok(!/导出|docx|文档/.test(g1), `应剥离导出措辞，实际: ${g1}`);

  const g2 = deriveAgentQuestionGenerationMessage("给我5道四级选择题，然后保存为word");
  assert.ok(/选择题/.test(g2) && !/保存|word/.test(g2), `中文保存为word 应剥离: ${g2}`);

  const g3 = deriveAgentQuestionGenerationMessage("generate 4 environment quiz questions and export as docx");
  assert.ok(/quiz|questions/.test(g3) && !/export|docx/i.test(g3), `英文导出应剥离: ${g3}`);
}

// ---- 2. isFreshQuestionGenerationExportRequest：仅“全新出题+导出”为真 ----
{
  assert.strictEqual(isFreshQuestionGenerationExportRequest("出3道关于environment的英语选择题并导出成docx文档"), true, "单轮出题+导出 应为 true");
  assert.strictEqual(isFreshQuestionGenerationExportRequest("给我5道四级选择题，然后保存为word"), true, "中文出题+保存 应为 true");
  assert.strictEqual(isFreshQuestionGenerationExportRequest("generate 4 environment quiz questions and export as docx"), true, "英文出题+导出 应为 true");
  // 引用历史 / 纯导出 / 纯出题：均不应触发新生成
  assert.strictEqual(isFreshQuestionGenerationExportRequest("把刚才这些题目导出成docx"), false, "引用刚才 走历史导出");
  assert.strictEqual(isFreshQuestionGenerationExportRequest("把这套题导出docx"), false, "引用这套 走历史导出");
  assert.strictEqual(isFreshQuestionGenerationExportRequest("导出题目"), false, "纯导出无生成意图");
  assert.strictEqual(isFreshQuestionGenerationExportRequest("出三道题"), false, "纯出题非导出请求");
}

// ---- 3. 「选择题」导出过滤修复：MCQ 题卡 + 含“选择题”的导出请求应能产出文件 ----
{
  const questionSetCard = {
    title: "练习题组",
    components: [
      {
        type: "question_set",
        questions: [
          { questionText: "What is renewable energy?", options: ["Replenishable energy", "Fossil fuels", "One-time energy", "Nuclear only"], correctAnswer: 0 },
          { questionText: "Which action reduces waste?", options: ["Recycling", "Burning", "Dumping", "Ignoring"], correctAnswer: 0 }
        ]
      }
    ]
  };
  const files = normalizeAgentOutputFiles([], "把这些选择题导出成docx文档", "已生成。", [], null, questionSetCard);
  assert.strictEqual(files.length, 1, "含“选择题”的请求应能从题卡导出文件（修复前会被误拦为空）");
  assert.strictEqual(files[0].format, "docx", "默认 docx");
  const c = String(files[0].content || "");
  assert.ok(/(^|\n)1\.\s/.test(c) && /(^|\n)A\.\s/.test(c) && /Correct answer:/i.test(c), "导出含题干/选项/答案");
}

// ---- 4. agentExportTextMatchesRequest：词义选择仍生效，选择题不再误拦 ----
{
  const api = createAgentOutputExport({
    normalizeAgentCardSpec: (x) => x,
    normalizeAgentStringArray: (x) => (Array.isArray(x) ? x : []),
    stripAgentQuestionOptionLabel: (x) => x,
    isStrictRegisterOnlyAgentCardRequest: () => false
  });
  // “选择题” MCQ：内容不含“选择/choice”也应放行（本次修复点）
  assert.strictEqual(api.agentExportTextMatchesRequest("Questions:\n1. What is AI?\nA. ...\nCorrect answer: A", "导出选择题为docx"), true, "选择题 MCQ 不应被 choice 过滤误拦");
  // “含义选择/词义选择”仍按词义过滤：内容无相关标记时应被过滤（确认未误删词义选择过滤）
  assert.strictEqual(api.agentExportTextMatchesRequest("Questions:\n1. What is AI?\nA. ...\nCorrect answer: A", "导出含义选择练习为docx"), false, "含义选择 与无关内容应被过滤");
  // “含义选择”内容确含‘含义’时放行
  assert.strictEqual(api.agentExportTextMatchesRequest("含义选择：\n1. bank 的含义\nA. 银行\n答案: A", "导出含义选择练习为docx"), true, "含义选择 内容含‘含义’应放行");
}

// ---- 5. 单轮“生成任意练习 + 导出”：放宽检测器 + 序列化器覆盖非选择题类型 ----
{
  const { isFreshPracticeGenerationExportRequest, serializeAgentCardSpecToExportText } = __test;

  // 放宽版检测器：覆盖各练习类型的“生成+导出”，排除纯讲解 / 历史引用
  assert.strictEqual(isFreshPracticeGenerationExportRequest("生成一张关于travel的英语词汇卡并导出docx"), true, "词汇卡+导出 应触发");
  assert.strictEqual(isFreshPracticeGenerationExportRequest("给我一个英语写作提纲卡并导出docx文档"), true, "写作提纲+导出 应触发");
  assert.strictEqual(isFreshPracticeGenerationExportRequest("出3道选择题并导出docx"), true, "选择题+导出 应触发");
  assert.strictEqual(isFreshPracticeGenerationExportRequest("讲一下现在完成时的用法"), false, "纯讲解不触发");
  assert.strictEqual(isFreshPracticeGenerationExportRequest("导出刚才的卡片"), false, "引用历史不触发");

  // 序列化器 + 通用导出：非选择题类型都能产出文件（不依赖模型）
  const cards = {
    vocabulary: { title: "Travel 词汇", components: [{ type: "vocabulary", items: ["itinerary - 行程", "customs - 海关"] }] },
    translation: { title: "翻译", components: [{ type: "translation", items: ["I miss you | 我想你", "Safe trip | 一路平安"] }] },
    writing_outline: { title: "写作提纲", components: [{ type: "writing_outline", steps: [{ label: "claim", text: "Travel broadens the mind" }, { label: "reason", text: "exposure to cultures" }] }] },
    grammar: { title: "语法", components: [{ type: "grammar", text: "现在完成时表经历", items: ["have + 过去分词", "常与 ever/never 连用"] }] },
    debate: { title: "辩论", components: [{ type: "debate", text: "Motion: AI helps education", items: ["For: personalized learning", "Against: reduces critical thinking"] }] }
  };
  for (const [type, card] of Object.entries(cards)) {
    const block = serializeAgentCardSpecToExportText(card);
    assert.ok(block && block.includes("Agent card:"), `${type} 序列化应含 Agent card 块`);
    const files = normalizeAgentOutputFiles([], "导出当前练习卡为docx", "已生成。", [{ role: "assistant", content: block }], null, card);
    assert.strictEqual(files.length, 1, `${type} 应能从序列化块导出文件`);
    assert.strictEqual(files[0].format, "docx", `${type} 默认 docx`);
    assert.ok(String(files[0].content || "").trim().length > 0, `${type} 导出内容非空`);
  }
  // 仅结构组件（无实质内容）不应产出序列化块
  assert.strictEqual(serializeAgentCardSpecToExportText({ title: "空", components: [{ type: "header" }, { type: "actions" }] }), "", "无实质内容不序列化");
}

console.log("agentQuestionExportAutogen.test.js passed");
