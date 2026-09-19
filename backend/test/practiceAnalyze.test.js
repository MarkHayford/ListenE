const assert = require("assert");
const { buildPracticeAnalysisPayload, normalizePracticeAnalysisResult } = require("../src/services/practiceAnalyze");

(() => {
  // ---- 作答表归一化：客户端 correct 标记为判分事实；空题面/空正解丢弃；wrongItems 只含答错项 ----
  {
    const norm = buildPracticeAnalysisPayload({
      title: "  完形练习  ",
      items: [
        { type: "CLOZE_SELECT", prompt: "I ___ to school.", userAnswer: "go", correctAnswer: "went", correct: false, explanation: "过去时" },
        { type: "choice", prompt: "选一个", userAnswer: "b", correctAnswer: "b", correct: true },
        { type: "input", prompt: "", userAnswer: "x", correctAnswer: "y", correct: false },
        { type: "translate", prompt: "翻译", userAnswer: "", correctAnswer: "I go home.", correct: false }
      ]
    });
    assert.strictEqual(norm.title, "完形练习");
    assert.strictEqual(norm.total, 3); // 空题面项被丢弃
    assert.strictEqual(norm.correct, 1);
    assert.strictEqual(norm.wrongItems.length, 2);
    assert.strictEqual(norm.items[0].type, "cloze_select");
    assert.strictEqual(norm.wrongItems[0].prompt, "I ___ to school.");
    assert.strictEqual(norm.wrongItems[1].userAnswer, "");
  }

  // ---- 模型输出对齐：只保留程序错题；模型漏讲的错题补兜底；编造的题号被丢弃 ----
  {
    const norm = buildPracticeAnalysisPayload({
      title: "t",
      items: [
        { type: "choice", prompt: "Q1", userAnswer: "a", correctAnswer: "b", correct: false },
        { type: "choice", prompt: "Q2", userAnswer: "b", correctAnswer: "b", correct: true },
        { type: "input", prompt: "Q3", userAnswer: "", correctAnswer: "c", correct: false }
      ]
    });
    const result = normalizePracticeAnalysisResult({
      summary: "有两处基础错误",
      weakPoints: ["冠词", ""],
      suggestions: ["再练一组同类题"],
      diagnosisTags: ["细节"],
      wrongQuestionInsights: [
        { questionIndex: 0, question: "Q1", mistakeType: "搭配", insight: "a 与语境不符，应选 b。" },
        { questionIndex: 1, question: "Q2", mistakeType: "编造", insight: "这题其实答对了" },
        { questionIndex: 99, question: "不存在", insight: "无效" }
      ]
    }, norm);
    assert.strictEqual(result.summary, "有两处基础错误");
    assert.deepStrictEqual(result.weakPoints, ["冠词"]);
    // 只解释程序判定的错题（Q1、Q3），答对的 Q2 与编造题号被剔除
    assert.deepStrictEqual(result.wrongQuestionInsights.map((w) => w.questionIndex), [0, 2]);
    assert.strictEqual(result.wrongQuestionInsights[0].mistakeType, "搭配");
    // Q3 模型没讲 → 兜底：未作答
    assert.strictEqual(result.wrongQuestionInsights[1].mistakeType, "未作答");
    assert.ok(result.wrongQuestionInsights[1].insight.length > 0);
    assert.deepStrictEqual(result.nextActions, []);
    assert.deepStrictEqual(result.recommendedPlanTasks, []);
  }

  // ---- 模型输出缺失/非对象：全兜底，summary 按成绩生成 ----
  {
    const norm = buildPracticeAnalysisPayload({
      items: [{ type: "choice", prompt: "Q1", userAnswer: "b", correctAnswer: "b", correct: true }]
    });
    const result = normalizePracticeAnalysisResult(null, norm);
    assert.ok(result.summary.includes("全部答对"));
    assert.deepStrictEqual(result.wrongQuestionInsights, []);
  }

  console.log("practiceAnalyze.test.js passed");
})();
