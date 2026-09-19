const assert = require("assert");
const {
  normalizeNoAudioShortAnswerCardTitle,
  agentCardTitleForPresentComponents,
  questionSetCardTitleForRequest,
  readingCardTitleForRequest,
  normalizeNoAudioShortAnswerTitle,
  neutralizeMinimalPairNoPronunciationTitle,
  isMinimalPairPronunciationHint,
  neutralizeNoAudioCueText,
  isDanglingNoAudioPronunciationPrompt,
  isDanglingNoAudioCueText,
  isDanglingNoAudioShortAnswerPrompt,
  inferFallbackReadingTopic,
  cetWordBankClozeTitle,
  agentChineseCountLabel
} = require("../src/services/cardTitles");

// ---- agentCardTitleForPresentComponents：按优先级取标题 ----
assert.strictEqual(agentCardTitleForPresentComponents(new Set(["register"])), "Register Practice");
assert.strictEqual(agentCardTitleForPresentComponents(new Set(["cloze"])), "Cloze Practice");
assert.strictEqual(agentCardTitleForPresentComponents(new Set(["vocabulary"])), "Vocabulary Practice");
assert.strictEqual(agentCardTitleForPresentComponents(new Set(["register", "cloze"])), "Register Practice"); // register 优先
assert.strictEqual(agentCardTitleForPresentComponents(new Set()), "Practice Card");

// ---- questionSetCardTitleForRequest ----
assert.strictEqual(questionSetCardTitleForRequest("grammar quiz"), "语法选择题");
assert.strictEqual(questionSetCardTitleForRequest("vocabulary meaning"), "词义选择题");
assert.strictEqual(questionSetCardTitleForRequest("cloze fill"), "填空选择题");
assert.strictEqual(questionSetCardTitleForRequest("translation"), "翻译选择题");
assert.strictEqual(questionSetCardTitleForRequest("reading"), "阅读理解题");
assert.strictEqual(questionSetCardTitleForRequest("something else"), "练习题组");

// ---- inferFallbackReadingTopic ----
assert.strictEqual(inferFallbackReadingTopic("I lost my wallet yesterday"), "lost_wallet");
assert.strictEqual(inferFallbackReadingTopic("online shopping and e-commerce"), "online_shopping");
assert.strictEqual(inferFallbackReadingTopic("school plastic waste recycling by students"), "school_plastic_waste");
assert.strictEqual(inferFallbackReadingTopic("nothing special"), "general");

// ---- readingCardTitleForRequest ----
assert.strictEqual(readingCardTitleForRequest("a lost wallet story"), "Reading Comprehension: Lost Wallet");
assert.strictEqual(readingCardTitleForRequest("nothing special"), "Reading Comprehension");

// ---- agentChineseCountLabel ----
assert.strictEqual(agentChineseCountLabel(0), "零");
assert.strictEqual(agentChineseCountLabel(5), "五");
assert.strictEqual(agentChineseCountLabel(10), "十");
assert.strictEqual(agentChineseCountLabel(15), "十五");
assert.strictEqual(agentChineseCountLabel(20), "二十");
assert.strictEqual(agentChineseCountLabel(23), "二十三");
assert.strictEqual(agentChineseCountLabel(100), "100");

// ---- cetWordBankClozeTitle ----
assert.strictEqual(cetWordBankClozeTitle(15, 10), "十五选十");
assert.strictEqual(cetWordBankClozeTitle(7, 5), "七选五");
assert.strictEqual(cetWordBankClozeTitle(), "十五选十"); // 默认 15 选 10

// ---- isMinimalPairPronunciationHint ----
assert.strictEqual(isMinimalPairPronunciationHint("ship /ʃɪp/"), true); // 含 IPA
assert.strictEqual(isMinimalPairPronunciationHint("vowel sound practice"), true);
assert.strictEqual(isMinimalPairPronunciationHint("发音对比"), true);
assert.strictEqual(isMinimalPairPronunciationHint("hello world"), false);

// ---- neutralizeMinimalPairNoPronunciationTitle ----
assert.strictEqual(neutralizeMinimalPairNoPronunciationTitle("Pronunciation sounds"), "Minimal Pairs");
assert.strictEqual(neutralizeMinimalPairNoPronunciationTitle("发音对比"), "Minimal Pairs");
assert.strictEqual(neutralizeMinimalPairNoPronunciationTitle("Word Pairs"), "Word Pairs"); // 无发音线索→原样

// ---- neutralizeNoAudioCueText ----
assert.strictEqual(neutralizeNoAudioCueText("Reading Practice", "fb"), "Reading Practice"); // 无音频线索→原样
assert.strictEqual(neutralizeNoAudioCueText("", "fb"), "fb"); // 空→fallback
{
  const r = neutralizeNoAudioCueText("Listen to the audio clip", "fb");
  assert.ok(!/audio/i.test(r) && !/listen/i.test(r)); // 音频线索被剔除
}

// ---- normalizeNoAudioShortAnswerCardTitle / Title ----
assert.strictEqual(normalizeNoAudioShortAnswerCardTitle(""), "Typing Practice");
assert.strictEqual(normalizeNoAudioShortAnswerCardTitle("typing practice"), "Typing Practice");
assert.strictEqual(normalizeNoAudioShortAnswerCardTitle("Grammar Drill"), "Grammar Drill");
assert.strictEqual(normalizeNoAudioShortAnswerTitle(""), "Type the Sentence");
assert.strictEqual(normalizeNoAudioShortAnswerTitle("typing prompt"), "Type the Sentence");

// ---- dangling no-audio 判定 ----
assert.strictEqual(isDanglingNoAudioCueText("listen to the dialogue", "to the dialogue"), true);
assert.strictEqual(isDanglingNoAudioCueText("hello there", "hello there"), false); // 源无音频线索
assert.strictEqual(isDanglingNoAudioShortAnswerPrompt("type the answer here", "and"), true);
assert.strictEqual(isDanglingNoAudioPronunciationPrompt("pronunciation tip", ""), true);

console.log("cardTitles.test.js passed");
