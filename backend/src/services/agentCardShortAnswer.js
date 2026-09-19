// 简答/写作输入卡（Short Answer）：请求识别、必需范围与兜底构建、写作输入题、各类范围裁剪与答案泄漏防护、从消息推断答案/提示
// 原内联于 mimoText.js，逐字搬出为独立模块；依赖闭合、无循环依赖。

const { compactAgentCardText, escapeRegExp, sanitizeAgentCardText } = require("./agentCardText");
const { neutralizeNoAudioCueText, normalizeNoAudioShortAnswerTitle } = require("./cardTitles");
const { cleanExplicitShortAnswer, inferCorrectionSourceSentenceFromMessage, isEditableCorrectionShortAnswerRequest } = require("./correction");
const { normalizeShortAnswerLeakText } = require("./textNormalize");

function isShortAnswerOnlyAgentCardRequest(text = "") {
  const value = String(text || "").toLowerCase();
  return /dictation|typed\s*answer|type\s+the\s+sentence|free\s*response|short\s*answer|text\s*input|input\s*answer|默写|输入答案|打字|短答|听写/i.test(value) &&
    /only\s+(?:one\s+)?(?:short\s*answer|typed\s*answer|text\s*input|input\s*answer|dictation)|only\s+.*(?:short\s*answer|typed\s*answer|text\s*input|input\s*answer|dictation)|no\s+multiple\s*choice|without\s+multiple\s*choice|no\s+mcq|without\s+mcq|不要.{0,8}(?:选择题|多选|单选)|不需要.{0,8}(?:选择题|多选|单选)/i.test(value);
}

function isFuzzyShortAnswerPracticeRequest(text = "") {
  const value = String(text || "").toLowerCase();
  if (isShortAnswerNegatedRequest(value)) return false;
  return /dictation|typed\s*answer|type\s+the\s+sentence|free\s*response|short\s*answer|text\s*input|input\s*answer|默写|输入答案|打字|短答|听写/i.test(value) &&
    /练|练习|来|给我|帮我|一句|简单|practice|drill/i.test(value);
}

function isShortAnswerNegatedRequest(text = "") {
  const value = String(text || "").toLowerCase();
  return /no\s+short\s*answer|without\s+short\s*answer|no\s+typed\s*answer|without\s+typed\s*answer|no\s+dictation|without\s+dictation|不要.{0,8}(?:短答|输入答案|默写|听写|打字)|不需要.{0,8}(?:短答|输入答案|默写|听写|打字)|无(?:短答|输入答案|默写|听写)/i.test(value);
}

function buildWritingInputShortAnswerComponent(message = "", existing = {}) {
  const answer = compactAgentCardText(existing?.answer || existing?.correctAnswer || "Type your essay draft here.", 220);
  return {
    ...(existing || {}),
    type: "short_answer",
    title: existing?.title || "Writing Box",
    text: compactAgentCardText(existing?.text || writingInputPromptText(message), 260),
    answer,
    correctAnswer: answer,
    explanation: ""
  };
}

function writingInputPromptText(message = "") {
  const task = inferWritingTaskDetails(message);
  if (task) return `Type your ${task.wordRange ? `${task.wordRange} ` : ""}${task.genre} draft here.`;
  return /作文|写作|essay|writing/i.test(String(message || ""))
    ? "在这里输入你的作文草稿。"
    : "Type your draft here.";
}

function inferWritingTaskDetails(message = "") {
  const raw = sanitizeAgentCardText(message);
  const lower = raw.toLowerCase();
  if (!/writing|essay|composition|email|letter|作文|写作|邮件|信/i.test(raw)) return null;
  const wordRange = inferWritingWordRange(raw);
  const genre = /email|邮件/i.test(raw)
    ? "email"
    : /letter|信/i.test(raw)
      ? "letter"
      : "short essay";
  const recipient = /friend|朋友/i.test(raw)
    ? "a friend"
    : /teacher|老师|教师/i.test(raw)
      ? "your teacher"
      : /hotel|front desk|酒店|前台/i.test(raw)
        ? "the hotel front desk"
        : "";
  const topic = inferWritingTaskTopic(lower, genre);
  const requirements = inferWritingTaskRequirements(raw);
  return { wordRange, genre, recipient, topic, requirements };
}

function inferWritingWordRange(message = "") {
  const match = /(\d{2,3})\s*(?:-|–|—|到|至)\s*(\d{2,3})\s*(?:words?|词|字)?/i.exec(message);
  return match ? `${match[1]}-${match[2]} word` : "";
}

function inferWritingTaskTopic(lower = "", genre = "short essay") {
  if (/weekend|周末/.test(lower) && /plan|计划|安排/.test(lower)) {
    return "about missing the weekend plan";
  }
  if (/missed class|homework|缺课|没上课|作业/.test(lower)) {
    return "about missing class and asking for the homework";
  }
  if (/booking|reservation|预订|预定/.test(lower)) {
    return "about changing a booking";
  }
  if (/online shopping|网购/.test(lower)) {
    return "about online shopping";
  }
  return genre === "email" ? "about the requested situation" : "on the requested topic";
}

function inferWritingTaskRequirements(message = "") {
  const lower = message.toLowerCase();
  const requirements = [];
  const add = (text) => {
    if (!requirements.includes(text)) requirements.push(text);
  };
  if (/apolog|sorry|道歉|抱歉/.test(lower)) add("an apology");
  if (/reason|because|why|原因|理由|说明/.test(lower)) add("the reason");
  if (/repair|make up|reschedule|new arrangement|补救|改期|重新安排|安排/.test(lower)) add("a repair arrangement");
  if (/polite|courteous|礼貌|客气/.test(lower)) add("a polite closing");
  if (/ask|request|homework|询问|请求|作业/.test(lower)) add("a clear request");
  return requirements;
}

function isShortAnswerRequiredScope(scope = {}) {
  return scope.allowed instanceof Set &&
    scope.allowed.has("short_answer") &&
    !scope.multiQuestionChoice &&
    !scope.multiFillBlankQuestions &&
    !scope.choiceConstraint &&
    !scope.clozeConstraint?.strict &&
    /dictation|typed\s*answer|type\s+the\s+sentence|free\s*response|short\s*answer|text\s*input|input\s*answer|默写|输入答案|打字|短答|听写/i.test(scope.messageText || "");
}

function buildRequiredShortAnswerComponent(existing = {}, scope = {}) {
  const explicitAnswer = inferShortAnswerFromMessage(scope?.messageText || "");
  const existingAnswer = existing.answer || existing.correctAnswer || "";
  const fallbackAnswer = fallbackShortAnswerForMessage(scope?.messageText || "");
  const answer = explicitAnswer || existingAnswer || fallbackAnswer || "";
  const explicitPrompt = inferShortAnswerPromptFromMessage(scope?.messageText || "", answer);
  const base = {
    ...existing,
    type: "short_answer",
    title: existing.title || "Type the Sentence",
    text: existing.text || explicitPrompt || "Type the answer.",
    answer,
    correctAnswer: answer,
    explanation: shouldSuppressAgentCardAnswerExplanation(existing, scope) ? "" : (existing.explanation || "")
  };
  return enforceShortAnswerNoAnswerLeakComponentScope(enforceShortAnswerCurrentRequestScope(base, scope));
}

function fallbackShortAnswerForMessage(message = "") {
  const text = String(message || "").toLowerCase();
  if (/simple|easy|简单|基础|a1|a2|听写|默写/.test(text)) return "I need a little help today.";
  return "";
}

function enforceNoAudioShortAnswerComponentScope(component = {}, scope = {}) {
  if (component?.type !== "short_answer" || !isNoAudioShortAnswerScope(scope)) return component;
  return {
    ...component,
    title: normalizeNoAudioShortAnswerTitle(component.title),
    text: neutralizeNoAudioCueText(component.text, "Type the answer.")
  };
}

function enforceShortAnswerCurrentRequestScope(component = {}, scope = {}) {
  if (component?.type !== "short_answer") return component;
  const explicitAnswer = inferShortAnswerFromMessage(scope?.messageText || "");
  if (!explicitAnswer) return component;
  const explicitPrompt = inferShortAnswerPromptFromMessage(scope?.messageText || "", explicitAnswer);
  const currentText = component.text || "";
  const text = textContainsShortAnswer(currentText, explicitAnswer)
    ? shortAnswerPromptWithoutAnswer(currentText)
    : shouldReplaceGenericShortAnswerPrompt(currentText, explicitPrompt)
      ? explicitPrompt
      : currentText;
  return {
    ...component,
    answer: explicitAnswer,
    correctAnswer: explicitAnswer,
    text
  };
}

function enforceShortAnswerNoAnswerLeakComponentScope(component = {}) {
  if (component?.type !== "short_answer") return component;
  const answer = compactAgentCardText(component.answer || component.correctAnswer || "", 220);
  if (!isLeakableShortAnswer(answer)) return component;
  const text = compactAgentCardText(component.text || "", 360);
  if (!textContainsShortAnswer(text, answer)) return component;
  return {
    ...component,
    text: shortAnswerPromptWithoutAnswer(text)
  };
}

function isLeakableShortAnswer(answer = "") {
  const text = compactAgentCardText(answer, 220);
  return text.length >= 10 && text.split(/\s+/).length >= 3;
}

function textContainsShortAnswer(text = "", answer = "") {
  const normalizedText = normalizeShortAnswerLeakText(text);
  const normalizedAnswer = normalizeShortAnswerLeakText(answer);
  return normalizedAnswer.length >= 10 && normalizedText.includes(normalizedAnswer);
}

function shortAnswerPromptWithoutAnswer(text = "") {
  return /sentence|句子/i.test(text) ? "Type the target sentence from your request." : "Type the answer.";
}

function findAgentCardShortAnswerAnswer(components = []) {
  const component = Array.isArray(components)
    ? components.find((item) => item?.type === "short_answer" && (item.answer || item.correctAnswer))
    : null;
  return compactAgentCardText(component?.answer || component?.correctAnswer || "", 220);
}

function visibleShortAnswerTextWithoutAnswerLeak(value = "", answer = "", fallback = "") {
  if (!textContainsShortAnswer(value, answer)) return value;
  return fallback;
}

function isNoAudioShortAnswerScope(scope = {}, presentComponents = null) {
  if (!scope?.suppressAudioCueText) return false;
  if (presentComponents instanceof Set) return presentComponents.has("short_answer");
  return scope.allowed instanceof Set && scope.allowed.has("short_answer");
}

function shouldSuppressAgentCardAnswerExplanation(component = {}, scope = {}) {
  if (!["cloze", "short_answer", "sentence_builder", "ordering"].includes(component?.type)) return false;
  if (!component.explanation) return false;
  const excluded = scope.excluded instanceof Set ? scope.excluded : new Set(scope.excluded || []);
  return scope.suppressExtraSuggestions || scope.suppressAnswerExplanation || excluded.has("grammar") || excluded.has("correction");
}

function inferShortAnswerFromMessage(message = "") {
  const text = String(message || "").replace(/\s+/g, " ").trim();
  const labeledAnswer = inferLabeledShortAnswerFromText(text);
  if (labeledAnswer) return labeledAnswer;
  const correctionAnswer = inferCorrectedShortAnswerFromMessage(text);
  if (correctionAnswer) return correctionAnswer;
  const chineseDictationMatch = /(?:听写|默写|输入答案|短答|打字)\s*(?:一句|句子|这句|内容)?\s*[:：]\s*(.+?)(?=(?:[。！？；;]\s*)?(?:只要|不要|不需要|无|别|only\b|no\s+|without\b|$))/i.exec(text);
  if (chineseDictationMatch?.[1]) return cleanExplicitShortAnswer(chineseDictationMatch[1]);
  const chineseSentenceIsMatch = /(?:\u53e5\u5b50|\u76ee\u6807\u53e5)\s*(?:\u662f|\u4e3a|[:：])\s*(.+?)(?=\s*(?:[\u3002\uff01\uff1f\uff1b;]\s*)?(?:\u53ea\u8981|\u4e0d\u8981|\u4e0d\u9700\u8981|\u65e0|\u522b|only\b|no\s+|without\b|$))/i.exec(text);
  if (chineseSentenceIsMatch?.[1]) return cleanExplicitShortAnswer(chineseSentenceIsMatch[1]);
  const explicitSentenceMatch = /\b(?:for\s+the\s+sentence|sentence)\s*[:：]\s*(.+?)(?=\s+(?:user\s+should|you\s+should|type|only|no|without|with\s+no|不要|不需要|无)\b|$)/i.exec(text);
  if (explicitSentenceMatch?.[1]) return cleanExplicitShortAnswer(explicitSentenceMatch[1]);
  const typeThisSentenceMatch = /\btype\s+(?:this|the)\s+sentence(?:\s+exactly)?\s*[:：]?\s*(.+?)(?=\s+answer\b|\s+(?:only|no|without|with\s+no)\b|$)/i.exec(text);
  if (typeThisSentenceMatch?.[1]) return cleanExplicitShortAnswer(typeThisSentenceMatch[1]);
  const sentenceMatch = /\btype\s+the\s+sentence\s+(.+?)(?=\s+answer\b|\s+(?:only|no|without|with\s+no)\b|$)/i.exec(text);
  if (sentenceMatch?.[1]) return cleanExplicitShortAnswer(sentenceMatch[1]);
  return "";
}

function inferCorrectedShortAnswerFromMessage(text = "") {
  if (!isEditableCorrectionShortAnswerRequest(text)) return "";
  const original = inferCorrectionSourceSentenceFromMessage(text);
  if (!original) return "";
  return correctSimpleA2Sentence(original) || "";
}

function correctSimpleA2Sentence(sentence = "") {
  const value = cleanExplicitShortAnswer(sentence);
  if (!value) return "";
  const thirdPersonMatch = /^(she|he|it)\s+([a-z]+)\b(.*)$/i.exec(value);
  if (!thirdPersonMatch) return "";
  const subject = thirdPersonMatch[1];
  const verb = thirdPersonMatch[2];
  const rest = thirdPersonMatch[3] || "";
  const correctedVerb = thirdPersonPresentSimpleVerb(verb);
  if (!correctedVerb || correctedVerb.toLowerCase() === verb.toLowerCase()) return "";
  const corrected = `${subject} ${correctedVerb}${rest}`.trim();
  return /[.!?]$/.test(corrected) ? corrected : `${corrected}.`;
}

function thirdPersonPresentSimpleVerb(verb = "") {
  const lower = String(verb || "").toLowerCase();
  if (!/^[a-z]+$/.test(lower)) return "";
  const irregular = {
    be: "is",
    have: "has",
    do: "does",
    go: "goes"
  };
  if (irregular[lower]) return irregular[lower];
  if (/(?:s|sh|ch|x|z|o)$/.test(lower)) return `${lower}es`;
  if (/[^aeiou]y$/.test(lower)) return `${lower.slice(0, -1)}ies`;
  return `${lower}s`;
}

function inferShortAnswerPromptFromMessage(message = "", answer = "") {
  const text = String(message || "").replace(/\s+/g, " ").trim();
  let prompt = "";
  const labelPattern = /\b(prompt|question|instruction|题目|提示)\b(\s*[:：]?)/ig;
  for (const match of text.matchAll(labelPattern)) {
    const rest = text.slice((match.index || 0) + match[0].length).trim();
    const stop = /\s+(?:correct\s+answer|answer|only|no|without|with\s+no|不要|不需要|无)\b/i.exec(rest);
    const rawCandidate = compactAgentCardText(rest.slice(0, stop ? stop.index : rest.length), 220);
    const candidate = textContainsShortAnswer(rawCandidate, answer)
      ? shortAnswerPromptCandidateWithoutAnswer(rawCandidate, answer)
      : rawCandidate;
    const hasLabelPunctuation = /[:：]/.test(match[2] || "");
    if (!candidate || textContainsShortAnswer(candidate, answer)) continue;
    if (
      hasLabelPunctuation ||
      /^(?:type|write|enter|answer|translate|choose|fill|what|where|when|why|how|which|who)\b/i.test(candidate) ||
      /^(?:输入|写|回答|选择|填|什么|哪里|何时|为什么|如何|哪个|谁)/.test(candidate)
    ) {
      prompt = candidate;
    }
  }
  if (!prompt) {
    if (/type\s+the\s+sentence|\bsentence\s+(?:correct\s+)?answer\b/i.test(text)) prompt = "Type this sentence.";
    else if (/dictation|默写|听写/i.test(text)) prompt = "Type the target sentence.";
    else if (/short\s*answer|typed\s*answer|input\s*answer|输入答案|短答/i.test(text)) prompt = "Type the answer.";
  }
  if (!prompt || textContainsShortAnswer(prompt, answer)) return shortAnswerPromptWithoutAnswer(prompt || "sentence");
  return prompt;
}

function shortAnswerPromptCandidateWithoutAnswer(candidate = "", answer = "") {
  const rawCandidate = compactAgentCardText(candidate, 220);
  const rawAnswer = compactAgentCardText(answer, 220);
  if (!rawCandidate || !rawAnswer) return "";
  const answerPattern = new RegExp(escapeRegExp(rawAnswer).replace(/\s+/g, "\\s+"), "i");
  const stripped = rawCandidate
    .replace(answerPattern, "")
    .replace(/\s+([,.!?;:])/g, "$1")
    .replace(/[:：]\s*$/, "")
    .replace(/\s+/g, " ")
    .trim();
  if (!/^(?:type|write|enter|answer|translate|choose|fill|输入|写|回答|选择|填)\b/i.test(stripped)) return "";
  return /[.!?。！？]$/.test(stripped) ? stripped : `${stripped}.`;
}

function shouldReplaceGenericShortAnswerPrompt(currentText = "", explicitPrompt = "") {
  if (!explicitPrompt) return false;
  const normalized = normalizeShortAnswerLeakText(currentText);
  if (!normalized) return true;
  return [
    "type the answer",
    "type your answer",
    "enter the answer",
    "input your answer",
    "type the target sentence",
    "type the target sentence from your request",
    "type the sentence you hear",
    "type what"
  ].includes(normalized);
}

function inferLabeledShortAnswerFromText(text = "") {
  let answer = "";
  const labelPattern = /\b(?:correct\s+answer|answer)\b\s*[:：]?/ig;
  for (const match of String(text || "").matchAll(labelPattern)) {
    const before = text.slice(0, match.index || 0).trim();
    if (/\b(?:short|typed|input|free|text)\s*$/i.test(before)) continue;
    const rest = text.slice((match.index || 0) + match[0].length);
    const stop = /\s+(?:only|no|without|with\s+no|不要|不需要|无)\b/i.exec(rest);
    const candidate = compactAgentCardText(rest.slice(0, stop ? stop.index : rest.length), 220);
    if (!candidate || /^(?:no|only|without|with\s+no|不要|不需要|无)\b/i.test(candidate)) continue;
    answer = candidate;
  }
  return answer;
}

module.exports = {
  isShortAnswerOnlyAgentCardRequest,
  isFuzzyShortAnswerPracticeRequest,
  isShortAnswerNegatedRequest,
  buildWritingInputShortAnswerComponent,
  writingInputPromptText,
  inferWritingTaskDetails,
  inferWritingWordRange,
  inferWritingTaskTopic,
  inferWritingTaskRequirements,
  isShortAnswerRequiredScope,
  buildRequiredShortAnswerComponent,
  fallbackShortAnswerForMessage,
  enforceNoAudioShortAnswerComponentScope,
  enforceShortAnswerCurrentRequestScope,
  enforceShortAnswerNoAnswerLeakComponentScope,
  isLeakableShortAnswer,
  textContainsShortAnswer,
  shortAnswerPromptWithoutAnswer,
  findAgentCardShortAnswerAnswer,
  visibleShortAnswerTextWithoutAnswerLeak,
  isNoAudioShortAnswerScope,
  shouldSuppressAgentCardAnswerExplanation,
  inferShortAnswerFromMessage,
  inferCorrectedShortAnswerFromMessage,
  correctSimpleA2Sentence,
  thirdPersonPresentSimpleVerb,
  inferShortAnswerPromptFromMessage,
  shortAnswerPromptCandidateWithoutAnswer,
  shouldReplaceGenericShortAnswerPrompt,
  inferLabeledShortAnswerFromText,
};
