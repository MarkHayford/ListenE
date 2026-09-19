const assert = require("assert");
const {
  scenarioQuestionReferencesUnsupportedDetail,
  scenarioQuestionEvidenceText,
  scenarioQuestionEvidenceContains,
  scenarioQuestionAllowedProperNouns,
  scenarioQuestionUnsupportedFactTerms,
  scenarioQuestionCorrectOptionText,
  scenarioQuestionSetTitle,
  scenarioQuestionBank,
  englishCountWord,
  scenarioParticipantDistractors,
  scenarioSpeakerRolesFromItems,
  inferScenarioSpeakerRoleFromItem,
  isNonSpeakerScenarioLabel
} = require("../src/services/scenario");

// ---- englishCountWord ----
assert.strictEqual(englishCountWord(0), "zero");
assert.strictEqual(englishCountWord(2), "two");
assert.strictEqual(englishCountWord(8), "eight");
assert.strictEqual(englishCountWord(9), "9"); // 超出表→数字串
assert.strictEqual(englishCountWord(20), "20");

// ---- scenarioParticipantDistractors：排除自身人数、最多 5 个、单复数正确 ----
assert.deepStrictEqual(
  scenarioParticipantDistractors(2),
  ["one person", "three people", "four people", "five people", "six people"]
);
assert.ok(!scenarioParticipantDistractors(2).includes("two people"));

// ---- scenarioQuestionSetTitle ----
assert.strictEqual(scenarioQuestionSetTitle("pharmacy"), "Pharmacy Scenario Questions");
assert.strictEqual(scenarioQuestionSetTitle("hotel_check_in"), "Hotel Check-in Questions");
assert.strictEqual(scenarioQuestionSetTitle("airport"), "Airport Scenario Questions");
assert.strictEqual(scenarioQuestionSetTitle("restaurant"), "Restaurant Scenario Questions");
assert.strictEqual(scenarioQuestionSetTitle("whatever"), "Scenario Questions");
assert.strictEqual(scenarioQuestionSetTitle(), "Scenario Questions");

// ---- scenarioQuestionCorrectOptionText ----
assert.strictEqual(scenarioQuestionCorrectOptionText({ options: ["a", "b", "c"], correctAnswer: 1 }), "b");
assert.strictEqual(scenarioQuestionCorrectOptionText({ options: ["a"], correctAnswer: "0" }), "a");
assert.strictEqual(scenarioQuestionCorrectOptionText({ options: ["a", "b"], correctAnswer: 5 }), "");
assert.strictEqual(scenarioQuestionCorrectOptionText({}), "");

// ---- scenarioQuestionEvidenceText：聚合并归一化 ----
{
  const ev = scenarioQuestionEvidenceText(
    { title: "Hotel", text: "Check in", items: ["Front desk"], pairs: [{ left: "Guest", right: "Clerk", hint: "key" }] },
    { messageText: "please" }
  );
  assert.ok(ev.includes("hotel") && ev.includes("front desk") && ev.includes("guest") && ev.includes("key"));
  assert.strictEqual(ev, ev.toLowerCase()); // 已小写归一
}

// ---- scenarioQuestionEvidenceContains：空 term 视为命中 ----
assert.strictEqual(scenarioQuestionEvidenceContains("at a hotel front desk", "Hotel"), true);
assert.strictEqual(scenarioQuestionEvidenceContains("at a hotel", "airport"), false);
assert.strictEqual(scenarioQuestionEvidenceContains("anything", ""), true);

// ---- scenarioQuestionAllowedProperNouns ----
{
  const s = scenarioQuestionAllowedProperNouns();
  assert.ok(s instanceof Set);
  assert.ok(s.has("hotel") && s.has("airport") && s.has("speaker"));
  assert.ok(!s.has("headache"));
}

// ---- scenarioQuestionUnsupportedFactTerms ----
assert.deepStrictEqual(
  scenarioQuestionUnsupportedFactTerms("He has a headache and lost his passport"),
  ["headache", "passport"]
);
assert.deepStrictEqual(scenarioQuestionUnsupportedFactTerms("nothing notable here"), []);

// ---- scenarioQuestionReferencesUnsupportedDetail ----
// 题面引用了证据里没有的专有名词(Paris) → 判为越界
assert.strictEqual(
  scenarioQuestionReferencesUnsupportedDetail({ questionText: "Is it in Paris?", options: [] }, { text: "we are at a hotel" }, {}),
  true
);
// 全部有据可循 → false
assert.strictEqual(
  scenarioQuestionReferencesUnsupportedDetail({ questionText: "is it nearby?", options: [] }, { text: "we are at a hotel nearby" }, {}),
  false
);

// ---- scenarioQuestionBank ----
{
  const bank = scenarioQuestionBank("pharmacy", ["Pharmacist", "Customer"], 2);
  assert.ok(Array.isArray(bank) && bank.length === 4);
  assert.strictEqual(bank[0].answer, "At a pharmacy");
  const head = bank.find((q) => /how many people/i.test(q.questionText));
  assert.ok(head && head.answer === "two people");
  // 角色代入
  const r = scenarioQuestionBank("restaurant", ["Waiter", "Guest"]);
  assert.ok(r.some((q) => q.answer === "Waiter"));
  // 兜底 generic
  const g = scenarioQuestionBank("anything-else");
  assert.ok(Array.isArray(g) && g.some((q) => /how many people/i.test(q.questionText)));
}

// ---- inferScenarioSpeakerRoleFromItem ----
assert.strictEqual(inferScenarioSpeakerRoleFromItem("Customer: Hi there"), "Customer");
assert.strictEqual(inferScenarioSpeakerRoleFromItem("- Pharmacist: Hello"), "Pharmacist");
assert.strictEqual(inferScenarioSpeakerRoleFromItem("Speaker B: A nurse - (calm)"), "A nurse");
assert.strictEqual(inferScenarioSpeakerRoleFromItem("Location: Hotel"), ""); // 非说话人标签
assert.strictEqual(inferScenarioSpeakerRoleFromItem("no colon here"), "");

// ---- scenarioSpeakerRolesFromItems ----
{
  const roles = scenarioSpeakerRolesFromItems(["Customer: Hi there", "Location: Hotel lobby"]);
  assert.ok(roles instanceof Set);
  assert.ok(roles.has("Customer"));
  assert.ok(!roles.has("Location"));
}

// ---- isNonSpeakerScenarioLabel ----
assert.strictEqual(isNonSpeakerScenarioLabel("Location"), true);
assert.strictEqual(isNonSpeakerScenarioLabel("difficulty"), true);
assert.strictEqual(isNonSpeakerScenarioLabel("场景"), true);
assert.strictEqual(isNonSpeakerScenarioLabel("Customer"), false);

console.log("scenario.test.js passed");
