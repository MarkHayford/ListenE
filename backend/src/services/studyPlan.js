// 学习计划（云端、按用户）：内容只能由 AI 写入（createPlans），用户只能改时间/重复（updatePlan）或删除。
const crypto = require("crypto");
const { query } = require("./db");

const RECURRENCES = new Set(["none", "daily", "weekly"]);

function compactText(text, max = 280) {
  const value = String(text || "").replace(/\s+/g, " ").trim();
  return value.length > max ? value.slice(0, max - 1) : value;
}

function normalizeRecurrence(value) {
  const v = String(value || "none").toLowerCase().trim();
  return RECURRENCES.has(v) ? v : "none";
}

function normalizeScheduledAt(value) {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return 0;
  return Math.round(n);
}

function planFromRow(row) {
  return {
    id: row.id,
    title: row.title || "",
    detail: row.detail || "",
    scheduledAt: Number(row.scheduled_at || 0),
    recurrence: row.recurrence || "none",
    createdAt: Number(row.created_at || 0),
    updatedAt: Number(row.updated_at || 0)
  };
}

function normalizePlanInput(item, now) {
  const title = compactText(item?.title, 160);
  if (!title) return null;
  return {
    id: compactText(item?.id, 120) || `plan_${now}_${crypto.randomUUID().slice(0, 8)}`,
    title,
    detail: compactText(item?.detail || item?.content || item?.description, 600),
    scheduledAt: normalizeScheduledAt(item?.scheduledAt ?? item?.scheduled_at ?? item?.time),
    recurrence: normalizeRecurrence(item?.recurrence)
  };
}

async function listPlans(user) {
  if (!user) return { plans: [], persisted: false };
  const result = await query(
    `SELECT id, title, detail, scheduled_at, recurrence, created_at, updated_at
     FROM study_plans
     WHERE user_id = $1
     ORDER BY scheduled_at ASC, created_at ASC
     LIMIT 500`,
    [user.id]
  );
  return { plans: result.rows.map(planFromRow), persisted: true };
}

async function createPlans(user, items = []) {
  if (!user) throw new Error("unauthorized");
  const list = Array.isArray(items) ? items : [];
  const now = Date.now();
  const normalized = list.map((it) => normalizePlanInput(it, now)).filter(Boolean).slice(0, 100);
  if (!normalized.length) throw new Error("no valid plan items");
  const created = [];
  for (const p of normalized) {
    const result = await query(
      `INSERT INTO study_plans (user_id, id, title, detail, scheduled_at, recurrence, data, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, '{}'::jsonb, $7, $7)
       ON CONFLICT (user_id, id) DO UPDATE SET
         title = EXCLUDED.title,
         detail = EXCLUDED.detail,
         scheduled_at = EXCLUDED.scheduled_at,
         recurrence = EXCLUDED.recurrence,
         updated_at = EXCLUDED.updated_at
       RETURNING id, title, detail, scheduled_at, recurrence, created_at, updated_at`,
      [user.id, p.id, p.title, p.detail, p.scheduledAt, p.recurrence, now]
    );
    created.push(planFromRow(result.rows[0]));
  }
  return { plans: created, persisted: true };
}

async function updatePlan(user, id, patch = {}) {
  if (!user) throw new Error("unauthorized");
  const planId = compactText(id, 120);
  if (!planId) throw new Error("plan id is required");
  // 用户只能改时间和重复；标题/内容只能由 AI 写入，这里不允许改。
  const fields = [];
  const params = [user.id, planId];
  let idx = 3;
  if (patch.scheduledAt !== undefined || patch.scheduled_at !== undefined || patch.time !== undefined) {
    fields.push(`scheduled_at = $${idx++}`);
    params.push(normalizeScheduledAt(patch.scheduledAt ?? patch.scheduled_at ?? patch.time));
  }
  if (patch.recurrence !== undefined) {
    fields.push(`recurrence = $${idx++}`);
    params.push(normalizeRecurrence(patch.recurrence));
  }
  if (!fields.length) throw new Error("no editable fields");
  fields.push(`updated_at = $${idx++}`);
  params.push(Date.now());
  const result = await query(
    `UPDATE study_plans SET ${fields.join(", ")}
     WHERE user_id = $1 AND id = $2
     RETURNING id, title, detail, scheduled_at, recurrence, created_at, updated_at`,
    params
  );
  if (!result.rows.length) throw new Error("plan not found");
  return { plan: planFromRow(result.rows[0]), persisted: true };
}

async function deletePlan(user, id) {
  if (!user) throw new Error("unauthorized");
  const planId = compactText(id, 120);
  if (!planId) throw new Error("plan id is required");
  await query(`DELETE FROM study_plans WHERE user_id = $1 AND id = $2`, [user.id, planId]);
  return { deleted: true, planId, persisted: true };
}

module.exports = {
  listPlans,
  createPlans,
  updatePlan,
  deletePlan,
  __test: { normalizePlanInput, normalizeRecurrence, normalizeScheduledAt, planFromRow }
};
