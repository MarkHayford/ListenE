const assert = require("assert");

const m = require("../src/services/learnerModel");

// clampAbility: bounds + NaN fallback
assert.strictEqual(m.clampAbility(-10), 0);
assert.strictEqual(m.clampAbility(999), 100);
assert.strictEqual(m.clampAbility("x", 50), 50);

// expectedScore: equal -> 0.5; higher ability -> higher P; monotonic in difficulty
assert.strictEqual(Math.round(m.expectedScore(50, 50) * 100), 50);
assert.ok(m.expectedScore(70, 50) > 0.7, "stronger learner clears an equal item easily");
assert.ok(m.expectedScore(30, 50) < 0.3, "weaker learner rarely clears a hard item");
assert.ok(m.expectedScore(50, 40) > m.expectedScore(50, 60), "easier item -> higher P");

// updateAbility: correct raises, wrong lowers, symmetric at P=0.5
const up = m.updateAbility(50, 50, true);
const down = m.updateAbility(50, 50, false);
assert.ok(up > 50 && down < 50);
assert.ok(Math.abs((up - 50) - (50 - down)) < 1e-9, "symmetric gain/loss at P=0.5");

// beating a HARD item raises ability more than beating an EASY one (IRT信息量)
assert.ok(m.updateAbility(50, 80, true) - 50 > m.updateAbility(50, 20, true) - 50, "hard win > easy win");
// failing an EASY item drops ability more than failing a HARD one
assert.ok(50 - m.updateAbility(50, 20, false) > 50 - m.updateAbility(50, 80, false), "easy loss > hard loss");

// partial outcome (correct/total) supported; actual==expected -> no move
assert.strictEqual(m.updateAbility(50, 50, 0.5), 50, "outcome equal to expected -> unchanged");
assert.ok(m.updateAbility(50, 50, 1) > m.updateAbility(50, 50, 0.6), "higher ratio -> larger gain");

// updates stay clamped in range
assert.ok(m.updateAbility(100, 0, true) <= 100 && m.updateAbility(0, 100, false) >= 0);

// recommendDifficulty: below ability for P=0.75 and actually yields ~targetP
const rd = m.recommendDifficulty(60);
assert.ok(rd < 60, "optimal challenge is a bit below ability");
const p = m.expectedScore(60, rd);
assert.ok(p > 0.72 && p < 0.78, `recommended item gives ~0.75 P (got ${p.toFixed(3)})`);
assert.ok(m.recommendDifficulty(0) >= 0 && m.recommendDifficulty(100) <= 100, "clamped");

// CEFR mapping + round trip ordering
assert.strictEqual(m.abilityToCefr(8), "A1");
assert.strictEqual(m.abilityToCefr(42), "B1");
assert.strictEqual(m.abilityToCefr(92), "C2");
assert.ok(m.cefrToAbility("B1") > m.cefrToAbility("A2"), "B1 > A2");
assert.ok(m.cefrToAbility("C1") > m.cefrToAbility("B1"), "C1 > B1");
assert.strictEqual(m.cefrToAbility("nonsense"), m.DEFAULT_ABILITY, "invalid band -> default ability");
assert.strictEqual(m.abilityToCefr(m.cefrToAbility("B2")), "B2", "band -> ability -> band round trip");

console.log("learnerModel.test.js passed");
