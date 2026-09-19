"use strict";

// 统一用户模型（方案 #4B）：跨功能、跨工作区的一份用户画像——能力(六技能 0..100) + 偏好 + 稳定弱点 + 备注。
// 汇聚各端信号：客户端上报 LearnerModelStore 能力；服务端 analysis 弱点回灌；聊天时作为全局上下文注入。
// 与「工作区级记忆」(memorySummary/memory，仅当前工作区)互补：这份是跨工作区、跨功能的长期画像。

const { query } = require("./db");
const { clampAbility, abilityToCefr } = require("./learnerModel");

const SKILLS = Object.freeze(["听力", "词汇", "语法", "阅读", "写作", "口语"]);
const MAX_LIST = 12;

function compact(value, max) {
  const s = String(value == null ? "" : value).replace(/\s+/g, " ").trim();
  return s.length > max ? s.slice(0, max) : s;
}

function normalizeAbilities(value) {
  const out = {};
  const o = value && typeof value === "object" ? value : {};
  for (const skill of SKILLS) {
    if (o[skill] != null && Number.isFinite(Number(o[skill]))) out[skill] = clampAbility(o[skill]);
  }
  return out;
}

function strList(value, max = MAX_LIST, itemMax = 120) {
  if (!Array.isArray(value)) return [];
  const seen = new Set();
  const out = [];
  for (const item of value) {
    const s = compact(item, itemMax);
    const key = s.toLowerCase();
    if (s && !seen.has(key)) {
      seen.add(key);
      out.push(s);
      if (out.length >= max) break;
    }
  }
  return out;
}

function normalizeUserModel(data) {
  const o = data && typeof data === "object" ? data : {};
  return {
    abilities: normalizeAbilities(o.abilities),
    preferences: strList(o.preferences),
    weaknesses: strList(o.weaknesses),
    notes: compact(o.notes, 500),
    updatedAt: Number(o.updatedAt) || 0
  };
}

// 合并补丁：能力取「新值优先」(端上是最新权威估计)；列表去重合并并裁剪；notes 给则覆盖。
function mergeUserModel(existing, patch, now = Date.now()) {
  const base = normalizeUserModel(existing);
  const inc = patch && typeof patch === "object" ? patch : {};
  const abilities = { ...base.abilities };
  const incAbilities = normalizeAbilities(inc.abilities);
  for (const skill of SKILLS) if (incAbilities[skill] != null) abilities[skill] = incAbilities[skill];
  const preferences = strList([...base.preferences, ...(Array.isArray(inc.preferences) ? inc.preferences : [])]);
  const weaknesses = strList([...base.weaknesses, ...(Array.isArray(inc.weaknesses) ? inc.weaknesses : [])]);
  const notes = inc.notes != null ? compact(inc.notes, 500) : base.notes;
  return { abilities, preferences, weaknesses, notes, updatedAt: now };
}

// 给 AI 的全局用户模型提示（稳定画像；与本轮冲突以本轮为准）。空模型返回空串（不污染 prompt）。
function buildUserModelHint(model) {
  const m = normalizeUserModel(model);
  const skillsWithAbility = SKILLS.filter((s) => m.abilities[s] != null);
  if (!skillsWithAbility.length && !m.preferences.length && !m.weaknesses.length && !m.notes) return "";
  const lines = ["全局用户模型（跨功能长期画像，非本工作区专属；与本轮明确要求冲突时以本轮为准）："];
  if (skillsWithAbility.length) {
    const avg = skillsWithAbility.reduce((a, s) => a + m.abilities[s], 0) / skillsWithAbility.length;
    const detail = skillsWithAbility.map((s) => `${s}${Math.round(m.abilities[s])}`).join("/");
    lines.push(`- 水平：约 ${abilityToCefr(avg)}（CEFR）；分技能 ${detail}。讲解与例子难度请贴合该水平。`);
  }
  if (m.weaknesses.length) lines.push(`- 稳定弱点：${m.weaknesses.slice(0, 6).join("；")}`);
  if (m.preferences.length) lines.push(`- 偏好：${m.preferences.slice(0, 6).join("；")}`);
  if (m.notes) lines.push(`- 备注：${m.notes}`);
  return lines.join("\n");
}

async function getUserModel(user) {
  if (!user) return normalizeUserModel(null);
  const result = await query("SELECT data, updated_at FROM user_model WHERE user_id = $1", [user.id]);
  const row = result.rows[0];
  return normalizeUserModel(row ? { ...row.data, updatedAt: Number(row.updated_at) || 0 } : null);
}

async function updateUserModel(user, patch, now = Date.now()) {
  const merged = mergeUserModel(user ? await getUserModel(user) : null, patch, now);
  if (!user) return merged;
  await query(
    `INSERT INTO user_model (user_id, data, updated_at) VALUES ($1, $2::jsonb, $3)
     ON CONFLICT (user_id) DO UPDATE SET data = EXCLUDED.data, updated_at = EXCLUDED.updated_at`,
    [user.id, JSON.stringify(merged), now]
  );
  return merged;
}

module.exports = {
  SKILLS,
  getUserModel,
  updateUserModel,
  buildUserModelHint,
  __test: { normalizeUserModel, mergeUserModel, buildUserModelHint, normalizeAbilities, strList }
};
