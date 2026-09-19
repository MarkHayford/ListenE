"use strict";

// 意图识别正则常量：从 mimoText.js 抽出的纯正则字面量（无任何依赖）。
// 正则内容与原实现逐字保持一致；改这里等于改全局意图判定，务必谨慎。

const AGENT_VOCABULARY_INTENT_PATTERN = /vocab|vocabulary|glossary|terms?|terminology|words?|academic\s+words?|travel\s+words?|词汇|单词|生词|关键词|生活词|高频词|核心词|术语|词表|易漏听的词/i;
const AGENT_PHRASE_INTENT_PATTERN = /\bphrases?\b|phrasal\s+verbs?|collocation|collocations|chunks?|lexical\s+chunks?|idioms?|interview\s+english|interview\s+(?:phrases?|expressions?)|短语|搭配|动词短语|常用表达|自然表达|语块|固定搭配|习语|惯用语|面试英语|面试表达/i;
const AGENT_LISTENING_CUE_INTENT_PATTERN = /listening\s*cues?|signal\s*words?|cue\s*words?|discourse\s*markers?|connectors?|linking\s*words?|听力信号词|听力信号|信号词|连接词|衔接词|转折词|因果词|让步信号/i;
const AGENT_MINIMAL_PAIR_INTENT_PATTERN = /minimal[-\s]*pairs?|sound[-\s]*pairs?|sound\s+contrast|sound\s+discrimination|listening\s+discrimination|音近词|音素对照|最小对|辨音|听辨|听音辨词|词对|容易听错|易听错|音近|发音相近|分不清|易混音|长短音|ship\s*(?:\/|\s+|和|and)\s*sheep|live\s*(?:\/|\s+|和|and)\s*leave|\/ɪ\/|\/iː\/|\br\s*(?:和|and|\/)\s*l\b|\bl\s*(?:和|and|\/)\s*r\b/i;
const AGENT_WORD_FAMILY_INTENT_PATTERN = /word\s*family|derivatives?|derivations?|related\s+forms?|family\s*tokens?|词族|派生词|派生|词根|词缀|词根词缀/i;
const AGENT_REGISTER_INTENT_PATTERN = /register|register\s*shift|casual|formal|polite|语气转换|语气改写|(?<!虚拟|祈使|陈述|疑问|感叹)语气|正式|非正式|礼貌|随意|礼貌一点|自然点/i;
const AGENT_GRAMMAR_INTENT_PATTERN = /grammar|tense|article|articles|preposition|语法|时态|从句|冠词|介词|语法规则/i;
const AGENT_TRANSLATION_INTENT_PATTERN = /translation|translate|翻译|翻成|译文|英译中|中译英|译成/i;
const AGENT_COMPARE_INTENT_PATTERN = /compare|difference|区别|对比|辨析| vs |和.+区别|跟.+区别|make\/do|make\s+do|make.*do|do.*make/i;
const AGENT_PRONUNCIATION_INTENT_PATTERN = /pronunciation|发音|音标|连读|重音|final\s*-?ed|\b-ed\b/i;
const AGENT_CORRECTION_INTENT_PATTERN = /correction|correct|rewrite|纠错|改错|改正|修正|纠正/i;
const AGENT_ETHICS_INTENT_PATTERN = /ethics?|moral(?:ity)?|moral\s*dilemma|ethical\s*dilemma|character\s*education|道德|伦理|品德|价值观|美德|道德两难|道德困境|道德情境|道德思辨|品格教育/i;
const AGENT_DEBATE_INTENT_PATTERN = /\bdebate\b|\bargue\b|argument(?:ation)?|pros\s*and\s*cons|for\s+and\s+against|take\s+a\s+(?:side|stance)|辩论|辩题|正反方|正方观点|反方观点|赞成还是反对|利弊分析/i;
const AGENT_ERROR_HUNT_INTENT_PATTERN = /error\s*hunt|proofread(?:ing)?|spot\s+the\s+(?:mistakes?|errors?)|find\s+the\s+(?:mistakes?|errors?)|校对|找错|挑错|找出.{0,6}错误|改错短文|纠错短文|短文改错|改错题|改错练习|英语改错|挑出.{0,6}错误/i;
const AGENT_STORYTELLING_INTENT_PATTERN = /storytelling|story\s*prompt|\bstory\b|\bstories\b|narrat(?:ive|ion)|creative\s+writing|故事/i;
const AGENT_PARAPHRASE_INTENT_PATTERN = /paraphrase|paraphrasing|rephrase|reword(?:ing)?|restate|say\s+it\s+(?:differently|another\s+way)|in\s+other\s+words|换种说法|换个说法|换一种说法|换种表达|换个表达|同义改写|改写句子|句子改写|说法改写|换种方式表达/i;
const AGENT_WEAKNESS_PRACTICE_CUE_PATTERN = /老错|总错|总忘|总看不明白|看不明白|看不懂|记不住|背不住|搞混|分不清|听不懂|听不明白|听不出|听不出来|老漏|不会|不行|不太行|不太会|提高|提分|救一下|救命|卡壳|总蒙|找不到|没思路|weak|poor|not\s+good|struggle/i;
const AGENT_UNSPECIFIED_WORD_OR_SENTENCE_PATTERN = /^(?:这个|这|那个|那)?(?:词|单词).*(?:怎么用|用法|意思)|^(?:这个|这|那个|那)?(?:句子|这句话|长难句).*(?:不会|看不懂|什么意思|分析|拆)/i;

module.exports = {
  AGENT_VOCABULARY_INTENT_PATTERN,
  AGENT_PHRASE_INTENT_PATTERN,
  AGENT_LISTENING_CUE_INTENT_PATTERN,
  AGENT_MINIMAL_PAIR_INTENT_PATTERN,
  AGENT_WORD_FAMILY_INTENT_PATTERN,
  AGENT_REGISTER_INTENT_PATTERN,
  AGENT_GRAMMAR_INTENT_PATTERN,
  AGENT_TRANSLATION_INTENT_PATTERN,
  AGENT_COMPARE_INTENT_PATTERN,
  AGENT_PRONUNCIATION_INTENT_PATTERN,
  AGENT_CORRECTION_INTENT_PATTERN,
  AGENT_ETHICS_INTENT_PATTERN,
  AGENT_DEBATE_INTENT_PATTERN,
  AGENT_ERROR_HUNT_INTENT_PATTERN,
  AGENT_STORYTELLING_INTENT_PATTERN,
  AGENT_PARAPHRASE_INTENT_PATTERN,
  AGENT_WEAKNESS_PRACTICE_CUE_PATTERN,
  AGENT_UNSPECIFIED_WORD_OR_SENTENCE_PATTERN
};
