const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");
const { settings } = require("../config");
const { mimoCircuit, isRetryableMimoStatus } = require("./mimoCore");

// 并发上限（保护 API rate limit）
const TTS_CONCURRENCY = Math.max(1, Number(process.env.TTS_CONCURRENCY || 6));
const SEGMENT_SILENCE_MS = Math.max(80, Number(process.env.TTS_SEGMENT_SILENCE_MS || 220));

// 音频压缩：拼接后的 WAV 体积大（24kHz/16bit ≈ 2.9MB/分钟），源站又远，下载很慢。
// 转成有损格式（默认 mp3 ~48kbps 单声道）可缩小约 5-10×。
// 关闭：AUDIO_COMPRESS=0；可调 AUDIO_CODEC(mp3|opus|aac)、AUDIO_BITRATE。
const AUDIO_COMPRESS = !["0", "false", "off", "no"].includes(
  String(process.env.AUDIO_COMPRESS ?? "1").toLowerCase()
);
const AUDIO_CODEC = String(process.env.AUDIO_CODEC || "mp3").toLowerCase();
const AUDIO_BITRATE = String(process.env.AUDIO_BITRATE || "48k");

const AUDIO_CODEC_SETUP = {
  mp3: { ext: "mp3", args: (b) => ["-c:a", "libmp3lame", "-b:a", b, "-ac", "1"] },
  opus: { ext: "opus", args: (b) => ["-c:a", "libopus", "-b:a", b, "-ac", "1", "-application", "voip"] },
  aac: { ext: "m4a", args: (b) => ["-c:a", "aac", "-b:a", b, "-ac", "1", "-movflags", "+faststart"] }
};

let ffmpegChecked = null;
function ffmpegAvailable() {
  if (ffmpegChecked === null) {
    try {
      ffmpegChecked = spawnSync("ffmpeg", ["-version"], { stdio: "ignore" }).status === 0;
    } catch (_) {
      ffmpegChecked = false;
    }
  }
  return ffmpegChecked;
}

// 把最终 WAV 转码为压缩格式；成功则删原 WAV 并返回新文件名，失败/ffmpeg 不可用时
// 优雅回退原 WAV（绝不让压缩失败影响听力可用性）。
// 时长保持不变，故先前按 WAV 算好的 audioSegments(startMs/endMs) 依然准确。
function compressFinalAudio(wavPath, baseName) {
  const setup = AUDIO_CODEC_SETUP[AUDIO_CODEC] || AUDIO_CODEC_SETUP.mp3;
  if (!AUDIO_COMPRESS || !ffmpegAvailable()) return path.basename(wavPath);
  const outName = `${baseName}.${setup.ext}`;
  const outPath = path.join(path.dirname(wavPath), outName);
  try {
    const r = spawnSync("ffmpeg", ["-y", "-i", wavPath, ...setup.args(AUDIO_BITRATE), outPath], {
      stdio: "ignore",
      timeout: 120000
    });
    if (r.status === 0 && fs.existsSync(outPath) && fs.statSync(outPath).size > 0) {
      try { fs.unlinkSync(wavPath); } catch (_) {}
      return outName;
    }
    try { if (fs.existsSync(outPath)) fs.unlinkSync(outPath); } catch (_) {}
  } catch (_) {}
  return path.basename(wavPath);
}

// ----------------- TTS 分句音频缓存（按 文本+音色 去重，省 MiMo 调用） -----------------
// 缓存放独立目录(默认 storage/tts-cache)，不会被 storage/audio 的 15 分钟 TTL 清理误删。
const TTS_CACHE_ENABLED = !["0", "false", "off", "no"].includes(
  String(process.env.TTS_CACHE ?? "1").toLowerCase()
);
const TTS_CACHE_TTL_MS = Math.max(0, Number(process.env.TTS_CACHE_TTL_MS || 30 * 24 * 60 * 60 * 1000));
const TTS_CACHE_VERSION = "v1"; // 改了 voicePrompt / 合成逻辑时改它以失效旧缓存
let ttsCacheLastPrune = 0;

function ttsCacheDir() {
  const dir = process.env.TTS_CACHE_DIR || path.join(settings.audioDir, "..", "tts-cache");
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

function ttsCacheKey(parts = {}) {
  return crypto
    .createHash("sha256")
    .update(JSON.stringify({
      ver: TTS_CACHE_VERSION,
      model: settings.mimoTtsModel || "",
      text: parts.text || "",
      gender: parts.gender || "",
      voice: parts.voiceProfile || "",
      seed: parts.seed ?? "",
      rate: parts.rateHint || "",
      fmt: parts.format || "wav"
    }))
    .digest("hex");
}

function ttsCachePathFor(key, format) {
  return path.join(ttsCacheDir(), `${key}.${format}`);
}

function ttsCacheGet(key, format) {
  if (!TTS_CACHE_ENABLED) return null;
  try {
    const p = ttsCachePathFor(key, format);
    if (fs.existsSync(p) && fs.statSync(p).size > 0) return p;
  } catch (_) {}
  return null;
}

function ttsCachePut(key, srcPath, format) {
  if (!TTS_CACHE_ENABLED) return;
  try {
    fs.copyFileSync(srcPath, ttsCachePathFor(key, format));
  } catch (_) {}
  maybePruneTtsCache();
}

// 惰性清理：最多每小时扫一次，删超过 TTL 的缓存（命中时会 touch mtime，热条目不会被清）。
function maybePruneTtsCache() {
  if (!TTS_CACHE_TTL_MS) return;
  const now = Date.now();
  if (now - ttsCacheLastPrune < 60 * 60 * 1000) return;
  ttsCacheLastPrune = now;
  try {
    const dir = ttsCacheDir();
    for (const f of fs.readdirSync(dir)) {
      const fp = path.join(dir, f);
      try {
        if (now - fs.statSync(fp).mtimeMs > TTS_CACHE_TTL_MS) fs.unlinkSync(fp);
      } catch (_) {}
    }
  } catch (_) {}
}

// 用户选语速 → TTS 提示短语
function speechRateHint(rate) {
  const v = String(rate || "medium").toLowerCase();
  if (v === "fast" || v === "快") return "fast pace, brisk and energetic delivery";
  if (v === "slow" || v === "慢") return "slow pace, deliberate and clear delivery with natural pauses";
  return "medium pace, natural conversational speed";
}

function normalizeVoiceGender(raw) {
  const v = String(raw || "").toLowerCase().trim();
  if (v === "male" || v === "男" || v === "男声" || v === "m") return "male";
  return "female";
}

function compactText(text, max = 260) {
  const value = String(text || "").replace(/\s+/g, " ").trim();
  return value.length > max ? value.slice(0, max - 1) : value;
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

function voiceDirectivesFromOptions(options = {}) {
  const source = `${options.voiceProfile || ""} ${options.pitch || ""} ${options.accent || ""} ${options.tone || ""}`;
  return {
    voiceGender: options.voiceGender ? normalizeVoiceGender(options.voiceGender) : "",
    voiceProfile: compactText(options.voiceProfile, 220),
    pitch: normalizePitch(options.pitch || source),
    accent: normalizeAccent(options.accent || source),
    tone: normalizeTone(options.tone || source)
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
  let raw = profile || "";
  raw = ensureGenderInProfile(raw, gender);
  const lower = raw.toLowerCase();
  const additions = [
    directiveProfileAddition(directives.voiceProfile, gender),
    tonePhrase(directives.tone),
    pitchPhrase(directives.pitch),
    accentPhrase(directives.accent)
  ]
    .map((part) => compactText(part, 120))
    .filter((part) => part && !lower.includes(part.toLowerCase().replace(" english", "")));
  if (additions.length) raw = `${raw.replace(/\.*$/, "")}, ${additions.join(", ")}.`;
  return compactText(raw, 320);
}

// 简易并发池
async function runWithConcurrency(items, limit, worker) {
  const results = new Array(items.length);
  let cursor = 0;
  const runners = Array.from({ length: Math.min(limit, items.length) }, async () => {
    while (true) {
      const i = cursor++;
      if (i >= items.length) return;
      results[i] = await worker(items[i], i);
    }
  });
  await Promise.all(runners);
  return results;
}

// 把 speakerId 映射为稳定的 seed，保证同角色音色一致
function seedFromString(str) {
  let seed = 0;
  const s = String(str || "");
  for (let i = 0; i < s.length; i++) {
    seed = (seed << 5) - seed + s.charCodeAt(i);
    seed |= 0;
  }
  return Math.abs(seed) % 100000;
}

const titleAbbreviations = new Set(["mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st"]);
const inlineAbbreviations = new Set(["e.g", "i.e", "vs"]);
const terminalAbbreviations = new Set(["a.m", "p.m", "etc"]);
const closingChars = new Set(['"', "'", ")", "]", "}", "”", "’", "）", "】"]);

function normalizeTranscriptText(text) {
  return String(text || "")
    .replace(/\s+/g, " ")
    .trim();
}

function splitTranscriptSentences(input) {
  const text = normalizeTranscriptText(input);
  if (!text) return [];
  const result = [];
  let buffer = "";
  let i = 0;
  while (i < text.length) {
    const ch = text[i];
    buffer += ch;
    if (isSentenceEnd(ch) && isSentenceBoundary(text, i, buffer)) {
      let next = i + 1;
      while (next < text.length && closingChars.has(text[next])) {
        buffer += text[next];
        next += 1;
      }
      pushSentence(result, buffer);
      buffer = "";
      while (next < text.length && /\s/.test(text[next])) next += 1;
      i = next;
      continue;
    }
    i += 1;
  }
  pushSentence(result, buffer);
  return result.flatMap(splitOverlongSentence);
}

function isSentenceEnd(ch) {
  return ch === "." || ch === "?" || ch === "!" || ch === "。" || ch === "？" || ch === "！";
}

function isSentenceBoundary(text, index, currentText) {
  const ch = text[index];
  if (ch === "." && isProtectedPeriod(text, index, currentText)) return false;
  let next = index + 1;
  while (next < text.length && closingChars.has(text[next])) next += 1;
  if (next >= text.length) return true;
  return /\s/.test(text[next]);
}

function isProtectedPeriod(text, index, currentText) {
  const prev = text[index - 1];
  const next = text[index + 1];
  if (/\d/.test(prev || "") && /\d/.test(next || "")) return true;

  let nextNonSpace = index + 1;
  while (nextNonSpace < text.length && /\s/.test(text[nextNonSpace])) nextNonSpace += 1;
  const nextSignificant = text[nextNonSpace] || "";

  const tokenMatch = normalizeTranscriptText(currentText).match(/([A-Za-z](?:\.[A-Za-z])*|[A-Za-z]+)\.$/);
  if (!tokenMatch) return false;
  const token = tokenMatch[1].toLowerCase();
  if (titleAbbreviations.has(token)) return true;
  if (inlineAbbreviations.has(token)) return true;
  if (terminalAbbreviations.has(token)) return /[a-z0-9]/.test(nextSignificant);
  if (/(?:\b[A-Za-z]\.){2,}$/.test(currentText.slice(-12))) return /[a-z0-9]/.test(nextSignificant);
  if (token.length === 1 && /[A-Z]/.test(nextSignificant)) return true;
  return token === "no" && /\d/.test(nextSignificant);
}

function pushSentence(result, raw) {
  const cleaned = normalizeTranscriptText(raw);
  if (cleaned) result.push(cleaned);
}

function splitOverlongSentence(sentence) {
  if (sentence.length <= 260) return [sentence];
  const semicolonParts = sentence.split(/(?<=[;；])\s+/).map(normalizeTranscriptText).filter(Boolean);
  if (semicolonParts.length > 1) return semicolonParts;
  const commaParts = sentence.split(/(?<=[,，])\s+/).map(normalizeTranscriptText).filter(Boolean);
  if (commaParts.length <= 1) return [sentence];
  const merged = [];
  let buffer = "";
  for (const part of commaParts) {
    if (buffer && buffer.length + part.length + 1 > 220) {
      merged.push(buffer);
      buffer = "";
    }
    buffer = buffer ? `${buffer} ${part}` : part;
  }
  if (buffer) merged.push(buffer);
  return merged.length ? merged : [sentence];
}

function buildDialogueSentenceJobs(ttsSegments = []) {
  const jobs = [];
  ttsSegments.forEach((seg, turnIndex) => {
    const sentences = splitTranscriptSentences(seg.text);
    const pieces = sentences.length ? sentences : [normalizeTranscriptText(seg.text)].filter(Boolean);
    pieces.forEach((text, sentenceIndex) => {
      jobs.push({
        speakerId: String(seg.speakerId || `P${turnIndex + 1}`),
        speakerName: String(seg.speakerName || `Speaker ${turnIndex + 1}`),
        speakerGender: normalizeVoiceGender(seg.speakerGender),
        voiceProfile: seg.voiceProfile || "",
        text,
        turnText: normalizeTranscriptText(seg.text),
        turnIndex,
        sentenceIndex
      });
    });
  });
  return jobs;
}

function buildSingleSpeakerSentenceJobs(script, speaker = {}) {
  const paragraphs = String(script || "")
    .replace(/\r\n/g, "\n")
    .replace(/\r/g, "\n")
    .split(/\n{2,}|\n/)
    .map(normalizeTranscriptText)
    .filter(Boolean);
  const source = paragraphs.length ? paragraphs : [normalizeTranscriptText(script)].filter(Boolean);
  const jobs = [];
  source.forEach((paragraph, turnIndex) => {
    const sentences = splitTranscriptSentences(paragraph);
    const pieces = sentences.length ? sentences : [paragraph];
    pieces.forEach((text, sentenceIndex) => {
      jobs.push({
        speakerId: speaker.speakerId || "N1",
        speakerName: speaker.speakerName || "Narrator",
        speakerGender: normalizeVoiceGender(speaker.speakerGender),
        voiceProfile: speaker.voiceProfile || "",
        text,
        turnText: paragraph,
        turnIndex,
        sentenceIndex
      });
    });
  });
  return jobs;
}

function buildAudioSegmentIndex(jobs, durationsMs, silenceMs = SEGMENT_SILENCE_MS) {
  const sentenceSegments = [];
  let cursor = 0;
  jobs.forEach((job, index) => {
    const duration = Math.max(1, Math.round(Number(durationsMs[index] || 0)));
    const startMs = Math.round(cursor);
    const endMs = Math.round(cursor + duration);
    sentenceSegments.push({
      id: `turn-${job.turnIndex}-sentence-${job.sentenceIndex}`,
      kind: "sentence",
      speakerId: job.speakerId || "",
      speakerName: job.speakerName || "",
      speakerGender: job.speakerGender || "",
      text: job.text || "",
      startMs,
      endMs,
      turnIndex: job.turnIndex,
      sentenceIndex: job.sentenceIndex
    });
    cursor = endMs;
    if (index < jobs.length - 1) cursor += silenceMs;
  });

  const turnSegments = [];
  const byTurn = new Map();
  sentenceSegments.forEach((segment) => {
    const current = byTurn.get(segment.turnIndex) || [];
    current.push(segment);
    byTurn.set(segment.turnIndex, current);
  });
  [...byTurn.keys()].sort((a, b) => a - b).forEach((turnIndex) => {
    const group = byTurn.get(turnIndex);
    if (!group || group.length === 0) return;
    const source = jobs.find((job) => job.turnIndex === turnIndex) || {};
    turnSegments.push({
      id: `turn-${turnIndex}`,
      kind: "turn",
      speakerId: source.speakerId || group[0].speakerId || "",
      speakerName: source.speakerName || group[0].speakerName || "",
      speakerGender: source.speakerGender || group[0].speakerGender || "",
      text: source.turnText || group.map((item) => item.text).join(" "),
      startMs: group[0].startMs,
      endMs: group[group.length - 1].endMs,
      turnIndex,
      sentenceIndex: -1
    });
  });

  return [...turnSegments, ...sentenceSegments].filter((item) => item.text && item.endMs > item.startMs);
}

// ----------------- 音频生成主入口 -----------------

async function generateAudioFile(script, ttsPrompt, ttsSegments = [], options = {}) {
  fs.mkdirSync(settings.audioDir, { recursive: true });
  const format = settings.mimoTtsFormat || "wav";
  const baseName = crypto.randomUUID();
  const filename = `${baseName}.${format}`;
  const outputPath = path.join(settings.audioDir, filename);
  const contentType = String(options.contentType || "dialogue").toLowerCase();
  const rateHint = speechRateHint(options.speechRate);
  const voiceDirectives = voiceDirectivesFromOptions(options);

  let audioSegments;
  if (contentType === "article") {
    audioSegments = await generateArticleAudio(script, outputPath, format, {
      gender: normalizeVoiceGender(options.voiceGender),
      rateHint,
      voiceDirectives
    });
  } else if (Array.isArray(ttsSegments) && ttsSegments.length > 0) {
    audioSegments = await generateStitchedDialogueAudio(ttsSegments, format, outputPath, rateHint, voiceDirectives);
  } else {
    // 兜底：单人 (角色对话场景)
    const promptLower = (ttsPrompt || "").toLowerCase();
    const isFemale = /\b(female|woman|girl|lady|mrs|ms|miss|her|she|mom|mum|wife|sister|daughter)\b/i.test(promptLower);
    const isMale = /\b(male|man|boy|gentleman|mr|sir|his|he|him|dad|father|husband|brother|son)\b/i.test(promptLower);
    let gender = normalizeVoiceGender(options.voiceGender);
    if (!options.voiceGender) {
      if (isMale && !isFemale) gender = "male";
      else if (isFemale) gender = "female";
    }

    const speaker = {
      speakerId: "N1",
      speakerName: "Narrator",
      speakerGender: gender,
      voiceProfile: buildVoiceProfileWithDirectives(ttsPrompt, voiceDirectives, gender)
    };
    audioSegments = await generateSentenceStitchedAudio(
      buildSingleSpeakerSentenceJobs(script, speaker),
      format,
      outputPath,
      rateHint,
      null,
      voiceDirectives
    );
  }

  // 拼接后的 WAV 压缩成更小的有损格式（失败则回退 WAV）；时长不变，audioSegments 仍准确。
  const finalName = format === "wav" ? compressFinalAudio(outputPath, baseName) : filename;
  return { filename: finalName, audioSegments };
}

// ----------------- 文章听力（按段落并行） -----------------

async function generateArticleAudio(script, outputPath, format, { gender, rateHint, voiceDirectives }) {
  const text = String(script || "").trim();
  if (!text) throw new Error("Article script is empty");

  const baseVoiceProfile = gender === "male"
    ? "Male adult narrator, clear steady baritone, professional broadcaster tone."
    : "Female adult narrator, clear bright mid-range voice, professional broadcaster tone.";
  const voiceProfile = buildVoiceProfileWithDirectives(baseVoiceProfile, voiceDirectives, gender);

  return generateSentenceStitchedAudio(
    buildSingleSpeakerSentenceJobs(text, {
      speakerId: "N1",
      speakerName: "Narrator",
      speakerGender: gender,
      voiceProfile
    }),
    format,
    outputPath,
    rateHint,
    54321,
    voiceDirectives
  );
}

// ----------------- 对话听力（并行分段） -----------------

async function generateStitchedDialogueAudio(ttsSegments, format, outputPath, rateHint, voiceDirectives) {
  return generateSentenceStitchedAudio(
    buildDialogueSentenceJobs(ttsSegments),
    format,
    outputPath,
    rateHint,
    null,
    voiceDirectives
  );
}

async function generateSentenceStitchedAudio(jobs, format, outputPath, rateHint, fixedSeed = null, voiceDirectives = {}) {
  const normalizedJobs = jobs.filter((job) => normalizeTranscriptText(job.text));
  if (normalizedJobs.length === 0) throw new Error("TTS script is empty");

  const paths = await runWithConcurrency(normalizedJobs, TTS_CONCURRENCY, async (job) => {
    const p = path.join(settings.audioDir, `${crypto.randomUUID()}.${format}`);
    const gender = normalizeVoiceGender(job.speakerGender);
    const voiceProfile = buildVoiceProfileWithDirectives(job.voiceProfile, voiceDirectives, gender);
    await generateSegmentAudio({
      text: job.text,
      gender,
      voiceProfile,
      seed: fixedSeed ?? seedFromString(job.speakerId || job.speakerName || "P1"),
      rateHint
    }, p, format);
    return p;
  });

  try {
    const durations = paths.map((p) => readAudioDurationMs(p, format));
    const concatResult = concatAudioFiles(paths, outputPath, format, SEGMENT_SILENCE_MS);
    return buildAudioSegmentIndex(normalizedJobs, durations, concatResult.silenceMs);
  } finally {
    paths.forEach(p => { try { fs.unlinkSync(p); } catch (e) {} });
  }
}

function ensureGenderInProfile(profile, gender) {
  const word = gender === "male" ? "Male" : "Female";
  const raw = String(profile || "").trim();
  if (!raw) return `${word} adult, clear natural English voice.`;
  const lower = raw.toLowerCase();
  if (lower.startsWith("male") && gender === "female") return raw.replace(/^male/i, "Female");
  if (lower.startsWith("female") && gender === "male") return raw.replace(/^female/i, "Male");
  if (!lower.startsWith("male") && !lower.startsWith("female")) return `${word} ${raw}`;
  return raw;
}

// ----------------- 单段 TTS 调用 -----------------

async function generateSegmentAudio(seg, outputPath, format) {
  const gender = seg.gender === "male" ? "MALE" : "FEMALE";
  const voiceProfile = seg.voiceProfile || "";
  const rateHint = seg.rateHint || "medium pace, natural conversational speed";

  // 缓存命中：相同 (文本+音色+seed+模型) 直接复用，省一次 MiMo 调用并保证同句音色一致。
  const cacheKey = ttsCacheKey({ text: seg.text, gender, voiceProfile, seed: seg.seed, rateHint, format });
  const cachedHit = ttsCacheGet(cacheKey, format);
  if (cachedHit) {
    fs.copyFileSync(cachedHit, outputPath);
    try { const t = new Date(); fs.utimesSync(cachedHit, t, t); } catch (_) {}
    return;
  }

  // 多重性别锁定：通过多个标签和明确的自然语言让 voicedesign 模型不混淆
  const voicePrompt =
`[REQUIRED_GENDER=${gender}]
[SPEAKER_SEX=${gender}]
[VOICE_GENDER_LOCK=${gender}]

The speaker is ${gender === "MALE" ? "a man (male voice)" : "a woman (female voice)"}.
Voice description: ${voiceProfile}
Speaking style: ${rateHint}.
Render with natural human prosody and clear articulation. Do not change gender.`;

  if (!mimoCircuit.canRequest()) {
    throw new Error("MiMo TTS 暂时不可用（服务熔断中），请稍后重试");
  }

  let response;
  try {
    response = await fetch(`${settings.mimoBaseUrl.replace(/\/+$/, "")}${settings.mimoTtsPath}`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "api-key": settings.mimoApiKey,
        Authorization: `Bearer ${settings.mimoApiKey}`
      },
      body: JSON.stringify({
        model: settings.mimoTtsModel,
        messages: [
          { role: "user", content: voicePrompt },
          { role: "assistant", content: seg.text }
        ],
        audio: { format },
        stream: false,
        // 部分实现支持 seed/temperature；忽略时也不会报错
        seed: seg.seed,
        temperature: 0.3
      }),
      signal: AbortSignal.timeout(Math.max(5000, Number(settings.mimoTtsTimeoutMs) || 120000))
    });
  } catch (networkError) {
    mimoCircuit.recordFailure();
    throw networkError;
  }

  if (!response.ok) {
    const body = await response.text().catch(() => "");
    if (isRetryableMimoStatus(response.status)) mimoCircuit.recordFailure();
    else mimoCircuit.recordSuccess();
    throw new Error(`TTS API failed: ${response.status} ${body.slice(0, 200)}`);
  }
  const data = await response.json();
  const base64 = data.choices?.[0]?.message?.audio?.data;
  if (!base64) {
    mimoCircuit.recordFailure();
    throw new Error("TTS No Audio Data");
  }
  mimoCircuit.recordSuccess();
  fs.writeFileSync(outputPath, Buffer.from(base64, "base64"));
  ttsCachePut(cacheKey, outputPath, format);
}

// ----------------- WAV 拼接 -----------------

function concatAudioFiles(paths, outputPath, format, silenceMs = 0) {
  const buffers = paths.map(p => fs.readFileSync(p));
  if (format === "wav") {
    const dataParts = [];
    let firstHeader = null;
    let firstInfo = null;
    let actualSilenceMs = 0;

    buffers.forEach((buf, i) => {
      const info = parseWavInfo(buf);
      if (i === 0) {
        firstInfo = info;
        firstHeader = buf.subarray(0, info.dataOffset);
      }
      dataParts.push(buf.subarray(info.dataOffset, info.dataOffset + info.dataSize));
      if (i < buffers.length - 1 && firstInfo && silenceMs > 0) {
        const silence = buildWavSilenceBuffer(firstInfo, silenceMs);
        actualSilenceMs = Math.round((silence.length / firstInfo.byteRate) * 1000);
        dataParts.push(silence);
      }
    });

    const combinedData = Buffer.concat(dataParts);
    if (firstHeader) {
      const finalHeader = Buffer.from(firstHeader);
      if (finalHeader.length >= 44) {
        finalHeader.writeUInt32LE(finalHeader.length - 8 + combinedData.length, 4);
        for (let o = 12; o < finalHeader.length - 4; o++) {
          if (finalHeader.toString("ascii", o, o + 4) === "data") {
            finalHeader.writeUInt32LE(combinedData.length, o + 4);
            break;
          }
        }
      }
      fs.writeFileSync(outputPath, Buffer.concat([finalHeader, combinedData]));
    } else {
      fs.writeFileSync(outputPath, Buffer.concat(buffers));
    }
    return { silenceMs: actualSilenceMs };
  } else {
    fs.writeFileSync(outputPath, Buffer.concat(buffers));
    return { silenceMs: 0 };
  }
}

function parseWavInfo(buffer) {
  let dataOffset = 44;
  let dataSizeOffset = 40;
  let dataSize = Math.max(0, buffer.length - dataOffset);
  let channels = 1;
  let sampleRate = 24000;
  let byteRate = 48000;
  let blockAlign = 2;
  let bitsPerSample = 16;

  for (let o = 12; o + 8 <= buffer.length;) {
    const chunkId = buffer.toString("ascii", o, o + 4);
    const chunkSize = buffer.readUInt32LE(o + 4);
    const chunkData = o + 8;
    if (chunkId === "fmt " && chunkData + 16 <= buffer.length) {
      channels = buffer.readUInt16LE(chunkData + 2);
      sampleRate = buffer.readUInt32LE(chunkData + 4);
      byteRate = buffer.readUInt32LE(chunkData + 8);
      blockAlign = buffer.readUInt16LE(chunkData + 12);
      bitsPerSample = buffer.readUInt16LE(chunkData + 14);
    }
    if (chunkId === "data") {
      dataOffset = chunkData;
      dataSizeOffset = o + 4;
      dataSize = Math.min(chunkSize, Math.max(0, buffer.length - dataOffset));
      break;
    }
    o = chunkData + chunkSize + (chunkSize % 2);
  }

  return {
    dataOffset,
    dataSizeOffset,
    dataSize,
    channels,
    sampleRate,
    byteRate: Math.max(1, byteRate),
    blockAlign: Math.max(1, blockAlign),
    bitsPerSample
  };
}

function buildWavSilenceBuffer(info, silenceMs) {
  const rawBytes = Math.max(0, Math.round(info.byteRate * silenceMs / 1000));
  const alignedBytes = Math.max(info.blockAlign, Math.round(rawBytes / info.blockAlign) * info.blockAlign);
  return Buffer.alloc(alignedBytes);
}

function readAudioDurationMs(filePath, format) {
  if (format !== "wav") return 0;
  const info = parseWavInfo(fs.readFileSync(filePath));
  return Math.max(1, Math.round((info.dataSize / info.byteRate) * 1000));
}

module.exports = {
  generateAudioFile,
  compressFinalAudio,
  ffmpegAvailable,
  __test: {
    ttsCacheKey,
    splitTranscriptSentences,
    buildDialogueSentenceJobs,
    buildAudioSegmentIndex,
    parseWavInfo,
    buildVoiceProfileWithDirectives,
    voiceDirectivesFromOptions
  }
};
