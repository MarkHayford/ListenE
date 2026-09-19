const assert = require("assert");

process.env.MIMO_API_KEY = "";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { generateListeningContent, __test } = require("../src/services/mimoText");

const questions = Array.from({ length: 6 }, (_, index) => ({
  questionText: `Question ${index + 1}?`,
  options: ["A", "B", "C", "D"],
  correctAnswer: 0,
  explanation: ""
}));

(async () => {
  const constraints = __test.resolveListeningGenerationConstraints({
    scene: "三人商务会议对话，困难难度，出5道题",
    difficulty: "普通"
  }, "dialogue");

  assert.strictEqual(constraints.difficulty, "困难");
  assert.strictEqual(constraints.questionCount, 5);
  assert.strictEqual(constraints.speakerCount, 3);

  const currentRequestDifficulty = __test.resolveListeningGenerationConstraints({
    scene: "两人校园对话，普通难度，4道题",
    difficulty: "困难"
  }, "dialogue");
  assert.strictEqual(currentRequestDifficulty.difficulty, "普通");
  assert.strictEqual(currentRequestDifficulty.questionCount, 4);
  assert.strictEqual(currentRequestDifficulty.speakerCount, 2);

  const englishTypedConstraints = __test.resolveListeningGenerationConstraints({
    scene: "constraintverify1 listening dialogue at a train station exactly 3 speakers B1 difficulty exactly 4 multiple choice questions only audio transcript and questions"
  }, "dialogue");
  assert.strictEqual(englishTypedConstraints.difficulty, "普通");
  assert.strictEqual(englishTypedConstraints.questionCount, 4);
  assert.strictEqual(englishTypedConstraints.speakerCount, 3);

  const result = __test.enforceListeningGenerationConstraints({
    title: "Project Meeting",
    script: "Alice: Hi\n\nBen: Hello\n\nCara: Morning",
    questions,
    speakers: [
      { speakerId: "P1", speakerName: "Alice" },
      { speakerId: "P2", speakerName: "Ben" },
      { speakerId: "P3", speakerName: "Cara" }
    ],
    ttsSegments: [
      { speakerId: "P1", text: "Hi" },
      { speakerId: "P2", text: "Hello" },
      { speakerId: "P3", text: "Morning" }
    ]
  }, constraints, "dialogue");

  assert.strictEqual(result.questions.length, 5);
  assert.strictEqual(result.speakers.length, 3);
  assert.strictEqual(new Set(result.ttsSegments.map((segment) => segment.speakerId)).size, 3);

  const repairedMissingSpeakerTurn = __test.enforceListeningGenerationConstraints({
    title: "Hotel Check-in",
    script: "Alice: Good evening, welcome to the hotel.\n\nBen: I have a reservation for tonight.",
    questions,
    speakers: [
      { speakerId: "P1", speakerName: "Alice", speakerGender: "female" },
      { speakerId: "P2", speakerName: "Ben", speakerGender: "male" },
      { speakerId: "P3", speakerName: "Cara", speakerGender: "female" }
    ],
    ttsSegments: [
      { speakerId: "P1", speakerName: "Alice", speakerGender: "female", text: "Good evening, welcome to the hotel." },
      { speakerId: "P2", speakerName: "Ben", speakerGender: "male", text: "I have a reservation for tonight." }
    ]
  }, constraints, "dialogue");

  assert.strictEqual(new Set(repairedMissingSpeakerTurn.ttsSegments.map((segment) => segment.speakerId)).size, 3);
  assert.match(repairedMissingSpeakerTurn.script, /Cara:/);

  assert.throws(() => {
    __test.enforceListeningGenerationConstraints({
      title: "Bad Meeting",
      questions: questions.slice(0, 4),
      speakers: [{ speakerId: "P1" }, { speakerId: "P2" }, { speakerId: "P3" }],
      ttsSegments: [{ speakerId: "P1", text: "Hi" }, { speakerId: "P2", text: "Hello" }, { speakerId: "P3", text: "Morning" }]
    }, constraints, "dialogue");
  }, /Expected exactly 5 questions/);

  const originalFetch = global.fetch;
  let prompt = "";
  global.fetch = async (_url, options = {}) => {
    const body = JSON.parse(String(options.body || "{}"));
    prompt = String(body.messages?.find((item) => item.role === "user")?.content || "");
    return {
      ok: true,
      text: async () => JSON.stringify({
        choices: [{
          message: {
            content: JSON.stringify({
              title: "Project Meeting",
              script: "Alice: Hi\\n\\nBen: Hello\\n\\nCara: Morning",
              speakers: [
                { speakerId: "P1", speakerName: "Alice", speakerGender: "female", voiceProfile: "Female project manager, bright clear voice." },
                { speakerId: "P2", speakerName: "Ben", speakerGender: "male", voiceProfile: "Male engineer, low steady voice." },
                { speakerId: "P3", speakerName: "Cara", speakerGender: "female", voiceProfile: "Female designer, warm medium voice." }
              ],
              ttsSegments: [
                { speakerId: "P1", text: "Hi" },
                { speakerId: "P2", text: "Hello" },
                { speakerId: "P3", text: "Morning" }
              ],
              questions
            })
          }
        }]
      })
    };
  };

  try {
    const generated = await generateListeningContent({
      scene: "三人商务会议对话，困难难度，出5道题",
      difficulty: "普通",
      contentType: "dialogue"
    });
    assert.match(prompt, /Questions: generate exactly 5 high-quality questions/);
    assert.match(prompt, /Speaker count: create exactly 3 unique speakers/);
    assert.strictEqual(generated.questions.length, 5);
    assert.strictEqual(generated.speakers.length, 3);
    assert.doesNotMatch(generated.script, /\\n/);
    assert.match(generated.script, /Alice: Hi\n\nBen: Hello/);
  } finally {
    global.fetch = originalFetch;
  }

  console.log("listeningConstraints.test.js passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
