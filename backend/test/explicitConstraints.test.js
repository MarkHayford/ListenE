const assert = require("assert");
const {
  inferExplicitChoiceConstraint,
  inferExplicitTranslationConstraint,
  inferExplicitListeningCueConstraint,
  parseExplicitChoiceOptions,
  cleanExplicitChoiceTerm,
  cleanExplicitTranslationTerm,
  cleanExplicitCueTerm,
  inferKnownTranslationTerms,
  parseExplicitCueTerms,
  uniqueAgentCardTexts,
  uniqueMinimalPairObjects,
  inferExplicitMinimalPairs,
  inferExplicitSlashMinimalPairsFromText,
  inferExplicitPhraseTargets,
  inferExplicitSpeakingPromptTopic,
  inferExplicitSpeakingPromptTopicInfo,
  inferExplicitSpeakingPromptOnlyText,
  cleanSpeakingPromptTopic,
  speakingPromptFromTopic,
  sentenceCaseAgentPrompt,
  inferAgentRequestedQuestionCount,
  inferRequestedAgentCardItemCounts,
  agentCardComponentTextMaxLength
} = require("../src/services/explicitConstraints");

// ---- agentCardComponentTextMaxLength：类型→上限查表 ----
assert.strictEqual(agentCardComponentTextMaxLength("summary"), 1400);
assert.strictEqual(agentCardComponentTextMaxLength("reading"), 1800);
assert.strictEqual(agentCardComponentTextMaxLength("cloze"), 800);
assert.strictEqual(agentCardComponentTextMaxLength("ordering"), 600);
assert.strictEqual(agentCardComponentTextMaxLength("vocabulary"), 600);
assert.strictEqual(agentCardComponentTextMaxLength("short_answer"), 360);
assert.strictEqual(agentCardComponentTextMaxLength("unknown-type"), 220);

// ---- sentenceCaseAgentPrompt ----
assert.strictEqual(sentenceCaseAgentPrompt("talk about your day"), "Talk about your day.");
assert.strictEqual(sentenceCaseAgentPrompt("hello."), "Hello.");
assert.strictEqual(sentenceCaseAgentPrompt(""), "");

// ---- speakingPromptFromTopic ----
assert.strictEqual(speakingPromptFromTopic("describe your hometown"), "Describe your hometown.");
assert.strictEqual(speakingPromptFromTopic("your weekend"), "Talk about your weekend.");
assert.strictEqual(speakingPromptFromTopic(""), "");
assert.ok(/unexpected problem while traveling/i.test(speakingPromptFromTopic("a travel problem")));

// ---- cleanSpeakingPromptTopic：去等级/卡片词 ----
{
  const t = cleanSpeakingPromptTopic("B1 speaking card about travel");
  assert.ok(!/b1/i.test(t) && !/card/i.test(t) && !/speaking/i.test(t));
  assert.ok(/travel/.test(t));
}

// ---- 口语提示主题推断 ----
{
  const info = inferExplicitSpeakingPromptTopicInfo("topic: my favorite food only prompt");
  assert.ok(info && info.topic === "my favorite food" && info.source === "topic");
  assert.strictEqual(inferExplicitSpeakingPromptTopic("topic: my favorite food only prompt"), "my favorite food");
  assert.strictEqual(inferExplicitSpeakingPromptOnlyText("topic: travel only prompt"), "Talk about travel.");
  assert.strictEqual(inferExplicitSpeakingPromptTopicInfo("hello"), null);
}

// ---- inferAgentRequestedQuestionCount ----
assert.strictEqual(inferAgentRequestedQuestionCount("give me 3 questions"), 3);
assert.strictEqual(inferAgentRequestedQuestionCount("出5道题"), 5);
assert.strictEqual(inferAgentRequestedQuestionCount("five questions please"), 5);
assert.strictEqual(inferAgentRequestedQuestionCount("hello"), null);

// ---- inferRequestedAgentCardItemCounts（导出给 __test 的主函数）----
assert.strictEqual(inferRequestedAgentCardItemCounts("give me 5 vocabulary words").vocabulary, 5);
assert.strictEqual(inferRequestedAgentCardItemCounts("create 3 questions").question_set, 3);
assert.deepStrictEqual(inferRequestedAgentCardItemCounts("hello there"), {});

// ---- uniqueAgentCardTexts：压缩 + 去重(按归一化) ----
assert.deepStrictEqual(uniqueAgentCardTexts(["Apple", "apple ", "Banana"]), ["Apple", "Banana"]);
assert.deepStrictEqual(uniqueAgentCardTexts([]), []);

// ---- uniqueMinimalPairObjects：双向去重 ----
{
  const out = uniqueMinimalPairObjects([
    { left: "ship", right: "sheep" },
    { left: "sheep", right: "ship" }, // 反向重复
    { left: "bit", right: "beat" }
  ]);
  assert.strictEqual(out.length, 2);
  assert.deepStrictEqual(out[0], { left: "ship", right: "sheep", hint: "" });
}

// ---- inferExplicitSlashMinimalPairsFromText ----
assert.deepStrictEqual(
  inferExplicitSlashMinimalPairsFromText("compare ship/sheep and bit / beat"),
  [["ship", "sheep"], ["bit", "beat"]]
);
assert.deepStrictEqual(inferExplicitSlashMinimalPairsFromText("no pairs here"), []);

// ---- inferExplicitMinimalPairs ----
{
  const mp = inferExplicitMinimalPairs("minimal pair card with ship sheep");
  assert.ok(mp.some((p) => p.left === "ship" && p.right === "sheep"));
}

// ---- inferExplicitPhraseTargets：抽取动词短语 ----
{
  const ph = inferExplicitPhraseTargets("please practice look up and turn off today");
  assert.ok(ph.includes("look up") && ph.includes("turn off"));
}

// ---- 小清洗器 ----
assert.strictEqual(cleanExplicitChoiceTerm("word apple."), "apple");
assert.strictEqual(cleanExplicitCueTerm("however only no extra"), "however");
assert.strictEqual(cleanExplicitTranslationTerm("'hello'"), "hello");

// ---- parseExplicitChoiceOptions：逗号分隔 + 截断到4 ----
assert.deepStrictEqual(parseExplicitChoiceOptions("apple, banana, cherry", "apple"), ["apple", "banana", "cherry"]);
assert.strictEqual(parseExplicitChoiceOptions("cat, dog, fish, bird, snake", "cat").length, 4);

// ---- inferKnownTranslationTerms：命中词库 ----
assert.deepStrictEqual(inferKnownTranslationTerms("boarding pass and window seat"), ["boarding pass", "window seat"]);

// ---- parseExplicitCueTerms：命中信号词库 ----
{
  const cues = parseExplicitCueTerms("however and therefore");
  assert.ok(cues.includes("however") && cues.includes("therefore"));
}

// ---- inferExplicitListeningCueConstraint ----
{
  const c = inferExplicitListeningCueConstraint("signal words: however, therefore, although");
  assert.ok(c && Array.isArray(c.terms) && c.terms.includes("however"));
}

// ---- inferExplicitChoiceConstraint（集成）----
{
  const r = inferExplicitChoiceConstraint("meaning choice card for happy options: glad, sad, tall answer: glad");
  assert.ok(r && r.target === "happy" && r.answer === "glad");
  assert.deepStrictEqual(r.options, ["glad", "sad", "tall"]);
  assert.strictEqual(inferExplicitChoiceConstraint("hello"), null);
}

// ---- inferExplicitTranslationConstraint（集成）----
{
  const r = inferExplicitTranslationConstraint("translate boarding pass into chinese");
  assert.ok(r && r.source === "boarding pass");
  assert.ok(r.terms.includes("boarding pass"));
  assert.strictEqual(inferExplicitTranslationConstraint("hello"), null);
}

console.log("explicitConstraints.test.js passed");
