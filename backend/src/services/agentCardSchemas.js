"use strict";

// 静态卡片数据（纯数据，从 mimoText.js 抽出，逐字保持一致）：
// - FOCUSED_AGENT_CARD_COMPONENT_SPEC：各组件紧凑字段规范模板，聚焦重生成时给模型当模板。
// - AGENT_CARD_COMPONENT_KEYWORDS：组件类型 → 关键词正则目录，用于从用户文本推断组件。

const FOCUSED_AGENT_CARD_COMPONENT_SPEC = {
  vocabulary: '{"type":"vocabulary","items":["word - 中文释义"]}（每项 "英文 - 中文释义"）',
  phrase: '{"type":"phrase","items":["phrase - 用法/中文"]}',
  cloze: '{"type":"cloze","text":"句子用 ___ 表示空","options":["opt1","opt2","opt3","opt4"],"answer":"正确答案","explanation":"简短"}（多空时 text 含多个 ___，answer 用 | 顺序连接，options 为词库）',
  question_set: '{"type":"question_set","questions":[{"questionText":"...","options":["A","B","C","D"],"answer":"正确选项原文","explanation":"简短"}]}',
  reading: '{"type":"reading","title":"文章标题","text":"一段完整短文，可多段用换行分隔","questions":[{"questionText":"...","options":["A","B","C","D"],"answer":"正确选项原文","explanation":"简短","tag":"主旨"}]}（专门的阅读理解：text 放短文，title 放标题，questions 放基于短文的选择题，每题 tag 用题型标签：主旨/细节/推断/词义/标题/态度/目的）',
  gap_match: '{"type":"gap_match","text":"一段含【1】【2】…空格标记的短文","options":["候选句A","候选句B","候选句C","候选句D","候选句E","候选句F","候选句G"],"answer":"C | A | F | B | E","explanation":"简短线索"}（七选五/选句填空：text 放含【数字】空格的短文，options 放比空格多 2 个的候选整句，answer 用大写字母按空位顺序连接，每个字母对应 options 第几个：A=第1句）',
  chart_writing: '{"type":"chart_writing","title":"图表标题","text":"写作要求(如用150词描述图表趋势)","answer":"bar","tokens":["2020","2021"],"items":["Coffee: 40, 55","Tea: 30, 35"]}（图表作文：title 图表标题，text 写作要求，answer 图表类型 bar/line/pie，tokens 数据系列名(单系列可省略)，items 每个类别一行 "标签: 值1, 值2" 与 tokens 顺序一致）',
  short_answer: '{"type":"short_answer","text":"题干/输入要求","answer":"完整正确答案","explanation":"简短提示"}',
  sentence_builder: '{"type":"sentence_builder","items":["乱序","词块"],"answer":"完整正确句子","explanation":"规则"}',
  ordering: '{"type":"ordering","items":["乱序整句1","乱序整句2"],"answer":"句1 | 句2","explanation":"线索"}',
  speaking_prompt: '{"type":"speaking_prompt","text":"一个口语话题/任务"}',
  minimal_pair: '{"type":"minimal_pair","pairs":[{"left":"ship","right":"sheep","hint":"/ɪ/ vs /iː/"}]}',
  pronunciation: '{"type":"pronunciation","text":"发音/连读/重音提示","items":["提示"]}',
  grammar: '{"type":"grammar","text":"规则说明","items":["要点"]}',
  translation: '{"type":"translation","items":["English | 中文"]}',
  compare: '{"type":"compare","items":["say - 说出内容","tell - 告诉某人"]}',
  writing_outline: '{"type":"writing_outline","steps":[{"label":"claim","text":"..."}]}',
  rubric: '{"type":"rubric","criteria":[{"label":"Content","text":"评分说明"},{"label":"Organization","text":"评分说明"},{"label":"Language","text":"评分说明"}]}（评分量规：criteria 给 3-5 个维度，每项必须有非空 label（简短英文维度名，如 Content/Organization/Language/Accuracy/Format）和 text（该维度的具体评分说明，可中文）；只用 criteria，不要用 items）',
  correction: '{"type":"correction","items":["I have went. -> I have gone."]}',
  word_family: '{"type":"word_family","tokens":["decide","decision","decisive"]}',
  listening_cue: '{"type":"listening_cue","items":["however - 转折"]}',
  scenario: '{"type":"scenario","items":["office - Could you clarify that?"]}',
  ethics: '{"type":"ethics","text":"一段简短的英文道德两难情境","items":["引导反思的英文问题1","引导反思的英文问题2"]}（用英语呈现道德/价值观思辨：text 放情境，items 放 2-4 个开放式反思问题）',
  debate: '{"type":"debate","text":"Motion: 一个英文辩题","items":["For: 支持要点","Against: 反对要点"],"explanation":"选一方并给两个理由"}（论证表达：text 放辩题，items 放正反双方要点）',
  error_hunt: '{"type":"error_hunt","text":"一段含若干语法/用词错误的英文短文","items":["wrong -> correct","wrong2 -> correct2"],"explanation":"先找错再改"}（篇章级校对：text 放含错短文，items 放每处错误->正确）',
  storytelling: '{"type":"storytelling","text":"一个英文写作情境或开头","items":["use the words: ...","include a twist"],"explanation":"写 3-5 句短故事"}（创意写作：text 放情境，items 放词语/结构要求）',
  paraphrase: '{"type":"paraphrase","text":"一句待改写的英文原句","items":["同义改写版本1","同义改写版本2"],"explanation":"保持原意，改变结构或用词"}（同义改写：text 放原句，items 放 2-3 个保持原意的英文改写版本）',
  register: '{"type":"register","items":["casual: I get it. | formal: I understand."]}',
  summary: '{"type":"summary","title":"短文","text":"一段与题目相关的短文"}'
};

const AGENT_CARD_COMPONENT_KEYWORDS = [
  ["audio", /audio|hear|listen|play|音频|播放|收听|听/i],
  ["question_preview", /question|quiz|题目|答题|做题|习题/i],
  ["transcript", /transcript|原文|文本/i],
  ["sentence_transcript", /sentence[_\s-]*transcript|sentence[_\s-]*by[_\s-]*sentence|逐句|精听|点播/i],
  ["feedback", /feedback|反馈|核对|对错/i],
  ["vocabulary", /vocab|vocabulary|word|词汇|单词/i],
  ["phrase", /\bphrases?\b|collocation|短语|搭配/i],
  ["grammar", /grammar|tense|语法|时态|从句|冠词|介词/i],
  ["translation", /translation|translate|翻译/i],
  ["examples", /example|例句/i],
  ["pronunciation", /pronunciation|发音|音标|连读|重音/i],
  ["cloze", /cloze|blank|填空/i],
  ["short_answer", /dictation|typed\s*answer|type\s+the\s+sentence|free\s*response|short\s*answer|text\s*input|input\s*answer|默写|输入答案|打字|短答|听写/i],
  ["sentence_builder", /sentence\s*builder|word\s*order|reorder\s+words?|组句|连词成句|单词顺序|词语排序|词块排序/i],
  ["ordering", /ordering|sequenc|paragraph\s*order|logical\s*order|logic\s*order|段落排序|逻辑排序|篇章排序|句子排序|按逻辑顺序|按事件顺序|排顺序/i],
  ["question_set", /question\s*set|multiple\s*choice|quiz|题组|选择题|题目/i],
  ["reading", /reading\s*comprehension|reading\s*passage|reading\s*quiz|comprehension|阅读理解|短文阅读|阅读题|阅读练习/i],
  ["gap_match", /gap\s*match|seven\s*choose\s*five|sentence\s*restoration|sentence\s*insertion|choose\s*the\s*sentence|七选五|选句填空|句子还原|选句还原|还原句子/i],
  ["chart_writing", /chart\s*writing|graph\s*writing|chart\s*essay|task\s*1\s*writing|图表作文|看图作文|图表描述|图表写作/i],
  ["compare", /compare|difference|区别|对比|辨析| vs /i],
  ["correction", /correction|correct|rewrite|纠错|改错/i],
  ["rubric", /rubric|criteria|评分|标准/i],
  ["listening_cue", /listening\s*cue|signal\s*word|听力信号|转折信号|因果信号/i],
  ["minimal_pair", /minimal\s*pair|音素|辨音/i],
  ["word_family", /word\s*family|词族|派生|derivation|morphology/i],
  ["scenario", /scenario|situational|场景|情景/i],
  ["register", /register|casual|formal|语气|正式|非正式/i],
  ["speaking_prompt", /speaking|\boral\b|口语/i],
  ["writing_outline", /writing|outline|essay|写作|提纲/i],
  ["mistake_pattern", /mistake|error\s*pattern|错因|错题/i],
  ["ethics", /ethics?|moral|moral\s*dilemma|道德|伦理|品德|价值观|美德|思辨/i],
  ["debate", /debate|argument|argumentation|\bargue\b|pros\s*and\s*cons|for\s+and\s+against|辩论|辩题|正反方|正方|反方|利弊/i],
  ["error_hunt", /error\s*hunt|proofread|spot\s+the\s+(?:mistake|error)|find\s+the\s+(?:mistake|error)|校对|找错|挑错|找出.{0,4}错误|改错短文/i],
  ["storytelling", /storytelling|story\s*prompt|\bstory\b|\bstories\b|narrat(?:ive|ion)|creative\s+writing|故事|编故事|讲故事|故事接龙/i],
  ["paraphrase", /paraphrase|paraphrasing|rephrase|reword|restate|换种说法|换个说法|同义改写|改写句子|句子改写/i]
];

module.exports = {
  FOCUSED_AGENT_CARD_COMPONENT_SPEC,
  AGENT_CARD_COMPONENT_KEYWORDS
};
