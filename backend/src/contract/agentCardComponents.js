"use strict";

// 前后端「卡片组件契约」的单一事实来源（后端侧副本）。
// 这里列出的就是 Android 客户端 AgentCardComponentRegistry.supportedTypes 能真正渲染的全部组件 type。
// 后端实时组卡引擎（mimoText 生成/归一化、意图路由、prompt 模板）只允许产出/引用这些 type；
// 任何一端新增/改名组件，必须同步：① 客户端 AgentCardComponentRegistry ② 本文件 ③ 两端契约测试，
// 否则会出现“后端发了客户端不认识的组件 → 被丢弃/渲染错乱”这类前后端漂移（历史上句子改错卡即此类）。
//
// 顺序与客户端 registry 保持一致，便于人工比对。
const AGENT_CARD_COMPONENT_TYPES = Object.freeze([
  "header",
  "chips",
  "actions",
  "summary",
  "progress",
  "audio",
  "question_preview",
  "transcript",
  "sentence_transcript",
  "feedback",
  "suggestion",
  "divider",
  "vocabulary",
  "phrase",
  "grammar",
  "translation",
  "examples",
  "pronunciation",
  "cloze",
  "short_answer",
  "sentence_builder",
  "ordering",
  "question_set",
  "reading",
  "gap_match",
  "chart_writing",
  "compare",
  "correction",
  "rubric",
  "listening_cue",
  "minimal_pair",
  "word_family",
  "scenario",
  "register",
  "speaking_prompt",
  "writing_outline",
  "mistake_pattern",
  "ethics",
  "debate",
  "error_hunt",
  "storytelling",
  "paraphrase",
  "shadowing"
]);

const AGENT_CARD_COMPONENT_TYPE_SET = new Set(AGENT_CARD_COMPONENT_TYPES);

// 客户端能交互作答的组件（点选/输入/核对类）。后端在“只要展示卡”等约束下可据此判断。
const INTERACTIVE_AGENT_CARD_COMPONENT_TYPES = Object.freeze([
  "cloze",
  "short_answer",
  "sentence_builder",
  "ordering",
  "question_set",
  "reading",
  "gap_match",
  "speaking_prompt",
  "correction",
  "error_hunt"
]);

function isKnownAgentCardComponentType(type) {
  return AGENT_CARD_COMPONENT_TYPE_SET.has(String(type || "").trim().toLowerCase());
}

module.exports = {
  AGENT_CARD_COMPONENT_TYPES,
  AGENT_CARD_COMPONENT_TYPE_SET,
  INTERACTIVE_AGENT_CARD_COMPONENT_TYPES,
  isKnownAgentCardComponentType
};
