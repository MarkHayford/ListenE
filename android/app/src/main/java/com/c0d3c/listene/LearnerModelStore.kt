package com.c0d3c.listene

import android.content.Context
import kotlin.math.log10
import kotlin.math.pow

// 学习者建模 + 逐题难度自适应（1PL / IRT，Elo 在线更新；端上本地持久化、离线可用）。
// 与后端 learnerModel.js 等价：能力 θ 与题目难度 b 同 0..100 刻度；
//   P(答对) = 1 / (1 + 10^((b - θ) / SCALE))；每题 θ' = θ + K*(actual - P)；
//   最优挑战难度由目标正确率(≈0.75)反推（略低于能力）→ 生成练习时对齐难度。
object LearnerModelStore {
    private const val PREFS = "learner_model"
    private const val SCALE = 20.0
    private const val DEFAULT_ABILITY = 50.0
    private const val K = 6.0
    private const val TARGET_P = 0.75

    // 与 ProgressStore.SKILLS 一致的六技能。
    val SKILLS = listOf("听力", "词汇", "语法", "阅读", "写作", "口语")

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(AuthStore.scopedPrefsName(ctx, PREFS), Context.MODE_PRIVATE)

    private fun keyOf(skill: String) = "ability_$skill"

    // 某技能当前能力 θ（0..100）；未知技能/未练过 → 默认 50。
    fun ability(ctx: Context, skill: String): Double =
        prefs(ctx).getFloat(keyOf(skill), DEFAULT_ABILITY.toFloat()).toDouble().coerceIn(0.0, 100.0)

    fun expectedScore(ability: Double, difficulty: Double): Double =
        1.0 / (1.0 + 10.0.pow((difficulty - ability) / SCALE))

    // 最优挑战难度：使答对概率≈targetP 的难度（略低于能力）。
    fun recommendDifficulty(ability: Double, targetP: Double = TARGET_P): Double {
        val p = targetP.coerceIn(0.5, 0.95)
        return (ability - SCALE * log10(p / (1 - p))).coerceIn(0.0, 100.0)
    }

    // 用一次作答结果在线更新能力。outcome∈[0,1]（整卡可传答对比例）；难度默认取当前推荐难度。
    fun update(ctx: Context, skill: String?, outcome: Double, difficulty: Double? = null): Double {
        val s = skill?.takeIf { it.isNotBlank() && it in SKILLS } ?: return DEFAULT_ABILITY
        val a = ability(ctx, s)
        val b = (difficulty ?: recommendDifficulty(a)).coerceIn(0.0, 100.0)
        val actual = outcome.coerceIn(0.0, 1.0)
        val next = (a + K * (actual - expectedScore(a, b))).coerceIn(0.0, 100.0)
        prefs(ctx).edit().putFloat(keyOf(s), next.toFloat()).apply()
        return next
    }

    // 便捷：用 正确数/总数 更新（total<=0 不更新）。
    fun updateFromCounts(ctx: Context, skill: String?, correct: Int, total: Int) {
        if (total <= 0 || skill.isNullOrBlank()) return
        update(ctx, skill, correct.toDouble() / total.toDouble())
    }

    fun cefr(ability: Double): String = when {
        ability <= 16 -> "A1"
        ability <= 33 -> "A2"
        ability <= 50 -> "B1"
        ability <= 67 -> "B2"
        ability <= 84 -> "C1"
        else -> "C2"
    }

    // 某技能推荐出题难度对应的 CEFR 段位（略低于其能力）。
    fun targetLevel(ctx: Context, skill: String): String = cefr(recommendDifficulty(ability(ctx, skill)))

    // 综合能力（六技能均值）。
    fun overallAbility(ctx: Context): Double = SKILLS.map { ability(ctx, it) }.average()

    // 通用练习请求用：综合能力对应的推荐出题难度段位。
    fun overallTargetLevel(ctx: Context): String = cefr(recommendDifficulty(overallAbility(ctx)))
}
