const crypto = require("crypto");
const { query } = require("./db");

const kinds = new Set(["file", "card", "plugin"]);

function compactText(text, max = 260) {
  const value = String(text || "").replace(/\s+/g, " ").trim();
  return value.length > max ? value.slice(0, max - 1) : value;
}

function normalizeKind(kind) {
  const value = String(kind || "").toLowerCase();
  if (!kinds.has(value)) throw new Error("invalid library kind");
  return value;
}

function itemFromRow(row) {
  return {
    id: row.id,
    kind: row.kind,
    title: row.title || "",
    summary: row.summary || "",
    data: row.data || {},
    createdAt: Number(row.created_at || 0),
    updatedAt: Number(row.updated_at || 0)
  };
}

async function listLibraryItems(kind, user) {
  normalizeKind(kind);
  if (!user) return { items: [], persisted: false };
  const result = await query(
    `SELECT id, kind, title, summary, data, created_at, updated_at
     FROM user_library_items
     WHERE user_id = $1 AND kind = $2
     ORDER BY updated_at DESC
     LIMIT 200`,
    [user.id, kind]
  );
  return { items: result.rows.map(itemFromRow), persisted: true };
}

async function createLibraryItem(kind, user, body = {}) {
  normalizeKind(kind);
  if (!user) throw new Error("unauthorized");
  const now = Date.now();
  const id = compactText(body.id, 120) || `${kind}_${crypto.randomUUID()}`;
  const title = compactText(body.title, 140);
  const summary = compactText(body.summary || body.description, 500);
  const data = body.data && typeof body.data === "object" ? body.data : {};
  const result = await query(
    `INSERT INTO user_library_items (user_id, id, kind, title, summary, data, created_at, updated_at)
     VALUES ($1, $2, $3, $4, $5, $6::jsonb, $7, $7)
     ON CONFLICT (user_id, id) DO UPDATE SET
       kind = EXCLUDED.kind,
       title = EXCLUDED.title,
       summary = EXCLUDED.summary,
       data = EXCLUDED.data,
       updated_at = EXCLUDED.updated_at
     RETURNING id, kind, title, summary, data, created_at, updated_at`,
    [user.id, id, kind, title, summary, JSON.stringify(data), now]
  );
  return { item: itemFromRow(result.rows[0]), persisted: true };
}

async function deleteLibraryItem(kind, id, user) {
  normalizeKind(kind);
  if (!user) throw new Error("unauthorized");
  await query(
    `DELETE FROM user_library_items WHERE user_id = $1 AND kind = $2 AND id = $3`,
    [user.id, kind, id]
  );
  return { deleted: true, itemId: id, persisted: true };
}

async function deleteLibraryItemsForWorkspace(workspaceId, user) {
  const id = compactText(workspaceId, 120);
  if (!id) return { deleted: 0, workspaceId: id, persisted: Boolean(user) };
  if (!user) return { deleted: 0, workspaceId: id, persisted: false };
  const result = await query(
    `DELETE FROM user_library_items
     WHERE user_id = $1
       AND kind IN ('file', 'card')
       AND data->>'workspaceId' = $2`,
    [user.id, id]
  );
  return { deleted: result.rowCount || 0, workspaceId: id, persisted: true };
}

module.exports = {
  createLibraryItem,
  deleteLibraryItemsForWorkspace,
  deleteLibraryItem,
  listLibraryItems
};
