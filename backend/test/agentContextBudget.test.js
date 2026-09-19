const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test } = require("../src/services/mimoText");
const { estimateTokens, selectRecentMessagesWithinBudget } = __test;

// --- estimateTokens: 空→0；CJK 比拉丁更密；单调 ---
assert.strictEqual(estimateTokens(""), 0);
assert.strictEqual(estimateTokens("你好世界"), 8, "4 CJK * 2 = 8");
assert.ok(estimateTokens("汉字") > estimateTokens("ab"), "CJK denser than latin");
assert.ok(
  estimateTokens("hello world hello world") > estimateTokens("hello world"),
  "longer latin -> more tokens"
);

// --- selection: 时间顺序、至少留最近 1 条、条数上限、预算约束 ---
const msgs = Array.from({ length: 30 }, (_, i) => ({ role: i % 2 ? "assistant" : "user", content: `msg ${i}` }));

// 小预算 → 少量，但 >=1，且保留的是最新的（chronological，末位是 msg 29）
const small = selectRecentMessagesWithinBudget(msgs, 30, 16);
assert.ok(small.length >= 1 && small.length <= 16, "bounded by budget");
assert.strictEqual(small[small.length - 1].content, "msg 29", "keeps newest");
for (let i = 1; i < small.length; i += 1) {
  const prev = Number(small[i - 1].content.split(" ")[1]);
  const cur = Number(small[i].content.split(" ")[1]);
  assert.ok(cur > prev, "chronological ascending order preserved");
}

// 巨大预算 → 受 maxMessages 上限约束
const capped = selectRecentMessagesWithinBudget(msgs, 100000, 5);
assert.strictEqual(capped.length, 5, "capped at maxMessages");
assert.strictEqual(capped[capped.length - 1].content, "msg 29");

// 角色归一 + 空白过滤（role 非 user → assistant；text/message 兼容）
const mixed = selectRecentMessagesWithinBudget(
  [{ role: "user", content: "   " }, { role: "system", text: "hi" }, { content: "" }],
  1000,
  16
);
assert.strictEqual(mixed.length, 1, "blank messages filtered");
assert.strictEqual(mixed[0].role, "assistant", "non-user role normalized to assistant");
assert.strictEqual(mixed[0].content, "hi", "text alias accepted");

// 即便单条超预算，也至少保留最近 1 条（不至于空上下文）
const big = selectRecentMessagesWithinBudget([{ role: "user", content: "x".repeat(10000) }], 5, 16);
assert.strictEqual(big.length, 1, "always keep at least the latest");

// 空输入 → 空数组
assert.deepStrictEqual(selectRecentMessagesWithinBudget([], 1000, 16), []);
assert.deepStrictEqual(selectRecentMessagesWithinBudget(null, 1000, 16), []);

// --- #3 相关性检索：记忆按与本轮消息的相关性挑 top-k ---
const { buildAgentWorkspaceMemoryHint, tokenizeForMemoryRelevance, scoreMemoryEntryRelevance } = __test;
const mem = [
  { type: "fact", content: "喜欢科技类文章 technology articles", importance: 0 },
  { type: "weakness", content: "虚拟语气 subjunctive 容易出错", importance: 0 },
  { type: "preference", content: "偏好英式发音 British pronunciation", importance: 0 }
];
const qTok = tokenizeForMemoryRelevance("subjunctive 虚拟语气");
assert.ok(
  scoreMemoryEntryRelevance(mem[1], qTok) > scoreMemoryEntryRelevance(mem[0], qTok),
  "命中查询的条目分数更高"
);
// query 为空 → 不报错，仍产出 hint（退回 importance/类型）
assert.match(buildAgentWorkspaceMemoryHint({ memory: mem, query: "" }), /本工作区级记忆/);
// top-k 截断：>8 条只保留 8 条，且相关的优先
const many = Array.from({ length: 20 }, (_, i) => ({ type: "fact", content: `fact number ${i} apple`, importance: i }));
const manyHint = buildAgentWorkspaceMemoryHint({ memory: many, query: "apple pie" });
assert.ok((manyHint.match(/- fact:/g) || []).length <= 8, "记忆条目截断到 top-8");

console.log("agentContextBudget.test.js passed");
