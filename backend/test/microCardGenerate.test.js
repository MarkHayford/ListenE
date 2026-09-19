const assert = require("assert");
const { validateMicroCard, renderReport } = require("../src/contract/microCardValidate");
const { generateMicroCard, microCardSchemaPrompt, difficultyHintFromBody, detectMicroSkillDimensions } = require("../src/services/microCardGenerate");

(async () => {
  // ---- validateMicroCard ----
  {
    const r = validateMicroCard({
      title: "完形",
      nodes: [
        { type: "passage", text: "I ___ home." },
        { type: "choice", prompt: "空1", options: ["go", "went"], answer: "went" }
      ]
    });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "choice", options: ["a", "b"], answer: "c" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M012"), "answer-not-in-options should report M012");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "nope" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M001"), "unknown node → M001");
    assert.ok(r.issues.some((i) => i.code === "M099"), "all-unknown → empty → M099");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "tokens", text: "She go home." }] });
    assert.ok(r.issues.some((i) => i.code === "M021"), "tokens without correct/errors → M021");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "match", pairs: [{ left: "apple", right: "苹果" }, { left: "banana", right: "香蕉" }] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "match", pairs: [{ left: "a", right: "1" }] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M070"), "match with <2 valid pairs → M070");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "categorize", categories: [{ name: "动词", items: ["run", "eat"] }, { name: "名词", items: ["cat"] }] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "categorize", categories: [{ name: "动词", items: ["run"] }] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M080"), "categorize with <2 categories → M080");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "flashcard", cards: [{ front: "apple", back: "苹果" }, { front: "cat", back: "猫" }] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "flashcard", cards: [{ front: "x" }] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M090"), "flashcard with no valid card → M090");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "dictation", text: "I have finished my homework." }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "dictation" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M100"), "dictation without text → M100");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "audio_choice", audioText: "sheep", options: ["ship", "sheep"], answer: "sheep" }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "audio_choice", options: ["a", "b"], answer: "c" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M110"), "audio_choice without audioText → M110");
    assert.ok(r.issues.some((i) => i.code === "M113"), "audio_choice answer not in options → M113");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "speak_score", text: "Read this sentence aloud." }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "speak_score" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M120"), "speak_score without text/prompts → M120");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "cloze_drag", text: "I ___ to school and ___ homework.", bank: ["went", "did", "go"], answers: ["went", "did"] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "cloze_drag", text: "no blanks here", bank: ["a"], answers: [] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M130"), "cloze_drag without blank → M130");
  }
  {
    // 空位数 ≠ 答案数：会导致端上判分整体错位，必须报错并可回灌。
    const r = validateMicroCard({ nodes: [{ type: "cloze_drag", text: "I ___ to school and ___ homework.", bank: ["went", "did", "go"], answers: ["went"] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M133"), "cloze_drag blanks≠answers → M133");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "roleplay_turn", scenario: "你是咖啡店店员，我来点单。", opening: "Hi! What can I get for you today?" }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "roleplay_turn", opening: "Hi" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M140"), "roleplay_turn without scenario → M140");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "ai_hint", prompt: "翻译：我昨天去了学校。", hints: ["用一般过去时", "go 的过去式是 went"], answer: "I went to school yesterday." }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "ai_hint", hints: [], answer: "x" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M150"), "ai_hint without prompt → M150");
    assert.ok(r.issues.some((i) => i.code === "M151"), "ai_hint without hints → M151");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "highlight_span", prompt: "选出所有动词", text: "I run and she eats.", answers: ["run", "eats"] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "highlight_span", text: "no answers here" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M160"), "highlight_span without prompt → M160");
    assert.ok(r.issues.some((i) => i.code === "M162"), "highlight_span without answers → M162");
  }
  {
    // 幽灵答案：answers 里的词不在 text 中（审计实证的真实坏卡形态：answers 含正文没有的 "is"）
    const r = validateMicroCard({ nodes: [{ type: "highlight_span", prompt: "选出所有动词", text: "She reads books and writes stories.", answers: ["reads", "writes", "is"] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M163" && /'is'/.test(i.message)), "highlight_span ghost answer → M163");
  }
  {
    // 定位与端上同口径：忽略大小写、去首尾标点、支持多词短语（连续出现）
    const r = validateMicroCard({ nodes: [{ type: "highlight_span", prompt: "选出表示地点的短语", text: "We met at the library, near the old bridge.", answers: ["At the library", "old bridge"] }] });
    assert.ok(r.ok, renderReport(r.issues));
    const r2 = validateMicroCard({ nodes: [{ type: "highlight_span", prompt: "选出短语", text: "The quick brown fox jumps.", answers: ["quick fox"] }] });
    assert.ok(!r2.ok);
    assert.ok(r2.issues.some((i) => i.code === "M163"), "非连续的多词短语 → M163");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "timed_challenge", prompt: "2+2=?", options: ["3", "4"], answer: "4", seconds: 15 }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "timed_challenge", prompt: "q", options: ["a", "b"], answer: "c" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M173"), "timed_challenge answer not in options → M173");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "table", title: "动词变化", headers: ["原形", "过去式"], rows: [["go", "went"], ["eat", "ate"]] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "table", headers: [], rows: [] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M180"), "table without headers → M180");
    assert.ok(r.issues.some((i) => i.code === "M181"), "table without rows → M181");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "sentence_diagram", sentence: "She reads books.", labels: ["主语", "谓语", "宾语"], items: [{ text: "She", label: "主语" }, { text: "reads", label: "谓语" }, { text: "books", label: "宾语" }] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "sentence_diagram", items: [{ text: "She", label: "主语" }] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M190"), "sentence_diagram with <2 items → M190");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "word_scramble", word: "beautiful", hint: "美丽的" }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "word_scramble", word: "a" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M201"), "word_scramble with <2 letters → M201");
    const r2 = validateMicroCard({ nodes: [{ type: "word_scramble" }] });
    assert.ok(r2.issues.some((i) => i.code === "M200"), "word_scramble without word → M200");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "true_false", prompt: "判断", statements: [{ text: "The sun rises in the east.", answer: true }, { text: "Cats can fly.", answer: false }] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "true_false", statements: [] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M210"), "true_false without statements → M210");
    const r2 = validateMicroCard({ nodes: [{ type: "true_false", statements: [{ text: "only text, no answer" }] }] });
    assert.ok(r2.issues.some((i) => i.code === "M210"), "true_false statement missing answer → M210");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "fill_table", title: "动词变化", headers: ["原形", "过去式", "过去分词"], rows: [["go", "went", "gone"], ["eat", "ate", "eaten"]], blanks: [[0, 1], [1, 2]] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "fill_table", headers: [], rows: [], blanks: [] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M220"), "fill_table without headers → M220");
    assert.ok(r.issues.some((i) => i.code === "M221"), "fill_table without rows → M221");
    assert.ok(r.issues.some((i) => i.code === "M222"), "fill_table without valid blanks → M222");
    // 挖空坐标指向空单元格/越界 → M222
    const r2 = validateMicroCard({ nodes: [{ type: "fill_table", headers: ["a", "b"], rows: [["x", ""]], blanks: [[0, 1], [5, 5]] }] });
    assert.ok(r2.issues.some((i) => i.code === "M222"), "fill_table blank at empty/out-of-range cell → M222");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "pairs_memory", prompt: "翻牌配对", pairs: [{ left: "apple", right: "苹果" }, { left: "banana", right: "香蕉" }] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "pairs_memory", pairs: [{ left: "apple", right: "苹果" }] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M230"), "pairs_memory with <2 pairs → M230");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "cloze_select", text: "I ___ to school and ___ homework.", blanks: [{ options: ["go", "went", "gone"], answer: "went" }, { options: ["do", "did", "done"], answer: "did" }] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "cloze_select", text: "no blank here", blanks: [{ options: ["a"], answer: "a" }] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M240"), "cloze_select without blank mark → M240");
    assert.ok(r.issues.some((i) => i.code === "M241"), "cloze_select without valid blank(<2 options) → M241");
    const r2 = validateMicroCard({ nodes: [{ type: "cloze_select", text: "I ___ home.", blanks: [{ options: ["go", "went"], answer: "did" }] }] });
    assert.ok(r2.issues.some((i) => i.code === "M242"), "cloze_select answer not in its options → M242");
    // ___ 数 ≠ blanks 条目数 → M243
    const r3 = validateMicroCard({ nodes: [{ type: "cloze_select", text: "I ___ to school and ___ homework.", blanks: [{ options: ["go", "went"], answer: "went" }] }] });
    assert.ok(r3.issues.some((i) => i.code === "M243"), "cloze_select blanks≠___ → M243");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "listen_fill", audioText: "I went to school by bus.", text: "I ___ to school by ___.", bank: ["went", "bus", "go", "car"], answers: ["went", "bus"] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "listen_fill", text: "no blank", bank: ["a"], answers: [] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M250"), "listen_fill without audioText → M250");
    assert.ok(r.issues.some((i) => i.code === "M251"), "listen_fill without blank mark → M251");
    assert.ok(r.issues.some((i) => i.code === "M252"), "listen_fill without answers → M252");
    assert.ok(r.issues.some((i) => i.code === "M253"), "listen_fill with <2 bank words → M253");
    const r2 = validateMicroCard({ nodes: [{ type: "listen_fill", audioText: "I went to school by bus.", text: "I ___ to school by ___.", bank: ["went", "bus", "go", "car"], answers: ["went"] }] });
    assert.ok(r2.issues.some((i) => i.code === "M254"), "listen_fill blanks≠answers → M254");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "word_formation", prompt: "写出词形", items: [{ base: "happy", target: "名词", answer: "happiness" }, { base: "care", target: "形容词", answer: "careful" }], bank: ["happiness", "careful", "happily"] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "word_formation", items: [{ base: "happy" }] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M260"), "word_formation item missing answer → M260");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "timeline", title: "英语时态", events: [{ time: "过去", title: "一般过去时", detail: "did" }, { time: "现在", title: "一般现在时" }] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "timeline", events: [{ title: "only one" }] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M270"), "timeline with <2 events → M270");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "stress_mark", word: "banana", syllables: ["ba", "na", "na"], stress: 2 }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "stress_mark", syllables: ["only"], stress: 5 }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M280"), "stress_mark with <2 syllables → M280");
    assert.ok(r.issues.some((i) => i.code === "M281"), "stress_mark with out-of-range stress → M281");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "reorder_paragraph", prompt: "排成连贯段落", sentences: ["First, I woke up early.", "Then I had breakfast.", "Finally I went to school."] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "reorder_paragraph", sentences: ["only one"] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M290"), "reorder_paragraph with <2 sentences → M290");
    const r2 = validateMicroCard({ nodes: [{ type: "reorder_paragraph", sentences: ["Same line.", "same line.", "Other."] }] });
    assert.ok(r2.issues.some((i) => i.code === "M291"), "reorder_paragraph with duplicate sentences → M291");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "odd_one_out", prompt: "选出不同类的", items: ["apple", "banana", "car", "orange"], answer: "car", explanation: "其余都是水果" }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "odd_one_out", items: ["a", "b"], answer: "a" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M300"), "odd_one_out with <3 items → M300");
    const r2 = validateMicroCard({ nodes: [{ type: "odd_one_out", items: ["apple", "banana", "orange"], answer: "car" }] });
    assert.ok(r2.issues.some((i) => i.code === "M301"), "odd_one_out answer not in items → M301");
    const r3 = validateMicroCard({ nodes: [{ type: "odd_one_out", items: ["apple", "banana", "orange"] }] });
    assert.ok(r3.issues.some((i) => i.code === "M301"), "odd_one_out without answer → M301");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "rank_order", prompt: "按从冷到热排列", items: ["freezing", "cold", "warm", "hot"], from: "最冷", to: "最热" }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "rank_order", items: ["a", "b"] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M310"), "rank_order with <3 items → M310");
    const r2 = validateMicroCard({ nodes: [{ type: "rank_order", items: ["small", "Small", "big"] }] });
    assert.ok(r2.issues.some((i) => i.code === "M311"), "rank_order with duplicate items → M311");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "spelling_bee", word: "necessary", hint: "必要的", example: "It is ___ to sleep well." }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "spelling_bee", word: "a" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M321"), "spelling_bee with <2 letters → M321");
    const r2 = validateMicroCard({ nodes: [{ type: "spelling_bee" }] });
    assert.ok(r2.issues.some((i) => i.code === "M320"), "spelling_bee without word → M320");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "sentence_transform", prompt: "改为被动语态", source: "He wrote the book.", answer: "The book was written by him.", accept: ["The book was written by him"] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "sentence_transform", prompt: "改为被动语态" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M330"), "sentence_transform without source → M330");
    assert.ok(r.issues.some((i) => i.code === "M331"), "sentence_transform without answer → M331");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "open_cloze", prompt: "用适当的介词/冠词填空", text: "I am good ___ English and I have ___ apple.", answers: ["at", "an"] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "open_cloze", text: "no blank here", answers: [] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M340"), "open_cloze without blank → M340");
    assert.ok(r.issues.some((i) => i.code === "M341"), "open_cloze without answers → M341");
    const r2 = validateMicroCard({ nodes: [{ type: "open_cloze", text: "I am good ___ English and I have ___ apple.", answers: ["at"] }] });
    assert.ok(r2.issues.some((i) => i.code === "M342"), "open_cloze blanks≠answers → M342");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "translate", direction: "zh2en", source: "我每天步行去上学。", answer: "I walk to school every day.", accept: ["I go to school on foot every day."] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "translate", direction: "zh2en" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M350"), "translate without source → M350");
    assert.ok(r.issues.some((i) => i.code === "M351"), "translate without answer → M351");
  }
  {
    // tfng 必须搭配 passage/text 材料（M501 卡级校验）
    const r = validateMicroCard({ nodes: [
      { type: "passage", text: "The author lives in Paris and enjoys tea every morning." },
      { type: "tfng", prompt: "根据短文判断", statements: [{ text: "The author lives in Paris.", answer: "true" }, { text: "The author hates coffee.", answer: "not_given" }, { text: "Cats can fly.", answer: "false" }] }
    ] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "tfng", statements: [] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M360"), "tfng without statements → M360");
    const r2 = validateMicroCard({ nodes: [{ type: "tfng", statements: [{ text: "only text, no answer" }] }] });
    assert.ok(r2.issues.some((i) => i.code === "M360"), "tfng statement missing answer → M360");
  }
  {
    // 材料共现（卡级）：tfng / match_info 缺 passage/text → M501（走整卡重生成）；补上材料即通过
    const bare = validateMicroCard({ nodes: [{ type: "tfng", statements: [{ text: "s", answer: "true" }] }] });
    assert.ok(!bare.ok);
    assert.ok(bare.issues.some((i) => i.code === "M501" && i.path === "nodes"), "tfng without material → card-level M501");
    const bareInfo = validateMicroCard({ nodes: [{ type: "match_info", options: ["A", "B"], statements: [{ text: "info", answer: "A" }] }] });
    assert.ok(bareInfo.issues.some((i) => i.code === "M501"), "match_info without material → M501");
    const withMaterial = validateMicroCard({ nodes: [
      { type: "passage", text: "Paragraph A talks about cats. Paragraph B talks about dogs." },
      { type: "match_info", options: ["A", "B"], statements: [{ text: "cats are mentioned", answer: "A" }] }
    ] });
    assert.ok(withMaterial.ok, renderReport(withMaterial.issues));
  }
  {
    // 答案泄漏：choice/timed_challenge/audio_choice 的 prompt 含答案原文 → error；audioText 含答案不算泄漏
    const leak = validateMicroCard({ nodes: [{ type: "choice", prompt: "Which word means 快乐的? It is happy.", options: ["happy", "sad"], answer: "happy" }] });
    assert.ok(leak.issues.some((i) => i.code === "M013"), "choice prompt leaking answer → M013");
    const noLeak = validateMicroCard({ nodes: [{ type: "choice", prompt: "选出 go 的过去式", options: ["went", "goed"], answer: "went" }] });
    assert.ok(noLeak.ok, renderReport(noLeak.issues));
    // 短 ASCII 答案(<3字符)不查泄漏，防 "a/an/go" 撞题干正常用词
    const shortAnswer = validateMicroCard({ nodes: [{ type: "choice", prompt: "Choose the correct article to go with 'apple'.", options: ["a", "an"], answer: "an" }] });
    assert.ok(shortAnswer.ok, renderReport(shortAnswer.issues));
    const timedLeak = validateMicroCard({ nodes: [{ type: "timed_challenge", prompt: "快答：went 是不是 go 的过去式？", options: ["went", "gone"], answer: "went" }] });
    assert.ok(timedLeak.issues.some((i) => i.code === "M174"), "timed_challenge prompt leaking answer → M174");
    const audioOk = validateMicroCard({ nodes: [{ type: "audio_choice", audioText: "sheep", prompt: "选出你听到的词", options: ["ship", "sheep"], answer: "sheep" }] });
    assert.ok(audioOk.ok, "audioText 含答案属正常（朗读内容），不应算泄漏：" + renderReport(audioOk.issues));
    const audioLeak = validateMicroCard({ nodes: [{ type: "audio_choice", audioText: "sheep", prompt: "听音选词：sheep 还是 ship？", options: ["ship", "sheep"], answer: "sheep" }] });
    assert.ok(audioLeak.issues.some((i) => i.code === "M114"), "audio_choice prompt leaking answer → M114");
  }
  {
    // 干扰项重复 → error（choice/cloze_select 每空/odd_one_out）
    const dup = validateMicroCard({ nodes: [{ type: "choice", prompt: "选出正确形式", options: ["went", "Went ", "gone"], answer: "gone" }] });
    assert.ok(dup.issues.some((i) => i.code === "M014"), "choice duplicate options → M014");
    const dupBlank = validateMicroCard({ nodes: [{ type: "cloze_select", text: "I ___ home.", blanks: [{ options: ["went", "went"], answer: "went" }] }] });
    assert.ok(dupBlank.issues.some((i) => i.code === "M244"), "cloze_select duplicate blank options → M244");
    const dupOdd = validateMicroCard({ nodes: [{ type: "odd_one_out", items: ["apple", "apple", "car"], answer: "car" }] });
    assert.ok(dupOdd.issues.some((i) => i.code === "M302"), "odd_one_out duplicate items → M302");
  }
  {
    // dialogue_complete：答案已出现在对话正文 → M393；选项重复 → M394
    const leakTurn = validateMicroCard({ nodes: [{ type: "dialogue_complete", turns: [{ speaker: "A", text: "What can I get you?" }, { speaker: "B", text: "A latte, please." }], options: ["A latte, please.", "See you."], answer: "A latte, please." }] });
    assert.ok(leakTurn.issues.some((i) => i.code === "M393"), "dialogue_complete answer shown in turns → M393");
    const dupOpts = validateMicroCard({ nodes: [{ type: "dialogue_complete", turns: [{ speaker: "A", text: "Hi?" }], options: ["Sure.", "sure."], answer: "Sure." }] });
    assert.ok(dupOpts.issues.some((i) => i.code === "M394"), "dialogue_complete duplicate options → M394");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "listen_cloze", audioText: "I went to school by bus.", text: "I ___ to school by ___.", answers: ["went", "bus"] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "listen_cloze", text: "no blank", answers: [] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M370"), "listen_cloze without audioText → M370");
    assert.ok(r.issues.some((i) => i.code === "M371"), "listen_cloze without blank → M371");
    assert.ok(r.issues.some((i) => i.code === "M372"), "listen_cloze without answers → M372");
    const r2 = validateMicroCard({ nodes: [{ type: "listen_cloze", audioText: "I went to school by bus.", text: "I ___ to school by ___.", answers: ["went"] }] });
    assert.ok(r2.issues.some((i) => i.code === "M373"), "listen_cloze blanks≠answers → M373");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "error_correction", prompt: "时态错误", sentence: "She go to school yesterday.", answer: "She went to school yesterday.", accept: ["She went to school yesterday"], explanation: "过去时间状语用过去式 went" }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "error_correction", prompt: "时态" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M380"), "error_correction without sentence → M380");
    assert.ok(r.issues.some((i) => i.code === "M381"), "error_correction without answer → M381");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "dialogue_complete", prompt: "在咖啡店", turns: [{ speaker: "Clerk", text: "What can I get for you?" }], options: ["A latte, please.", "I am fine, thanks.", "See you tomorrow."], answer: "A latte, please." }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "dialogue_complete", turns: [], options: ["a"], answer: "z" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M390"), "dialogue_complete without turns → M390");
    assert.ok(r.issues.some((i) => i.code === "M391"), "dialogue_complete with <2 options → M391");
    const r2 = validateMicroCard({ nodes: [{ type: "dialogue_complete", turns: [{ speaker: "A", text: "Hi" }], options: ["a", "b"], answer: "z" }] });
    assert.ok(r2.issues.some((i) => i.code === "M392"), "dialogue_complete answer not in options → M392");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "word_search", prompt: "找出动物", words: ["CAT", "DOG"], grid: [["C", "A", "T", "X"], ["D", "Y", "Z", "Q"], ["O", "M", "N", "P"], ["G", "H", "I", "J"]] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "word_search", grid: [], words: [] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M400"), "word_search without grid → M400");
    assert.ok(r.issues.some((i) => i.code === "M401"), "word_search without words → M401");
    const r2 = validateMicroCard({ nodes: [{ type: "word_search", words: ["CAT"], grid: [["X", "A", "T"], ["D", "Y", "Z"]] }] });
    assert.ok(r2.issues.some((i) => i.code === "M402"), "word_search word not placeable → M402");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "hangman", word: "banana", hint: "香蕉", maxWrong: 6 }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "hangman", word: "a" }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M410"), "hangman with <2 letters → M410");
    const r2 = validateMicroCard({ nodes: [{ type: "hangman" }] });
    assert.ok(r2.issues.some((i) => i.code === "M410"), "hangman without word → M410");
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "proof_paragraph", prompt: "改错", lines: [{ text: "She go home.", answer: "She goes home.", note: "第三人称单数" }, { text: "I am fine.", answer: "I am fine." }] }] });
    assert.ok(r.ok, renderReport(r.issues));
  }
  {
    const r = validateMicroCard({ nodes: [{ type: "proof_paragraph", lines: [] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M420"), "proof_paragraph without lines → M420");
    const r2 = validateMicroCard({ nodes: [{ type: "proof_paragraph", lines: [{ text: "All correct.", answer: "All correct." }] }] });
    assert.ok(r2.issues.some((i) => i.code === "M421"), "proof_paragraph with no erroneous line → M421");
  }
  {
    // 每行都必须显式给 answer（审计实证的真实坏卡形态：题面称 10 处错、11 行里只 2 行带 answer，
    // 端上把无 answer 的行判为「本来就对」→ 学生改对真实错误反被判错）
    const r = validateMicroCard({ nodes: [{ type: "proof_paragraph", prompt: "每行至多一处错", lines: [
      { text: "She go home.", answer: "She goes home." },
      { text: "I saw a old man." },
      { text: "He looked very worry." }
    ] }] });
    assert.ok(!r.ok);
    const m422 = r.issues.filter((i) => i.code === "M422");
    assert.strictEqual(m422.length, 2, "两行缺 answer → 2 个 M422");
    assert.ok(m422.some((i) => /lines\[1\]/.test(i.path)) && m422.some((i) => /lines\[2\]/.test(i.path)), "M422 定位到具体行");
  }
  {
    // writing：自由写作微元（AI 批改、不判分）——prompt 必填
    const ok = validateMicroCard({ nodes: [{ type: "writing", prompt: "写一封 80-100 词的英文道歉邮件，说明原因并提出补救安排。", reference: "要点：道歉+原因+补救+礼貌结尾" }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "writing", reference: "只有参考没有题目" }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M460"), "writing without prompt → M460");
  }
  {
    // 题面宣称错误数与实际不符（回归实证：模型爱写「共有10处错误」但只给 6~7 处）→ M423
    const r = validateMicroCard({ nodes: [{ type: "proof_paragraph", prompt: "以下短文共有10处错误，请逐行改正。", lines: [
      { text: "She go home.", answer: "She goes home." },
      { text: "I am fine.", answer: "I am fine." }
    ] }] });
    assert.ok(!r.ok);
    assert.ok(r.issues.some((i) => i.code === "M423" && /claims 10 errors but 1/.test(i.message)), "宣称 10 实为 1 → M423");
    // “每行最多一处错误，共10处”里的「共10处」是总数宣称（前缀含逗号截断分布性描述）→ 触发
    const r3 = validateMicroCard({ nodes: [{ type: "proof_paragraph", prompt: "每行最多一处错误，共10处。", lines: [
      { text: "She go home.", answer: "She goes home." },
      { text: "I am fine.", answer: "I am fine." }
    ] }] });
    assert.ok(r3.issues.some((i) => i.code === "M423"), "「共10处」总数宣称 → M423");
    // 数目一致 → 通过
    const ok = validateMicroCard({ nodes: [{ type: "proof_paragraph", prompt: "短文共有1处错误（每行至多1处）。", lines: [
      { text: "She go home.", answer: "She goes home." },
      { text: "I am fine.", answer: "I am fine." }
    ] }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    // “每行至多有1处错”是分布性描述、不是总数宣称 → 不触发 M423
    const dist = validateMicroCard({ nodes: [{ type: "proof_paragraph", prompt: "每行至多有1处错，逐行改正。", lines: [
      { text: "She go home.", answer: "She goes home." },
      { text: "I saw a old man.", answer: "I saw an old man." }
    ] }] });
    assert.ok(dist.ok, renderReport(dist.issues));
  }
  {
    // monologue：看图说话/话题独白（AI 评分、不判分）——prompt 必填
    const ok = validateMicroCard({ nodes: [{ type: "monologue", prompt: "描述这张图片里发生的事，说 40 秒。", scene: "一个繁忙的火车站", points: ["人物", "动作", "感受"] }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "monologue", scene: "只有情景没有任务" }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M470"), "monologue without prompt → M470");
  }
  {
    // shadowing：影子跟读（AI 评分、不判分）——text 必填
    const ok = validateMicroCard({ nodes: [{ type: "shadowing", text: "I would like a cup of coffee, please.", translation: "我想要一杯咖啡，谢谢。" }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "shadowing", translation: "只有译文没有示范句" }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M480"), "shadowing without text → M480");
  }
  {
    // minimal_pair：最小对立对听辨（可判分）——朗读 answer 那个词，从近音词里选
    const ok = validateMicroCard({ nodes: [{ type: "minimal_pair", audioText: "sheep", options: ["ship", "sheep"], answer: "sheep", ipa: ["/ʃɪp/", "/ʃiːp/"] }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "minimal_pair", options: ["a"], answer: "b" }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M490"), "minimal_pair without audioText → M490");
    assert.ok(bad.issues.some((i) => i.code === "M491"), "minimal_pair with <2 options → M491");
    assert.ok(bad.issues.some((i) => i.code === "M493"), "minimal_pair answer not in options → M493");
    const noAns = validateMicroCard({ nodes: [{ type: "minimal_pair", audioText: "sheep", options: ["ship", "sheep"] }] });
    assert.ok(noAns.issues.some((i) => i.code === "M492"), "minimal_pair without answer → M492");
    const leak = validateMicroCard({ nodes: [{ type: "minimal_pair", audioText: "sheep", prompt: "is it sheep or ship?", options: ["ship", "sheep"], answer: "sheep" }] });
    assert.ok(leak.issues.some((i) => i.code === "M494"), "minimal_pair prompt leaking answer → M494");
    const dup = validateMicroCard({ nodes: [{ type: "minimal_pair", audioText: "sheep", options: ["sheep", "Sheep "], answer: "sheep" }] });
    assert.ok(dup.issues.some((i) => i.code === "M495"), "minimal_pair duplicate options → M495");
    const ipaMismatch = validateMicroCard({ nodes: [{ type: "minimal_pair", audioText: "sheep", options: ["ship", "sheep"], answer: "sheep", ipa: ["/ʃiːp/"] }] });
    assert.ok(ipaMismatch.issues.some((i) => i.code === "M496" && i.severity === "warning"), "minimal_pair ipa≠options → M496 warning");
    const wrongAudio = validateMicroCard({ nodes: [{ type: "minimal_pair", audioText: "boat", options: ["ship", "sheep"], answer: "sheep" }] });
    assert.ok(wrongAudio.issues.some((i) => i.code === "M497"), "minimal_pair audioText not speaking answer → M497");
  }
  {
    // ipa_read：音标认读（可判分）——认 IPA 选含该音的词
    const ok = validateMicroCard({ nodes: [{ type: "ipa_read", symbol: "/iː/", example: "sheep", options: ["ship", "sheep"], answer: "sheep" }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "ipa_read", options: ["a"], answer: "b" }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M510"), "ipa_read without symbol → M510");
    assert.ok(bad.issues.some((i) => i.code === "M511"), "ipa_read with <2 options → M511");
    assert.ok(bad.issues.some((i) => i.code === "M513"), "ipa_read answer not in options → M513");
    const noAns = validateMicroCard({ nodes: [{ type: "ipa_read", symbol: "/iː/", options: ["ship", "sheep"] }] });
    assert.ok(noAns.issues.some((i) => i.code === "M512"), "ipa_read without answer → M512");
    const dup = validateMicroCard({ nodes: [{ type: "ipa_read", symbol: "/iː/", options: ["sheep", "Sheep "], answer: "sheep" }] });
    assert.ok(dup.issues.some((i) => i.code === "M515"), "ipa_read duplicate options → M515");
  }
  {
    // sound_link：连读/弱读/语调（纯展示、不判分）——text 必填
    const ok = validateMicroCard({ nodes: [{ type: "sound_link", text: "Would you like an apple?", marks: ["like_an", "an_apple"], note: "辅音+元音连读" }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "sound_link", note: "只有讲解没有句子" }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M520"), "sound_link without text → M520");
  }
  {
    // map_label：听力位置标注（可判分）——每个地点选位置标签、须∈options
    const ok = validateMicroCard({ nodes: [{ type: "map_label", audioText: "The cafe is at A and the library is at B.", options: ["A", "B", "C"], items: [{ text: "Cafe", answer: "A" }, { text: "Library", answer: "B" }] }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "map_label", options: ["A"], items: [{ text: "Cafe", answer: "Z" }] }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M530"), "map_label without audioText → M530");
    assert.ok(bad.issues.some((i) => i.code === "M531"), "map_label with <2 options → M531");
    assert.ok(bad.issues.some((i) => i.code === "M533"), "map_label item answer not in options → M533");
    const noItems = validateMicroCard({ nodes: [{ type: "map_label", audioText: "x", options: ["A", "B"] }] });
    assert.ok(noItems.issues.some((i) => i.code === "M532"), "map_label without items → M532");
    const dup = validateMicroCard({ nodes: [{ type: "map_label", audioText: "x", options: ["A", "a "], items: [{ text: "Cafe", answer: "A" }] }] });
    assert.ok(dup.issues.some((i) => i.code === "M534"), "map_label duplicate options → M534");
  }
  {
    // note_complete：长音频笔记填空（可判分）——空位数须等于 answers 条目数
    const ok = validateMicroCard({ nodes: [{ type: "note_complete", audioText: "The meeting is on Monday at 3pm in room 5.", title: "Meeting notes", text: "Day: ___  Time: ___", answers: ["Monday", "3pm"] }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "note_complete", text: "no blank", answers: [] }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M540"), "note_complete without audioText → M540");
    assert.ok(bad.issues.some((i) => i.code === "M541"), "note_complete without blank → M541");
    assert.ok(bad.issues.some((i) => i.code === "M542"), "note_complete without answers → M542");
    const mismatch = validateMicroCard({ nodes: [{ type: "note_complete", audioText: "x", text: "a ___ b ___", answers: ["one"] }] });
    assert.ok(mismatch.issues.some((i) => i.code === "M543"), "note_complete blanks≠answers → M543");
  }
  {
    // match_sentence_endings：IELTS 句尾配对（可判分）——每个句尾须∈endings
    const ok = validateMicroCard({ nodes: [{ type: "match_sentence_endings", stems: [{ text: "The library opens", answer: "at nine." }, { text: "The cafe closes", answer: "at ten." }], endings: ["at nine.", "at ten.", "on Sundays."] }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "match_sentence_endings", stems: [], endings: ["a"] }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M550"), "match_sentence_endings without stems → M550");
    assert.ok(bad.issues.some((i) => i.code === "M551"), "match_sentence_endings with <2 endings → M551");
    const notIn = validateMicroCard({ nodes: [{ type: "match_sentence_endings", stems: [{ text: "x", answer: "zzz" }], endings: ["at nine.", "at ten."] }] });
    assert.ok(notIn.issues.some((i) => i.code === "M552"), "match_sentence_endings stem answer not in endings → M552");
    const dup = validateMicroCard({ nodes: [{ type: "match_sentence_endings", stems: [{ text: "x", answer: "at nine." }], endings: ["at nine.", "At nine. "] }] });
    assert.ok(dup.issues.some((i) => i.code === "M553"), "match_sentence_endings duplicate endings → M553");
  }
  {
    // summary_complete：摘要/流程图选词填空（可判分）——answers 每项须∈bank、空位数须匹配
    const ok = validateMicroCard({ nodes: [{ type: "summary_complete", text: "The process starts with ___ then ___.", bank: ["heating", "cooling", "mixing"], answers: ["heating", "cooling"] }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "summary_complete", text: "no blank", bank: ["a"], answers: [] }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M560"), "summary_complete without blank → M560");
    assert.ok(bad.issues.some((i) => i.code === "M561"), "summary_complete without answers → M561");
    assert.ok(bad.issues.some((i) => i.code === "M562"), "summary_complete with <2 bank words → M562");
    const mismatch = validateMicroCard({ nodes: [{ type: "summary_complete", text: "a ___ b ___", bank: ["heating", "cooling"], answers: ["heating"] }] });
    assert.ok(mismatch.issues.some((i) => i.code === "M563"), "summary_complete blanks≠answers → M563");
    const notInBank = validateMicroCard({ nodes: [{ type: "summary_complete", text: "a ___ b", bank: ["heating", "cooling"], answers: ["boiling"] }] });
    assert.ok(notInBank.issues.some((i) => i.code === "M564"), "summary_complete answer not in bank → M564");
  }
  {
    // short_answer：篇章简答（可判分）——须搭配 passage/text 材料（卡级 M501）
    const ok = validateMicroCard({ nodes: [
      { type: "passage", text: "London is the capital of England and has about 9 million people." },
      { type: "short_answer", prompt: "Answer based on the passage.", questions: [{ q: "What is the capital of England?", answer: "London" }, { q: "About how many people live there?", answer: "9 million", accept: ["nine million"] }] }
    ] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const noQ = validateMicroCard({ nodes: [{ type: "short_answer", questions: [] }] });
    assert.ok(!noQ.ok);
    assert.ok(noQ.issues.some((i) => i.code === "M570"), "short_answer without questions → M570");
    const bareMaterial = validateMicroCard({ nodes: [{ type: "short_answer", questions: [{ q: "Q?", answer: "A" }] }] });
    assert.ok(bareMaterial.issues.some((i) => i.code === "M501" && i.path === "nodes"), "short_answer without material → card-level M501");
  }
  {
    // guided_writing：结构化引导写作（AI 评分、不判分）——prompt 必填
    const ok = validateMicroCard({ nodes: [{ type: "guided_writing", prompt: "写一篇 120 词的观点作文，谈远程办公的利弊。", steps: [{ label: "提纲", hint: "列 3 个要点" }, { label: "成文" }], reference: "要点：定义+利+弊+结论" }] });
    assert.ok(ok.ok, renderReport(ok.issues));
    const bad = validateMicroCard({ nodes: [{ type: "guided_writing", reference: "只有参考没有要求" }] });
    assert.ok(!bad.ok);
    assert.ok(bad.issues.some((i) => i.code === "M580"), "guided_writing without prompt → M580");
  }

  // ---- renderReport 形态（可回灌给模型）----
  {
    const r = validateMicroCard({ nodes: [{ type: "choice", options: ["a"], answer: "a" }] });
    const report = renderReport(r.issues);
    assert.ok(/MicroCard Validation/.test(report));
    assert.ok(/\[M010\] \[error\]/.test(report));
  }

  // ---- prompt 由微元清单生成 ----
  {
    const p = microCardSchemaPrompt();
    assert.ok(/tokens/.test(p) && /choice/.test(p) && /passage/.test(p) && /roleplay_turn/.test(p) && /ai_hint/.test(p) && /highlight_span/.test(p) && /timed_challenge/.test(p) && /table:/.test(p) && /sentence_diagram/.test(p) && /word_scramble/.test(p) && /true_false/.test(p) && /fill_table/.test(p) && /pairs_memory/.test(p) && /cloze_select/.test(p) && /listen_fill/.test(p) && /word_formation/.test(p) && /timeline/.test(p) && /stress_mark/.test(p) && /reorder_paragraph/.test(p) && /odd_one_out/.test(p) && /rank_order/.test(p) && /spelling_bee/.test(p) && /sentence_transform/.test(p) && /open_cloze/.test(p) && /translate/.test(p) && /tfng/.test(p) && /listen_cloze/.test(p) && /error_correction/.test(p) && /dialogue_complete/.test(p) && /word_search/.test(p) && /hangman/.test(p) && /proof_paragraph/.test(p));
    // 新增 11 个题型也须进入全量 prompt（清单变了 prompt 自动跟着变）
    assert.ok(/- monologue:\{/.test(p) && /- shadowing:\{/.test(p) && /- minimal_pair:\{/.test(p) && /- ipa_read:\{/.test(p) && /- sound_link:\{/.test(p) && /- map_label:\{/.test(p) && /- note_complete:\{/.test(p) && /- match_sentence_endings:\{/.test(p) && /- summary_complete:\{/.test(p) && /- short_answer:\{/.test(p) && /- guided_writing:\{/.test(p), "全量 prompt 应含 11 个新题型");
  }

  // ---- 计数一致：空位数 == 答案数时应通过（回归护栏，避免误报）----
  {
    const ok = validateMicroCard({ nodes: [{ type: "open_cloze", text: "I am good ___ English and I have ___ apple.", answers: ["at", "an"] }] });
    assert.ok(ok.ok, renderReport(ok.issues));
  }

  // ---- schema prompt 按技能维度裁剪 ----
  {
    // 无消息 / 检测不到维度 → 全量（与裁剪前行为一致）
    const full = microCardSchemaPrompt();
    assert.strictEqual(microCardSchemaPrompt("给我出道题"), full, "检测不到维度应发全量");
    assert.strictEqual(detectMicroSkillDimensions("给我出道题"), null);
    // 听力请求：保留听力+通用，剔除阅读专属
    const listening = microCardSchemaPrompt("出几道听力精听练习");
    assert.ok(/- listen_fill:\{/.test(listening), "听力请求应含 listen_fill");
    assert.ok(/- dictation:\{/.test(listening), "听力请求应含 dictation");
    assert.ok(!/- match_headings:\{/.test(listening), "听力请求不应含阅读专属 match_headings");
    assert.ok(/- choice:\{/.test(listening) && /- text:\{/.test(listening), "通用微元恒在");
    assert.ok(listening.length < full.length, "裁剪后应显著变短");
    // 新增听力类题型随听力维度保留、阅读专属新题型被剔除
    assert.ok(/- map_label:\{/.test(listening) && /- note_complete:\{/.test(listening) && /- minimal_pair:\{/.test(listening), "听力请求应含听力类新题型");
    assert.ok(!/- summary_complete:\{/.test(listening) && !/- match_sentence_endings:\{/.test(listening) && !/- short_answer:\{/.test(listening), "听力请求不应含阅读专属新题型");
    // 口语请求：保留口语类新题型（含跟读/独白/音标/连读）
    const speaking = microCardSchemaPrompt("练一练英语发音和跟读");
    assert.ok(/- shadowing:\{/.test(speaking) && /- monologue:\{/.test(speaking) && /- ipa_read:\{/.test(speaking) && /- sound_link:\{/.test(speaking), "口语请求应含口语类新题型");
    assert.ok(!/- summary_complete:\{/.test(speaking), "口语请求不应含阅读专属 summary_complete");
    // 写作请求：保留 guided_writing
    const writing = microCardSchemaPrompt("帮我练习英语写作");
    assert.ok(/- guided_writing:\{/.test(writing) && /- writing:\{/.test(writing), "写作请求应含 guided_writing/writing");
    // 可用 type 枚举同步裁剪
    assert.ok(!/type（必须取其一）：[^\n]*match_headings/.test(listening), "type 枚举也应剔除未附说明的类型");
    // 词汇请求：保留词汇游戏、剔除口语/阅读专属
    const vocab = microCardSchemaPrompt("帮我背这些单词");
    assert.ok(/- hangman:\{/.test(vocab) && /- flashcard:\{/.test(vocab), "词汇请求应含词汇专属微元");
    assert.ok(!/- roleplay_turn:\{/.test(vocab), "词汇请求不应含口语专属 roleplay_turn");
    // 多维度并集：听力+语法都保留
    const mixed = microCardSchemaPrompt("出一道听力填空题");
    assert.ok(/- listen_fill:\{/.test(mixed) && /- open_cloze:\{/.test(mixed), "多维度命中应取并集");
    // 决策指南行同步裁剪
    assert.ok(!/词汇·记忆/.test(listening), "听力请求不应带词汇决策指南");
    assert.ok(/通用增强/.test(listening), "通用指南恒在");
  }

  // ---- 裁剪后的 prompt 进入生成链路（system 随 userMessage 变化）----
  {
    const seen = [];
    const callModel = async (messages) => {
      seen.push(messages[0].content);
      return { title: "t", nodes: [{ type: "dictation", text: "I like tea." }] };
    };
    await generateMicroCard("来一段听写练习", { callModel, maxAttempts: 1 });
    assert.ok(/- dictation:\{/.test(seen[0]), "生成链路应使用按消息裁剪的 system prompt");
    assert.ok(!/- match_headings:\{/.test(seen[0]), "听写请求的 system 不应含阅读专属类型");
  }

  // ---- #3 自适应难度接线：显式 ability / level / 统一用户模型 abilities ----
  {
    // 无任何能力信息 → 空串（保持原行为，不污染 prompt）。
    assert.strictEqual(difficultyHintFromBody({}), "");
    assert.strictEqual(difficultyHintFromBody({ message: "出一道题" }), "");
    // 显式 ability 优先。
    const byAbility = difficultyHintFromBody({ ability: 30 });
    assert.ok(/CEFR/.test(byAbility) && byAbility.length > 0, "显式 ability 应产出难度提示");
    // 显式 level（CEFR）。
    const byLevel = difficultyHintFromBody({ level: "B2" });
    assert.ok(/CEFR/.test(byLevel), "显式 level 应产出难度提示");
    // 主链路兜底：只带 userModel.abilities 时也应接上（此前形同虚设的场景）。
    const byUserModel = difficultyHintFromBody({ userModel: { abilities: { "听力": 20, "语法": 40 } } });
    assert.ok(/CEFR/.test(byUserModel), "userModel.abilities 平均应产出难度提示");
    // 空 userModel / 空 abilities → 不产出。
    assert.strictEqual(difficultyHintFromBody({ userModel: {} }), "");
    assert.strictEqual(difficultyHintFromBody({ userModel: { abilities: {} } }), "");
    // 优先级：显式 ability 覆盖 userModel。
    assert.strictEqual(
      difficultyHintFromBody({ ability: 90, userModel: { abilities: { "听力": 10 } } }),
      difficultyHintFromBody({ ability: 90 }),
      "显式 ability 应覆盖 userModel"
    );
  }

  // ---- 自修复闭环：坏节点可定位 → 走定向修复（第二次调用只带问题节点），合并后通过 ----
  {
    let n = 0;
    const bad = { nodes: [{ type: "choice", options: ["a", "b"], answer: "c" }] };
    const fixed = { nodes: [{ type: "choice", options: ["a", "b"], answer: "a" }] };
    const calls = [];
    const callModel = async (messages) => {
      calls.push(messages);
      return n++ === 0 ? bad : fixed;
    };
    const out = await generateMicroCard("出一道选择题", { callModel, maxAttempts: 3 });
    assert.ok(out.ok, out.report);
    assert.strictEqual(out.attempts, 2);
    assert.strictEqual(calls.length, 2, "整卡生成 1 次 + 定向修复 1 次");
    const repairUser = calls[1][calls[1].length - 1].content;
    assert.ok(/M012/.test(repairUser), "修复调用应带上该节点的校验问题");
    assert.ok(/问题节点/.test(repairUser), "修复调用应是定向修复 prompt");
    assert.strictEqual(out.card.nodes[0].answer, "a");
  }

  // ---- 定向修复：多节点卡只有 1 个坏节点 → 只重修坏节点，合格节点零改动、不回灌 ----
  {
    let n = 0;
    const goodText = { type: "text", text: "UNIQUE_GOOD_NODE_TEXT" };
    const bad = { title: "t", nodes: [goodText, { type: "choice", options: ["a"], answer: "a" }] };
    const fixed = { nodes: [{ type: "choice", options: ["a", "b"], answer: "a" }] };
    const calls = [];
    const callModel = async (messages) => {
      calls.push(messages);
      return n++ === 0 ? bad : fixed;
    };
    const out = await generateMicroCard("x", { callModel, maxAttempts: 3 });
    assert.ok(out.ok, out.report);
    const repairUser = calls[1][calls[1].length - 1].content;
    assert.ok(!/UNIQUE_GOOD_NODE_TEXT/.test(repairUser), "合格节点不应回灌给修复调用");
    assert.strictEqual(out.card.nodes.length, 2);
    assert.strictEqual(out.card.nodes[0].text, "UNIQUE_GOOD_NODE_TEXT", "合格节点应原样保留");
    assert.strictEqual(out.card.nodes[1].options.length, 2, "坏节点应被修复版替换");
  }

  // ---- 定向修复输出不合规（长度不符）→ 回退整卡重生成（带回灌报告）----
  {
    let n = 0;
    const bad = { nodes: [{ type: "choice", options: ["a", "b"], answer: "c" }] };
    const malformedRepair = { nodes: [] }; // 长度 0 ≠ 1 → 修复失败
    const good = { title: "t", nodes: [{ type: "choice", options: ["a", "b"], answer: "a" }] };
    const calls = [];
    const callModel = async (messages) => {
      calls.push({ len: messages.length, contents: messages.map((m) => String(m.content)) });
      n += 1;
      if (n === 1) return bad;
      if (n === 2) return malformedRepair;
      return good;
    };
    const out = await generateMicroCard("x", { callModel, maxAttempts: 3 });
    assert.ok(out.ok, out.report);
    assert.strictEqual(calls.length, 3, "生成 → 修复失败 → 整卡重生成");
    assert.ok(calls[2].len > calls[0].len, "整卡重生成应带回灌的报告消息");
    assert.ok(calls[2].contents.some((c) => /未通过校验/.test(c)), "回灌消息应含校验报告引导");
  }

  // ---- 卡级问题（清洗后为空 M099）→ 不做定向修复，直接整卡重生成 ----
  {
    let n = 0;
    const allUnknown = { nodes: [{ type: "unknown_x" }] };
    const good = { title: "t", nodes: [{ type: "text", text: "ok" }] };
    const calls = [];
    const callModel = async (messages) => {
      calls.push({ len: messages.length, last: String(messages[messages.length - 1].content) });
      return n++ === 0 ? allUnknown : good;
    };
    const out = await generateMicroCard("x", { callModel, maxAttempts: 2 });
    assert.ok(out.ok, out.report);
    assert.strictEqual(calls.length, 2);
    assert.ok(!/问题节点/.test(calls[1].last), "卡级问题不应走定向修复");
    assert.ok(calls[1].len > calls[0].len, "应走整卡回灌重生成");
  }

  // ---- 始终不达标 → graceful：返回能渲染的合法微元（不套固定题型），ok=false ----
  {
    const partlyBad = {
      title: "t",
      nodes: [
        { type: "text", text: "提示" },
        { type: "choice", options: ["a"], answer: "a" } // 选项<2，字段无效但 type 已知
      ]
    };
    const callModel = async () => partlyBad;
    const out = await generateMicroCard("x", { callModel, maxAttempts: 2 });
    assert.ok(!out.ok);
    assert.ok(out.card && out.card.nodes.length >= 1, "graceful 应返回能渲染的微元而非固定兜底");
  }

  // ---- 全坏（清洗后为空）→ card=null，交调用方降级 ----
  {
    const callModel = async () => ({ nodes: [{ type: "unknown_x" }] });
    const out = await generateMicroCard("x", { callModel, maxAttempts: 2 });
    assert.ok(!out.ok);
    assert.strictEqual(out.card, null);
  }

  console.log("microCardGenerate.test.js passed");
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
