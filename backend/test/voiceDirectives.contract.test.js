const assert = require("assert");

process.env.MIMO_API_KEY = "";

const { createWorkspace } = require("../src/services/learningWorkspace");
const { __test } = require("../src/services/mimoTts");
const { __test: mimoTextTest } = require("../src/services/mimoText");

(async () => {
  const created = await createWorkspace({
    need: "生成一套雅思听力训练，温柔低音女声，英音，语速慢"
  });

  const plan = created.workspace.plan;
  assert.strictEqual(plan.contentType, "dialogue");
  assert.strictEqual(plan.speechRate, "slow");
  assert.strictEqual(plan.voiceGender, "female");
  assert.strictEqual(plan.pitch, "low");
  assert.strictEqual(plan.accent, "british");
  assert.strictEqual(plan.tone, "gentle");
  assert.match(plan.voiceProfile, /Female/i);
  assert.match(plan.voiceProfile, /gentle|warm|温柔/i);
  assert.match(plan.voiceProfile, /low|低/i);
  assert.match(plan.voiceProfile, /British|英/i);

  const enriched = __test.buildVoiceProfileWithDirectives(
    "Female adult narrator, clear bright mid-range voice.",
    {
      voiceGender: "female",
      voiceProfile: "Female gentle low-pitched British tutor voice",
      pitch: "low",
      accent: "british",
      tone: "gentle"
    }
  );
  assert.match(enriched, /Female/);
  assert.match(enriched, /gentle/);
  assert.match(enriched, /low/);
  assert.match(enriched, /British/);

  const maleTtsSpeaker = __test.buildVoiceProfileWithDirectives(
    "Male young adult IELTS student with a medium-pitched, earnest, and articulate British accent.",
    {
      voiceGender: "female",
      voiceProfile: "Female gentle low-pitched British tutor voice",
      pitch: "low",
      accent: "british",
      tone: "gentle"
    },
    "male"
  );
  assert.match(maleTtsSpeaker, /^Male/i);
  assert.doesNotMatch(maleTtsSpeaker, /\bFemale\b/i);
  assert.match(maleTtsSpeaker, /gentle/i);
  assert.match(maleTtsSpeaker, /low/i);
  assert.match(maleTtsSpeaker, /British/i);

  const maleListeningSpeaker = mimoTextTest.buildVoiceProfileWithDirectives(
    "Male young adult IELTS student with a medium-pitched, earnest, and articulate British accent.",
    {
      voiceGender: "female",
      voiceProfile: "Female gentle low-pitched British tutor voice",
      pitch: "low",
      accent: "british",
      tone: "gentle"
    },
    "male"
  );
  assert.match(maleListeningSpeaker, /^Male/i);
  assert.doesNotMatch(maleListeningSpeaker, /\bFemale\b/i);
  assert.match(maleListeningSpeaker, /gentle/i);
  assert.match(maleListeningSpeaker, /low/i);
  assert.match(maleListeningSpeaker, /British/i);

  console.log("voiceDirectives.contract.test.js passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
