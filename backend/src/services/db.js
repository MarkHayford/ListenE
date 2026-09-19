const { Pool } = require("pg");
const { settings } = require("../config");

let pool;
let schemaPromise;

function getPool() {
  if (!pool) {
    pool = new Pool({
      connectionString: settings.databaseUrl,
      max: 10,
      idleTimeoutMillis: 30000,
      connectionTimeoutMillis: 5000
    });
  }
  return pool;
}

function ensureSchema() {
  if (!schemaPromise) {
    schemaPromise = (async () => {
      const pool = getPool();
      // 轻量迁移：用 schema_migrations 记录已应用版本，按序只跑一次。
      // 现有内联 DDL 收编为 0001_init（全是 IF NOT EXISTS，对已存在的库是幂等空操作）。
      // 以后改结构：往 ensureSchema 里按序加 0002_xxx 即可，不必再堆 IF NOT EXISTS。
      await pool.query(
        `CREATE TABLE IF NOT EXISTS schema_migrations (version TEXT PRIMARY KEY, applied_at BIGINT NOT NULL);`
      );
      const appliedMigrations = new Set(
        (await pool.query("SELECT version FROM schema_migrations")).rows.map((r) => r.version)
      );
      if (!appliedMigrations.has("0001_init")) {
      await pool.query(`
      CREATE TABLE IF NOT EXISTS users (
        id TEXT PRIMARY KEY,
        email TEXT NOT NULL UNIQUE,
        display_name TEXT NOT NULL DEFAULT '',
        password_hash TEXT NOT NULL,
        token_version BIGINT NOT NULL DEFAULT 0,
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL
      );

      ALTER TABLE users ADD COLUMN IF NOT EXISTS token_version BIGINT NOT NULL DEFAULT 0;

      CREATE TABLE IF NOT EXISTS workspaces (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        id TEXT NOT NULL,
        data JSONB NOT NULL,
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL,
        PRIMARY KEY (user_id, id)
      );

      CREATE TABLE IF NOT EXISTS workspace_messages (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        workspace_id TEXT NOT NULL,
        messages JSONB NOT NULL,
        updated_at BIGINT NOT NULL,
        PRIMARY KEY (user_id, workspace_id),
        FOREIGN KEY (user_id, workspace_id) REFERENCES workspaces(user_id, id) ON DELETE CASCADE
      );

      CREATE TABLE IF NOT EXISTS user_library_items (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        id TEXT NOT NULL,
        kind TEXT NOT NULL CHECK (kind IN ('file', 'card', 'plugin')),
        title TEXT NOT NULL DEFAULT '',
        summary TEXT NOT NULL DEFAULT '',
        data JSONB NOT NULL DEFAULT '{}'::jsonb,
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL,
        PRIMARY KEY (user_id, id)
      );

      CREATE TABLE IF NOT EXISTS study_plans (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        id TEXT NOT NULL,
        title TEXT NOT NULL DEFAULT '',
        detail TEXT NOT NULL DEFAULT '',
        scheduled_at BIGINT NOT NULL DEFAULT 0,
        recurrence TEXT NOT NULL DEFAULT 'none',
        data JSONB NOT NULL DEFAULT '{}'::jsonb,
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL,
        PRIMARY KEY (user_id, id)
      );

      CREATE TABLE IF NOT EXISTS study_progress (
        id BIGSERIAL PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        component_type TEXT NOT NULL DEFAULT '',
        skill TEXT NOT NULL DEFAULT '',
        title TEXT NOT NULL DEFAULT '',
        score INTEGER NOT NULL DEFAULT 0,
        total INTEGER NOT NULL DEFAULT 0,
        correct INTEGER NOT NULL DEFAULT 0,
        created_at BIGINT NOT NULL
      );

      CREATE TABLE IF NOT EXISTS review_items (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        id TEXT NOT NULL,
        kind TEXT NOT NULL DEFAULT 'mcq',
        component_type TEXT NOT NULL DEFAULT '',
        skill TEXT NOT NULL DEFAULT '',
        prompt TEXT NOT NULL DEFAULT '',
        options JSONB NOT NULL DEFAULT '[]'::jsonb,
        answer TEXT NOT NULL DEFAULT '',
        explanation TEXT NOT NULL DEFAULT '',
        box INTEGER NOT NULL DEFAULT 0,
        due_at BIGINT NOT NULL DEFAULT 0,
        times_wrong INTEGER NOT NULL DEFAULT 1,
        times_reviewed INTEGER NOT NULL DEFAULT 0,
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL,
        PRIMARY KEY (user_id, id)
      );

      CREATE TABLE IF NOT EXISTS vocab_items (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        id TEXT NOT NULL,
        word TEXT NOT NULL DEFAULT '',
        phonetic TEXT NOT NULL DEFAULT '',
        meaning TEXT NOT NULL DEFAULT '',
        example TEXT NOT NULL DEFAULT '',
        box INTEGER NOT NULL DEFAULT 0,
        due_at BIGINT NOT NULL DEFAULT 0,
        times_reviewed INTEGER NOT NULL DEFAULT 0,
        created_at BIGINT NOT NULL,
        updated_at BIGINT NOT NULL,
        PRIMARY KEY (user_id, id)
      );

      CREATE TABLE IF NOT EXISTS daily_challenge (
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        day INTEGER NOT NULL,
        questions JSONB NOT NULL DEFAULT '[]'::jsonb,
        completed BOOLEAN NOT NULL DEFAULT false,
        score INTEGER NOT NULL DEFAULT 0,
        created_at BIGINT NOT NULL,
        completed_at BIGINT NOT NULL DEFAULT 0,
        PRIMARY KEY (user_id, day)
      );
    `);
        await pool.query(
          "INSERT INTO schema_migrations (version, applied_at) VALUES ($1, $2) ON CONFLICT (version) DO NOTHING",
          ["0001_init", Date.now()]
        );
      }
      // 0002：统一用户模型（跨功能一份「用户画像」——能力/偏好/稳定弱点/备注），best-effort 汇聚各端信号。
      if (!appliedMigrations.has("0002_user_model")) {
        try {
          await pool.query(`
      CREATE TABLE IF NOT EXISTS user_model (
        user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
        data JSONB NOT NULL DEFAULT '{}'::jsonb,
        updated_at BIGINT NOT NULL
      );
    `);
          await pool.query(
            "INSERT INTO schema_migrations (version, applied_at) VALUES ($1, $2) ON CONFLICT (version) DO NOTHING",
            ["0002_user_model", Date.now()]
          );
        } catch (error) {
          // 受限 DB 用户可能无建表权限(42501)：不阻断启动，也不记录迁移(有权限时下次重试)；
          // 统一用户模型相关查询自行降级(端点报错、聊天用客户端携带的模型，主链路不受影响)。
          if (!error || error.code !== "42501") throw error;
        }
      }
      // 0003：相册式分类的云端同步（每用户一份分类清单+归属，按域 workspace/cards/files），跨设备。
      if (!appliedMigrations.has("0003_user_categories")) {
        try {
          await pool.query(`
      CREATE TABLE IF NOT EXISTS user_categories (
        user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
        data JSONB NOT NULL DEFAULT '{}'::jsonb,
        updated_at BIGINT NOT NULL
      );
    `);
          await pool.query(
            "INSERT INTO schema_migrations (version, applied_at) VALUES ($1, $2) ON CONFLICT (version) DO NOTHING",
            ["0003_user_categories", Date.now()]
          );
        } catch (error) {
          // 受限 DB 用户无建表权限(42501)：不阻断启动；分类同步端点自行降级(本地仍可用)。
          if (!error || error.code !== "42501") throw error;
        }
      }
      // 0004：认证审计与会话监控。只保存账号、时间、来源和 session 元数据，不保存 token/密码。
      if (!appliedMigrations.has("0004_auth_monitoring")) {
        try {
          await pool.query(`
      CREATE TABLE IF NOT EXISTS auth_sessions (
        id TEXT PRIMARY KEY,
        user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        token_version BIGINT NOT NULL DEFAULT 0,
        ip TEXT NOT NULL DEFAULT '',
        user_agent TEXT NOT NULL DEFAULT '',
        created_at BIGINT NOT NULL,
        last_seen_at BIGINT NOT NULL,
        expires_at BIGINT NOT NULL,
        revoked_at BIGINT NOT NULL DEFAULT 0
      );

      CREATE TABLE IF NOT EXISTS auth_events (
        id BIGSERIAL PRIMARY KEY,
        event_type TEXT NOT NULL,
        success BOOLEAN NOT NULL,
        user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
        email TEXT NOT NULL DEFAULT '',
        session_id TEXT NOT NULL DEFAULT '',
        ip TEXT NOT NULL DEFAULT '',
        user_agent TEXT NOT NULL DEFAULT '',
        detail TEXT NOT NULL DEFAULT '',
        created_at BIGINT NOT NULL
      );

      CREATE INDEX IF NOT EXISTS auth_sessions_user_last_seen_idx ON auth_sessions(user_id, last_seen_at DESC);
      CREATE INDEX IF NOT EXISTS auth_sessions_active_idx ON auth_sessions(user_id, revoked_at, expires_at, last_seen_at DESC);
      CREATE INDEX IF NOT EXISTS auth_events_user_created_idx ON auth_events(user_id, created_at DESC);
      CREATE INDEX IF NOT EXISTS auth_events_email_created_idx ON auth_events(email, created_at DESC);
    `);
          await pool.query(
            "INSERT INTO schema_migrations (version, applied_at) VALUES ($1, $2) ON CONFLICT (version) DO NOTHING",
            ["0004_auth_monitoring", Date.now()]
          );
        } catch (error) {
          if (!error || error.code !== "42501") throw error;
        }
      }
      // 索引 best-effort（与历史一致：受限 DB 用户无 CREATE INDEX 权限报 42501 时忽略）。
      try {
        await pool.query(`
          CREATE INDEX IF NOT EXISTS workspaces_user_updated_idx ON workspaces(user_id, updated_at DESC);
          CREATE INDEX IF NOT EXISTS user_library_items_user_kind_updated_idx ON user_library_items(user_id, kind, updated_at DESC);
          CREATE INDEX IF NOT EXISTS study_plans_user_scheduled_idx ON study_plans(user_id, scheduled_at ASC);
          CREATE INDEX IF NOT EXISTS study_progress_user_created_idx ON study_progress(user_id, created_at DESC);
          CREATE INDEX IF NOT EXISTS review_items_user_due_idx ON review_items(user_id, due_at ASC);
          CREATE INDEX IF NOT EXISTS vocab_items_user_due_idx ON vocab_items(user_id, due_at ASC);
        `);
      } catch (error) {
        if (!error || error.code !== "42501") throw error;
      }
    })();
  }
  return schemaPromise;
}

async function query(text, params = []) {
  await ensureSchema();
  return getPool().query(text, params);
}

module.exports = {
  ensureSchema,
  query
};
