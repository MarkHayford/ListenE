// 听力素材「真二进制 zip」打包：把原文 transcript、题目 questions、音频字节一起压成一个真正的 .zip 档案，
// 存到 storage/packages/ 并返回限时下载链接（与音频静态服务/清理机制一致）。
// 与「清单文本」导出（agentOutputExport 的 buildAgentListeningZipExportContent）不同，这里产出可双击解压的二进制包。

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const AdmZip = require("adm-zip");
const { settings } = require("../config");

const PACKAGE_MAX_AGE_MS = 30 * 60 * 1000;

function packagesDir() {
  return settings.packagesDir || path.join(path.resolve(__dirname, ".."), "storage", "packages");
}

function sanitizePackageTitle(name, fallback = "ListenE-Listening") {
  const clean = String(name || "")
    .replace(/[\\/:*?"<>|]+/g, "_")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 60);
  return clean || fallback;
}

function audioFileNameFromUrl(audioUrl) {
  const raw = String(audioUrl || "").split("?")[0].split("#")[0];
  const name = (raw.split("/").pop() || "").trim();
  return /^[\w.-]+\.(wav|mp3|m4a|ogg|aac)$/i.test(name) ? name : "";
}

// SSRF 防护：audioUrl 来自请求体且本端点无需登录。若不加限制地 fetch，攻击者可让服务器
// 代取内网服务 / 云元数据(169.254.169.254) 并从返回的 zip 里读回响应。故只放行「本服务
// 自身对外地址(publicBaseUrl 同源)」——音频本就由本服务在该域生成；额外 CDN 域用
// PACKAGE_AUDIO_FETCH_HOSTS(逗号分隔主机名) 显式放行。其它一律拒绝（退回“音频不可达”）。
function allowedAudioFetchHosts() {
  const hosts = new Set();
  try {
    if (settings.publicBaseUrl) hosts.add(new URL(settings.publicBaseUrl).host.toLowerCase());
  } catch (_) {}
  String(process.env.PACKAGE_AUDIO_FETCH_HOSTS || "")
    .split(",")
    .map((h) => h.trim().toLowerCase())
    .filter(Boolean)
    .forEach((h) => hosts.add(h));
  return hosts;
}

function isAllowedAudioFetchUrl(audioUrl) {
  let parsed;
  try {
    parsed = new URL(String(audioUrl || ""));
  } catch (_) {
    return false;
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") return false;
  const allowed = allowedAudioFetchHosts();
  // 同源(host 含端口) 优先；仅当显式配置了额外主机名时才按 hostname 放行(CDN 常换端口/协议)。
  if (allowed.has(parsed.host.toLowerCase())) return true;
  if (allowed.has(parsed.hostname.toLowerCase())) return true;
  return false;
}

// 优先读取本机 storage/audio（音频本就由本服务生成）；否则在同源白名单内尝试 fetch 远程 URL。
async function resolveAudioBytes(audioUrl) {
  const name = audioFileNameFromUrl(audioUrl);
  if (name) {
    const localPath = path.join(settings.audioDir, name);
    try {
      if (fs.existsSync(localPath)) return { bytes: fs.readFileSync(localPath), ext: (name.split(".").pop() || "wav").toLowerCase() };
    } catch (_) {}
  }
  if (/^https?:\/\//i.test(String(audioUrl || "")) && isAllowedAudioFetchUrl(audioUrl)) {
    try {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), Math.max(5000, settings.mimoTtsTimeoutMs || 120000));
      const resp = await fetch(audioUrl, { signal: controller.signal, redirect: "error" });
      clearTimeout(timer);
      if (resp && resp.ok) {
        const bytes = Buffer.from(await resp.arrayBuffer());
        if (bytes.length) return { bytes, ext: (name.split(".").pop() || "wav").toLowerCase() };
      }
    } catch (_) {}
  }
  return null;
}

function formatPackageQuestionsText(questions, title) {
  const rows = [];
  (Array.isArray(questions) ? questions : []).forEach((q, index) => {
    const questionText = String((q && (q.questionText || q.question)) || "").trim();
    if (!questionText) return;
    rows.push(`${index + 1}. ${questionText}`);
    const options = Array.isArray(q.options) ? q.options : [];
    options.forEach((option, oi) => rows.push(`${String.fromCharCode(65 + oi)}. ${String(option).replace(/^[A-H][).、]\s*/i, "").trim()}`));
    let answer = "";
    if (Number.isInteger(q.correctAnswer) && q.correctAnswer >= 0 && q.correctAnswer < options.length) answer = String.fromCharCode(65 + q.correctAnswer);
    else if (String(q.correctAnswer ?? "").trim() !== "" && Number.isInteger(Number(q.correctAnswer)) && Number(q.correctAnswer) >= 0) answer = String.fromCharCode(65 + Number(q.correctAnswer));
    if (answer) rows.push(`Correct answer: ${answer}`);
    const explanation = String(q.explanation || "").trim();
    if (explanation) rows.push(`Explanation: ${explanation}`);
    rows.push("");
  });
  return [title || "ListenE Listening Practice", "", ...rows].join("\n").trim();
}

// 生成真二进制 zip。record: { title/scene, script/scriptPreview, questions[], audioUrl/audio }
async function buildListeningPackageZip(record = {}) {
  if (!record || typeof record !== "object") throw new Error("invalid listening record");
  const title = sanitizePackageTitle(record.title || record.scene || "ListenE-Listening");
  const script = String(record.script || record.scriptPreview || "").trim();
  const questions = Array.isArray(record.questions) ? record.questions : [];
  const audioUrl = String(record.audioUrl || record.audio || "").trim();
  if (!script && questions.length === 0 && !audioUrl) {
    throw new Error("no listening material to package");
  }

  const zip = new AdmZip();
  zip.addFile("transcript.txt", Buffer.from(script || "Transcript unavailable.", "utf8"));
  zip.addFile("questions.txt", Buffer.from(formatPackageQuestionsText(questions, title), "utf8"));

  let audioIncluded = false;
  let audioNote = "Audio unavailable.";
  const audio = audioUrl ? await resolveAudioBytes(audioUrl) : null;
  if (audio && audio.bytes && audio.bytes.length) {
    const ext = /^(wav|mp3|m4a|ogg|aac)$/i.test(audio.ext) ? audio.ext.toLowerCase() : "wav";
    zip.addFile(`audio.${ext}`, audio.bytes);
    audioIncluded = true;
    audioNote = `audio.${ext} (${audio.bytes.length} bytes)`;
  } else if (audioUrl) {
    zip.addFile("audio_url.txt", Buffer.from(audioUrl, "utf8"));
    audioNote = `Audio not embedded (source unreachable); see audio_url.txt: ${audioUrl}`;
  }
  zip.addFile(
    "README.txt",
    Buffer.from([`Title: ${title}`, "", "Contents:", "- transcript.txt", "- questions.txt", audioIncluded ? `- audio.*` : "- audio_url.txt", "", audioNote].join("\n"), "utf8")
  );

  const dir = packagesDir();
  fs.mkdirSync(dir, { recursive: true });
  const token = `${Date.now().toString(36)}_${crypto.randomBytes(4).toString("hex")}`;
  const storedName = `${token}.zip`;
  const fullPath = path.join(dir, storedName);
  zip.writeZip(fullPath);
  const sizeBytes = fs.statSync(fullPath).size;

  return {
    fileName: `${title}.zip`,
    storedName,
    path: `/api/v1/packages/${storedName}`,
    url: `${settings.publicBaseUrl}/packages/${storedName}`,
    sizeBytes,
    audioIncluded
  };
}

function cleanupExpiredPackages(maxAgeMs = PACKAGE_MAX_AGE_MS) {
  try {
    const dir = packagesDir();
    if (!fs.existsSync(dir)) return;
    const now = Date.now();
    for (const file of fs.readdirSync(dir)) {
      const fullPath = path.join(dir, file);
      try {
        if (now - fs.statSync(fullPath).mtimeMs > maxAgeMs) fs.unlinkSync(fullPath);
      } catch (_) {}
    }
  } catch (_) {}
}

function startPackageCleanup() {
  return setInterval(() => cleanupExpiredPackages(), 10 * 60 * 1000);
}

module.exports = {
  buildListeningPackageZip,
  cleanupExpiredPackages,
  startPackageCleanup,
  packagesDir,
  __test: { formatPackageQuestionsText, audioFileNameFromUrl, sanitizePackageTitle, isAllowedAudioFetchUrl, resolveAudioBytes }
};
