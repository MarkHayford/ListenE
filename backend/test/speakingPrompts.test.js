const assert = require("assert");
const {
  isAgentPromptOnlyRequest,
  isSingleSpeakingPromptOnlyRequest,
  buildFallbackSpeakingPromptComponent,
  inferSpeakingPromptText,
  enforceRequestedSpeakingPromptCount,
  agentCardSpeakingPromptScopedFallbackItems,
  inferSpeakingPromptCurrentTopicScope,
  inferSpeakingPromptTopicProfile,
  agentCardSpeakingPromptTopicFallbackItems,
  filterAgentCardSpeakingPromptFallbackItems,
  isAcceptableAgentCardSpeakingPrompt,
  isAgentCardSpeakingPromptTopicMismatch,
  meaningfulSpeakingPromptTopicTokens,
  isGenericAgentCardSpeakingPrompt,
  isAgentCardSpeakingPromptRoleMismatch,
  agentCardSpeakingPromptIntentKey,
  agentCardSpeakingPromptFallbackItems,
  extractAgentCardNumberedPrompts,
  enforceSpeakingPromptComponentScope
} = require("../src/services/speakingPrompts");

// ---- isAgentPromptOnlyRequest ----
assert.strictEqual(isAgentPromptOnlyRequest("only the prompt please"), true);
assert.strictEqual(isAgentPromptOnlyRequest("prompt only"), true);
assert.strictEqual(isAgentPromptOnlyRequest("只要题目"), true);
assert.strictEqual(isAgentPromptOnlyRequest("hello"), false);

// ---- isSingleSpeakingPromptOnlyRequest ----
assert.strictEqual(isSingleSpeakingPromptOnlyRequest("prompt only", {}), true);
assert.strictEqual(isSingleSpeakingPromptOnlyRequest("speaking prompt, just one, no rubric", {}), true);
assert.strictEqual(isSingleSpeakingPromptOnlyRequest("give me prompts", { speaking_prompt: 3 }), false);
assert.strictEqual(isSingleSpeakingPromptOnlyRequest("hello", {}), false);

// ---- inferSpeakingPromptText ----
assert.strictEqual(inferSpeakingPromptText('say "my dream job"'), "my dream job");
assert.strictEqual(inferSpeakingPromptText("topic: travel plans"), "Talk about travel plans in clear English.");
assert.strictEqual(inferSpeakingPromptText("讲讲校园"), "Talk about school life in clear English.");
assert.strictEqual(inferSpeakingPromptText("random"), "Talk about a familiar daily topic in clear English.");

// ---- inferSpeakingPromptTopicProfile ----
assert.strictEqual(inferSpeakingPromptTopicProfile("my favorite city"), "favorite_city");
assert.strictEqual(inferSpeakingPromptTopicProfile("changing my hotel booking"), "hotel_booking_change");
assert.strictEqual(inferSpeakingPromptTopicProfile("a travel trip with flight delay"), "travel_problem");
assert.strictEqual(inferSpeakingPromptTopicProfile("cooking dinner"), "generic");

// ---- inferSpeakingPromptCurrentTopicScope ----
{
  const sc = inferSpeakingPromptCurrentTopicScope({ messageText: "topic: my favorite city only prompt" });
  assert.ok(sc && sc.profile === "favorite_city");
  assert.strictEqual(inferSpeakingPromptCurrentTopicScope({ messageText: "hello" }), null);
}

// ---- agentCardSpeakingPromptTopicFallbackItems ----
{
  const fc = agentCardSpeakingPromptTopicFallbackItems({ profile: "favorite_city", topic: "Paris" });
  assert.strictEqual(fc.length, 3);
  assert.ok(/favorite city/i.test(fc[0]));
  assert.deepStrictEqual(agentCardSpeakingPromptTopicFallbackItems({ profile: "generic", topic: "" }), []);
  assert.strictEqual(agentCardSpeakingPromptTopicFallbackItems({ profile: "generic", topic: "music" }).length, 3);
}

// ---- meaningfulSpeakingPromptTopicTokens ----
assert.deepStrictEqual(meaningfulSpeakingPromptTopicTokens("Describe your favorite city Paris"), ["city", "paris"]);
assert.deepStrictEqual(meaningfulSpeakingPromptTopicTokens("the and or"), []);

// ---- isGenericAgentCardSpeakingPrompt ----
assert.strictEqual(isGenericAgentCardSpeakingPrompt("State your answer in one simple sentence"), true);
assert.strictEqual(isGenericAgentCardSpeakingPrompt("Describe your city"), false);

// ---- agentCardSpeakingPromptIntentKey ----
assert.strictEqual(agentCardSpeakingPromptIntentKey("Ask for the menu"), "menu");
assert.strictEqual(agentCardSpeakingPromptIntentKey("Order a coffee"), "order");
assert.strictEqual(agentCardSpeakingPromptIntentKey("Pay the bill and say goodbye"), "close");
assert.strictEqual(agentCardSpeakingPromptIntentKey("Greet the host warmly"), "greet");
assert.strictEqual(agentCardSpeakingPromptIntentKey("random text"), "");

// ---- agentCardSpeakingPromptFallbackItems ----
{
  const r = agentCardSpeakingPromptFallbackItems("at a restaurant with a waiter");
  assert.strictEqual(r.length, 3);
  assert.ok(/Waiter/.test(r[0]));
  assert.ok(/barista/i.test(agentCardSpeakingPromptFallbackItems("coffee at a cafe")[0]));
  assert.ok(/State your answer/i.test(agentCardSpeakingPromptFallbackItems("nothing specific")[0]));
}

// ---- filterAgentCardSpeakingPromptFallbackItems：去通用 + 去同意图重复 ----
assert.deepStrictEqual(
  filterAgentCardSpeakingPromptFallbackItems(["Ask for the menu", "State your answer in one simple sentence"], []),
  ["Ask for the menu"]
);

// ---- extractAgentCardNumberedPrompts ----
{
  const p = extractAgentCardNumberedPrompts("1. First prompt 2. Second prompt 3. Third prompt");
  assert.strictEqual(p.length, 3);
  assert.strictEqual(p[0], "First prompt");
  assert.deepStrictEqual(extractAgentCardNumberedPrompts("no numbers here"), []);
}

// ---- isAcceptableAgentCardSpeakingPrompt ----
assert.strictEqual(isAcceptableAgentCardSpeakingPrompt("Describe your day", {}), true);
assert.strictEqual(isAcceptableAgentCardSpeakingPrompt("State your answer in one simple sentence", {}), false);

// ---- isAgentCardSpeakingPromptTopicMismatch ----
assert.strictEqual(
  isAgentCardSpeakingPromptTopicMismatch("Talk about the flight delay", { messageText: "topic: my favorite city only prompt" }),
  true
);
assert.strictEqual(
  isAgentCardSpeakingPromptTopicMismatch("Visit a special place in the city", { messageText: "topic: my favorite city only prompt" }),
  false
);

// ---- isAgentCardSpeakingPromptRoleMismatch ----
assert.strictEqual(isAgentCardSpeakingPromptRoleMismatch("anything", { messageText: "hello" }), false);
assert.strictEqual(
  isAgentCardSpeakingPromptRoleMismatch("three customers and the waiter chat", { messageText: "restaurant dialogue with 3 people" }),
  true
);

// ---- buildFallbackSpeakingPromptComponent ----
{
  const comp = buildFallbackSpeakingPromptComponent("topic: travel plans");
  assert.strictEqual(comp.type, "speaking_prompt");
  assert.strictEqual(comp.title, "Speaking Prompt");
  assert.strictEqual(comp.text, "Talk about travel plans in clear English.");
  assert.deepStrictEqual(comp.items, []);
}

// ---- enforceRequestedSpeakingPromptCount ----
{
  const out = enforceRequestedSpeakingPromptCount(
    { text: "1. Talk about A 2. Talk about B 3. Talk about C" }, 3, {}
  );
  assert.strictEqual(out.items.length, 3);
  assert.strictEqual(out.text, "");
  const same = enforceRequestedSpeakingPromptCount({ text: "x" }, 0, {});
  assert.strictEqual(same.text, "x"); // requestedCount=0 → 原样
}

// ---- agentCardSpeakingPromptScopedFallbackItems ----
{
  const r = agentCardSpeakingPromptScopedFallbackItems({ messageText: "restaurant dialogue with 3 people and a waiter and menu" });
  assert.strictEqual(r.length, 3);
  assert.ok(/Waiter/.test(r[0]));
  assert.deepStrictEqual(agentCardSpeakingPromptScopedFallbackItems({ messageText: "hello" }), []);
}

// ---- enforceSpeakingPromptComponentScope ----
{
  const out = enforceSpeakingPromptComponentScope(
    { type: "speaking_prompt", text: "old text" }, { promptOnly: true, messageText: "topic: travel only prompt" }
  );
  assert.strictEqual(out.text, "Talk about travel.");
  assert.deepStrictEqual(out.items, []);
  const passthrough = enforceSpeakingPromptComponentScope({ type: "cloze" }, { promptOnly: true });
  assert.strictEqual(passthrough.type, "cloze");
}

console.log("speakingPrompts.test.js passed");
