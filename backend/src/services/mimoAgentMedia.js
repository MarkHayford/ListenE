const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { settings } = require("../config");
const { mimoBackoffDelayMs, isRetryableMimoStatus, parseRetryAfterMs } = require("./mimoCore");
const { compressFinalAudio } = require("./mimoTts");
// JSON 抽取/修复已抽到独立模块（jsonRepair.js）；这里 re-export 以保持既有调用方不变。
const { extractJsonFromContent } = require("./jsonRepair");

const MAX_ATTACHMENT_BYTES = 18 * 1024 * 1024;
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));

function mimoHeaders() {
  return {
    "Content-Type": "application/json",
    "api-key": settings.mimoApiKey,
    Authorization: `Bearer ${settings.mimoApiKey}`
  };
}

function normalizeAttachment(item = {}) {
  const mimeType = String(item.mimeType || item.contentType || "").trim().toLowerCase();
  const base64 = String(item.base64 || item.data || "").replace(/^data:[^;]+;base64,/, "").trim();
  const name = String(item.name || item.fileName || "attachment").trim();
  if (!base64 || !mimeType) return null;
  const bytes = Buffer.byteLength(base64, "base64");
  if (bytes <= 0 || bytes > MAX_ATTACHMENT_BYTES) {
    throw new Error(`文件过大或无效：${name}`);
  }
  const type = mimeType.startsWith("image/")
    ? "image"
    : mimeType.startsWith("audio/")
      ? "audio"
      : mimeType.startsWith("video/")
        ? "video"
        : "file";
  if (type === "file" && !isSupportedDocumentMimeType(mimeType)) {
    throw new Error(`暂不支持的文件类型：${mimeType}`);
  }
  return { type, mimeType, base64, name, bytes, textPreview: type === "file" ? documentTextPreview(base64, mimeType) : "" };
}

function hasMultimodalAttachments(attachments = []) {
  return attachments.some((item) => ["image", "audio", "video"].includes(item.type));
}

function attachmentToMessagePart(attachment) {
  const dataUrl = `data:${attachment.mimeType};base64,${attachment.base64}`;
  if (attachment.type === "image") {
    return { type: "image_url", image_url: { url: dataUrl } };
  }
  if (attachment.type === "video") {
    return { type: "video_url", video_url: { url: dataUrl } };
  }
  if (attachment.type === "file") {
    return {
      type: "text",
      text: attachment.textPreview
        ? `文档内容预览：\n${attachment.textPreview}`
        : `这是一个文档附件。当前只能读取文本类文档内容；二进制文档请根据文件名、类型和用户描述处理。`
    };
  }
  return {
    type: "input_audio",
    input_audio: {
      data: dataUrl
    }
  };
}

function buildUserContentWithAttachments(message, attachments = []) {
  const parts = [{ type: "text", text: String(message || "") }];
  attachments.forEach((attachment) => {
    parts.push({ type: "text", text: `附件：${attachment.name} (${attachment.mimeType})` });
    parts.push(attachmentToMessagePart(attachment));
  });
  return parts;
}

function isSupportedDocumentMimeType(mimeType) {
  return mimeType.startsWith("text/") ||
    mimeType === "application/json" ||
    mimeType === "application/pdf" ||
    mimeType === "application/msword" ||
    mimeType === "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
    mimeType === "application/vnd.ms-excel" ||
    mimeType === "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ||
    mimeType === "application/vnd.ms-powerpoint" ||
    mimeType === "application/vnd.openxmlformats-officedocument.presentationml.presentation";
}

function documentTextPreview(base64, mimeType) {
  if (!mimeType.startsWith("text/") && mimeType !== "application/json") return "";
  try {
    return Buffer.from(base64, "base64").toString("utf8").replace(/\u0000/g, "").slice(0, 12000);
  } catch (_) {
    return "";
  }
}

async function callMimoChatRaw(messages, { model, temperature = 0.35, maxTokens = 1200, json = true, responseFormat = null, extra = {} } = {}) {
  if (!settings.mimoApiKey) throw new Error("未配置 MIMO_API_KEY");
  const body = { model, messages, temperature, ...extra };
  // responseFormat 优先：可传 { type: "json_schema", json_schema: {...} } 启用结构化输出（需模型端支持，如 GPT）。
  // 不传时回退到原有 json_object 行为，保持向后兼容。
  if (responseFormat) body.response_format = responseFormat;
  else if (json) body.response_format = { type: "json_object" };
  if (maxTokens) body.max_tokens = maxTokens;
  const url = `${settings.mimoBaseUrl.replace(/\/+$/, "")}/chat/completions`;
  const maxRetries = Math.max(0, Number(settings.mimoMaxRetries) || 0);
  const baseMs = Math.max(0, Number(settings.mimoRetryBaseMs) || 0);
  const timeoutMs = Math.max(5000, Number(settings.mimoTimeoutMs) || 60000);

  let attempt = 0;
  while (true) {
    let response;
    try {
      response = await fetch(url, {
        method: "POST",
        headers: mimoHeaders(),
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(timeoutMs)
      });
    } catch (networkError) {
      if (attempt < maxRetries) {
        await sleep(mimoBackoffDelayMs(attempt, baseMs));
        attempt += 1;
        continue;
      }
      throw new Error(`MiMo 网络请求失败：${networkError?.message || networkError}`);
    }
    const text = await response.text();
    if (!response.ok) {
      if (isRetryableMimoStatus(response.status) && attempt < maxRetries) {
        const retryAfter = parseRetryAfterMs(response.headers);
        await sleep(retryAfter != null ? retryAfter : mimoBackoffDelayMs(attempt, baseMs));
        attempt += 1;
        continue;
      }
      let detail = `MiMo API error (${response.status})`;
      try {
        const errJson = JSON.parse(text);
        detail = errJson?.error?.message || errJson?.detail || errJson?.message || detail;
      } catch (_) {
        if (text) detail = text.slice(0, 240);
      }
      throw new Error(detail);
    }
    return JSON.parse(text);
  }
}

// 流式聊天调用：stream:true 逐 chunk 读取 delta.content（忽略 reasoning_content），
// 每段增量回调 onContentDelta，同时拼出完整 content，最终返回与 callMimoChatRaw 形状兼容的对象，
// 便于下游 JSON 解析零改动。流式失败由调用方自行回退非流式。
async function callMimoChatStream(messages, { model, temperature = 0.35, maxTokens = 1200, json = true, extra = {} } = {}, onContentDelta) {
  if (!settings.mimoApiKey) throw new Error("未配置 MIMO_API_KEY");
  const body = { model, messages, temperature, stream: true, ...extra };
  if (json) body.response_format = { type: "json_object" };
  if (maxTokens) body.max_tokens = maxTokens;
  const url = `${settings.mimoBaseUrl.replace(/\/+$/, "")}/chat/completions`;
  const timeoutMs = Math.max(5000, Number(settings.mimoTimeoutMs) || 60000);
  const response = await fetch(url, {
    method: "POST",
    headers: mimoHeaders(),
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs)
  });
  if (!response.ok || !response.body) {
    const text = await response.text().catch(() => "");
    let detail = `MiMo API error (${response.status})`;
    try {
      const errJson = JSON.parse(text);
      detail = errJson?.error?.message || errJson?.detail || errJson?.message || detail;
    } catch (_) {
      if (text) detail = text.slice(0, 240);
    }
    throw new Error(detail);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let full = "";
  let finishReason = null;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let idx;
    while ((idx = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, idx).replace(/\r$/, "");
      buffer = buffer.slice(idx + 1);
      if (!line.startsWith("data:")) continue;
      const dataStr = line.slice(5).trim();
      if (!dataStr || dataStr === "[DONE]") continue;
      let obj;
      try { obj = JSON.parse(dataStr); } catch (_) { continue; }
      const choice = obj?.choices?.[0];
      const delta = choice?.delta?.content;
      if (typeof delta === "string" && delta) {
        full += delta;
        if (typeof onContentDelta === "function") {
          try { onContentDelta(delta); } catch (_) { /* 下游写失败不阻断生成 */ }
        }
      }
      if (choice?.finish_reason) finishReason = choice.finish_reason;
    }
  }
  if (!full) throw new Error("MiMo 流式响应为空");
  return { choices: [{ message: { content: full }, finish_reason: finishReason }] };
}

async function transcribeAudio({ base64, mimeType = "audio/wav", language = "auto" } = {}) {
  const attachment = normalizeAttachment({ base64, mimeType, name: "voice" });
  if (!attachment || attachment.type !== "audio") throw new Error("语音数据无效");
  const data = await callMimoChatRaw([
    {
      role: "user",
      content: [
        attachmentToMessagePart(attachment)
      ]
    }
  ], {
    model: settings.mimoAsrModel,
    temperature: 0,
    maxTokens: 800,
    json: false,
    extra: {
      asr_options: {
        language: String(language || "auto")
      }
    }
  });
  const content = data.choices?.[0]?.message?.content;
  const text = String(content || "").trim();
  if (!text) return { text: "" };
  const jsonText = runJsonParse(text);
  if (jsonText) return { text: jsonText };
  return { text };
}

function runJsonParse(text) {
  try {
    const obj = extractJsonFromContent(text);
    return String(obj.text || obj.transcript || "").trim();
  } catch (_) {
    return "";
  }
}

function clampScore(value, fallback = 0) {
  const n = Math.round(Number(value));
  if (!Number.isFinite(n)) return fallback;
  return Math.max(0, Math.min(100, n));
}

// 音素级发音细节：把模型给的「哪个词、哪个音发错了」结构化。仅保留有问题(含音素或提示)或带音标的词，各项裁剪防滥用。
function normalizePronunciationDetail(value) {
  const o = value && typeof value === "object" ? value : {};
  const summary = String(o.summary || "").replace(/\s+/g, " ").trim().slice(0, 200);
  const rawWords = Array.isArray(o.words) ? o.words : [];
  const words = rawWords
    .map((w) => {
      const word = String(w?.word || "").trim().slice(0, 40);
      const ipa = String(w?.ipa || w?.phonetic || "").trim().slice(0, 60);
      const rawScore = clampScore(w?.score, -1);
      const rawIssues = Array.isArray(w?.issues) ? w.issues : [];
      const issues = rawIssues
        .map((it) => ({
          phoneme: String(it?.phoneme || it?.sound || "").trim().slice(0, 24),
          heard: String(it?.heard || it?.sounded || it?.actual || "").trim().slice(0, 24),
          tip: String(it?.tip || it?.note || it?.fix || "").replace(/\s+/g, " ").trim().slice(0, 140)
        }))
        .filter((it) => it.phoneme || it.tip)
        .slice(0, 3);
      return { word, ipa, score: rawScore < 0 ? 0 : rawScore, issues };
    })
    .filter((w) => w.word && (w.issues.length > 0 || w.ipa))
    .slice(0, 8);
  return { summary, words };
}

function normalizeSpeakingAssessment(obj, { durationMs = 0 } = {}) {
  const o = obj && typeof obj === "object" ? obj : {};
  const rawScores = o.scores && typeof o.scores === "object" ? o.scores : {};
  const scores = {
    pronunciation: clampScore(rawScores.pronunciation),
    fluency: clampScore(rawScores.fluency),
    grammar: clampScore(rawScores.grammar),
    vocabulary: clampScore(rawScores.vocabulary),
    content: clampScore(rawScores.content)
  };
  const transcript = String(o.transcript || o.text || "").trim();
  let overall = clampScore(o.overall, -1);
  if (overall < 0) {
    const vals = Object.values(scores);
    overall = Math.round(vals.reduce((a, b) => a + b, 0) / vals.length);
  }
  let wpm = Math.round(Number(o.wpm));
  if (!Number.isFinite(wpm) || wpm <= 0) {
    const words = transcript ? transcript.split(/\s+/).filter(Boolean).length : 0;
    const mins = Number(durationMs) > 0 ? Number(durationMs) / 60000 : 0;
    wpm = mins > 0 ? Math.round(words / mins) : 0;
  }
  const strList = (value, max) =>
    Array.isArray(value)
      ? value.map((x) => String(x || "").trim()).filter(Boolean).slice(0, max)
      : [];
  const corrections = Array.isArray(o.corrections)
    ? o.corrections
        .map((c) => ({
          from: String(c?.from || "").trim(),
          to: String(c?.to || "").trim(),
          note: String(c?.note || "").trim()
        }))
        .filter((c) => c.from || c.to)
        .slice(0, 4)
    : [];
  return {
    transcript,
    overall,
    scores,
    wpm: Math.max(0, wpm),
    highlights: strList(o.highlights, 3),
    improvements: strList(o.improvements, 3),
    corrections,
    pronunciationDetail: normalizePronunciationDetail(o.pronunciationDetail || o.pronunciation_detail),
    sampleAnswer: String(o.sampleAnswer || "").trim(),
    comment: String(o.comment || "").trim()
  };
}

// 口语评测：把用户录音直接交给多模态模型「听」，给出转写 + 五维评分（发音/流利度/语法/词汇/内容）+ 反馈 + 范例答案。
async function assessSpeaking({ base64, mimeType = "audio/wav", prompt = "", durationMs = 0, language = "en" } = {}) {
  const attachment = normalizeAttachment({ base64, mimeType, name: "speaking" });
  if (!attachment || attachment.type !== "audio") throw new Error("语音数据无效");
  const task = String(prompt || "").trim().slice(0, 800);
  const seconds = Math.max(0, Math.round(Number(durationMs || 0) / 100) / 10);
  const instruction = [
    "You are a strict but encouraging English speaking examiner (IELTS / CEFR style).",
    "Listen to the student's spoken-English audio answer and assess it.",
    task ? `The speaking task was: "${task}"` : "There was no fixed task; assess the general spoken English.",
    seconds ? `Recording length: about ${seconds} seconds.` : "",
    "Step 1: transcribe EXACTLY what you hear, verbatim, keeping any mistakes.",
    "Step 2: score each dimension 0-100. Judge pronunciation and fluency from the AUDIO itself (accent, clarity, intonation, stress, pace, pauses, filler words). Judge grammar/vocabulary/content from the transcript and the task.",
    "Step 3: do a PHONEME-LEVEL pronunciation diagnosis from the audio: find the specific words the student actually mispronounced and, for each, the exact wrong sound (target IPA phoneme vs what it sounded like).",
    "Return ONLY a JSON object with these keys:",
    '{"transcript": string, "overall": number, "scores": {"pronunciation": number, "fluency": number, "grammar": number, "vocabulary": number, "content": number}, "wpm": number, "highlights": [string], "improvements": [string], "corrections": [{"from": string, "to": string, "note": string}], "pronunciationDetail": {"summary": string, "words": [{"word": string, "ipa": string, "score": number, "issues": [{"phoneme": string, "heard": string, "tip": string}]}]}, "sampleAnswer": string, "comment": string}',
    "highlights/improvements: <=3 items each, written in concise Simplified Chinese. corrections: <=4 items, 'from' is the student's wrong phrase, 'to' is the fix, 'note' is a short Chinese reason. sampleAnswer: a better English answer of 2-4 sentences. comment: one warm sentence in Simplified Chinese.",
    "pronunciationDetail: summary = one short Simplified-Chinese sentence naming the main sound problems (e.g. θ/ð、元音长短、词重音). words = up to 6 words the student REALLY mispronounced; per word: ipa = correct IPA (with slashes), score = that word's pronunciation 0-100, issues (<=3) = the specific wrong sounds where phoneme is the target IPA symbol (e.g. \"/θ/\"), heard is what it actually sounded like (e.g. \"/s/\"), tip is a <=1 short Simplified-Chinese fix (mouth/tongue cue). If pronunciation is basically fine, return words as [] and a brief positive summary. Never invent errors that are not in the audio.",
    "If the audio is empty, silent or unintelligible, set overall and all scores low and explain kindly in comment."
  ]
    .filter(Boolean)
    .join("\n");

  const data = await callMimoChatRaw(
    [
      {
        role: "user",
        content: [{ type: "text", text: instruction }, attachmentToMessagePart(attachment)]
      }
    ],
    {
      model: settings.mimoMultimodalModel,
      temperature: 0.2,
      maxTokens: 1900,
      json: true,
      extra: { asr_options: { language: String(language || "en") } }
    }
  );
  const content = data.choices?.[0]?.message?.content;
  const obj = extractJsonFromContent(content);
  return normalizeSpeakingAssessment(obj, { durationMs });
}

function normalizeWritingAssessment(obj, { wordCount = 0 } = {}) {
  const o = obj && typeof obj === "object" ? obj : {};
  const rawScores = o.scores && typeof o.scores === "object" ? o.scores : {};
  const scores = {
    taskAchievement: clampScore(rawScores.taskAchievement ?? rawScores.task ?? rawScores.content),
    coherence: clampScore(rawScores.coherence ?? rawScores.organization ?? rawScores.structure),
    vocabulary: clampScore(rawScores.vocabulary ?? rawScores.lexical),
    grammar: clampScore(rawScores.grammar ?? rawScores.accuracy)
  };
  let overall = clampScore(o.overall, -1);
  if (overall < 0) {
    const vals = Object.values(scores);
    overall = Math.round(vals.reduce((a, b) => a + b, 0) / vals.length);
  }
  const strList = (value, max) =>
    Array.isArray(value) ? value.map((x) => String(x || "").trim()).filter(Boolean).slice(0, max) : [];
  const corrections = Array.isArray(o.corrections)
    ? o.corrections
        .map((c) => ({
          from: String(c?.from || "").trim(),
          to: String(c?.to || "").trim(),
          note: String(c?.note || "").trim()
        }))
        .filter((c) => c.from || c.to)
        .slice(0, 5)
    : [];
  let words = Math.round(Number(o.wordCount));
  if (!Number.isFinite(words) || words <= 0) words = Math.max(0, Math.round(Number(wordCount) || 0));
  return {
    overall,
    scores,
    wordCount: words,
    highlights: strList(o.highlights, 3),
    improvements: strList(o.improvements, 3),
    corrections,
    sampleAnswer: String(o.sampleAnswer || "").trim(),
    comment: String(o.comment || "").trim()
  };
}

// 写作评分（图表作文/作文）：把题目要求 + 图表数据摘要 + 学生作文交给文本模型评分。
async function assessWriting({ prompt = "", reference = "", essay = "" } = {}) {
  const text = String(essay || "").trim();
  if (!text) throw new Error("作文内容为空");
  const task = String(prompt || "").trim().slice(0, 600);
  const ref = String(reference || "").trim().slice(0, 1400);
  const wordCount = text.split(/\s+/).filter(Boolean).length;
  const instruction = [
    "You are a strict but encouraging English writing examiner (IELTS Task 1 / CEFR style).",
    task ? `Writing task: "${task}"` : "Assess the student's English writing.",
    ref ? `The chart/data the student should describe:\n${ref}` : "",
    `The student's essay has about ${wordCount} words.`,
    "Score each dimension 0-100: taskAchievement (does it cover the task and key data trends), coherence (organization & linking), vocabulary (range & accuracy), grammar (range & accuracy).",
    "Return ONLY a JSON object with keys:",
    '{"overall": number, "scores": {"taskAchievement": number, "coherence": number, "vocabulary": number, "grammar": number}, "wordCount": number, "highlights": [string], "improvements": [string], "corrections": [{"from": string, "to": string, "note": string}], "sampleAnswer": string, "comment": string}',
    "highlights/improvements: <=3 items each, concise Simplified Chinese. corrections: <=5 items, 'from' is the student's wrong phrase, 'to' is the fix, 'note' is a short Chinese reason. sampleAnswer: a strong model answer in English that fits the task and data. comment: one warm sentence in Simplified Chinese.",
    "If the essay is empty or off-topic, score low and explain kindly in comment."
  ]
    .filter(Boolean)
    .join("\n");
  const data = await callMimoChatRaw(
    [{ role: "user", content: `${instruction}\n\nSTUDENT ESSAY:\n${text.slice(0, 4000)}` }],
    { model: settings.mimoTextModel, temperature: 0.2, maxTokens: 1500, json: true }
  );
  const obj = extractJsonFromContent(data.choices?.[0]?.message?.content);
  return normalizeWritingAssessment(obj, { wordCount });
}

// 口语陪练 · 单轮：AI 扮演场景里的另一方，用一句自然的英文推进对话。
async function roleplayTurn({ scenario = "", history = [], userText = "" } = {}) {
  const scene = String(scenario || "").trim().slice(0, 200) || "free daily English conversation";
  const sys = [
    "You are a warm, patient English conversation partner for a Chinese learner.",
    `Role-play this scenario and stay fully in character as the OTHER person: "${scene}".`,
    "Reply with ONE natural, fairly short English turn (1-3 sentences) at an everyday, learnable level.",
    "Keep the conversation moving by usually ending with a simple question. Do NOT correct the learner mid-conversation; just respond naturally.",
    'Return ONLY a JSON object: {"reply": "your English line", "hint": "一句给学习者的中文小提示，建议可以怎么回答（可空）"}.'
  ].join("\n");
  const messages = [{ role: "system", content: sys }];
  (Array.isArray(history) ? history : []).slice(-12).forEach((h) => {
    const role = h && h.role === "assistant" ? "assistant" : "user";
    const content = String(h?.content || "").trim().slice(0, 600);
    if (content) messages.push({ role, content });
  });
  const learner = String(userText || "").trim().slice(0, 600);
  if (learner) messages.push({ role: "user", content: learner });
  if (messages.length === 1) {
    messages.push({ role: "user", content: "(The conversation starts now. Please greet me and open the scenario.)" });
  }
  const data = await callMimoChatRaw(messages, {
    model: settings.mimoTextModel,
    temperature: 0.6,
    maxTokens: 400,
    json: true
  });
  const obj = extractJsonFromContent(data.choices?.[0]?.message?.content);
  const reply = String(obj.reply || obj.text || "").trim();
  if (!reply) throw new Error("roleplay empty reply");
  return { reply: reply.slice(0, 600), hint: String(obj.hint || "").trim().slice(0, 200) };
}

function normalizeRoleplayFeedback(obj) {
  const o = obj && typeof obj === "object" ? obj : {};
  const s = o.scores && typeof o.scores === "object" ? o.scores : {};
  const scores = {
    fluency: clampScore(s.fluency),
    grammar: clampScore(s.grammar),
    vocabulary: clampScore(s.vocabulary ?? s.lexical),
    taskCompletion: clampScore(s.taskCompletion ?? s.task ?? s.content)
  };
  let overall = clampScore(o.overall, -1);
  if (overall < 0) {
    const vals = Object.values(scores);
    overall = Math.round(vals.reduce((a, b) => a + b, 0) / vals.length);
  }
  const strList = (v, max) => (Array.isArray(v) ? v.map((x) => String(x || "").trim()).filter(Boolean).slice(0, max) : []);
  const betterLines = Array.isArray(o.betterLines)
    ? o.betterLines
        .map((c) => ({ from: String(c?.from || "").trim(), to: String(c?.to || "").trim() }))
        .filter((c) => c.from || c.to)
        .slice(0, 5)
    : [];
  return {
    overall,
    scores,
    highlights: strList(o.highlights, 3),
    improvements: strList(o.improvements, 3),
    betterLines,
    comment: String(o.comment || "").trim()
  };
}

// 口语陪练 · 结束评分：对整段对话里学习者的表现打分并给改进版表达。
async function roleplayFeedback({ scenario = "", history = [] } = {}) {
  const turns = (Array.isArray(history) ? history : []).filter((h) => h && String(h.content || "").trim());
  const learnerTurns = turns.filter((h) => h.role !== "assistant");
  if (!learnerTurns.length) throw new Error("对话内容为空");
  const scene = String(scenario || "").trim().slice(0, 200) || "free daily English conversation";
  const transcript = turns
    .map((h) => (h.role === "assistant" ? "Partner: " : "Learner: ") + String(h.content).trim())
    .join("\n")
    .slice(0, 4000);
  const instruction = [
    "You are an encouraging English speaking examiner. Assess ONLY the Learner's turns in this role-play conversation.",
    `Scenario: "${scene}".`,
    "Score 0-100 for: fluency (natural flow & length), grammar, vocabulary (range & accuracy), taskCompletion (did the learner handle the scenario).",
    "Return ONLY a JSON object:",
    '{"overall": number, "scores": {"fluency": number, "grammar": number, "vocabulary": number, "taskCompletion": number}, "highlights": [string], "improvements": [string], "betterLines": [{"from": string, "to": string}], "comment": string}',
    "highlights/improvements: <=3 items each in concise Simplified Chinese. betterLines: <=5, 'from' = a learner line that could be better, 'to' = a more natural English version. comment: one warm sentence in Simplified Chinese."
  ].join("\n");
  const data = await callMimoChatRaw(
    [{ role: "user", content: `${instruction}\n\nCONVERSATION:\n${transcript}` }],
    { model: settings.mimoTextModel, temperature: 0.3, maxTokens: 1400, json: true }
  );
  const obj = extractJsonFromContent(data.choices?.[0]?.message?.content);
  return normalizeRoleplayFeedback(obj);
}

function normalizeShadowingSentences(obj) {
  const arr = Array.isArray(obj?.sentences) ? obj.sentences : (Array.isArray(obj) ? obj : []);
  return arr
    .map((s) => {
      if (typeof s === "string") return { text: String(s).replace(/\s+/g, " ").trim().slice(0, 240), translation: "" };
      return {
        text: String(s?.text || s?.sentence || s?.english || "").replace(/\s+/g, " ").trim().slice(0, 240),
        translation: String(s?.translation || s?.chinese || s?.cn || "").replace(/\s+/g, " ").trim().slice(0, 240)
      };
    })
    .filter((s) => s.text)
    .slice(0, 10);
}

// 影子跟读：按主题/难度生成若干适合「听后跟读」的英文句子。
async function generateShadowingSentences({ topic = "", level = "", count = 6 } = {}) {
  const n = Math.max(3, Math.min(10, Math.round(Number(count) || 6)));
  const lvl = String(level || "B1").trim().slice(0, 12) || "B1";
  const t = String(topic || "").trim().slice(0, 120) || "everyday life";
  const prompt = [
    `Generate ${n} natural English sentences for shadowing practice (listen-and-repeat) for a Chinese learner.`,
    `Topic: "${t}". CEFR level: ${lvl}.`,
    "Each must be ONE clear, self-contained, natural sentence good for pronunciation practice (8-18 words).",
    'Return ONLY a JSON object: {"sentences": [{"text": "the English sentence", "translation": "中文翻译"}]}'
  ].join("\n");
  const data = await callMimoChatRaw(
    [{ role: "user", content: prompt }],
    { model: settings.mimoTextModel, temperature: 0.5, maxTokens: 900, json: true }
  );
  const obj = extractJsonFromContent(data.choices?.[0]?.message?.content);
  const sentences = normalizeShadowingSentences(obj);
  if (!sentences.length) throw new Error("shadowing generation empty");
  return { sentences };
}

async function synthesizeAgentSpeech({
  text,
  voiceProfile = "",
  voiceMode = "default",
  format = "wav"
} = {}) {
  const cleanText = String(text || "").trim();
  if (!cleanText) throw new Error("text is required");
  const customVoice = String(voiceProfile || "").trim();
  const model = customVoice || voiceMode === "custom"
    ? settings.mimoVoiceDesignModel
    : settings.mimoAgentTtsModel;
  const prompt = customVoice
    ? `Voice description: ${customVoice}\nSpeak naturally and clearly.`
    : `Use a natural, calm mobile AI assistant voice.`;
  const safeFormat = String(format || settings.mimoTtsFormat || "wav").replace(/[^a-z0-9]/gi, "").toLowerCase() || "wav";
  const audioOptions = customVoice
    ? { format: safeFormat, optimize_text_preview: true }
    : { format: safeFormat, voice: settings.mimoTtsVoice || "mimo_default" };
  const data = await callMimoChatRaw([
    { role: "user", content: prompt },
    { role: "assistant", content: cleanText }
  ], {
    model,
    temperature: 0.3,
    maxTokens: 1200,
    json: false,
    extra: {
      audio: audioOptions,
      stream: false
    }
  });
  const audio = data.choices?.[0]?.message?.audio?.data;
  if (!audio) throw new Error("TTS No Audio Data");
  const baseName = `agent_${crypto.randomUUID()}`;
  const filename = `${baseName}.${safeFormat}`;
  const outputPath = path.join(settings.audioDir, filename);
  fs.mkdirSync(settings.audioDir, { recursive: true });
  fs.writeFileSync(outputPath, Buffer.from(audio, "base64"));
  const finalName = safeFormat === "wav" ? compressFinalAudio(outputPath, baseName) : filename;
  return {
    audioUrl: `${settings.publicBaseUrl}/audio/${finalName}`,
    format: finalName.split(".").pop() || safeFormat,
    model
  };
}

module.exports = {
  normalizeAttachment,
  hasMultimodalAttachments,
  buildUserContentWithAttachments,
  callMimoChatRaw,
  callMimoChatStream,
  extractJsonFromContent,
  transcribeAudio,
  assessSpeaking,
  assessWriting,
  roleplayTurn,
  roleplayFeedback,
  generateShadowingSentences,
  synthesizeAgentSpeech,
  __test: {
    normalizeSpeakingAssessment,
    normalizeWritingAssessment,
    normalizeRoleplayFeedback,
    normalizeShadowingSentences,
    clampScore
  }
};
