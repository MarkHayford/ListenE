// 生词本（云端、按用户）：AI 查词 + Leitner 间隔复习。
const crypto = require("crypto");
const { query } = require("./db");
const { settings } = require("../config");
const { callMimoChatRaw, extractJsonFromContent } = require("./mimoAgentMedia");

const HOUR = 3600 * 1000;
const DAY = 24 * HOUR;
const SRS_INTERVALS = [4 * HOUR, 1 * DAY, 2 * DAY, 4 * DAY, 7 * DAY, 15 * DAY];
const MAX_BOX = SRS_INTERVALS.length - 1;

function compact(text, max) {
  return String(text || "").replace(/\s+/g, " ").trim().slice(0, max);
}
function clampBox(box) {
  const n = Math.round(Number(box));
  if (!Number.isFinite(n)) return 0;
  return Math.max(0, Math.min(MAX_BOX, n));
}
function nextDue(box, now) {
  return now + SRS_INTERVALS[clampBox(box)];
}
function wordId(word) {
  return "vw_" + crypto.createHash("sha1").update(String(word || "").trim().toLowerCase()).digest("hex").slice(0, 16);
}

function normalizeWordInput(item, now) {
  const word = compact(item?.word, 80);
  if (!word) return null;
  return {
    id: wordId(word),
    word,
    phonetic: compact(item?.phonetic, 80),
    meaning: compact(item?.meaning, 400),
    example: compact(item?.example, 400)
  };
}

function wordFromRow(row, now = Date.now()) {
  return {
    id: row.id,
    word: row.word || "",
    phonetic: row.phonetic || "",
    meaning: row.meaning || "",
    example: row.example || "",
    box: clampBox(row.box),
    dueAt: Number(row.due_at || 0),
    timesReviewed: Number(row.times_reviewed || 0),
    due: Number(row.due_at || 0) <= now,
    createdAt: Number(row.created_at || 0),
    updatedAt: Number(row.updated_at || 0)
  };
}

// AI 查词（不落库）：给中国英语学习者一份简明释义。
async function lookupWord({ word = "" } = {}) {
  const term = compact(word, 80);
  if (!term) throw new Error("word is required");
  const prompt = [
    `Define the English word or phrase "${term}" for a Chinese English learner.`,
    "Return ONLY a JSON object:",
    '{"word": string, "phonetic": "IPA like /ˈwɜːrd/ (empty if unsure)", "meaning": "简明中文释义，多个义项用；分隔，可标注词性如 n./v./adj.", "example": "one natural English example sentence followed by its 中文翻译 in parentheses"}'
  ].join("\n");
  const data = await callMimoChatRaw(
    [{ role: "user", content: prompt }],
    { model: settings.mimoTextModel, temperature: 0.2, maxTokens: 400, json: true }
  );
  const obj = extractJsonFromContent(data.choices?.[0]?.message?.content);
  return {
    word: compact(obj.word, 80) || term,
    phonetic: compact(obj.phonetic, 80),
    meaning: compact(obj.meaning, 400),
    example: compact(obj.example, 400)
  };
}

async function addWord(user, item = {}) {
  if (!user) throw new Error("unauthorized");
  const now = Date.now();
  const p = normalizeWordInput(item, now);
  if (!p) throw new Error("word is required");
  const result = await query(
    `INSERT INTO vocab_items (user_id, id, word, phonetic, meaning, example, box, due_at, times_reviewed, created_at, updated_at)
     VALUES ($1,$2,$3,$4,$5,$6,0,$7,0,$8,$8)
     ON CONFLICT (user_id, id) DO UPDATE SET
       phonetic = EXCLUDED.phonetic,
       meaning = EXCLUDED.meaning,
       example = EXCLUDED.example,
       updated_at = $8
     RETURNING *`,
    [user.id, p.id, p.word, p.phonetic, p.meaning, p.example, nextDue(0, now), now]
  );
  return { added: true, item: wordFromRow(result.rows[0], now), persisted: true };
}

async function listWords(user) {
  if (!user) return { items: [], dueCount: 0, total: 0, persisted: false };
  const now = Date.now();
  const result = await query(
    `SELECT * FROM vocab_items WHERE user_id = $1 ORDER BY due_at ASC LIMIT 2000`,
    [user.id]
  );
  const items = result.rows.map((r) => wordFromRow(r, now));
  return { items, dueCount: items.filter((i) => i.due).length, total: items.length, persisted: true };
}

async function gradeWord(user, id, remembered) {
  if (!user) throw new Error("unauthorized");
  const itemId = compact(id, 60);
  if (!itemId) throw new Error("item id is required");
  const now = Date.now();
  const current = await query(`SELECT box FROM vocab_items WHERE user_id = $1 AND id = $2`, [user.id, itemId]);
  if (!current.rows.length) throw new Error("vocab item not found");
  const box = remembered ? clampBox(Number(current.rows[0].box) + 1) : 0;
  const result = await query(
    `UPDATE vocab_items SET box = $3, due_at = $4, times_reviewed = times_reviewed + 1, updated_at = $5
     WHERE user_id = $1 AND id = $2 RETURNING *`,
    [user.id, itemId, box, nextDue(box, now), now]
  );
  return { item: wordFromRow(result.rows[0], now), persisted: true };
}

async function removeWord(user, id) {
  if (!user) throw new Error("unauthorized");
  const itemId = compact(id, 60);
  if (!itemId) throw new Error("item id is required");
  await query(`DELETE FROM vocab_items WHERE user_id = $1 AND id = $2`, [user.id, itemId]);
  return { deleted: true, id: itemId, persisted: true };
}

module.exports = {
  lookupWord,
  addWord,
  listWords,
  gradeWord,
  removeWord,
  __test: { normalizeWordInput, nextDue, clampBox, wordId, wordFromRow, SRS_INTERVALS, MAX_BOX }
};
