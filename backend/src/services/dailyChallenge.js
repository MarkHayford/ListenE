// 每日挑战（云端、按用户、按自然日）：每天一套跨技能、跨题型的小测，完成计入进度/连续打卡。
// 题型覆盖 mcq/选词填空/连词成句/句子排序/短答/翻译，每天随机抽取若干种，均可确定性判分。
const { query } = require("./db");
const { settings } = require("../config");
const { callMimoChatRaw, extractJsonFromContent } = require("./mimoAgentMedia");
const { recordProgress, deriveSkill } = require("./studyProgress");
const { addWrong } = require("./reviewBook");

const DAY_MS = 86400000;
const TZ_OFFSET_MS = 8 * 3600 * 1000;
const SKILLS = new Set(["听力", "词汇", "语法", "阅读", "写作", "口语"]);

// 每日挑战支持的全部题型（均可确定性判分）。每天从中随机抽取。
const DAILY_TYPES = ["mcq", "cloze", "sentence_builder", "ordering", "short_answer", "translation"];
const DAILY_TYPE_SET = new Set(DAILY_TYPES);
const DAILY_QUESTION_COUNT = 5;

function dayKey(ts = Date.now()) {
  return Math.floor((Number(ts) + TZ_OFFSET_MS) / DAY_MS);
}
function compact(text, max) {
  return String(text || "").replace(/\s+/g, " ").trim().slice(0, max);
}

// 文本判分归一化：忽略大小写、首尾/多余空格与常见标点。
function normalizeText(value) {
  return compact(value, 400)
    .toLowerCase()
    .replace(/[.!?。！？,，；;：:'"`()\[\]{}\\\/]/g, "")
    .replace(/\s+/g, " ")
    .trim();
}
// 词级归一化（选词/词序）：在 normalizeText 基础上去掉所有空格。
function normalizeWord(value) {
  return normalizeText(value).replace(/\s+/g, "");
}

// 返回正确选项下标；无法可靠解析时返回 -1（绝不静默默认成 A，避免错误答案键）。
function resolveAnswerIndex(answer, options) {
  const raw = compact(answer, 160);
  if (!raw) return -1;
  if (raw.length === 1 && raw.toUpperCase() >= "A" && raw.toUpperCase() <= "Z") {
    const idx = raw.toUpperCase().charCodeAt(0) - 65;
    if (idx >= 0 && idx < options.length) return idx;
  }
  const num = Number.parseInt(raw, 10);
  if (Number.isInteger(num) && num >= 1 && num <= options.length) return num - 1;
  const norm = raw.toLowerCase();
  return options.findIndex((o) => compact(o, 160).toLowerCase() === norm);
}

function shuffleArray(arr, rng = Math.random) {
  const a = arr.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(rng() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

// 随机抽取本日题型：洗牌后取前 count 种（题库 6 种、每天 5 种，保证天天不同且覆盖全部）。
function pickDailyTypes(rng = Math.random, count = DAILY_QUESTION_COUNT) {
  const pool = shuffleArray(DAILY_TYPES, rng);
  const out = [];
  for (let i = 0; i < count; i++) out.push(pool[i % pool.length]);
  return out;
}

function commonFields(q) {
  const skillRaw = compact(q?.skill, 8);
  return {
    skill: SKILLS.has(skillRaw) ? skillRaw : "语法",
    explanation: compact(q?.explanation || q?.reason, 200)
  };
}

function normalizeOptions(value, max, len) {
  return (Array.isArray(value) ? value : []).map((o) => compact(o, len)).filter(Boolean).slice(0, max);
}

// 可接受答案集（短答/翻译）：主答案 + 模型给的等价说法，按归一化去重。
// 判分时归一化命中任一即算对，缓解开放题"答案有多种合法说法"导致的误判。
function normalizeAcceptList(value, primary) {
  const all = [primary, ...((Array.isArray(value) ? value : []).map((s) => compact(s, 200)))];
  const seen = new Set();
  const out = [];
  for (const a of all) {
    const n = normalizeText(a);
    if (!n || seen.has(n)) continue;
    seen.add(n);
    out.push(a);
  }
  return out.slice(0, 6);
}

// ---- 各题型归一化（无效返回 null 丢弃）----

function normalizeMcq(q) {
  const questionText = compact(q?.questionText || q?.question || q?.text, 220);
  const options = normalizeOptions(q?.options, 4, 140);
  if (!questionText || options.length < 2) return null;
  const correctAnswer = resolveAnswerIndex(q?.answer ?? q?.correctAnswer, options);
  if (correctAnswer < 0) return null;
  return { type: "mcq", questionText, options, correctAnswer, ...commonFields(q) };
}

// 选词填空（单空）：句子里恰好一个 ___，从词库里选正确词。判分等价于"选对下标"。
function normalizeCloze(q) {
  const questionText = compact(q?.text || q?.questionText || q?.prompt, 220);
  const options = normalizeOptions(q?.options, 6, 60);
  const answerWord = compact(q?.answer ?? q?.correctAnswer, 60);
  if (!questionText || !questionText.includes("_") || options.length < 2 || !answerWord) return null;
  const correctAnswer = options.findIndex((o) => normalizeWord(o) === normalizeWord(answerWord));
  if (correctAnswer < 0) return null;
  return { type: "cloze", questionText, options, correctAnswer, answer: options[correctAnswer], ...commonFields(q) };
}

// 连词成句：tokens 乱序词块，answer 正确整句。
function normalizeSentenceBuilder(q) {
  const answer = compact(q?.answer ?? q?.correctAnswer ?? q?.sentence, 220);
  if (!answer) return null;
  const answerTokens = answer.split(/\s+/).filter(Boolean);
  if (answerTokens.length < 2) return null;
  let tokens = (Array.isArray(q?.tokens) ? q.tokens : (Array.isArray(q?.items) ? q.items : []))
    .map((t) => compact(t, 40)).filter(Boolean).slice(0, 14);
  const sig = (arr) => arr.map(normalizeWord).filter(Boolean).sort().join("|");
  if (tokens.length < 2 || sig(tokens) !== sig(answerTokens)) tokens = answerTokens.slice(0, 14);
  const scrambled = shuffleArray(tokens);
  const questionText = compact(q?.questionText || q?.text || "Put the words in the correct order.", 120);
  return { type: "sentence_builder", questionText, tokens: scrambled, answer, ...commonFields(q) };
}

// 句子排序：items 乱序句子，answer 正确顺序数组。
function normalizeOrdering(q) {
  let answer = Array.isArray(q?.answer)
    ? q.answer.map((s) => compact(s, 160))
    : compact(q?.answer ?? q?.correctAnswer, 800).split("|").map((s) => s.trim());
  answer = answer.filter(Boolean).slice(0, 6);
  if (answer.length < 2) return null;
  let items = normalizeOptions(q?.items, 6, 160);
  const sig = (arr) => arr.map(normalizeText).sort().join("||");
  if (items.length < 2 || sig(items) !== sig(answer)) items = answer.slice();
  const scrambled = shuffleArray(items);
  const questionText = compact(q?.questionText || q?.text || "Put the sentences in the correct order.", 120);
  return { type: "ordering", questionText, items: scrambled, answer, ...commonFields(q) };
}

// 短答：一句话简答，键入后归一化匹配主答案或任一可接受答案。
function normalizeShortAnswer(q) {
  const questionText = compact(q?.questionText || q?.text || q?.prompt, 220);
  const answer = compact(q?.answer ?? q?.correctAnswer, 200);
  if (!questionText || !answer) return null;
  return { type: "short_answer", questionText, answer, accept: normalizeAcceptList(q?.accept, answer), ...commonFields(q) };
}

// 翻译：中译英，键入后归一化匹配参考译文或任一可接受译文。
function normalizeTranslation(q) {
  const questionText = compact(q?.source || q?.prompt || q?.questionText || q?.text, 220);
  const answer = compact(q?.answer ?? q?.correctAnswer ?? q?.reference, 220);
  if (!questionText || !answer) return null;
  return { type: "translation", questionText, answer, accept: normalizeAcceptList(q?.accept, answer), ...commonFields(q) };
}

function normalizeOneQuestion(q) {
  const type = compact(q?.type, 20).toLowerCase();
  switch (type) {
    case "cloze": return normalizeCloze(q);
    case "sentence_builder": return normalizeSentenceBuilder(q);
    case "ordering": return normalizeOrdering(q);
    case "short_answer": return normalizeShortAnswer(q);
    case "translation": return normalizeTranslation(q);
    case "mcq":
    default: return normalizeMcq(q);
  }
}

function normalizeQuestions(arr) {
  const list = Array.isArray(arr) ? arr : [];
  return list.map(normalizeOneQuestion).filter(Boolean).slice(0, DAILY_QUESTION_COUNT);
}

// 各题型的出题 schema（喂给模型）。
const TYPE_PROMPT = {
  mcq: 'type "mcq": a 4-option single-best-answer question. Fields: {"type":"mcq","questionText":"...","options":["a","b","c","d"],"answer":"the exact correct option text","explanation":"简短中文解析","skill":"..."}',
  cloze: 'type "cloze": a single-blank word-bank fill-in. Put EXACTLY ONE blank "___" inside "text". Provide 4 plausible word-bank options. Fields: {"type":"cloze","text":"a sentence with exactly one ___ blank","options":["w1","w2","w3","w4"],"answer":"the exact correct word (must be one of options)","explanation":"简短中文解析","skill":"..."}',
  sentence_builder: 'type "sentence_builder": unscramble words into one correct sentence. Fields: {"type":"sentence_builder","tokens":["the","sentence","words","shuffled"],"answer":"The full correct sentence.","explanation":"简短中文解析","skill":"..."}',
  ordering: 'type "ordering": put shuffled sentences into the correct logical order. Use 3-4 short sentences. Fields: {"type":"ordering","items":["sentence in shuffled order", "..."],"answer":["sentence 1 in correct order","sentence 2","..."],"explanation":"简短中文解析","skill":"..."}',
  short_answer: 'type "short_answer": a question with a short answer (a single word or short phrase). Provide the best answer plus other acceptable answers/synonyms in "accept" for lenient grading. Fields: {"type":"short_answer","questionText":"...","answer":"the best short answer","accept":["acceptable variant","synonym"],"explanation":"简短中文解析","skill":"..."}',
  translation: 'type "translation": translate a short Chinese sentence into natural English. Provide the most natural translation plus other acceptable correct translations in "accept" for lenient grading. Fields: {"type":"translation","source":"中文句子","answer":"the most natural English translation","accept":["another acceptable translation"],"explanation":"简短中文解析","skill":"..."}'
};

async function generateDailyQuestions(types) {
  const chosen = (Array.isArray(types) && types.length ? types : pickDailyTypes())
    .filter((t) => DAILY_TYPE_SET.has(t));
  const requested = chosen.length ? chosen : pickDailyTypes();
  const schemaLines = requested.map((t, i) => `Q${i + 1} -> ${TYPE_PROMPT[t]}`);
  const prompt = [
    `Generate exactly ${requested.length} English daily-challenge questions for a Chinese learner, MIXED across the question types specified below (one question per line, in the given order).`,
    "Vary skills across 词汇/语法/阅读/听力/写作 where natural. Keep each question concise and unambiguous, with exactly one correct answer.",
    "Test real English knowledge. Do NOT write self-referential or circular questions, and never let the correct answer merely repeat wording from the question stem.",
    'Set each "skill" to exactly one of: 听力|词汇|语法|阅读|写作|口语.',
    "Question types to produce (EXACTLY these, in this order):",
    ...schemaLines,
    'Return ONLY a JSON object: {"questions":[ one object per question above, each strictly matching its type schema ]}'
  ].join("\n");
  const data = await callMimoChatRaw(
    [{ role: "user", content: prompt }],
    { model: settings.mimoTextModel, temperature: 0.6, maxTokens: 2200, json: true }
  );
  const obj = extractJsonFromContent(data.choices?.[0]?.message?.content);
  const questions = normalizeQuestions(obj?.questions);
  if (questions.length < 3) throw new Error("daily generation insufficient");
  return questions;
}

// 给客户端的题目（隐藏答案/解析，防止作弊），按题型只暴露作答所需字段。
function publicQuestion(q) {
  const type = q?.type || "mcq";
  const base = { type, questionText: q.questionText, skill: q.skill };
  switch (type) {
    case "cloze":
      return { ...base, options: q.options };
    case "sentence_builder":
      return { ...base, tokens: q.tokens };
    case "ordering":
      return { ...base, items: q.items };
    case "short_answer":
    case "translation":
      return base;
    case "mcq":
    default:
      return { ...base, options: q.options };
  }
}

// 把客户端单题作答规整为 { choice?, order?, text? }。兼容旧客户端直接传选项下标(number)。
function coerceAnswer(pick) {
  if (typeof pick === "number") return { choice: pick };
  if (pick && typeof pick === "object") return pick;
  return {};
}

// 按题型判分，返回 { correct, your?, yourOrder?, yourText? }。
function gradeAnswer(q, pick) {
  const a = coerceAnswer(pick);
  switch (q?.type) {
    case "sentence_builder": {
      const order = Array.isArray(a.order) ? a.order.map((s) => String(s || "")) : [];
      return { yourOrder: order, correct: order.length > 0 && normalizeText(order.join(" ")) === normalizeText(q.answer) };
    }
    case "ordering": {
      const order = Array.isArray(a.order) ? a.order.map((s) => String(s || "")) : [];
      const want = Array.isArray(q.answer) ? q.answer : [];
      const correct = order.length === want.length && want.length > 0 &&
        order.every((s, i) => normalizeText(s) === normalizeText(want[i]));
      return { yourOrder: order, correct };
    }
    case "short_answer":
    case "translation": {
      const text = compact(a.text, 300);
      const norm = normalizeText(text);
      const accepts = Array.isArray(q.accept) && q.accept.length ? q.accept : [q.answer];
      const correct = norm.length > 0 && accepts.some((ans) => normalizeText(ans) === norm);
      return { yourText: text, correct };
    }
    case "cloze":
    case "mcq":
    default: {
      const your = Number.isInteger(a.choice) ? a.choice : -1;
      return { your, correct: your >= 0 && your === q.correctAnswer };
    }
  }
}

// 答错题转错题本条目（按题型映射 kind/options/answer 文本）。
function reviewItemFromResult(r) {
  if (r.type === "mcq" || r.type === "cloze") {
    const options = Array.isArray(r.options) ? r.options : [];
    return {
      componentType: r.type === "mcq" ? "question_set" : "cloze",
      kind: r.type === "cloze" ? "cloze" : "mcq",
      options,
      answer: options[r.correctAnswer] || r.answer || ""
    };
  }
  const answerText = r.type === "ordering"
    ? (Array.isArray(r.answer) ? r.answer.join(" | ") : String(r.answer || ""))
    : String(r.answer || "");
  return { componentType: r.type, kind: "blank", options: [], answer: answerText };
}

async function getDaily(user) {
  if (!user) throw new Error("unauthorized");
  const day = dayKey();
  const now = Date.now();
  const existing = await query(`SELECT * FROM daily_challenge WHERE user_id = $1 AND day = $2`, [user.id, day]);
  if (existing.rows.length) {
    const row = existing.rows[0];
    const questions = Array.isArray(row.questions) ? row.questions : [];
    return {
      day,
      completed: row.completed === true,
      score: Number(row.score || 0),
      total: questions.length,
      questions: row.completed ? questions : questions.map(publicQuestion),
      persisted: true
    };
  }
  const questions = await generateDailyQuestions(pickDailyTypes());
  await query(
    `INSERT INTO daily_challenge (user_id, day, questions, completed, score, created_at)
     VALUES ($1, $2, $3::jsonb, false, 0, $4)
     ON CONFLICT (user_id, day) DO NOTHING`,
    [user.id, day, JSON.stringify(questions), now]
  );
  // 并发首次拉取时可能是别的请求先写入；回读 DB 里实际持久化的题目，
  // 确保返回给客户端的题目与 submitDaily 判分所依据的题目完全一致。
  const persisted = await query(`SELECT questions, completed, score FROM daily_challenge WHERE user_id = $1 AND day = $2`, [user.id, day]);
  const row = persisted.rows[0];
  const finalQuestions = Array.isArray(row?.questions) ? row.questions : questions;
  return {
    day,
    completed: row?.completed === true,
    score: Number(row?.score || 0),
    total: finalQuestions.length,
    questions: row?.completed ? finalQuestions : finalQuestions.map(publicQuestion),
    persisted: true
  };
}

async function submitDaily(user, answers = []) {
  if (!user) throw new Error("unauthorized");
  const day = dayKey();
  const now = Date.now();
  const existing = await query(`SELECT * FROM daily_challenge WHERE user_id = $1 AND day = $2`, [user.id, day]);
  if (!existing.rows.length) throw new Error("no daily challenge for today");
  const row = existing.rows[0];
  const questions = Array.isArray(row.questions) ? row.questions : [];
  const picks = Array.isArray(answers) ? answers : [];
  const results = questions.map((q, i) => ({ ...q, ...gradeAnswer(q, picks[i]) }));
  const correct = results.filter((r) => r.correct).length;
  const total = questions.length || 1;
  const score = Math.round((correct / total) * 100);
  const firstTime = row.completed !== true;
  if (firstTime) {
    await query(
      `UPDATE daily_challenge SET completed = true, score = $3, completed_at = $4 WHERE user_id = $1 AND day = $2`,
      [user.id, day, score, now]
    );
    // 每题计入进度（喂技能雷达 + 连续打卡）。
    for (const r of results) {
      try {
        await recordProgress(user, { componentType: "question_set", skill: SKILLS.has(r.skill) ? r.skill : deriveSkill("question_set"), total: 1, correct: r.correct ? 1 : 0 });
      } catch (_) {}
    }
  }
  // 答错的题自动加入错题本（Leitner SRS）；addWrong 幂等，重复提交不会重复添加。
  for (const r of results) {
    if (r.correct) continue;
    try {
      const mapped = reviewItemFromResult(r);
      if (!mapped.answer) continue;
      await addWrong(user, {
        componentType: mapped.componentType,
        kind: mapped.kind,
        skill: SKILLS.has(r.skill) ? r.skill : "语法",
        prompt: r.questionText,
        options: mapped.options,
        answer: mapped.answer,
        explanation: r.explanation
      });
    } catch (_) {}
  }
  return { completed: true, score, correct, total: questions.length, results, alreadyCompleted: !firstTime, persisted: true };
}

module.exports = {
  getDaily,
  submitDaily,
  __test: { normalizeQuestions, resolveAnswerIndex, publicQuestion, pickDailyTypes, gradeAnswer, normalizeText, normalizeWord, DAILY_TYPES }
};
