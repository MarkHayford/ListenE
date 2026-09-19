const assert = require("assert");
const {
  isLongFormCardRequest,
  isWordBankClozePracticeCardRequest,
  isCetWordBankClozeRequest,
  hasAffirmativeListeningMaterialCue,
  isListeningMaterialNegated,
  isFuzzyWritingPracticeRequest,
  isGrammarOrMistakePatternTopicRequest,
  isFuzzyReadingPracticeRequest,
  isFuzzySpeakingPracticeRequest,
  isFuzzyScenarioPracticeRequest,
  inferRequestedDialogueParticipantCount,
  inferExplicitScenarioConstraint
} = require("../src/services/intent");

// ---- isLongFormCardRequest ----
assert.strictEqual(isLongFormCardRequest("写一篇长篇作文"), true);
assert.strictEqual(isLongFormCardRequest("give me a detailed essay"), true);
assert.strictEqual(isLongFormCardRequest("100 words please"), true);
assert.strictEqual(isLongFormCardRequest("你好"), false);

// ---- 听力材料否定 / 肯定线索 ----
assert.strictEqual(isListeningMaterialNegated("不要听力"), true);
assert.strictEqual(isListeningMaterialNegated("no audio please"), true);
assert.strictEqual(isListeningMaterialNegated("给我听力"), false);
assert.strictEqual(hasAffirmativeListeningMaterialCue("给我一段听力"), true);
assert.strictEqual(hasAffirmativeListeningMaterialCue("不要听力"), false); // 被否定
assert.strictEqual(hasAffirmativeListeningMaterialCue("写作文"), false);

// ---- 选词填空 / word-bank cloze（听力优先排除）----
assert.strictEqual(isCetWordBankClozeRequest("来一道四级选词填空"), true);
assert.strictEqual(isCetWordBankClozeRequest("选词填空，但配一段听力音频"), false);
assert.strictEqual(isCetWordBankClozeRequest("你好"), false);
assert.strictEqual(isWordBankClozePracticeCardRequest("fill in the blank cloze"), true);
assert.strictEqual(isWordBankClozePracticeCardRequest("选词填空 加 听力音频"), false);
assert.strictEqual(isWordBankClozePracticeCardRequest("你好"), false);

// ---- 写作意图：命中题材且有练习线索才算 ----
assert.strictEqual(isFuzzyWritingPracticeRequest("帮我出一道写作题"), true);
assert.strictEqual(isFuzzyWritingPracticeRequest("essay"), false); // 仅题材、无练习线索
assert.strictEqual(isFuzzyWritingPracticeRequest("你好"), false);

// ---- 语法/错题题材（含 reading 时排除）----
assert.strictEqual(isGrammarOrMistakePatternTopicRequest("grammar tense drill"), true);
assert.strictEqual(isGrammarOrMistakePatternTopicRequest("reading comprehension about grammar"), false);
assert.strictEqual(isGrammarOrMistakePatternTopicRequest("hello"), false);

// ---- 阅读意图（语法题材时不算阅读）----
assert.strictEqual(isFuzzyReadingPracticeRequest("给我一篇阅读理解题"), true);
assert.strictEqual(isFuzzyReadingPracticeRequest("grammar tense"), false);
assert.strictEqual(isFuzzyReadingPracticeRequest("你好"), false);

// ---- 口语意图（显式否定时排除）----
assert.strictEqual(isFuzzySpeakingPracticeRequest("来一道口语练习题"), true);
assert.strictEqual(isFuzzySpeakingPracticeRequest("no speaking please"), false);
assert.strictEqual(isFuzzySpeakingPracticeRequest("oral"), false); // 仅题材、无练习线索

// ---- 场景意图 ----
assert.strictEqual(isFuzzyScenarioPracticeRequest("来一个场景对话练习"), true);
assert.strictEqual(isFuzzyScenarioPracticeRequest("你好"), false);

// ---- 对话人数推断 ----
assert.strictEqual(inferRequestedDialogueParticipantCount("3 people dialogue"), 3);
assert.strictEqual(inferRequestedDialogueParticipantCount("两人对话"), 2);
assert.strictEqual(inferRequestedDialogueParticipantCount("five speakers"), 5);
assert.strictEqual(inferRequestedDialogueParticipantCount("你好"), null);

// ---- 显式场景约束（非场景→null；命中→{participantCount, promptCount}）----
assert.strictEqual(inferExplicitScenarioConstraint("你好"), null);
{
  const c = inferExplicitScenarioConstraint("场景对话 5 prompts 3 people");
  assert.ok(c && typeof c === "object", "应返回对象");
  assert.strictEqual(c.promptCount, 5);
  assert.strictEqual(c.participantCount, 3);
}

console.log("intent.test.js passed");
