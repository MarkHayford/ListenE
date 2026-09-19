// 错题本 + 间隔复习（Leitner SRS，云端、按用户）。
// 练习答错自动加入；复习时「记得」升盒（拉长间隔），「忘了」回到 0 盒（很快再练）。
const crypto = require("crypto");
const { query } = require("./db");
const { deriveSkill } = require("./studyProgress");
const { updateUserModel } = require("./userModel");

const HOUR = 3600 * 1000;
const DAY = 24 * HOUR;
// Leitner 盒子 0..5 的复习间隔。
const SRS_INTERVALS = [4 * HOUR, 1 * DAY, 2 * DAY, 4 * DAY, 7 * DAY, 15 * DAY];
const MAX_BOX = SRS_INTERVALS.length - 1;
const KINDS = new Set(["mcq", "cloze", "blank"]);

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

// 新答错（或再次答错）的错题「立即可复习」：入库即到期(due_at = now)，避免
// 「刚错的题 4 小时内无法复习」。只有第一次复习评分后，才按 Leitner 间隔(nextDue)拉长。
function initialDueAt(now) {
  return now;
}

function stableId(componentType, prompt, answer) {
  return "rv_" + crypto.createHash("sha1").update(`${componentType}|${prompt}|${answer}`).digest("hex").slice(0, 16);
}

function normalizeOptions(value) {
  if (!Array.isArray(value)) return [];
  return value.map((v) => compact(v, 200)).filter(Boolean).slice(0, 8);
}

function normalizeItemInput(item, now) {
  const componentType = compact(item?.componentType || item?.component_type || item?.type, 40).toLowerCase();
  const prompt = compact(item?.prompt || item?.questionText || item?.text, 600);
  const answer = compact(item?.answer || item?.correctAnswer, 400);
  if (!prompt || !answer) return null;
  const kindRaw = compact(item?.kind, 16).toLowerCase();
  const kind = KINDS.has(kindRaw) ? kindRaw : "mcq";
  const skill = compact(item?.skill, 12) || deriveSkill(componentType);
  return {
    id: compact(item?.id, 60) || stableId(componentType, prompt, answer),
    kind,
    componentType,
    skill,
    prompt,
    options: normalizeOptions(item?.options),
    answer,
    explanation: compact(item?.explanation, 600)
  };
}

function itemFromRow(row, now = Date.now()) {
  return {
    id: row.id,
    kind: row.kind || "mcq",
    componentType: row.component_type || "",
    skill: row.skill || "",
    prompt: row.prompt || "",
    options: Array.isArray(row.options) ? row.options : (runParseJsonArray(row.options)),
    answer: row.answer || "",
    explanation: row.explanation || "",
    box: clampBox(row.box),
    dueAt: Number(row.due_at || 0),
    timesWrong: Number(row.times_wrong || 0),
    timesReviewed: Number(row.times_reviewed || 0),
    due: Number(row.due_at || 0) <= now,
    createdAt: Number(row.created_at || 0),
    updatedAt: Number(row.updated_at || 0)
  };
}

function runParseJsonArray(value) {
  if (Array.isArray(value)) return value;
  try {
    const parsed = JSON.parse(String(value || "[]"));
    return Array.isArray(parsed) ? parsed : [];
  } catch (_) {
    return [];
  }
}

async function addWrong(user, item = {}) {
  if (!user) throw new Error("unauthorized");
  const now = Date.now();
  const p = normalizeItemInput(item, now);
  if (!p) throw new Error("prompt and answer are required");
  // 答错（或再次答错）：重置到 0 盒并立即可复习（due_at = now），错误次数 +1。
  const result = await query(
    `INSERT INTO review_items (user_id, id, kind, component_type, skill, prompt, options, answer, explanation, box, due_at, times_wrong, times_reviewed, created_at, updated_at)
     VALUES ($1,$2,$3,$4,$5,$6,$7::jsonb,$8,$9,0,$10,1,0,$11,$11)
     ON CONFLICT (user_id, id) DO UPDATE SET
       box = 0,
       due_at = $10,
       times_wrong = review_items.times_wrong + 1,
       prompt = EXCLUDED.prompt,
       options = EXCLUDED.options,
       answer = EXCLUDED.answer,
       explanation = EXCLUDED.explanation,
       skill = EXCLUDED.skill,
       updated_at = $11
     RETURNING *`,
    [user.id, p.id, p.kind, p.componentType, p.skill, p.prompt, JSON.stringify(p.options), p.answer, p.explanation, initialDueAt(now), now]
  );
  const saved = itemFromRow(result.rows[0], now);
  // opt3：高频错点（累计错 ≥3 次）回灌统一用户模型，作为稳定弱点。best-effort，绝不影响加错题本。
  if (saved.timesWrong >= 3) {
    const label = [saved.skill, saved.prompt].filter(Boolean).join("：").replace(/\s+/g, " ").trim().slice(0, 120);
    if (label) {
      try { await updateUserModel(user, { weaknesses: [label] }); } catch (_) { /* 忽略 */ }
    }
  }
  return { added: true, item: saved, persisted: true };
}

async function listItems(user) {
  if (!user) return { items: [], dueCount: 0, total: 0, persisted: false };
  const now = Date.now();
  const result = await query(
    `SELECT * FROM review_items WHERE user_id = $1 ORDER BY due_at ASC LIMIT 1000`,
    [user.id]
  );
  const items = result.rows.map((r) => itemFromRow(r, now));
  return { items, dueCount: items.filter((i) => i.due).length, total: items.length, persisted: true };
}

async function gradeItem(user, id, remembered) {
  if (!user) throw new Error("unauthorized");
  const itemId = compact(id, 60);
  if (!itemId) throw new Error("item id is required");
  const now = Date.now();
  const current = await query(`SELECT box FROM review_items WHERE user_id = $1 AND id = $2`, [user.id, itemId]);
  if (!current.rows.length) throw new Error("review item not found");
  const box = remembered ? clampBox(Number(current.rows[0].box) + 1) : 0;
  const result = await query(
    `UPDATE review_items SET box = $3, due_at = $4, times_reviewed = times_reviewed + 1, updated_at = $5
     WHERE user_id = $1 AND id = $2 RETURNING *`,
    [user.id, itemId, box, nextDue(box, now), now]
  );
  return { item: itemFromRow(result.rows[0], now), persisted: true };
}

async function removeItem(user, id) {
  if (!user) throw new Error("unauthorized");
  const itemId = compact(id, 60);
  if (!itemId) throw new Error("item id is required");
  await query(`DELETE FROM review_items WHERE user_id = $1 AND id = $2`, [user.id, itemId]);
  return { deleted: true, id: itemId, persisted: true };
}

module.exports = {
  addWrong,
  listItems,
  gradeItem,
  removeItem,
  __test: { normalizeItemInput, nextDue, initialDueAt, clampBox, stableId, itemFromRow, SRS_INTERVALS, MAX_BOX }
};
