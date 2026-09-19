const crypto = require("crypto");
const { settings } = require("../config");
const { query } = require("./db");
const { logger } = require("./logger");
const { clientIpFromRequest } = require("./rateLimit");

const SESSION_TOUCH_INTERVAL_MS = 60 * 1000;
const DEFAULT_ACTIVE_WINDOW_MS = 15 * 60 * 1000;

function compactText(value, max = 512) {
  const text = String(value || "").replace(/\s+/g, " ").trim();
  return text.length > max ? text.slice(0, max) : text;
}

function normalizeEmail(email) {
  return compactText(email, 254).toLowerCase();
}

function requestMeta(request) {
  return {
    ip: compactText(clientIpFromRequest(request), 80),
    userAgent: compactText(request?.headers?.["user-agent"], 512)
  };
}

function tokenExpiresAt(now = Date.now()) {
  return now + settings.authTokenDays * 24 * 60 * 60 * 1000;
}

function iso(value) {
  const n = Number(value || 0);
  return n > 0 ? new Date(n).toISOString() : null;
}

function isSessionActive(row, now = Date.now(), activeWindowMs = DEFAULT_ACTIVE_WINDOW_MS) {
  return Number(row?.revoked_at || row?.revokedAt || 0) === 0
    && Number(row?.expires_at || row?.expiresAt || 0) > now
    && Number(row?.last_seen_at || row?.lastSeenAt || 0) >= now - activeWindowMs;
}

async function createAuthSession({ userId, tokenVersion = 0, request, now = Date.now() }) {
  const id = `ses_${crypto.randomUUID()}`;
  const expiresAt = tokenExpiresAt(now);
  const meta = requestMeta(request);
  try {
    await query(
      `INSERT INTO auth_sessions
       (id, user_id, token_version, ip, user_agent, created_at, last_seen_at, expires_at, revoked_at)
       VALUES ($1, $2, $3, $4, $5, $6, $6, $7, 0)`,
      [id, userId, Number(tokenVersion) || 0, meta.ip, meta.userAgent, now, expiresAt]
    );
    return { id, expiresAt };
  } catch (error) {
    logger.warn("auth session create failed", { detail: error?.message || String(error) });
    return null;
  }
}

async function touchAuthSession(sessionId, userId, request, options = {}) {
  if (!sessionId || !userId) return;
  const now = options.now || Date.now();
  const expiresAt = options.expiresAt || tokenExpiresAt(now);
  const meta = requestMeta(request);
  try {
    await query(
      `UPDATE auth_sessions
       SET last_seen_at = $3,
           expires_at = GREATEST(expires_at, $4::bigint),
           ip = CASE WHEN $5 = '' THEN ip ELSE $5 END,
           user_agent = CASE WHEN $6 = '' THEN user_agent ELSE $6 END
       WHERE id = $1
         AND user_id = $2
         AND revoked_at = 0
         AND last_seen_at < $3 - $7`,
      [sessionId, userId, now, expiresAt, meta.ip, meta.userAgent, SESSION_TOUCH_INTERVAL_MS]
    );
  } catch (error) {
    logger.warn("auth session touch failed", { detail: error?.message || String(error) });
  }
}

async function extendAuthSession(sessionId, userId, request, now = Date.now()) {
  if (!sessionId || !userId) return;
  const expiresAt = tokenExpiresAt(now);
  const meta = requestMeta(request);
  try {
    await query(
      `UPDATE auth_sessions
       SET last_seen_at = $3,
           expires_at = $4,
           ip = CASE WHEN $5 = '' THEN ip ELSE $5 END,
           user_agent = CASE WHEN $6 = '' THEN user_agent ELSE $6 END
       WHERE id = $1 AND user_id = $2 AND revoked_at = 0`,
      [sessionId, userId, now, expiresAt, meta.ip, meta.userAgent]
    );
  } catch (error) {
    logger.warn("auth session extend failed", { detail: error?.message || String(error) });
  }
}

async function verifyAuthSession(payload, request) {
  const sessionId = payload?.sid;
  if (!sessionId) return true;
  try {
    const result = await query(
      `SELECT id, user_id, token_version, expires_at, revoked_at
       FROM auth_sessions WHERE id = $1 AND user_id = $2`,
      [sessionId, payload.sub]
    );
    const row = result.rows[0];
    if (!row) return false;
    if (Number(row.token_version || 0) !== Number(payload.tv || 0)) return false;
    if (Number(row.revoked_at || 0) > 0) return false;
    if (Number(row.expires_at || 0) <= Date.now()) return false;
    await touchAuthSession(sessionId, payload.sub, request);
    return true;
  } catch (error) {
    logger.warn("auth session verify failed", { detail: error?.message || String(error) });
    return false;
  }
}

async function revokeAuthSessionsForUser(userId, request) {
  if (!userId) return;
  const now = Date.now();
  const meta = requestMeta(request);
  try {
    await query(
      `UPDATE auth_sessions
       SET revoked_at = $2,
           last_seen_at = GREATEST(last_seen_at, $2),
           ip = CASE WHEN $3 = '' THEN ip ELSE $3 END,
           user_agent = CASE WHEN $4 = '' THEN user_agent ELSE $4 END
       WHERE user_id = $1 AND revoked_at = 0`,
      [userId, now, meta.ip, meta.userAgent]
    );
  } catch (error) {
    logger.warn("auth session revoke failed", { detail: error?.message || String(error) });
  }
}

async function recordAuthEvent({ eventType, success, userId = null, email = "", sessionId = "", request, detail = "" }) {
  const meta = requestMeta(request);
  try {
    await query(
      `INSERT INTO auth_events
       (event_type, success, user_id, email, session_id, ip, user_agent, detail, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)`,
      [
        compactText(eventType, 40),
        Boolean(success),
        userId || null,
        normalizeEmail(email),
        compactText(sessionId, 80),
        meta.ip,
        meta.userAgent,
        compactText(detail, 200),
        Date.now()
      ]
    );
  } catch (error) {
    logger.warn("auth event record failed", { detail: error?.message || String(error) });
  }
}

function sessionView(row, now, activeWindowMs) {
  return {
    id: row.id,
    createdAt: iso(row.created_at),
    lastSeenAt: iso(row.last_seen_at),
    expiresAt: iso(row.expires_at),
    revokedAt: iso(row.revoked_at),
    ip: row.ip || "",
    userAgent: row.user_agent || "",
    active: isSessionActive(row, now, activeWindowMs)
  };
}

function eventView(row) {
  return {
    id: Number(row.id),
    eventType: row.event_type,
    success: Boolean(row.success),
    email: row.email || "",
    sessionId: row.session_id || "",
    ip: row.ip || "",
    userAgent: row.user_agent || "",
    detail: row.detail || "",
    createdAt: iso(row.created_at)
  };
}

async function authMonitorForEmail(email, options = {}) {
  const normalized = normalizeEmail(email);
  const now = options.now || Date.now();
  const activeWindowMs = Math.max(60 * 1000, Number(options.activeWindowMs || DEFAULT_ACTIVE_WINDOW_MS));
  const limit = Math.max(1, Math.min(Number(options.limit || 20), 100));

  const userResult = await query(
    `SELECT id, email, display_name, token_version, created_at, updated_at
     FROM users WHERE lower(email) = lower($1)`,
    [normalized]
  );
  const user = userResult.rows[0] || null;

  if (!user) {
    const eventsResult = await query(
      `SELECT id, event_type, success, email, session_id, ip, user_agent, detail, created_at
       FROM auth_events
       WHERE lower(email) = lower($1)
       ORDER BY created_at DESC
       LIMIT $2`,
      [normalized, limit]
    );
    return {
      email: normalized,
      exists: false,
      activeWindowMs,
      activeSessionCount: 0,
      sessions: [],
      recentEvents: eventsResult.rows.map(eventView)
    };
  }

  const sessionsResult = await query(
    `SELECT id, ip, user_agent, created_at, last_seen_at, expires_at, revoked_at
     FROM auth_sessions
     WHERE user_id = $1
     ORDER BY last_seen_at DESC
     LIMIT $2`,
    [user.id, limit]
  );
  const eventsResult = await query(
    `SELECT id, event_type, success, email, session_id, ip, user_agent, detail, created_at
     FROM auth_events
     WHERE user_id = $1 OR lower(email) = lower($2)
     ORDER BY created_at DESC
     LIMIT $3`,
    [user.id, normalized, limit]
  );

  const sessions = sessionsResult.rows.map((row) => sessionView(row, now, activeWindowMs));
  const successfulLogins = eventsResult.rows.filter((row) => row.event_type === "login" && row.success);
  const lastLogin = successfulLogins[0] || null;
  const lastSeenAt = sessions.reduce((max, session) => {
    const value = session.lastSeenAt ? Date.parse(session.lastSeenAt) : 0;
    return Math.max(max, Number.isFinite(value) ? value : 0);
  }, 0);

  return {
    email: user.email,
    exists: true,
    account: {
      id: user.id,
      email: user.email,
      displayName: user.display_name || "",
      tokenVersion: Number(user.token_version || 0),
      createdAt: iso(user.created_at),
      updatedAt: iso(user.updated_at)
    },
    activeWindowMs,
    activeSessionCount: sessions.filter((session) => session.active).length,
    lastLoginAt: iso(lastLogin?.created_at),
    lastSeenAt: iso(lastSeenAt),
    sessions,
    recentEvents: eventsResult.rows.map(eventView)
  };
}

module.exports = {
  createAuthSession,
  touchAuthSession,
  extendAuthSession,
  verifyAuthSession,
  revokeAuthSessionsForUser,
  recordAuthEvent,
  authMonitorForEmail,
  __test: {
    compactText,
    normalizeEmail,
    requestMeta,
    isSessionActive,
    tokenExpiresAt
  }
};
