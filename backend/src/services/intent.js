// 「模糊/显式」练习意图识别：从用户中文/英文消息里判断要什么练习（写作/口语/场景/阅读/选词填空/
// 长篇），以及场景对话的人数/提示条数。一组保守正则 + 纯谓词，集中在此便于调参与单测。
// 历史上内联在 mimoText.js（1 万行），现抽成独立模块——大文件拆分的又一刀(B: 意图识别)。
// 仅依赖 listeningGenerate 的 parseCountToken（无环）；mimoText 改为从这里 require 这些谓词。

const { parseCountToken } = require("./listeningGenerate");

const FUZZY_WRITING_PRACTICE_PATTERN = /writing|outline|essay|composition|email|写作|提纲|作文|作文题|作文思路|写作题目|写作思路|邮件/i;
const FUZZY_SPEAKING_PRACTICE_PATTERN = /speaking|\boral\b|口语|口语题|口语练习|口语提示/i;
const FUZZY_SCENARIO_PRACTICE_PATTERN = /scenario|situational|dialogue|conversation|role.?play|场景|情景|对话|角色扮演|模拟|酒店入住|办理入住|餐厅点餐|餐厅预订|面试/i;
const FUZZY_READING_PRACTICE_PATTERN = /reading|阅读|阅读理解|仔细阅读|信息匹配|短文|文章|篇章|passage|article|text|定位/i;
const CET_WORD_BANK_CLOZE_PATTERN = /选词填空|选填|[0-9一二两俩三四五六七八九十]{1,3}\s*选\s*[0-9一二两俩三四五六七八九十]{1,3}|四级选词|六级选词|四级.{0,12}(?:选填|选词填空|[0-9一二两俩三四五六七八九十]{1,3}\s*选\s*[0-9一二两俩三四五六七八九十]{1,3})|六级.{0,12}(?:选填|选词填空|[0-9一二两俩三四五六七八九十]{1,3}\s*选\s*[0-9一二两俩三四五六七八九十]{1,3})|cet\s*[46].{0,40}(?:选填|word\s*bank)|word\s*bank|cloze.{0,40}cet\s*[46]/i;
const LONG_FORM_CARD_REQUEST_PATTERN = /长篇|长一点|长点|再长|更长|加长|详细|详尽|篇幅|大段|完整版|long|lengthy|detailed|in[-\s]?depth|\b\d{2,4}\s*(?:词|字|words?)\b/i;

function isLongFormCardRequest(message = "") {
  return LONG_FORM_CARD_REQUEST_PATTERN.test(String(message || ""));
}

function isWordBankClozePracticeCardRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  const wordBankClozeHit = CET_WORD_BANK_CLOZE_PATTERN.test(text) ||
    /cloze|fill[-\s]*in[-\s]*the[-\s]*blank|fill[-\s]*in[-\s]*blank/i.test(text);
  if (!wordBankClozeHit) return false;
  const explicitListeningHit = hasAffirmativeListeningMaterialCue(text);
  return !explicitListeningHit;
}

function isCetWordBankClozeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (!CET_WORD_BANK_CLOZE_PATTERN.test(text)) return false;
  return !hasAffirmativeListeningMaterialCue(text);
}

function hasAffirmativeListeningMaterialCue(message = "") {
  const text = String(message || "");
  if (isListeningMaterialNegated(text)) return false;
  return /听力|听音频|音频|listening|audio|听一段|精听|复听|逐句听/i.test(text);
}

function isListeningMaterialNegated(message = "") {
  const text = String(message || "");
  return /不要.{0,10}(?:听力|听音频|录音|音频|audio|listening)|不需要.{0,10}(?:听力|听音频|录音|音频|audio|listening)|别(?:要|加|放|做|出|练).{0,10}(?:听力|听音频|录音|音频|audio|listening)|不是.{0,8}(?:听力|听音频|录音|音频)|非(?:听力|音频)|不(?:练|做|要).{0,8}(?:听力|听音频|录音|音频)|无(?:听力|录音|音频)|no\s+(?:audio|listening)|without\s+(?:audio|listening)/i.test(text);
}

function isFuzzyWritingPracticeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  return FUZZY_WRITING_PRACTICE_PATTERN.test(text) &&
    /练|练习|题|题目|思路|提纲|outline|prompt|topic|作文|写作|生成|给我|帮我|来|出|不会写|没思路|卡壳/i.test(text);
}

function isGrammarOrMistakePatternTopicRequest(text = "") {
  const value = String(text || "").toLowerCase();
  return /mistake\s+patterns?|error\s+patterns?|grammar|articles?|prepositions?|tense|correction/.test(value) &&
    !/\breading\b|comprehension|passage|short\s+text|short\s+article/.test(value);
}

function isFuzzyReadingPracticeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (isGrammarOrMistakePatternTopicRequest(text)) return false;
  if (!FUZZY_READING_PRACTICE_PATTERN.test(text) && !/主旨|中心|推断|暗示|定位/.test(text)) return false;
  return /练|练习|题|题目|道|配|短文|文章|篇|passage|article|questions?|quiz|mcq|comprehension|理解|匹配|仔细阅读|来|给我|生成|出|更多|找不到|看不明白|看不懂|不行|不太行|不太会|总蒙|老错|救|weak|poor|not\s+good|struggle|improve|提高/i.test(text);
}

function isFuzzySpeakingPracticeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (/no\s+(?:writing\s*\/\s*)?speaking|without\s+(?:writing\s*\/\s*)?speaking|no\s+oral|without\s+oral/i.test(text)) return false;
  return FUZZY_SPEAKING_PRACTICE_PATTERN.test(text) &&
    /题|题目|prompt|练|练习|practice|drill|模拟|关于|about|topic|只要|给我|来|生成|出/i.test(text);
}

function isFuzzyScenarioPracticeRequest(message = "") {
  const text = String(message || "");
  if (!text.trim()) return false;
  if (!FUZZY_SCENARIO_PRACTICE_PATTERN.test(text)) return false;
  return /卡|练|练习|题|题目|prompt|句|轮|人|角色|speaker|people|对话|模拟|场景|情景|别加|只要|给我|来|生成|出|role.?play|conversation|dialogue|airport|hotel|restaurant|interview|meeting|customer\s+service/i.test(text);
}

function inferRequestedDialogueParticipantCount(message = "") {
  const text = String(message || "").toLowerCase();
  const numberToken = "([0-9]{1,2}|[一二两兩俩倆三四五六七八九十]{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)";
  const patterns = [
    new RegExp(`${numberToken}\\s*-?\\s*(?:person|people|participant|participants|speaker|speakers|character|characters)`, "i"),
    new RegExp(`${numberToken}\\s*(?:人|位|个角色|個角色|角色|说话人|說話人)`, "i"),
    new RegExp(`(?:person|people|participant|participants|speaker|speakers|character|characters|人数|人數|角色数|角色數)[^0-9一二两兩三四五六七八九十a-z]{0,8}${numberToken}`, "i")
  ];
  for (const pattern of patterns) {
    const match = pattern.exec(text);
    const count = parseCountToken(match?.[1] || "");
    if (count && count >= 1 && count <= 12) return count;
  }
  return null;
}

function inferExplicitScenarioConstraint(message = "") {
  const text = String(message || "").toLowerCase();
  if (!FUZZY_SCENARIO_PRACTICE_PATTERN.test(text)) return null;
  const numberToken = "([0-9]{1,2}|[一二两兩俩倆三四五六七八九十]{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)";
  const promptPatterns = [
    new RegExp(`${numberToken}\\s+(?:role\\s*play\\s*)?(?:prompts?|turns?|lines?|tasks?|cues?)\\b`, "i"),
    new RegExp(`${numberToken}\\s*(?:个|個|条|條|句|轮|輪)?\\s*(?:提示|任务|任務|轮次|輪次|台词|臺詞|句子|句|prompts?|turns?|lines?|tasks?|cues?)`, "i"),
    new RegExp(`(?:prompts?|turns?|lines?|tasks?|cues?|提示|任务|任務|轮次|輪次|台词|臺詞|句子|句)[^0-9一二两兩俩倆三四五六七八九十a-z]{0,12}${numberToken}`, "i")
  ];
  let promptCount = null;
  for (const pattern of promptPatterns) {
    const match = pattern.exec(text);
    promptCount = parseCountToken(match?.[1] || "");
    if (promptCount && promptCount >= 1 && promptCount <= 12) break;
    promptCount = null;
  }
  return {
    participantCount: inferRequestedDialogueParticipantCount(message),
    promptCount
  };
}

module.exports = {
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
  inferExplicitScenarioConstraint,
};
