const assert = require("assert");

process.env.MIMO_API_KEY = "test-key";
process.env.MIMO_BASE_URL = "http://mimo.test/v1";

const { __test } = require("../src/services/mimoAgentMedia");
const { normalizeSpeakingAssessment, normalizeWritingAssessment, normalizeRoleplayFeedback, normalizeShadowingSentences, clampScore } = __test;

// clampScore: clamps to 0-100, rounds, fallback on NaN
assert.strictEqual(clampScore(50), 50);
assert.strictEqual(clampScore(120), 100);
assert.strictEqual(clampScore(-5), 0);
assert.strictEqual(clampScore(87.6), 88);
assert.strictEqual(clampScore("not-a-number", 7), 7);

// full object passes through, scores clamped, arrays capped
const full = normalizeSpeakingAssessment(
  {
    transcript: "  I go to school yesterday  ",
    overall: 72,
    scores: { pronunciation: 80, fluency: 65, grammar: 50, vocabulary: 70, content: 999 },
    wpm: 110,
    highlights: ["发音清晰", "语速自然", "用词得体", "额外一条"],
    improvements: ["注意时态"],
    corrections: [
      { from: "I go", to: "I went", note: "过去式" },
      { from: "a apple", to: "an apple", note: "冠词" },
      { from: "", to: "", note: "空的应被过滤" }
    ],
    sampleAnswer: "Yesterday I went to school by bus.",
    comment: "继续加油！"
  },
  { durationMs: 30000 }
);
assert.strictEqual(full.transcript, "I go to school yesterday");
assert.strictEqual(full.overall, 72);
assert.strictEqual(full.scores.content, 100, "content clamped to 100");
assert.strictEqual(full.wpm, 110);
assert.strictEqual(full.highlights.length, 3, "highlights capped at 3");
assert.strictEqual(full.corrections.length, 2, "empty correction filtered");
assert.strictEqual(full.corrections[0].to, "I went");

// overall missing -> averaged from the five scores
const avg = normalizeSpeakingAssessment(
  { scores: { pronunciation: 60, fluency: 60, grammar: 60, vocabulary: 60, content: 80 } },
  {}
);
assert.strictEqual(avg.overall, 64, "overall = round(avg of scores)");

// wpm missing -> derived from transcript words / duration
const derived = normalizeSpeakingAssessment(
  { transcript: "one two three four five six", scores: {} },
  { durationMs: 60000 }
);
assert.strictEqual(derived.wpm, 6, "6 words in 1 minute -> 6 wpm");

// empty input -> safe defaults, no throw
const empty = normalizeSpeakingAssessment(null, {});
assert.strictEqual(empty.transcript, "");
assert.strictEqual(empty.overall, 0);
assert.strictEqual(empty.wpm, 0);
assert.deepStrictEqual(empty.highlights, []);
assert.deepStrictEqual(empty.corrections, []);
assert.deepStrictEqual(empty.pronunciationDetail, { summary: "", words: [] }, "empty -> empty pronunciationDetail");

// pronunciation detail (音素级): summary trimmed, words filtered/capped, issues filtered/capped, score clamped
const pd = normalizeSpeakingAssessment(
  {
    scores: {},
    pronunciationDetail: {
      summary: "  θ 音发成了 s，元音偏短  ",
      words: [
        { word: "think", ipa: "/θɪŋk/", score: 55, issues: [{ phoneme: "/θ/", heard: "/s/", tip: "咬舌尖轻送气" }, { phoneme: "", tip: "" }] },
        { word: "", ipa: "", issues: [] },
        { word: "nostip", ipa: "/x/", score: 200, issues: [] },
        ...Array.from({ length: 10 }, (_, i) => ({ word: `w${i}`, ipa: "/w/", issues: [{ phoneme: "/w/", tip: "圆唇" }] }))
      ]
    }
  },
  {}
).pronunciationDetail;
assert.strictEqual(pd.summary, "θ 音发成了 s，元音偏短", "summary trimmed/collapsed");
assert.strictEqual(pd.words.length, 8, "words capped at 8");
assert.strictEqual(pd.words[0].word, "think");
assert.strictEqual(pd.words[0].issues.length, 1, "empty issue filtered");
assert.strictEqual(pd.words[0].issues[0].heard, "/s/");
assert.strictEqual(pd.words[1].word, "nostip", "word kept when it has ipa even without issues");
assert.strictEqual(pd.words[1].score, 100, "per-word score clamped to 100");
// alias keys (sound/actual/fix) accepted; word with neither issues nor ipa dropped
const pdAlias = normalizeSpeakingAssessment(
  { scores: {}, pronunciation_detail: { words: [{ word: "bad", issues: [{ sound: "/æ/", actual: "/e/", fix: "张大嘴" }] }, { word: "skip" }] } },
  {}
).pronunciationDetail;
assert.strictEqual(pdAlias.words.length, 1, "word without ipa/issues dropped");
assert.strictEqual(pdAlias.words[0].issues[0].phoneme, "/æ/", "sound alias -> phoneme");
assert.strictEqual(pdAlias.words[0].issues[0].heard, "/e/", "actual alias -> heard");
assert.strictEqual(pdAlias.words[0].issues[0].tip, "张大嘴", "fix alias -> tip");

// --- writing assessment ---
const w = normalizeWritingAssessment(
  {
    overall: 78,
    scores: { taskAchievement: 80, coherence: 75, vocabulary: 70, grammar: 120 },
    wordCount: 0,
    highlights: ["覆盖了主要趋势", "段落清晰", "额外一条多余"],
    improvements: ["注意时态一致"],
    corrections: [{ from: "is increase", to: "increased", note: "时态" }, { from: "", to: "", note: "空" }],
    sampleAnswer: "The chart shows...",
    comment: "不错！"
  },
  { wordCount: 152 }
);
assert.strictEqual(w.overall, 78);
assert.strictEqual(w.scores.grammar, 100, "grammar clamped");
assert.strictEqual(w.wordCount, 152, "wordCount falls back to provided count when 0");
assert.strictEqual(w.highlights.length, 3);
assert.strictEqual(w.corrections.length, 1, "empty correction filtered");

// writing overall averages four dimensions when missing; alias keys accepted
const wAvg = normalizeWritingAssessment(
  { scores: { task: 60, organization: 60, lexical: 60, accuracy: 80 } },
  { wordCount: 100 }
);
assert.strictEqual(wAvg.overall, 65, "overall = round(avg of four dims) with alias keys");
assert.strictEqual(wAvg.scores.taskAchievement, 60);
assert.strictEqual(wAvg.scores.grammar, 80);

const wEmpty = normalizeWritingAssessment(null, {});
assert.strictEqual(wEmpty.overall, 0);
assert.strictEqual(wEmpty.wordCount, 0);
assert.deepStrictEqual(wEmpty.corrections, []);

// --- roleplay feedback ---
const rp = normalizeRoleplayFeedback({
  scores: { fluency: 70, grammar: 60, lexical: 80, task: 90 },
  highlights: ["回应及时", "用了完整句", "多余项被裁"],
  improvements: ["可多用连接词"],
  betterLines: [{ from: "I no like", to: "I don't like it" }, { from: "", to: "" }],
  comment: "聊得不错！"
});
assert.strictEqual(rp.scores.vocabulary, 80, "lexical alias -> vocabulary");
assert.strictEqual(rp.scores.taskCompletion, 90, "task alias -> taskCompletion");
assert.strictEqual(rp.overall, 75, "overall = round avg(70,60,80,90)");
assert.strictEqual(rp.highlights.length, 3);
assert.strictEqual(rp.betterLines.length, 1, "empty betterLine filtered");
assert.strictEqual(rp.betterLines[0].to, "I don't like it");
const rpEmpty = normalizeRoleplayFeedback(null);
assert.strictEqual(rpEmpty.overall, 0);
assert.deepStrictEqual(rpEmpty.betterLines, []);

// --- shadowing sentences ---
const sh = normalizeShadowingSentences({
  sentences: [
    { text: "  I   take the bus to work. ", translation: "我坐公交去上班。" },
    "Plain string sentence works too.",
    { text: "", translation: "空的应被过滤" },
    { english: "Alias key english.", chinese: "别名键" }
  ]
});
assert.strictEqual(sh.length, 3, "empty text filtered");
assert.strictEqual(sh[0].text, "I take the bus to work.", "whitespace collapsed");
assert.strictEqual(sh[1].text, "Plain string sentence works too.");
assert.strictEqual(sh[2].text, "Alias key english.", "english/chinese aliases");
assert.deepStrictEqual(normalizeShadowingSentences(null), [], "non-array -> []");

console.log("speakingAssess.test.js passed");
