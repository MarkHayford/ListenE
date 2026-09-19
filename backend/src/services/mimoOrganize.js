const { settings } = require("../config");
const { callMimoChatRaw, extractJsonFromContent } = require("./mimoAgentMedia");

// AI 一键整理：把用户「收藏的项目」(工作区/卡片/文件) 按标题+摘要归纳成几个清晰分类（像相册），
// 并把每个项目分到最合适的一个分类。只用文本模型，产出结构化 JSON，供客户端预览后套用。

function strClean(v, max) {
  return String(v == null ? "" : v).replace(/\s+/g, " ").trim().slice(0, max);
}

function normalizeItems(value) {
  if (!Array.isArray(value)) return [];
  const seen = new Set();
  const out = [];
  for (const it of value) {
    const id = strClean(it && (it.id != null ? it.id : it.itemId), 200);
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push({ id, title: strClean(it && it.title, 200), summary: strClean(it && it.summary, 300) });
    if (out.length >= 300) break;
  }
  return out;
}

function normalizeNames(value, max) {
  if (!Array.isArray(value)) return [];
  const out = [];
  const seen = new Set();
  for (const x of value) {
    const n = strClean(x && (x.name != null ? x.name : x), 24);
    if (!n) continue;
    const key = n.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(n);
    if (out.length >= max) break;
  }
  return out;
}

function buildInstruction(maxCategories, existing) {
  const lines = [
    "你在帮用户把「收藏的项目」整理成几个清晰的分类（像相册那样），方便以后查找。",
    `请根据每个项目的标题/摘要，归纳出不超过 ${maxCategories} 个简洁的中文分类名（每个 2-6 字，互不重叠、覆盖主要主题），并把每个项目分到最合适的一个分类。`,
    "相似的项目要放进同一个分类，避免每个项目自成一类；确实无法归类的项目可以不分配。"
  ];
  if (existing && existing.length) {
    lines.push(`已有分类可优先复用（合适就用，不合适再新建）：${existing.join("、")}。`);
  }
  lines.push('只输出一个 JSON 对象：{"categories":["分类名",...],"assignments":[{"id":"项目id","category":"分类名"},...]}');
  lines.push("category 必须是 categories 里出现过的名字；id 必须来自我给你的项目 id。");
  return lines.join("\n");
}

function normalizeResult(obj, items, maxC) {
  const o = obj && typeof obj === "object" ? obj : {};
  let categories = normalizeNames(o.categories, maxC);
  const catCanon = new Map(categories.map((c) => [c.toLowerCase(), c]));
  const validIds = new Set(items.map((it) => it.id));
  const rawAssign = Array.isArray(o.assignments) ? o.assignments : [];
  const assignments = [];
  const usedCats = new Set();
  const seenIds = new Set();
  for (const a of rawAssign) {
    const id = strClean(a && a.id, 200);
    const category = strClean(a && a.category, 24);
    if (!id || !validIds.has(id) || seenIds.has(id)) continue;
    const canon = catCanon.get(category.toLowerCase());
    if (!canon) continue;
    assignments.push({ id, category: canon });
    usedCats.add(canon.toLowerCase());
    seenIds.add(id);
  }
  categories = categories.filter((c) => usedCats.has(c.toLowerCase()));
  if (categories.length === 0) throw new Error("整理失败：没能归纳出分类，请稍后再试");
  return { categories, assignments };
}

async function organizeItems({ items = [], existingCategories = [], maxCategories = 8, callModel } = {}) {
  const list = normalizeItems(items);
  if (list.length === 0) throw new Error("没有可整理的项目");
  const maxC = Math.min(12, Math.max(2, Number(maxCategories) || 8));
  const existing = normalizeNames(existingCategories, maxC);

  const instruction = buildInstruction(maxC, existing);
  const itemsText = list
    .map((it, i) => `${i + 1}. [id=${it.id}] ${it.title || "(无标题)"}${it.summary ? " —— " + it.summary : ""}`)
    .join("\n");
  const userText = `${instruction}\n\n待整理项目（共 ${list.length} 个）：\n${itemsText}`;

  const call = typeof callModel === "function" ? callModel : callMimoChatRaw;
  const data = await call([{ role: "user", content: userText }], {
    model: settings.mimoTextModel,
    temperature: 0.2,
    maxTokens: Math.min(4000, 800 + list.length * 40),
    json: true
  });
  const obj = extractJsonFromContent(data && data.choices && data.choices[0] && data.choices[0].message && data.choices[0].message.content);
  return normalizeResult(obj, list, maxC);
}

module.exports = {
  organizeItems,
  __test: { normalizeItems, normalizeNames, normalizeResult, buildInstruction }
};
