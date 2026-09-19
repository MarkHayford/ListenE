// Agent 输出文件 / 导出：导出意图识别、outputFiles 归一化、题目/听力 zip/历史导出内容构建。
// 从 mimoText.js 抽出（零行为变更）。依赖经工厂注入，保持单向依赖 mimoText -> agentOutputExport。

const { sanitizeAgentCardText } = require("./agentCardText");

function createAgentOutputExport(deps) {
const { normalizeAgentCardSpec, normalizeAgentStringArray, stripAgentQuestionOptionLabel, isStrictRegisterOnlyAgentCardRequest } = deps;

function sanitizeAgentMissingQuestionExportReply(reply = "", message = "", outputFiles = []) {
  if (isAgentListeningZipExportRequest(message)) return outputFiles.length > 0 ? reply : "没有找到可导出的听力素材。";
  if (!isAgentQuestionExportRequest(message) || outputFiles.length > 0) return reply;
  return "没有找到可导出的题目。";
}

function normalizeAgentOutputFiles(files, message, reply, recentMessages = [], currentRecordSummary = null, currentCardSpec = null) {
  const requested = isAgentOutputFileRequest(message);
  const rawFiles = Array.isArray(files) ? files : [];
  const requestedFormat = requested ? inferAgentOutputFormat(message) : "";
  const listeningZipContent = requested && isAgentListeningZipExportRequest(message)
    ? buildAgentListeningZipExportContent(currentRecordSummary, message)
    : "";
  const exportableFromCardSpec = requested
    ? buildAgentQuestionExportContentFromCardSpecForRequest(currentCardSpec, message)
    : "";
  const exportableFromHistory = requested && !listeningZipContent
    ? extractAgentQuestionExportContent({ message, recentMessages, currentRecordSummary }) ||
      (isAgentHistoryExportRequest(message) && !isAgentQuestionExportRequest(message) ? extractAgentLooseHistoryExportContent(recentMessages) : "") ||
      exportableFromCardSpec
    : "";
  const normalized = rawFiles
    .map((item) => normalizeAgentOutputFile(item))
    .map((item) => forceAgentOutputFormatForRequest(item, requestedFormat))
    .map((item) => listeningZipContent ? useListeningZipContentForExport(item, listeningZipContent) : item)
    .map((item) => useHistoryContentForExport(item, message, exportableFromHistory))
    .filter((item) => !isAgentQuestionExportRequest(message) || Boolean(exportableFromHistory) || Boolean(listeningZipContent))
    .filter(Boolean)
    .slice(0, 3);
  if (!requested) return [];
  if (normalized.length > 0) return normalized;
  if (isAgentListeningZipExportRequest(message)) {
    return listeningZipContent
      ? [normalizeAgentOutputFile({
          name: "ListenE-Listening-Package.zip",
          format: "zip",
          content: listeningZipContent
        })].filter(Boolean)
      : [];
  }
  if (isAgentQuestionExportRequest(message) && !exportableFromHistory) return [];
  const useHistoryContent = shouldUseHistoryContentForExport(message, exportableFromHistory);
  const content = useHistoryContent
    ? cleanAgentExportDocumentContent(exportableFromHistory || inferAgentOutputFileFallbackContent(message, reply, recentMessages), message)
    : inferAgentOutputFileFallbackContent(message, reply, recentMessages);
  return [normalizeAgentOutputFile({
    name: `ListenE-Agent-${Date.now()}.${requestedFormat}`,
    format: requestedFormat,
    content
  })].filter(Boolean);
}

function forceAgentOutputFormatForRequest(file, requestedFormat) {
  if (!file || !requestedFormat) return file;
  if (file.format === requestedFormat) return file;
  const baseName = String(file.name || "ListenE-Agent").replace(/\.[^.]+$/, "") || "ListenE-Agent";
  return {
    ...file,
    name: sanitizeAgentOutputFileName(baseName, requestedFormat),
    format: requestedFormat,
    mimeType: outputMimeType("", requestedFormat)
  };
}

function useHistoryContentForExport(file, message, exportableFromHistory) {
  if (!file || !shouldUseHistoryContentForExport(message, exportableFromHistory)) return file;
  return {
    ...file,
    content: cleanAgentExportDocumentContent(exportableFromHistory, message).slice(0, 120000)
  };
}

function useListeningZipContentForExport(file, listeningZipContent = "") {
  if (!file || !listeningZipContent) return file;
  return {
    ...file,
    content: listeningZipContent.slice(0, 120000)
  };
}

function shouldUseHistoryContentForExport(message = "", exportableFromHistory = "") {
  if (!exportableFromHistory) return false;
  if (isAgentListeningZipExportRequest(message)) return false;
  if (isAgentQuestionExportRequest(message)) return true;
  return isAgentHistoryExportRequest(message);
}

function cleanAgentExportDocumentContent(content = "", message = "") {
  const source = isAgentQuestionExportRequest(message)
    ? extractAgentQuestionOnlyExportContent(content) || content
    : content;
  const cleaned = String(source || "")
    .split(/\r?\n/)
    .map((line) => line.replace(/^Agent card:\s*/i, ""))
    .filter((line) => !/^\s*Agent card\s*:\s*$/i.test(line))
    .filter((line) => !isAgentQuestionExportRequest(message) || shouldIncludeQuestionExportExplanations(message) || !/^\s*(?:Explanation|解析|解释|讲解)\s*[:：]/i.test(line))
    .map((line) => line.replace(/^([A-D])\.\s+\1[.)]\s+/i, "$1. "))
    .map((line) => line.replace(/^([A-D])\.\s+[A-D][.)]\s+/i, "$1. "))
    .join("\n")
    .trim();
  if (!shouldUseAnswerKeyQuestionExport(message)) return cleaned;
  return buildAgentAnswerKeyQuestionExportContent(cleaned) || cleaned;
}

function extractAgentQuestionOnlyExportContent(content = "") {
  const lines = String(content || "").split(/\r?\n/);
  const firstQuestionIndex = lines.findIndex((line) => /^\s*\d{1,2}[\).、]\s+\S/.test(line));
  if (firstQuestionIndex < 0) return "";
  let startIndex = firstQuestionIndex;
  for (let index = firstQuestionIndex - 1; index >= 0; index -= 1) {
    const line = lines[index].trim();
    if (!line) continue;
    if (/^(?:questions?|question\s*set|.+\s+questions?|题目|题组|试题|习题|选择题)\s*[:：]$/i.test(line)) {
      startIndex = index;
    }
    break;
  }
  const titleIndex = lines.findIndex((line) => /^\s*Agent card\s*:\s*\S/i.test(line));
  if (titleIndex >= 0 && titleIndex < startIndex) {
    const between = lines.slice(titleIndex + 1, startIndex)
      .map((line) => line.trim())
      .filter(Boolean);
    const hasNonQuestionSectionBeforeQuestions = between.some((line) =>
      /^(?:summary|scene|scenario|transcript|script|original|source|原文|场景|简介|摘要|建议|suggestions?)\s*[:：]$/i.test(line)
    );
    if (!hasNonQuestionSectionBeforeQuestions) startIndex = titleIndex;
  } else {
    const leadingRows = lines.slice(0, startIndex)
      .map((line, index) => ({ line: line.trim(), index }))
      .filter((item) => item.line);
    if (
      leadingRows.length === 1 &&
      !/^(?:questions?|question\s*set|summary|scene|scenario|transcript|script|original|source|原文|场景|简介|摘要|建议|suggestions?)\s*[:：]?$/i.test(leadingRows[0].line)
    ) {
      startIndex = leadingRows[0].index;
    }
  }
  const questionRows = [];
  for (let index = startIndex; index < lines.length; index += 1) {
    const line = lines[index].trim();
    if (!line) {
      if (questionRows.length) questionRows.push("");
      continue;
    }
    if (
      questionRows.length &&
      /^(?:summary|scene|scenario|transcript|script|original|source|原文|场景|简介|摘要|建议|suggestions?)\s*[:：]$/i.test(line)
    ) {
      break;
    }
    questionRows.push(lines[index]);
  }
  const text = questionRows.join("\n").replace(/\n{3,}/g, "\n\n").trim();
  return isLikelyAgentQuestionExportContent(text) ? text : "";
}

function shouldUseAnswerKeyQuestionExport(message = "") {
  const text = String(message || "").toLowerCase();
  return /answer\s*key|答案键|答案表|答案汇总|answer\s*sheet/.test(text) &&
    /only|include\s+only|只|仅|只要|只包含|不要/.test(text);
}

function buildAgentAnswerKeyQuestionExportContent(content = "") {
  const questions = parseAgentQuestionExportBlocks(content);
  if (!questions.length || !questions.every((question) => question.answer)) return "";
  const lines = ["Questions:"];
  questions.forEach((question, index) => {
    lines.push(`${index + 1}. ${question.text}`);
    question.options.forEach((option, optionIndex) => {
      lines.push(`${agentExportOptionLetter(optionIndex)}. ${option.text}`);
    });
  });
  lines.push("", "Answer Key:");
  questions.forEach((question, index) => {
    lines.push(`${index + 1}. ${question.answer}`);
  });
  return lines.join("\n").trim();
}

function parseAgentQuestionExportBlocks(content = "") {
  const questions = [];
  let current = null;
  const pushCurrent = () => {
    if (current?.text && current.options.length) questions.push(current);
  };
  String(content || "").split(/\r?\n/).forEach((rawLine) => {
    const line = rawLine.trim();
    if (!line) return;
    const questionMatch = /^([0-9]{1,2})[\).、]\s+(.+)$/.exec(line);
    if (questionMatch) {
      pushCurrent();
      current = {
        text: sanitizeAgentCardText(questionMatch[2]).trim(),
        options: [],
        answer: ""
      };
      return;
    }
    if (!current) return;
    const optionMatch = /^([A-HＡ-Ｈ])[\).、]\s+(.+)$/.exec(line);
    if (optionMatch) {
      current.options.push({
        letter: normalizeAgentExportOptionLetter(optionMatch[1]) || agentExportOptionLetter(current.options.length),
        text: stripAgentQuestionOptionLabel(sanitizeAgentCardText(optionMatch[2]).trim())
      });
      return;
    }
    const answerMatch = /^(?:Correct\s+answer|Answer|答案|正确答案)\s*[:：]\s*(.+)$/i.exec(line);
    if (answerMatch) {
      current.answer = normalizeAgentQuestionExportAnswerKey(answerMatch[1], current.options);
    }
  });
  pushCurrent();
  return questions
    .map((question) => ({
      ...question,
      options: question.options.filter((option) => option.text).slice(0, 8)
    }))
    .filter((question) => question.text && question.options.length);
}

function normalizeAgentQuestionExportAnswerKey(value = "", options = []) {
  const clean = sanitizeAgentCardText(value).trim();
  const letter = normalizeAgentExportOptionLetter(clean.replace(/[\).、].*$/, ""));
  if (letter) return letter;
  const answerText = normalizeAgentExportAnswer(clean);
  const answerIndex = options.findIndex((option) => normalizeAgentExportAnswer(option.text) === answerText);
  return answerIndex >= 0 ? agentExportOptionLetter(answerIndex) : clean;
}

function normalizeAgentExportOptionLetter(value = "") {
  const clean = String(value || "").trim().toUpperCase();
  if (/^[A-H]$/.test(clean)) return clean;
  const fullWidth = "ＡＢＣＤＥＦＧＨ".indexOf(clean);
  return fullWidth >= 0 ? agentExportOptionLetter(fullWidth) : "";
}

function inferAgentOutputFileFallbackContent(message = "", reply = "", recentMessages = []) {
  const fromHistory = extractAgentExportableContentFromRecentMessages(recentMessages, message);
  if (fromHistory) return fromHistory;
  const replyText = String(reply || "").trim();
  if (replyText && !/^(已生成|文件已生成|done|created)[\s。.!！]*$/i.test(replyText)) return replyText;
  return String(message || "").trim() || "这是 ListenE Agent 生成的文档。";
}

function extractAgentExportableContentFromRecentMessages(recentMessages = [], message = "") {
  if (!Array.isArray(recentMessages)) return "";
  const cardCandidate = recentMessages
    .slice(-8)
    .reverse()
    .map((item) => extractAgentCardExportBlock(String(item?.content || item?.text || "").trim()))
    .filter(isLikelyAgentExportableContent)
    .filter((text) => agentExportTextMatchesRequest(text, message))
    .find(Boolean);
  if (cardCandidate) return cardCandidate;
  const candidates = recentMessages
    .slice(-8)
    .reverse()
    .map((item) => String(item?.content || item?.text || "").trim())
    .filter(Boolean)
    .filter(isLikelyAgentExportableContent)
    .filter((text) => agentExportTextMatchesRequest(text, message));
  return candidates[0] || "";
}

function extractAgentQuestionExportContent({ message = "", recentMessages = [], currentRecordSummary = null } = {}) {
  if (!isAgentQuestionExportRequest(message)) {
    return extractAgentExportableContentFromRecentMessages(recentMessages, message);
  }
  const fromHistory = extractAgentQuestionExportContentFromRecentMessages(recentMessages, message);
  if (shouldPreferRecentMessagesForQuestionExport(message) && fromHistory) return fromHistory;
  return buildAgentQuestionExportContentFromRecordForRequest(currentRecordSummary, message) ||
    fromHistory;
}

function extractAgentQuestionExportContentFromRecentMessages(recentMessages = [], message = "") {
  if (!Array.isArray(recentMessages)) return "";
  const candidates = recentMessages
    .slice(-8)
    .reverse()
    .map((item) => {
      const raw = String(item?.content || item?.text || "").trim();
      return extractAgentCardExportBlock(raw) || raw;
    })
    .filter(isLikelyAgentQuestionExportContent)
    .filter((text) => agentExportTextMatchesRequest(text, message));
  return candidates[0] || "";
}

function extractAgentLooseHistoryExportContent(recentMessages = []) {
  if (!Array.isArray(recentMessages)) return "";
  const candidate = recentMessages
    .slice(-8)
    .reverse()
    .map((item) => String(item?.content || item?.text || "").trim())
    .find((text) => text && !/^(好的。?|可以。?|已生成。?)$/i.test(text));
  return candidate
    ? `ListenE 导出内容\n\n${candidate}`
    : "";
}

function isLikelyAgentQuestionExportContent(text = "") {
  const value = String(text || "").trim();
  if (!value) return false;
  if (isAgentQuestionExportSuggestionText(value)) return false;
  if (isLikelyAgentPracticeExportContent(value)) return true;
  const hasQuestionSection = /(?:^|\n)\s*(?:Questions?|Question\s*Set|题目|题组)\s*[:：]/i.test(value);
  const numberedRows = (value.match(/(?:^|\n)\s*\d+[\).、]\s+[^\n]+/g) || [])
    .filter((row) => !/^\s*\d+[\).、]\s*(?:export|send|save|download|share|generate|create|make|would\s+you\s+like|do\s+you\s+want|导出|发送|生成|创建|要不要|是否)/i.test(row));
  const hasNumberedQuestion = numberedRows.length > 0;
  const questionMarkRows = numberedRows.filter((row) => /[?？]/.test(row)).length;
  const hasOptions = /(?:^|[\n\s])(?:[A-H]|[Ａ-Ｈ])[\).、]\s+\S/i.test(value) || /(?:^|\n)\s*(?:options?|选项)\s*[:：]/i.test(value);
  const hasAnswer = /(?:^|\n)\s*(?:Correct\s+answer|Answer|答案|正确答案)\s*[:：]/i.test(value);
  if (hasQuestionSection && (hasNumberedQuestion || hasOptions || hasAnswer)) return true;
  if (hasNumberedQuestion && (hasOptions || hasAnswer)) return true;
  return questionMarkRows >= 2;
}

function isLikelyAgentExportableContent(text = "") {
  const value = String(text || "").trim();
  if (!value) return false;
  if (isAgentQuestionExportSuggestionText(value)) return false;
  if (isLikelyAgentQuestionExportContent(value)) return true;
  if (!/(?:^|\n)\s*Agent card\s*:/i.test(value)) return false;
  const hasStructuredSection = /(?:^|\n)\s*(?:Summary|Key\s+Differences?|Differences?|Compare|Comparison|Vocabulary|Words?|Phrases?|Translation|Examples?|Grammar|Pronunciation|Correction|Corrections|Rubric|Criteria|Listening\s+Cues?|Signal\s+Words?|Minimal\s+Pairs?|Word\s+Family|Scenario|Register|Speaking\s+Prompt|Task|Prompt|Writing\s+Outline|Mistake\s+Patterns?|Feedback|Transcript|Script|摘要|总结|要点|区别|对比|词汇|单词|短语|翻译|例句|语法|发音|纠错|评分|听力信号|音素|词族|场景|语气|口语|任务|提示|写作|错因|原文)\s*[:：]/i.test(value);
  const contentLines = value
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .filter((line) => !/^Agent card\s*:/i.test(line));
  const hasListRows = contentLines.filter((line) => /^[-*•]\s+\S/.test(line) || /\s+-\s+\S/.test(line) || /\s+\|\s+\S/.test(line)).length >= 1;
  return contentLines.length >= 2 && (hasStructuredSection || hasListRows);
}

function isLikelyAgentPracticeExportContent(text = "") {
  const value = String(text || "").trim();
  if (!value) return false;
  const hasAgentCard = /(?:^|\n)\s*Agent card\s*:/i.test(value);
  const hasLocalExerciseSection = /(?:^|\n)\s*(?:Short answer|Typed answer|Dictation|Type (?:the )?Sentence|Typing|Input Answer|Text Input|Free Response|Cloze|Fill in the blanks?|Select the meaning|Meaning Choice|Sentence builder|Reorder(?: the words?)?|Word Order|输入答案|默写|听写|填空|含义选择|词义选择|组句|排序)\s*[:：]/i.test(value);
  const hasExerciseAnswer = /(?:^|\n)\s*(?:Answer|Correct answer|答案|正确答案)\s*[:：]\s*\S/i.test(value);
  const hasExercisePrompt = /(?:^|\n)\s*(?:Prompt|Question|题目|提示)\s*[:：]\s*\S/i.test(value);
  const hasSentenceBuilderWords = /(?:^|\n)\s*Words\s*[:：]\s*\S/i.test(value);
  const hasClozeBlank = /___|(?:^|\n)\s*(?:[A-H]|[Ａ-Ｈ])[\).、]\s+\S/i.test(value);
  return hasAgentCard && hasLocalExerciseSection && hasExerciseAnswer &&
    (hasExercisePrompt || hasSentenceBuilderWords || hasClozeBlank || value.split(/\r?\n/).length >= 3);
}

function isAgentQuestionExportSuggestionText(text = "") {
  const value = String(text || "").trim();
  return /(?:would\s+you\s+like|do\s+you\s+want|是否需要|要不要|要我|你想让我)/i.test(value) &&
    /(?:not\s+found|no\s+.+found|没有找到|未找到|instead|first|先|重新|generate|create|导出|生成)/i.test(value);
}

function agentExportTextMatchesRequest(text = "", message = "") {
  const request = String(message || "").toLowerCase();
  const value = String(text || "").toLowerCase();
  if (/pharmacy|medicine|medication|prescription|药店|药房/.test(request) && !/pharmacy|medicine|medication|prescription|药店|药房/.test(value)) return false;
  if (/airport|flight|boarding|check.?in|机场|航班|登机|值机/.test(request) && !/airport|flight|boarding|check.?in|机场|航班|登机|值机/.test(value)) return false;
  if (/hotel|reservation|room|酒店|入住/.test(request) && !/hotel|reservation|room|酒店|入住/.test(value)) return false;
  if (/speaking\s*prompt|oral\s*prompt|口语提示|口语题/.test(request) && !/speaking\s*prompt|oral\s*prompt|口语提示|口语题|(?:^|\n)\s*(?:task|prompt)\s*[:：]/.test(value)) return false;
  if (/writing\s*outline|essay\s*outline|outline\s*card|写作提纲|提纲卡/.test(request) && !/writing\s*outline|essay\s*outline|outline|写作提纲|提纲/.test(value)) return false;
  if (/compare|comparison|difference|区别|对比|辨析/.test(request) && !/compare|comparison|difference|key\s*differences?|区别|对比|辨析/.test(value)) return false;
  if (/vocab|vocabulary|words?|词汇|单词/.test(request) && !/vocab|vocabulary|words?|词汇|单词/.test(value)) return false;
  if (/translation|translate|翻译/.test(request) && !/translation|translate|翻译|english\s*[|→-]|中文|chinese/.test(value)) return false;
  if (/(?:meaning\s+)?choice|choose|select\s+the\s+meaning|含义选择|词义选择|选择(?!题)/.test(request) && !/meaning|select\s+the\s+meaning|choice|means\s*:|含义|词义|选择/.test(value)) return false;
  if (/cloze|fill\s+in\s+the\s+blanks?|填空/.test(request) && !/cloze|fill\s+in\s+the\s+blanks?|___|填空/.test(value)) return false;
  if (/sentence\s*builder|reorder|word\s*order|组句|排序/.test(request) && !/sentence\s*builder|reorder|word\s*order|words\s*[:：]|组句|排序/.test(value)) return false;
  if (/short\s*answer|typed\s*answer|type\s+the\s+sentence|dictation|输入答案|默写|短答|听写/.test(request) && !/short\s*answer|typed\s*answer|type\s+the\s+sentence|dictation|answer\s*[:：]|输入答案|默写|短答|听写/.test(value)) return false;
  return true;
}

function shouldPreferRecentMessagesForQuestionExport(message = "") {
  const text = String(message || "").toLowerCase();
  if (/当前听力素材|当前素材|当前这套素材|current\s+(?:listening\s+)?material|latest\s+(?:listening\s+)?material/.test(text)) {
    return false;
  }
  return /刚才|上一|上面|前面|最近|previous|last|above|recent|just\s+created|just\s+generated/.test(text) ||
    /questions?|题目|题组|quiz|worksheet/.test(text);
}

function buildAgentQuestionExportContentFromRecord(record, message = "") {
  if (!record || typeof record !== "object") return "";
  const questions = Array.isArray(record.questions) ? record.questions : [];
  const includeExplanations = shouldIncludeQuestionExportExplanations(message);
  const rows = questions
    .map((question, index) => formatAgentExportQuestion(question, index, { includeExplanations }))
    .filter(Boolean);
  if (!rows.length) return "";
  const title = sanitizeAgentCardText(record.title || record.scene || "").trim() || "ListenE Questions";
  return [
    title,
    "",
    ...rows
  ].join("\n").trim();
}

function buildAgentListeningZipExportContent(record, message = "") {
  if (!record || typeof record !== "object") return "";
  const script = sanitizeAgentCardText(record.script || record.scriptPreview || "").trim();
  const questions = buildAgentQuestionExportContentFromRecord(record, message);
  const audioUrl = sanitizeAgentCardText(record.audioUrl || record.audio || "").trim();
  const audioReady = record.audioReady === true || Boolean(audioUrl);
  if (!script && !questions && !audioReady) return "";
  const title = sanitizeAgentCardText(record.title || record.scene || "").trim() || "ListenE Listening Practice";
  const audioName = inferAgentListeningZipAudioFileName(audioUrl);
  const sections = [
    "ListenE ZIP package manifest",
    `Title: ${title}`,
    "",
    "Files:",
    audioReady
      ? `- audio/${audioName}${audioUrl ? `\n  audioUrl: ${audioUrl}` : ""}`
      : "- audio/README.txt\n  audioUrl: unavailable",
    "- transcript.txt",
    "- questions.txt",
    "",
    "transcript.txt",
    script || "Transcript unavailable.",
    "",
    "questions.txt",
    questions || "Questions unavailable."
  ];
  return sections.join("\n").trim();
}

function inferAgentListeningZipAudioFileName(audioUrl = "") {
  const raw = String(audioUrl || "").trim();
  const withoutQuery = raw.split("?")[0].split("#")[0];
  const name = withoutQuery.split("/").pop() || "listening-audio.mp3";
  const clean = sanitizeAgentOutputFileName(name, /\.([a-z0-9]{2,5})$/i.exec(name)?.[1] || "mp3");
  return clean.includes(".") ? clean : "listening-audio.mp3";
}

function buildAgentQuestionExportContentFromRecordForRequest(record, message = "") {
  if (!recordMatchesQuestionExportRequest(record, message)) return "";
  return buildAgentQuestionExportContentFromRecord(record, message);
}

function buildAgentQuestionExportContentFromCardSpecForRequest(cardSpec, message = "") {
  if (!isAgentQuestionExportRequest(message)) return "";
  const normalized = normalizeAgentCardSpec(cardSpec);
  if (!normalized) return "";
  const questionSets = (normalized.components || [])
    .filter((component) => component?.type === "question_set" && Array.isArray(component.questions));
  const questions = questionSets.flatMap((component) => component.questions).filter(Boolean);
  if (!questions.length) return "";
  const includeExplanations = shouldIncludeQuestionExportExplanations(message);
  const rows = questions
    .map((question, index) => formatAgentExportQuestion(question, index, { includeExplanations }))
    .filter(Boolean);
  if (!rows.length) return "";
  const title = sanitizeAgentCardText(normalized.title || "ListenE Questions").trim() || "ListenE Questions";
  const content = [
    title,
    "",
    ...rows
  ].join("\n").trim();
  return isLikelyAgentQuestionExportContent(content) && agentExportTextMatchesRequest(content, message)
    ? content
    : "";
}

function recordMatchesQuestionExportRequest(record, message = "") {
  if (!record || typeof record !== "object") return false;
  const text = String(message || "").toLowerCase();
  const recordText = `${record.title || ""} ${record.scene || ""} ${record.contentType || ""} ${record.scriptPreview || ""}`.toLowerCase();
  if (/pharmacy|medicine|medication|prescription|药店|药房/.test(text) && !/pharmacy|medicine|medication|prescription|药店|药房/.test(recordText)) return false;
  if (/airport|flight|boarding|check.?in|机场|航班|登机|值机/.test(text) && !/airport|flight|boarding|check.?in|机场|航班|登机|值机/.test(recordText)) return false;
  if (/hotel|reservation|room|酒店|入住/.test(text) && !/hotel|reservation|room|酒店|入住/.test(recordText)) return false;
  return true;
}

function shouldIncludeQuestionExportExplanations(message = "") {
  const text = String(message || "");
  if (/(?:no|without|exclude|do\s+not\s+include)\s+explanations?|不要.{0,6}(?:解析|解释|讲解)|不需要.{0,6}(?:解析|解释|讲解)|不含.{0,6}(?:解析|解释|讲解)|不包含.{0,6}(?:解析|解释|讲解)/i.test(text)) {
    return false;
  }
  return /解析|解释|讲解|explanations?|rationale|reasons?|include\s+explanations?|with\s+explanations?/i.test(text);
}

function formatAgentExportQuestion(question, index, settings = {}) {
  if (!question || typeof question !== "object") return "";
  const questionText = sanitizeAgentCardText(question.questionText || question.question || "").trim();
  if (!questionText) return "";
  const options = normalizeAgentStringArray(question.options, 160)
    .map(stripAgentQuestionOptionLabel)
    .slice(0, 8);
  const answerIndex = inferAgentExportQuestionAnswerIndex(question, options);
  const lines = [`${index + 1}. ${questionText}`];
  options.forEach((option, optionIndex) => {
    lines.push(`${agentExportOptionLetter(optionIndex)}. ${option}`);
  });
  if (answerIndex >= 0 && answerIndex < options.length) {
    lines.push(`Correct answer: ${agentExportOptionLetter(answerIndex)}`);
  } else {
    const answerText = sanitizeAgentCardText(question.answer || question.correctAnswerText || "").trim();
    if (answerText) lines.push(`Correct answer: ${answerText}`);
  }
  const explanation = settings.includeExplanations ? sanitizeAgentCardText(question.explanation || "").trim() : "";
  if (explanation) lines.push(`Explanation: ${explanation}`);
  return lines.join("\n");
}

function inferAgentExportQuestionAnswerIndex(question, options = []) {
  const rawIndex = Number(question.correctAnswerIndex ?? question.correctIndex ?? question.answerIndex);
  if (Number.isInteger(rawIndex) && rawIndex >= 0) return rawIndex;
  const rawCorrectAnswer = question.correctAnswer;
  if (Number.isInteger(Number(rawCorrectAnswer)) && String(rawCorrectAnswer).trim() !== "") {
    return Number(rawCorrectAnswer);
  }
  const answerText = sanitizeAgentCardText(rawCorrectAnswer || question.answer || question.correctAnswerText || "").trim();
  if (!answerText || !options.length) return -1;
  const normalizedAnswer = normalizeAgentExportAnswer(answerText);
  const letterIndex = /^[A-H]$/i.test(normalizedAnswer) ? normalizedAnswer.toUpperCase().charCodeAt(0) - 65 : -1;
  if (letterIndex >= 0 && letterIndex < options.length) return letterIndex;
  return options.findIndex((option) => normalizeAgentExportAnswer(option) === normalizedAnswer);
}

function normalizeAgentExportAnswer(value = "") {
  return String(value || "")
    .replace(/^[A-H][).、]\s*/i, "")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
}

function agentExportOptionLetter(index) {
  return String.fromCharCode(65 + index);
}

function extractAgentCardExportBlock(text = "") {
  if (!text) return "";
  const marker = text.lastIndexOf("Agent card:");
  if (marker < 0) return "";
  return text.slice(marker).trim();
}

function isAgentOutputFileRequest(message) {
  const text = normalizeAgentExportRequestText(message);
  const exportText = stripNegatedAgentExportObjectTerms(text);
  if (isStrictRegisterOnlyAgentCardRequest(text) && !hasAgentNamedFileExportCue(exportText)) return false;
  const asksForNamedFile = hasAgentNamedFileExportCue(exportText);
  return asksForNamedFile || isAgentQuestionExportRequest(exportText) || isAgentListeningZipExportRequest(exportText) || isAgentHistoryExportRequest(exportText);
}

function isAgentQuestionExportRequest(message) {
  const text = stripNegatedAgentExportObjectTerms(normalizeAgentExportRequestText(message));
  return hasAgentQuestionExportActionCue(text) &&
    /题目|试题|习题|练习题|选择题|听力题|题组|测验|quiz|questions?|question\s*set|worksheet/i.test(text);
}

function isAgentListeningZipExportRequest(message = "") {
  const text = stripNegatedAgentExportObjectTerms(normalizeAgentExportRequestText(message));
  if (/\bdocx\b|\bword\s*(?:document|doc|file)\b|word\s*文档|文档/i.test(text)) return false;
  const asksExport = hasAgentExportActionCue(text);
  if (!asksExport) return false;
  const explicitZip = /\bzip\b|压缩包|打包|package/i.test(text);
  const listeningContext = /听力|音频|录音|原文|transcript|script|audio|listening|素材|这套|当前|刚才|上一|previous|current|last/i.test(text);
  if (explicitZip && listeningContext) return true;
  if (/\bdocx\b|\bword\b|文档|\btxt\b|markdown|\bmd\b|\bjson\b|\bcsv\b|\bhtml\b/i.test(text)) return false;
  return /(?:听力|listening).{0,16}(?:题|题目|试题|习题|练习题|选择题|题组|测验|quiz|questions?|question\s*set|worksheet|practice|set|素材|音频|录音|原文|transcript|script|audio)|(?:音频|录音|原文|transcript|script|audio).{0,16}(?:听力|listening)|完整.{0,8}(?:素材|听力|package)|素材包/i.test(text);
}

function isAgentHistoryExportRequest(message) {
  const text = stripNegatedAgentExportObjectTerms(normalizeAgentExportRequestText(message));
  if (!hasAgentExportActionCue(text)) return false;
  const referencesHistory = /刚才|上一|上一个|上张|之前|前面|上面|最近|最新|当前|这张|这个|这份|这套|此|已生成|已有|previous|last|above|recent|latest|current|this|that|existing|just\s+(?:created|generated)/i.test(text);
  const referencesExportableObject = /套|卡片|练习|题目|题组|素材|内容|词汇|单词|短语|翻译|对比|区别|原文|compare|comparison|card|practice|exercise|worksheet|quiz|question|set|material|vocab|vocabulary|phrase|translation|transcript|script|grammar|scenario|rubric|outline|prompt/i.test(text);
  return referencesHistory && referencesExportableObject;
}

function normalizeAgentExportRequestText(message = "") {
  return String(message || "").replace(/[_-]+/g, " ");
}

function stripNegatedAgentExportObjectTerms(message = "") {
  return String(message || "")
    .replace(/(?:不要|不需要|别|无|没有).{0,18}(?:题目|试题|习题|练习题|选择题|听力题|题组|测验|文档|文件|压缩包|素材包|quiz|questions?|question\s*set|worksheet|docx|word|txt|markdown|md|json|csv|html|zip)/gi, " ")
    .replace(/\b(?:no|without)\s+(?:extra\s+)?(?:questions?|question\s*set|quiz|worksheet|docx|word\s*(?:document|doc|file)?|file|txt|markdown|md|json|csv|html|zip)\b/gi, " ");
}

function hasAgentExportActionCue(message = "") {
  const text = String(message || "");
  return /导出|保存|下载|分享|发给|发送|做成|整理成|打包|\b(?:export|save|download|share|package)\b/i.test(text) ||
    /\bsend\b.{0,40}(?:docx|word\s*(?:document|doc|file)?|document|file|txt|markdown|md|json|csv|html|zip|questions?|question\s*set|quiz|worksheet|card|practice|exercise|material|content|transcript|script|audio|package)/i.test(text);
}

function hasAgentQuestionExportActionCue(message = "") {
  const text = String(message || "");
  return /导出|保存|下载|分享|发给|发送|做成|整理成|打包|\b(?:export|save|download|share|package)\b/i.test(text) ||
    /\bsend\b.{0,40}(?:questions?|question\s*set|quiz|worksheet|test|exam|题目|题组|试题|习题)|(?:questions?|question\s*set|quiz|worksheet|test|exam|题目|题组|试题|习题).{0,40}\bsend\b/i.test(text);
}

function hasAgentNamedFileExportCue(message = "") {
  const text = String(message || "");
  const fileTerm = /(?:\bdocx\b|\bword\s*(?:document|doc|file)\b|word\s*文档|文档|文件|\btxt\b|markdown|\bmd\b|\bjson\b|\bcsv\b|\bhtml\b|\bzip\b|压缩包)/i;
  return /(?:生成|整理|发送|导出|保存|制作|做成).{0,40}(?:docx|word\s*(?:文档|document|doc|file)|文档|文件|txt|markdown|md|json|csv|html|zip|压缩包)/i.test(text) ||
    /(?:docx|word\s*(?:文档|document|doc|file)|文档|文件|txt|markdown|md|json|csv|html|zip|压缩包).{0,40}(?:生成|整理|发送|导出|保存|制作|做成)/i.test(text) ||
    /\b(?:create|generate|export|save|download|share)\b.{0,40}(?:docx|word\s*(?:document|doc|file)|document|file|txt|markdown|md|json|csv|html|zip)\b/i.test(text) ||
    fileTerm.test(text) && /\bas\s+(?:a\s+)?(?:docx|word\s*(?:document|doc|file)|document|file|txt|markdown|md|json|csv|html|zip)\b/i.test(text) ||
    /\bsend\b.{0,40}(?:docx|word\s*(?:document|doc|file)?|document|file|txt|markdown|md|json|csv|html|zip)\b/i.test(text);
}

function normalizeAgentOutputFile(item = {}) {
  if (!item || typeof item !== "object") return null;
  const content = String(item.content || item.text || item.body || "").trim();
  if (!content) return null;
  const format = sanitizeAgentOutputFormat(item.format || item.type || item.name);
  const name = sanitizeAgentOutputFileName(item.name || `ListenE-Agent.${format}`, format);
  return {
    name,
    format,
    mimeType: outputMimeType(item.mimeType, format),
    content: content.slice(0, 120000)
  };
}

function inferAgentOutputFormat(message) {
  const text = String(message || "").toLowerCase();
  if (/\bzip\b|压缩包/.test(text)) return "zip";
  if (/\bdocx\b|\bword\s*(?:document|doc|file)\b|word\s*文档|文档/.test(text)) return "docx";
  if (/markdown|\bmd\b/.test(text)) return "md";
  if (/json/.test(text)) return "json";
  if (/csv/.test(text)) return "csv";
  if (/html|网页/.test(text)) return "html";
  if (isAgentListeningZipExportRequest(message)) return "zip";
  if (isAgentQuestionExportRequest(message)) return "docx";
  return "txt";
}

function sanitizeAgentOutputFormat(value) {
  const raw = String(value || "").toLowerCase().replace(/^\./, "");
  const ext = raw.includes(".") ? raw.split(".").pop() : raw;
  if (["docx", "txt", "md", "json", "csv", "html", "zip"].includes(ext)) return ext;
  if (ext === "markdown") return "md";
  if (ext === "word") return "docx";
  return "txt";
}

function sanitizeAgentOutputFileName(name, format) {
  const clean = String(name || "ListenE-Agent")
    .replace(/[\\/:*?"<>|]+/g, "_")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 80) || "ListenE-Agent";
  return clean.toLowerCase().endsWith(`.${format}`) ? clean : `${clean}.${format}`;
}

function outputMimeType(mimeType, format) {
  const clean = String(mimeType || "").trim().toLowerCase();
  if (clean) return clean;
  if (format === "docx") return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
  if (format === "json") return "application/json";
  if (format === "zip") return "application/zip";
  if (format === "md") return "text/markdown";
  if (format === "csv") return "text/csv";
  if (format === "html") return "text/html";
  return "text/plain";
}

return {
  sanitizeAgentMissingQuestionExportReply,
  normalizeAgentOutputFiles,
  forceAgentOutputFormatForRequest,
  useHistoryContentForExport,
  useListeningZipContentForExport,
  shouldUseHistoryContentForExport,
  cleanAgentExportDocumentContent,
  extractAgentQuestionOnlyExportContent,
  shouldUseAnswerKeyQuestionExport,
  buildAgentAnswerKeyQuestionExportContent,
  parseAgentQuestionExportBlocks,
  normalizeAgentQuestionExportAnswerKey,
  normalizeAgentExportOptionLetter,
  inferAgentOutputFileFallbackContent,
  extractAgentExportableContentFromRecentMessages,
  extractAgentQuestionExportContent,
  extractAgentQuestionExportContentFromRecentMessages,
  extractAgentLooseHistoryExportContent,
  isLikelyAgentQuestionExportContent,
  isLikelyAgentExportableContent,
  isLikelyAgentPracticeExportContent,
  isAgentQuestionExportSuggestionText,
  agentExportTextMatchesRequest,
  shouldPreferRecentMessagesForQuestionExport,
  buildAgentQuestionExportContentFromRecord,
  buildAgentListeningZipExportContent,
  inferAgentListeningZipAudioFileName,
  buildAgentQuestionExportContentFromRecordForRequest,
  buildAgentQuestionExportContentFromCardSpecForRequest,
  recordMatchesQuestionExportRequest,
  shouldIncludeQuestionExportExplanations,
  formatAgentExportQuestion,
  inferAgentExportQuestionAnswerIndex,
  normalizeAgentExportAnswer,
  agentExportOptionLetter,
  extractAgentCardExportBlock,
  isAgentOutputFileRequest,
  isAgentQuestionExportRequest,
  isAgentListeningZipExportRequest,
  isAgentHistoryExportRequest,
  normalizeAgentExportRequestText,
  stripNegatedAgentExportObjectTerms,
  hasAgentExportActionCue,
  hasAgentQuestionExportActionCue,
  hasAgentNamedFileExportCue,
  normalizeAgentOutputFile,
  inferAgentOutputFormat,
  sanitizeAgentOutputFormat,
  sanitizeAgentOutputFileName,
  outputMimeType,
};
}

module.exports = { createAgentOutputExport };
