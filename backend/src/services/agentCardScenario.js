// 场景/角色扮演卡（Scenario）：场景请求识别、必需范围与构建、角色/情境推断
// 原内联于 mimoText.js，逐字搬出为独立模块；依赖闭合、无循环依赖。

const { inferAgentRequestedOptionCount, placeCorrectAgentQuestionOption } = require("./agentCardReading");
const { compactAgentCardText } = require("./agentCardText");
const { inferScenarioParticipantCountFromItems } = require("./cardItemDerivations");
const { inferAgentRequestedQuestionCount, inferExplicitPhraseTargets, inferRequestedAgentCardItemCounts } = require("./explicitConstraints");
const { inferExplicitScenarioConstraint, inferRequestedDialogueParticipantCount } = require("./intent");
const { inferScenarioSpeakerRoleFromItem, scenarioQuestionBank, scenarioQuestionReferencesUnsupportedDetail, scenarioQuestionSetTitle, scenarioSpeakerRolesFromItems } = require("./scenario");
const { normalizeAgentAnswerLookupText } = require("./textNormalize");
const { agentCardTranslationFallbackTerms, explicitTranslationFallbackTarget } = require("./translation");

function isExplicitScenarioCardRequest(text = "") {
  const value = String(text || "").toLowerCase();
  return /scenario|situational|场景|情景|role.?play|角色扮演|模拟/.test(value) ||
    /only\s+scenario\s+and\s+(?:questions?|question\s*set)|only\s+(?:scenario|role.?play).{0,40}(?:questions?|question\s*set)|只要.{0,20}(?:场景|情景|角色).{0,20}(?:题|题目|题组|问题)/i.test(value);
}

function enforceScenarioQuestionSetCurrentRequestScope(components = [], scope = {}) {
  if (!isDialogueScenarioCardRequest(scope?.messageText || "", scope)) return components;
  if (shouldConstrainHotelCheckInScenario(scope?.messageText || "")) return components;
  const scenarioIndex = components.findIndex((component) => component?.type === "scenario");
  const questionSetIndex = components.findIndex((component) => component?.type === "question_set");
  if (scenarioIndex < 0 || questionSetIndex < 0) return components;
  const scenario = components[scenarioIndex];
  const questionSet = components[questionSetIndex];
  const questions = Array.isArray(questionSet.questions) ? questionSet.questions : [];
  const requestedCount = (scope.requestedCounts && scope.requestedCounts.question_set) ||
    inferAgentRequestedQuestionCount(scope.messageText || "") ||
    Math.max(2, questions.length || 3);
  const optionCount = inferAgentRequestedOptionCount(scope.messageText || "") || 4;
  const wrongQuestionCount = questions.length !== requestedCount;
  const wrongOptionCount = questions.some((question) => !Array.isArray(question?.options) || question.options.length !== optionCount);
  const unsupportedQuestion = questions.some((question) =>
    scenarioQuestionReferencesUnsupportedDetail(question, scenario, scope)
  );
  if (!wrongQuestionCount && !wrongOptionCount && !unsupportedQuestion) {
    return orderScenarioQuestionSetComponents(components);
  }
  const next = [...components];
  next[questionSetIndex] = buildScenarioQuestionSetComponent(scope.messageText || "", scenario, requestedCount, optionCount);
  return orderScenarioQuestionSetComponents(next);
}

function orderScenarioQuestionSetComponents(components = []) {
  const header = components.find((component) => component?.type === "header");
  const questionSet = components.find((component) => component?.type === "question_set");
  const scenario = components.find((component) => component?.type === "scenario");
  if (!questionSet || !scenario) return components;
  const others = components.filter((component) =>
    component?.type !== "header" &&
    component?.type !== "question_set" &&
    component?.type !== "scenario"
  );
  return [
    ...(header ? [header] : []),
    questionSet,
    scenario,
    ...others
  ];
}

function buildScenarioQuestionSetComponent(message = "", scenario = {}, requestedCount = 3, optionCount = 4) {
  const topic = inferScenarioQuestionTopic(message, scenario);
  const participantCount = inferRequestedDialogueParticipantCount(message) ||
    inferScenarioParticipantCountFromItems(scenario) ||
    2;
  const roles = agentCardScenarioRoles(`${message} ${scenario?.title || ""} ${scenario?.text || ""}`, participantCount);
  const bank = scenarioQuestionBank(topic, roles, participantCount);
  const safeQuestionCount = Math.max(1, Math.min(Number(requestedCount) || 3, 8));
  const safeOptionCount = Math.max(2, Math.min(Number(optionCount) || 4, 6));
  const questions = Array.from({ length: safeQuestionCount }, (_, index) => {
    const source = bank[index % bank.length];
    const options = [source.answer, ...source.distractors].slice(0, safeOptionCount);
    const balanced = placeCorrectAgentQuestionOption(options, 0, index % options.length);
    return {
      questionText: source.questionText,
      options: balanced.options,
      correctAnswer: balanced.correctAnswer,
      explanation: ""
    };
  });
  return {
    type: "question_set",
    title: scenarioQuestionSetTitle(topic),
    text: `${questions.length} scenario questions`,
    questions
  };
}

function inferScenarioQuestionTopic(message = "", scenario = {}) {
  const text = `${message || ""} ${scenario?.title || ""} ${scenario?.text || ""} ${Array.isArray(scenario?.items) ? scenario.items.join(" ") : ""}`.toLowerCase();
  if (/pharmacy|medicine|medication|prescription|sore|headache|药店|药房|药/.test(text)) return "pharmacy";
  if (/hotel|check.?in|reservation|酒店|入住/.test(text)) return "hotel_check_in";
  if (/airport|flight|check.?in|boarding|luggage|机场|航班|值机|登机/.test(text)) return "airport";
  if (/restaurant|waiter|waitress|menu|餐厅|饭店|服务员/.test(text)) return "restaurant";
  return "generic";
}

function enforceScenarioQuestionSetScope(component = {}, scope = {}) {
  if (component?.type !== "question_set") return component;
  if (!shouldConstrainHotelCheckInScenario(scope?.messageText || "")) return component;
  const requestedCount = (scope.requestedCounts && scope.requestedCounts.question_set) ||
    inferAgentRequestedQuestionCount(scope?.messageText || "") ||
    3;
  const optionCount = inferAgentRequestedOptionCount(scope?.messageText || "") || 4;
  const questions = Array.isArray(component.questions) ? component.questions : [];
  const wrongCount = questions.length !== requestedCount;
  const wrongOptions = questions.some((question) => !Array.isArray(question?.options) || question.options.length !== optionCount);
  const offTopic = questions.some(isOffTopicHotelCheckInQuestion);
  const lacksCheckInFocus = !agentQuestionSetTextMatchesHotelCheckIn(questions);
  if (!wrongCount && !wrongOptions && !offTopic && !lacksCheckInFocus) return component;
  return buildHotelCheckInQuestionSetComponent(scope?.messageText || "", component);
}

function isOffTopicHotelCheckInQuestion(question = {}) {
  const text = JSON.stringify(question || {}).toLowerCase();
  return /breakfast|checkout|check out|pool|gym|airport|flight|gate|boarding|luggage|nights?|mr\.|mrs\.|restaurant/.test(text);
}

function agentQuestionSetTextMatchesHotelCheckIn(questions = []) {
  const text = JSON.stringify(questions || {}).toLowerCase();
  return /reservation|booking reference|id|deposit|key card|room number/.test(text);
}

function buildHotelCheckInQuestionSetComponent(text = "", original = {}) {
  const questionCount = inferAgentRequestedQuestionCount(text) || 3;
  const optionCount = inferAgentRequestedOptionCount(text) || 4;
  const bank = [
    {
      questionText: "What should the guest give first at check-in?",
      answer: "A reservation name",
      distractors: ["A coffee order", "A city map", "A shopping list", "A postcard"]
    },
    {
      questionText: "What may the receptionist ask to see?",
      answer: "An ID",
      distractors: ["A postcard", "A city map", "A pencil", "A shopping list"]
    },
    {
      questionText: "What should the guest confirm before going to the room?",
      answer: "The key card and room number",
      distractors: ["The weather forecast", "A taxi color", "A postcard", "A pencil"]
    },
    {
      questionText: "Why might the receptionist mention a deposit?",
      answer: "To explain a temporary room charge",
      distractors: ["To sell a city tour", "To choose a taxi color", "To change the weather", "To print a postcard"]
    }
  ];
  const questions = Array.from({ length: Math.max(2, Math.min(questionCount, 8)) }, (_, index) => {
    const source = bank[index % bank.length];
    const options = [source.answer, ...source.distractors].slice(0, Math.max(2, Math.min(optionCount, 6)));
    const balanced = placeCorrectAgentQuestionOption(options, 0, index % options.length);
    return {
      questionText: source.questionText,
      options: balanced.options,
      correctAnswer: balanced.correctAnswer,
      explanation: ""
    };
  });
  return {
    ...original,
    type: "question_set",
    title: "Hotel Check-in Questions",
    text: `${questions.length} check-in questions`,
    questions,
    explanation: ""
  };
}

function inferScenarioPromptCount(message = "") {
  return inferExplicitScenarioConstraint(message)?.promptCount || null;
}

function isFinalEdPronunciationContext(context = "") {
  return /(?:final\s*-?ed|\b-ed\b|ed\s+endings?|past\s+tense\s+endings?)/i.test(String(context || ""));
}

function isThPronunciationContext(context = "") {
  return /(?:\bth\b|\/θ\/|\/ð\/|θ|ð|voiced\s+vs|voiceless\s+vs|tongue.{0,40}teeth|teeth.{0,40}tongue)/i.test(String(context || ""));
}

function enforceRequestedScenarioComponentCount(component = {}, requestedCount = 0, scope = {}) {
  const currentItems = Array.isArray(component.items) ? component.items : [];
  const currentPairs = Array.isArray(component.pairs) ? component.pairs : [];
  const context = [
    scope.messageText,
    component.title,
    component.text,
    ...currentItems,
    ...currentPairs.flatMap((pair) => [pair?.left, pair?.right, pair?.hint])
  ].filter(Boolean).join(" ").toLowerCase();
  const dialogueScenarioFallbackItems = agentCardScenarioFallbackItems(context, scope);
  const isDialogueScenario = isDialogueScenarioCardRequest(context, scope);
  const scopedItems = shouldConstrainHotelCheckInScenario(context)
    ? currentItems.filter(isHotelCheckInScenarioRow)
    : currentItems;
  if (shouldConstrainHotelCheckInScenario(context) && scopedItems.length >= requestedCount) {
    return {
      ...component,
      items: scopedItems.slice(0, requestedCount)
    };
  }
  const mergedItems = uniqueScenarioCardTexts(
    isDialogueScenario
      ? [...dialogueScenarioFallbackItems, ...scopedItems]
      : [...scopedItems, ...dialogueScenarioFallbackItems],
    180
  )
    .slice(0, requestedCount);
  if (!mergedItems.length) return component;
  if (requestedCount && mergedItems.length < requestedCount) return component;
  return {
    ...component,
    items: mergedItems
  };
}

function enforceScenarioParticipantCountScope(component = {}, scope = {}) {
  if (component?.type !== "scenario") return component;
  const explicitParticipantCount = Number(scope?.scenarioConstraint?.participantCount || inferRequestedDialogueParticipantCount(scope?.messageText || "") || 0);
  if (!explicitParticipantCount) return component;
  const participantCount = explicitParticipantCount;
  if (!participantCount || !isDialogueScenarioCardRequest(`${component.title || ""} ${component.text || ""} ${(component.items || []).join(" ")}`, scope)) return component;
  const currentItems = Array.isArray(component.items) ? component.items : [];
  const shouldConstrainHotel = shouldConstrainHotelCheckInScenario(`${scope?.messageText || ""} ${component.title || ""} ${component.text || ""} ${currentItems.join(" ")}`);
  const hasOffTopicHotelRows = shouldConstrainHotel && currentItems.some((item) => !isHotelCheckInScenarioRow(item));
  const currentRoles = scenarioSpeakerRolesFromItems(currentItems);
  if (currentRoles.size > participantCount) {
    const trimmed = currentItems.filter(isScenarioSpeakerItem).slice(0, participantCount);
    if (scenarioSpeakerRolesFromItems(trimmed).size === participantCount) {
      return {
        ...component,
        items: trimmed,
        pairs: []
      };
    }
  }
  if (currentRoles.size >= participantCount && !hasOffTopicHotelRows) return component;
  const promptCount = Math.max(
    Number(scope?.scenarioConstraint?.promptCount || scope?.requestedCounts?.scenario || 0),
    currentItems.length,
    participantCount
  );
  const fallback = agentCardDialogueScenarioFallbackItems(
    `${scope?.messageText || ""} ${component.title || ""} ${component.text || ""}`,
    participantCount,
    promptCount
  );
  const reusableItems = currentItems.filter((item) => {
    if (!isScenarioSpeakerItem(item)) return false;
    return !shouldConstrainHotel || isHotelCheckInScenarioRow(item);
  });
  const merged = uniqueScenarioCardTexts([...fallback, ...reusableItems], 180).slice(0, promptCount);
  if (scenarioSpeakerRolesFromItems(merged).size < participantCount) {
    return component;
  }
  return {
    ...component,
    items: merged,
    pairs: []
  };
}

function isScenarioSpeakerItem(value = "") {
  return Boolean(inferScenarioSpeakerRoleFromItem(value));
}

function agentCardScenarioFallbackItems(context = "", scope = {}) {
  const constraint = scope?.scenarioConstraint || {};
  const participantCount = Number(constraint.participantCount || inferRequestedDialogueParticipantCount(scope?.messageText || context) || 0);
  const promptCount = Number(constraint.promptCount || scope?.requestedCounts?.scenario || 0);
  if (promptCount > 0 && isDialogueScenarioCardRequest(context, scope)) {
    return agentCardDialogueScenarioFallbackItems(context, participantCount || 2, promptCount);
  }
  return agentCardListFallbackItems("scenario", context);
}

function isDialogueScenarioCardRequest(context = "", scope = {}) {
  return /dialogue|role.?play|conversation|对话|角色|酒店入住|办理入住|餐厅点餐|餐厅预订|面试|问诊|客服|银行开户/.test(`${scope?.messageText || ""} ${context}`.toLowerCase()) ||
    Boolean(scope?.fuzzyScenario);
}

function agentCardDialogueScenarioFallbackItems(context = "", participantCount = 2, promptCount = 0) {
  const roles = agentCardScenarioRoles(context, participantCount);
  const tasks = agentCardScenarioTasks(context);
  const count = Math.max(promptCount || roles.length, roles.length);
  return Array.from({ length: count }, (_, index) => {
    const role = roles[index % roles.length] || `Speaker ${index + 1}`;
    const task = tasks[index % tasks.length] || "Use one natural sentence to keep the conversation moving.";
    return `${role}: ${task}`;
  }).slice(0, Math.max(promptCount || roles.length, roles.length));
}

function agentCardScenarioRoles(context = "", participantCount = 2) {
  const text = String(context || "").toLowerCase();
  let roles;
  if (/hotel|check.?in|reservation|酒店|入住/.test(text)) {
    roles = ["Guest", "Receptionist", "Manager", "Porter"];
  } else if (/airport|flight|check.?in|boarding|luggage|机场|航班|值机|登机/.test(text)) {
    roles = ["Passenger", "Check-in Agent", "Travel Companion", "Gate Staff"];
  } else if (/pharmacy|medicine|medication|prescription|药店|药房|药/.test(text)) {
    roles = ["Customer", "Pharmacist", "Friend", "Assistant"];
  } else if (/restaurant|waiter|waitress|menu|餐厅|饭店|服务员/.test(text)) {
    roles = ["Customer 1", "Waiter", "Customer 2", "Host"];
  } else {
    roles = ["Speaker A", "Speaker B", "Speaker C", "Speaker D"];
  }
  const count = Math.max(1, Math.min(Number(participantCount) || roles.length, roles.length));
  return roles.slice(0, count);
}

function agentCardScenarioTasks(context = "") {
  const text = String(context || "").toLowerCase();
  if (/hotel|check.?in|reservation|酒店|入住/.test(text)) {
    return [
      "Say you have a reservation and give your name.",
      "Ask for the booking reference and confirm the room type.",
      "Ask to see an ID and explain the deposit clearly.",
      "Confirm the key card and room number before ending check-in."
    ];
  }
  if (/airport|flight|check.?in|boarding|luggage|机场|航班|值机|登机/.test(text)) {
    return [
      "Give the flight destination and booking reference.",
      "Ask about luggage and confirm the seat preference.",
      "Ask one clarifying question about the gate or boarding time.",
      "Respond politely and close the check-in conversation."
    ];
  }
  if (/pharmacy|medicine|medication|prescription|药店|药房|药/.test(text)) {
    return [
      "Say what medicine or help you need in one simple sentence.",
      "Ask one short follow-up question about symptoms or dosage.",
      "Suggest a safe next step and keep the language easy.",
      "Close the conversation politely after confirming the solution."
    ];
  }
  return [
    "Start the conversation with a clear request.",
    "Ask one natural follow-up question.",
    "Respond to the problem and suggest a solution.",
    "Close the conversation politely."
  ];
}

function uniqueScenarioCardTexts(items = [], maxLength = 180) {
  const seen = new Set();
  return items
    .map((item) => compactAgentCardText(item, maxLength))
    .filter(Boolean)
    .filter((item) => {
      const key = normalizeScenarioCardLookupText(item);
      if (!key || seen.has(key)) return false;
      seen.add(key);
      return true;
    });
}

function normalizeScenarioCardLookupText(value = "") {
  return normalizeAgentAnswerLookupText(value)
    .replace(/\[(?:your\s+)?name\]/gi, "[name]")
    .replace(/\s+/g, " ")
    .trim();
}

function shouldConstrainHotelCheckInScenario(context = "") {
  return /hotel|酒店/.test(context) && /check.?in|入住|reservation/.test(context);
}

function isHotelCheckInScenarioRow(value = "") {
  const text = String(value || "").toLowerCase();
  if (/wake[- ]?up|room service|laundry|breakfast|checkout|check out|pool|gym|restaurant|luggage|suitcase|bellboy|porter|叫醒|客房服务/.test(text)) return false;
  return /reservation|booking reference|booking|check.?in|front desk|reception|room with|room type|room number|key card|\bid\b|deposit|under the name|预订|入住|前台/.test(text);
}

function agentCardListFallbackItems(type = "", context = "") {
  if (type === "translation") {
    return agentCardTranslationFallbackTerms(context).map((term) => {
      const target = explicitTranslationFallbackTarget(term);
      return target ? `${term} | ${target}` : term;
    });
  }
  if (type === "register") {
    if (/work|office|client|meeting|request|职场|工作|请求/.test(context)) {
      return [
        "casual: Can you send me that file? | formal: Could you please send me the file?",
        "casual: I need this done now. | formal: Would it be possible to complete this at your earliest convenience?",
        "casual: Can you check this? | formal: Could you please review this when you have time?",
        "casual: Tell me when you are free. | formal: Please let me know when you are available."
      ];
    }
    return [
      "casual: Can you help me? | formal: Could you please assist me?",
      "casual: I need this soon. | formal: I would appreciate receiving this soon.",
      "casual: Tell me what you think. | formal: Please share your feedback when convenient."
    ];
  }
  if (type === "scenario") {
    if (/hotel|check.?in|reservation|酒店|入住/.test(context)) {
      return [
        "I have a reservation under the name [your name].",
        "Could I check in, please?",
        "Could I get a room with a view?"
      ];
    }
    return [
      "Could you help me with this?",
      "I would like to ask about this.",
      "Could you repeat that, please?"
    ];
  }
  if (type === "listening_cue") {
    return [
      "however - contrast",
      "therefore - result",
      "because - reason",
      "although - contrast"
    ];
  }
  if (type === "mistake_pattern") {
    return [
      "wrong verb form - use the correct tense form",
      "missing auxiliary - add the needed helping verb",
      "word order error - place words in the correct order"
    ];
  }
  if (type === "ethics") {
    return [
      "What would you do in this situation, and why?",
      "Who is affected by this choice, and how?",
      "Is there a fairer option that helps everyone?",
      "What value matters most here: honesty, kindness, or fairness?"
    ];
  }
  if (type === "debate") {
    return [
      "For: it can bring clear, practical benefits",
      "Against: it may cause problems that are hard to fix",
      "Your view: pick a side and give two reasons with examples"
    ];
  }
  if (type === "error_hunt") {
    return [
      "go -> went",
      "buyed -> bought",
      "much people -> many people"
    ];
  }
  if (type === "storytelling") {
    return [
      "use the words: suitcase, stranger, promise",
      "include a problem and how it is solved",
      "end with a surprise"
    ];
  }
  if (type === "paraphrase") {
    return [
      "The trip was canceled because of the bad weather.",
      "Because the weather was bad, we canceled the trip.",
      "We called off the trip due to the poor weather."
    ];
  }
  if (type === "correction") {
    return [
      "wrong form -> correct form",
      "missing word -> add the required word",
      "word order -> reorder the sentence"
    ];
  }
  if (type === "compare") {
    return [
      "use case - when to choose each form",
      "structure - how each form is built",
      "meaning - the key difference"
    ];
  }
  if (type === "phrase") {
    const explicitPhrases = inferExplicitPhraseTargets(context);
    if (explicitPhrases.length) {
      return [
        ...explicitPhrases.map((phrase) => `${phrase} - meaning and natural use`),
        "use it in a short sentence",
        "notice the object or preposition",
        "compare it with a similar phrase"
      ];
    }
    if (/interview|job\s*interview|面试/.test(context)) {
      return [
        "introduce yourself briefly",
        "talk about your strengths",
        "describe your work experience",
        "explain why you want the job",
        "ask about the next step",
        "thank the interviewer"
      ];
    }
    if (/airport|flight|boarding|luggage|机场|航班|登机|行李/.test(context)) {
      return [
        "check in at the counter",
        "show your boarding pass",
        "go through security",
        "find the departure gate",
        "pick up your luggage",
        "ask about a delayed flight",
        "change your seat",
        "board the plane"
      ];
    }
    return [
      "make a request",
      "ask for help",
      "follow up",
      "get back to someone",
      "check the details",
      "confirm the time",
      "solve a problem",
      "make a polite suggestion"
    ];
  }
  if (type === "pronunciation") {
    if (isFinalEdPronunciationContext(context)) {
      return [
        "Use /t/ after voiceless sounds.",
        "Use /d/ after voiced sounds and vowel sounds.",
        "Use /ɪd/ after /t/ or /d/ sounds."
      ];
    }
    if (isThPronunciationContext(context)) {
      return [
        "Place the tip of your tongue lightly between your upper and lower teeth.",
        "For unvoiced /θ/, push air out without vibrating your vocal cords.",
        "For voiced /ð/, keep the same tongue position and add gentle vocal cord vibration."
      ];
    }
    if (/presentation|stress|重音/.test(context)) {
      return [
        "Put the main stress on the strongest syllable.",
        "Keep weaker syllables shorter and lighter.",
        "Use a clear pitch rise on the stressed syllable."
      ];
    }
    return [
      "Mark the main stress before practicing.",
      "Keep unstressed sounds short and light.",
      "Repeat slowly before speaking at normal speed."
    ];
  }
  return [];
}

function buildFallbackInterviewScenarioComponent(message = "") {
  const count = inferScenarioPromptCount(message) || 4;
  return enforceRequestedScenarioComponentCount({
    type: "scenario",
    title: "面试实用表达",
    items: [
      "Interviewer: Tell me about yourself. | Candidate: I have two years of experience in customer support.",
      "Interviewer: Why do you want this role? | Candidate: I want to use my communication skills in a practical team.",
      "Interviewer: Can you describe a challenge? | Candidate: I handled a tight deadline by breaking the task into steps.",
      "Candidate: Could you tell me more about the team and daily work?"
    ]
  }, count, {
    messageText: message,
    scenarioConstraint: inferExplicitScenarioConstraint(message),
    requestedCounts: inferRequestedAgentCardItemCounts(message),
    fuzzyScenario: true
  });
}

module.exports = {
  isExplicitScenarioCardRequest,
  enforceScenarioQuestionSetCurrentRequestScope,
  orderScenarioQuestionSetComponents,
  buildScenarioQuestionSetComponent,
  inferScenarioQuestionTopic,
  enforceScenarioQuestionSetScope,
  isOffTopicHotelCheckInQuestion,
  agentQuestionSetTextMatchesHotelCheckIn,
  buildHotelCheckInQuestionSetComponent,
  inferScenarioPromptCount,
  isFinalEdPronunciationContext,
  isThPronunciationContext,
  enforceRequestedScenarioComponentCount,
  enforceScenarioParticipantCountScope,
  isScenarioSpeakerItem,
  agentCardScenarioFallbackItems,
  isDialogueScenarioCardRequest,
  agentCardDialogueScenarioFallbackItems,
  agentCardScenarioRoles,
  agentCardScenarioTasks,
  uniqueScenarioCardTexts,
  normalizeScenarioCardLookupText,
  shouldConstrainHotelCheckInScenario,
  isHotelCheckInScenarioRow,
  agentCardListFallbackItems,
  buildFallbackInterviewScenarioComponent,
};
