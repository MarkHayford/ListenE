"use strict";

// 学习者建模 + 逐题难度自适应（1PL / IRT，Elo 在线更新）。
// 能力 θ 与题目难度 b 用同一 0..100 刻度（0≈CEFR pre-A1，100≈C2），便于与现有六技能分数并存。
//   答对概率  P = 1 / (1 + 10^((b - θ) / SCALE))      —— θ 越高、b 越低 → P 越大
//   在线更新  θ' = θ + K * (actual - P)               —— Elo/1PL：赢难题涨得多、赢易题涨得少
//   最优挑战  由目标正确率 targetP 反推难度 b*         —— 略低于能力，落在“最近发展区”
// 纯函数、无副作用、无依赖，便于单测；端上有等价实现 LearnerModelStore.kt。

const ABILITY_MIN = 0;
const ABILITY_MAX = 100;
const DEFAULT_ABILITY = 50;
const SCALE = 20;      // logistic 斜率：能力与难度相差 SCALE 分 ≈ 10:1 胜率
const DEFAULT_K = 6;   // 单题 Elo 步长
const TARGET_P = 0.75; // 最优挑战区目标正确率

function clampAbility(value, fallback = DEFAULT_ABILITY) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.max(ABILITY_MIN, Math.min(ABILITY_MAX, n));
}

// 答对概率（能力 θ 对难度 b，同刻度）。
function expectedScore(ability, difficulty) {
  const a = clampAbility(ability);
  const b = clampAbility(difficulty);
  return 1 / (1 + Math.pow(10, (b - a) / SCALE));
}

// Elo 在线更新能力。outcome：布尔(对/错) 或 [0,1] 的答对比例（整卡可传 correct/total）。
function updateAbility(ability, difficulty, outcome, { k = DEFAULT_K } = {}) {
  const a = clampAbility(ability);
  const b = clampAbility(difficulty);
  const actual = typeof outcome === "boolean"
    ? (outcome ? 1 : 0)
    : Math.max(0, Math.min(1, Number(outcome) || 0));
  const kk = Math.max(1, Number(k) || DEFAULT_K);
  return clampAbility(a + kk * (actual - expectedScore(a, b)));
}

// 最优挑战难度：使答对概率≈targetP 的难度 b*（略低于能力）。
// 由 P = 1/(1+10^((b-a)/S)) 反解：b = a - S*log10(P/(1-P))。
function recommendDifficulty(ability, { targetP = TARGET_P } = {}) {
  const a = clampAbility(ability);
  const p = Math.max(0.5, Math.min(0.95, Number(targetP) || TARGET_P));
  return clampAbility(a - SCALE * Math.log10(p / (1 - p)));
}

const CEFR_ORDER = ["A1", "A2", "B1", "B2", "C1", "C2"];
const CEFR_BANDS = [
  { max: 16, band: "A1" },
  { max: 33, band: "A2" },
  { max: 50, band: "B1" },
  { max: 67, band: "B2" },
  { max: 84, band: "C1" },
  { max: 100, band: "C2" }
];
const CEFR_MIDPOINTS = { A1: 8, A2: 25, B1: 42, B2: 58, C1: 75, C2: 92 };

// 能力 → CEFR 段位（给生成器/UI 一个可读难度标签）。
function abilityToCefr(ability) {
  const a = clampAbility(ability);
  return (CEFR_BANDS.find((x) => a <= x.max) || CEFR_BANDS[CEFR_BANDS.length - 1]).band;
}

// CEFR 段位 → 能力（取段位中点；无效输入回落到默认能力）。
function cefrToAbility(band) {
  const key = String(band || "").trim().toUpperCase();
  return Object.prototype.hasOwnProperty.call(CEFR_MIDPOINTS, key) ? CEFR_MIDPOINTS[key] : DEFAULT_ABILITY;
}

module.exports = {
  ABILITY_MIN,
  ABILITY_MAX,
  DEFAULT_ABILITY,
  SCALE,
  DEFAULT_K,
  TARGET_P,
  CEFR_ORDER,
  clampAbility,
  expectedScore,
  updateAbility,
  recommendDifficulty,
  abilityToCefr,
  cefrToAbility
};
