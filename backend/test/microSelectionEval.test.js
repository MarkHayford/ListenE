"use strict";

// 离线单测：微元选型评测的「打分器」纯函数（不触网、不调模型）。
const assert = require("assert");
const { primaryTypesOf, dominantPrimaryType, scoreCase, summarize, runPool } = require("../eval/microSelectionEval");

(async () => {
  // primaryTypesOf：只保留核心交互题型，忽略 text/passage/audio 等脚手架。
  {
    const card = { nodes: [{ type: "text" }, { type: "passage" }, { type: "open_cloze" }, { type: "audio" }] };
    assert.deepStrictEqual(primaryTypesOf(card), ["open_cloze"]);
  }
  // dominantPrimaryType：取出现最多者；并列取先出现者；只有脚手架 → null。
  {
    assert.strictEqual(dominantPrimaryType({ nodes: [{ type: "choice" }, { type: "cloze_select" }, { type: "cloze_select" }] }), "cloze_select");
    assert.strictEqual(dominantPrimaryType({ nodes: [{ type: "choice" }, { type: "true_false" }] }), "choice");
    assert.strictEqual(dominantPrimaryType({ nodes: [{ type: "text" }, { type: "passage" }] }), null);
    assert.strictEqual(dominantPrimaryType(null), null);
  }
  // 非判分但属核心交互的题型也纳入（flashcard/roleplay_turn/speak_score/timed_challenge/ai_hint/pairs_memory）。
  {
    assert.strictEqual(dominantPrimaryType({ nodes: [{ type: "text" }, { type: "flashcard" }] }), "flashcard");
    assert.strictEqual(dominantPrimaryType({ nodes: [{ type: "roleplay_turn" }] }), "roleplay_turn");
  }
  // scoreCase：主导命中 + 无禁用。
  {
    const s = scoreCase({ expect: ["open_cloze"], forbid: ["choice", "cloze_select"] }, { nodes: [{ type: "passage" }, { type: "open_cloze" }] });
    assert.ok(s.primaryHit && s.softHit && !s.forbidHit);
  }
  // scoreCase：并列时主导取先出现的 choice → 主导未命中，但期望题型出现(soft)、且触发禁用。
  {
    const s = scoreCase({ expect: ["open_cloze"], forbid: ["choice"] }, { nodes: [{ type: "choice" }, { type: "open_cloze" }] });
    assert.ok(!s.primaryHit && s.softHit && s.forbidHit);
  }
  // scoreCase：只有脚手架 → 完全未命中。
  {
    const s = scoreCase({ expect: ["dictation"] }, { nodes: [{ type: "text" }] });
    assert.strictEqual(s.primary, null);
    assert.ok(!s.primaryHit && !s.softHit);
  }
  // scoreCase：expect 大小写不敏感。
  {
    assert.ok(scoreCase({ expect: ["OPEN_CLOZE"] }, { nodes: [{ type: "open_cloze" }] }).primaryHit);
  }
  // summarize：命中/禁用/错误计数与混淆表。
  {
    const rows = [
      { id: "a", score: scoreCase({ expect: ["choice"] }, { nodes: [{ type: "choice" }] }) },
      { id: "b", score: scoreCase({ expect: ["open_cloze"], forbid: ["choice"] }, { nodes: [{ type: "choice" }] }) },
      { id: "c", error: "boom", score: scoreCase({ expect: ["x"] }, null) }
    ];
    const sum = summarize(rows);
    assert.strictEqual(sum.total, 3);
    assert.strictEqual(sum.primaryHits, 1);
    assert.strictEqual(sum.forbidHits, 1);
    assert.strictEqual(sum.errors, 1);
    assert.strictEqual(sum.confusion.length, 2);
    assert.ok(sum.primaryRate > 0.33 && sum.primaryRate < 0.34);
  }
  // runPool：并发执行且保持顺序。
  {
    const out = await runPool([1, 2, 3, 4, 5], async (x) => x * 2, 2);
    assert.deepStrictEqual(out, [2, 4, 6, 8, 10]);
  }
  console.log("microSelectionEval.test.js passed");
})().catch((e) => {
  console.error(e);
  process.exit(1);
});
