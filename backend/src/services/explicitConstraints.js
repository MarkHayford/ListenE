// 从用户消息里推断「显式指定的练习约束/目标」：选择题选项与正解、选词填空空格与词、
// 翻译句与术语、听力线索、最小对立对、口语提示主题、题量/卡片项数等。一组保守正则解析的
// 纯函数。历史上内联在 mimoText.js（1 万行），现抽成独立模块——大文件拆分的又一刀(大簇)。

const { compactAgentCardText, escapeRegExp, sanitizeAgentCardText } = require("./agentCardText");
const { inferExplicitScenarioConstraint } = require("./intent");
const { parseCountToken } = require("./listeningGenerate");
const { normalizeAgentAnswerLookupText } = require("./textNormalize");

function inferExplicitChoiceConstraint(message = "") {
  const text = sanitizeAgentCardText(message).replace(/\s+/g, " ").trim();
  if (!text) return null;
  const optionsMatch = /(?:options?|选项)\s*[:：]?\s*(.+?)(?=\s+(?:answer|correct\s+answer|答案|正确答案)\b|$)/i.exec(text);
  const answerMatch = /(?:answer|correct\s+answer|答案|正确答案)\s*[:：]?\s*(.+?)(?=\s+(?:only(?:\s+choice)?|no\s+|without\s+|不要|不需要|无|only\s+core|核心)|$)/i.exec(text);
  if (!optionsMatch || !answerMatch) return null;
  const answer = cleanExplicitChoiceTerm(compactAgentCardText(
    answerMatch[1].replace(/\s+(?:only(?:\s+choice)?|choice|card|no\s+[\s\S]*)$/i, ""),
    120
  ));
  const options = parseExplicitChoiceOptions(optionsMatch[1], answer);
  if (!answer || options.length < 2) return null;
  const beforeOptions = text.slice(0, optionsMatch.index).trim();
  const target = inferExplicitChoiceTarget(beforeOptions);
  if (!target) return null;
  const normalizedAnswer = normalizeAgentAnswerLookupText(answer);
  const finalOptions = options.some((item) => normalizeAgentAnswerLookupText(item) === normalizedAnswer)
    ? options
    : [answer, ...options].slice(0, 4);
  return {
    target,
    options: finalOptions.slice(0, 4),
    answer
  };
}

function inferExplicitChoiceTarget(beforeOptions = "") {
  const text = compactAgentCardText(beforeOptions, 220);
  const chineseMeaningMatch = /(?:词义选择|含义选择|意思选择)\s*[:：-]?\s*(.+?)$/i.exec(text);
  if (chineseMeaningMatch?.[1]) {
    const target = cleanExplicitChoiceTerm(compactAgentCardText(
      chineseMeaningMatch[1].replace(/^["'“”‘’]+|["'“”‘’?.]+$/g, ""),
      48
    ));
    if (target) return target;
  }
  const questionMatch = /(?:question|题目|问题)\s+(?:what\s+does\s+)?(.+?)(?:\s+mean[?.]?|\s+means?[?.]?|是什么意思|的意思是什么)\s*$/i.exec(text);
  if (questionMatch?.[1]) {
    return cleanExplicitChoiceTerm(compactAgentCardText(
      questionMatch[1].replace(/^["'“”‘’]+|["'“”‘’?.]+$/g, ""),
      48
    ));
  }
  const directQuestionMatch = /what\s+does\s+(.+?)\s+mean[?.]?$/i.exec(text);
  if (directQuestionMatch?.[1]) {
    return cleanExplicitChoiceTerm(compactAgentCardText(
      directQuestionMatch[1].replace(/^["'“”‘’]+|["'“”‘’?.]+$/g, ""),
      48
    ));
  }
  const leadingMeaningMatch = /(?:word\s+)?meaning\s+choice(?:\s+(?:card|practice))?\s*[:：-]?\s*(.+?)(?:\s+means?[?.]?|\s+是什么意思|\s+的意思是什么)?$/i.exec(text);
  if (leadingMeaningMatch?.[1]) {
    const target = cleanExplicitChoiceTerm(compactAgentCardText(
      leadingMeaningMatch[1]
        .replace(/^(?:card|practice)\s+/i, "")
        .replace(/^(?:for|about|regarding|on|word|关于|针对|给|为)\s+/i, "")
        .replace(/^["'“”‘’]+|["'“”‘’?.]+$/g, ""),
      48
    ));
    if (target && !/^(?:card|practice|choice|meaning)$/i.test(target)) return target;
  }
  const targetMatch = /(?:meaning\s+choice\s+card\s+for|choice\s+card\s+for|card\s+for|for|关于|针对|给|为)\s+(.+)$/i.exec(text);
  return cleanExplicitChoiceTerm(compactAgentCardText(
    (targetMatch?.[1] || "")
      .replace(/^word\s+/i, "")
      .replace(/^["'“”‘’]+|["'“”‘’]+$/g, ""),
    48
  ));
}

function inferExplicitClozeConstraint(message = "", requestedCount = 0) {
  const text = sanitizeAgentCardText(message).replace(/\s+/g, " ").trim();
  if (!text || !/cloze|blank|fill|填空|空格|挖空|完形/i.test(text)) return null;
  const markedSentence = lastAgentCardRegexGroup(text, /(?:from\s+this\s+sentence|using\s+sentence|use\s+sentence|句子)\s*[:：]?\s*(.+?)(?=\s+(?:(?:exactly\s+)?[0-9一二两三四五六七八九十]+(?:\s+\w+){0,2}\s+blanks?|options?|选项|answers?|correct\s+answers?|答案|正确答案|using|use|only|no\s+|without\s+|不要|不需要|无|$))/gi);
  const cardSentence = lastAgentCardRegexGroup(text, /(?:填空卡|填空题|cloze\s*card)\s*[:：]\s*(.+?)(?=\s*(?:options?|选项|answers?|correct\s+answers?|answer|correct\s+answer|答案|正确答案|only|no\s+|without\s+|不要|不需要|无|；|;|$))/gi);
  const genericSentence = lastAgentCardRegexGroup(text, /(?:sentence)\s*[:：]?\s*(.+?)(?=\s+(?:(?:exactly\s+)?[0-9一二两三四五六七八九十]+(?:\s+\w+){0,2}\s+blanks?|options?|选项|answers?|correct\s+answers?|答案|正确答案|using|use|only|no\s+|without\s+|不要|不需要|无|$))/gi);
  const textSentence = lastAgentCardRegexGroup(text, /(?:text|prompt|题干|文本)\s*[:：]\s*(.+?)(?=\s+(?:options?|选项|answers?|correct\s+answers?|answer|correct\s+answer|答案|正确答案|only|no\s+|without\s+|不要|不需要|无|$))/gi);
  const chineseSentence = lastAgentCardRegexGroup(text, /(?:句子|原句)\s*(?:是|为|：|:)\s*(.+?)(?=\s*(?:挖空答案|空格答案|答案依次|答案顺序|answers?|correct\s+answers?|选项|options?|只要|不要|不需要|无|$))/gi);
  const usingValue = lastAgentCardRegexGroup(text, /(?:using|use|使用|用)\s+(.+?)(?=\s+(?:only|just|no\s+|without\s+|不要|不需要|无|core|核心|cards?|modules?|examples?|grammar|vocab(?:ulary)?|extra|$))/gi);
  const optionsValue = lastAgentCardRegexGroup(text, /(?:options?\s*(?:[:：]\s*|\s+)|选项\s*(?:是|为|[:：])?\s*)(.+?)(?=(?:\s|[;；。])(?:answer|correct\s+answer|答案|正确答案|只要|only|no\s+|without\s+|不要|不需要|无|$)|[。.!?！？]?\s*$)/gi);
  const answerValue = lastAgentCardRegexGroup(text, /(?:answers|correct\s+answers?|答案(?:依次|顺序)?(?:是|为)?|正确答案)\s*[:：]?\s*(.+?)(?=(?:\s|[;；。])(?:options?|选项|只要|only|no\s+|without\s+|不要|不需要|无|$))/gi) ||
    lastAgentCardRegexGroup(text, /(?:answer|correct\s+answer)\s*[:：]?\s*(.+?)(?=(?:\s|[;；])(?:options?|选项|only|no\s+|without\s+|不要|不需要|无|$))/gi);
  const sourceSentence = compactAgentCardText(cleanExplicitClozeSourceSentence(chineseSentence || markedSentence || cardSentence || genericSentence || textSentence), agentCardComponentTextMaxLength("cloze"));
  const fromUsing = /^\s*(?:sentence|句子)\b/i.test(usingValue) ? [] : parseExplicitClozeTerms(usingValue, requestedCount);
  const fromOptions = parseExplicitClozeTerms(optionsValue, 0);
  const fromAnswer = parseExplicitClozeTerms(answerValue, requestedCount);
  const answerParts = fromAnswer.length ? fromAnswer : fromUsing;
  const options = fromOptions.length ? fromOptions : fromUsing;
  if (!answerParts.length && !options.length && !sourceSentence) return null;
  return { answerParts, options, sourceSentence, strict: answerParts.length > 0 || options.length > 0 };
}

function lastAgentCardRegexGroup(text = "", pattern) {
  let value = "";
  for (const match of String(text || "").matchAll(pattern)) {
    if (match?.[1]) value = match[1];
  }
  return value;
}

function cleanExplicitClozeSourceSentence(value = "") {
  return sanitizeAgentCardText(value)
    .replace(/^(?:是|为|:|：)\s*/i, "")
    .replace(/\s+(?:exactly\s+)?[0-9一二两三四五六七八九十]+\s+(?:\w+\s+){0,2}blanks?\b[\s\S]*$/i, "")
    .replace(/\s*(?:挖空答案|空格答案|答案依次|答案顺序|选项是|选项为|选项[:：]|using|use|options?|answers?|correct\s+answers?|only|no\s+|without)[\s\S]*$/i, "")
    .trim();
}

function parseExplicitClozeTerms(value = "", requestedCount = 0) {
  const raw = sanitizeAgentCardText(value)
    .replace(/["'“”‘’`]+/g, " ")
    .replace(/^(?:是|为|:|：)\s*/i, "")
    .replace(/\s*(?:only|just|只要|no\s+|without\s+|不要|不需要|无|core|核心|cards?|modules?|examples?|grammar|vocab(?:ulary)?|extra|额外)\b[\s\S]*$/i, "")
    .replace(/[。.!?！？]\s*$/g, "")
    .trim();
  if (!raw) return [];
  const delimited = raw.split(/\s*(?:\||\/|,|，|、|;|；)\s*/).filter(Boolean);
  const candidates = delimited.length >= 2 ? delimited : raw.split(/\s+/);
  const stopWords = new Set([
    "and", "or", "only", "just", "with", "using", "use", "words", "word", "forms", "form",
    "cloze", "blank", "blanks", "fill", "card", "cards"
  ]);
  const terms = candidates
    .map((item) => item.replace(/^[^A-Za-z0-9]+|[^A-Za-z0-9]+$/g, "").trim())
    .filter((item) => item && !stopWords.has(item.toLowerCase()));
  const limit = requestedCount && requestedCount > 0 ? requestedCount : 8;
  return uniqueAgentCardTexts(terms, 80).slice(0, limit);
}

function inferExplicitTranslationConstraint(message = "") {
  const text = sanitizeAgentCardText(message).replace(/\s+/g, " ").trim();
  const baTranslateMatch = /\u628a\s*(.+?)\s*\u7ffb\u8bd1(?:\u6210|\u4e3a)?(?:\u81ea\u7136)?(?:\u4e2d\u6587|\u6c49\u8bed|\u6f22\u8a9e|chinese)?/i.exec(text);
  if (!text || !/translation|translate|翻译|中文|chinese/i.test(text)) return null;
  const colonListMatch = /(?:translation\s+card\s+for|translate|translation\s+of|for)\s+(?:exactly\s+)?(?:these\s+)?(?:[0-9]+|one|two|three|four|five|six|seven|eight|nine|ten|一|二|两|三|四|五|六|七|八|九|十)?\s*(?:phrases?|terms?|items?|sentences?|短语|詞組|词组|句子)?\s*[:：]\s*(.+?)(?=\s+(?:target\s+(?:chinese\s+meaning|中文|汉语)|chinese\s+meaning|into\s+chinese|to\s+chinese|中文|汉语|only\s+translation|only|no\s+|without\s+|不要|不需要|无|$))/i.exec(text);
  const targetColonMatch = /(?:translat(?:e|ing)|translation\s+card\s+(?:for\s+)?(?:translat(?:e|ing)\s+)?)\s*(?:exactly\s+)?(?:these\s+)?(?:[0-9]+|one|two|three|four|five|six|seven|eight|nine|ten|一|二|两|三|四|五|六|七|八|九|十)?\s*(?:(?:short|simple|natural|english|chinese|travel|daily|routine|b1|a1|a2|b2)\s+){0,8}(?:phrases?|terms?|items?|sentences?|短语|詞組|词组|句子)?\s+(?:into|to)\s+(?:chinese|中文|汉语|漢語)\s*[:：]\s*(.+?)(?=\s+(?:only\s+translation|only|no\s+|without\s+|不要|不需要|无|$))/i.exec(text);
  const match = /(?:translation\s+card\s+for|translate|translation\s+of|for|sentence)\s+(.+?)(?=\s+(?:target\s+(?:chinese\s+meaning|中文|汉语)|chinese\s+meaning|into\s+chinese|to\s+chinese|中文|汉语|only\s+translation|only|no\s+|without\s+|不要|不需要|无|$))/i.exec(text);
  const postTargetMatch = /(?:into|to)\s+(?:chinese|中文|汉语|漢語)\s+(.+?)(?=\s+(?:only\s+translation|only|no\s+|without\s+|不要|不需要|无|$))/i.exec(text);
  const rawMatchedSource = baTranslateMatch?.[1] || targetColonMatch?.[1] || colonListMatch?.[1] || postTargetMatch?.[1] || match?.[1] || "";
  if (!rawMatchedSource) return null;
  const requestedCount = inferRequestedAgentCardItemCounts(message).translation || 0;
  const rawSource = rawMatchedSource
    .replace(/^(?:exactly|only|just)?\s*(?:[0-9]+|one|two|three|four|five|six|seven|eight|nine|ten|一|二|两|三|四|五|六|七|八|九|十)\s+(?:sentence|sentences|phrases?|terms?|items?|句子|短语|詞組|词组)\s+/i, "")
    .replace(/^(?:sentence|phrases?|terms?|items?)\s+/i, "")
    .replace(/\s+(?:target|source)\s*$/i, "")
    .replace(/^["'“”‘’]+|["'“”‘’]+$/g, "");
  const terms = inferExplicitTranslationTerms(rawSource, requestedCount);
  const source = compactAgentCardText(terms.length ? terms[0] : rawSource, 160);
  if (!source || /^(?:card|translation|translate|exactly|only)$/i.test(source)) return null;
  return {
    source,
    terms
  };
}

function inferExplicitTranslationTerms(rawSource = "", requestedCount = 0) {
  const cleaned = sanitizeAgentCardText(rawSource)
    .replace(/\b(?:target|source|chinese|meaning|translation|translate|card)\b/gi, " ")
    .replace(/\b(?:exactly|only|just)\s*(?:[0-9]+|one|two|three|four|five|six|seven|eight|nine|ten|一|二|两|三|四|五|六|七|八|九|十)?\s*(?:phrases?|terms?|items?|sentences?)?\b/gi, " ")
    .replace(/\b(?:phrases?|terms?|items?|sentences?)\b/gi, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!cleaned) return [];
  const sentenceCandidates = requestedCount > 1
    ? splitExplicitTranslationSentences(cleaned)
    : [];
  if (sentenceCandidates.length >= Math.min(requestedCount || 2, 2)) {
    const limit = requestedCount && requestedCount > 0 ? requestedCount : 8;
    return uniqueAgentCardTexts(sentenceCandidates.map(cleanExplicitTranslationTerm), 120)
      .filter((item) => !/^(?:exactly|only|just|phrase|phrases|term|terms|item|items)$/i.test(item))
      .slice(0, limit);
  }
  const delimited = cleaned.split(/\s*(?:\u548c|\u4ee5\u53ca|\||,|，|、|;|；)\s*/).filter(Boolean);
  const candidates = delimited.length >= 2
    ? delimited
    : inferKnownTranslationTerms(cleaned);
  const terms = uniqueAgentCardTexts(candidates.map(cleanExplicitTranslationTerm), 120)
    .filter((item) => !/^(?:exactly|only|just|phrase|phrases|term|terms|item|items)$/i.test(item));
  const limit = requestedCount && requestedCount > 0 ? requestedCount : 8;
  return terms.slice(0, limit);
}

function splitExplicitTranslationSentences(value = "") {
  const raw = String(value || "");
  const semicolonParts = raw
    .split(/\s*(?:;|；)\s*/)
    .map((item) => item.trim())
    .filter((item) => /[A-Za-z\u4e00-\u9fff]/.test(item));
  if (semicolonParts.length >= 2) return semicolonParts;
  const matches = raw.match(/[^.!?。？！]+[.!?。？！]/g) || [];
  return matches
    .map((item) => item.trim())
    .filter((item) => /[A-Za-z\u4e00-\u9fff]/.test(item));
}

function inferKnownTranslationTerms(value = "") {
  const raw = sanitizeAgentCardText(value).trim();
  const normalized = normalizeAgentAnswerLookupText(raw);
  const lexicon = [
    "boarding pass",
    "window seat",
    "security check",
    "check-in counter",
    "passport control",
    "departure gate",
    "carry-on bag",
    "seat belt",
    "customs form",
    "baggage claim"
  ];
  const found = lexicon
    .map((term) => {
      const pattern = new RegExp(`(?:^|[^a-z0-9])${escapeRegExp(term).replace(/\\ /g, "\\s+")}(?=$|[^a-z0-9])`, "i");
      const match = pattern.exec(normalized);
      return match ? { term, index: match.index } : null;
    })
    .filter(Boolean)
    .sort((a, b) => a.index - b.index)
    .map((item) => item.term);
  if (found.length) return found;
  return [raw];
}

function cleanExplicitTranslationTerm(value = "") {
  return sanitizeAgentCardText(value)
    .replace(/^(?:\u548c|\u4ee5\u53ca)\s+/i, "")
    .replace(/^["'“”‘’`]+|["'“”‘’`]+$/g, "")
    .replace(/^(?:these|this)\s+(?:[0-9]+|one|two|three|four|five|six|seven|eight|nine|ten)\s*[:：]\s*/i, "")
    .replace(/^[.?!。？！,，;；:：]+|[.。,，;；:：]+$/g, "")
    .trim();
}

function parseExplicitChoiceOptions(value = "", answer = "") {
  const raw = sanitizeAgentCardText(value).trim();
  if (!raw) return [];
  const delimited = raw.split(/\s*(?:\||\/|,|，|、|;|；)\s*/).filter(Boolean);
  if (delimited.length >= 2) return uniqueAgentCardTexts(delimited.map(cleanExplicitChoiceTerm), 120).slice(0, 4);
  const normalizedRaw = normalizeAgentAnswerLookupText(raw);
  const normalizedAnswer = normalizeAgentAnswerLookupText(answer);
  if (normalizedAnswer && normalizedRaw.startsWith(normalizedAnswer)) {
    const rest = raw.slice(answer.length).trim();
    if (rest) {
      const restWords = rest.split(/\s+/).filter(Boolean);
      if (shouldSplitExplicitChoiceRestWords(restWords)) {
        return uniqueAgentCardTexts([answer, ...restWords].map(cleanExplicitChoiceTerm), 120).slice(0, 4);
      }
      return uniqueAgentCardTexts([answer, rest].map(cleanExplicitChoiceTerm), 120).slice(0, 4);
    }
  }
  const orParts = raw.split(/\s+(?:or|还是|或|或者)\s+/i).filter(Boolean);
  if (orParts.length >= 2) return uniqueAgentCardTexts(orParts.map(cleanExplicitChoiceTerm), 120).slice(0, 4);
  const wordParts = raw.split(/\s+/).filter(Boolean);
  if (wordParts.length >= 2 && wordParts.length <= 6 && wordParts.every((item) => /^[A-Za-z][A-Za-z'-]*$/.test(item))) {
    return uniqueAgentCardTexts(wordParts.map(cleanExplicitChoiceTerm), 120).slice(0, 4);
  }
  return uniqueAgentCardTexts([cleanExplicitChoiceTerm(raw)], 120);
}

function shouldSplitExplicitChoiceRestWords(words = []) {
  if (words.length < 2 || words.length > 6) return false;
  if (!words.every((item) => /^[A-Za-z][A-Za-z'-]*$/.test(item))) return false;
  if (words.length === 2) {
    const phrase = words.join(" ").toLowerCase();
    if (/\b(?:arrive early|leave early|come late|go home|turn left|turn right|check in|check out)\b/.test(phrase)) {
      return false;
    }
  }
  return true;
}

function cleanExplicitChoiceTerm(value = "") {
  return sanitizeAgentCardText(value)
    .replace(/^word\s+/i, "")
    .replace(/^["'“”‘’`]+|["'“”‘’`]+$/g, "")
    .replace(/[.?!。？！,，;；:：]+$/g, "")
    .trim();
}

function inferExplicitListeningCueConstraint(message = "") {
  const text = sanitizeAgentCardText(message).replace(/\s+/g, " ").trim();
  if (!text) return null;
  const termsFromFullMessage = parseExplicitCueTerms(text);
  if (termsFromFullMessage.length >= 2) return { terms: termsFromFullMessage };
  const match = /(?:signal\s*words?|cue\s*words?|listening\s*cues?|discourse\s*markers?|听力信号词|信号词)\s*[:：]?\s*(.+?)(?=\s+(?:only\s+core|core\s+cues?|no\s+|without\s+|不要|不需要|无|只要|仅|$))/i.exec(text);
  if (!match) return null;
  const terms = parseExplicitCueTerms(match[1]);
  return terms.length ? { terms } : null;
}

function parseExplicitCueTerms(value = "") {
  const raw = sanitizeAgentCardText(value).trim();
  if (!raw) return [];
  const lexicon = [
    "on the other hand",
    "as a result",
    "even though",
    "in contrast",
    "nevertheless",
    "therefore",
    "meanwhile",
    "although",
    "however",
    "whereas",
    "because",
    "instead",
    "though",
    "hence",
    "thus",
    "but",
    "so"
  ];
  const normalized = normalizeAgentAnswerLookupText(raw);
  const found = lexicon
    .map((term) => {
      const pattern = new RegExp(`(?:^|[^a-z0-9])${escapeRegExp(term).replace(/\\ /g, "\\s+")}(?=$|[^a-z0-9])`, "i");
      const match = pattern.exec(normalized);
      return match ? { term, index: match.index } : null;
    })
    .filter(Boolean)
    .sort((a, b) => a.index - b.index)
    .map((item) => item.term);
  if (found.length) return uniqueAgentCardTexts(found, 60).slice(0, 8);
  const delimited = raw.split(/\s*(?:\||,|，|、|;|；)\s*/).filter(Boolean);
  if (delimited.length >= 2) return uniqueAgentCardTexts(delimited.map(cleanExplicitCueTerm), 60).slice(0, 8);
  const thematic = listeningCueFallbackTermsForContext(raw);
  if (thematic.length) return thematic;
  return uniqueAgentCardTexts(raw.split(/\s+/).filter((item) => item.length > 1), 60).slice(0, 8);
}

function cleanExplicitCueTerm(value = "") {
  return sanitizeAgentCardText(value)
    .replace(/\b(?:only|no|without|extra|cards?|examples?|transcript|audio|actions?|listening|cues?)\b.*$/i, "")
    .replace(/^[.?!。？！,，;；:：]+|[.?!。？！,，;；:：]+$/g, "")
    .trim();
}

function listeningCueFallbackTermsForContext(context = "") {
  const text = String(context || "").toLowerCase();
  if (/contrast|转折|让步/.test(text)) return ["however", "although", "on the other hand"];
  if (/cause|reason|because|原因|因果/.test(text)) return ["because", "since", "as"];
  if (/result|therefore|consequence|结果|结论/.test(text)) return ["therefore", "as a result", "so"];
  return [];
}

function uniqueAgentCardTexts(items = [], maxLength = 120) {
  const seen = new Set();
  return items
    .map((item) => compactAgentCardText(item, maxLength))
    .filter(Boolean)
    .filter((item) => {
      const key = normalizeAgentAnswerLookupText(item);
      if (!key || seen.has(key)) return false;
      seen.add(key);
      return true;
    });
}

function inferExplicitMinimalPairs(message = "") {
  const raw = String(message || "").toLowerCase();
  const segments = [];
  const segmentPattern = /\b(?:include|including|for|using|with|words?|(?:exactly|only|just|with)?\s*[0-9]{0,2}\s*pairs?)\s+(.{0,180}?)(?=\b(?:only|no|without|不要|不需要|无|audio|pronunciation|tips?|examples?|extra|cards?|modules?|questions?|question\s*set|quiz|cloze|sentence\s*builder|grammar|actions?)\b|[.。]|$)/gi;
  let match;
  while ((match = segmentPattern.exec(raw))) {
    if (match[1]) segments.push(match[1]);
  }
  if (!segments.length) return [];
  const stopWords = new Set([
    "create", "make", "generate", "a1", "a2", "b1", "b2", "c1", "c2", "english",
    "minimal", "pair", "pairs", "card", "exactly", "only", "just", "with", "using",
    "include", "including", "focus", "focused", "vs", "and", "or", "core", "sound",
    "sounds", "listening", "discrimination", "practice", "pronunciation"
  ]);
  const pairs = [];
  for (const segment of segments) {
    const words = segment
      .replace(/[\/,;，；、|]+/g, " ")
      .replace(/\band\b/g, " ")
      .replace(/[^a-z\s'-]+/g, " ")
      .replace(/\s+/g, " ")
      .trim()
      .split(/\s+/)
      .filter((word) => /^[a-z][a-z'-]{1,24}$/.test(word))
      .filter((word) => !stopWords.has(word));
    for (let index = 0; index + 1 < words.length; index += 2) {
      const left = compactAgentCardText(words[index], 34);
      const right = compactAgentCardText(words[index + 1], 34);
      if (left && right && left !== right) pairs.push({ left, right, hint: "" });
    }
  }
  return uniqueMinimalPairObjects(pairs);
}

function uniqueMinimalPairObjects(pairs = []) {
  const seen = new Set();
  return pairs.filter((pair) => {
    const left = compactAgentCardText(pair?.left || "", 34);
    const right = compactAgentCardText(pair?.right || "", 34);
    if (!left || !right) return false;
    const key = `${left.toLowerCase()}|${right.toLowerCase()}`;
    const reverseKey = `${right.toLowerCase()}|${left.toLowerCase()}`;
    if (seen.has(key) || seen.has(reverseKey)) return false;
    seen.add(key);
    return true;
  }).map((pair) => ({
    left: compactAgentCardText(pair.left, 34),
    right: compactAgentCardText(pair.right, 34),
    hint: compactAgentCardText(pair.hint || "", 60)
  }));
}

function inferExplicitPhraseTargets(message = "") {
  const text = sanitizeAgentCardText(message).replace(/\s+/g, " ").trim();
  const results = [];
  const phrasalVerbPattern = /\b([a-z]{2,18}\s+(?:out|up|in|on|off|over|back|away|through|into|with|for|to|from|down))\b/gi;
  let match;
  while ((match = phrasalVerbPattern.exec(text)) !== null) {
    const phrase = compactAgentCardText(match[1], 60);
    if (phrase) results.push(phrase);
  }
  return uniqueAgentCardTexts(results, 60).slice(0, 8);
}

function inferExplicitSpeakingPromptOnlyText(message = "") {
  const topic = inferExplicitSpeakingPromptTopic(message);
  if (!topic) return "";
  return speakingPromptFromTopic(topic);
}

function inferExplicitSpeakingPromptTopic(message = "") {
  return inferExplicitSpeakingPromptTopicInfo(message)?.topic || "";
}

function inferExplicitSpeakingPromptTopicInfo(message = "") {
  const text = sanitizeAgentCardText(message).replace(/\s+/g, " ").trim();
  if (!text) return null;
  const topicMatch = /(?:topic|主题)\s*[:：]?\s*(.+?)(?=\s+(?:only(?:\s+(?:the\s+)?prompt|\s+speaking\s+prompts?)?|no\s+|without\s+|不要|不需要|无|$))/i.exec(text);
  if (topicMatch?.[1]) return {
    topic: compactAgentCardText(cleanSpeakingPromptTopic(topicMatch[1]), 140),
    source: "topic"
  };
  const chineseTopicMatch = /\u4e3b\u9898\u662f\s*(.+?)(?=\s*(?:\u3002|\.|\uff0c|,|\u53ea\u8981|only|no\s+|without\s+|\u4e0d\u8981|\u4e0d\u9700\u8981|\u65e0|$))/i.exec(text);
  if (chineseTopicMatch?.[1]) return {
    topic: compactAgentCardText(cleanSpeakingPromptTopic(chineseTopicMatch[1]), 140),
    source: "topic"
  };
  const aboutMatch = /(?:speaking\s+prompt|oral\s+prompt|口语提示).{0,24}\b(?:about|for)\s+(.+?)(?=\s+(?:only(?:\s+(?:the\s+)?prompt|\s+speaking\s+prompts?)?|no\s+|without\s+|不要|不需要|无|$))/i.exec(text);
  if (aboutMatch?.[1]) return {
    topic: compactAgentCardText(cleanSpeakingPromptTopic(aboutMatch[1]), 140),
    source: /\bfor\s+/i.test(aboutMatch[0]) ? "for" : "about"
  };
  return null;
}

function cleanSpeakingPromptTopic(value = "") {
  return sanitizeAgentCardText(value)
    .replace(/\b(?:a1|a2|b1|b2|c1|c2|beginner|intermediate|advanced)\b/gi, "")
    .replace(/\b(?:card|prompt|speaking|oral)\b/gi, "")
    .replace(/\s+/g, " ")
    .trim()
    .replace(/^[,.;:，。；：-]+|[,.;:，。；：-]+$/g, "")
    .trim();
}

function speakingPromptFromTopic(topic = "") {
  const value = compactAgentCardText(topic, 140).replace(/[.!?。！？]+$/g, "").trim();
  if (!value) return "";
  const lookup = normalizeAgentAnswerLookupText(value);
  if (/travel|\u65c5\u884c|\u65c5\u6e38/.test(lookup) && /problem|unexpected|\u7a81\u53d1|\u95ee\u9898|\u89e3\u51b3/.test(lookup)) {
    return "Describe a time when you solved an unexpected problem while traveling. What happened, what did you do, and what did you learn?";
  }
  const replacements = [
    [/^describing\s+/i, "Describe "],
    [/^describe\s+/i, "Describe "],
    [/^talking\s+about\s+/i, "Talk about "],
    [/^talk\s+about\s+/i, "Talk about "],
    [/^explaining\s+/i, "Explain "],
    [/^explain\s+/i, "Explain "],
    [/^comparing\s+/i, "Compare "],
    [/^compare\s+/i, "Compare "]
  ];
  for (const [pattern, replacement] of replacements) {
    if (pattern.test(value)) {
      return sentenceCaseAgentPrompt(value.replace(pattern, replacement));
    }
  }
  return sentenceCaseAgentPrompt(`Talk about ${value}`);
}

function sentenceCaseAgentPrompt(value = "") {
  const text = compactAgentCardText(value, 180).replace(/[.!?。！？]+$/g, "").trim();
  if (!text) return "";
  return `${text.charAt(0).toUpperCase()}${text.slice(1)}.`;
}

function inferExplicitSlashMinimalPairsFromText(text = "") {
  const pairs = [];
  const pattern = /\b([a-z][a-z'-]{1,24})\s*\/\s*([a-z][a-z'-]{1,24})\b/gi;
  let match;
  while ((match = pattern.exec(String(text || "")))) {
    pairs.push([match[1], match[2]]);
  }
  return pairs
    .map(([left, right]) => [compactAgentCardText(left, 34), compactAgentCardText(right, 34)])
    .filter(([left, right]) => left && right && left.toLowerCase() !== right.toLowerCase())
    .filter((pair, index, all) => all.findIndex((item) =>
      item[0].toLowerCase() === pair[0].toLowerCase() &&
      item[1].toLowerCase() === pair[1].toLowerCase()
    ) === index)
    .slice(0, 8);
}

function inferAgentRequestedQuestionCount(text = "") {
  const value = String(text || "").toLowerCase();
  const compactChineseQuestionCount = /(?:\u914d|\u51fa|\u7ed9|\u6765|\u505a)?\s*([1-9])\s*(?:\u9053|\u4e2a|\u500b|\u6761|\u689d)?\s*(?:(?:a1|a2|b1|b2|c1|c2|\u5355\u8bcd|\u8bcd\u6c47|\u8bcd\u4e49|\u542b\u4e49|\u610f\u601d|\u91ca\u4e49|\u9009\u62e9|\u9605\u8bfb|\u7406\u89e3|\u5339\u914d|\u542c\u529b|\u8bed\u6cd5|\u5b8c\u5f62|\u586b\u7a7a)\s*){0,8}(?:\u9898|\u984c|\u9898\u76ee|\u984c\u76ee|\u95ee\u9898|\u554f\u984c)/i.exec(value);
  if (compactChineseQuestionCount) return Number(compactChineseQuestionCount[1]);
  const questionModifiers = "(?:a1|a2|b1|b2|c1|c2|beginner|intermediate|advanced|english|chinese|language|multiple[-\\s]*choice|multiple|choice|mcq|quiz|comprehension|meaning|vocabulary|grammar|cloze|fill[-\\s]*in[-\\s]*the[-\\s]*blank|fill[-\\s]*in[-\\s]*blank|fill|blank|short|answer|minimal[-\\s]*pair|minimal|pair|listening|discrimination|pronunciation|sound)";
  const digit = new RegExp(`(?:^|\\s)([1-9])\\s+(?:${questionModifiers}\\s+){0,10}(?:questions?|题目|题|道)(?:\\b|$)`, "i").exec(value) ||
    /(?:配|出|给|来|做)?\s*([1-9])\s*(?:道|个|條|条)?\s*(?:(?:a1|a2|b1|b2|c1|c2|单词|词汇|含义|意思|选择|阅读|理解|匹配|听力|语法|完形|填空)\s*){0,8}(?:题|題|题目|題目|问题|問題)/i.exec(value) ||
    /(?:question\s*count|number\s*of\s*questions|题目数量|题数)\D{0,12}([1-9])(?:\s|$)/i.exec(value) ||
    /(?:questions?|题目|题|道)\s*[:：]\s*([1-9])(?:\s|$)/i.exec(value);
  if (digit) return Number(digit[1]);
  const wordPattern = [
    ["one", 1], ["two", 2], ["three", 3], ["four", 4], ["five", 5], ["six", 6], ["seven", 7], ["eight", 8], ["nine", 9],
    ["一", 1], ["二", 2], ["两", 2], ["三", 3], ["四", 4], ["五", 5], ["六", 6], ["七", 7], ["八", 8], ["九", 9]
  ].find(([word]) => new RegExp(`(?:^|\\s)${escapeRegExp(String(word))}\\s+(?:${questionModifiers}\\s+){0,10}(?:questions?|题|题目|道)(?:\\s|$)|(?:question\\s*count|number\\s*of\\s*questions|题目数量|题数)\\D{0,12}${escapeRegExp(String(word))}(?:\\s|$)`, "i").test(value));
  if (wordPattern) return wordPattern[1];
  const chineseCompact = [
    ["一道", 1], ["一题", 1], ["两道", 2], ["两题", 2], ["三道", 3], ["三题", 3], ["四道", 4], ["四题", 4], ["五道", 5], ["五题", 5],
    ["六道", 6], ["六题", 6], ["七道", 7], ["七题", 7], ["八道", 8], ["八题", 8], ["九道", 9], ["九题", 9]
  ].find(([pattern]) => value.includes(pattern));
  if (chineseCompact) return chineseCompact[1];
  const compactDigit = /(?:^|[^0-9])([1-9])\s*(?:道|个|個|条|條)\s*(?:题|題|题目|題目|问题|問題)?(?:$|[^0-9])/.exec(value) ||
    /(?:^|[^0-9])([1-9])\s*(?:题|題|题目|題目|问题|問題)(?:$|[^0-9])/.exec(value);
  return compactDigit ? Number(compactDigit[1]) : null;
}

function inferRequestedAgentCardItemCounts(message = "") {
  const text = String(message || "").toLowerCase();
  const numberToken = "([0-9]{1,2}|[一二两兩俩倆三四五六七八九十]{1,3}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)";
  const specs = [
    ["vocabulary", "(?:vocab(?:ulary)?|glossary|terms?|terminology|words?|academic\\s+words?|travel\\s+words?|词汇|单词|生词|关键词|生活词|高频词|核心词|术语|词表)"],
    ["question_set", "(?:questions?|quiz\\s+questions?|multiple\\s+choice\\s+questions?|题目|问题|选择题|语法题|完形题|填空题|meaning\\s+questions?)"],
    ["phrase", "(?:phrases?|phrasal\\s+verbs?|collocations?|短语|搭配|动词短语|常用表达|自然表达)"],
    ["translation", "(?:translations|translated\\s+phrases?|phrases?|terms?|sentences?|翻译|短语|句子)"],
    ["examples", "(?:examples?|sentences?|例句|句子)"],
    ["pronunciation", "(?:pronunciation\\s*tips?|stress\\s*tips?|sound\\s*tips?|发音提示|重音提示|连读提示|口型提示)"],
    ["listening_cue", "(?:listening\\s*cues?|signal\\s*words?|cue\\s*words?|discourse\\s*markers?|connectors?|linking\\s*words?|听力信号词|听力信号|信号词|连接词|衔接词|转折词|因果词|让步信号)"],
    ["minimal_pair", "(?:minimal\\s*pairs?|sound\\s*pairs?|sound\\s+contrasts?|音近词|音素对照|辨音|词对|组词对)"],
    ["word_family", "(?:word\\s*family|derivatives?|derivations?|related\\s+forms?|family\\s*tokens?|tokens?|词族|派生词|词根词缀|词根|词缀|forms?)"],
    ["scenario", "(?:scenario\\s*expressions?|situational\\s*expressions?|场景表达|情景表达)"],
    ["register", "(?:register\\s*shifts?|formal\\s*versions?|casual\\s*versions?|语气转换|语气改写|正式表达|非正式表达|礼貌表达|礼貌说法|versions?)"],
    ["rubric", "(?:rubric\\s*criteria|criteria|评分标准|标准)"],
    ["speaking_prompt", "(?:speaking\\s*)?prompts?|oral\\s*prompts?|口语提示"],
    ["writing_outline", "(?:outline\\s*steps?|writing\\s*steps?|提纲步骤|写作提纲|提纲)"],
    ["correction", "(?:corrections?|error\\s*corrections?|改错|纠错|句子改错|改正|修正)"],
    ["mistake_pattern", "(?:mistake\\s*patterns?|error\\s*patterns?|错因模式|错因)"],
    ["ethics", "(?:moral\\s*dilemmas?|ethical\\s*dilemmas?|moral\\s*(?:questions?|prompts?|scenarios?)|ethics?|道德两难|道德困境|道德情境|道德思辨|道德问题|道德反思|道德|伦理|品德|价值观)"],
    ["ordering", "(?:ordering|sequencing|sequence|paragraph\\s*order|sentence\\s*order|logical\\s*order|logic\\s*order|段落排序|句子排序|逻辑排序|篇章排序|排序|排顺序)"],
    ["cloze", "(?:cloze\\s*)?(?:blanks?|blank\\s*items?|fill\\s*blanks?|填空|空格|完形|空)"]
  ];
  const counts = {};
  specs.forEach(([type, label]) => {
    const before = new RegExp(`(?:^|[^a-z0-9])(?:exactly|only|just|给我|生成|create|make|with)?\\s*${numberToken}\\s+(?:[a-z0-9+-]+\\s+){0,4}${label}\\b`, "i").exec(text);
    const beforeWithChineseUnit = new RegExp(`(?:^|[^a-z0-9])(?:exactly|only|just|给我|生成|create|make|with)?\\s*${numberToken}\\s*(?:个|個|条|條|组|組|对|對|句|道)?\\s*(?:[a-z0-9+-]+\\s+){0,4}${label}`, "i").exec(text);
    const after = new RegExp(`${label}\\s*(?:数量|个数|count|number)?[^0-9一二两兩三四五六七八九十a-z]{0,12}${numberToken}`, "i").exec(text);
    const count = parseCountToken(before?.[1] || beforeWithChineseUnit?.[1] || after?.[1]);
    if (count && count >= 1 && count <= 12) counts[type] = count;
  });
  if (!counts.question_set) {
    const readingQuestionCount = new RegExp(
      `(?:^|[^a-z0-9])(?:exactly|only|just|with|给我|生成|create|make)?\\s*${numberToken}\\s+(?:reading\\s+)?(?:comprehension\\s+)?questions?\\b`,
      "i"
    ).exec(text);
    const count = parseCountToken(readingQuestionCount?.[1]);
    if (count && count >= 1 && count <= 12) counts.question_set = count;
  }
  // “N道/N题/N道题”是最明确的题数信号：数字直接接中文量词 道/题（支持多位数与十进制中文数字）。
  // 它优先于会被“每题四个选项”等干扰的通用启发式，并天然排除“N个选项”（选项数≠题数）。
  {
    const explicitQuestionMeasure = new RegExp(
      `(?:^|[^0-9a-z])${numberToken}\\s*(?:道题?|题目|题|问题|題目|題|問題)(?![0-9a-z])`,
      "i"
    ).exec(text);
    const explicitQMeasure = parseCountToken(explicitQuestionMeasure?.[1]);
    if (explicitQMeasure && explicitQMeasure >= 1 && explicitQMeasure <= 12) counts.question_set = explicitQMeasure;
  }
  const contextualSpecs = [
    ["vocabulary", "(?:vocab(?:ulary)?|glossary|terms?|terminology|words?|词汇|单词|术语|词表)", "(?:words?|terms?|items?|entries|个|条|项|词|术语)"],
    ["register", "(?:register|casual|formal|polite|语气|正式|非正式|礼貌|随意|改写|转换)", "(?:pairs?|versions?|lines?|组|对|条|句|种说法|版)"],
    ["scenario", "(?:scenario|situational|场景|情景)", "(?:expressions?|phrases?|items?|句|条|个)"],
    ["compare", "(?:compare|difference|区别|对比|辨析)", "(?:differences?|points?|items?|条|点|个)"],
    ["mistake_pattern", "(?:mistake|error|错因)", "(?:patterns?|items?|条|个)"],
    ["listening_cue", "(?:listening\\s*cues?|signal\\s*words?|cue\\s*words?|discourse\\s*markers?|connectors?|linking\\s*words?|听力信号|信号词|连接词|衔接词|转折词|因果词|让步信号)", "(?:cues?|signals?|words?|markers?|connectors?|items?|条|个)"],
    ["pronunciation", "(?:pronunciation|stress|sound|发音|重音|连读)", "(?:tips?|points?|items?|条|点|个)"],
    ["minimal_pair", "(?:minimal\\s*pair|sound\\s*pair|音近词|音素对照|辨音|词对|易听错)", "(?:pairs?|items?|组|对|个)"],
    ["phrase", "(?:phrase|phrasal\\s+verb|collocation|短语|搭配|表达)", "(?:phrases?|phrasal\\s+verbs?|collocations?|items?|条|个)"],
    ["translation", "(?:translation|translate|翻译|中文|chinese)", "(?:phrases?|terms?|items?|translations|sentences?|短语|句子|条|个)"],
    ["correction", "(?:correction|correct|rewrite|改错|纠错|改正|修正)", "(?:sentences?|items?|句子|句|条|个)"],
    ["writing_outline", "(?:writing|outline|essay|写作|提纲)", "(?:steps?|提纲步骤|步骤)"],
    ["ordering", "(?:ordering|sequencing|sequence|paragraph|sentence|logical|logic|段落|句子|短文|篇章|排序)", "(?:items?|sentences?|paragraphs?|句|段|段落|个|条|项)"]
  ];
  contextualSpecs.forEach(([type, contextPattern, unitPattern]) => {
    if (counts[type] || !new RegExp(contextPattern, "i").test(text)) return;
    const before = new RegExp(`(?:^|[^a-z0-9])(?:exactly|only|just|with|给我|生成|create|make)?\\s*${numberToken}\\s+(?:[a-z0-9+-]+\\s+){0,3}${unitPattern}\\b`, "i").exec(text);
    const beforeWithChineseUnit = new RegExp(`(?:^|[^a-z0-9])(?:exactly|only|just|with|给我|生成|create|make)?\\s*${numberToken}\\s*(?:个|個|条|條|组|組|对|對|句|道)?\\s*(?:[a-z0-9+-]+\\s+){0,3}${unitPattern}`, "i").exec(text);
    const after = new RegExp(`${unitPattern}\\s*(?:数量|个数|count|number)?[^0-9一二两兩三四五六七八九十a-z]{0,12}${numberToken}`, "i").exec(text);
    const count = parseCountToken(before?.[1] || beforeWithChineseUnit?.[1] || after?.[1]);
    if (count && count >= 1 && count <= 12) counts[type] = count;
  });
  const scenarioConstraint = inferExplicitScenarioConstraint(message);
  if (scenarioConstraint?.promptCount) counts.scenario = scenarioConstraint.promptCount;
  const compactChineseSpecs = [
    ["translation", /([一二两兩俩倆三四五六七八九十0-9]{1,3})\s*(?:个|個|条|條)?\s*(?:句|句子).{0,12}(?:翻译|译文|中译英|英译中)|(?:翻译|译文|中译英|英译中)\s*([一二两兩俩倆三四五六七八九十0-9]{1,3})\s*(?:个|個|条|條)?\s*(?:句|句子)?/i],
    ["correction", /(?:改错|纠错|改正|修正)\s*([一二两兩俩倆三四五六七八九十0-9]{1,3})\s*(?:个|個|条|條)?\s*(?:句|句子)?|([一二两兩俩倆三四五六七八九十0-9]{1,3})\s*(?:个|個|条|條)?\s*(?:句|句子).{0,12}(?:改错|纠错|改正|修正)/i],
    ["cloze", /(?:完形|填空|cloze)\s*(?:来|出|给)?\s*([一二两兩俩倆三四五六七八九十0-9]{1,3})\s*(?:个|個|道|条|條)?\s*(?:空|blank|blanks)?|([一二两兩俩倆三四五六七八九十0-9]{1,3})\s*(?:个|個|道|条|條)?\s*(?:空|blank|blanks)/i]
  ];
  compactChineseSpecs.forEach(([type, pattern]) => {
    if (counts[type]) return;
    const match = pattern.exec(text);
    const count = parseCountToken(match?.[1] || match?.[2]);
    if (count && count >= 1 && count <= 12) counts[type] = count;
  });
  const trailingLabelCountSpecs = [
    ["phrase", /(?:phrases?|phrasal\s+verbs?|collocations?|chunks?|lexical\s+chunks?|idioms?|短语|搭配|语块|习语)\D{0,12}([0-9]{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|一|二|两|三|四|五|六|七|八|九|十)(?:\b|$)/i],
    ["vocabulary", /(?:vocab(?:ulary)?|glossary|terms?|terminology|words?|词汇|单词|术语|词表)\D{0,12}([0-9]{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|一|二|两|三|四|五|六|七|八|九|十)(?:\b|$)/i]
  ];
  trailingLabelCountSpecs.forEach(([type, pattern]) => {
    if (counts[type]) return;
    const match = pattern.exec(text);
    const count = parseCountToken(match?.[1]);
    if (count && count >= 1 && count <= 12) counts[type] = count;
  });
  if (!counts.phrase && /phrases?|phrasal\s+verbs?|collocations?|chunks?|lexical\s+chunks?|idioms?|短语|搭配|语块|习语/i.test(text)) {
    const itemCount = /([0-9]{1,2}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|一|二|两|三|四|五|六|七|八|九|十)\s+(?:items?|entries|phrases?|idioms?|chunks?)\b/i.exec(text);
    const count = parseCountToken(itemCount?.[1]);
    if (count && count >= 1 && count <= 12) counts.phrase = count;
  }
  const explicitQuestionCount = inferAgentRequestedQuestionCount(text);
  if (explicitQuestionCount) counts.question_set = explicitQuestionCount;
  if (counts.ordering && counts.ordering < 2) delete counts.ordering;
  return counts;
}

function agentCardComponentTextMaxLength(type = "") {
  if (type === "summary") return 1400;
  if (type === "reading") return 1800;
  if (type === "gap_match") return 1500;
  if (type === "cloze") return 800;
  if (type === "ordering") return 600;
  if (type === "transcript") return 1200;
  if ([
    "vocabulary",
    "phrase",
    "grammar",
    "translation",
    "pronunciation",
    "compare",
    "correction",
    "rubric",
    "listening_cue",
    "scenario",
    "register",
    "speaking_prompt",
    "writing_outline",
    "mistake_pattern",
    "ethics",
    "debate",
    "error_hunt",
    "storytelling",
    "paraphrase"
  ].includes(type)) return 600;
  if (["short_answer", "speaking_prompt", "sentence_builder"].includes(type)) return 360;
  return 220;
}

module.exports = {
  inferExplicitChoiceConstraint,
  inferExplicitChoiceTarget,
  inferExplicitClozeConstraint,
  lastAgentCardRegexGroup,
  cleanExplicitClozeSourceSentence,
  parseExplicitClozeTerms,
  inferExplicitTranslationConstraint,
  inferExplicitTranslationTerms,
  splitExplicitTranslationSentences,
  inferKnownTranslationTerms,
  cleanExplicitTranslationTerm,
  parseExplicitChoiceOptions,
  shouldSplitExplicitChoiceRestWords,
  cleanExplicitChoiceTerm,
  inferExplicitListeningCueConstraint,
  parseExplicitCueTerms,
  cleanExplicitCueTerm,
  listeningCueFallbackTermsForContext,
  uniqueAgentCardTexts,
  inferExplicitMinimalPairs,
  uniqueMinimalPairObjects,
  inferExplicitPhraseTargets,
  inferExplicitSpeakingPromptOnlyText,
  inferExplicitSpeakingPromptTopic,
  inferExplicitSpeakingPromptTopicInfo,
  cleanSpeakingPromptTopic,
  speakingPromptFromTopic,
  sentenceCaseAgentPrompt,
  inferExplicitSlashMinimalPairsFromText,
  inferAgentRequestedQuestionCount,
  inferRequestedAgentCardItemCounts,
  agentCardComponentTextMaxLength,
};
