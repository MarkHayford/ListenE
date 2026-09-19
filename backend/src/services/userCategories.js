"use strict";

// 相册式分类的云端同步（P4）：每用户一份「分类清单 + 归属映射」，按域(workspace/cards/files)分开存。
// 整块 JSONB 存取(last-write-wins)；服务端只做规模/字段的归一化校验，不涉业务合并。

const { query } = require("./db");

const DOMAINS = Object.freeze(["workspace", "cards", "files"]);
const MAX_CATS = 100;
const MAX_ASSIGN = 5000;

function compact(value, max) {
  return String(value == null ? "" : value).replace(/\s+/g, " ").trim().slice(0, max);
}

function normalizeDomain(domain) {
  const o = domain && typeof domain === "object" ? domain : {};
  const rawCats = Array.isArray(o.categories) ? o.categories : [];
  const categories = [];
  const seen = new Set();
  for (const c of rawCats) {
    const id = compact(c && c.id, 80);
    const name = compact(c && c.name, 40);
    if (!id || !name || seen.has(id)) continue;
    seen.add(id);
    categories.push({ id, name });
    if (categories.length >= MAX_CATS) break;
  }
  const validIds = new Set(categories.map((c) => c.id));
  const rawAssign = o.assignments && typeof o.assignments === "object" ? o.assignments : {};
  const assignments = {};
  let n = 0;
  for (const key of Object.keys(rawAssign)) {
    if (n >= MAX_ASSIGN) break;
    const itemId = compact(key, 200);
    const catId = compact(rawAssign[key], 80);
    if (!itemId || !catId || !validIds.has(catId)) continue;
    assignments[itemId] = catId;
    n++;
  }
  return { categories, assignments };
}

function normalizeCategories(data) {
  const o = data && typeof data === "object" ? data : {};
  const out = {};
  for (const dom of DOMAINS) out[dom] = normalizeDomain(o[dom]);
  return out;
}

async function getCategories(user) {
  if (!user) return { data: normalizeCategories(null), updatedAt: 0 };
  const result = await query("SELECT data, updated_at FROM user_categories WHERE user_id = $1", [user.id]);
  const row = result.rows[0];
  return {
    data: normalizeCategories(row ? row.data : null),
    updatedAt: row ? Number(row.updated_at) || 0 : 0
  };
}

async function putCategories(user, data, now = Date.now()) {
  const clean = normalizeCategories(data);
  if (!user) return { data: clean, updatedAt: now };
  await query(
    `INSERT INTO user_categories (user_id, data, updated_at) VALUES ($1, $2::jsonb, $3)
     ON CONFLICT (user_id) DO UPDATE SET data = EXCLUDED.data, updated_at = EXCLUDED.updated_at`,
    [user.id, JSON.stringify(clean), now]
  );
  return { data: clean, updatedAt: now };
}

module.exports = {
  DOMAINS,
  getCategories,
  putCategories,
  __test: { normalizeCategories, normalizeDomain }
};
