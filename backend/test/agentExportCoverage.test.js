"use strict";
// 全练习类型导出覆盖回归测试：对每种练习卡类型，用代表性“Agent card:”序列化块走
// 通用历史文本导出路径，断言都能产出非空文件。锁定“所有练习类型均可导出”这一保证。
// 纯函数 / 真实依赖，不调用模型。
const assert = require("assert");
const { __test } = require("../src/services/mimoText");
const normalizeAgentOutputFiles = __test.normalizeAgentOutputFiles;

// FOCUSED_AGENT_CARD_COMPONENT_SPEC 里全部练习组件类型的代表性客户端序列化文本。
const blocks = {
  vocabulary: "Agent card: 词汇卡\nVocabulary:\n- ambitious - 有抱负的\n- diligent - 勤奋的\n- resilient - 有韧性的",
  phrase: "Agent card: 短语卡\nPhrases:\n- look forward to - 期待\n- give up - 放弃",
  cloze: "Agent card: 填空\nCloze:\nShe ___ to school every day.\nOptions: go / goes / going / gone\nAnswer: goes",
  question_set: "Agent card: 题组\nQuestions:\n1. What is AI?\nA. Tool\nB. Animal\nC. Plant\nD. Rock\nCorrect answer: A\n2. Renewable?\nA. Coal\nB. Solar\nCorrect answer: B",
  reading: "Agent card: 阅读理解\nTranscript:\nTechnology changes life.\nQuestions:\n1. Main idea?\nA. Tech\nB. Food\nCorrect answer: A",
  gap_match: "Agent card: 七选五\nText: Reading is useful. 【1】 It builds knowledge. 【2】\nOptions:\nA. It is fun.\nB. It is hard.\nC. It helps learning.\nAnswer: C | A",
  chart_writing: "Agent card: 图表作文\nTask: Describe the chart in 150 words.\nData:\n- Coffee: 40, 55\n- Tea: 30, 35",
  short_answer: "Agent card: 短答\nPrompt: Translate: 我爱学习。\nAnswer: I love studying.",
  sentence_builder: "Agent card: 组句\nWords: is / this / a / book\nAnswer: This is a book.",
  ordering: "Agent card: 排序\n- He woke up.\n- He had breakfast.\n- He went to work.\nAnswer: 1 | 2 | 3",
  speaking_prompt: "Agent card: 口语\nSpeaking Prompt: Describe your hometown.\nTask: Speak for one minute.",
  minimal_pair: "Agent card: 辨音\nMinimal Pairs:\n- ship / sheep\n- bit / beat",
  pronunciation: "Agent card: 发音\nPronunciation:\n- linking: an apple\n- stress: reCORD (v)",
  grammar: "Agent card: 语法\nGrammar:\n- Present perfect: have + p.p.\n- Use for experience",
  translation: "Agent card: 翻译\nTranslation:\n- I love you | 我爱你\n- See you | 再见",
  compare: "Agent card: 辨析\nKey Differences:\n- say - 说出内容\n- tell - 告诉某人",
  writing_outline: "Agent card: 写作提纲\nWriting Outline:\n- claim: Technology helps\n- reason: efficiency",
  correction: "Agent card: 纠错\nCorrections:\n- I have went -> I have gone\n- He don't -> He doesn't",
  word_family: "Agent card: 词族\nWord Family:\n- decide / decision / decisive",
  listening_cue: "Agent card: 听力信号\nListening Cues:\n- however - 转折\n- because - 因果",
  scenario: "Agent card: 场景\nScenario:\n- office - Could you clarify that?\n- shop - How much is it?",
  ethics: "Agent card: 思辨\nText: A friend cheated on a test and asks you to stay silent.\n- Is loyalty more important than honesty?\n- What would you do?",
  debate: "Agent card: 辩论\nMotion: Social media does more harm than good.\n- For: It spreads misinformation.\n- Against: It connects people.",
  error_hunt: "Agent card: 找错\nText: He go to school yesterday and eat lunch.\n- go -> went\n- eat -> ate",
  storytelling: "Agent card: 故事\nPrompt: Write a short story about a lost key.\n- use words: mystery, find\n- include a twist",
  paraphrase: "Agent card: 改写\nOriginal: The film was so boring that I fell asleep.\n- The film bored me into sleep.\n- I dozed off because the film was dull.",
  register: "Agent card: 语气\nRegister:\n- casual: I get it. | formal: I understand.",
  summary: "Agent card: 短文\nSummary: A short passage about the benefits of reading every day."
};

const exportMessage = "把刚才这张卡片导出成 docx 文档";
const missing = [];
for (const [type, block] of Object.entries(blocks)) {
  const recent = [{ role: "assistant", content: block }];
  let files = [];
  try {
    files = normalizeAgentOutputFiles([], exportMessage, "已生成。", recent, null, null);
  } catch (_) {
    files = [];
  }
  const ok = Array.isArray(files) && files.length > 0 && String((files[0] || {}).content || "").trim().length > 0;
  if (!ok) missing.push(type);
}

assert.strictEqual(missing.length, 0, `以下练习类型未能从历史导出: ${missing.join(", ")}`);
assert.ok(Object.keys(blocks).length >= 28, "应覆盖全部练习组件类型(>=28)");

// ---- 听力素材打包导出：题目 + 原文 + 音频 一起打成 zip（含“打包/压缩包”措辞） ----
{
  const record = {
    title: "Airport Check-in",
    scriptPreview: "A: May I see your passport?\nB: Sure, here you go.\nA: Are you checking any bags?",
    questions: [
      { index: 0, questionText: "Where does it take place?", options: ["Airport", "Hotel", "Bank", "School"], correctAnswer: 0, explanation: "" },
      { index: 1, questionText: "How many bags?", options: ["One", "Two", "None", "Three"], correctAnswer: 0, explanation: "" }
    ],
    audioUrl: "https://api.example.com/listene/api/v1/audio/airport.wav",
    audioReady: true
  };
  const zipMessages = [
    "把这套听力素材连同题目和音频一起打包导出成 zip",
    "把刚才这套听力打包成压缩包"
  ];
  for (const msg of zipMessages) {
    assert.strictEqual(__test.isAgentListeningZipExportRequest(msg), true, `应识别为听力打包: ${msg}`);
    const files = normalizeAgentOutputFiles([], msg, "已生成。", [], record, null);
    assert.strictEqual(files.length, 1, `应产出 1 个包: ${msg}`);
    assert.strictEqual(files[0].format, "zip", "听力包格式应为 zip");
    const c = String(files[0].content || "");
    assert.ok(/transcript\.txt/i.test(c) && /passport/i.test(c), "听力包应含原文 transcript");
    assert.ok(/questions\.txt/i.test(c) && /take place/i.test(c), "听力包应含题目 questions");
    assert.ok(/airport\.wav/i.test(c), "听力包应含音频引用");
  }
}

console.log(`agentExportCoverage.test.js passed (${Object.keys(blocks).length}/${Object.keys(blocks).length} types + listening zip)`);
