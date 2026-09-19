const assert = require("assert");
const crypto = require("crypto");
const { __test } = require("../src/services/auth");

const { hashPassword, verifyPassword, createToken, verifyToken, dummyPasswordHash } = __test;

// 密码哈希：格式正确 + 正确口令通过 + 错误口令拒绝
{
  const h = hashPassword("correct horse battery staple");
  assert.ok(/^scrypt\$\d+\$\d+\$\d+\$/.test(h), "hash 应为 scrypt$N$r$p$salt$hash 格式");
  assert.strictEqual(verifyPassword("correct horse battery staple", h), true);
  assert.strictEqual(verifyPassword("wrong-password", h), false);
}

// 每次随机盐 → 同口令哈希不重复
assert.notStrictEqual(hashPassword("samepw"), hashPassword("samepw"));

// 向后兼容：旧 N=16384 哈希仍可校验（verify 从哈希读 N/r/p）
{
  const salt = crypto.randomBytes(16).toString("base64url");
  const legacy = crypto.scryptSync("legacy-pw", salt, 64, { N: 16384, r: 8, p: 1 }).toString("base64url");
  const stored = `scrypt$16384$8$1$${salt}$${legacy}`;
  assert.strictEqual(verifyPassword("legacy-pw", stored), true);
  assert.strictEqual(verifyPassword("nope", stored), false);
}

// 畸形/空哈希：不抛异常、一律判否
assert.strictEqual(verifyPassword("x", ""), false);
assert.strictEqual(verifyPassword("x", "garbage"), false);
assert.strictEqual(verifyPassword("x", "bcrypt$1$2$3$4$5"), false);
assert.strictEqual(verifyPassword("x", null), false);

// 防枚举假哈希：是合法 scrypt 哈希、稳定缓存、且任意口令都不该命中它
{
  const d1 = dummyPasswordHash();
  const d2 = dummyPasswordHash();
  assert.strictEqual(d1, d2, "假哈希应缓存为同一值");
  assert.ok(/^scrypt\$\d+\$\d+\$\d+\$/.test(d1), "假哈希应为合法 scrypt 格式");
  assert.strictEqual(verifyPassword("", d1), false);
  assert.strictEqual(verifyPassword("anything", d1), false);
}

// token：签发 + 校验往返
{
  const token = createToken({ id: "usr_1", email: "a@b.com" }, 0);
  assert.ok(/^lt1\./.test(token), "token 应带 lt1 前缀");
  const body = verifyToken(token);
  assert.strictEqual(body.sub, "usr_1");
  assert.strictEqual(body.email, "a@b.com");
  assert.strictEqual(Number(body.tv), 0);
  assert.ok(Number(body.exp) > Math.floor(Date.now() / 1000), "exp 应在未来");
}

// token：带 session id 时写入 sid，便于服务端会话监控；不传时保持兼容旧 token
{
  const token = createToken({ id: "usr_1", email: "a@b.com" }, 2, "ses_abc");
  const body = verifyToken(token);
  assert.strictEqual(body.sid, "ses_abc");
  assert.strictEqual(Number(body.tv), 2);
}

// token：篡改 payload 但复用旧签名 → 签名不符 → 拒绝（防越权伪造）
{
  const token = createToken({ id: "usr_1", email: "a@b.com" }, 0);
  const [prefix, , signature] = token.split(".");
  const forgedPayload = Buffer
    .from(JSON.stringify({ sub: "usr_admin", exp: 9_999_999_999 }))
    .toString("base64url");
  assert.throws(() => verifyToken(`${prefix}.${forgedPayload}.${signature}`), /unauthorized/);
}

// token：畸形 / 空 / 缺段 一律拒绝
assert.throws(() => verifyToken(""), /unauthorized/);
assert.throws(() => verifyToken("notatoken"), /unauthorized/);
assert.throws(() => verifyToken("lt1.only-two"), /unauthorized/);
assert.throws(() => verifyToken("wrongprefix.a.b"), /unauthorized/);

console.log("auth.unit.test.js passed");
