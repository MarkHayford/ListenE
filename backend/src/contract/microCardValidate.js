"use strict";

// 微元卡校验 + “Stem 式”可回灌校验报告（AI-in-the-loop）。
// 设计目标：把每处问题以稳定的 [code][severity] path: message 形式列出，能直接喂回模型让它自修。
// 与现有 43 题型管线隔离，仅服务微元卡 spike。

const { isKnownMicroNode, sanitizeMicroCard } = require("./microCardContract");

function issue(code, severity, path, message) {
  return { code, severity, path, message };
}

function hasText(value) {
  return value != null && !(typeof value === "string" && value.trim() === "");
}

// ---- 语义级检查（结构合法之上的出题卫生）----

// 选项池重复（大小写/首尾空白归一后）：干扰项失效、判分歧义。
function duplicatedOptions(options) {
  const seen = new Set();
  for (const option of options || []) {
    const key = String(option == null ? "" : option).trim().toLowerCase();
    if (seen.has(key)) return true;
    seen.add(key);
  }
  return false;
}

// 答案泄漏：题干等展示文本里原文出现正确答案。
// 保守匹配以压误报：纯 ASCII 答案要求 >=3 字符且带"词边界"（防 go 撞 good）；
// 含 CJK 的答案要求 >=2 字符、用包含匹配。
function answerLeaksInText(text, answer) {
  const t = String(text || "").toLowerCase();
  const a = String(answer == null ? "" : answer).trim().toLowerCase();
  if (!t || !a) return false;
  if (/^[\x00-\x7F]+$/.test(a)) {
    if (a.length < 3) return false;
    const escaped = a.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return new RegExp(`(^|[^a-z0-9])${escaped}($|[^a-z0-9])`, "i").test(t);
  }
  if (a.length < 2) return false;
  return t.includes(a);
}

// highlight_span：answers 里的词/短语必须真的出现在 text 中（镜像端上按空白分词、
// 去首尾标点、忽略大小写的定位算法）。幽灵词会被端上静默丢弃——答案键错但用户无从发现。
function splitHighlightTokens(text) {
  return String(text || "").trim().split(/\s+/).filter(Boolean);
}

function normalizeHighlightToken(token) {
  return String(token || "").trim().replace(/^\p{P}+|\p{P}+$/gu, "").toLowerCase();
}

function highlightPhraseFindable(text, phrase) {
  const target = splitHighlightTokens(phrase).map(normalizeHighlightToken).filter(Boolean);
  if (!target.length) return false;
  const norm = splitHighlightTokens(text).map(normalizeHighlightToken);
  if (target.length > norm.length) return false;
  for (let start = 0; start <= norm.length - target.length; start += 1) {
    let ok = true;
    for (let k = 0; k < target.length; k += 1) {
      if (norm[start + k] !== target[k]) { ok = false; break; }
    }
    if (ok) return true;
  }
  return false;
}

// word_search：判断 word 是否能在 grid 里沿 8 个方向的直线找到（大小写/非字母数字忽略）。
function wordSearchFindable(grid, word) {
  const g = Array.isArray(grid)
    ? grid.map((row) => (Array.isArray(row) ? row.map((c) => String(c == null ? "" : c).trim().toUpperCase()) : []))
    : [];
  const rows = g.length;
  const target = String(word || "").replace(/[^A-Za-z0-9]/g, "").toUpperCase();
  if (target.length < 2 || rows === 0) return false;
  const dirs = [[0, 1], [1, 0], [1, 1], [1, -1], [0, -1], [-1, 0], [-1, -1], [-1, 1]];
  for (let r = 0; r < rows; r += 1) {
    for (let c = 0; c < g[r].length; c += 1) {
      for (const [dr, dc] of dirs) {
        let ok = true;
        let s = "";
        for (let k = 0; k < target.length; k += 1) {
          const cell = g[r + k * dr] && g[r + k * dr][c + k * dc];
          if (!cell) { ok = false; break; }
          s += cell;
        }
        if (ok && s === target) return true;
      }
    }
  }
  return false;
}

function validateNode(node, index) {
  const issues = [];
  const path = `nodes[${index}]`;
  const rawType = node && node.type;
  const type = String(rawType || "").trim().toLowerCase();
  if (!isKnownMicroNode(type)) {
    issues.push(issue("M001", "error", path, `unknown node type '${rawType}' (will be dropped)`));
    return issues;
  }
  switch (type) {
    case "text":
    case "passage":
      if (!hasText(node.text)) issues.push(issue("M002", "error", `${path}.text`, `'${type}' requires non-empty 'text'`));
      break;
    case "choice": {
      const options = Array.isArray(node.options)
        ? node.options.filter((o) => String(o || "").trim() !== "")
        : [];
      if (options.length < 2) issues.push(issue("M010", "error", `${path}.options`, "'choice' needs >= 2 non-empty options"));
      if (!hasText(node.answer)) {
        issues.push(issue("M011", "error", `${path}.answer`, "'choice' requires 'answer'"));
      } else if (options.length && !options.some((o) => String(o).trim().toLowerCase() === String(node.answer).trim().toLowerCase())) {
        issues.push(issue("M012", "error", `${path}.answer`, `answer '${node.answer}' must match one of options`));
      }
      if (answerLeaksInText(node.prompt, node.answer)) {
        issues.push(issue("M013", "error", `${path}.prompt`, "'choice' prompt leaks the answer verbatim; rephrase the prompt without the answer"));
      }
      if (options.length >= 2 && duplicatedOptions(options)) {
        issues.push(issue("M014", "error", `${path}.options`, "'choice' options must be distinct (duplicates break distractors)"));
      }
      break;
    }
    case "tokens":
      if (!hasText(node.text)) issues.push(issue("M020", "error", `${path}.text`, "'tokens' requires 'text' (the wrong sentence/passage)"));
      if (!hasText(node.correct) && !(Array.isArray(node.errors) && node.errors.length)) {
        issues.push(issue("M021", "error", path, "'tokens' needs 'correct' sentence or non-empty 'errors' so the mistake can be located"));
      }
      break;
    case "input":
      if (!hasText(node.prompt) && !hasText(node.answer)) {
        issues.push(issue("M030", "warning", path, "'input' has neither 'prompt' nor 'answer' (won't be gradable)"));
      }
      break;
    case "order": {
      const items = Array.isArray(node.items) ? node.items.filter((o) => String(o || "").trim() !== "") : [];
      if (items.length < 2) issues.push(issue("M060", "error", `${path}.items`, "'order' needs >= 2 items to reorder"));
      if (!hasText(node.answer)) issues.push(issue("M061", "error", `${path}.answer`, "'order' requires 'answer' (correct order 'a | b | c' or full sentence)"));
      break;
    }
    case "audio":
      if (!hasText(node.src)) issues.push(issue("M040", "warning", `${path}.src`, "'audio' missing 'src'"));
      break;
    case "reveal":
      if (!hasText(node.content)) issues.push(issue("M050", "warning", `${path}.content`, "'reveal' missing 'content'"));
      break;
    case "match": {
      const pairs = Array.isArray(node.pairs)
        ? node.pairs.filter((p) => p && hasText(p.left) && hasText(p.right))
        : [];
      if (pairs.length < 2) issues.push(issue("M070", "error", `${path}.pairs`, "'match' needs >= 2 pairs, each with non-empty 'left' and 'right'"));
      break;
    }
    case "categorize": {
      const cats = Array.isArray(node.categories)
        ? node.categories.filter((c) => c && hasText(c.name) && Array.isArray(c.items) && c.items.some((x) => String(x || "").trim() !== ""))
        : [];
      const totalItems = cats.reduce((n, c) => n + c.items.filter((x) => String(x || "").trim() !== "").length, 0);
      if (cats.length < 2) {
        issues.push(issue("M080", "error", `${path}.categories`, "'categorize' needs >= 2 categories, each with 'name' and non-empty 'items'"));
      } else if (totalItems < 2) {
        issues.push(issue("M081", "error", `${path}.categories`, "'categorize' needs >= 2 items total"));
      }
      break;
    }
    case "flashcard": {
      const cards = Array.isArray(node.cards)
        ? node.cards.filter((c) => c && hasText(c.front) && hasText(c.back))
        : [];
      if (cards.length < 1) issues.push(issue("M090", "error", `${path}.cards`, "'flashcard' needs >= 1 card, each with non-empty 'front' and 'back'"));
      break;
    }
    case "dictation":
      if (!hasText(node.text)) issues.push(issue("M100", "error", `${path}.text`, "'dictation' requires 'text' (the sentence/word to be read aloud and typed)"));
      break;
    case "audio_choice": {
      const options = Array.isArray(node.options) ? node.options.filter((o) => String(o || "").trim() !== "") : [];
      if (!hasText(node.audioText)) issues.push(issue("M110", "error", `${path}.audioText`, "'audio_choice' requires 'audioText' (what is spoken)"));
      if (options.length < 2) issues.push(issue("M111", "error", `${path}.options`, "'audio_choice' needs >= 2 options"));
      if (!hasText(node.answer)) {
        issues.push(issue("M112", "error", `${path}.answer`, "'audio_choice' requires 'answer'"));
      } else if (options.length && !options.some((o) => String(o).trim().toLowerCase() === String(node.answer).trim().toLowerCase())) {
        issues.push(issue("M113", "error", `${path}.answer`, `answer '${node.answer}' must match one of options`));
      }
      // 只查屏显 prompt（audioText 是朗读内容、天然等于答案，不算泄漏）。
      if (answerLeaksInText(node.prompt, node.answer)) {
        issues.push(issue("M114", "error", `${path}.prompt`, "'audio_choice' prompt leaks the answer verbatim; rephrase the prompt without the answer"));
      }
      if (options.length >= 2 && duplicatedOptions(options)) {
        issues.push(issue("M115", "error", `${path}.options`, "'audio_choice' options must be distinct"));
      }
      break;
    }
    case "speak_score": {
      const prompts = Array.isArray(node.prompts) ? node.prompts.filter((p) => String(p || "").trim() !== "") : [];
      if (!hasText(node.text) && prompts.length === 0) {
        issues.push(issue("M120", "error", path, "'speak_score' needs 'text' or non-empty 'prompts' (what to read aloud)"));
      }
      break;
    }
    case "roleplay_turn":
      if (!hasText(node.scenario)) {
        issues.push(issue("M140", "error", `${path}.scenario`, "'roleplay_turn' requires 'scenario' (who the AI plays + the situation)"));
      }
      break;
    case "ai_hint": {
      const hints = Array.isArray(node.hints) ? node.hints.filter((h) => String(h || "").trim() !== "") : [];
      if (!hasText(node.prompt)) issues.push(issue("M150", "error", `${path}.prompt`, "'ai_hint' requires 'prompt' (the question)"));
      if (hints.length < 1) issues.push(issue("M151", "error", `${path}.hints`, "'ai_hint' needs >= 1 hint (shallow to deep)"));
      if (!hasText(node.answer)) issues.push(issue("M152", "error", `${path}.answer`, "'ai_hint' requires 'answer' (revealed last)"));
      break;
    }
    case "highlight_span": {
      const targets = Array.isArray(node.answers) ? node.answers.filter((a) => String(a || "").trim() !== "") : [];
      if (!hasText(node.prompt)) issues.push(issue("M160", "error", `${path}.prompt`, "'highlight_span' requires 'prompt' (what to select, e.g. all verbs)"));
      if (!hasText(node.text)) issues.push(issue("M161", "error", `${path}.text`, "'highlight_span' requires 'text' (the passage to select within)"));
      if (targets.length < 1) issues.push(issue("M162", "error", `${path}.answers`, "'highlight_span' needs >= 1 target word in 'answers'"));
      if (hasText(node.text)) {
        targets.forEach((a) => {
          if (!highlightPhraseFindable(node.text, a)) {
            issues.push(issue("M163", "error", `${path}.answers`, `answer '${a}' does not appear in 'text' (answers must be words/phrases taken verbatim from the passage)`));
          }
        });
      }
      break;
    }
    case "timed_challenge": {
      const opts = Array.isArray(node.options) ? node.options.filter((o) => String(o || "").trim() !== "") : [];
      if (!hasText(node.prompt)) issues.push(issue("M170", "error", `${path}.prompt`, "'timed_challenge' requires 'prompt' (the question)"));
      if (opts.length < 2) issues.push(issue("M171", "error", `${path}.options`, "'timed_challenge' needs >= 2 options"));
      if (!hasText(node.answer)) {
        issues.push(issue("M172", "error", `${path}.answer`, "'timed_challenge' requires 'answer'"));
      } else if (opts.length && !opts.some((o) => String(o).trim().toLowerCase() === String(node.answer).trim().toLowerCase())) {
        issues.push(issue("M173", "error", `${path}.answer`, `answer '${node.answer}' must match one of options`));
      }
      if (answerLeaksInText(node.prompt, node.answer)) {
        issues.push(issue("M174", "error", `${path}.prompt`, "'timed_challenge' prompt leaks the answer verbatim; rephrase the prompt without the answer"));
      }
      if (opts.length >= 2 && duplicatedOptions(opts)) {
        issues.push(issue("M175", "error", `${path}.options`, "'timed_challenge' options must be distinct"));
      }
      break;
    }
    case "table": {
      const headers = Array.isArray(node.headers) ? node.headers.filter((h) => String(h || "").trim() !== "") : [];
      const rows = Array.isArray(node.rows) ? node.rows.filter((r) => Array.isArray(r) && r.some((c) => String(c || "").trim() !== "")) : [];
      if (headers.length < 1) issues.push(issue("M180", "error", `${path}.headers`, "'table' needs >= 1 column header"));
      if (rows.length < 1) issues.push(issue("M181", "error", `${path}.rows`, "'table' needs >= 1 row (each an array of cells)"));
      break;
    }
    case "sentence_diagram": {
      const items = Array.isArray(node.items) ? node.items.filter((it) => it && hasText(it.text) && hasText(it.label)) : [];
      if (items.length < 2) issues.push(issue("M190", "error", `${path}.items`, "'sentence_diagram' needs >= 2 items, each with 'text' and its grammatical 'label'"));
      break;
    }
    case "cloze_drag": {
      const blanks = (String(node.text || "").match(/_{2,}/g) || []).length;
      const bank = Array.isArray(node.bank) ? node.bank.filter((b) => String(b || "").trim() !== "") : [];
      const answers = Array.isArray(node.answers) ? node.answers.filter((a) => String(a || "").trim() !== "") : [];
      if (blanks < 1) issues.push(issue("M130", "error", `${path}.text`, "'cloze_drag' text needs >= 1 blank marked with ___"));
      if (answers.length < 1) issues.push(issue("M131", "error", `${path}.answers`, "'cloze_drag' needs 'answers' (correct word per blank, in order)"));
      if (bank.length < 2) issues.push(issue("M132", "error", `${path}.bank`, "'cloze_drag' needs a word 'bank' with >= 2 words"));
      if (blanks >= 1 && answers.length >= 1 && answers.length !== blanks) {
        issues.push(issue("M133", "error", `${path}.answers`, `'cloze_drag' has ${blanks} blank(s) ___ but ${answers.length} answer(s); need exactly one answer per blank, in order`));
      }
      break;
    }
    case "word_scramble": {
      const letters = String(node.word || "").replace(/\s+/g, "");
      if (!hasText(node.word)) {
        issues.push(issue("M200", "error", `${path}.word`, "'word_scramble' requires 'word' (the target word to spell)"));
      } else if (letters.length < 2) {
        issues.push(issue("M201", "error", `${path}.word`, "'word_scramble' 'word' needs >= 2 letters to scramble"));
      }
      break;
    }
    case "true_false": {
      const statements = Array.isArray(node.statements)
        ? node.statements.filter((s) => s && hasText(s.text) && hasText(s.answer))
        : [];
      if (statements.length < 1) {
        issues.push(issue("M210", "error", `${path}.statements`, "'true_false' needs >= 1 statement, each with non-empty 'text' and a true/false 'answer'"));
      }
      break;
    }
    case "fill_table": {
      const headers = Array.isArray(node.headers) ? node.headers.filter((h) => String(h || "").trim() !== "") : [];
      const allRows = Array.isArray(node.rows) ? node.rows : [];
      const nonEmptyRows = allRows.filter((r) => Array.isArray(r) && r.some((c) => String(c || "").trim() !== ""));
      const validBlanks = (Array.isArray(node.blanks) ? node.blanks : []).filter((b) =>
        Array.isArray(b) && b.length >= 2 && Number.isInteger(b[0]) && Number.isInteger(b[1]) &&
        Array.isArray(allRows[b[0]]) && String(allRows[b[0]][b[1]] == null ? "" : allRows[b[0]][b[1]]).trim() !== ""
      );
      if (headers.length < 1) issues.push(issue("M220", "error", `${path}.headers`, "'fill_table' needs >= 1 column header"));
      if (nonEmptyRows.length < 1) issues.push(issue("M221", "error", `${path}.rows`, "'fill_table' needs >= 1 full row (array of cells)"));
      if (validBlanks.length < 1) issues.push(issue("M222", "error", `${path}.blanks`, "'fill_table' needs >= 1 valid blank [row,col] pointing to a non-empty cell"));
      break;
    }
    case "pairs_memory": {
      const pairs = Array.isArray(node.pairs)
        ? node.pairs.filter((p) => p && hasText(p.left) && hasText(p.right))
        : [];
      if (pairs.length < 2) issues.push(issue("M230", "error", `${path}.pairs`, "'pairs_memory' needs >= 2 pairs, each with non-empty 'left' and 'right'"));
      break;
    }
    case "cloze_select": {
      const blankMarks = (String(node.text || "").match(/_{2,}/g) || []).length;
      const rawBlanks = Array.isArray(node.blanks) ? node.blanks : [];
      const withOptions = rawBlanks.map((b) => ({
        options: Array.isArray(b && b.options) ? b.options.filter((o) => String(o || "").trim() !== "") : [],
        answer: b && b.answer
      }));
      const validBlanks = withOptions.filter((b) =>
        b.options.length >= 2 && hasText(b.answer) &&
        b.options.some((o) => String(o).trim().toLowerCase() === String(b.answer).trim().toLowerCase())
      );
      if (blankMarks < 1) issues.push(issue("M240", "error", `${path}.text`, "'cloze_select' text needs >= 1 blank marked with ___"));
      if (validBlanks.length < 1) issues.push(issue("M241", "error", `${path}.blanks`, "'cloze_select' needs >= 1 blank, each with >= 2 options"));
      if (blankMarks >= 1 && rawBlanks.length >= 1 && rawBlanks.length !== blankMarks) {
        issues.push(issue("M243", "error", `${path}.blanks`, `'cloze_select' has ${blankMarks} blank(s) ___ but ${rawBlanks.length} blank spec(s); need exactly one blank spec per ___, in order`));
      }
      withOptions.forEach((b, i) => {
        if (b.options.length >= 2 && hasText(b.answer) && !b.options.some((o) => String(o).trim().toLowerCase() === String(b.answer).trim().toLowerCase())) {
          issues.push(issue("M242", "error", `${path}.blanks[${i}].answer`, `blank answer '${b.answer}' must match one of its options`));
        }
        if (b.options.length >= 2 && duplicatedOptions(b.options)) {
          issues.push(issue("M244", "error", `${path}.blanks[${i}].options`, "'cloze_select' blank options must be distinct"));
        }
      });
      break;
    }
    case "listen_fill": {
      const blanks = (String(node.text || "").match(/_{2,}/g) || []).length;
      const bank = Array.isArray(node.bank) ? node.bank.filter((b) => String(b || "").trim() !== "") : [];
      const answers = Array.isArray(node.answers) ? node.answers.filter((a) => String(a || "").trim() !== "") : [];
      if (!hasText(node.audioText)) issues.push(issue("M250", "error", `${path}.audioText`, "'listen_fill' requires 'audioText' (the full sentence to read aloud)"));
      if (blanks < 1) issues.push(issue("M251", "error", `${path}.text`, "'listen_fill' text needs >= 1 blank marked with ___"));
      if (answers.length < 1) issues.push(issue("M252", "error", `${path}.answers`, "'listen_fill' needs 'answers' (correct word per blank, in order)"));
      if (bank.length < 2) issues.push(issue("M253", "error", `${path}.bank`, "'listen_fill' needs a word 'bank' with >= 2 words"));
      if (blanks >= 1 && answers.length >= 1 && answers.length !== blanks) {
        issues.push(issue("M254", "error", `${path}.answers`, `'listen_fill' has ${blanks} blank(s) ___ but ${answers.length} answer(s); need exactly one answer per blank, in order`));
      }
      break;
    }
    case "word_formation": {
      const items = Array.isArray(node.items)
        ? node.items.filter((it) => it && hasText(it.base) && hasText(it.answer))
        : [];
      if (items.length < 1) issues.push(issue("M260", "error", `${path}.items`, "'word_formation' needs >= 1 item, each with 'base' and 'answer' (the derived form)"));
      break;
    }
    case "timeline": {
      const events = Array.isArray(node.events)
        ? node.events.filter((e) => e && hasText(e.title))
        : [];
      if (events.length < 2) issues.push(issue("M270", "error", `${path}.events`, "'timeline' needs >= 2 events, each with a non-empty 'title'"));
      break;
    }
    case "stress_mark": {
      const syllables = Array.isArray(node.syllables) ? node.syllables.filter((s) => String(s || "").trim() !== "") : [];
      const stress = Number(node.stress);
      if (syllables.length < 2) {
        issues.push(issue("M280", "error", `${path}.syllables`, "'stress_mark' needs >= 2 'syllables' to choose the stressed one"));
      }
      if (!Number.isInteger(stress) || stress < 1 || stress > syllables.length) {
        issues.push(issue("M281", "error", `${path}.stress`, "'stress_mark' 'stress' must be the 1-based index of the stressed syllable (within range)"));
      }
      break;
    }
    case "reorder_paragraph": {
      const sentences = Array.isArray(node.sentences)
        ? node.sentences.map((s) => String(s == null ? "" : s).trim()).filter((s) => s !== "")
        : [];
      const distinct = new Set(sentences.map((s) => s.toLowerCase()));
      if (sentences.length < 2) {
        issues.push(issue("M290", "error", `${path}.sentences`, "'reorder_paragraph' needs >= 2 non-empty 'sentences' (given in the correct order)"));
      } else if (distinct.size < sentences.length) {
        issues.push(issue("M291", "error", `${path}.sentences`, "'reorder_paragraph' 'sentences' must be distinct (duplicate sentences make the order ambiguous)"));
      }
      break;
    }
    case "odd_one_out": {
      const items = Array.isArray(node.items) ? node.items.filter((o) => String(o || "").trim() !== "") : [];
      if (items.length < 3) {
        issues.push(issue("M300", "error", `${path}.items`, "'odd_one_out' needs >= 3 items (a group plus the odd one)"));
      }
      if (!hasText(node.answer)) {
        issues.push(issue("M301", "error", `${path}.answer`, "'odd_one_out' requires 'answer' (the item that does not belong)"));
      } else if (items.length && !items.some((o) => String(o).trim().toLowerCase() === String(node.answer).trim().toLowerCase())) {
        issues.push(issue("M301", "error", `${path}.answer`, `answer '${node.answer}' must match one of items`));
      }
      if (items.length >= 3 && duplicatedOptions(items)) {
        issues.push(issue("M302", "error", `${path}.items`, "'odd_one_out' items must be distinct (duplicates make the odd one ambiguous)"));
      }
      break;
    }
    case "rank_order": {
      const items = Array.isArray(node.items)
        ? node.items.map((o) => String(o == null ? "" : o).trim()).filter((o) => o !== "")
        : [];
      const distinct = new Set(items.map((o) => o.toLowerCase()));
      if (items.length < 3) {
        issues.push(issue("M310", "error", `${path}.items`, "'rank_order' needs >= 3 items (given low-to-high in the correct order) to rank"));
      } else if (distinct.size < items.length) {
        issues.push(issue("M311", "error", `${path}.items`, "'rank_order' 'items' must be distinct (duplicate items make the ranking ambiguous)"));
      }
      break;
    }
    case "spelling_bee": {
      const letters = String(node.word || "").replace(/\s+/g, "");
      if (!hasText(node.word)) {
        issues.push(issue("M320", "error", `${path}.word`, "'spelling_bee' requires 'word' (the target word to spell from audio)"));
      } else if (letters.length < 2) {
        issues.push(issue("M321", "error", `${path}.word`, "'spelling_bee' 'word' needs >= 2 letters to spell"));
      }
      break;
    }
    case "sentence_transform": {
      if (!hasText(node.source)) issues.push(issue("M330", "error", `${path}.source`, "'sentence_transform' requires 'source' (the original sentence to rewrite)"));
      if (!hasText(node.answer)) issues.push(issue("M331", "error", `${path}.answer`, "'sentence_transform' requires 'answer' (the correct transformed sentence)"));
      break;
    }
    case "open_cloze": {
      const blanks = (String(node.text || "").match(/_{2,}/g) || []).length;
      const answers = Array.isArray(node.answers) ? node.answers.filter((a) => String(a || "").trim() !== "") : [];
      if (blanks < 1) issues.push(issue("M340", "error", `${path}.text`, "'open_cloze' text needs >= 1 blank marked with ___"));
      if (answers.length < 1) issues.push(issue("M341", "error", `${path}.answers`, "'open_cloze' needs 'answers' (correct word per blank, in order; no options/bank given)"));
      if (blanks >= 1 && answers.length >= 1 && answers.length !== blanks) {
        issues.push(issue("M342", "error", `${path}.answers`, `'open_cloze' has ${blanks} blank(s) ___ but ${answers.length} answer(s); need exactly one answer per blank, in order`));
      }
      break;
    }
    case "translate": {
      if (!hasText(node.source)) issues.push(issue("M350", "error", `${path}.source`, "'translate' requires 'source' (the sentence to translate)"));
      if (!hasText(node.answer)) issues.push(issue("M351", "error", `${path}.answer`, "'translate' requires 'answer' (the reference translation)"));
      break;
    }
    case "tfng": {
      const statements = Array.isArray(node.statements)
        ? node.statements.filter((s) => s && hasText(s.text) && hasText(s.answer))
        : [];
      if (statements.length < 1) {
        issues.push(issue("M360", "error", `${path}.statements`, "'tfng' needs >= 1 statement, each with 'text' and an answer of true/false/not_given"));
      }
      break;
    }
    case "listen_cloze": {
      const blanks = (String(node.text || "").match(/_{2,}/g) || []).length;
      const answers = Array.isArray(node.answers) ? node.answers.filter((a) => String(a || "").trim() !== "") : [];
      if (!hasText(node.audioText)) issues.push(issue("M370", "error", `${path}.audioText`, "'listen_cloze' requires 'audioText' (the full sentence to read aloud)"));
      if (blanks < 1) issues.push(issue("M371", "error", `${path}.text`, "'listen_cloze' text needs >= 1 blank marked with ___"));
      if (answers.length < 1) issues.push(issue("M372", "error", `${path}.answers`, "'listen_cloze' needs 'answers' (correct word per blank, in order; typed, no bank)"));
      if (blanks >= 1 && answers.length >= 1 && answers.length !== blanks) {
        issues.push(issue("M373", "error", `${path}.answers`, `'listen_cloze' has ${blanks} blank(s) ___ but ${answers.length} answer(s); need exactly one answer per blank, in order`));
      }
      break;
    }
    case "error_correction": {
      if (!hasText(node.sentence)) issues.push(issue("M380", "error", `${path}.sentence`, "'error_correction' requires 'sentence' (the sentence containing an error)"));
      if (!hasText(node.answer)) issues.push(issue("M381", "error", `${path}.answer`, "'error_correction' requires 'answer' (the corrected sentence)"));
      break;
    }
    case "dialogue_complete": {
      const turns = Array.isArray(node.turns) ? node.turns.filter((t) => t && hasText(t.text)) : [];
      const options = Array.isArray(node.options) ? node.options.filter((o) => String(o || "").trim() !== "") : [];
      if (turns.length < 1) issues.push(issue("M390", "error", `${path}.turns`, "'dialogue_complete' needs >= 1 dialogue turn, each with 'text' (and usually 'speaker')"));
      if (options.length < 2) issues.push(issue("M391", "error", `${path}.options`, "'dialogue_complete' needs >= 2 options for the missing line"));
      if (!hasText(node.answer)) {
        issues.push(issue("M392", "error", `${path}.answer`, "'dialogue_complete' requires 'answer' (the correct option)"));
      } else if (options.length && !options.some((o) => String(o).trim().toLowerCase() === String(node.answer).trim().toLowerCase())) {
        issues.push(issue("M392", "error", `${path}.answer`, `answer '${node.answer}' must match one of options`));
      }
      // 缺句答案不能已经出现在对话正文里（等于把答案摆在题面上）。
      const turnsText = turns.map((t) => String(t.text || "")).join("\n");
      if (answerLeaksInText(node.prompt, node.answer) || answerLeaksInText(turnsText, node.answer)) {
        issues.push(issue("M393", "error", `${path}.turns`, "'dialogue_complete' answer already appears in prompt/turns; the missing line must not be shown"));
      }
      if (options.length >= 2 && duplicatedOptions(options)) {
        issues.push(issue("M394", "error", `${path}.options`, "'dialogue_complete' options must be distinct"));
      }
      break;
    }
    case "word_search": {
      const grid = Array.isArray(node.grid) ? node.grid : [];
      const rows = grid.filter((row) => Array.isArray(row) && row.length > 0);
      const cols = rows.length ? Math.max(...rows.map((row) => row.length)) : 0;
      const rectangular = rows.length >= 2 && cols >= 2 &&
        rows.every((row) => row.length === cols && row.every((c) => String(c == null ? "" : c).trim().length === 1));
      const words = Array.isArray(node.words)
        ? node.words.filter((w) => String(w || "").replace(/[^A-Za-z0-9]/g, "").length >= 2)
        : [];
      if (!rectangular) issues.push(issue("M400", "error", `${path}.grid`, "'word_search' needs a rectangular 'grid' (>= 2 rows x >= 2 cols, each cell a single letter)"));
      if (words.length < 1) issues.push(issue("M401", "error", `${path}.words`, "'word_search' needs >= 1 'words' (each >= 2 letters) to find"));
      if (rectangular) {
        words.forEach((w) => {
          if (!wordSearchFindable(grid, w)) issues.push(issue("M402", "error", `${path}.words`, `word '${w}' is not placeable in the grid (must appear in a straight line, 8 directions)`));
        });
      }
      break;
    }
    case "hangman": {
      const letters = String(node.word || "").replace(/[^A-Za-z]/g, "");
      if (!hasText(node.word)) {
        issues.push(issue("M410", "error", `${path}.word`, "'hangman' requires 'word' (the target word to guess)"));
      } else if (letters.length < 2) {
        issues.push(issue("M410", "error", `${path}.word`, "'hangman' 'word' needs >= 2 letters"));
      }
      break;
    }
    case "proof_paragraph": {
      const lines = Array.isArray(node.lines) ? node.lines.filter((l) => l && hasText(l.text)) : [];
      const withError = lines.filter((l) => hasText(l.answer) && String(l.answer).trim().toLowerCase() !== String(l.text).trim().toLowerCase());
      if (lines.length < 1) {
        issues.push(issue("M420", "error", `${path}.lines`, "'proof_paragraph' needs >= 1 line, each with 'text'"));
      } else if (withError.length < 1) {
        issues.push(issue("M421", "error", `${path}.lines`, "'proof_paragraph' needs >= 1 line whose 'answer' differs from 'text' (an actual error to correct)"));
      }
      // 端上把「无 answer 的行」判为本来就对：若该行实际有错，学生改对了反被判错。
      // 强制每行显式给 answer（无错行 answer === text），把判分口径钉死在生成侧。
      lines.forEach((l, i) => {
        if (!hasText(l.answer)) {
          issues.push(issue("M422", "error", `${path}.lines[${i}].answer`, "'proof_paragraph' every line needs an explicit 'answer' (copy 'text' verbatim when the line has no error)"));
        }
      });
      // 题面宣称的错误数（如“短文共有10处错误”“共10处”）必须与实际 answer≠text 的行数一致，
      // 否则学生会为不存在的错误反复找茬。只认阿拉伯数字、排除“每行…N处”类分布性描述，压误报。
      const promptText = String(node.prompt || "");
      const claim = promptText.match(/(?:共有?|总共|合计)\s*(\d+)\s*[处个]|有\s*(\d+)\s*处错/);
      if (claim && lines.length && !/每行[^，。,;；]{0,8}$/.test(promptText.slice(0, claim.index))) {
        const n = Number(claim[1] || claim[2]);
        if (n > 0 && withError.length !== n) {
          issues.push(issue("M423", "error", `${path}.prompt`, `prompt claims ${n} errors but ${withError.length} lines differ from their 'answer'; make them match (adjust the prompt or the lines)`));
        }
      }
      break;
    }
    case "writing": {
      if (!hasText(node.prompt)) {
        issues.push(issue("M460", "error", `${path}.prompt`, "'writing' requires 'prompt' (the writing task: topic, word count, required points)"));
      }
      break;
    }
    case "monologue": {
      if (!hasText(node.prompt)) {
        issues.push(issue("M470", "error", `${path}.prompt`, "'monologue' requires 'prompt' (the speaking task)"));
      }
      break;
    }
    case "shadowing": {
      if (!hasText(node.text)) {
        issues.push(issue("M480", "error", `${path}.text`, "'shadowing' requires 'text' (the model sentence to read aloud and shadow)"));
      }
      break;
    }
    case "minimal_pair": {
      const options = Array.isArray(node.options) ? node.options.filter((o) => String(o || "").trim() !== "") : [];
      const ipa = Array.isArray(node.ipa) ? node.ipa : [];
      if (!hasText(node.audioText)) issues.push(issue("M490", "error", `${path}.audioText`, "'minimal_pair' requires 'audioText' (the spoken word, i.e. the answer word)"));
      if (options.length < 2) issues.push(issue("M491", "error", `${path}.options`, "'minimal_pair' needs >= 2 near-homophone options"));
      if (!hasText(node.answer)) {
        issues.push(issue("M492", "error", `${path}.answer`, "'minimal_pair' requires 'answer'"));
      } else if (options.length && !options.some((o) => String(o).trim().toLowerCase() === String(node.answer).trim().toLowerCase())) {
        issues.push(issue("M493", "error", `${path}.answer`, `answer '${node.answer}' must match one of options`));
      }
      // 只查屏显 prompt（audioText 是朗读内容、天然等于答案，不算泄漏）。
      if (answerLeaksInText(node.prompt, node.answer)) {
        issues.push(issue("M494", "error", `${path}.prompt`, "'minimal_pair' prompt leaks the answer verbatim; rephrase the prompt without the answer"));
      }
      if (options.length >= 2 && duplicatedOptions(options)) {
        issues.push(issue("M495", "error", `${path}.options`, "'minimal_pair' options must be distinct"));
      }
      if (ipa.length && options.length && ipa.length !== (Array.isArray(node.options) ? node.options.length : 0)) {
        issues.push(issue("M496", "warning", `${path}.ipa`, `'minimal_pair' 'ipa' has ${ipa.length} entries but 'options' has ${(node.options || []).length}; they align by position, so give one IPA per option (or omit 'ipa')`));
      }
      if (hasText(node.audioText) && hasText(node.answer) &&
        !String(node.audioText).toLowerCase().includes(String(node.answer).trim().toLowerCase())) {
        issues.push(issue("M497", "error", `${path}.audioText`, `'minimal_pair' audioText must speak the answer word '${node.answer}' (the user picks what they heard)`));
      }
      break;
    }
    case "ipa_read": {
      const options = Array.isArray(node.options) ? node.options.filter((o) => String(o || "").trim() !== "") : [];
      if (!hasText(node.symbol)) issues.push(issue("M510", "error", `${path}.symbol`, "'ipa_read' requires 'symbol' (the IPA symbol, e.g. /i:/)"));
      if (options.length < 2) issues.push(issue("M511", "error", `${path}.options`, "'ipa_read' needs >= 2 candidate words"));
      if (!hasText(node.answer)) {
        issues.push(issue("M512", "error", `${path}.answer`, "'ipa_read' requires 'answer' (the word containing the sound)"));
      } else if (options.length && !options.some((o) => String(o).trim().toLowerCase() === String(node.answer).trim().toLowerCase())) {
        issues.push(issue("M513", "error", `${path}.answer`, `answer '${node.answer}' must match one of options`));
      }
      if (answerLeaksInText(node.prompt, node.answer)) {
        issues.push(issue("M514", "error", `${path}.prompt`, "'ipa_read' prompt leaks the answer verbatim; rephrase the prompt without the answer"));
      }
      if (options.length >= 2 && duplicatedOptions(options)) {
        issues.push(issue("M515", "error", `${path}.options`, "'ipa_read' options must be distinct"));
      }
      break;
    }
    case "sound_link": {
      if (!hasText(node.text)) {
        issues.push(issue("M520", "error", `${path}.text`, "'sound_link' requires 'text' (the sentence to read aloud with linking/weak forms)"));
      }
      break;
    }
    case "map_label": {
      const options = Array.isArray(node.options) ? node.options.filter((o) => String(o || "").trim() !== "") : [];
      const items = Array.isArray(node.items)
        ? node.items.filter((it) => it && hasText(it.text) && hasText(it.answer))
        : [];
      if (!hasText(node.audioText)) issues.push(issue("M530", "error", `${path}.audioText`, "'map_label' requires 'audioText' (the spoken description locating each place)"));
      if (options.length < 2) issues.push(issue("M531", "error", `${path}.options`, "'map_label' needs >= 2 position labels in 'options'"));
      if (items.length < 1) issues.push(issue("M532", "error", `${path}.items`, "'map_label' needs >= 1 item, each with 'text' (the place) and its 'answer' position label"));
      if (options.length) {
        items.forEach((it, i) => {
          if (!options.some((o) => String(o).trim().toLowerCase() === String(it.answer).trim().toLowerCase())) {
            issues.push(issue("M533", "error", `${path}.items[${i}].answer`, `item answer '${it.answer}' must match one of 'options'`));
          }
        });
      }
      if (options.length >= 2 && duplicatedOptions(options)) {
        issues.push(issue("M534", "error", `${path}.options`, "'map_label' options must be distinct"));
      }
      break;
    }
    case "note_complete": {
      const blanks = (String(node.text || "").match(/_{2,}/g) || []).length;
      const answers = Array.isArray(node.answers) ? node.answers.filter((a) => String(a || "").trim() !== "") : [];
      if (!hasText(node.audioText)) issues.push(issue("M540", "error", `${path}.audioText`, "'note_complete' requires 'audioText' (the passage to read aloud)"));
      if (blanks < 1) issues.push(issue("M541", "error", `${path}.text`, "'note_complete' text needs >= 1 blank marked with ___"));
      if (answers.length < 1) issues.push(issue("M542", "error", `${path}.answers`, "'note_complete' needs 'answers' (correct word per blank, in order; typed, no bank)"));
      if (blanks >= 1 && answers.length >= 1 && answers.length !== blanks) {
        issues.push(issue("M543", "error", `${path}.answers`, `'note_complete' has ${blanks} blank(s) ___ but ${answers.length} answer(s); need exactly one answer per blank, in order`));
      }
      break;
    }
    case "match_sentence_endings": {
      const stems = Array.isArray(node.stems)
        ? node.stems.filter((s) => s && hasText(s.text) && hasText(s.answer))
        : [];
      const endings = Array.isArray(node.endings) ? node.endings.filter((e) => String(e || "").trim() !== "") : [];
      if (stems.length < 1) issues.push(issue("M550", "error", `${path}.stems`, "'match_sentence_endings' needs >= 1 stem, each with 'text' (sentence start) and its 'answer' ending"));
      if (endings.length < 2) issues.push(issue("M551", "error", `${path}.endings`, "'match_sentence_endings' needs >= 2 'endings' to choose from (ideally more than stems, with distractors)"));
      if (endings.length) {
        stems.forEach((s, i) => {
          if (!endings.some((e) => String(e).trim().toLowerCase() === String(s.answer).trim().toLowerCase())) {
            issues.push(issue("M552", "error", `${path}.stems[${i}].answer`, `stem answer '${s.answer}' must match one of 'endings'`));
          }
        });
      }
      if (endings.length >= 2 && duplicatedOptions(endings)) {
        issues.push(issue("M553", "error", `${path}.endings`, "'match_sentence_endings' endings must be distinct"));
      }
      break;
    }
    case "summary_complete": {
      const blanks = (String(node.text || "").match(/_{2,}/g) || []).length;
      const bank = Array.isArray(node.bank) ? node.bank.filter((b) => String(b || "").trim() !== "") : [];
      const answers = Array.isArray(node.answers) ? node.answers.filter((a) => String(a || "").trim() !== "") : [];
      if (blanks < 1) issues.push(issue("M560", "error", `${path}.text`, "'summary_complete' text needs >= 1 blank marked with ___"));
      if (answers.length < 1) issues.push(issue("M561", "error", `${path}.answers`, "'summary_complete' needs 'answers' (correct word per blank, in order)"));
      if (bank.length < 2) issues.push(issue("M562", "error", `${path}.bank`, "'summary_complete' needs a word 'bank' with >= 2 words"));
      if (blanks >= 1 && answers.length >= 1 && answers.length !== blanks) {
        issues.push(issue("M563", "error", `${path}.answers`, `'summary_complete' has ${blanks} blank(s) ___ but ${answers.length} answer(s); need exactly one answer per blank, in order`));
      }
      if (bank.length) {
        answers.forEach((a, i) => {
          if (!bank.some((b) => String(b).trim().toLowerCase() === String(a).trim().toLowerCase())) {
            issues.push(issue("M564", "error", `${path}.answers`, `answer '${a}' (blank ${i + 1}) must appear in 'bank' (users can only pick bank words)`));
          }
        });
      }
      break;
    }
    case "short_answer": {
      const questions = Array.isArray(node.questions)
        ? node.questions.filter((q) => q && hasText(q.q) && hasText(q.answer))
        : [];
      if (questions.length < 1) {
        issues.push(issue("M570", "error", `${path}.questions`, "'short_answer' needs >= 1 question, each with 'q' (the question) and 'answer' (reference answer)"));
      }
      break;
    }
    case "guided_writing": {
      if (!hasText(node.prompt)) {
        issues.push(issue("M580", "error", `${path}.prompt`, "'guided_writing' requires 'prompt' (the writing task)"));
      }
      break;
    }
    case "match_headings": {
      const paras = Array.isArray(node.paragraphs)
        ? node.paragraphs.filter((p) => p && hasText(p.text) && hasText(p.answer))
        : [];
      const headings = Array.isArray(node.headings) ? node.headings.filter((h) => String(h || "").trim() !== "") : [];
      if (paras.length < 2) issues.push(issue("M430", "error", `${path}.paragraphs`, "'match_headings' needs >= 2 paragraphs, each with 'text' and its correct 'answer' heading"));
      if (headings.length < 2) issues.push(issue("M431", "error", `${path}.headings`, "'match_headings' needs >= 2 'headings' to choose from"));
      if (headings.length) {
        paras.forEach((p, i) => {
          if (!headings.some((h) => String(h).trim().toLowerCase() === String(p.answer).trim().toLowerCase())) {
            issues.push(issue("M432", "error", `${path}.paragraphs[${i}].answer`, `paragraph answer '${p.answer}' must match one of 'headings'`));
          }
        });
      }
      break;
    }
    case "match_info": {
      const options = Array.isArray(node.options) ? node.options.filter((o) => String(o || "").trim() !== "") : [];
      const statements = Array.isArray(node.statements)
        ? node.statements.filter((s) => s && hasText(s.text) && hasText(s.answer))
        : [];
      if (options.length < 2) issues.push(issue("M440", "error", `${path}.options`, "'match_info' needs >= 2 paragraph labels in 'options'"));
      if (statements.length < 1) issues.push(issue("M441", "error", `${path}.statements`, "'match_info' needs >= 1 statement, each with 'text' and its 'answer' paragraph label"));
      if (options.length) {
        statements.forEach((s, i) => {
          if (!options.some((o) => String(o).trim().toLowerCase() === String(s.answer).trim().toLowerCase())) {
            issues.push(issue("M442", "error", `${path}.statements[${i}].answer`, `statement answer '${s.answer}' must match one of 'options'`));
          }
        });
      }
      break;
    }
    default:
      break;
  }
  return issues;
}

function validateMicroCard(card) {
  const issues = [];
  const src = card && typeof card === "object" ? card : {};
  if (!Array.isArray(src.nodes)) {
    issues.push(issue("M000", "error", "nodes", "card must have a 'nodes' array"));
  }
  const nodes = Array.isArray(src.nodes) ? src.nodes : [];
  nodes.forEach((node, index) => {
    issues.push(...validateNode(node || {}, index));
  });
  // 材料共现（卡级）：tfng/match_info/short_answer 是「对照材料判断/定位/简答」题，
  // 缺 passage/text 材料用户无据可答。卡级 path 会让修复走整卡重生成（需要新增材料节点，定向修复补不了）。
  const nodeTypes = nodes.map((n) => String((n && n.type) || "").trim().toLowerCase());
  const needsMaterial = nodeTypes.some((t) => t === "tfng" || t === "match_info" || t === "short_answer");
  const hasMaterial = nodes.some((n) => {
    const t = String((n && n.type) || "").trim().toLowerCase();
    return (t === "passage" || t === "text") && hasText(n && n.text);
  });
  if (needsMaterial && !hasMaterial) {
    issues.push(issue("M501", "error", "nodes", "'tfng'/'match_info'/'short_answer' need an accompanying 'passage' or 'text' node as the source material"));
  }
  const sanitized = sanitizeMicroCard(src);
  if (sanitized.nodes.length === 0) {
    issues.push(issue("M099", "error", "nodes", "no renderable micro-elements after sanitisation"));
  }
  const errors = issues.filter((i) => i.severity === "error");
  return { ok: errors.length === 0, issues, errors, sanitized };
}

function renderReport(issues) {
  if (!issues || !issues.length) return "OK: no issues";
  const counts = issues.reduce((acc, i) => {
    acc[i.severity] = (acc[i.severity] || 0) + 1;
    return acc;
  }, {});
  const header = `=== MicroCard Validation: ${counts.error || 0} errors, ${counts.warning || 0} warnings ===`;
  const lines = issues.map((i) => `[${i.code}] [${i.severity}] ${i.path}: ${i.message}`);
  return [header, ...lines].join("\n");
}

module.exports = { validateMicroCard, renderReport, validateNode };
