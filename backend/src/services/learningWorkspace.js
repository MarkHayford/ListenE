const crypto = require("crypto");
const { settings } = require("../config");
const { callMimoText } = require("./mimoText");
const { query } = require("./db");
const { updateUserModel } = require("./userModel");
const { deleteLibraryItemsForWorkspace } = require("./userLibrary");
const { logger } = require("./logger");

function compactText(text, max = 500) {
  const value = String(text || "").replace(/\s+/g, " ").trim();
  return value.length > max ? value.slice(0, max - 1) : value;
}

function asArray(value) {
  return Array.isArray(value) ? value : [];
}

function uniqueCompactList(items, maxItems = 12, maxLength = 260) {
  const seen = new Set();
  const out = [];
  for (const item of asArray(items)) {
    const value = compactText(item, maxLength);
    const key = value.toLowerCase();
    if (!value || seen.has(key)) continue;
    seen.add(key);
    out.push(value);
    if (out.length >= maxItems) break;
  }
  return out;
}

function memoryNow(value) {
  const num = Number(value || 0);
  return Number.isFinite(num) && num > 0 ? num : Date.now();
}

function normalizeWorkspaceMemoryEntry(entry = {}, fallback = {}) {
  const type = ["weakness", "preference", "fact"].includes(String(entry.type || fallback.type || "").toLowerCase())
    ? String(entry.type || fallback.type).toLowerCase()
    : "fact";
  const content = compactText(entry.content || fallback.content, 260);
  if (!content) return null;
  const key = compactText(entry.key || fallback.key || `${type}:${content.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fa5]+/gi, "_").slice(0, 80)}`, 120);
  const importance = Math.max(0, Math.min(1, Number(entry.importance ?? fallback.importance ?? 0.55)));
  return {
    key,
    type,
    content,
    importance,
    updatedAt: memoryNow(entry.updatedAt || fallback.updatedAt)
  };
}

function mergeWorkspaceMemory(memory = [], additions = [], now = Date.now()) {
  const map = new Map();
  for (const item of asArray(memory)) {
    const normalized = normalizeWorkspaceMemoryEntry(item);
    if (normalized) map.set(normalized.key, normalized);
  }
  for (const item of asArray(additions)) {
    const normalized = normalizeWorkspaceMemoryEntry(item, { updatedAt: now });
    if (!normalized) continue;
    const previous = map.get(normalized.key);
    map.set(normalized.key, previous
      ? {
          ...previous,
          ...normalized,
          importance: Math.max(previous.importance || 0, normalized.importance || 0),
          updatedAt: Math.max(previous.updatedAt || 0, normalized.updatedAt || now)
        }
      : normalized);
  }
  return Array.from(map.values())
    .sort((a, b) => ((b.importance || 0) * 10000000000000 + (b.updatedAt || 0)) - ((a.importance || 0) * 10000000000000 + (a.updatedAt || 0)))
    .slice(0, 30);
}

function normalizeWorkspaceMemoryFields(workspace = {}) {
  return {
    memorySummary: compactText(workspace.memorySummary, 1500),
    memorySummaryThroughId: Number(workspace.memorySummaryThroughId) || 0,
    memory: mergeWorkspaceMemory(workspace.memory || [], [], Date.now())
  };
}

// 增量摘要选择：只挑「刚移出保留尾部(tail) 且此前未摘要过(id > previousThroughId)」的旧消息交给摘要器。
// 保证每条旧消息只被摘要一次——此前每轮把尾部以外的全部重摘，既多一次模型调用又重复折叠同一批消息。
// 消息缺少稳定 id（或 id 重复）时安全退回：一次性摘要整段 aged-out（宁可多摘也不丢内容）。
function selectWorkspaceSummaryBatch(previousThroughId = 0, messages = [], tail = 24) {
  const list = asArray(messages);
  const keepTail = Math.max(0, Number(tail) || 0);
  const agedOut = list.slice(0, Math.max(0, list.length - keepTail));
  const prev = Number(previousThroughId) || 0;
  if (agedOut.length === 0) return { toSummarize: [], throughId: prev };
  const ids = agedOut.map((m) => Number(m && m.id) || 0);
  const usableIds = ids.every((id) => id > 0) && new Set(ids).size === ids.length;
  if (!usableIds) return { toSummarize: agedOut, throughId: Math.max(prev, ...ids) };
  const toSummarize = agedOut.filter((_, i) => ids[i] > prev);
  return { toSummarize, throughId: Math.max(prev, ...ids) };
}

function workspaceMessageText(message = {}) {
  const role = message?.role === "user" ? "User" : "Agent";
  const content = compactText(message?.content || message?.text || message?.message, 320);
  return content ? `${role}: ${content}` : "";
}

function summarizeWorkspaceMessages(previousSummary = "", messages = []) {
  const lines = asArray(messages).map(workspaceMessageText).filter(Boolean);
  if (!lines.length) return compactText(previousSummary, 1500);
  const joined = lines.join(" ");
  const prefix = compactText(previousSummary, 900);
  return compactText(prefix ? `${prefix} ${joined}` : joined, 1500);
}

async function summarizeWorkspaceMessagesWithModel(previousSummary = "", messages = []) {
  const lines = asArray(messages).map(workspaceMessageText).filter(Boolean);
  if (!lines.length) return compactText(previousSummary, 1500);
  const fallback = summarizeWorkspaceMessages(previousSummary, messages);
  if (!settings.mimoApiKey) return fallback;
  try {
    const raw = await callMimoText([
      {
        role: "system",
        content: `你是 ListenE 工作区级记忆摘要器。只为当前工作区维护滚动摘要，不写全局用户画像，不跨工作区推断。
任务：把旧 memorySummary 和即将移出最近窗口的旧消息增量压缩成新的 memorySummary。
要求：
- 只保留本工作区已经聊过什么、生成过什么、进展到哪里、明确偏好或稳定薄弱点。
- 不要编造未出现的信息。
- 不要输出寒暄、建议或解释。
- 摘要不超过 1500 个中文字符或英文等量长度。
- 只返回 JSON：{"memorySummary":"..."}.`
      },
      {
        role: "user",
        content: JSON.stringify({
          previousMemorySummary: compactText(previousSummary, 1500),
          messagesLeavingRecentWindow: lines
        })
      }
    ], { temperature: 0.2, maxTokens: 700 });
    const summary = compactText(raw?.memorySummary || raw?.summary, 1500);
    return summary || fallback;
  } catch (error) {
    logger.warn("workspace memory summary compression failed", { detail: error?.message || String(error) });
    return fallback;
  }
}

function extractExplicitMemoryEntriesFromMessages(messages = [], now = Date.now()) {
  const entries = [];
  for (const message of asArray(messages)) {
    if (message?.role && message.role !== "user") continue;
    const text = compactText(message?.content || message?.text || message?.message, 400);
    if (!text) continue;
    const rememberMatch = text.match(/(?:remember|please remember|记住|帮我记住|请记住)[:：,\s]*(.+)$/i);
    if (rememberMatch?.[1]) {
      const content = compactText(rememberMatch[1], 220);
      if (content) {
        entries.push(normalizeWorkspaceMemoryEntry({
          key: `fact:explicit:${content.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fa5]+/gi, "_").slice(0, 70)}`,
          type: /prefer|preference|喜欢|偏好|更想|更喜欢/i.test(content) ? "preference" : "fact",
          content,
          importance: 1,
          updatedAt: now
        }));
      }
    }
  }
  return entries.filter(Boolean);
}

function extractMemoryEntriesFromEvent(event = {}, body = {}, now = Date.now()) {
  const entries = [];
  const type = `${event.type || ""} ${event.title || ""}`.toLowerCase();
  const explicitMemory = asArray(body.memory).map((item) => normalizeWorkspaceMemoryEntry(item, { updatedAt: now })).filter(Boolean);
  entries.push(...explicitMemory);
  const weakPoints = uniqueCompactList([
    ...asArray(body.weakPoints),
    ...asArray(body.result?.weakPoints),
    ...asArray(body.analysis?.weakPoints)
  ], 10, 220);
  if (/analysis|mistake|wrong|weak|错因|薄弱/i.test(type) || weakPoints.length) {
    const source = weakPoints.length ? weakPoints : uniqueCompactList([event.description || event.title], 2, 220);
    source.forEach((content) => {
      entries.push(normalizeWorkspaceMemoryEntry({
        key: `weakness:${content.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fa5]+/gi, "_").slice(0, 80)}`,
        type: "weakness",
        content,
        importance: 0.78,
        updatedAt: now
      }));
    });
  }
  const preferenceText = compactText(body.preference || body.preferred || "", 220);
  if (preferenceText) {
    entries.push(normalizeWorkspaceMemoryEntry({
      key: `preference:${preferenceText.toLowerCase().replace(/[^a-z0-9\u4e00-\u9fa5]+/gi, "_").slice(0, 80)}`,
      type: "preference",
      content: preferenceText,
      importance: 0.72,
      updatedAt: now
    }));
  }
  return entries.filter(Boolean);
}

function normalizeContentType(value) {
  const raw = String(value || "").toLowerCase();
  if (raw.includes("chat") || raw.includes("qa") || raw.includes("question") || raw.includes("问答") || raw.includes("答疑")) return "chat";
  if (raw.includes("article") || raw.includes("文章") || raw.includes("lecture") || raw.includes("news")) return "article";
  return "dialogue";
}

function normalizeDifficulty(value) {
  const raw = String(value || "").toLowerCase();
  if (raw.includes("easy") || raw.includes("简单") || raw.includes("a2")) return "简单";
  if (raw.includes("hard") || raw.includes("困难") || raw.includes("c1") || raw.includes("advanced")) return "困难";
  return "普通";
}

function normalizeSpeechRate(value) {
  const raw = String(value || "").toLowerCase();
  if (raw.includes("slow") || raw.includes("慢")) return "slow";
  if (raw.includes("fast") || raw.includes("快")) return "fast";
  return "medium";
}

function normalizeVoiceGender(value) {
  const raw = String(value || "").toLowerCase();
  if (raw.includes("female") || raw.includes("女")) return "female";
  if (raw.includes("male") || raw.includes("男")) return "male";
  return "female";
}

function normalizePitch(value) {
  const raw = String(value || "").toLowerCase();
  if (/low|deep|baritone|bass|低音|低沉|偏低/.test(raw)) return "low";
  if (/high|bright|treble|高音|偏高|清亮|尖亮/.test(raw)) return "high";
  if (/mid|middle|neutral|中音|自然音调|普通音调/.test(raw)) return "medium";
  return "";
}

function normalizeAccent(value) {
  const raw = String(value || "").toLowerCase();
  if (/british|uk|rp|英音|英式|英国/.test(raw)) return "british";
  if (/american|us|美音|美式|美国/.test(raw)) return "american";
  if (/australian|澳音|澳式|澳大利亚/.test(raw)) return "australian";
  if (/canadian|加拿大/.test(raw)) return "canadian";
  return "";
}

function normalizeTone(value) {
  const raw = String(value || "").toLowerCase();
  if (/gentle|soft|温柔|柔和/.test(raw)) return "gentle";
  if (/warm|friendly|亲切|温暖/.test(raw)) return "warm";
  if (/calm|steady|沉稳|平静|克制/.test(raw)) return "calm";
  if (/energetic|lively|活泼|有活力/.test(raw)) return "energetic";
  if (/serious|formal|严肃|正式/.test(raw)) return "serious";
  if (/professional|broadcast|主播|专业/.test(raw)) return "professional";
  return "";
}

function voiceDirectiveText({ pitch = "", accent = "", tone = "" } = {}) {
  const parts = [];
  if (tone) parts.push(`${tone} tone`);
  if (pitch) parts.push(`${pitch} pitch`);
  if (accent) parts.push(`${accent} accent`);
  return parts.join(", ");
}

function normalizeVoiceProfile(value, directives = {}) {
  const gender = normalizeVoiceGender(directives.voiceGender || value);
  const genderWord = gender === "male" ? "Male" : "Female";
  const directive = voiceDirectiveText(directives);
  let raw = compactText(value, 220);
  if (!raw) raw = `${genderWord} adult English voice${directive ? `, ${directive}` : ""}.`;
  const lower = raw.toLowerCase();
  if (!lower.startsWith("male") && !lower.startsWith("female")) {
    raw = `${genderWord} ${raw}`;
  } else if (lower.startsWith("male") && gender === "female") {
    raw = raw.replace(/^male/i, "Female");
  } else if (lower.startsWith("female") && gender === "male") {
    raw = raw.replace(/^female/i, "Male");
  }
  const missing = [];
  if (directives.tone && !raw.toLowerCase().includes(directives.tone)) missing.push(`${directives.tone} tone`);
  if (directives.pitch && !raw.toLowerCase().includes(directives.pitch)) missing.push(`${directives.pitch} pitch`);
  if (directives.accent && !raw.toLowerCase().includes(directives.accent)) missing.push(`${directives.accent} accent`);
  if (missing.length) raw = `${raw.replace(/\.*$/, "")}, ${missing.join(", ")}.`;
  return compactText(raw, 260);
}

function voiceDirectivesFromText(text, overrides = {}) {
  const merged = `${text || ""} ${overrides.voiceProfile || ""} ${overrides.pitch || ""} ${overrides.accent || ""} ${overrides.tone || ""}`;
  const voiceGender = normalizeVoiceGender(overrides.voiceGender || merged);
  const pitch = normalizePitch(overrides.pitch || merged);
  const accent = normalizeAccent(overrides.accent || merged);
  const tone = normalizeTone(overrides.tone || merged);
  return {
    voiceGender,
    voiceProfile: normalizeVoiceProfile(overrides.voiceProfile || text, { voiceGender, pitch, accent, tone }),
    pitch,
    accent,
    tone
  };
}

function isListeningPracticeNeed(text) {
  const raw = String(text || "");
  const lower = raw.toLowerCase();
  const wordBankClozeHit = /选词填空|word\s*bank|cloze|fill[-\s]*in[-\s]*the[-\s]*blank|fill[-\s]*in[-\s]*blank/i.test(raw);
  const explicitListeningHit = /听力|听音频|音频|listening|audio|听一段|精听|复听|逐句听/i.test(raw);
  if (wordBankClozeHit && !explicitListeningHit) return false;
  const listeningHit = [
    "听力", "听音频", "听一段", "精听", "复听", "逐句听", "音频",
    "listening", "ielts listening", "toefl listening"
  ].some((item) => lower.includes(item));
  const creationHit = [
    "生成", "创建", "来一套", "来一段", "做一套", "出一套", "出题",
    "训练", "练习", "习题", "题目", "素材", "卡片", "practice", "training", "quiz", "test", "material"
  ].some((item) => lower.includes(item));
  const materialSwitchHit = ["换一套", "新素材", "再生成", "重新生成", "再来一套"].some((item) => lower.includes(item));
  const examListeningHit = /雅思|托福|四级|六级|高考|考研|ielts|toefl/i.test(raw) &&
    /听力|listening|练|训练|题|practice|training|test/i.test(raw);
  return materialSwitchHit || (listeningHit && creationHit) || examListeningHit;
}

function chatTitleFromNeed(need) {
  const titleSeed = compactText(String(need || "").replace(/[。！？!?]+$/g, ""), 28) || "英语问题";
  return `${titleSeed}问答`;
}

function cleanChatTitle(rawTitle, fallbackTitle) {
  const title = compactText(rawTitle, 60);
  if (!title) return fallbackTitle;
  if (/听力|素材|题目|音频|训练|生成|listening|material|quiz|audio|training/i.test(title)) return fallbackTitle;
  return title;
}

function cleanChatSummary(rawSummary, fallbackSummary) {
  const summary = compactText(rawSummary, 260);
  if (!summary) return fallbackSummary;
  if (/生成.*(听力|素材|题目|音频)|听力训练|听力素材|练习卡片|listening practice|listening material|audio material/i.test(summary)) {
    return fallbackSummary;
  }
  return summary;
}

function fallbackPlan(need) {
  const text = String(need || "");
  if (!isListeningPracticeNeed(text)) {
    const voice = voiceDirectivesFromText("");
    return {
      title: chatTitleFromNeed(text),
      summary: "",
      contentType: "chat",
      materialPrompt: "",
      difficulty: "普通",
      speechRate: "medium",
      voiceGender: voice.voiceGender,
      voiceProfile: voice.voiceProfile,
      pitch: voice.pitch,
      accent: voice.accent,
      tone: voice.tone,
      rationale: "普通英语问答工作区。",
      steps: defaultSteps("chat")
    };
  }
  const contentType = normalizeContentType(text);
  const difficulty = normalizeDifficulty(text);
  const speechRate = normalizeSpeechRate(text);
  const voice = voiceDirectivesFromText(text);
  const titleSeed = compactText(text, 22) || "英语听力闭环";
  return {
    title: `${titleSeed}工作区`,
    summary: "已根据需求建立听力闭环，先生成素材，再用答题结果驱动分析、词句复练和复习计划。",
    contentType,
    materialPrompt: compactText(text, 240) || "日常英语听力训练",
    difficulty,
    speechRate,
    voiceGender: voice.voiceGender,
    voiceProfile: voice.voiceProfile,
    pitch: voice.pitch,
    accent: voice.accent,
    tone: voice.tone,
    rationale: "使用本地规则兜底生成工作区计划。",
    steps: defaultSteps(contentType)
  };
}

function defaultSteps(contentType) {
  if (contentType === "chat") {
    return [
      {
        id: "chat",
        title: "英语问答",
        description: "围绕首个问题继续讲解、翻译、辨析和扩展追问。",
        status: "running"
      }
    ];
  }
  return [
    {
      id: "material",
      title: contentType === "article" ? "生成文章听力" : "生成对话听力",
      description: "根据用户需求生成听力脚本、音频、题目与解析。",
      status: "running"
    },
    {
      id: "practice",
      title: "完成听力练习",
      description: "用户听音频并完成答题，核对后由 Agent 接管下一步。",
      status: "pending"
    },
    {
      id: "analysis",
      title: "AI 错因分析",
      description: "Agent 根据题目、答案和原文定位薄弱点。",
      status: "pending"
    },
    {
      id: "review",
      title: "生成词句与计划",
      description: "把薄弱词句、复听任务和间隔复习写入后续工作流。",
      status: "pending"
    }
  ];
}

async function buildWorkspacePlan(need) {
  const cleanNeed = compactText(need, 800);
  if (!settings.mimoApiKey) return fallbackPlan(cleanNeed);
  const fallback = fallbackPlan(cleanNeed);
  const raw = await callMimoText([
    {
      role: "system",
      content: `你是 ListenE 移动端英语学习 Agent 的工作区命名与初始化规划器。用户第一次说话可能只是问英语问题，也可能是在要求生成听力训练。你只判断首句的初始意图来生成标题和初始建议，不要把工作区固定成某一种全局模式。
只返回 JSON：
{
  "initialIntent": "chat 或 listening_practice，仅表示首句初始意图，不是工作区全局类型",
  "title": "短工作区标题",
  "summary": "一句话说明这个工作区如何帮助用户",
  "contentType": "chat、dialogue 或 article，仅作为首句/首套素材元数据",
  "materialPrompt": "仅 initialIntent=listening_practice 时填写用于生成第一套听力素材的主题/场景；initialIntent=chat 时必须为空",
  "difficulty": "简单/普通/困难",
  "speechRate": "slow/medium/fast",
  "voiceGender": "male/female",
  "voiceProfile": "完整音色描述，如 Female gentle low-pitched British tutor voice；没有指定则给自然英语声音",
  "pitch": "low/high/medium 或空字符串",
  "accent": "british/american/australian/canadian 或空字符串",
  "tone": "gentle/warm/calm/energetic/serious/professional 或空字符串",
  "rationale": "为什么这样安排",
  "steps": [
    {"id":"material","title":"生成听力素材","description":"...","status":"running"},
    {"id":"practice","title":"完成听力练习","description":"...","status":"pending"},
    {"id":"analysis","title":"AI 错因分析","description":"...","status":"pending"},
    {"id":"review","title":"词句复练与计划","description":"...","status":"pending"}
  ]
}
规则：
- initialIntent=chat：首句是在问英语单词、句子、语法、翻译、表达区别、听力技巧、学习方法，或只是给出一个知识点让你解释。此时不要生成首套听力素材。
- initialIntent=listening_practice：只有首句明确要求生成听力训练、听力素材、音频、题目、习题、卡片、来一套、换一套、再生成，或明确说要雅思/托福/考试听力练习时才使用。
- 普通问题如“present perfect grammar”“现在完成时怎么用”“translate this sentence”“affect 和 effect 区别”必须是 initialIntent=chat。
- initialIntent=chat 时 title 要概括问题主题，不要包含“听力训练”“素材”“题目”“生成”；contentType 必须是 chat；materialPrompt 必须为空；steps 只返回一个 chat 步骤。
- initialIntent=listening_practice 时才给首套听力素材建议。
- 这些字段只初始化工作区标题、首句回复和首套素材，不限制后续消息。同一个工作区后续可以按用户每条消息继续答疑、生成新素材或组装练习卡片。
- contentType 只能是 chat、dialogue 或 article。
- 用户要场景互动、面试、旅行、日常会话时选 dialogue。
- 用户要新闻、讲座、主题文章、长篇输入时选 article。
- 如果用户指定音色、音调、口音或语气，例如温柔低音女声、英音、美音、沉稳男声，必须结构化填写 voiceProfile/pitch/accent/tone/voiceGender；语速填写 speechRate。
- 不要要求用户手动选择工具；所有后续工具由 Agent 内部调度。`
    },
    { role: "user", content: cleanNeed }
  ], { temperature: 0.25, maxTokens: 1200 });

  const rawMode = isListeningPracticeNeed(cleanNeed) ? "listening_practice" : "chat";
  const contentType = rawMode === "chat" ? "chat" : normalizePracticeContentType(raw.contentType);
  const modeFallback = rawMode === "chat" ? fallbackPlan(cleanNeed) : (fallback.contentType === "chat" ? listeningFallbackPlan(cleanNeed) : fallback);
  const voice = voiceDirectivesFromText(cleanNeed, {
    voiceGender: raw.voiceGender,
    voiceProfile: raw.voiceProfile,
    pitch: raw.pitch,
    accent: raw.accent,
    tone: raw.tone
  });
  return {
    title: contentType === "chat"
      ? cleanChatTitle(raw.title, modeFallback.title)
      : (compactText(raw.title, 60) || modeFallback.title),
    summary: contentType === "chat"
      ? cleanChatSummary(raw.summary, modeFallback.summary)
      : (compactText(raw.summary, 260) || modeFallback.summary),
    contentType,
    materialPrompt: contentType === "chat" ? "" : (compactText(raw.materialPrompt || cleanNeed, 320) || "英语听力训练"),
    difficulty: normalizeDifficulty(raw.difficulty),
    speechRate: normalizeSpeechRate(raw.speechRate),
    voiceGender: voice.voiceGender,
    voiceProfile: voice.voiceProfile,
    pitch: voice.pitch,
    accent: voice.accent,
    tone: voice.tone,
    rationale: compactText(raw.rationale, 240),
    steps: normalizeSteps(raw.steps, contentType)
  };
}

function normalizePracticeContentType(value) {
  const contentType = normalizeContentType(value);
  return contentType === "chat" ? "dialogue" : contentType;
}

function listeningFallbackPlan(need) {
  const text = String(need || "");
  const contentType = normalizePracticeContentType(text);
  const difficulty = normalizeDifficulty(text);
  const speechRate = normalizeSpeechRate(text);
  const voice = voiceDirectivesFromText(text);
  const titleSeed = compactText(text, 22) || "英语听力闭环";
  return {
    title: `${titleSeed}工作区`,
    summary: "已根据需求建立听力闭环，先生成素材，再用答题结果驱动分析、词句复练和复习计划。",
    contentType,
    materialPrompt: compactText(text, 240) || "日常英语听力训练",
    difficulty,
    speechRate,
    voiceGender: voice.voiceGender,
    voiceProfile: voice.voiceProfile,
    pitch: voice.pitch,
    accent: voice.accent,
    tone: voice.tone,
    rationale: "使用本地规则兜底生成工作区计划。",
    steps: defaultSteps(contentType)
  };
}

function normalizeSteps(steps, contentType) {
  const cleaned = asArray(steps)
    .map((step, index) => ({
      id: compactText(step?.id || `step_${index + 1}`, 40),
      title: compactText(step?.title, 80),
      description: compactText(step?.description, 180),
      status: ["done", "running", "pending"].includes(String(step?.status || "").toLowerCase())
        ? String(step.status).toLowerCase()
        : index === 0 ? "running" : "pending"
    }))
    .filter((step) => step.title);
  if (contentType === "chat") return defaultSteps("chat");
  return cleaned.length >= 3 ? cleaned.slice(0, 6) : defaultSteps(contentType);
}

function workspaceFromRow(row) {
  const data = row?.data || {};
  return normalizeWorkspaceProgress({
    ...data,
    ...normalizeWorkspaceMemoryFields(data),
    createdAt: Number(data.createdAt || row?.created_at || Date.now()),
    updatedAt: Number(data.updatedAt || row?.updated_at || data.createdAt || Date.now())
  });
}

async function listWorkspaces(user) {
  if (!user) return [];
  const result = await query(
    `SELECT data, created_at, updated_at
     FROM workspaces
     WHERE user_id = $1
     ORDER BY updated_at DESC
     LIMIT 200`,
    [user.id]
  );
  return result.rows.map(workspaceFromRow);
}

async function getWorkspace(user, id) {
  if (!user) return null;
  const result = await query(
    `SELECT data, created_at, updated_at FROM workspaces WHERE user_id = $1 AND id = $2`,
    [user.id, id]
  );
  return result.rows[0] ? workspaceFromRow(result.rows[0]) : null;
}

async function saveWorkspace(user, workspace) {
  const normalizedWorkspace = {
    ...workspace,
    ...normalizeWorkspaceMemoryFields(workspace)
  };
  if (!user) return { workspace: normalizedWorkspace, persisted: false };
  await query(
    `INSERT INTO workspaces (user_id, id, data, created_at, updated_at)
     VALUES ($1, $2, $3::jsonb, $4, $5)
     ON CONFLICT (user_id, id) DO UPDATE SET
       data = EXCLUDED.data,
       updated_at = EXCLUDED.updated_at`,
    [
      user.id,
      normalizedWorkspace.id,
      JSON.stringify(normalizedWorkspace),
      Number(normalizedWorkspace.createdAt || Date.now()),
      Number(normalizedWorkspace.updatedAt || Date.now())
    ]
  );
  return { workspace: normalizedWorkspace, persisted: true };
}

function stepsForCurrentStep(steps, currentStep) {
  const list = asArray(steps);
  const activeIndex = Math.max(0, list.findIndex((step) => step?.id === currentStep));
  return list.map((step, index) => ({
    ...step,
    status: index < activeIndex ? "done" : index === activeIndex ? "running" : "pending"
  }));
}

function normalizeWorkspaceProgress(workspace = {}) {
  const plan = workspace.plan || {};
  const steps = asArray(plan.steps).length ? plan.steps : defaultSteps(plan.contentType || "dialogue");
  const currentStep = compactText(workspace.currentStep, 40) || steps[0]?.id || (plan.contentType === "chat" ? "chat" : "material");
  const memoryFields = normalizeWorkspaceMemoryFields(workspace);
  return {
    ...workspace,
    ...memoryFields,
    currentStep,
    plan: {
      ...plan,
      steps: stepsForCurrentStep(steps, currentStep)
    }
  };
}

async function createWorkspace(body = {}, user = null) {
  const need = compactText(body.need || body.goal || body.prompt, 800);
  if (!need) throw new Error("need is required");
  const now = Date.now();
  const plan = await buildWorkspacePlan(need);
  const currentStep = plan.contentType === "chat" ? "chat" : "material";
  const workspace = {
    id: compactText(body.id, 120) || `ws_${now}_${crypto.randomUUID().slice(0, 8)}`,
    title: plan.title,
    need,
    summary: plan.summary,
    status: "active",
    currentStep,
    createdAt: now,
    updatedAt: now,
    plan,
    linkedRecordIds: [],
    linkedContainerIds: [],
    linkedPlanTaskIds: [],
    memorySummary: "",
    memory: [],
    events: [{
      id: crypto.randomUUID(),
      type: "workspace_created",
      title: "创建工作区",
      description: plan.summary,
      createdAt: now
    }]
  };
  return saveWorkspace(user, workspace);
}

async function updateWorkspace(id, patch = {}, user = null) {
  const existing = await getWorkspace(user, id);
  const now = Date.now();
  const currentStep = compactText(patch.currentStep, 40) || existing?.currentStep || "material";
  const base = existing || {
    id,
    title: "英语学习工作区",
    need: "",
    summary: "",
    status: "active",
    createdAt: now,
    plan: { contentType: "dialogue", steps: defaultSteps("dialogue") },
    linkedRecordIds: [],
    linkedContainerIds: [],
    linkedPlanTaskIds: [],
    memorySummary: "",
    memory: [],
    events: []
  };
  const next = normalizeWorkspaceProgress({
    ...base,
    ...patch,
    id,
    status: compactText(patch.status, 40) || base.status || "active",
    currentStep,
    title: compactText(patch.title, 80) || base.title || "英语学习工作区",
    summary: compactText(patch.summary, 260) || base.summary || "",
    updatedAt: now,
    plan: {
      ...(base.plan || {}),
      ...(patch.plan || {})
    },
    linkedRecordIds: addUnique(base.linkedRecordIds, patch.linkedRecordIds || []),
    linkedContainerIds: addUnique(base.linkedContainerIds, patch.linkedContainerIds || []),
    linkedPlanTaskIds: addUnique(base.linkedPlanTaskIds, patch.linkedPlanTaskIds || []),
    memorySummary: compactText(patch.memorySummary ?? base.memorySummary, 1500),
    memory: mergeWorkspaceMemory(base.memory, patch.memory || [], now),
    events: asArray(patch.events).length ? asArray(patch.events).concat(asArray(base.events)) : asArray(base.events)
  });
  return saveWorkspace(user, next);
}

async function deleteWorkspace(id, user = null) {
  if (!user) return { deleted: true, workspaceId: id, persisted: false };
  const library = await deleteLibraryItemsForWorkspace(id, user);
  await query(`DELETE FROM workspaces WHERE user_id = $1 AND id = $2`, [user.id, id]);
  return { deleted: true, workspaceId: id, persisted: true, libraryDeleted: library.deleted };
}

async function recordWorkspaceEvent(id, body = {}, user = null) {
  const now = Date.now();
  const event = {
    id: crypto.randomUUID(),
    type: compactText(body.type || "workspace_event", 60),
    title: compactText(body.title, 120),
    description: compactText(body.description, 260),
    recordId: compactText(body.recordId, 120),
    containerId: compactText(body.containerId, 120),
    taskId: compactText(body.taskId, 120),
    targetKind: compactText(body.targetKind, 60),
    targetId: compactText(body.targetId, 120),
    createdAt: now
  };
  const existing = await getWorkspace(user, id);
  const currentStep = compactText(body.currentStep, 40) || inferStepFromEvent(event.type, "material");
  const memoryAdditions = extractMemoryEntriesFromEvent(event, body, now);
  const workspace = normalizeWorkspaceProgress({
    ...(existing || {}),
    id,
    currentStep,
    status: compactText(body.status, 40) || existing?.status || "active",
    title: existing?.title || "英语学习工作区",
    summary: existing?.summary || "",
    plan: {
      ...(existing?.plan || {}),
      steps: stepsForCurrentStep(existing?.plan?.steps || defaultSteps("dialogue"), currentStep)
    },
    linkedRecordIds: addUnique(existing?.linkedRecordIds, [event.recordId]),
    linkedContainerIds: addUnique(existing?.linkedContainerIds, [event.containerId]),
    linkedPlanTaskIds: addUnique(existing?.linkedPlanTaskIds, [event.taskId]),
    memorySummary: existing?.memorySummary || "",
    memory: mergeWorkspaceMemory(existing?.memory, memoryAdditions, now),
    events: [event].concat(asArray(existing?.events)).slice(0, 200),
    createdAt: existing?.createdAt || now,
    updatedAt: now
  });
  const saved = await saveWorkspace(user, workspace);
  // #4B：把本次分析的稳定弱点回灌到统一用户模型（跨功能画像）。best-effort，绝不影响事件持久化。
  const userModelWeaknesses = uniqueCompactList([
    ...asArray(body.weakPoints),
    ...asArray(body.result?.weakPoints),
    ...asArray(body.analysis?.weakPoints)
  ], 10, 220);
  if (user && userModelWeaknesses.length) {
    try {
      await updateUserModel(user, { weaknesses: userModelWeaknesses });
    } catch (error) {
      logger.warn("user_model weakness merge failed", { detail: error?.message || String(error) });
    }
  }
  return { event, workspace: saved.workspace, persisted: saved.persisted };
}

async function listWorkspaceMessages(id, user = null) {
  if (!user) return { messages: [], persisted: false };
  const result = await query(
    `SELECT messages FROM workspace_messages WHERE user_id = $1 AND workspace_id = $2`,
    [user.id, id]
  );
  return { messages: result.rows[0]?.messages || [], persisted: true };
}

async function saveWorkspaceMessages(id, messages = [], user = null) {
  if (!user) return { messages: asArray(messages).slice(-80), persisted: false };
  const workspace = await getWorkspace(user, id);
  if (!workspace) throw new Error("workspace not found");
  const now = Date.now();
  const cleaned = asArray(messages)
    .filter((message) => message && typeof message === "object")
    .slice(-80);
  // 增量压缩：只摘要「刚移出保留尾部且未摘要过」的旧消息，避免每轮重复调用摘要模型、重复折叠同一批。
  const { toSummarize, throughId } = selectWorkspaceSummaryBatch(workspace.memorySummaryThroughId, cleaned, 24);
  const memorySummary = toSummarize.length > 0
    ? await summarizeWorkspaceMessagesWithModel(workspace.memorySummary, toSummarize)
    : compactText(workspace.memorySummary, 1500);
  const memory = mergeWorkspaceMemory(
    workspace.memory,
    extractExplicitMemoryEntriesFromMessages(cleaned, now),
    now
  );
  await query(
    `INSERT INTO workspace_messages (user_id, workspace_id, messages, updated_at)
     VALUES ($1, $2, $3::jsonb, $4)
     ON CONFLICT (user_id, workspace_id) DO UPDATE SET
       messages = EXCLUDED.messages,
       updated_at = EXCLUDED.updated_at`,
    [user.id, id, JSON.stringify(cleaned), now]
  );
  const saved = await saveWorkspace(user, {
    ...workspace,
    memorySummary,
    memorySummaryThroughId: throughId,
    memory,
    updatedAt: now
  });
  return { messages: cleaned, workspace: saved.workspace, persisted: true };
}

function addUnique(existing, additions) {
  const seen = new Set();
  const out = [];
  for (const item of [...asArray(existing), ...asArray(additions)]) {
    const value = compactText(item, 120);
    if (!value || seen.has(value)) continue;
    seen.add(value);
    out.push(value);
  }
  return out;
}

function inferStepFromEvent(type, fallback) {
  if (/record|material/i.test(type)) return "practice";
  if (/answer|practice/i.test(type)) return "analysis";
  if (/analysis/i.test(type)) return "review";
  if (/container|plan|review/i.test(type)) return "review";
  return fallback || "material";
}

module.exports = {
  listWorkspaces,
  createWorkspace,
  updateWorkspace,
  deleteWorkspace,
  recordWorkspaceEvent,
  listWorkspaceMessages,
  saveWorkspaceMessages,
  __test: {
    isListeningPracticeNeed,
    normalizeWorkspaceMemoryEntry,
    mergeWorkspaceMemory,
    summarizeWorkspaceMessages,
    summarizeWorkspaceMessagesWithModel,
    selectWorkspaceSummaryBatch,
    extractExplicitMemoryEntriesFromMessages,
    extractMemoryEntriesFromEvent
  }
};
