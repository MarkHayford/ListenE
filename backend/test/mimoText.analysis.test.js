const assert = require("assert");

const {
  __test
} = require("../src/services/mimoText");

function run() {
  const wrongPayload = {
    title: "Wrong answer case",
    script: "Alex wanted to take the earlier train because the later train was fully booked.",
    questions: [
      {
        questionText: "Why did Alex choose the earlier train?",
        options: ["It was cheaper.", "The later train was fully booked.", "It was faster.", "He missed a meeting."],
        correctAnswer: 1,
        explanation: "The script says the later train was fully booked."
      }
    ],
    selectedAnswers: [
      { questionIndex: 0, selectedAnswer: 0 }
    ]
  };

  const slimPayload = __test.buildAnalysisPayload(wrongPayload);
  const wrongQuestions = __test.evaluateAnswerSheet(slimPayload);
  assert.strictEqual(wrongQuestions.length, 1);
  assert.strictEqual(wrongQuestions[0].questionIndex, 0);
  assert.strictEqual(wrongQuestions[0].selectedAnswerIndex, 0);
  assert.strictEqual(wrongQuestions[0].correctAnswerIndex, 1);

  const normalized = __test.normalizeAnalysisResultWithAnswerSheet(
    {
      summary: "全部正确。",
      weakPoints: [],
      suggestions: [],
      diagnosisTags: [],
      wrongQuestionInsights: []
    },
    slimPayload
  );

  assert.strictEqual(normalized.wrongQuestionInsights.length, 1);
  assert.strictEqual(normalized.wrongQuestionInsights[0].questionIndex, 0);
  assert.strictEqual(normalized.wrongQuestionInsights[0].selectedAnswer, "It was cheaper.");
  assert.strictEqual(normalized.wrongQuestionInsights[0].correctAnswer, "The later train was fully booked.");
  assert.match(normalized.summary, /1\s*道错题|1\s*题|错题/);

  const correctPayload = {
    ...wrongPayload,
    selectedAnswers: [
      { questionIndex: 0, selectedAnswer: 1 }
    ]
  };
  const normalizedCorrect = __test.normalizeAnalysisResultWithAnswerSheet(
    {
      summary: "本次有 1 道错题，需要复盘。",
      weakPoints: [],
      suggestions: [],
      diagnosisTags: [],
      wrongQuestionInsights: [
        {
          questionIndex: 0,
          question: "Why did Alex choose the earlier train?",
          selectedAnswer: "It was cheaper.",
          correctAnswer: "The later train was fully booked.",
          mistakeType: "细节定位",
          insight: "AI 幻觉出来的错题"
        }
      ]
    },
    correctPayload
  );

  assert.strictEqual(normalizedCorrect.wrongQuestionInsights.length, 0);
  assert.match(normalizedCorrect.summary, /没有发现错题/);

  const clozeQuality = __test.assessAgentCardQuality({
    message: "create a B1 cloze practice with exactly 3 blanks",
    cardSpec: {
      kind: "custom",
      title: "B1 Cloze",
      components: [
        { type: "header" },
        {
          type: "cloze",
          title: "Cloze",
          text: "Maintaining healthy habits is ___. Eating a ___ diet helps. Regular ___ reduces stress.",
          options: ["exercise", "balanced", "essential"],
          answer: "essential | balanced | exercise"
        },
        { type: "actions" }
      ],
      actions: []
    }
  });
  assert.deepStrictEqual(clozeQuality.warnings, []);

  assert.strictEqual(__test.sanitizeAgentOutputFormat("zip"), "zip");
  assert.strictEqual(__test.sanitizeAgentOutputFormat("ListenE.zip"), "zip");
  assert.strictEqual(__test.outputMimeType("", "zip"), "application/zip");

  assert.strictEqual(__test.isAgentOutputFileRequest("export_the_previous_set_for_me"), true);
  assert.strictEqual(__test.isAgentOutputFileRequest("send_previous_set_as_file"), true);
  assert.strictEqual(__test.isAgentOutputFileRequest("导出题目"), true);
  assert.strictEqual(__test.isAgentOutputFileRequest("导出听力题"), true);
  assert.strictEqual(__test.isAgentListeningZipExportRequest("导出听力题"), true);
  assert.strictEqual(__test.isAgentListeningZipExportRequest("把当前听力素材的题目导出成 docx 发给我"), false);

  const listeningZip = __test.buildAgentListeningZipExportContent(
    {
      title: "Cafe Listening",
      script: "Clerk: What would you like?\nCustomer: Coffee, please.",
      audioReady: true,
      audioUrl: "https://cdn.example.test/audio/cafe.mp3",
      questions: [
        {
          questionText: "What does the customer order?",
          options: ["Coffee", "Tea", "Juice", "Water"],
          correctAnswer: 0
        }
      ]
    },
    "导出听力题"
  );
  assert.match(listeningZip, /audio\/cafe\.mp3/);
  assert.match(listeningZip, /transcript\.txt/);
  assert.match(listeningZip, /questions\.txt/);
  assert.match(listeningZip, /Clerk: What would you like/);
  assert.match(listeningZip, /What does the customer order/);
}

run();
console.log("mimoText.analysis.test.js passed");
