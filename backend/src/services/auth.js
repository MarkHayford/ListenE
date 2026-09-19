const crypto = require("crypto");
const { settings } = require("../config");
const { query } = require("./db");
const {
  createAuthSession,
  extendAuthSession,
  verifyAuthSession,
  revokeAuthSessionsForUser,
  recordAuthEvent
} = require("./authMonitor");

// 密码长度上限：scryptSync 是 CPU 密集型，超长密码可被用于拒绝服务攻击。
const MAX_PASSWORD_LENGTH = 128;

// scrypt 代价参数（可调）。默认 N 从 16384 提到 32768（更抗暴力）。
// 注意：N=32768,r=8 约需 ~33MB，超过 node 默认 maxmem(32MB)，故显式放宽 maxmem。
// 向后兼容：verifyPassword 从已存哈希里读 N/r/p，旧密码(N=16384)照常校验。
const SCRYPT_N = Math.max(16384, Number(process.env.SCRYPT_N) || 32768);
const SCRYPT_R = Math.max(1, Number(process.env.SCRYPT_R) || 8);
const SCRYPT_P = Math.max(1, Number(process.env.SCRYPT_P) || 1);
const SCRYPT_MAXMEM = Math.max(64 * 1024 * 1024, Number(process.env.SCRYPT_MAXMEM) || 0);

function compactText(text, max = 200) {
  const value = String(text || "").replace(/\s+/g, " ").trim();
  return value.length > max ? value.slice(0, max - 1) : value;
}

function normalizeEmail(email) {
  return compactText(email, 254).toLowerCase();
}

function publicUser(row) {
  if (!row) return null;
  return {
    id: row.id,
    email: row.email,
    displayName: row.display_name || "",
    createdAt: Number(row.created_at || 0),
    updatedAt: Number(row.updated_at || 0)
  };
}

function hashPassword(password) {
  const salt = crypto.randomBytes(16).toString("base64url");
  const n = SCRYPT_N;
  const r = SCRYPT_R;
  const p = SCRYPT_P;
  const hash = crypto
    .scryptSync(String(password), salt, 64, { N: n, r, p, maxmem: SCRYPT_MAXMEM })
    .toString("base64url");
  return `scrypt$${n}$${r}$${p}$${salt}$${hash}`;
}

function verifyPassword(password, stored) {
  const [scheme, nRaw, rRaw, pRaw, salt, expected] = String(stored || "").split("$");
  if (scheme !== "scrypt" || !salt || !expected) return false;
  const actual = crypto.scryptSync(String(password), salt, 64, {
    N: Number(nRaw),
    r: Number(rRaw),
    p: Number(pRaw),
    maxmem: SCRYPT_MAXMEM
  }).toString("base64url");
  return constantEqual(actual, expected);
}

// 防用户枚举：登录时账号不存在也要跑一次等价的 scrypt 校验（针对一个固定的随机假哈希），
// 否则「未注册邮箱秒回 / 已注册邮箱慢回」的耗时差会泄露邮箱是否注册。惰性计算一次缓存。
// 注：假哈希用当前代价参数；对历史 N=16384 的旧用户耗时略短，但主要枚举信号（秒回）已消除。
let dummyHashCache = "";
function dummyPasswordHash() {
  if (!dummyHashCache) dummyHashCache = hashPassword(crypto.randomBytes(32).toString("base64url"));
  return dummyHashCache;
}

function constantEqual(a, b) {
  const aa = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  if (aa.length !== bb.length) return false;
  return crypto.timingSafeEqual(aa, bb);
}

function signPayload(payload) {
  return crypto.createHmac("sha256", settings.authTokenSecret).update(payload).digest("base64url");
}

function createToken(user, tokenVersion = 0, sessionId = "") {
  const now = Math.floor(Date.now() / 1000);
  const body = {
    sub: user.id,
    email: user.email,
    iat: now,
    exp: now + settings.authTokenDays * 24 * 60 * 60,
    v: 1,
    tv: Number(tokenVersion) || 0
  };
  if (sessionId) body.sid = sessionId;
  const payload = Buffer.from(JSON.stringify(body)).toString("base64url");
  return `lt1.${payload}.${signPayload(payload)}`;
}

function verifyToken(token) {
  const [prefix, payload, signature] = String(token || "").split(".");
  if (prefix !== "lt1" || !payload || !signature) throw new Error("unauthorized");
  if (!constantEqual(signPayload(payload), signature)) throw new Error("unauthorized");
  let body;
  try {
    body = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
  } catch {
    throw new Error("unauthorized");
  }
  if (!body?.sub || Number(body.exp || 0) < Math.floor(Date.now() / 1000)) throw new Error("unauthorized");
  return body;
}

async function registerUser(body = {}, request) {
  const email = normalizeEmail(body.email || body.username);
  const password = String(body.password || "");
  const displayName = compactText(body.displayName || body.name || email.split("@")[0], 80);
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) throw new Error("valid email is required");
  if (password.length < 8) throw new Error("password must be at least 8 characters");
  if (password.length > MAX_PASSWORD_LENGTH) throw new Error(`password must be at most ${MAX_PASSWORD_LENGTH} characters`);
  const now = Date.now();
  const id = `usr_${crypto.randomUUID()}`;
  try {
    const result = await query(
      `INSERT INTO users (id, email, display_name, password_hash, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $5)
       RETURNING id, email, display_name, created_at, updated_at, token_version`,
      [id, email, displayName, hashPassword(password), now]
    );
    const row = result.rows[0];
    const user = publicUser(row);
    const session = await createAuthSession({ userId: row.id, tokenVersion: row.token_version, request });
    await recordAuthEvent({
      eventType: "register",
      success: true,
      userId: row.id,
      email,
      sessionId: session?.id || "",
      request
    });
    return { user, token: createToken(user, row.token_version, session?.id || "") };
  } catch (error) {
    if (error && error.code === "23505") {
      await recordAuthEvent({ eventType: "register", success: false, email, request, detail: "email already registered" });
      throw new Error("email already registered");
    }
    throw error;
  }
}

async function loginUser(body = {}, request) {
  const email = normalizeEmail(body.email || body.username);
  const password = String(body.password || "");
  if (password.length > MAX_PASSWORD_LENGTH) {
    await recordAuthEvent({ eventType: "login", success: false, email, request, detail: "invalid credentials" });
    throw new Error("invalid email or password");
  }
  const result = await query(
    `SELECT id, email, display_name, password_hash, created_at, updated_at, token_version
     FROM users WHERE email = $1`,
    [email]
  );
  const row = result.rows[0];
  // 不论账号是否存在都跑一次 scrypt 校验（不存在时用固定假哈希），消除时间侧信道。
  const passwordOk = verifyPassword(password, row ? row.password_hash : dummyPasswordHash());
  if (!row || !passwordOk) {
    await recordAuthEvent({
      eventType: "login",
      success: false,
      userId: row?.id || null,
      email,
      request,
      detail: "invalid credentials"
    });
    throw new Error("invalid email or password");
  }
  const user = publicUser(row);
  const session = await createAuthSession({ userId: row.id, tokenVersion: row.token_version, request });
  await recordAuthEvent({
    eventType: "login",
    success: true,
    userId: row.id,
    email,
    sessionId: session?.id || "",
    request
  });
  return { user, token: createToken(user, row.token_version, session?.id || "") };
}

async function logoutUser(userId, request) {
  let sessionId = "";
  try {
    sessionId = verifyToken(tokenFromRequest(request)).sid || "";
  } catch (_) {
    sessionId = "";
  }
  await query(
    `UPDATE users SET token_version = token_version + 1, updated_at = $2 WHERE id = $1`,
    [userId, Date.now()]
  );
  await revokeAuthSessionsForUser(userId, request);
  await recordAuthEvent({ eventType: "logout", success: true, userId, sessionId, request });
  return { success: true };
}

async function userById(id) {
  const result = await query(
    `SELECT id, email, display_name, created_at, updated_at, token_version FROM users WHERE id = $1`,
    [id]
  );
  return result.rows[0] || null;
}

function tokenFromRequest(request) {
  const header = request?.headers?.authorization || request?.headers?.Authorization || "";
  const match = /^Bearer\s+(.+)$/i.exec(String(header));
  return match ? match[1].trim() : "";
}

async function optionalUserFromRequest(request) {
  const token = tokenFromRequest(request);
  if (!token) return null;
  // optional 端点对无效/过期/已失效 token 优雅降级为匿名（返回 null），不抛 401；
  // 必填端点由 requireUserFromRequest 在拿到 null 时统一抛 unauthorized。
  // 注意：DB 查询错误仍向上抛出（不吞），避免把数据库故障误当成匿名。
  let payload;
  try {
    payload = verifyToken(token);
  } catch (_) {
    return null;
  }
  const row = await userById(payload.sub);
  if (!row) return null;
  if (Number(row.token_version || 0) !== Number(payload.tv || 0)) return null;
  if (!(await verifyAuthSession(payload, request))) return null;
  return publicUser(row);
}

async function requireUserFromRequest(request) {
  const user = await optionalUserFromRequest(request);
  if (!user) throw new Error("unauthorized");
  return { user };
}

// 滑动续期：客户端在 token 过期【之前】用当前有效 token 换一枚新 token（exp 顺延），
// 无需重新登录、也无需单独的长效 refresh token。仍校验签名/未过期/token_version 一致，
// 因此 logout（递增 token_version）会让旧 token 连同其续期能力一起失效。
async function refreshSession(request) {
  const token = tokenFromRequest(request);
  if (!token) throw new Error("unauthorized");
  let payload;
  try {
    payload = verifyToken(token);
  } catch (_) {
    throw new Error("unauthorized");
  }
  const row = await userById(payload.sub);
  if (!row) throw new Error("unauthorized");
  if (Number(row.token_version || 0) !== Number(payload.tv || 0)) throw new Error("unauthorized");
  const user = publicUser(row);
  let sessionId = payload.sid || "";
  if (sessionId) {
    if (!(await verifyAuthSession(payload, request))) throw new Error("unauthorized");
    await extendAuthSession(sessionId, row.id, request);
  } else {
    const session = await createAuthSession({ userId: row.id, tokenVersion: row.token_version, request });
    sessionId = session?.id || "";
  }
  await recordAuthEvent({ eventType: "refresh", success: true, userId: row.id, email: row.email, sessionId, request });
  return { user, token: createToken(user, row.token_version, sessionId) };
}

module.exports = {
  loginUser,
  logoutUser,
  optionalUserFromRequest,
  refreshSession,
  registerUser,
  requireUserFromRequest,
  __test: {
    hashPassword,
    verifyPassword,
    createToken,
    verifyToken,
    dummyPasswordHash
  }
};
