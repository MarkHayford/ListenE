const assert = require("assert");
const {
  AGENT_CARD_COMPONENT_TYPES,
  INTERACTIVE_AGENT_CARD_COMPONENT_TYPES,
  isKnownAgentCardComponentType
} = require("../src/contract/agentCardComponents");
const {
  FOCUSED_AGENT_CARD_COMPONENT_SPEC,
  AGENT_CARD_COMPONENT_KEYWORDS
} = require("../src/services/agentCardSchemas");

// 契约列表本身：无重复、非空。
assert.ok(AGENT_CARD_COMPONENT_TYPES.length > 0, "组件契约不应为空");
assert.strictEqual(
  new Set(AGENT_CARD_COMPONENT_TYPES).size,
  AGENT_CARD_COMPONENT_TYPES.length,
  "组件契约不应有重复 type"
);

// 交互组件必须是已知组件的子集。
INTERACTIVE_AGENT_CARD_COMPONENT_TYPES.forEach((type) => {
  assert.ok(isKnownAgentCardComponentType(type), `交互组件不在契约内: ${type}`);
});

// 防漂移①：prompt 给模型的“可用组件模板”里出现的 type，必须都是客户端能渲染的 canonical 类型，
// 否则模型会被引导产出客户端不认识的组件。
Object.keys(FOCUSED_AGENT_CARD_COMPONENT_SPEC).forEach((type) => {
  assert.ok(
    isKnownAgentCardComponentType(type),
    `FOCUSED_AGENT_CARD_COMPONENT_SPEC 引用了契约外组件: ${type}`
  );
});

// 防漂移②：意图路由关键词目录里的 type 也必须都是 canonical，避免路由到一个无法渲染的组件。
AGENT_CARD_COMPONENT_KEYWORDS.forEach(([type]) => {
  assert.ok(
    isKnownAgentCardComponentType(type),
    `AGENT_CARD_COMPONENT_KEYWORDS 引用了契约外组件: ${type}`
  );
});

// 归一化大小写/空白。
assert.strictEqual(isKnownAgentCardComponentType("  Cloze "), true);
assert.strictEqual(isKnownAgentCardComponentType("not_a_real_component"), false);

console.log("cardComponentContract.test.js passed");
