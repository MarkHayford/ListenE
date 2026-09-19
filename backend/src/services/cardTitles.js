// 各类练习卡片的标题/标签生成，以及「无音频」场景下的可见文本中和（去掉提示听/看原文等
// 与当前卡片无关的句子）。一组内聚的纯文本逻辑。历史上内联在 mimoText.js（1 万行），
// 现抽成独立模块——大文件拆分的又一刀。

const { compactAgentCardText } = require("./agentCardText");

function normalizeNoAudioShortAnswerCardTitle(value = "") {
  const cleaned = neutralizeNoAudioCueText(value, "");
  if (!cleaned) return "Typing Practice";
  if (/^\s*(?:typing|input)\s*(?:practice|card)?\s*$/i.test(cleaned)) return "Typing Practice";
  if (/^\s*short\s+answer\s+practice\s*$/i.test(cleaned)) return "Typing Practice";
  return cleaned
    .replace(/\btyping\s+practice\b/ig, "Typing Practice")
    .replace(/\bshort\s+answer\s+practice\b/ig, "Typing Practice")
    .trim();
}

function agentCardTitleForPresentComponents(present = new Set()) {
  if (present.has("register")) return "Register Practice";
  if (present.has("ethics")) return "Ethics Reflection";
  if (present.has("debate")) return "Debate Practice";
  if (present.has("error_hunt")) return "Error Hunt";
  if (present.has("storytelling")) return "Storytelling";
  if (present.has("paraphrase")) return "Paraphrase Practice";
  if (present.has("mistake_pattern")) return "Mistake Pattern Practice";
  if (present.has("writing_outline")) return "Writing Outline";
  if (present.has("speaking_prompt")) return "Speaking Prompt";
  if (present.has("rubric")) return "Rubric";
  if (present.has("scenario")) return "Scenario Practice";
  if (present.has("question_set")) return "Question Set";
  if (present.has("summary") && present.has("question_set")) return "Reading Practice";
  if (present.has("cloze")) return "Cloze Practice";
  if (present.has("short_answer")) return "Short Answer Practice";
  if (present.has("sentence_builder")) return "Sentence Builder";
  if (present.has("ordering")) return "Ordering Practice";
  if (present.has("translation")) return "Translation Practice";
  if (present.has("compare")) return "Compare Practice";
  if (present.has("pronunciation")) return "Pronunciation Practice";
  if (present.has("minimal_pair")) return "Minimal Pair Practice";
  if (present.has("listening_cue")) return "Listening Cue Practice";
  if (present.has("word_family")) return "Word Family";
  if (present.has("vocabulary")) return "Vocabulary Practice";
  if (present.has("phrase")) return "Phrase Practice";
  if (present.has("grammar")) return "Grammar Practice";
  if (present.has("correction")) return "Correction Practice";
  return "Practice Card";
}

function questionSetCardTitleForRequest(message = "") {
  const text = String(message || "").toLowerCase();
  if (/grammar|语法|冠词|介词|时态/.test(text)) return "语法选择题";
  if (/vocab|vocabulary|word|meaning|词汇|单词|含义/.test(text)) return "词义选择题";
  if (/cloze|blank|fill|完形|填空/.test(text)) return "填空选择题";
  if (/translation|translate|翻译/.test(text)) return "翻译选择题";
  if (/minimal|辨音|音近词|词对/.test(text)) return "辨音选择题";
  if (/reading|阅读/.test(text)) return "阅读理解题";
  return "练习题组";
}

function readingCardTitleForRequest(message = "") {
  const topic = inferFallbackReadingTopic(message);
  if (topic === "school_plastic_waste") return "Reading Comprehension: School Plastic Waste";
  if (topic === "lost_wallet") return "Reading Comprehension: Lost Wallet";
  if (topic === "lost_backpack") return "Reading Comprehension: Lost Backpack";
  if (topic === "online_shopping") return "Reading Comprehension: Online Shopping";
  return "Reading Comprehension";
}

function normalizeNoAudioShortAnswerTitle(value = "") {
  const cleaned = neutralizeNoAudioCueText(value, "");
  if (!cleaned) return "Type the Sentence";
  if (/^\s*(?:typing|input)\s*(?:prompt|\d+)?\s*$/i.test(cleaned)) return "Type the Sentence";
  return cleaned
    .replace(/\btyping\s+practice\b/ig, "Typing Practice")
    .replace(/\btyping\s+prompt\b/ig, "Type the Sentence")
    .replace(/\btype\s+the\s+sentence\b/i, "Type the Sentence")
    .replace(/\binput\s+answer\b/i, "Input Answer")
    .trim();
}

function neutralizeMinimalPairNoPronunciationTitle(value = "") {
  const title = compactAgentCardText(value, 40);
  if (!title) return title;
  if (
    isMinimalPairPronunciationHint(title) ||
    /\b(?:sounds?|phonemes?|phonetic|pronunciation|listen|hear|play|audio)\b/i.test(title) ||
    /音素|辨音|发音|播放|收听|听/.test(title)
  ) {
    return "Minimal Pairs";
  }
  return title;
}

function isMinimalPairPronunciationHint(value = "") {
  const text = String(value || "").trim();
  if (!text) return false;
  return /[\/ːɪʊəɛæɑɔɒʌθðʃʒŋˈˌ]/.test(text) ||
    /\b(?:ipa|phoneme|phonetic|pronunciation|vowel|consonant|mouth|tongue|stress|length|sound|listen|hear|play)\b/i.test(text) ||
    /音标|发音|元音|辅音|口型|舌位|重音|音长|播放|收听|听/.test(text);
}

function neutralizeNoAudioCueText(value = "", fallback = "") {
  let text = String(value || "").trim();
  const original = text;
  if (!text) return fallback;
  const hasAudioCue = /\b(?:hear|heard|listen|listening|play|playing|audio|dictation|repeat)\b|(?:tap|click)\s+to\b|(?:听写|听到|听见|播放|收听|音频|跟读|复述)/i.test(text);
  if (!hasAudioCue) return text;
  text = text
    .replace(/\btext\s+only\s*,?\s*no\s+audio\b/gi, "Text only")
    .replace(/\b(?:no|without)\s+audio\b/gi, "")
    .replace(/听写/g, "输入答案")
    .replace(/\bshort\s+answer\s+dictation\b/gi, "typing")
    .replace(/\bdictation\b/gi, "typing")
    .replace(/\b(?:you\s+)?(?:will\s+)?hear\b/gi, "")
    .replace(/\bheard\b/gi, "")
    .replace(/\blistening\b/gi, "")
    .replace(/\blisten\b/gi, "")
    .replace(/\bplaying\b/gi, "")
    .replace(/\bplay\b/gi, "")
    .replace(/\brepeat\b/gi, "")
    .replace(/\b(?:tap|click)\s+to\b/gi, "")
    .replace(/\baudio\b/gi, "")
    .replace(/听到|听见|播放|收听|音频|跟读|复述/g, "")
    .replace(/\bshort answer\s+short answer\b/gi, "short answer")
    .replace(/\btyping\s+typing\b/gi, "typing")
    .replace(/\s+([,.;:!?])/g, "$1")
    .replace(/^\s*[,，;；]\s*/, "")
    .replace(/\b(?:and|then)\s+(?:the\s+)?(?:target\s+)?sound\.?$/i, "")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/[:：]\s*$/, "")
    .replace(/[-–—]\s*$/, "")
    .trim();
  if (isDanglingNoAudioShortAnswerPrompt(original, text)) {
    return /sentence|句子/i.test(original) ? "Type this sentence." : "Type the answer.";
  }
  if (isDanglingNoAudioPronunciationPrompt(original, text)) {
    return /mouth|tongue|口型|舌位/i.test(original) ? "Focus on the mouth and tongue position." : fallback;
  }
  if (isDanglingNoAudioCueText(original, text)) return fallback;
  return text || fallback;
}

function isDanglingNoAudioPronunciationPrompt(original = "", cleaned = "") {
  const source = String(original || "");
  if (!/\b(?:pronunciation|sound|listen|repeat|play|hear)\b|发音|音标|口型|播放|收听|跟读|复述/i.test(source)) return false;
  const text = String(cleaned || "").trim().toLowerCase();
  if (!text) return true;
  if (/^(?:and|then|to|for|the)\b/.test(text)) return true;
  if (/^(?:target\s+)?sound\.?$/.test(text)) return true;
  if (/\b(?:and|then|to|for|the)\s*$/.test(text)) return true;
  return false;
}

function isDanglingNoAudioCueText(original = "", cleaned = "") {
  const source = String(original || "");
  const text = String(cleaned || "").trim().toLowerCase();
  if (!/\b(?:listen|listening|play|audio|hear|repeat)\b|播放|收听|音频|跟读|复述/i.test(source)) return false;
  return /^(?:to\s+)?(?:the\s+)?(?:dialogue|conversation|audio|material|recording)(?:\s+and\s+answer(?:\s+the)?\s+questions?)?\.?$/i.test(text);
}

function isDanglingNoAudioShortAnswerPrompt(original = "", cleaned = "") {
  const source = String(original || "");
  const text = String(cleaned || "").trim().toLowerCase();
  if (!/(?:short answer|sentence|answer|type|输入|答案|句子)/i.test(source)) return false;
  if (/^(?:to|for|at|from|with|and|or|then|below)\b/.test(text)) return true;
  if (/\b(?:to|for|at|from|with|and|or)\s*$/.test(text)) return true;
  if (/^(?:the\s+)?sentence\s+and\s+type\b/.test(text)) return true;
  return false;
}

function inferFallbackReadingTopic(text = "") {
  const value = String(text || "").toLowerCase();
  if (/community\s+garden|garden|heavy\s+rain|rainstorm|雨后|暴雨|社区花园|花园/.test(value)) return "community_garden_rain";
  if (/plastic|waste|recycl|reuse|bottle|lunch\s+box/.test(value) && /school|class|student|campus/.test(value)) return "school_plastic_waste";
  if (/lost\s+wallet|wallet|钱包/.test(value)) return "lost_wallet";
  if (/lost\s+backpack|backpack|背包/.test(value)) return "lost_backpack";
  if (/online\s+shopping|shopping|e-?commerce|retail/.test(value)) return "online_shopping";
  if (/travel|trip|airport|hotel|station|ticket/.test(value)) return "travel";
  if (/school|class|teacher|homework|student/.test(value)) return "school";
  return "general";
}

function cetWordBankClozeTitle(optionCount = 15, blankCount = 10) {
  const safeOptionCount = Math.max(1, Number(optionCount) || 15);
  const safeBlankCount = Math.max(1, Number(blankCount) || 10);
  return `${agentChineseCountLabel(safeOptionCount)}选${agentChineseCountLabel(safeBlankCount)}`;
}

function agentChineseCountLabel(count = 0) {
  const value = Math.max(0, Number(count) || 0);
  const digits = ["零", "一", "二", "三", "四", "五", "六", "七", "八", "九"];
  if (value <= 10) return value === 10 ? "十" : digits[value];
  if (value < 20) return `十${value % 10 === 0 ? "" : digits[value % 10]}`;
  if (value < 100) {
    const tens = Math.floor(value / 10);
    const ones = value % 10;
    return `${digits[tens]}十${ones === 0 ? "" : digits[ones]}`;
  }
  return String(value);
}

module.exports = {
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
  agentChineseCountLabel,
};
