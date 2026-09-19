const { settings } = require("../config");
const {
  normalizeAttachment,
  hasMultimodalAttachments,
  buildUserContentWithAttachments,
  callMimoChatRaw,
  extractJsonFromContent
} = require("./mimoAgentMedia");

// 拍照答疑 / 解题：把用户上传的题目（图片/文档/文字）交给模型，产出结构化解题：
// 读题回显 → 题型 → 技能标签 → 考点 → 分步解析 → 答案 → 易错点 → 生词。
// 图片/音视频附件走多模态模型 mimo-v2.5；纯文字走文本模型 mimo-v2.5-pro。

// 与 ProgressStore/ReviewStore 的六大技能保持一致，便于「加入错题本/计入进度」。
const SOLVE_SKILLS = Object.freeze(["听力", "词汇", "语法", "阅读", "写作", "口语"]);

function clampSolveSkill(value) {
  const s = String(value || "").trim();
  return SOLVE_SKILLS.includes(s) ? s : "";
}

function strList(value, max, itemMax = 400) {
  if (!Array.isArray(value)) return [];
  return value
    .map((x) => String(x || "").replace(/\s+/g, " ").trim().slice(0, itemMax))
    .filter(Boolean)
    .slice(0, max);
}

function normalizeSolveVocab(value) {
  if (!Array.isArray(value)) return [];
  return value
    .map((v) => ({
      word: String(v?.word ?? v?.term ?? "").replace(/\s+/g, " ").trim().slice(0, 60),
      phonetic: String(v?.phonetic ?? v?.ipa ?? "").trim().slice(0, 60),
      meaning: String(v?.meaning ?? v?.definition ?? v?.cn ?? "").replace(/\s+/g, " ").trim().slice(0, 200),
      example: String(v?.example ?? v?.sentence ?? "").replace(/\s+/g, " ").trim().slice(0, 240)
    }))
    .filter((v) => v.word && v.meaning)
    .slice(0, 6);
}

function normalizeSolveResult(obj) {
  const o = obj && typeof obj === "object" ? obj : {};
  return {
    questionText: String(o.questionText ?? o.question ?? o.readback ?? "").trim().slice(0, 2000),
    questionType: String(o.questionType ?? o.type ?? "").replace(/\s+/g, " ").trim().slice(0, 60),
    skill: clampSolveSkill(o.skill),
    points: strList(o.points ?? o.keyPoints, 4),
    steps: strList(o.steps ?? o.analysis ?? o.solution, 6, 600),
    answer: String(o.answer ?? o.finalAnswer ?? "").trim().slice(0, 800),
    pitfalls: strList(o.pitfalls ?? o.traps ?? o.mistakes, 3),
    vocab: normalizeSolveVocab(o.vocab ?? o.words),
    comment: String(o.comment ?? "").replace(/\s+/g, " ").trim().slice(0, 300)
  };
}

const SOLVE_INSTRUCTION = [
  "你是一位耐心细致的中学英语老师，帮助中国学生解答不会做的英语题目（题目可能来自图片、文档或文字描述）。",
  "先准确读题：若题目来自图片/文档，请把题目原文（含选项）转写到 questionText，作为读题回显。",
  "再一步步讲清怎么做、为什么，最后给出确定的答案。",
  "只输出一个 JSON 对象，键如下：",
  '{"questionText":"题目原文(读题回显)","questionType":"题型/学科，如 语法单选/完形填空/阅读理解/翻译/写作","skill":"听力|词汇|语法|阅读|写作|口语 之一","points":["考点(<=4)"],"steps":["分步解析(<=6，由浅入深，讲清依据)"],"answer":"最终答案(明确)","pitfalls":["易错点/提醒(<=3)"],"vocab":[{"word":"英文生词","phonetic":"音标","meaning":"中文释义","example":"例句"}],"comment":"一句中文鼓励或点拨"}',
  "steps 用简体中文讲解，可引用英文原句。skill 必须是六选一。vocab 收录题目中值得记的英文生词（<=6，没有就空数组）。answer 必须明确、直接。",
  "如果附件或文字里没有可解答的题目，请在 questionText 说明，并在 answer 给出你能提供的帮助，其余键可留空。"
].join("\n");

async function solveQuestion({ message = "", attachments = [], callModel } = {}) {
  const rawList = Array.isArray(attachments) ? attachments : [];
  const normalized = rawList.map((item) => normalizeAttachment(item)).filter(Boolean);
  const question = String(message || "").trim().slice(0, 2000);
  if (!question && normalized.length === 0) throw new Error("题目为空");
  const multimodal = hasMultimodalAttachments(normalized);

  const userText = question ? `${SOLVE_INSTRUCTION}\n\n学生补充：${question}` : SOLVE_INSTRUCTION;
  const messages = normalized.length
    ? [{ role: "user", content: buildUserContentWithAttachments(userText, normalized) }]
    : [{ role: "user", content: userText }];

  const call = typeof callModel === "function" ? callModel : callMimoChatRaw;
  const data = await call(messages, {
    model: multimodal ? settings.mimoMultimodalModel : settings.mimoTextModel,
    temperature: 0.2,
    maxTokens: 2200,
    json: true
  });
  const obj = extractJsonFromContent(data?.choices?.[0]?.message?.content);
  const result = normalizeSolveResult(obj);
  if (!result.questionText && !result.answer && result.steps.length === 0) {
    throw new Error("解题失败：没能识别题目，请换一张更清晰的图或补充文字");
  }
  return result;
}

module.exports = {
  solveQuestion,
  SOLVE_SKILLS,
  __test: { normalizeSolveResult, normalizeSolveVocab, clampSolveSkill, strList }
};
