// 听力素材生成：难度 / 语速 / 音色 / 约束 + 对话 / 文章生成编排。
// 从 mimoText.js 抽出（零行为变更）；底层模型调用走 mimoCore。

// ----------------- 难度 / 语速 / 性别工具 -----------------

function normalizeListeningDifficulty(value, fallback = "普通") {
  const raw = String(value || "").toLowerCase();
  if (/\b(?:a1|a2)\b/.test(raw) || /简单|基础|初级|入门|\b(?:easy|beginner|basic)\b/.test(raw)) return "简单";
  if (/\b(?:b1|b2)\b/.test(raw) || /普通|中等|中级|\b(?:normal|medium|intermediate)\b/.test(raw)) return "普通";
  if (/\b(?:c1|c2)\b/.test(raw) || /困难|高级|进阶|雅思|托福|\b(?:hard|difficult|advanced)\b/.test(raw)) return "困难";
  return fallback;
}

function difficultySpec(difficulty, contentType = "dialogue") {
  const normalizedDifficulty = normalizeListeningDifficulty(difficulty);
  if (contentType === "article") {
    const map = {
      "简单": {
        cefr: "CEFR A2",
        length: "120-180 words",
        sentence: "Simple short sentences with basic SVO structures.",
        vocab: "Beginner vocabulary; avoid idioms and complex phrases.",
        questions: "2-3"
      },
      "普通": {
        cefr: "CEFR B1-B2",
        length: "220-320 words",
        sentence: "Medium-length sentences with common clauses.",
        vocab: "Common daily and workplace vocabulary.",
        questions: "4-6"
      },
      "困难": {
        cefr: "CEFR C1",
        length: "400-550 words",
        sentence: "Complex sentences with sub-clauses and idioms.",
        vocab: "Advanced/professional vocabulary and nuanced expressions.",
        questions: "6-10"
      }
    };
    return map[normalizedDifficulty] || map["普通"];
  }
  const map = {
    "简单": {
      cefr: "CEFR A2",
      length: "5-7 turns",
      sentence: "Short, clear SVO sentences.",
      vocab: "Beginner everyday vocabulary.",
      questions: "2-3"
    },
    "普通": {
      cefr: "CEFR B1-B2",
      length: "8-12 turns",
      sentence: "Medium-length sentences with natural clauses.",
      vocab: "Common daily and workplace vocabulary.",
      questions: "4-6"
    },
    "困难": {
      cefr: "CEFR C1",
      length: "12-18 turns",
      sentence: "Long sentences, sub-clauses, idioms.",
      vocab: "Advanced/professional vocabulary.",
      questions: "6-10"
    }
  };
  return map[normalizedDifficulty] || map["普通"];
}

function speechRateHint(rate) {
  const mapping = {
    fast: "fast pace (brisk, energetic, crisp articulation)",
    slow: "slow pace (deliberate, clear, with natural pauses)",
    medium: "medium pace (natural conversational speed)"
  };
  return mapping[rate] || mapping.medium;
}

function normalizeVoiceGender(raw) {
  const v = String(raw || "").toLowerCase().trim();
  if (v === "male" || v === "男" || v === "男声" || v === "m") return "male";
  if (v === "female" || v === "女" || v === "女声" || v === "f") return "female";
  return "female";
}

function normalizePitch(raw) {
  const v = String(raw || "").toLowerCase();
  if (/low|deep|baritone|bass|低音|低沉|偏低/.test(v)) return "low";
  if (/high|bright|treble|高音|偏高|清亮|尖亮/.test(v)) return "high";
  if (/mid|middle|neutral|中音|自然音调|普通音调/.test(v)) return "medium";
  return "";
}

function normalizeAccent(raw) {
  const v = String(raw || "").toLowerCase();
  if (/british|uk|rp|英音|英式|英国/.test(v)) return "british";
  if (/american|us|美音|美式|美国/.test(v)) return "american";
  if (/australian|澳音|澳式|澳大利亚/.test(v)) return "australian";
  if (/canadian|加拿大/.test(v)) return "canadian";
  return "";
}

function normalizeTone(raw) {
  const v = String(raw || "").toLowerCase();
  if (/gentle|soft|温柔|柔和/.test(v)) return "gentle";
  if (/warm|friendly|亲切|温暖/.test(v)) return "warm";
  if (/calm|steady|沉稳|平静|克制/.test(v)) return "calm";
  if (/energetic|lively|活泼|有活力/.test(v)) return "energetic";
  if (/serious|formal|严肃|正式/.test(v)) return "serious";
  if (/professional|broadcast|主播|专业/.test(v)) return "professional";
  return "";
}

function compactText(text, max = 260) {
  const value = String(text || "").replace(/\s+/g, " ").trim();
  return value.length > max ? value.slice(0, max - 1) : value;
}

const ENGLISH_COUNT_WORDS = {
  one: 1,
  two: 2,
  three: 3,
  four: 4,
  five: 5,
  six: 6,
  seven: 7,
  eight: 8,
  nine: 9,
  ten: 10,
  eleven: 11,
  twelve: 12
};

const CHINESE_COUNT_DIGITS = {
  一: 1,
  二: 2,
  两: 2,
  俩: 2,
  倆: 2,
  三: 3,
  四: 4,
  五: 5,
  六: 6,
  七: 7,
  八: 8,
  九: 9
};

function parseCountToken(token) {
  const raw = String(token || "").trim().toLowerCase();
  if (!raw) return null;
  if (/^\d+$/.test(raw)) return Number(raw);
  if (ENGLISH_COUNT_WORDS[raw]) return ENGLISH_COUNT_WORDS[raw];
  const text = raw.replace(/兩/g, "两").replace(/倆/g, "俩");
  if (CHINESE_COUNT_DIGITS[text]) return CHINESE_COUNT_DIGITS[text];
  if (text === "十") return 10;
  if (text.includes("十")) {
    const [tensRaw, onesRaw] = text.split("十");
    const tens = tensRaw ? CHINESE_COUNT_DIGITS[tensRaw] : 1;
    const ones = onesRaw ? (CHINESE_COUNT_DIGITS[onesRaw] || 0) : 0;
    if (tens) return tens * 10 + ones;
  }
  return null;
}

function boundedCount(value, min, max) {
  const parsed = typeof value === "number" ? value : parseCountToken(value);
  if (!Number.isFinite(parsed)) return null;
  const count = Math.trunc(parsed);
  if (count < min || count > max) return null;
  return count;
}

function firstCountMatch(source, patterns, min, max) {
  for (const pattern of patterns) {
    const match = pattern.exec(source);
    if (!match) continue;
    const count = boundedCount(match[1], min, max);
    if (count) return count;
  }
  return null;
}

function resolveListeningGenerationConstraints(request = {}, contentType = "dialogue") {
  const type = String(contentType || request.contentType || "dialogue").toLowerCase();
  const source = `${request.scene || ""} ${request.details || ""}`.trim();
  const numberToken = "([0-9]{1,2}|[一二两兩俩倆三四五六七八九十]{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)";
  const questionCount = boundedCount(request.questionCount, 1, 12) || firstCountMatch(source, [
    new RegExp(`${numberToken}\\s+(?:(?:multiple|choice|mcq|quiz|listening|comprehension|meaning|vocabulary|grammar|cloze|fill|blank|short|answer)\\s+){0,5}questions?\\b`, "i"),
    new RegExp(`${numberToken}\\s*(?:道|个|個|条|條|套)?\\s*(?:(?:选择|單選|单选|多选|多選|填空|听力|聽力|阅读|閱讀|理解|词汇|詞彙|语法|語法|问答|問答|简答|簡答)\\s*){0,4}(?:题|題|题目|題目|问题|問題)`, "i"),
    new RegExp(`${numberToken}\\s*(?:道|个|個|条|條|套)?\\s*(?:题|題|题目|題目|问题|問題|questions?\\b)`, "i"),
    new RegExp(`(?:题目|題目|问题|問題|questions?|question\\s*count|number\\s*of\\s*questions)[^0-9一二两兩俩倆三四五六七八九十a-z]{0,8}${numberToken}`, "i")
  ], 1, 12);
  const speakerCount = type === "dialogue"
    ? boundedCount(request.speakerCount || request.participantCount, 1, 6) || firstCountMatch(source, [
        new RegExp(`${numberToken}\\s*(?:个|個|位)?\\s*(?:人|角色|人物|说话人|說話人|speakers?\\b|people\\b|persons?\\b|participants?\\b|characters?\\b)`, "i"),
        new RegExp(`(?:人数|人數|角色数|角色數|说话人数量|說話人數量|speaker\\s*count|number\\s*of\\s*speakers|participants?|characters?)[^0-9一二两兩俩倆三四五六七八九十a-z]{0,8}${numberToken}`, "i")
      ], 1, 6)
    : null;
  const difficulty = normalizeListeningDifficulty(source, "") || normalizeListeningDifficulty(request.difficulty);
  return {
    difficulty,
    questionCount,
    speakerCount
  };
}

function questionCountInstruction(constraints, spec) {
  return constraints.questionCount
    ? `exactly ${constraints.questionCount}`
    : spec.questions;
}

function enforceListeningGenerationConstraints(result, constraints = {}, contentType = "dialogue") {
  const next = { ...result };
  if (constraints.questionCount) {
    const questions = Array.isArray(next.questions) ? next.questions : [];
    if (questions.length < constraints.questionCount) {
      throw new Error(`Expected exactly ${constraints.questionCount} questions, got ${questions.length}.`);
    }
    next.questions = questions.slice(0, constraints.questionCount);
  }
  if (contentType === "dialogue" && constraints.speakerCount) {
    const speakers = Array.isArray(next.speakers) ? next.speakers : [];
    const segments = Array.isArray(next.ttsSegments) ? next.ttsSegments.slice() : [];
    const speakerIds = new Set(speakers.map((speaker) => String(speaker.speakerId || "").trim()).filter(Boolean));
    if (speakerIds.size !== constraints.speakerCount || speakers.length !== constraints.speakerCount) {
      throw new Error(`Expected exactly ${constraints.speakerCount} speakers, got ${speakers.length}.`);
    }
    repairMissingListeningSpeakerTurns(next, speakers, segments, constraints.speakerCount);
    const segmentSpeakerIds = new Set(segments.map((segment) => String(segment.speakerId || "").trim()).filter(Boolean));
    for (const id of segmentSpeakerIds) {
      if (!speakerIds.has(id)) throw new Error(`Generated segment uses undeclared speaker ${id}.`);
    }
    if (segmentSpeakerIds.size !== constraints.speakerCount) {
      throw new Error(`Expected all ${constraints.speakerCount} speakers to appear in ttsSegments, got ${segmentSpeakerIds.size}.`);
    }
  }
  return next;
}

function repairMissingListeningSpeakerTurns(result, speakers, segments, speakerCount) {
  if (!Array.isArray(segments) || segments.length === 0) return;
  const used = new Set(segments.map((segment) => String(segment.speakerId || "").trim()).filter(Boolean));
  const missing = speakers
    .map((speaker) => String(speaker.speakerId || "").trim())
    .filter((id) => id && !used.has(id));
  if (!missing.length) {
    result.ttsSegments = segments;
    return;
  }
  const bySpeaker = new Map();
  segments.forEach((segment) => {
    const id = String(segment.speakerId || "").trim();
    bySpeaker.set(id, (bySpeaker.get(id) || 0) + 1);
  });
  missing.forEach((missingId, offset) => {
    const donorIndex = findListeningSpeakerTurnDonorIndex(segments, bySpeaker, offset);
    const speaker = speakers.find((item) => String(item.speakerId || "").trim() === missingId) || {};
    if (donorIndex >= 0) {
      const donor = segments[donorIndex];
      const previousId = String(donor.speakerId || "").trim();
      segments[donorIndex] = {
        ...donor,
        speakerId: missingId,
        speakerName: speaker.speakerName || donor.speakerName || missingId,
        speakerGender: speaker.speakerGender || donor.speakerGender || "female",
        voiceProfile: speaker.voiceProfile || donor.voiceProfile || ""
      };
      bySpeaker.set(previousId, Math.max(0, (bySpeaker.get(previousId) || 1) - 1));
    } else {
      segments.push({
        speakerId: missingId,
        speakerName: speaker.speakerName || missingId,
        speakerGender: speaker.speakerGender || "female",
        voiceProfile: speaker.voiceProfile || "",
        text: fallbackMissingSpeakerTurnText(result, speaker)
      });
    }
    bySpeaker.set(missingId, 1);
  });
  result.ttsSegments = segments;
  result.script = normalizeListeningScriptText(segments
    .map((segment) => `${segment.speakerName || segment.speakerId}: ${segment.text}`)
    .join("\n\n"));
}

function findListeningSpeakerTurnDonorIndex(segments, bySpeaker, offset) {
  const candidates = segments
    .map((segment, index) => ({
      index,
      speakerId: String(segment.speakerId || "").trim(),
      length: String(segment.text || "").length
    }))
    .filter((item) => (bySpeaker.get(item.speakerId) || 0) > 1);
  if (!candidates.length) return -1;
  candidates.sort((a, b) => b.index - a.index || b.length - a.length);
  return candidates[offset % candidates.length].index;
}

function fallbackMissingSpeakerTurnText(result, speaker = {}) {
  const source = `${result.title || ""} ${result.script || ""}`.toLowerCase();
  if (/hotel|check.?in|reservation|front desk|reception/.test(source)) {
    return "I can help with that as well.";
  }
  if (/restaurant|table|menu|order|booking/.test(source)) {
    return "Let me check that for you.";
  }
  if (/meeting|project|office|client/.test(source)) {
    return "I have one quick update to add.";
  }
  if (/airport|flight|gate|boarding/.test(source)) {
    return "Please let me confirm the details.";
  }
  return "I can add one more detail.";
}

function voiceDirectivesFromRequest(request = {}) {
  const source = `${request.scene || ""} ${request.details || ""} ${request.voiceProfile || ""} ${request.pitch || ""} ${request.accent || ""} ${request.tone || ""}`;
  return {
    voiceGender: request.voiceGender ? normalizeVoiceGender(request.voiceGender) : "",
    voiceProfile: compactText(request.voiceProfile, 220),
    pitch: normalizePitch(request.pitch || source),
    accent: normalizeAccent(request.accent || source),
    tone: normalizeTone(request.tone || source)
  };
}

function pitchPhrase(value) {
  if (value === "low") return "low pitch";
  if (value === "high") return "high pitch";
  if (value === "medium") return "medium pitch";
  return "";
}

function accentPhrase(value) {
  if (value === "british") return "British English accent";
  if (value === "american") return "American English accent";
  if (value === "australian") return "Australian English accent";
  if (value === "canadian") return "Canadian English accent";
  return "";
}

function tonePhrase(value) {
  if (value === "gentle") return "gentle tone";
  if (value === "warm") return "warm friendly tone";
  if (value === "calm") return "calm steady tone";
  if (value === "energetic") return "energetic lively tone";
  if (value === "serious") return "serious formal tone";
  if (value === "professional") return "professional broadcaster tone";
  return "";
}

function genderSafeVoiceProfile(profile, gender) {
  const target = gender === "male" ? "male" : "female";
  const replacements = target === "male"
    ? [
        [/\bfemale\b/gi, "male"],
        [/\bwoman\b/gi, "man"],
        [/\bgirl\b/gi, "boy"],
        [/\blady\b/gi, "man"]
      ]
    : [
        [/\bmale\b/gi, "female"],
        [/\bman\b/gi, "woman"],
        [/\bboy\b/gi, "girl"],
        [/\bgentleman\b/gi, "woman"]
      ];
  let raw = compactText(profile, 160);
  replacements.forEach(([pattern, replacement]) => {
    raw = raw.replace(pattern, replacement);
  });
  return raw.replace(/\s+/g, " ").trim();
}

function directiveProfileAddition(profile, gender) {
  return genderSafeVoiceProfile(profile, gender)
    .replace(/^(male|female)\b[,\s-]*/i, "")
    .trim();
}

function buildVoiceProfileWithDirectives(profile, directives = {}, genderOverride = "") {
  const gender = normalizeVoiceGender(genderOverride || directives.voiceGender || profile);
  let raw = buildVoiceProfile({ voiceProfile: profile }, gender, 0);
  const lower = raw.toLowerCase();
  const additions = [
    directiveProfileAddition(directives.voiceProfile, gender),
    tonePhrase(directives.tone),
    pitchPhrase(directives.pitch),
    accentPhrase(directives.accent)
  ]
    .map((item) => compactText(item, 120))
    .filter((item) => item && !lower.includes(item.toLowerCase().replace(" english", "")));
  if (additions.length) raw = `${raw.replace(/\.*$/, "")}, ${additions.join(", ")}.`;
  return compactText(raw, 320);
}

/** 兜底性别推断：基于姓名/角色称谓的关键词 */
function inferGenderFromText(text) {
  const t = String(text || "").toLowerCase();
  const maleHit = /\b(mr\.?|sir|man|boy|male|husband|father|dad|daddy|son|brother|uncle|grandpa|grandfather|king|prince|gentleman|guy|he|his|him)\b/.test(t);
  const femaleHit = /\b(mrs\.?|ms\.?|miss|lady|woman|girl|female|wife|mother|mom|mommy|mum|daughter|sister|aunt|grandma|grandmother|queen|princess|she|her|hers)\b/.test(t);
  if (femaleHit && !maleHit) return "female";
  if (maleHit && !femaleHit) return "male";
  return null;
}

// ----------------- 调用 MiMo 文本模型 -----------------

const { callMimoText } = require("./mimoCore");

// ----------------- 文章听力 -----------------

const generateArticleListeningContent = async (request) => {
  const narratorGender = normalizeVoiceGender(request.voiceGender);
  const voiceDirectives = voiceDirectivesFromRequest(request);
  const constraints = resolveListeningGenerationConstraints(request, "article");
  const spec = difficultySpec(constraints.difficulty, "article");
  const questionInstruction = questionCountInstruction(constraints, spec);

  const prompt = `Create an English ARTICLE listening exercise (single narrator monologue).
Topic: ${request.scene}
Narrator gender: ${narratorGender}
Length: ${spec.length}
Sentence style: ${spec.sentence}
Vocabulary: ${spec.vocab} (${spec.cefr})
Questions: generate ${questionInstruction} high-quality questions, mixing factual, inferential and main-idea.
Additional notes: ${request.details || "None"}
Voice directives: ${[
    request.voiceProfile ? `voiceProfile=${request.voiceProfile}` : "",
    voiceDirectives.pitch ? `pitch=${voiceDirectives.pitch}` : "",
    voiceDirectives.accent ? `accent=${voiceDirectives.accent}` : "",
    voiceDirectives.tone ? `tone=${voiceDirectives.tone}` : "",
    request.speechRate ? `speechRate=${request.speechRate}` : ""
  ].filter(Boolean).join("; ") || "None"}

Return STRICT JSON:
{
  "title": "Title",
  "script": "Full article prose. Paragraphs separated by a blank line. No 'Speaker:' labels.",
  "questions": [
    { "questionText": "...", "options": ["A","B","C","D"], "correctAnswer": 0, "explanation": "..." }
  ]
}

Rules:
- Plain article prose only, no dialogue and no speaker tags.
- If an exact question count is specified above, the "questions" array MUST contain exactly that many items.
- Distractors must be plausible but definitively wrong.
- Do NOT include emotion / speed / delivery instructions anywhere.`;

  const data = await callMimoText([
    { role: "system", content: "You output ONLY a single valid JSON object that matches the requested schema. No markdown, no commentary." },
    { role: "user", content: prompt }
  ], { temperature: 0.4 });

  return enforceListeningGenerationConstraints({
    title: data.title || "Listening Practice",
    script: normalizeListeningScriptText(data.script || ""),
    questions: (data.questions || []).map(q => ({
      questionText: q.questionText,
      options: q.options,
      correctAnswer: q.correctAnswer,
      explanation: q.explanation || ""
    })),
    speakers: [{
      speakerId: "N1",
      speakerName: "Narrator",
      speakerGender: narratorGender,
      voiceProfile: buildVoiceProfileWithDirectives(narratorGender === "male"
        ? "Adult male narrator, clear and steady baritone, professional broadcaster tone."
        : "Adult female narrator, clear and bright mid-range voice, professional broadcaster tone.", voiceDirectives, narratorGender)
    }],
    ttsSegments: [],
    ttsPrompt: buildVoiceProfileWithDirectives(`${narratorGender} narrator article reading`, voiceDirectives, narratorGender),
    contentType: "article",
    voiceGender: narratorGender,
    speechRate: String(request.speechRate || "").trim(),
    voiceProfile: voiceDirectives.voiceProfile,
    pitch: voiceDirectives.pitch,
    accent: voiceDirectives.accent,
    tone: voiceDirectives.tone
  }, constraints, "article");
};

// ----------------- 对话听力 -----------------

const generateDialogueListeningContent = async (request) => {
  const constraints = resolveListeningGenerationConstraints(request, "dialogue");
  const spec = difficultySpec(constraints.difficulty, "dialogue");
  const voiceDirectives = voiceDirectivesFromRequest(request);
  const questionInstruction = questionCountInstruction(constraints, spec);
  const speakerInstruction = constraints.speakerCount
    ? `Speaker count: create exactly ${constraints.speakerCount} unique speaker${constraints.speakerCount === 1 ? "" : "s"}/characters. The "speakers" array MUST contain exactly ${constraints.speakerCount} item${constraints.speakerCount === 1 ? "" : "s"}, and ttsSegments MUST use all and only those speakerId values.`
    : "Speaker count: choose the natural number of speakers for the scene. Whenever the scene allows, include BOTH a male and a female speaker.";

  const prompt = `Create an English DIALOGUE listening exercise.
Scene: ${request.scene}
Length: ${spec.length}
Sentence style: ${spec.sentence}
Vocabulary: ${spec.vocab} (${spec.cefr})
Questions: generate ${questionInstruction} high-quality questions, mixing factual, inferential and main-idea.
${speakerInstruction}
Additional notes: ${request.details || "None"}
Voice directives for all generated speakers: ${[
    request.voiceProfile ? `voiceProfile=${request.voiceProfile}` : "",
    voiceDirectives.pitch ? `pitch=${voiceDirectives.pitch}` : "",
    voiceDirectives.accent ? `accent=${voiceDirectives.accent}` : "",
    voiceDirectives.tone ? `tone=${voiceDirectives.tone}` : "",
    request.speechRate ? `speechRate=${request.speechRate}` : ""
  ].filter(Boolean).join("; ") || "None"}

For EVERY unique speaker you must design a coherent character profile so a TTS engine can produce a clearly distinguishable voice. Different speakers MUST have CLEARLY DIFFERENT profiles (gender, age, occupation, vocal character).
Apply the voice directives to every speaker profile while preserving each character's gender.

Return STRICT JSON:
{
  "title": "Title",
  "script": "Full transcript. Each turn formatted as 'SpeakerName: text', turns separated by a blank line.",
  "speakers": [
    {
      "speakerId": "P1",
      "speakerName": "Alice",
      "speakerGender": "female",
      "age": "young adult | middle-aged | elderly | teenager | child",
      "occupation": "e.g. Barista, Engineer, Teacher",
      "voiceProfile": "One sentence describing the human voice. MUST start with the gender word (e.g. 'Female ...' or 'Male ...'). Include age, occupation, pitch and timbre. NO emotion or speed words."
    }
  ],
  "ttsSegments": [
    { "speakerId": "P1", "text": "..." }
  ],
  "questions": [
    { "questionText": "...", "options": ["A","B","C","D"], "correctAnswer": 0, "explanation": "..." }
  ]
}

Rules:
- speakerId is unique per character (P1, P2, P3 ...). Reuse the same speakerId for the same character across turns.
- If an exact speaker count is specified above, do not add extra narrators, hosts, bystanders, or unnamed speakers.
- If an exact question count is specified above, the "questions" array MUST contain exactly that many items.
- speakerGender MUST be exactly the lowercase string "male" or "female", and MUST match the character's name and persona.
- voiceProfile MUST begin with the literal word "Male" or "Female".
- ttsSegments preserve dialogue order; each segment is one continuous spoken turn (no nested speakers).
- Do NOT include any emotion, speed, pause, delivery, stage direction or sound effect hints anywhere.
- Distractors in 'options' must be plausible but definitively wrong.`;

  const data = await callMimoText([
    { role: "system", content: "You output ONLY a single valid JSON object that matches the requested schema. No markdown, no commentary." },
    { role: "user", content: prompt }
  ], { temperature: 0.4 });

  const result = buildDialogueResult(data, voiceDirectives);
  result.contentType = "dialogue";
  result.voiceGender = request.voiceGender ? normalizeVoiceGender(request.voiceGender) : "";
  result.speechRate = String(request.speechRate || "").trim();
  result.voiceProfile = voiceDirectives.voiceProfile;
  result.pitch = voiceDirectives.pitch;
  result.accent = voiceDirectives.accent;
  result.tone = voiceDirectives.tone;
  if (!result.ttsSegments.length) throw new Error("Dialogue listening requires ttsSegments.");
  return enforceListeningGenerationConstraints(result, constraints, "dialogue");
};

function buildDialogueResult(data, voiceDirectives = {}) {
  const rawSpeakers = Array.isArray(data.speakers) ? data.speakers : [];
  const speakerMap = new Map();

  rawSpeakers.forEach((sp, idx) => {
    const id = String(sp.speakerId || `P${idx + 1}`);
    const declared = normalizeVoiceGender(sp.speakerGender);
    const inferred = inferGenderFromText(`${sp.speakerName || ""} ${sp.voiceProfile || ""}`);
    // 大模型自己声明的性别优先；只有当 voiceProfile/姓名 与声明强烈冲突时才纠正
    let gender = declared;
    if (inferred && inferred !== declared) {
      const profileLower = String(sp.voiceProfile || "").toLowerCase();
      if (profileLower.startsWith(inferred) || /\b(she|her|hers|woman|girl|lady|female|mom|mother|wife|sister|daughter)\b/.test(profileLower) === (inferred === "female")) {
        gender = inferred;
      }
    }

    const profile = buildVoiceProfileWithDirectives(buildVoiceProfile(sp, gender, idx), voiceDirectives, gender);
    speakerMap.set(id, {
      speakerId: id,
      speakerName: sp.speakerName || `Speaker ${idx + 1}`,
      speakerGender: gender,
      age: sp.age || "",
      occupation: sp.occupation || "",
      voiceProfile: profile
    });
  });

  // 转换 ttsSegments：注入 speaker 的性别/profile
  const rawSegments = Array.isArray(data.ttsSegments) ? data.ttsSegments : [];
  const ttsSegments = rawSegments
    .map((seg, idx) => {
      const id = String(seg.speakerId || `P${(idx % Math.max(speakerMap.size, 1)) + 1}`);
      let sp = speakerMap.get(id);
      if (!sp) {
        const fallbackGender = idx % 2 === 0 ? "female" : "male";
        sp = {
          speakerId: id,
          speakerName: `Speaker ${idx + 1}`,
          speakerGender: fallbackGender,
          age: "adult",
          occupation: "",
          voiceProfile: buildVoiceProfile({}, fallbackGender, idx)
        };
        speakerMap.set(id, sp);
      }
      const text = String(seg.text || "").trim();
      if (!text) return null;
      return {
        speakerId: sp.speakerId,
        speakerName: sp.speakerName,
        speakerGender: sp.speakerGender,
        voiceProfile: sp.voiceProfile,
        text
      };
    })
    .filter(Boolean);

  return {
    title: data.title || "Listening Practice",
    script: normalizeListeningScriptText(data.script || ttsSegments.map(s => `${s.speakerName}: ${s.text}`).join("\n\n")),
    questions: (data.questions || []).map(q => ({
      questionText: q.questionText,
      options: q.options,
      correctAnswer: q.correctAnswer,
      explanation: q.explanation || ""
    })),
    speakers: Array.from(speakerMap.values()),
    ttsSegments,
    ttsPrompt: `dialogue with ${speakerMap.size} distinct speakers`
  };
}

function normalizeListeningScriptText(value = "") {
  return String(value || "")
    .replace(/\\r\\n/g, "\n")
    .replace(/\\n/g, "\n")
    .replace(/\\t/g, "\t")
    .replace(/\r\n/g, "\n")
    .trim();
}

/** 兜底 voiceProfile：保证开头一定是 Male/Female 且区分度足够 */
const FEMALE_FALLBACK = [
  "Female adult, mid-high pitch, clear bright timbre, professional tone.",
  "Female mature, warm and steady mid-range voice, calm tone.",
  "Female young adult, light and energetic timbre, friendly tone."
];
const MALE_FALLBACK = [
  "Male adult, mid-low pitch, deep warm baritone, steady and clear.",
  "Male young adult, lighter conversational timbre, energetic and friendly.",
  "Male mature, grounded authoritative tone, crisp articulation."
];

function buildVoiceProfile(sp, gender, idx) {
  const genderWord = gender === "male" ? "Male" : "Female";
  let raw = String(sp.voiceProfile || "").trim();
  if (raw) {
    // 强制开头是性别词
    const lower = raw.toLowerCase();
    if (!lower.startsWith("male") && !lower.startsWith("female")) {
      raw = `${genderWord} ${raw}`;
    } else if (lower.startsWith("male") && gender === "female") {
      raw = raw.replace(/^male/i, "Female");
    } else if (lower.startsWith("female") && gender === "male") {
      raw = raw.replace(/^female/i, "Male");
    } else {
      // 大小写规范化
      raw = `${genderWord}${raw.slice(genderWord.length)}`;
    }
    // 附加年龄/职业（如果模型没写进 profile 但单独提供了）
    const extras = [];
    if (sp.age && !raw.toLowerCase().includes(String(sp.age).toLowerCase())) extras.push(String(sp.age));
    if (sp.occupation && !raw.toLowerCase().includes(String(sp.occupation).toLowerCase())) extras.push(String(sp.occupation));
    if (extras.length) raw = `${raw} (${extras.join(", ")})`;
    return raw;
  }
  const pool = gender === "male" ? MALE_FALLBACK : FEMALE_FALLBACK;
  return pool[idx % pool.length];
}

// ----------------- 听力生成入口 -----------------

const generateListeningContent = async (request) => {
  const type = (request.contentType || "dialogue").toLowerCase();
  if (type === "article") return generateArticleListeningContent(request);
  return generateDialogueListeningContent(request);
};

module.exports = {
  generateListeningContent,
  speechRateHint,
  normalizeVoiceGender,
  buildVoiceProfileWithDirectives,
  resolveListeningGenerationConstraints,
  enforceListeningGenerationConstraints,
  parseCountToken,
  firstCountMatch
};
